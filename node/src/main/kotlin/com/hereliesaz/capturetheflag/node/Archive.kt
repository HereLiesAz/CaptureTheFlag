package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.data.CityDirectory
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.onboarding.Onboarding
import com.hereliesaz.capturetheflag.rules.CityCell
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The network's shared memory (docs/DECENTRALIZED.md §Archive): a folder every node can read
 * and write, kept in sync by something other than the node.
 *
 * - [Sync.GIT]: a clone of a private GitHub repository. The node pulls, commits and pushes.
 * - [Sync.EXTERNAL]: a folder something else keeps in sync, like a gated Google Drive folder
 *   through Drive for desktop or `rclone mount`. The node only reads and writes files.
 *
 * The node appends every signed event to `events/<game or kind>.jsonl`, caches city surveys in
 * `cities/<city>.json`, and lists itself in `nodes/<pubkey>`. New nodes bootstrap from it.
 *
 * Nothing here is trusted: every event still carries its signature and is re-verified on load.
 * The archive is a mirror and a cache. If it vanishes, the network loses convenience, not truth.
 * Credentials belong to git or the sync client; the node never handles them.
 */
class Archive(val root: File, private val sync: Sync) {
    enum class Sync { GIT, EXTERNAL }

    private val events = File(root, "events").apply { mkdirs() }
    val cities = File(root, "cities").apply { mkdirs() }
    private val nodes = File(root, "nodes").apply { mkdirs() }

    /** Appends a signed event to its game's log (or its kind's, for events outside a game). */
    fun record(e: Event) {
        val name = e.tag("g") ?: "kind-${e.kind}"
        File(events, "$name.jsonl").appendText(Nostr.json.encodeToString(Event.serializer(), e) + "\n")
    }

    /** Every archived event that still verifies. What a new node loads before joining. */
    fun replay(): List<Event> = events.listFiles().orEmpty().filter { it.extension == "jsonl" }.flatMap { f ->
        f.readLines().mapNotNull { runCatching { Nostr.json.decodeFromString(Event.serializer(), it) }.getOrNull() }
    }.filter(Event::valid).distinctBy { it.id }

    /** Lists this node so others can find it. */
    fun announce(pubkey: String, url: String) = File(nodes, pubkey).writeText(url + "\n")

    /** Git: pull others' work, commit ours, push; failures retry next time. External: nothing to do. */
    fun sync() {
        if (sync != Sync.GIT) return
        git("pull", "--rebase", "--autostash")
        git("add", "-A")
        if (git("diff", "--cached", "--quiet") != 0) git("commit", "-m", "node sync")
        git("push")
    }

    private fun git(vararg args: String): Int = runCatching {
        ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start().let { p ->
            if (!p.waitFor(2, TimeUnit.MINUTES)) { p.destroy(); -1 } else p.exitValue()
        }
    }.getOrDefault(-1)
}

/**
 * Surveys are expensive (minutes of API calls) and identical for everyone. A node checks the
 * archive before surveying, and files its own survey there afterwards, so each city is surveyed
 * once across the whole network, not once per node.
 */
class SurveyCache(private val archive: Archive, private val fresh: CityDirectory) : CityDirectory {
    @Serializable
    private data class Stored(val id: String, val name: String, val utc: Int, val boundary: List<List<Double>>, val cells: List<List<Double>>)

    override suspend fun resolve(cityName: String): Pair<City, List<CityCell>>? {
        val file = File(archive.cities, cityName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-") + ".json")
        if (file.exists()) runCatching { return load(Nostr.json.decodeFromString(Stored.serializer(), file.readText())) }
        val got = fresh.resolve(cityName) ?: return null
        val (c, cells) = got
        file.writeText(Nostr.json.encodeToString(Stored.serializer(), Stored(
            c.id, c.name, c.utcOffsetMinutes,
            c.boundary.ring.map { listOf(it.lat, it.lng) },
            cells.map { listOf(it.center.lat, it.center.lng, it.population, it.buildings, it.landAreaM2, it.barrier) },
        )))
        return got
    }

    override fun progress(cityName: String): StateFlow<Onboarding?> = fresh.progress(cityName)

    private fun load(s: Stored) = City(s.id, s.name, Polygon(s.boundary.map { GeoPoint(it[0], it[1]) }), s.utc) to
        s.cells.map { CityCell(GeoPoint(it[0], it[1]), it[2], it[3], it[4], it[5]) }
}
