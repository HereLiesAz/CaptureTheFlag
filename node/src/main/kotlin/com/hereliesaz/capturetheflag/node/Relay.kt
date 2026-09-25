package com.hereliesaz.capturetheflag.node

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Every event the node has accepted, in arrival order, persisted as one JSON event per line.
 * Duplicates by id are ignored. [live] carries each new event to subscribers and to the referee.
 */
class EventStore(private val file: File? = null) {
    private val events = mutableListOf<Event>()
    private val ids = mutableSetOf<String>()
    private val lock = Mutex()
    private val _live = MutableSharedFlow<Event>(extraBufferCapacity = 1024)
    val live: SharedFlow<Event> = _live

    init {
        file?.takeIf { it.exists() }?.forEachLine { line ->
            runCatching { Nostr.json.decodeFromString(Event.serializer(), line) }.getOrNull()
                ?.takeIf { it.valid() && ids.add(it.id) }?.let(events::add)
        }
    }

    /** Stores a valid, new event. Returns false for invalid or already-seen events. */
    suspend fun add(e: Event): Boolean = lock.withLock {
        if (!e.valid() || !ids.add(e.id)) return false
        events += e
        file?.appendText(Nostr.json.encodeToString(Event.serializer(), e) + "\n")
        _live.emit(e)
        true
    }

    suspend fun query(filters: List<Filter>): List<Event> = lock.withLock {
        filters.flatMap { f ->
            val hits = events.filter(f::matches).sortedByDescending { it.created_at }
            f.limit?.let { hits.take(it) } ?: hits
        }.distinctBy { it.id }.sortedBy { it.created_at }
    }

    suspend fun size() = lock.withLock { events.size }
}

/**
 * NIP-01 over WebSocket: `EVENT` in, `OK` back; `REQ` returns stored matches, then `EOSE`, then
 * keeps streaming new matches until `CLOSE`.
 */
fun Route.relay(store: EventStore) {
    webSocket("/") {
        val subs = mutableMapOf<String, List<Filter>>()
        val outbox = Channel<String>(Channel.UNLIMITED)
        val sender = launch { for (m in outbox) send(Frame.Text(m)) }
        val listener = launch {
            store.live.collect { e ->
                subs.forEach { (id, fs) -> if (fs.any { it.matches(e) }) outbox.send(msg("EVENT", id, e)) }
            }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val m = runCatching { Nostr.json.parseToJsonElement(frame.readText()).jsonArray }.getOrNull() ?: continue
                when (m.getOrNull(0)?.jsonPrimitive?.content) {
                    "EVENT" -> {
                        val e = runCatching { Nostr.json.decodeFromJsonElement(Event.serializer(), m[1]) }.getOrNull()
                        if (e == null) { outbox.send(notice("invalid: malformed event")); continue }
                        val ok = store.add(e)
                        val reason = when { ok -> ""; !e.valid() -> "invalid: bad id or signature"; else -> "duplicate: already have it" }
                        outbox.send(buildJsonArray { add(JsonPrimitive("OK")); add(JsonPrimitive(e.id)); add(JsonPrimitive(ok || reason.startsWith("duplicate"))); add(JsonPrimitive(reason)) }.toString())
                    }
                    "REQ" -> {
                        val id = m[1].jsonPrimitive.content
                        val filters = m.drop(2).filterIsInstance<JsonObject>().map(Filter::parse)
                        subs[id] = filters
                        store.query(filters).forEach { outbox.send(msg("EVENT", id, it)) }
                        outbox.send(buildJsonArray { add(JsonPrimitive("EOSE")); add(JsonPrimitive(id)) }.toString())
                    }
                    "CLOSE" -> subs.remove(m[1].jsonPrimitive.content)
                    else -> outbox.send(notice("unsupported message"))
                }
            }
        } finally {
            listener.cancel(); outbox.close(); sender.join()
        }
    }
}

private fun msg(type: String, sub: String, e: Event) = buildJsonArray {
    add(JsonPrimitive(type)); add(JsonPrimitive(sub)); add(Nostr.json.encodeToJsonElement(Event.serializer(), e))
}.toString()

private fun notice(text: String) = JsonArray(listOf(JsonPrimitive("NOTICE"), JsonPrimitive(text))).toString()
