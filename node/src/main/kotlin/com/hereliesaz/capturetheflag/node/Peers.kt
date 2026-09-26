package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.Filter
import com.hereliesaz.capturetheflag.net.MediaClient
import com.hereliesaz.capturetheflag.net.RelayClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
 */
class Peers(private val urls: List<String>, private val store: EventStore, private val http: HttpClient, private val scope: CoroutineScope) {
    private val media = urls.map { MediaClient(it, http) }

    /** Starts following every peer, from [since] (unix seconds) on. */
    fun start(since: Long) {
        for (url in urls) {
            val relay = RelayClient(url, http, scope)
            relay.start()
            scope.launch { relay.events.collect { store.add(it) } }
            scope.launch { relay.subscribe("peer", listOf(Filter(since = since))) }
        }
    }

    /** The file named [sha] from the first peer that has it, checked against its name. */
    suspend fun fetch(sha: String): ByteArray? {
        for (m in media) m.get(m.base + sha)?.let { return it }
        return null
    }
}
