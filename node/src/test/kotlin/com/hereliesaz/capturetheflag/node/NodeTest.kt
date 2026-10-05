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
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.put
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import com.hereliesaz.capturetheflag.rules.Verdict
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
import kotlin.test.assertNull
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

    @Test fun aSilentRefereeIsReplacedAndItsReplacementCatchesUp() = runTest {
        val net = Network(6)
        val players = (1..4).map { Keys.generate() }
        net.send(action(players[0], Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.referees.firstNotNullOf { r -> r.games.keys.singleOrNull() }
        val panel = net.referees.firstNotNullOf { it.panelOf(game) }
        val spare = net.keys.indexOfFirst { it.pub !in panel }
        assertEquals(5, panel.size)
        assertNull(net.referees[spare].panelOf(game), "the sixth isn't on it")

        // A referee can't play in its own game.
        val sneaky = net.keys.first { it.pub in panel }
        net.send(action(sneaky, Action.Join("Ref", "s"), game, panel = panel)); net.flush()
        players.forEachIndexed { i, p -> net.send(action(p, Action.Join("P$i", "s$i"), game, panel = panel)) }
        net.flush()
        net.now += GameRules.SIGNUP_WINDOW
        repeat(PANEL_TURNS) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        val on = net.referees.filter { it.panelOf(game) != null }
        assertTrue(on.all { sneaky.pub !in it.games.getValue(game).players }, "the referee's join was refused")
        assertIs<GamePhase.FlagPlacement>(on.first().games.getValue(game).phase)

        // One goes silent for good. After ten minutes the rest vote it out; the seed draws the sixth in.
        val silent = net.keys.indexOfFirst { it.pub in panel }
        net.down += silent
        net.now += Referee.SILENT_MS
        repeat(PANEL_TURNS * 2) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        val now = net.referees[spare].panelOf(game)
        assertNotNull(now, "the sixth was handed the round")
        assertTrue(net.keys[spare].pub in now && net.keys[silent].pub !in now, "now=${now.map { k -> net.keys.indexOfFirst { it.pub == k } }} spare=$spare silent=$silent")
        net.live().filter { it.panelOf(game) != null }.forEach { assertEquals(now, it.panelOf(game)) }
        assertEquals(1, net.store.query(listOf(Filter(kinds = setOf(Kinds.PANEL)))).map { it.content }.distinct().size)

        // It replays to the same game, and plays on with the others.
        assertEquals(net.referees[(0 until 6).first { it != silent && it != spare }].games.getValue(game), net.referees[spare].games.getValue(game))
        val cap = net.referees[spare].games.getValue(game).players.values.first { it.role == Role.CAPTAIN }
        val later = action(players.first { it.pub == cap.id }, Action.CoCaptains(emptySet()), game, panel = now, at = net.now / 1000)
        net.send(later)
        repeat(PANEL_TURNS) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        val outcomes = net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).filter { later.id in it.content }
        assertTrue(net.keys[spare].pub in outcomes.map { it.pubkey }, "the newcomer ruled on it")
        assertEquals(1, outcomes.map { it.content }.distinct().size, "and agreed")
    }

    @Test fun aRefereeThatNeverShowsUpIsLeftOutOfTheSeed() = runTest {
        val net = Network(5)
        net.down += 4
        val players = (1..4).map { Keys.generate() }
        net.send(action(players[0], Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).first().tag("g")!!
        assertTrue(net.live().none { game in it.games }, "four commitments of five: no seed yet")
        assertTrue(net.store.query(listOf(Filter(kinds = setOf(Kinds.SEED_REVEAL)))).isEmpty(), "and nobody reveals early")

        // Five minutes on, the four vote the fifth out of the seed and draw it among themselves.
        net.now += Referee.SEED_WAIT_MS
        net.flush()
        assertEquals(4, net.store.query(listOf(Filter(kinds = setOf(Kinds.SEED_DROP)))).size)
        assertEquals(1, net.live().map { it.games.getValue(game) }.distinct().size, "the four agree on the round")

        // And play goes on without it.
        val panel = net.referees[0].panelOf(game)!!
        players.forEachIndexed { i, p -> net.send(action(p, Action.Join("P$i", "s$i"), game, panel = panel)) }
        net.flush()
        net.live().forEach { assertEquals(4, it.games.getValue(game).signups.size) }

        // It comes back late with its commitment and reveal: too late, the seed stands.
        net.down -= 4
        net.pump(); net.flush()
        assertEquals(1, net.referees.map { it.games.getValue(game) }.distinct().size, "it replays to the same round")
    }

    /** A round on [net] with four players, flags and jails placed: play is live. */
    private class Live(val net: Network, val players: List<Keys>, val game: String, val panel: List<String>) {
        val keyOf = players.associateBy { it.pub }
        fun g() = net.referees.first { game in it.games }.games.getValue(game)
        suspend fun send(e: Event) = net.send(e)
        suspend fun sealed(k: Keys, kind: Int, body: String) =
            net.send(k.sign(kind, Sealed.forPanel(body, k, panel), listOf(listOf("g", game)), net.now / 1000))
        suspend fun act(k: Keys, a: Action) { sealed(k, Kinds.ACTION, Nostr.json.encodeToString(Action.serializer(), a)); net.flush() }
        suspend fun at(k: Keys, p: GeoPoint) { sealed(k, Kinds.POSITION, Nostr.json.encodeToString(Position.serializer(), Position(p.lat, p.lng, net.now, 5.0))); net.flush() }
        fun ground(team: Team) = g().territory.cells.map { it.center }.filter { g().territory.ownerOf(it) == team }
    }

    private suspend fun live(net: Network, players: List<Keys> = (1..4).map { Keys.generate() }): Live {
        net.send(action(players[0], Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).first { it.tag("c") == "new orleans" }.tag("g")!!
        val l = Live(net, players, game, net.referees.firstNotNullOf { it.panelOf(game) })
        players.forEachIndexed { i, p -> l.act(p, Action.Join("P$i", "s$i")) }
        net.now += GameRules.SIGNUP_WINDOW; net.flush()
        for (team in Team.entries) {
            val cap = l.g().players.values.first { it.team == team && it.role == Role.CAPTAIN }
            val home = l.ground(team)
            val flag = home.first()
            val jail = home.first { it.distanceTo(flag) > GameRules.JAIL_MIN_FROM_FLAG_M + 100 }
            l.act(l.keyOf.getValue(cap.id), Action.PlaceFlag("Statue", FlagVenueKind.PUBLIC_SPACE, "1 St", flag.lat, flag.lng, shot(flag, net.now)))
            l.act(l.keyOf.getValue(cap.id), Action.PlaceJail("Jail", "2 St", jail.lat, jail.lng, shot(jail, net.now)))
        }
        assertIs<GamePhase.Active>(l.g().phase)
        return l
    }

    @Test fun teamChatToSomeoneCutOffWaitsUntilTheyreHome() = runTest {
        val l = live(Network(5))
        val (a, b) = l.g().players.values.groupBy { it.team }.values.first { it.size >= 2 }.take(2).map { l.keyOf.getValue(it.id) }
        val team = l.g().players.getValue(a.pub).team
        // B on enemy ground: cut off.
        l.net.now += 1_000; l.at(b, l.ground(team.opponent).first())
        assertTrue(com.hereliesaz.capturetheflag.chat.ChatAccess.blackedOut(b.pub, l.g()))

        // A writes to the team room, through the referees.
        val inner = a.sign(Kinds.TEAM_CHAT, """{"channel":"team:${l.game}:${team.name}","text":"they moved the flag"}""")
        l.sealed(a, Kinds.CHAT, Nostr.json.encodeToString(Event.serializer(), inner))
        suspend fun deliveredTo(k: Keys) = l.net.store.query(listOf(Filter(kinds = setOf(Kinds.TEAM_CHAT), tags = mapOf("p" to setOf(k.pub)))))
        assertTrue(deliveredTo(a).isNotEmpty(), "A gets their own copy")
        assertTrue(deliveredTo(b).isEmpty(), "B, cut off, gets nothing yet")

        // B makes it home: the message is waiting, signed by A.
        l.net.now += 1_000; l.at(b, l.ground(team).first())
        val got = deliveredTo(b)
        assertTrue(got.isNotEmpty(), "delivered once B is home")
        val opened = Nostr.json.decodeFromString(Event.serializer(), Nip44.open(got.first().content, b, got.first().pubkey))
        assertEquals(inner.id, opened.id)
    }

    @Test fun secretHighlightsAreHeldUntilTheRoundEnds() = runTest {
        val net = Network(5)
        val players = (1..4).map { Keys.generate() }
        // Seasoned players: an earlier round, signed by a majority of the same trusted referees, made them high level.
        val before = net.now / 1000 - 100
        val panel = net.keys.map { it.pub }
        net.keys.forEach { k -> net.store.add(k.sign(Kinds.GAME_OPEN, Nostr.json.encodeToString(GameOpen.serializer(), GameOpen("old", "old town", 0, panel, "x")), listOf(listOf("g", "g-old")), before)) }
        val past = Nostr.json.encodeToString(Outcome.serializer(), Outcome(1, emptyMap(), players.map { Outcome.AwardDto(it.pub, 1_000_000, "veteran") }, emptyList(), "Ended"))
        net.keys.take(3).forEach { k -> net.store.add(k.sign(Kinds.OUTCOME, past, listOf(listOf("g", "g-old")), before)) }
        // And a stranger's forged game counts for nothing.
        val forger = Keys.generate()
        // Dates are the signer's say-so: a "seasoned" announcement, backdated a month, buys nothing.
        net.store.add(forger.sign(Kinds.NODE, "wss://forger.example", emptyList(), before - 31L * 86_400))
        net.store.add(forger.sign(Kinds.GAME_OPEN, Nostr.json.encodeToString(GameOpen.serializer(), GameOpen("fake", "old town", 0, listOf(forger.pub), "x")), listOf(listOf("g", "g-fake")), before))
        net.store.add(forger.sign(Kinds.OUTCOME, Nostr.json.encodeToString(Outcome.serializer(), Outcome(1, emptyMap(), listOf(Outcome.AwardDto(forger.pub, 9_000_000, "self-made")), emptyList(), "Ended")), listOf(listOf("g", "g-fake")), before))

        val l = live(net, players + forger)
        assertEquals(1, l.g().players.getValue(forger.pub).level, "the forger's self-awarded points count for nothing")
        assertTrue(players.all { l.g().players.getValue(it.pub).level > 1 }, "the veterans' do")
        val p = l.g().players.values.first { com.hereliesaz.capturetheflag.rules.Progression.perksFor(it.level).decoysPerGame > 0 }
        val target = l.ground(p.team.opponent).first()
        l.act(l.keyOf.getValue(p.id), Action.Decoy(target.lat, target.lng))
        assertEquals(1, l.g().decoysUsed[p.id])
        suspend fun aired() = net.store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME), tags = mapOf("g" to setOf(l.game)))))
            .flatMap { Nostr.json.decodeFromString(Outcome.serializer(), it.content).highlights }
        assertTrue(aired().none { it.kind == com.hereliesaz.capturetheflag.model.HighlightKind.DECOY }, "secret while the round is on")

        net.now += GameRules.PLAY_WINDOW
        repeat(PANEL_TURNS) { net.flush(); net.now += Referee.LEADER_TURN_MS }
        assertIs<GamePhase.Ended>(l.g().phase)
        assertTrue(aired().any { it.kind == com.hereliesaz.capturetheflag.model.HighlightKind.DECOY && it.user == p.id }, "aired when it ends")
    }

    @Test fun aRefereeThatCommitsButNeverRevealsIsLeftOutOfTheSeed() = runTest {
        val net = Network(5)
        net.down += 4
        net.send(action(Keys.generate(), Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val open = net.store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).first()
        val game = open.tag("g")!!
        val o = Nostr.json.decodeFromString(GameOpen.serializer(), open.content)
        // The fifth commits to a share, then never says it.
        net.send(net.keys[4].sign(Kinds.GAME_OPEN, Nostr.json.encodeToString(GameOpen.serializer(), o.copy(commit = Nostr.sha256(ByteArray(32) { 7 }).toHex())), listOf(listOf("g", game), listOf("c", o.city))))
        assertEquals(4, net.store.query(listOf(Filter(kinds = setOf(Kinds.SEED_REVEAL)))).size, "the other four reveal")
        assertTrue(net.live().none { game in it.games }, "no seed without the fifth, yet")

        net.now += Referee.SEED_WAIT_MS
        net.flush()
        assertTrue(net.store.query(listOf(Filter(kinds = setOf(Kinds.SEED_DROP)))).isNotEmpty())
        assertEquals(1, net.live().map { it.games.getValue(game) }.distinct().size, "the four agree on a seed without it")
    }

    @Test fun theStoreRefusesEventsFromTheFutureAndOversizeOnes() = runTest {
        val now = 2_000_000_000_000L
        val store = EventStore { now }
        val k = Keys.generate()
        assertTrue(store.add(k.sign(Kinds.NOTE, "now", emptyList(), now / 1000)))
        assertTrue(store.add(k.sign(Kinds.NOTE, "long ago", emptyList(), 1)), "the past is open: peers replay history")
        assertFalse(store.add(k.sign(Kinds.NOTE, "tomorrow", emptyList(), now / 1000 + 86_400)))
        assertFalse(store.add(k.sign(Kinds.NOTE, "x".repeat(EventStore.MAX_CONTENT + 1), emptyList(), now / 1000)))
    }

    @Test fun forgedHandoversAreIgnoredAndNeverCrashANode() = runTest {
        val net = Network(6)
        net.send(action(Keys.generate(), Action.Open("New Orleans"), city = "new orleans", at = net.now / 1000))
        val game = net.store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).first().tag("g")!!
        val panel = net.referees.firstNotNullOf { it.panelOf(game) }
        val victim = net.keys.indexOfFirst { it.pub !in panel }
        val (a, b) = Keys.generate() to Keys.generate()
        val fake = action(a, Action.Open("Nowhere"), city = "nowhere", at = net.now / 1000)
        fun handover(seed: String, g: String, open: Event) = Handover(open, "nowhere", listOf(a.pub, b.pub, net.keys[victim].pub), mapOf(a.pub to listOf(1L, Long.MAX_VALUE)), seed, emptyList())
        for ((g, open, seed) in listOf(Triple("g-fake", fake, "00".repeat(32)), Triple("g-fake2", fake, "zz"), Triple(game, net.store.query(listOf(Filter(kinds = setOf(Kinds.ACTION)))).first(), "01"))) {
            for (k in listOf(a, b)) net.send(k.sign(Kinds.HANDOVER, Nip44.seal(Nostr.json.encodeToString(Handover.serializer(), handover(seed, g, open)), k, net.keys[victim].pub), listOf(listOf("g", g), listOf("p", net.keys[victim].pub))))
        }
        net.flush()
        assertTrue(net.referees[victim].games.isEmpty(), "strangers can't enrol a node in a round")
        assertNull(net.referees[victim].panelOf(game), "nor hand it a real one")
    }

    @Test fun theArchiveNeverWritesOutsideItsFolder() {
        val root = createTempDirectory("archive").toFile()
        val archive = Archive(File(root, "repo").apply { mkdirs() }, Archive.Sync.EXTERNAL)
        val k = Keys.generate()
        archive.record(k.sign(Kinds.NOTE, "x", listOf(listOf("g", "../escaped"))))
        archive.record(k.sign(Kinds.NOTE, "y", listOf(listOf("g", "nodir/x"))))
        archive.record(k.sign(Kinds.NOTE, "z", listOf(listOf("g", "g-ok"))))
        assertFalse(File(root, "repo/escaped.jsonl").exists())
        assertTrue(File(root, "repo/events").listFiles()!!.all { it.parentFile.name == "events" && !it.name.contains("/") })
        assertEquals(3, archive.replay().size)
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

    @Test fun phonesPlayThroughANode() = testApplication {
        val store = EventStore()
        val node = Keys.generate()
        var now = System.currentTimeMillis()
        val referee = Referee(node, store, DemoCityDirectory) { now }
        install(WebSockets)
        routing { relay(store) }
        startApplication()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        scope.launch { store.live.collect { referee.accept(it) } }
        scope.launch { while (true) { kotlinx.coroutines.delay(50); referee.flush() } }
        val http = createClient { install(ClientWebSockets) }
        fun phone() = NodeBackend(Keys.generate(), RelayClient("/", http, scope).also { it.start() }, scope, clock = { now }, answerWithinMs = 10_000)
        suspend fun eventually(what: String, check: () -> Boolean) {
            kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!check()) kotlinx.coroutines.delay(50) } ?: error("never: $what")
        }
        try {
            val (ana, bo) = phone() to phone()
            ana.register("Ana", "selfie-a"); bo.register("Bo", "selfie-b")
            assertIs<GamePhase.Signup>(ana.requestCity("New Orleans").phase, "the referee opens a round on request")
            bo.requestCity("New Orleans")
            assertEquals(Verdict.Valid, ana.join("New Orleans"), "the verdict comes back from the referee")
            assertEquals(Verdict.Valid, bo.join("New Orleans"))
            eventually("both signed up") { ana.game("New Orleans").value?.signups?.size == 2 }

            // The round opened a moment after `now` was read: go a minute past its deadline.
            now += GameRules.SIGNUP_WINDOW + GameRules.MINUTE
            eventually("teams dealt") { ana.game("New Orleans").value?.phase is GamePhase.FlagPlacement }
            val seen = ana.game("New Orleans").value!!
            assertEquals(setOf(ana.me.value!!.id), seen.lastFix.keys + ana.me.value!!.id)
            assertEquals(2, seen.players.size)

            // City chat reaches everyone; the other phone sees it by name.
            assertEquals(Verdict.Valid, ana.send(com.hereliesaz.capturetheflag.chat.Channel.City(seen.city.id), "Laissez les bons temps rouler"))
            bo.requestCity("New Orleans")
            eventually("chat arrives") { bo.messages(com.hereliesaz.capturetheflag.chat.Channel.City(seen.city.id)).value.any { it.fromName == "Ana" } }
            // Referees' awards and radio reach the phone too.
            eventually("radio") { ana.commentary("New Orleans").value.isNotEmpty() }

            // Team chat goes through the referees, who hand it to each teammate: here, Ana herself.
            val room = com.hereliesaz.capturetheflag.chat.Channel.TeamRoom(seen.id, seen.players.getValue(ana.me.value!!.id).team)
            assertEquals(Verdict.Valid, ana.send(room, "Flag's under the oak"))
            eventually("team chat comes back through the panel") { ana.messages(room).value.any { it.body == "Flag's under the oak" } }
            // Straight from a player, around the referees: ignored.
            val sneak = Keys.generate()
            val inner = sneak.sign(Kinds.TEAM_CHAT, "{\"channel\":\"${room.key}\",\"text\":\"psst\"}")
            store.add(sneak.sign(Kinds.TEAM_CHAT, Nip44.seal(Nostr.json.encodeToString(Event.serializer(), inner), sneak, ana.me.value!!.id), listOf(listOf("p", ana.me.value!!.id))))
            kotlinx.coroutines.delay(300)
            assertTrue(ana.messages(room).value.none { it.body == "psst" })

            // A silent referee replaced: the phone seals to the new panel once a majority of the old one says so.
            val newcomer = Keys.generate()
            store.add(node.sign(Kinds.PANEL, Nostr.json.encodeToString(PanelChange.serializer(), PanelChange(listOf(node.pub, newcomer.pub))), listOf(listOf("g", seen.id))))
            suspend fun chatFrom(k: String) = store.query(listOf(Filter(kinds = setOf(Kinds.CHAT), authors = setOf(k))))
            val sent = chatFrom(ana.me.value!!.id).size
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (true) {
                    ana.send(room, "still under the oak")
                    val last = chatFrom(ana.me.value!!.id).drop(sent).lastOrNull()
                    if (last != null && Sealed.open(last.content, newcomer, last.pubkey) != null) break
                    kotlinx.coroutines.delay(100)
                }
            } ?: error("the phone never sealed to the new panel")

            // Highlights ride the referees' outcomes.
            val moment = com.hereliesaz.capturetheflag.model.Highlight(com.hereliesaz.capturetheflag.model.HighlightKind.NEAR_MISS, ana.me.value!!.id, null, seen.id, seen.city.id, now)
            store.add(node.sign(Kinds.OUTCOME, Nostr.json.encodeToString(Outcome.serializer(), Outcome(99_999, emptyMap(), emptyList(), emptyList(), "Active", listOf(moment))), listOf(listOf("g", seen.id))))
            eventually("highlights reach the phone") { moment in ana.highlights.value }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @Test fun theMediaStoreTakesSignedUploadsAndKeepsPrivatePhotosPrivate() = testApplication {
        val dir = createTempDirectory("media").toFile()
        val store = MediaStore(dir)
        routing { media(store) }
        startApplication()
        val http = createClient { }
        val me = Keys.generate()
        val client = MediaClient("ws://localhost", http)
        assertEquals("http://localhost/media/", client.base)

        // A public segment: stored by its hash, fetched back intact.
        val segment = ByteArray(5_000) { (it % 251).toByte() }
        val url = client.put(me, segment)!!
        assertEquals(client.base + Media.sha(segment), url)
        assertTrue(client.get(url)!!.contentEquals(segment))

        // A private photo: the node holds ciphertext; only the reference's key opens it.
        val photo = "the statue on Esplanade".toByteArray()
        val ref = client.putPrivate(me, photo)!!
        val (plainUrl, key) = Media.parse(ref)
        assertNotNull(key)
        assertFalse(store.read(plainUrl.substringAfterLast('/'))!!.contentEquals(photo), "the node can't read it")
        assertTrue(client.get(ref)!!.contentEquals(photo))

        // No signature, or a signature for a different file: refused.
        val sha = Media.sha(segment + 1)
        assertEquals(401, http.put("/media/$sha") { setBody(segment + 1) }.status.value)
        assertEquals(401, http.put("/media/$sha") { header("Authorization", Media.auth(me, Media.sha(segment))); setBody(segment + 1) }.status.value)
        // Signed, but the bytes don't match the name: refused.
        assertEquals(400, http.put("/media/$sha") { header("Authorization", Media.auth(me, sha)); setBody(segment) }.status.value)
    }

    /**
     * Real speech recognition, when this machine has it: set VOSK_MODEL to a Vosk model directory
     * and SPOKEN_SEGMENT to a video whose audio says "amber lantern". Otherwise there's nothing to run.
     */
    @Test fun voskHearsTheChallenge() = runTest {
        val model = System.getenv("VOSK_MODEL")?.let(::File)?.takeIf { it.isDirectory } ?: return@runTest println("skipped: no VOSK_MODEL")
        val segment = System.getenv("SPOKEN_SEGMENT")?.let(::File)?.takeIf { it.exists() } ?: return@runTest println("skipped: no SPOKEN_SEGMENT")
        val ears = VoskEars(model)
        val right = ears.heard(listOf(segment.readBytes()), listOf("amber", "lantern"))!!
        assertTrue(right.said, "heard: ${right.transcript}")
        val wrong = ears.heard(listOf(segment.readBytes()), listOf("walnut", "zebra"))!!
        assertFalse(wrong.said, "heard: ${wrong.transcript}")
    }

    @Test fun aNodeFollowsItsPeersEventsAndMedia() = testApplication {
        // Node B: a real relay and media store.
        val bEvents = EventStore()
        val bMedia = MediaStore(createTempDirectory("b").toFile())
        install(WebSockets)
        routing { relay(bEvents); media(bMedia) }
        startApplication()
        val someone = Keys.generate()
        val before = someone.sign(Kinds.ACTION, "{}", listOf(listOf("g", "g1")))
        bEvents.add(before)
        val segment = ByteArray(3_000) { (it * 7).toByte() }
        bMedia.write(Media.sha(segment), segment)

        // Node A follows B.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            val aEvents = EventStore()
            val peers = Peers(listOf("/"), aEvents, createClient { install(ClientWebSockets) }, scope)
            val aMedia = MediaStore(createTempDirectory("a").toFile(), elsewhere = peers::fetch)
            peers.start(since = 0)
            suspend fun eventually(what: String, check: suspend () -> Boolean) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!check()) kotlinx.coroutines.delay(50) } ?: error("never: $what")
            }
            eventually("B's history reaches A") { aEvents.query(listOf(Filter(ids = setOf(before.id)))).isNotEmpty() }
            val after = someone.sign(Kinds.ACTION, "{\"late\":1}", listOf(listOf("g", "g1")))
            bEvents.add(after)
            eventually("and B's new events, as they happen") { aEvents.query(listOf(Filter(ids = setOf(after.id)))).isNotEmpty() }

            // A segment uploaded to B: A fetches it, checks it against its name, and keeps it.
            assertFalse(aMedia.has(Media.sha(segment)))
            assertTrue(aMedia.get(Media.sha(segment))!!.contentEquals(segment))
            assertTrue(aMedia.has(Media.sha(segment)), "kept for next time")
            assertNull(aMedia.get(Media.sha(byteArrayOf(1, 2, 3))), "nobody has it")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @Test fun aNodeFindsTheRostersNodesByTheirAnnouncements() = testApplication {
        // Node B: a real relay, holding one event.
        val bEvents = EventStore()
        install(WebSockets)
        routing { relay(bEvents) }
        startApplication()
        val b = Keys.generate()
        val before = Keys.generate().sign(Kinds.ACTION, "{}", listOf(listOf("g", "g1")))
        bEvents.add(before)

        // Node A has no seeds; it learns of B from B's announcement, arriving by way of some other peer.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            val aEvents = EventStore()
            val peers = Peers(emptyList(), aEvents, createClient { install(ClientWebSockets) }, scope, roster = setOf(b.pub), acceptable = { true })
            peers.start(since = 0)
            aEvents.add(Keys.generate().sign(Kinds.NODE, "/elsewhere"))
            aEvents.add(b.sign(Kinds.NODE, "/"))
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (aEvents.query(listOf(Filter(ids = setOf(before.id)))).isEmpty()) kotlinx.coroutines.delay(50)
            } ?: error("A never followed B")
            assertEquals(setOf("/"), peers.urls, "a stranger's announcement is ignored")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
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
