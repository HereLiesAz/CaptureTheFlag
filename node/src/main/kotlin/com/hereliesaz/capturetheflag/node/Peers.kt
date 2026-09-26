package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.Filter
import com.hereliesaz.capturetheflag.net.Keys
import com.hereliesaz.capturetheflag.net.Kinds
import com.hereliesaz.capturetheflag.net.MediaClient
import com.hereliesaz.capturetheflag.net.RelayClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The other nodes this one keeps up with. A phone talks to one node; its events have to reach
 * every referee on the panel, wherever they run, and every viewer, wherever they watch.
 *
 * Events: this node subscribes to each peer's relay and adds whatever arrives to its own store.
 * The store checks every signature and drops repeats, so a peer can't forge anything, and two
 * nodes that follow each other don't echo forever.
 *
 * Media: files are named by their hash, so a copy from anywhere is as good as the original. A
 * file this node lacks is fetched from a peer, checked against its name, and kept.
 *
 * Discovery: each node signs a [Kinds.NODE] event with its own address and re-signs it daily.
 * It travels like any other event, so following one peer is enough to learn of the rest. Only
 * addresses signed by a key on the referee [roster] are followed: anyone can sign an
 * announcement, and a stranger's would only waste connections. At most [MAX_PEERS].
 */
class Peers(
    private val seeds: List<String>,
    private val store: EventStore,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val roster: Set<String> = emptySet(),
    private val self: String? = null,
    private val acceptable: (String) -> Boolean = { it.startsWith("wss://") },
) {
    private val followed = ConcurrentHashMap<String, MediaClient>()
    private var since = 0L

    /** Who this node follows now: the seeds, then whoever the roster announced. */
    val urls: Set<String> get() = followed.keys

    /** Starts following every seed, and every roster node announced, from [since] (unix seconds) on. */
    fun start(since: Long) {
        this.since = since
        seeds.forEach { follow(it, seed = true) }
        scope.launch { store.live.collect(::heard) }
        scope.launch { store.query(listOf(Filter(kinds = setOf(Kinds.NODE)))).forEach(::heard) }
    }

    /** Tells the network where this node is, now and daily, so it stays inside every peer's window. */
    fun announce(keys: Keys, url: String) = scope.launch {
        while (true) {
            store.add(keys.sign(Kinds.NODE, url))
            delay(ANNOUNCE_EVERY_MS)
        }
    }

    private fun heard(e: com.hereliesaz.capturetheflag.net.Event) {
        if (e.kind == Kinds.NODE && e.pubkey in roster) follow(e.content.trim())
    }

    /** Seeds are the operator's own choice; announced addresses must also pass [acceptable]. */
    private fun follow(url: String, seed: Boolean = false) {
        if (url == self || (!seed && (!acceptable(url) || followed.size >= MAX_PEERS))) return
        if (followed.putIfAbsent(url, MediaClient(url, http)) != null) return
        val relay = RelayClient(url, http, scope)
        relay.start()
        scope.launch { relay.events.collect { store.add(it) } }
        scope.launch { relay.subscribe("peer", listOf(Filter(since = since))) }
    }

    /** The file named [sha] from the first peer that has it, checked against its name. */
    suspend fun fetch(sha: String): ByteArray? {
        for (m in followed.values) m.get(m.base + sha)?.let { return it }
        return null
    }

    companion object {
        const val MAX_PEERS = 64
        private const val ANNOUNCE_EVERY_MS = 24 * 3600_000L
    }
}
