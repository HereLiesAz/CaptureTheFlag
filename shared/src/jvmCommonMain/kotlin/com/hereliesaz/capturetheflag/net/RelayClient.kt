package com.hereliesaz.capturetheflag.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * One NIP-01 relay connection that stays up: it reconnects on its own, re-sends every open
 * subscription, and holds events published while offline until it's back. Everything the relay
 * sends arrives on [events].
 */
class RelayClient(private val url: String, private val http: HttpClient, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val subs = mutableMapOf<String, List<Filter>>()
    private val unsent = mutableListOf<String>()
    private var session: DefaultClientWebSocketSession? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    /** Every valid event the relay delivers, on any subscription. */
    val events: SharedFlow<Event> = _events

    fun start(): Job = scope.launch {
        while (isActive) {
            runCatching {
                http.webSocket(url) {
                    lock.withLock {
                        session = this
                        subs.forEach { (id, f) -> send(Frame.Text(req(id, f))) }
                        unsent.forEach { send(Frame.Text(it)) }
                        unsent.clear()
                    }
                    for (frame in incoming) (frame as? Frame.Text)?.let { handle(it.readText()) }
                }
            }
            lock.withLock { session = null }
            delay(RECONNECT_MS)
        }
    }

    suspend fun publish(e: Event) = send(buildJsonArray { add(JsonPrimitive("EVENT")); add(Nostr.json.encodeToJsonElement(Event.serializer(), e)) }.toString(), keep = true)

    suspend fun subscribe(id: String, filters: List<Filter>) {
        lock.withLock { subs[id] = filters }
        send(req(id, filters), keep = false)
    }

    private fun req(id: String, filters: List<Filter>) =
        buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(id)); filters.forEach { add(it.toJson()) } }.toString()

    /** Sends now if connected. Otherwise events wait for the connection; subscriptions are re-sent on it anyway. */
    private suspend fun send(text: String, keep: Boolean) = lock.withLock {
        val s = session
        if (s != null && runCatching { s.send(Frame.Text(text)) }.isSuccess) return@withLock
        if (keep) unsent += text
    }

    private suspend fun handle(text: String) {
        val msg = runCatching { Nostr.json.parseToJsonElement(text) as JsonArray }.getOrNull() ?: return
        if (msg.size >= 3 && msg[0].jsonPrimitive.content == "EVENT") {
            val e = runCatching { Nostr.json.decodeFromJsonElement(Event.serializer(), msg[2]) }.getOrNull() ?: return
            if (e.valid()) _events.emit(e)
        }
    }

    private companion object { const val RECONNECT_MS = 2_000L }
}
