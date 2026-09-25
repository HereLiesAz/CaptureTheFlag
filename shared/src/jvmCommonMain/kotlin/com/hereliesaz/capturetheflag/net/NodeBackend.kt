package com.hereliesaz.capturetheflag.net

import com.hereliesaz.capturetheflag.chat.Channel
import com.hereliesaz.capturetheflag.chat.ChatAccess
import com.hereliesaz.capturetheflag.chat.ChatMessage
import com.hereliesaz.capturetheflag.commentary.Commentary
import com.hereliesaz.capturetheflag.data.GameBackend
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Highlight
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.onboarding.Onboarding
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's side of the network: [GameBackend] over a node, per docs/DECENTRALIZED.md.
 *
 * The phone decides nothing. It signs what the player does, seals it to the game's referee
 * panel, and waits for the panel's `outcome` to say how it went. What it shows is the view the
 * referees sealed to this player after the latest batch, never more.
 *
 * Not yet: highlights (the referees don't publish them), city onboarding progress (the node
 * surveys on its own), and team chat through the referees (it's sealed teammate to teammate,
 * so the cut-off is only enforced here in the app).
 */
class NodeBackend(
    private val keys: Keys,
    private val relay: RelayClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val answerWithinMs: Long = 30_000,
    /** The node's media store, for selfies, evidence photos and stream video. */
    private val media: MediaClient? = null,
    /** Reads a photo this phone took, by the reference the platform gave it. */
    private val read: suspend (String) -> ByteArray? = { null },
) : GameBackend {
    private val _me = MutableStateFlow<User?>(null)
    override val me: StateFlow<User?> = _me

    private class Round(val id: String, val panel: List<String>)
    private val rounds = ConcurrentHashMap<String, Round>()
    private val current = ConcurrentHashMap<String, String>()
    private val games = ConcurrentHashMap<String, MutableStateFlow<Game?>>()
    private val viewSeq = ConcurrentHashMap<String, Long>()
    private val verdicts = ConcurrentHashMap<String, String>()
    private val answered = MutableSharedFlow<String>(extraBufferCapacity = 256)
    private val pingBus = MutableSharedFlow<Ping>(extraBufferCapacity = 256)
    private val radio = ConcurrentHashMap<String, MutableStateFlow<List<Commentary>>>()
    private val threads = ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val outcomes = ConcurrentHashMap<String, List<Award>>()
    private val _ledger = MutableStateFlow<List<Award>>(emptyList())
    override val ledger: StateFlow<List<Award>> = _ledger
    override val highlights: StateFlow<List<Highlight>> = MutableStateFlow(emptyList())
    private val names = ConcurrentHashMap<PlayerId, String>()
    private val bleKeys = ConcurrentHashMap<String, ByteArray>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val laps = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    init {
        scope.launch { relay.events.collect { if (seen.add(it.id)) runCatching { receive(it) } } }
        scope.launch { relay.subscribe("mine", listOf(Filter(kinds = setOf(Kinds.VIEW, Kinds.PING, Kinds.TEAM_CHAT), tags = mapOf("p" to setOf(keys.pub))))) }
    }

    private fun key(city: String) = city.trim().lowercase()

    // --- What arrives ---

    private suspend fun receive(e: Event) {
        when (e.kind) {
            Kinds.GAME_OPEN -> {
                val o = Nostr.json.decodeFromString(GameOpen.serializer(), e.content)
                val id = e.tag("g") ?: return
                rounds.putIfAbsent(id, Round(id, o.panel))
                current[o.city] = id
                relay.subscribe("game:$id", listOf(Filter(kinds = setOf(Kinds.OUTCOME), tags = mapOf("g" to setOf(id)))))
            }
            Kinds.PUBLIC_VIEW -> {
                val city = e.tag("c") ?: return
                val g = Views.decode(e.content)
                // Once we're in the game, our sealed view says more; until then, the public one will do.
                if (_me.value?.id?.let { it in g.players || g.signups.any { u -> u.id == it } } != true) show(city, g, e)
            }
            Kinds.VIEW -> {
                val g = Views.decode(Nip44.open(e.content, keys, e.pubkey))
                show(current.entries.firstOrNull { it.value == g.id }?.key ?: key(g.city.name), g, e)
            }
            Kinds.OUTCOME -> {
                val o = Nostr.json.decodeFromString(Outcome.serializer(), e.content)
                val game = e.tag("g") ?: return
                o.verdicts.forEach { (id, v) -> if (verdicts.putIfAbsent(id, v) == null) answered.emit(id) }
                // Every referee signs the same outcome; count it once.
                val city = games.entries.firstOrNull { it.value.value?.id == game }?.key ?: ""
                if (outcomes.putIfAbsent("$game|${o.seq}", o.awards.map { Award(it.user, city, game, it.points, it.reason, e.created_at * 1000) }) == null) {
                    _ledger.value = outcomes.values.flatten().sortedBy { it.at }
                }
            }
            Kinds.PING -> pingBus.emit(Pings.decode(Nip44.open(e.content, keys, e.pubkey)))
            Kinds.RADIO -> {
                val city = e.tag("c") ?: return
                feed(city).update { (it + Commentary(e.created_at * 1000, e.content)).sortedBy { c -> c.at }.takeLast(FEED_LENGTH) }
            }
            Kinds.NOTE -> e.tag("c")?.let { city -> post(Channel.City(city).key, e.pubkey, e.content, e.created_at) }
            Kinds.TEAM_CHAT -> {
                val m = Nostr.json.decodeFromString(TeamMessage.serializer(), Nip44.open(e.content, keys, e.pubkey))
                post(m.channel, e.pubkey, m.text, e.created_at)
            }
            Kinds.LAP -> {
                val stream = e.tag("s") ?: return
                lap(stream).update { if (e.content in it) it else it + e.content }
            }
            Kinds.PROFILE -> runCatching { (Nostr.json.parseToJsonElement(e.content) as JsonObject)["name"]!!.jsonPrimitive.content }
                .getOrNull()?.let { names[e.pubkey] = it }
        }
    }

    /** Keeps the newest view by batch sequence. */
    private fun show(city: String, g: Game, e: Event) {
        val seq = e.tag("s")?.toLongOrNull() ?: 0
        val k = "${g.id}|${if (e.kind == Kinds.VIEW) "me" else "public"}"
        if ((viewSeq[k] ?: -1) > seq) return
        viewSeq[k] = seq
        (g.players.values.map { it.user } + g.signups).forEach { names[it.id] = it.displayName }
        slot(city).value = g
    }

    private fun post(channel: String, from: String, text: String, at: Long) {
        val msg = ChatMessage(Nostr.sha256("$channel|$from|$at|$text".toByteArray()).toHex().take(16), channel, from, displayName(from), text, at * 1000)
        thread(channel).update { if (it.any { m -> m.id == msg.id }) it else (it + msg).sortedBy { m -> m.at } }
    }

    // --- What goes out ---

    /** Signs and seals [a] to the game's panel, sends it, and waits for the referees' verdict. */
    private suspend fun act(city: String, a: Action): Verdict {
        val round = current[key(city)]?.let { rounds[it] } ?: return Verdict.Rejected("No game here yet")
        val body = Nostr.json.encodeToString(Action.serializer(), a)
        val e = keys.sign(Kinds.ACTION, Sealed.forPanel(body, keys, round.panel), listOf(listOf("g", round.id)))
        relay.publish(e)
        return verdict(e.id)
    }

    private suspend fun verdict(id: String): Verdict {
        val reason = verdicts[id] ?: withTimeoutOrNull(answerWithinMs) { answered.first { it == id }; verdicts[id] }
            ?: return Verdict.Rejected("No word from the referees yet. It may still count.")
        return if (reason == "ok") Verdict.Valid else Verdict.Rejected(reason)
    }

    private suspend fun seal(city: String, kind: Int, body: String) {
        val round = current[key(city)]?.let { rounds[it] } ?: return
        relay.publish(keys.sign(kind, Sealed.forPanel(body, keys, round.panel), listOf(listOf("g", round.id))))
    }

    // --- GameBackend ---

    override suspend fun register(displayName: String, selfieUri: String): User {
        // The selfie is public, by design: it goes on the roster for both sides.
        val selfie = if (selfieUri.startsWith("http")) selfieUri
            else read(selfieUri)?.let { bytes -> media?.put(keys, bytes) } ?: selfieUri
        relay.publish(keys.sign(Kinds.PROFILE, Nostr.json.encodeToString(JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(displayName)); put("picture", kotlinx.serialization.json.JsonPrimitive(selfie))
        })))
        return User(keys.pub, displayName, selfie).also { _me.value = it; names[it.id] = displayName }
    }

    override suspend fun requestCity(cityName: String): Game {
        val city = key(cityName)
        relay.subscribe("city:$city", listOf(Filter(kinds = setOf(Kinds.GAME_OPEN, Kinds.PUBLIC_VIEW, Kinds.RADIO, Kinds.NOTE, Kinds.LAP), tags = mapOf("c" to setOf(city)))))
        // A round already running shows up within moments; otherwise ask for one.
        val running = withTimeoutOrNull(LOOK_MS) { slot(city).first { it != null && it.phase !is GamePhase.Ended } }
        if (running != null) return running
        relay.publish(keys.sign(Kinds.ACTION, Nostr.json.encodeToString(Action.serializer(), Action.Open(cityName)), listOf(listOf("c", city))))
        return withTimeoutOrNull(answerWithinMs) { slot(city).first { it != null && it.phase !is GamePhase.Ended } }
            ?: error("No referee answered for $cityName. Try again in a moment.")
    }

    override fun game(cityName: String): StateFlow<Game?> = slot(key(cityName)).asStateFlow()
    override fun onboarding(cityName: String): StateFlow<Onboarding?> = MutableStateFlow(null)

    override suspend fun join(cityName: String): Verdict {
        val u = _me.value ?: return Verdict.Rejected("Register first")
        return act(cityName, Action.Join(u.displayName, u.selfieUrl ?: ""))
    }

    override suspend fun appointCoCaptains(cityName: String, picks: Set<PlayerId>) = act(cityName, Action.CoCaptains(picks))

    override suspend fun placeFlag(cityName: String, venueName: String, kind: FlagVenueKind, address: String, venue: GeoPoint, photo: PhotoEvidence) =
        act(cityName, Action.PlaceFlag(venueName, kind, address, venue.lat, venue.lng, stored(photo).dto()))

    override suspend fun placeJail(cityName: String, venueName: String, address: String, venue: GeoPoint, photo: PhotoEvidence) =
        act(cityName, Action.PlaceJail(venueName, address, venue.lat, venue.lng, stored(photo).dto()))

    override suspend fun goLive(cityName: String, purpose: StreamPurpose, fix: LocationFix) =
        act(cityName, Action.GoLive("s-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex(), purpose, fix.dto()))

    override suspend fun streamFrame(cityName: String, streamId: String, fix: LocationFix, chunk: String, segment: ByteArray?): Verdict {
        // Frames come every few seconds; waiting on each would back them up. A dropped stream shows in the view.
        val round = current[key(cityName)]?.let { rounds[it] } ?: return Verdict.Rejected("No game here yet")
        segment?.let { media?.put(keys, it) }
        val body = Nostr.json.encodeToString(Action.serializer(), Action.Frame(streamId, fix.dto(), chunk))
        relay.publish(keys.sign(Kinds.ACTION, Sealed.forPanel(body, keys, round.panel), listOf(listOf("g", round.id))))
        return Verdict.Valid
    }

    override suspend fun lapSegment(cityName: String, streamId: String, chunk: String, segment: ByteArray) {
        val game = current[key(cityName)] ?: return
        media?.put(keys, segment) ?: return
        relay.publish(keys.sign(Kinds.LAP, chunk, listOf(listOf("g", game), listOf("s", streamId), listOf("c", key(cityName)))))
    }

    override fun segments(cityName: String, streamId: String): StateFlow<List<String>> {
        val m = media ?: return MutableStateFlow(emptyList())
        val out = MutableStateFlow<List<String>>(emptyList())
        scope.launch {
            kotlinx.coroutines.flow.combine(slot(key(cityName)), lap(streamId)) { g, lapped ->
                (g?.streams?.get(streamId)?.chunks.orEmpty() + lapped).distinct().map { m.base + it }
            }.collect { out.value = it }
        }
        return out
    }

    override suspend fun endStream(cityName: String, streamId: String, photo: PhotoEvidence) = act(cityName, Action.EndStream(streamId, stored(photo).dto()))
    override suspend fun dispute(cityName: String, streamId: String, reason: String) = act(cityName, Action.Dispute(streamId, reason))
    override suspend fun tag(cityName: String, target: PlayerId, photo: PhotoEvidence) = act(cityName, Action.Tag(target, stored(photo).dto()))

    /**
     * An evidence photo, uploaded encrypted: the node keeps ciphertext, and the key rides inside
     * the sealed action, so only the referees can look. Left as is when there's no store.
     */
    private suspend fun stored(photo: PhotoEvidence): PhotoEvidence {
        if (photo.imageUri.startsWith("http")) return photo
        val ref = read(photo.imageUri)?.let { media?.putPrivate(keys, it) } ?: return photo
        return photo.copy(imageUri = ref)
    }

    override suspend fun reportLocation(cityName: String, fix: LocationFix) =
        seal(cityName, Kinds.POSITION, Nostr.json.encodeToString(Position.serializer(), fix.dto()))

    override suspend fun locationOff(cityName: String) {
        val round = current[key(cityName)]?.let { rounds[it] } ?: return
        relay.publish(keys.sign(Kinds.ACTION, Sealed.forPanel(Nostr.json.encodeToString(Action.serializer(), Action.LocationOff), keys, round.panel), listOf(listOf("g", round.id))))
    }

    /** This game's BLE key: made here, sent sealed to the referees once, and never anywhere else. */
    override suspend fun currentBleToken(cityName: String): String {
        val game = current[key(cityName)] ?: return ""
        val k = bleKeys.getOrPut(game) {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { seal(cityName, Kinds.BLE_KEY, it.toHex()) }
        }
        return Ble.token(k, clock() / GameRules.BLE_TOKEN_ROTATION)
    }

    override fun pings(cityName: String): Flow<Ping> = pingBus

    override suspend fun decoy(cityName: String, at: GeoPoint) = act(cityName, Action.Decoy(at.lat, at.lng))
    override suspend fun interrogate(cityName: String, subject: PlayerId) = act(cityName, Action.Interrogate(subject))
    override suspend fun vanish(cityName: String) = act(cityName, Action.Vanish)
    override suspend fun bounty(cityName: String, target: PlayerId) = act(cityName, Action.Bounty(target))

    override fun displayName(user: PlayerId): String = names[user] ?: user.take(8)
    override fun commentary(cityName: String): StateFlow<List<Commentary>> = feed(key(cityName)).asStateFlow()
    override fun messages(channel: Channel): StateFlow<List<ChatMessage>> = thread(channel.key).asStateFlow()

    override suspend fun send(channel: Channel, body: String): Verdict {
        val me = _me.value ?: return Verdict.Rejected("Register first")
        if (body.isBlank()) return Verdict.Rejected("Empty message")
        val city = when (channel) {
            is Channel.City -> channel.city
            is Channel.TeamRoom -> games.values.firstNotNullOfOrNull { it.value?.takeIf { g -> g.id == channel.game }?.city?.id }
            is Channel.Direct -> games.values.firstNotNullOfOrNull { it.value?.takeIf { g -> g.id == channel.game }?.city?.id }
        }
        val game = city?.let { slot(it).value }
        if (!ChatAccess.canUse(me.id, channel, game)) return Verdict.Rejected("Not allowed in this channel")
        if (ChatAccess.blackedOut(me.id, game)) return Verdict.Rejected("You're cut off: nothing gets out until you're home, or you say it on a live stream")
        when (channel) {
            is Channel.City -> relay.publish(keys.sign(Kinds.NOTE, body.trim(), listOf(listOf("c", channel.city))))
            else -> {
                val g = game!!
                val to = when (channel) {
                    is Channel.Direct -> setOf(channel.a, channel.b)
                    else -> g.team(g.players.getValue(me.id).team).map { it.id }.toSet()
                }
                val text = Nostr.json.encodeToString(TeamMessage.serializer(), TeamMessage(channel.key, body.trim()))
                // One sealed copy per teammate, ourselves included, so our own thread shows it too.
                for (who in to) relay.publish(keys.sign(Kinds.TEAM_CHAT, Nip44.seal(text, keys, who), listOf(listOf("p", who))))
            }
        }
        return Verdict.Valid
    }

    private fun slot(city: String) = games.getOrPut(city) { MutableStateFlow(null) }
    private fun lap(stream: String) = laps.getOrPut(stream) { MutableStateFlow(emptyList()) }
    private fun feed(city: String) = radio.getOrPut(city) { MutableStateFlow(emptyList()) }
    private fun thread(key: String) = threads.getOrPut(key) { MutableStateFlow(emptyList()) }

    @Serializable private data class TeamMessage(val channel: String, val text: String)

    private companion object {
        const val FEED_LENGTH = 200
        /** How long to look for a round already running before asking for one. */
        const val LOOK_MS = 3_000L
    }
}

private fun LocationFix.dto() = Position(point.lat, point.lng, at, accuracyM)

private fun PhotoEvidence.dto() = Evidence(
    image = imageUri,
    lat = exifLocation?.lat, lng = exifLocation?.lng,
    takenAt = exifTakenAt,
    fix = deviceFix?.dto(),
    direction = exifDirection,
    pose = pose?.let { Evidence.Pose(it.azimuthDeg, it.pitchDeg, it.rollDeg, it.at) },
    ble = bleSightings.map { Evidence.Sighting(it.token, it.at, it.rssi) },
)
