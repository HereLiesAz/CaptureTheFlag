package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.commentary.Commentator
import com.hereliesaz.capturetheflag.data.CityDirectory
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.engine.Transition
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.CityPartitioner
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Leaderboard
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * A single referee (quorum of one): the prototype of docs/DECENTRALIZED.md §Referees.
 *
 * Players publish signed actions; the referee collects them, fixes their order in a signed
 * `batch`, replays the batch through [GameEngine] seeded by a commit-reveal seed, and publishes
 * a signed `outcome`, the pings (to their recipients), and radio lines. Nothing a player
 * publishes changes the game until a batch includes it.
 *
 * The engine is deterministic, so anyone holding the same events, batches and seed reaches the
 * same state; [restore] does exactly that from the node's own store.
 */
class Referee(
    private val keys: Keys,
    private val store: EventStore,
    private val cities: CityDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Table(
        val id: String,
        val city: String,
        val seed: ByteArray,
        var game: Game,
        var seq: Long = 0,
        val names: MutableMap<PlayerId, User> = mutableMapOf(),
        val bleKeys: MutableMap<PlayerId, ByteArray> = mutableMapOf(),
        var lastLook: Long? = null,
        /** Every secret this round holds, for the reveal. */
        val secrets: MutableList<Secret> = mutableListOf(),
        /** Secrets not yet committed publicly. */
        val uncommitted: MutableList<Secret> = mutableListOf(),
    )

    private val tables = mutableMapOf<String, Table>()
    private val pending = mutableListOf<Event>()
    private val ledger = mutableListOf<Award>()
    private val booth = Commentator(Random.Default)
    private val lock = Mutex()

    val games: Map<String, Game> get() = tables.mapValues { it.value.game }

    /** Queues player events for the next batch. Opens games on request. */
    suspend fun accept(e: Event) = lock.withLock {
        when (e.kind) {
            Kinds.ACTION -> when (val game = e.tag("g")) {
                // Only an open request is public; everything in a game is sealed and read at flush.
                null -> (runCatching { Nostr.json.decodeFromString(Action.serializer(), e.content) }.getOrNull() as? Action.Open)?.let { open(it.city) }
                in tables -> pending += e
                else -> {}
            }
            Kinds.POSITION, Kinds.BLE_KEY -> if (e.tag("g") in tables) pending += e
        }
    }

    /**
     * Opens a round in [city] unless one is already running there. Publishes the seed
     * commitment in `game.open`, then the reveal: with one referee the ceremony is trivial,
     * but it's the same two steps a quorum will use.
     */
    private suspend fun open(city: String) {
        val key = city.trim().lowercase()
        if (tables.values.any { it.city == key && it.game.phase !is GamePhase.Ended }) return
        val (c, cells) = cities.resolve(city) ?: return
        val share = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val seed = Nostr.sha256(share)
        val line = CityPartitioner().partition(cells, Random(seed.long()))
        val id = "g-" + Nostr.sha256(share + key.toByteArray()).toHex().take(16)
        val game = GameEngine(Random(seed.long())).newRound(id, c, Territory(c, line.line, cells), clock())
        tables[id] = Table(id, key, seed, game)
        publish(Kinds.GAME_OPEN, """{"city":"${c.name}","deadline":${(game.phase as GamePhase.Signup).deadline},"commit":"${Nostr.sha256(share).toHex()}"}""", id)
        publish(Kinds.SEED_REVEAL, """{"share":"${share.toHex()}"}""", id)
    }

    /** Orders everything pending into one batch per game, applies it, and publishes the results. */
    suspend fun flush() = lock.withLock {
        val now = clock()
        val byGame = pending.sortedWith(compareBy({ it.created_at }, { it.id })).groupBy { it.tag("g")!! }
        pending.clear()
        for (t in tables.values) {
            val events = byGame[t.id].orEmpty()
            val seq = ++t.seq
            publish(Kinds.BATCH, Nostr.json.encodeToString(Batch.serializer(), Batch(seq, now, events.map { it.id })), t.id)
            apply(t, seq, now, events, live = true)
        }
    }

    /** Runs one batch through the engine. [live] publishes results; a restore only rebuilds state. */
    private suspend fun apply(t: Table, seq: Long, now: Long, events: List<Event>, live: Boolean) {
        val engine = GameEngine(Random((t.seed + seq.bytes()).let(Nostr::sha256).long())) { id -> Leaderboard.levelOf(ledger, id) }
        val before = t.game
        var total = engine.tick(t.game, now)
        val verdicts = mutableMapOf<String, String>()
        for (e in events) {
            val body = runCatching { Nip44.open(e.content, keys, e.pubkey) }.getOrNull()
            if (body == null) { verdicts[e.id] = "unreadable"; continue }
            val step = runCatching { step(t, engine, total.game, e, body, now) }.getOrElse { verdicts[e.id] = "malformed"; null } ?: continue
            verdicts[e.id] = (step.verdict as? Verdict.Rejected)?.reason ?: "ok"
            total += step
        }
        t.game = total.game
        ledger += total.awards
        if (!live) { t.uncommitted.clear(); return }
        publish(Kinds.OUTCOME, Nostr.json.encodeToString(Outcome.serializer(), Outcome(
            seq, verdicts, total.awards.map { Outcome.AwardDto(it.user, it.points, it.reason) }, total.notices, t.game.phase::class.simpleName ?: "",
        )), t.id)
        for (p in total.pings) {
            val body = Nostr.json.encodeToString(PingDto.serializer(), PingDto(p.number, p.location.lat, p.location.lng, p.radiusM, p.kind.toString()))
            // One event per recipient: nobody else can read it, and nobody else is named on it.
            for (to in p.recipients) publish(Kinds.PING, Nip44.seal(body, keys, to), t.id, listOf(listOf("p", to)))
        }
        for (s in t.uncommitted) publish(Kinds.COMMIT, Nostr.json.encodeToString(Commit.serializer(), Commit(s.what, s.who, s.team, s.commitment)), t.id)
        t.uncommitted.clear()
        if (before.phase !is GamePhase.Ended && t.game.phase is GamePhase.Ended) {
            publish(Kinds.REVEAL, Nostr.json.encodeToString(Reveal.serializer(), Reveal(t.secrets.toList())), t.id)
        }
        booth.narrate(before, t.game, total.awards, total.notices, now, t.lastLook, total.highlights).forEach {
            publish(Kinds.RADIO, it.text, t.id, listOf(listOf("c", t.city)))
        }
        t.lastLook = now
    }

    /**
     * One player event, already decrypted to [body], as an engine call. Null for events that
     * don't reach the engine (bookkeeping only).
     */
    private fun step(t: Table, engine: GameEngine, g: Game, e: Event, body: String, now: Long): Transition? {
        val who = e.pubkey
        return when (e.kind) {
            Kinds.POSITION -> engine.reportLocation(g, who, Nostr.json.decodeFromString(Position.serializer(), body).fix())
            Kinds.BLE_KEY -> { t.bleKeys[who] = body.hex(); t.hold(Secret("ble", who, null, body, salt(e))); null }
            Kinds.ACTION -> when (val a = Nostr.json.decodeFromString(Action.serializer(), body)) {
                is Action.Open -> null
                is Action.Join -> User(who, a.name, a.selfie).also { t.names[who] = it }.let { engine.join(g, it) }
                is Action.CoCaptains -> engine.appointCoCaptains(g, who, a.picks)
                is Action.PlaceFlag -> engine.placeFlag(g, who, a.venue, a.kind, a.address, GeoPoint(a.lat, a.lng), a.photo.toModel(), now).also {
                    if (it.verdict !is Verdict.Rejected) {
                        val photoHash = Nostr.sha256(a.photo.image.toByteArray()).toHex()
                        t.hold(Secret("flag", who, g.players[who]?.team?.name, "${a.lat},${a.lng},$photoHash", salt(e)))
                    }
                }
                is Action.PlaceJail -> engine.placeJail(g, who, a.venue, a.address, GeoPoint(a.lat, a.lng), a.photo.toModel(), now)
                is Action.Capture -> engine.captureFlag(g, who, a.photo.toModel(), now)
                is Action.Jailbreak -> engine.jailbreak(g, who, a.photo.toModel(), now)
                is Action.Tag -> engine.tag(g, who, a.target, a.photo.toModel(), now, ble(t), GameRules.HOUR)
                is Action.Decoy -> engine.decoy(g, who, GeoPoint(a.lat, a.lng), now)
                is Action.Vanish -> engine.vanish(g, who, now)
                is Action.Interrogate -> engine.interrogate(g, who, a.subject, now)
                is Action.Bounty -> engine.bounty(g, who, a.target)
            }
            else -> null
        }
    }

    private fun Table.hold(s: Secret) { secrets += s; uncommitted += s }

    /**
     * A commitment's salt: private to this referee, yet the same on every replay, so a restored
     * referee reveals what the live one committed to. Derived from the seed it would be public.
     */
    private fun salt(e: Event): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.secret, "HmacSHA256")) }.doFinal(e.id.hex()).toHex()

    /**
     * BLE tokens are `HMAC(k, window)` truncated to 8 bytes, where `k` is the player's key
     * (held by referees only) and `window` is the 15-minute rotation index. The referee resolves
     * a sighting by recomputing every player's token for that window.
     */
    private fun ble(t: Table) = BleTokenRegistry { token, at ->
        val window = at / GameRules.BLE_TOKEN_ROTATION
        t.bleKeys.entries.firstOrNull { (_, k) -> Ble.token(k, window) == token }?.key
    }

    /**
     * Rebuilds every game from the store: the node's own `game.open`, `seed.reveal` and `batch`
     * events, replayed in sequence against the player events they name. Proof that the log, not
     * the node's memory, is the game.
     */
    suspend fun restore() = lock.withLock {
        val mine = store.query(listOf(Filter(authors = setOf(keys.pub), kinds = setOf(Kinds.GAME_OPEN, Kinds.SEED_REVEAL, Kinds.BATCH))))
        val byId = store.query(listOf(Filter(kinds = setOf(Kinds.ACTION, Kinds.POSITION, Kinds.BLE_KEY)))).associateBy { it.id }
        for (open in mine.filter { it.kind == Kinds.GAME_OPEN }) {
            val id = open.tag("g") ?: continue
            val share = mine.firstOrNull { it.kind == Kinds.SEED_REVEAL && it.tag("g") == id }
                ?.let { Nostr.json.parseToJsonElement(it.content).let { j -> (j as kotlinx.serialization.json.JsonObject)["share"].toString().trim('"') } }
                ?.hex() ?: continue
            val cityName = Nostr.json.parseToJsonElement(open.content).let { (it as kotlinx.serialization.json.JsonObject)["city"].toString().trim('"') }
            val (c, cells) = cities.resolve(cityName) ?: continue
            val seed = Nostr.sha256(share)
            val line = CityPartitioner().partition(cells, Random(seed.long()))
            val t = Table(id, cityName.lowercase(), seed, GameEngine(Random(seed.long())).newRound(id, c, Territory(c, line.line, cells), open.created_at * 1000))
            // The open event's own time is the round's start; restore uses the published deadline instead.
            val deadline = Nostr.json.parseToJsonElement(open.content).let { (it as kotlinx.serialization.json.JsonObject)["deadline"].toString().toLong() }
            t.game = t.game.copy(phase = GamePhase.Signup(deadline))
            tables[id] = t
            mine.filter { it.kind == Kinds.BATCH && it.tag("g") == id }
                .map { Nostr.json.decodeFromString(Batch.serializer(), it.content) }
                .sortedBy { it.seq }
                .forEach { b ->
                    t.seq = b.seq
                    apply(t, b.seq, b.at, b.events.mapNotNull(byId::get), live = false)
                }
        }
    }

    private suspend fun publish(kind: Int, content: String, game: String, extra: List<List<String>> = emptyList()) {
        store.add(keys.sign(kind, content, listOf(listOf("g", game)) + extra))
    }

    private fun ByteArray.long() = ByteBuffer.wrap(this, 0, 8).long
    private fun Long.bytes() = ByteBuffer.allocate(8).putLong(this).array()
}

object Ble {
    /** The token a player with key [k] advertises during rotation [window]. */
    fun token(k: ByteArray, window: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }
        return mac.doFinal(ByteBuffer.allocate(8).putLong(window).array()).copyOf(8).toHex()
    }
}
