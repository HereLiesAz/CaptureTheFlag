package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.data.DemoCityDirectory
import com.hereliesaz.capturetheflag.model.GamePhase
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

    /** A player's action: plaintext when opening a city, sealed to [referee] inside a game. */
    private fun action(k: Keys, a: Action, game: String? = null, city: String? = null, referee: String? = null): Event {
        val body = Nostr.json.encodeToString(Action.serializer(), a)
        return k.sign(Kinds.ACTION, referee?.let { Nip44.seal(body, k, it) } ?: body, listOfNotNull(game?.let { listOf("g", it) }, city?.let { listOf("c", it) }))
    }

    @Test fun refereeRunsARoundFromSignedActionsAndTheLogRebuildsIt() = runTest {
        var now = 1_000_000L
        val store = EventStore()
        val node = Keys.generate()
        val referee = Referee(node, store, DemoCityDirectory) { now }
        val players = (1..4).map { Keys.generate() }

        referee.accept(action(players[0], Action.Open("New Orleans"), city = "new orleans"))
        val game = referee.games.keys.single()
        val opened = store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN, Kinds.SEED_REVEAL))))
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.pubkey == node.pub })

        players.forEachIndexed { i, p ->
            val e = action(p, Action.Join("P$i", "selfie$i"), game, referee = node.pub)
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
        referee.accept(action(players[0], Action.Open("New Orleans"), city = "new orleans"))
        val game = referee.games.keys.single()
        suspend fun send(e: Event) { store.add(e); referee.accept(e) }
        players.forEachIndexed { i, p -> send(action(p, Action.Join("P$i", "s$i"), game, referee = node.pub)) }
        val k = ByteArray(32) { 7 }.toHex()
        send(players[0].sign(Kinds.BLE_KEY, Nip44.seal(k, players[0], node.pub), listOf(listOf("g", game))))
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
