package com.hereliesaz.capturetheflag.data

import com.hereliesaz.capturetheflag.chat.Channel
import com.hereliesaz.capturetheflag.chat.ChatAccess
import com.hereliesaz.capturetheflag.chat.ChatMessage
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.engine.Transition
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.CityCell
import com.hereliesaz.capturetheflag.rules.CityPartitioner
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.JailRules
import com.hereliesaz.capturetheflag.rules.Leaderboard
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** Resolves a requested city name to its boundary and statistical grid. */
interface CityDirectory {
    suspend fun resolve(cityName: String): Pair<City, List<CityCell>>?
}

/**
 * Single-process stand-in for the real server. Runs the authoritative engine locally so the
 * app is playable end-to-end on one device during development. Not multi-device.
 */
class InMemoryBackend(
    private val directory: CityDirectory,
    private val clock: () -> Millis,
    private val random: Random = Random.Default,
    private val travel: TravelTimeEstimator = HeuristicTravel,
    private val weather: WeatherFactor = NoWeather,
) : GameBackend {
    private val _ledger = MutableStateFlow<List<Award>>(emptyList())
    override val ledger: StateFlow<List<Award>> = _ledger.asStateFlow()
    private val users = mutableMapOf<PlayerId, User>()
    private val engine = GameEngine(random) { Leaderboard.levelOf(_ledger.value, it) }
    private val partitioner = CityPartitioner()
    private val games = mutableMapOf<String, MutableStateFlow<Game?>>()
    private val threads = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()
    private val pingBus = MutableSharedFlow<Ping>(extraBufferCapacity = 64)
    private val tokens = mutableMapOf<String, Pair<PlayerId, Millis>>()
    private val _me = MutableStateFlow<User?>(null)
    override val me: StateFlow<User?> = _me.asStateFlow()

    private val bleRegistry = BleTokenRegistry { token, at ->
        tokens[token]?.takeIf { (_, issued) -> at - issued in 0..GameRules.BLE_TOKEN_ROTATION }?.first
    }

    private fun slot(city: String) = games.getOrPut(city.lowercase()) { MutableStateFlow(null) }
    private fun myId() = checkNotNull(_me.value) { "Not registered" }.id

    private fun apply(city: String, t: (Game) -> Transition): Verdict {
        val flow = slot(city)
        val g = flow.value ?: return Verdict.Rejected("No game in $city")
        val ticked = engine.tick(g, clock())
        ticked.pings.forEach { pingBus.tryEmit(it) }
        val result = t(ticked.game)
        result.pings.forEach { pingBus.tryEmit(it) }
        _ledger.update { it + ticked.awards + result.awards }
        (ticked.notices + result.notices).forEach { announce(flow.value?.city ?: g.city, it) }
        flow.value = result.game
        return result.verdict
    }

    override suspend fun register(displayName: String, selfieUri: String): User =
        User("u-${random.nextLong().toULong().toString(36)}", displayName, selfieUri)
            .also { users[it.id] = it; _me.value = it }

    override suspend fun requestCity(cityName: String): Game {
        val flow = slot(cityName)
        flow.value?.let { current ->
            val ticked = engine.tick(current, clock())
            _ledger.update { it + ticked.awards }
            val t = ticked.game
            if (t.phase !is GamePhase.Ended) return t.also { flow.value = it }
        }
        val (city, cells) = directory.resolve(cityName) ?: error("Unknown city: $cityName")
        val line = partitioner.partition(cells, random).line
        return engine.newRound("g-${random.nextLong().toULong().toString(36)}", city, Territory(city, line, cells), clock())
            .also { flow.value = it }
    }

    override fun game(cityName: String): StateFlow<Game?> = slot(cityName).asStateFlow()

    override suspend fun join(cityName: String) = apply(cityName) { engine.join(it, checkNotNull(_me.value)) }

    override suspend fun appointCoCaptains(cityName: String, picks: Set<PlayerId>) =
        apply(cityName) { engine.appointCoCaptains(it, myId(), picks) }

    override suspend fun placeFlag(
        cityName: String, venueName: String, kind: FlagVenueKind, address: String, venue: GeoPoint, photo: PhotoEvidence,
    ) = apply(cityName) { engine.placeFlag(it, myId(), venueName, kind, address, venue, photo, clock()) }

    override suspend fun placeJail(cityName: String, venueName: String, address: String, venue: GeoPoint, photo: PhotoEvidence) =
        apply(cityName) { engine.placeJail(it, myId(), venueName, address, venue, photo, clock()) }

    override suspend fun jailbreak(cityName: String, photo: PhotoEvidence) =
        apply(cityName) { engine.jailbreak(it, myId(), photo, clock()) }

    override suspend fun captureFlag(cityName: String, photo: PhotoEvidence) =
        apply(cityName) { engine.captureFlag(it, myId(), photo, clock()) }

    override suspend fun tag(cityName: String, target: PlayerId, photo: PhotoEvidence): Verdict {
        // Report window from where the target stands to the jail they must reach.
        val g = slot(cityName).value
        val from = g?.lastFix?.get(target)?.point
        val jail = g?.players?.get(target)?.let { g.jails[it.team.opponent] }?.location
        val window = if (from != null && jail != null) {
            JailRules.reportWindow(travel.travelMs(from, jail, clock()), weather.at(from, clock()))
        } else GameRules.HOUR
        return apply(cityName) { engine.tag(it, myId(), target, photo, clock(), bleRegistry, window) }
    }

    override suspend fun reportLocation(cityName: String, fix: LocationFix) {
        apply(cityName) { engine.reportLocation(it, myId(), fix) }
    }

    override suspend fun currentBleToken(cityName: String): String {
        val now = clock()
        val id = myId()
        tokens.entries.firstOrNull { (_, v) -> v.first == id && now - v.second < GameRules.BLE_TOKEN_ROTATION }
            ?.let { return it.key }
        return random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .also { tokens[it] = id to now }
    }

    override suspend fun decoy(cityName: String, at: GeoPoint) = apply(cityName) { engine.decoy(it, myId(), at, clock()) }

    override suspend fun interrogate(cityName: String, subject: PlayerId) =
        apply(cityName) { engine.interrogate(it, myId(), subject, clock()) }

    override suspend fun vanish(cityName: String) = apply(cityName) { engine.vanish(it, myId()) }

    override suspend fun bounty(cityName: String, target: PlayerId) = apply(cityName) { engine.bounty(it, myId(), target) }

    /** Engine notices go to the city channel under a blank sender. */
    private fun announce(city: City, text: String) {
        val key = Channel.City(city.id).key
        val msg = ChatMessage("n-${random.nextLong().toULong().toString(36)}", key, "", "—", text, clock())
        threads.getOrPut(key) { MutableStateFlow(emptyList()) }.update { it + msg }
    }

    override fun displayName(user: PlayerId) = users[user]?.displayName ?: user

    override fun pings(cityName: String): Flow<Ping> = pingBus.filter { _me.value?.id in it.recipients }

    override fun messages(channel: Channel): StateFlow<List<ChatMessage>> =
        threads.getOrPut(channel.key) { MutableStateFlow(emptyList()) }.asStateFlow()

    override suspend fun send(channel: Channel, body: String): Verdict {
        val me = _me.value ?: return Verdict.Rejected("Register first")
        val gameId = when (channel) {
            is Channel.City -> null
            is Channel.TeamRoom -> channel.game
            is Channel.Direct -> channel.game
        }
        val game = gameId?.let { id -> games.values.firstNotNullOfOrNull { f -> f.value?.takeIf { it.id == id } } }
        if (!ChatAccess.canUse(me.id, channel, game)) return Verdict.Rejected("Not allowed in this channel")
        if (body.isBlank()) return Verdict.Rejected("Empty message")
        val msg = ChatMessage("m-${random.nextLong().toULong().toString(36)}", channel.key, me.id, me.displayName, body.trim(), clock())
        threads.getOrPut(channel.key) { MutableStateFlow(emptyList()) }.update { it + msg }
        return Verdict.Valid
    }
}

/** Development directory: a rough New Orleans boundary over a synthetic grid. */
object DemoCityDirectory : CityDirectory {
    override suspend fun resolve(cityName: String): Pair<City, List<CityCell>> {
        val sw = GeoPoint(29.89, -90.14)
        val ne = GeoPoint(30.03, -89.93)
        val boundary = Polygon(listOf(sw, GeoPoint(sw.lat, ne.lng), ne, GeoPoint(ne.lat, sw.lng)))
        val n = 12
        val cells = (0 until n).flatMap { i ->
            (0 until n).map { j ->
                val c = GeoPoint(sw.lat + (ne.lat - sw.lat) * (i + .5) / n, sw.lng + (ne.lng - sw.lng) * (j + .5) / n)
                // Synthetic: denser toward the middle, a "river" barrier along one diagonal.
                val dense = 1.0 - (kotlin.math.abs(i - n / 2) + kotlin.math.abs(j - n / 2)) / n.toDouble()
                CityCell(c, 1000 * dense, 200 * dense, 1_000_000.0, if (i == j) 1.0 else 0.0)
            }
        }
        return City(cityName.lowercase(), cityName, boundary, utcOffsetMinutes = -300) to cells
    }
}
