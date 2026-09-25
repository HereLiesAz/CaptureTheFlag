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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun action(k: Keys, a: Action, game: String? = null, city: String? = null) =
        k.sign(Kinds.ACTION, Nostr.json.encodeToString(Action.serializer(), a), listOfNotNull(game?.let { listOf("g", it) }, city?.let { listOf("c", it) }))

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
            val e = action(p, Action.Join("P$i", "selfie$i"), game)
            store.add(e); referee.accept(e)
        }
        // An action with a bad signature never reaches the store, so never reaches a batch.
        referee.flush()
        assertEquals(4, referee.games.getValue(game).signups.size)

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
