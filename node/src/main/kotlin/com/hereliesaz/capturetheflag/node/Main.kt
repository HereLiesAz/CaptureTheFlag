package com.hereliesaz.capturetheflag.node

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
 * ~~~
 */
fun main() = runBlocking {
    val port = System.getenv("PORT")?.toInt() ?: 7447
    val dir = File(System.getenv("DATA_DIR") ?: "node-data").apply { mkdirs() }
    val keys = loadOrCreateKeys(File(dir, "node.key"))
    val archive = System.getenv("ARCHIVE")?.let {
        val sync = if (System.getenv("ARCHIVE_SYNC") == "external") Archive.Sync.EXTERNAL else Archive.Sync.GIT
        Archive(File(it), sync)
    }
    val store = EventStore(File(dir, "events.jsonl"))
    // Bootstrap: anything the network archived that this node hasn't seen.
    archive?.replay()?.forEach { store.add(it) }

    val open = OpenData()
    val surveyor = CityOnboarding(open.boundaries, open.population, open.features)
    val cities = archive?.let { SurveyCache(it, OnboardedDirectory(surveyor)) } ?: OnboardedDirectory(surveyor)
    val referee = Referee(keys, store, cities)
    referee.restore()

    println("node ${keys.pub} on ws://0.0.0.0:$port/ with ${store.size()} events" + (archive?.let { ", archiving to ${it.root}" } ?: ""))

    launch { store.live.collect { referee.accept(it); archive?.record(it) } }
    launch { while (true) { delay(BATCH_EVERY_MS); referee.flush() } }
    archive?.let { a -> launch { while (true) { delay(ARCHIVE_EVERY_MS); a.sync() } } }

    embeddedServer(Netty, port = port) {
        install(WebSockets)
        routing { relay(store) }
    }.start(wait = true)
    Unit
}

private const val BATCH_EVERY_MS = 5_000L
private const val ARCHIVE_EVERY_MS = 5 * 60_000L

/** The node's identity. Generated once, kept in DATA_DIR. Back it up: it's the node's reputation. */
fun loadOrCreateKeys(file: File): Keys =
    if (file.exists()) Keys(file.readText().trim().hex())
    else Keys.generate().also { file.writeText(it.secret.toHex()); file.setReadable(false, false); file.setReadable(true, true) }
