package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.*
import com.hereliesaz.capturetheflag.data.OnboardedDirectory
import com.hereliesaz.capturetheflag.onboarding.CityOnboarding
import com.hereliesaz.capturetheflag.onboarding.OpenData
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Runs a node: a Nostr relay on `ws://host:PORT/`, a referee for games opened through it, and
 * a surveyor for new cities. Everything it knows lives in DATA_DIR.
 *
 * ~~~
 * PORT=7447 DATA_DIR=./node-data ARCHIVE=/path/to/private-repo-clone ./gradlew :node:run
 * PORT=7447 DATA_DIR=./node-data ARCHIVE=~/GoogleDrive/ctf-archive ARCHIVE_SYNC=external ./gradlew :node:run
 * REFEREES=<pubkey>,<pubkey>,... ./gradlew :node:run     # the shared referee roster
 * VOSK_MODEL=/path/to/vosk-model-small-en-us-0.15 ...    # hear the challenge (needs ffmpeg)
 * PEERS=wss://node-b.example,wss://node-c.example ...     # follow other nodes' events and media
 * ATTESTATION=off ...                                    # accept evidence from emulators and rooted phones (development only)
 * PUBLIC_URL=wss://node-a.example ...                    # announce this node; the roster's announced nodes are followed too
 * ~~~
 */
/** A setting, or null when it's unset or blank (compose passes unset variables as empty strings). */
private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

fun main() = runBlocking {
    val port = env("PORT")?.toInt() ?: 7447
    val dir = File(env("DATA_DIR") ?: "node-data").apply { mkdirs() }
    val keys = loadOrCreateKeys(File(dir, "node.key"))
    val archive = env("ARCHIVE")?.let {
        val sync = if (env("ARCHIVE_SYNC") == "external") Archive.Sync.EXTERNAL else Archive.Sync.GIT
        Archive(File(it), sync)
    }
    val store = EventStore(File(dir, "events.jsonl"))
    // Bootstrap: anything the network archived that this node hasn't seen.
    archive?.replay()?.forEach { store.add(it) }

    val open = OpenData()
    val surveyor = CityOnboarding(open.boundaries, open.population, open.features)
    val cities = archive?.let { SurveyCache(it, OnboardedDirectory(surveyor)) } ?: OnboardedDirectory(surveyor)
    // Every node must list the same roster: panels are drawn from it. Alone, a node is its own panel of one.
    val roster = env("REFEREES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.plus(keys.pub)?.distinct() ?: listOf(keys.pub)
    // PEERS: other nodes' relay addresses (wss://…), comma-separated. Their events and media reach this one.
    // One is enough: the roster's nodes announce themselves, and announced nodes are followed too.
    val publicUrl = env("PUBLIC_URL")?.trim()?.takeIf { it.startsWith("wss://") }
    val peers = env("PEERS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        .let { Peers(it, store, io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }, this, roster.toSet(), publicUrl) }
    val media = MediaStore(File(dir, "media"), elsewhere = peers::fetch)
    // VOSK_MODEL: a Vosk model directory (alphacephei.com/vosk/models), for hearing the challenge. Needs ffmpeg.
    val ears = env("VOSK_MODEL")?.let { VoskEars(File(it)) }
    val referee = Referee(keys, store, cities, roster, StreamJudge(keys.pub, media = media::get, ears = ears),
        // ATTESTATION_SIGNERS: SHA-256 (hex) of the app's signing certificate(s), comma-separated.
        attestation = if (env("ATTESTATION") == "off") null
            else KeyAttestation(signers = env("ATTESTATION_SIGNERS")?.split(',')?.map { it.trim().lowercase().replace(":", "") }?.filter(String::isNotEmpty)?.toSet().orEmpty()),
        // The photo matcher: evidence photos are private, so each is opened with the key its reference carries.
        photos = { ref -> Media.parse(ref).let { (url, key) -> media.get(url.substringAfterLast('/'))?.let { if (key != null) Media.open(it, key) else it } } })
    referee.restore()

    println("node ${keys.pub} on ws://0.0.0.0:$port/ (media at /media/) with ${store.size()} events" + (archive?.let { ", archiving to ${it.root}" } ?: ""))

    launch {
        store.live.collect { e ->
            referee.accept(e)
            runCatching { archive?.record(e) }.onFailure { System.err.println("archive: skipped event ${e.id}: $it") }
        }
    }
    // A week back is every live round, and then some.
    peers.start(since = System.currentTimeMillis() / 1000 - 7 * 24 * 3600)
    publicUrl?.let { peers.announce(keys, it) }
    launch { while (true) { delay(BATCH_EVERY_MS); referee.flush() } }
    archive?.let { a -> launch { while (true) { delay(ARCHIVE_EVERY_MS); a.sync() } } }

    embeddedServer(Netty, port = port) {
        install(WebSockets)
        routing { relay(store); media(media); roster(roster) }
    }.start(wait = true)
    Unit
}

private const val BATCH_EVERY_MS = 5_000L
private const val ARCHIVE_EVERY_MS = 5 * 60_000L

/** The node's identity. Generated once, kept in DATA_DIR. Back it up: it's the node's reputation. */
fun loadOrCreateKeys(file: File): Keys =
    if (file.exists()) Keys(file.readText().trim().hex())
    else Keys.generate().also { file.writeText(it.secret.toHex()); file.setReadable(false, false); file.setReadable(true, true) }
