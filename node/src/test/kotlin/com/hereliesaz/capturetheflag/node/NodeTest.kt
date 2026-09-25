package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.*
import com.hereliesaz.capturetheflag.data.DemoCityDirectory
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.rules.GameRules
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PANEL_TURNS = 6

class NodeTest {
    @Test fun eventsSignAndVerify() {
        val k = Keys.generate()
        val e = k.sign(1, "hello", listOf(listOf("c", "nola")))
        assertEquals(64, e.pubkey.length); assertEquals(128, e.sig.length)
        assertTrue(e.valid())
        assertFalse(e.copy(content = "hullo").valid())
        assertFalse(e.copy(pubkey = Keys.generate().pub).valid())
        assertEquals(k.pub, Keys(k.secret).pub)
    }

    @Test fun filtersMatchLikeNip01() {
        val k = Keys.generate()
        val e = k.sign(33000, "{}", listOf(listOf("g", "g1")), at = 100)
        assertTrue(Filter(kinds = setOf(33000), tags = mapOf("g" to setOf("g1"))).matches(e))
        assertFalse(Filter(tags = mapOf("g" to setOf("g2"))).matches(e))
        assertFalse(Filter(since = 101).matches(e))
        assertTrue(Filter(authors = setOf(k.pub), until = 100).matches(e))
    }

    @Test fun relaySpeaksNip01() = testApplication {
        val store = EventStore()
        install(WebSockets)
        routing { relay(store) }
        val client = createClient { install(ClientWebSockets) }
        val k = Keys.generate()
        val stored = k.sign(1, "old news", listOf(listOf("c", "nola")))
        store.add(stored)
        client.webSocket("/") {
            send(Frame.Text(buildJsonArray {
                add(JsonPrimitive("REQ")); add(JsonPrimitive("s1"))
                add(buildJsonObject { putJsonArray("#c") { add(JsonPrimitive("nola")) } })
            }.toString()))
            val first = Nostr.json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonArray
            assertEquals("EVENT", first[0].jsonPrimitive.content)
            assertEquals("EOSE", Nostr.json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonArray[0].jsonPrimitive.content)

            val fresh = k.sign(1, "breaking", listOf(listOf("c", "nola")))
            send(Frame.Text(buildJsonArray { add(JsonPrimitive("EVENT")); add(Nostr.json.encodeToJsonElement(Event.serializer(), fresh)) }.toString()))
            val replies = (1..2).map { Nostr.json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonArray }
            assertTrue(replies.any { it[0].jsonPrimitive.content == "OK" && it[2].jsonPrimitive.content == "true" })
            assertTrue(replies.any { it[0].jsonPrimitive.content == "EVENT" && it[1].jsonPrimitive.content == "s1" })

            val forged = fresh.copy(content = "fake")
            send(Frame.Text(buildJsonArray { add(JsonPrimitive("EVENT")); add(Nostr.json.encodeToJsonElement(Event.serializer(), forged)) }.toString()))
            val no = Nostr.json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonArray
            assertEquals("false", no[2].jsonPrimitive.content)
        }
    }

    /** A player's action: plaintext when opening a city, sealed to the [panel] inside a game. */
    private fun action(k: Keys, a: Action, game: String? = null, city: String? = null, panel: List<String>? = null, at: Long? = null): Event {
        val body = Nostr.json.encodeToString(Action.serializer(), a)
        val tags = listOfNotNull(game?.let { listOf("g", it) }, city?.let { listOf("c", it) })
        val content = panel?.let { Sealed.forPanel(body, k, it) } ?: body
        return if (at != null) k.sign(Kinds.ACTION, content, tags, at) else k.sign(Kinds.ACTION, content, tags)
    }

    @Test fun refereeRunsARoundFromSignedActionsAndTheLogRebuildsIt() = runTest {
        var now = 1_000_000L
        val store = EventStore()
        val node = Keys.generate()
        val referee = Referee(node, store, DemoCityDirectory) { now }
        val players = (1..4).map { Keys.generate() }

        action(players[0], Action.Open("New Orleans"), city = "new orleans", at = now / 1000).let { store.add(it); referee.accept(it) }
        val game = referee.games.keys.single()
        val opened = store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN, Kinds.SEED_REVEAL))))
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.pubkey == node.pub })

        players.forEachIndexed { i, p ->
            val e = action(p, Action.Join("P$i", "selfie$i"), game, panel = listOf(node.pub))
            store.add(e); referee.accept(e)
        }
        // An action with a bad signature never reaches the store, so never reaches a batch.
        // One sent in the clear is refused: in a game, only the referee reads what players send.
        val clear = action(Keys.generate(), Action.Join("Loud", "selfie"), game)
        store.add(clear); referee.accept(clear)
        referee.flush()
        assertEquals(4, referee.games.getValue(game).signups.size)
        val first = store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).single()
        assertEquals("unreadable", Nostr.json.decodeFromString(Outcome.serializer(), first.content).verdicts[clear.id])

        now += GameRules.SIGNUP_WINDOW
        referee.flush()
        val g = referee.games.getValue(game)
        assertIs<GamePhase.FlagPlacement>(g.phase)
        assertEquals(2, g.players.values.count { it.role == Role.CAPTAIN })

        val outcomes = store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME), authors = setOf(node.pub))))
        assertEquals(2, outcomes.size)
        assertTrue(store.query(listOf(Filter(kinds = setOf(Kinds.RADIO)))).isNotEmpty())

        // A fresh referee with only the log reaches the same game: same teams, same captains.
        val again = Referee(node, store, DemoCityDirectory) { now }
        again.restore()
        val rebuilt = again.games.getValue(game)
        assertEquals(g.players.mapValues { it.value.team to it.value.role }, rebuilt.players.mapValues { it.value.team to it.value.role })
        assertEquals(g.phase, rebuilt.phase)
    }

    @Test fun secretsAreCommittedDuringTheRoundAndRevealedAfter() = runTest {
        var now = 1_000_000L
        val store = EventStore()
        val node = Keys.generate()
        val referee = Referee(node, store, DemoCityDirectory) { now }
        val players = (1..4).map { Keys.generate() }
        action(players[0], Action.Open("New Orleans"), city = "new orleans", at = now / 1000).let { store.add(it); referee.accept(it) }
        val game = referee.games.keys.single()
        suspend fun send(e: Event) { store.add(e); referee.accept(e) }
        players.forEachIndexed { i, p -> send(action(p, Action.Join("P$i", "s$i"), game, panel = listOf(node.pub))) }
        val k = ByteArray(32) { 7 }.toHex()
        send(players[0].sign(Kinds.BLE_KEY, Sealed.forPanel(k, players[0], listOf(node.pub)), listOf(listOf("g", game))))
        referee.flush()

        assertTrue(store.query(listOf(Filter())).none { k in it.content }, "the key never appears in the clear")
        val commit = Nostr.json.decodeFromString(Commit.serializer(), store.query(listOf(Filter(kinds = setOf(Kinds.COMMIT)))).single().content)
        assertEquals("ble" to players[0].pub, commit.what to commit.who)
        assertTrue(store.query(listOf(Filter(kinds = setOf(Kinds.REVEAL)))).isEmpty())

        // Nobody places a flag: the round ends at the placement deadline, and the secrets come out.
        now += GameRules.SIGNUP_WINDOW; referee.flush()
        now += 3 * GameRules.HOUR; referee.flush()
        assertIs<GamePhase.Ended>(referee.games.getValue(game).phase)
        val revealed = Nostr.json.decodeFromString(Reveal.serializer(), store.query(listOf(Filter(kinds = setOf(Kinds.REVEAL)))).single().content).secrets.single()
        assertEquals(k, revealed.preimage)
        assertEquals(commit.commitment, revealed.commitment, "the reveal matches what was committed")
    }

    /** Five referees on one network. Referees in [down] neither hear nor speak. */
    private class Network(n: Int, var now: Long = 1_000_000L) {
        val store = EventStore()
        val keys = List(n) { Keys.generate() }
        val referees = keys.map { Referee(it, store, DemoCityDirectory, keys.map(Keys::pub)) { now } }
        val down = mutableSetOf<Int>()

        suspend fun send(e: Event) { store.add(e); pump() }

        /** Delivers the whole log to every live referee (they ignore repeats) until it stops growing. */
        suspend fun pump() {
            var size = -1
            while (store.size() != size) {
                size = store.size()
                val all = store.query(listOf(Filter())).sortedWith(compareBy({ it.created_at }, { it.id }))
                all.forEach { e -> referees.forEachIndexed { i, r -> if (i !in down) r.accept(e) } }
            }
        }

        suspend fun flush() { referees.forEachIndexed { i, r -> if (i !in down) r.flush() }; pump() }
        fun live() = referees.filterIndexed { i, _ -> i !in down }
    }

    @Test fun fiveRefereesAgreeAndThreeAreEnough() = runTest {
        val net = Network(5)
        val players = (1..4).map { Keys.generate() }
        net.send(action(players[0], Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.referees[0].games.keys.single()
        val panel = net.referees[0].panelOf(game)!!
        assertEquals(net.keys.map { it.pub }.toSet(), panel.toSet())
        // Five commitments, then five reveals: every referee reaches the same seed.
        assertEquals(5, net.store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).size)
        assertEquals(5, net.store.query(listOf(Filter(kinds = setOf(Kinds.SEED_REVEAL)))).size)

        players.forEachIndexed { i, p -> net.send(action(p, Action.Join("P$i", "s$i"), game, panel = panel)) }
        net.flush()
        net.referees.forEach { assertEquals(4, it.games.getValue(game).signups.size) }
        assertEquals(5, net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).size, "every referee signs the outcome")
        assertEquals(1, net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).map { it.content }.distinct().size, "and they all say the same")

        // Two referees go dark, including whoever leads next. The other three carry on.
        net.down += listOf(0, 1)
        net.now += GameRules.SIGNUP_WINDOW
        repeat(PANEL_TURNS) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        val teams = net.live().map { r -> r.games.getValue(game).let { g -> g.phase::class to g.players.mapValues { it.value.team to it.value.role } } }
        assertIs<GamePhase.FlagPlacement>(net.referees[2].games.getValue(game).phase)
        assertEquals(1, teams.distinct().size, "the three agree on teams and captains")

        // A third goes dark: no majority, no batch, no change.
        net.down += 2
        val before = net.referees[3].games.getValue(game)
        val late = action(players[1], Action.CoCaptains(emptySet()), game, panel = panel)
        net.send(late)
        repeat(PANEL_TURNS) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        assertEquals(before, net.referees[3].games.getValue(game))
        assertTrue(net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).none { late.id in it.content })

        // One comes back: it catches up from the log and play resumes.
        net.down -= 2
        net.pump()
        repeat(PANEL_TURNS) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        assertTrue(net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).any { late.id in it.content })
        assertTrue(net.live().all { it.equivocators.isEmpty() })
    }

    @Test fun aRefereeWhoSignsTwoBatchesIsCaught() = runTest {
        val net = Network(5)
        val p = Keys.generate()
        net.send(action(p, Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.referees[0].games.keys.single()
        val liar = net.keys[4]
        net.send(liar.sign(Kinds.BATCH, Nostr.json.encodeToString(Batch.serializer(), Batch(1, net.now, emptyList())), listOf(listOf("g", game))))
        net.send(liar.sign(Kinds.BATCH, Nostr.json.encodeToString(Batch.serializer(), Batch(1, net.now + 1, emptyList())), listOf(listOf("g", game))))
        assertTrue(net.referees.take(4).all { liar.pub in it.equivocators })
    }

    /** A photo that passes every check, taken at [at] facing north at [t]. */
    private fun shot(at: GeoPoint, t: Long) = Evidence(
        "img-$t", at.lat, at.lng, t, Position(at.lat, at.lng, t, 5.0), 0.0, Evidence.Pose(0.0, 0.0, 0.0, t),
    )

    @Test fun aDisputedFlagRunIsReviewedBySoftwareReportedToLeadersAndAppealable() = runTest {
        val net = Network(5)
        val players = (1..4).map { Keys.generate() }
        val keyOf = players.associateBy { it.pub }
        net.send(action(players[0], Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.referees[0].games.keys.single()
        val panel = net.referees[0].panelOf(game)!!
        suspend fun act(k: Keys, a: Action) { net.send(action(k, a, game, panel = panel, at = net.now / 1000)); net.flush() }
        fun g() = net.referees[0].games.getValue(game)
        players.forEachIndexed { i, p -> act(p, Action.Join("P$i", "s$i")) }
        net.now += GameRules.SIGNUP_WINDOW; net.flush()
        assertIs<GamePhase.FlagPlacement>(g().phase)

        // Each captain places a flag and a jail on their own side.
        for (team in Team.entries) {
            val cap = g().players.values.first { it.team == team && it.role == Role.CAPTAIN }
            val home = g().territory.cells.map { it.center }.filter { g().territory.ownerOf(it) == team }
            val flag = home.first()
            val jail = home.first { it.distanceTo(flag) > GameRules.JAIL_MIN_FROM_FLAG_M + 100 }
            act(keyOf.getValue(cap.id), Action.PlaceFlag("Statue", FlagVenueKind.PUBLIC_SPACE, "1 St", flag.lat, flag.lng, shot(flag, net.now)))
            act(keyOf.getValue(cap.id), Action.PlaceJail("Jail", "2 St", jail.lat, jail.lng, shot(jail, net.now)))
        }
        assertIs<GamePhase.Active>(g().phase)

        // Each player's view: their own flag, never the enemy's; onlookers see neither.
        val someone = g().players.values.first()
        val sealed = net.store.query(listOf(Filter(kinds = setOf(Kinds.VIEW), tags = mapOf("p" to setOf(someone.id))))).last()
        val view = Views.decode(Nip44.open(sealed.content, keyOf.getValue(someone.id), sealed.pubkey))
        assertEquals(setOf(someone.team), view.flags.keys)
        assertEquals(setOf(someone.id), view.lastFix.keys + someone.id)
        val public = Views.decode(net.store.query(listOf(Filter(kinds = setOf(Kinds.PUBLIC_VIEW)))).last().content)
        assertTrue(public.flags.isEmpty() && public.lastFix.isEmpty())
        assertEquals(2, public.jails.size, "jails are public")

        // A flag run: live 60 m out, walk in, the winning frame with the challenge, frames until the footage closes.
        val runner = g().players.values.first { !it.isLeader }
        val target = g().flags.getValue(runner.team.opponent).location
        val start = GeoPoint(target.lat + 60 / 111_320.0, target.lng)
        act(keyOf.getValue(runner.id), Action.GoLive("s1", StreamPurpose.CAPTURE, Position(start.lat, start.lng, net.now, 5.0)))
        assertNotNull(g().streams.getValue("s1").challenge)
        net.now += 5_000
        act(keyOf.getValue(runner.id), Action.Frame("s1", Position(target.lat, target.lng, net.now, 5.0), "chunk-${net.now}"))
        act(keyOf.getValue(runner.id), Action.EndStream("s1", shot(target, net.now)))
        assertNotNull(g().streams.getValue("s1").qualifiedAt)
        while (g().streams.getValue("s1").open) {
            net.now += 5_000
            act(keyOf.getValue(runner.id), Action.Frame("s1", Position(target.lat, target.lng, net.now, 5.0), "chunk-${net.now}"))
        }
        assertTrue(g().streams.getValue("s1").pending)

        // A defender disputes. Every referee reviews, votes, and seals its report to all four... leaders only.
        val defender = g().players.values.first { it.team == runner.team.opponent }
        act(keyOf.getValue(defender.id), Action.Dispute("s1", "Challenge not said on camera"))
        net.flush()
        val rulings = net.store.query(listOf(Filter(kinds = setOf(Kinds.RULING))))
        assertEquals(5, rulings.size)
        val leaders = g().players.values.filter { it.isLeader }
        val reports = net.store.query(listOf(Filter(kinds = setOf(Kinds.REPORT))))
        assertEquals(5 * leaders.size, reports.size)
        val captain = leaders.first { it.team == defender.team && it.role == Role.CAPTAIN }
        val mine = reports.filter { it.tag("p") == captain.id }.map { r ->
            Nostr.json.decodeFromString(ReviewReport.serializer(), Nip44.open(r.content, keyOf.getValue(captain.id), r.pubkey))
        }
        assertEquals(panel.toSet(), mine.map { it.referee }.toSet(), "the captain sees how each referee ruled")
        assertTrue(mine.all { it.upheld })
        assertTrue(mine.first().checks.any { it.result == ReviewReport.Result.NOT_RUN }, "and what couldn't be checked, and why")
        assertTrue(Reviews.discrepancies(mine).isEmpty())
        val outsider = players.first { k -> g().players.getValue(k.pub).let { !it.isLeader } }
        assertFails { Nip44.open(reports.first().content, outsider, reports.first().pubkey) }
        assertNotNull(g().streams.getValue("s1").ruling, "the majority has ruled")
        assertIs<GamePhase.Active>(g().phase, "but it waits for the appeal window")

        // The defenders' captain appeals: a second review, final at once.
        act(keyOf.getValue(captain.id), Action.Appeal("s1"))
        net.flush()
        val over = g().phase
        assertIs<GamePhase.Ended>(over)
        assertEquals(com.hereliesaz.capturetheflag.model.Outcome.FlagCaptured(runner.team, runner.id), over.outcome)
        assertEquals(1, net.referees.map { it.games.getValue(game).phase }.distinct().size, "all five agree")
        val paid = net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).flatMap { Nostr.json.decodeFromString(Outcome.serializer(), it.content).awards }
        assertTrue(paid.any { it.user == captain.id && it.reason == "Captain" && it.points == 300L }, "the losing captain is paid the same")
    }

    @Test fun discrepanciesShowWhereRefereesDisagree() {
        fun r(ref: String, result: ReviewReport.Result) = ReviewReport("s", 0, ref, result != ReviewReport.Result.FAIL, listOf(
            ReviewReport.Check("Unbroken stream", ReviewReport.Result.PASS, "ok"),
            ReviewReport.Check("Matches the registration photo", result, "detail from $ref"),
        ))
        val d = Reviews.discrepancies(listOf(r("a", ReviewReport.Result.PASS), r("b", ReviewReport.Result.FAIL), r("c", ReviewReport.Result.NOT_RUN)))
        assertEquals(listOf("Matches the registration photo"), d.map { it.check })
        assertEquals("detail from b", d.single().findings.getValue("b").detail)
    }

    @Test fun nip44MatchesTheOfficialVectors() {
        // github.com/paulmillr/nip44 nip44.vectors.json
        val v = Nostr.json.parseToJsonElement(javaClass.getResource("/nip44.vectors.json")!!.readText()).jsonObject["v2"]!!.jsonObject
        val valid = v["valid"]!!.jsonObject
        fun kotlinx.serialization.json.JsonElement.s(key: String) = jsonObject[key]!!.jsonPrimitive.content
        valid["get_conversation_key"]!!.jsonArray.forEach {
            assertEquals(it.s("conversation_key"), Nip44.conversationKey(it.s("sec1").hex(), it.s("pub2")).toHex())
        }
        valid["calc_padded_len"]!!.jsonArray.forEach {
            assertEquals(it.jsonArray[1].jsonPrimitive.content.toInt(), Nip44.paddedLength(it.jsonArray[0].jsonPrimitive.content.toInt()))
        }
        valid["encrypt_decrypt"]!!.jsonArray.forEach {
            val ck = Nip44.conversationKey(it.s("sec1").hex(), Keys(it.s("sec2").hex()).pub)
            assertEquals(it.s("conversation_key"), ck.toHex())
            assertEquals(it.s("payload"), Nip44.encrypt(it.s("plaintext"), ck, it.s("nonce").hex()))
            assertEquals(it.s("plaintext"), Nip44.decrypt(it.s("payload"), ck))
        }
        val invalid = v["invalid"]!!.jsonObject
        invalid["decrypt"]!!.jsonArray.forEach {
            assertFails(it.s("note")) { Nip44.decrypt(it.s("payload"), it.s("conversation_key").hex()) }
        }
        invalid["get_conversation_key"]!!.jsonArray.forEach {
            assertFails(it.s("note")) { Nip44.conversationKey(it.s("sec1").hex(), it.s("pub2")) }
        }
    }

    @Test fun sealedPayloadsOpenOnlyForTheirRecipient() {
        val (a, b, eve) = List(3) { Keys.generate() }
        val sealed = Nip44.seal("ping 6: the corner of Frenchmen", a, b.pub)
        assertEquals("ping 6: the corner of Frenchmen", Nip44.open(sealed, b, a.pub))
        assertFails { Nip44.open(sealed, eve, a.pub) }
        assertFalse(sealed == Nip44.seal("ping 6: the corner of Frenchmen", a, b.pub), "fresh nonce every time")
    }

    @Test fun bleTokensRotateAndResolve() {
        val k = ByteArray(32) { it.toByte() }
        assertEquals(Ble.token(k, 7), Ble.token(k, 7))
        assertFalse(Ble.token(k, 7) == Ble.token(k, 8))
        assertEquals(16, Ble.token(k, 7).length)
    }

    @Test fun archiveMirrorsEventsAndCachesSurveys() = runTest {
        val root = createTempDirectory("archive").toFile()
        val archive = Archive(root, Archive.Sync.EXTERNAL)
        val k = Keys.generate()
        val e = k.sign(Kinds.ACTION, "{}", listOf(listOf("g", "g1")))
        archive.record(e)
        archive.record(e.copy(content = "tampered")) // mirrored, but won't verify
        assertEquals(listOf(e), archive.replay())

        var surveys = 0
        val counting = object : com.hereliesaz.capturetheflag.data.CityDirectory {
            override suspend fun resolve(cityName: String) = DemoCityDirectory.resolve(cityName).also { surveys++ }
        }
        val first = SurveyCache(archive, counting).resolve("New Orleans")
        val second = SurveyCache(Archive(root, Archive.Sync.EXTERNAL), counting).resolve("New Orleans")
        assertEquals(1, surveys)
        assertNotNull(second)
        assertEquals(first!!.second.size, second.second.size)
        assertTrue(File(root, "cities/new-orleans.json").exists())
    }
}
