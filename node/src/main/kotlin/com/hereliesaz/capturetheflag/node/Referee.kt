package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.*
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
import com.hereliesaz.capturetheflag.rules.GameView
import com.hereliesaz.capturetheflag.rules.Leaderboard
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * One referee on a panel: docs/DECENTRALIZED.md §Referees.
 *
 * Every node knows the same [roster] of referee keys. An `open` request draws a panel of up to
 * five from it; each panel member commits to a seed share, then reveals it, and the seed is all
 * shares together. From then on, batches of player events are proposed by a rotating leader
 * and endorsed by the others; a batch is final when a majority of the panel (3 of 5) has
 * signed the same one. Every referee replays final batches through [GameEngine] and signs an
 * `outcome`; the proposer also delivers pings, radio, commitments and reveals.
 *
 * Each referee signs at most one batch per sequence number. A referee that signs two is
 * caught, since both signatures are public: [equivocators].
 *
 * Nothing is remembered that the log can't rebuild: [restore] replays the store.
 */
class Referee(
    private val keys: Keys,
    private val store: EventStore,
    private val cities: CityDirectory,
    private val roster: List<String> = listOf(keys.pub),
    private val judge: StreamJudge = StreamJudge(keys.pub),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Round(val open: Event, val id: String, val city: String, val panel: List<String>) {
        val quorum = panel.size / 2 + 1
        val commits = mutableMapOf<String, String>()
        val reveals = mutableMapOf<String, ByteArray>()
        var seed: ByteArray? = null
        var game: Game? = null
        var applied = 0L
        var lastAt = 0L
        var seqSince = 0L
        /** seq → signer → the batch content it signed. */
        val signed = mutableMapOf<Long, MutableMap<String, String>>()
        val proposed = mutableSetOf<Long>()
        val finalized = mutableSetOf<String>()
        val bleKeys = mutableMapOf<PlayerId, ByteArray>()
        var lastLook: Long? = null
        val secrets = mutableListOf<Secret>()
        val uncommitted = mutableListOf<Secret>()
        /** "stream|review" → referee → its vote, as final batches deliver them. */
        val votes = mutableMapOf<String, MutableMap<String, Boolean>>()
        /** Reviews this referee has voted on, from its own published rulings. */
        val voted = mutableSetOf<String>()

        fun final(seq: Long) = signed[seq].orEmpty().values.groupingBy { it }.eachCount().entries.firstOrNull { it.value >= quorum }?.key
    }

    private val rounds = mutableMapOf<String, Round>()
    /** Referee events that arrived before the open request they belong to. */
    private val early = mutableMapOf<String, MutableList<Event>>()
    private val playerEvents = mutableMapOf<String, MutableMap<String, Event>>()
    private val seen = mutableSetOf<String>()
    private val ledger = mutableListOf<Award>()
    private val booth = Commentator(Random.Default)
    private val lock = Mutex()
    private val caught = mutableSetOf<String>()

    val games: Map<String, Game> get() = rounds.values.mapNotNull { r -> r.game?.let { r.id to it } }.toMap()

    /** Referees seen signing two different batches for the same game and sequence. */
    val equivocators: Set<String> get() = caught

    /** Who referees [game]; players seal their events to exactly these keys. */
    fun panelOf(game: String): List<String>? = rounds[game]?.panel

    /** Takes in any event from the network and moves every game as far as it can go. */
    suspend fun accept(e: Event) = lock.withLock {
        ingest(e)
        advance(live = true)
    }

    /**
     * Proposes the next batch of each game this referee leads right now. The leader for a
     * sequence rotates through the panel every [LEADER_TURN_MS] it goes unsigned, so a silent
     * referee delays play by one turn, not forever.
     */
    suspend fun flush() = lock.withLock {
        val now = clock()
        for (r in rounds.values) {
            if (r.game == null) continue
            val next = r.applied + 1
            if (r.signed[next]?.containsKey(keys.pub) == true) continue
            val attempt = (now - r.seqSince) / LEADER_TURN_MS
            if (r.panel[((next + attempt) % r.panel.size).toInt()] != keys.pub) continue
            val events = pendingOf(r)
            if (events.isEmpty() && now - r.lastAt < TICK_MS) continue
            r.proposed += next
            // Strictly after the last batch, even within the same millisecond, or no one will sign it.
            val at = maxOf(now, r.lastAt + 1)
            publish(Kinds.BATCH, Nostr.json.encodeToString(Batch.serializer(), Batch(next, at, events.map { it.id })), r.id)
        }
        advance(live = true)
    }

    /** Rebuilds every game this node referees from the store, publishing nothing. */
    suspend fun restore() = lock.withLock {
        store.query(listOf(Filter(kinds = setOf(Kinds.ACTION, Kinds.POSITION, Kinds.BLE_KEY, Kinds.RULING, Kinds.GAME_OPEN, Kinds.SEED_REVEAL, Kinds.BATCH))))
            .sortedWith(compareBy({ it.created_at }, { it.id }))
            .forEach { ingest(it) }
        advance(live = false)
        for (r in rounds.values) r.seqSince = clock()
    }

    private fun ingest(e: Event) {
        if (!seen.add(e.id)) return
        val game = e.tag("g")
        when (e.kind) {
            Kinds.ACTION, Kinds.POSITION, Kinds.BLE_KEY ->
                if (game != null) playerEvents.getOrPut(game) { mutableMapOf() }[e.id] = e
                else if (e.kind == Kinds.ACTION) {
                    val open = runCatching { Nostr.json.decodeFromString(Action.serializer(), e.content) }.getOrNull() as? Action.Open
                    if (open != null) openRound(e, open.city)
                }
            // Rulings are ordered like player events, so every referee counts the same votes at the same point.
            Kinds.RULING -> if (game != null) {
                playerEvents.getOrPut(game) { mutableMapOf() }[e.id] = e
                if (e.pubkey == keys.pub) runCatching { Nostr.json.decodeFromString(Ruling.serializer(), e.content) }.getOrNull()
                    ?.let { rounds[game]?.voted?.add("${it.stream}|${it.review}") }
            }
            Kinds.GAME_OPEN, Kinds.SEED_REVEAL, Kinds.BATCH -> {
                if (game == null) return
                val r = rounds[game] ?: run { early.getOrPut(game) { mutableListOf() } += e; return }
                ceremony(r, e)
            }
        }
    }

    /**
     * An open request draws its panel from the roster by the request's own id, so every node
     * draws the same one and nobody chooses. Rounds this node isn't on are none of its business.
     */
    private fun openRound(e: Event, city: String) {
        val key = city.trim().lowercase()
        if (rounds.values.any { it.city == key && it.game?.phase !is GamePhase.Ended }) return
        val panel = roster.distinct().sortedBy { Nostr.sha256((it + e.id).toByteArray()).toHex() }.take(PANEL_SIZE)
        if (keys.pub !in panel) return
        val id = "g-" + e.id.take(16)
        val r = Round(e, id, key, panel).also { rounds[id] = it }
        early.remove(id)?.forEach { ceremony(r, it) }
    }

    private fun ceremony(r: Round, e: Event) {
        if (e.pubkey !in r.panel) return
        when (e.kind) {
            Kinds.GAME_OPEN -> runCatching { Nostr.json.decodeFromString(GameOpen.serializer(), e.content) }.getOrNull()
                ?.takeIf { it.open == r.open.id && it.panel == r.panel }
                ?.let { r.commits.putIfAbsent(e.pubkey, it.commit) }
            Kinds.SEED_REVEAL -> runCatching { (Nostr.json.parseToJsonElement(e.content) as JsonObject)["share"]!!.jsonPrimitive.content.hex() }
                .getOrNull()?.let { r.reveals.putIfAbsent(e.pubkey, it) }
            Kinds.BATCH -> {
                val b = runCatching { Nostr.json.decodeFromString(Batch.serializer(), e.content) }.getOrNull() ?: return
                val prior = r.signed.getOrPut(b.seq) { mutableMapOf() }.putIfAbsent(e.pubkey, e.content)
                if (prior != null && prior != e.content) caught += e.pubkey
            }
        }
    }

    /** Every step that can happen now: seed ceremony, endorsements, final batches. Idempotent. */
    private suspend fun advance(live: Boolean) {
        for (r in rounds.values.toList()) {
            if (r.seed == null) seedCeremony(r, live)
            if (r.game == null) continue
            while (true) {
                val next = r.applied + 1
                if (r.final(next) == null && live) endorse(r, next)
                val final = r.final(next) ?: break
                val b = Nostr.json.decodeFromString(Batch.serializer(), final)
                val known = playerEvents[r.id].orEmpty()
                // A final batch naming an event this node hasn't received yet waits for it.
                if (!b.events.all { it in known }) break
                apply(r, b, b.events.map(known::getValue), live, deliver = live && next in r.proposed && r.signed[next]?.get(keys.pub) == final)
                r.applied = next; r.lastAt = b.at; r.seqSince = clock()
                r.finalized += b.events
            }
        }
    }

    private suspend fun seedCeremony(r: Round, live: Boolean) {
        val share = myShare(r)
        if (live && keys.pub !in r.commits) {
            val deadline = r.open.created_at * 1000 + GameRules.SIGNUP_WINDOW
            publish(Kinds.GAME_OPEN, Nostr.json.encodeToString(GameOpen.serializer(), GameOpen(r.open.id, r.city, deadline, r.panel, Nostr.sha256(share).toHex())), r.id)
        }
        // Nobody reveals until everyone has committed: nobody can pick a share after seeing others'.
        if (r.commits.size < r.panel.size) return
        if (live && keys.pub !in r.reveals) publish(Kinds.SEED_REVEAL, """{"share":"${share.toHex()}"}""", r.id)
        if (r.panel.any { p -> r.reveals[p]?.let { Nostr.sha256(it).toHex() } != r.commits[p] }) return
        val seed = Nostr.sha256(r.panel.map { r.reveals.getValue(it) }.reduce(ByteArray::plus))
        val (c, cells) = cities.resolve(r.city) ?: return
        val line = CityPartitioner().partition(cells, Random(seed.long()))
        r.seed = seed
        r.game = GameEngine(Random(seed.long())).newRound(r.id, c, Territory(c, line.line, cells), r.open.created_at * 1000)
        r.seqSince = clock()
    }

    /** This referee's seed share: secret, yet the same after a restart, so a crash between commit and reveal isn't fatal. */
    private fun myShare(r: Round) = hmac(keys.secret, "seed|${r.open.id}".toByteArray())

    /** Signs the one batch for [seq] this referee finds sound, if it hasn't signed one already. */
    private suspend fun endorse(r: Round, seq: Long) {
        val signers = r.signed[seq].orEmpty()
        if (keys.pub in signers) return
        val now = clock()
        val pending = pendingOf(r).map { it.id }.toSet()
        val sound = signers.values.distinct().firstOrNull { content ->
            val b = runCatching { Nostr.json.decodeFromString(Batch.serializer(), content) }.getOrNull() ?: return@firstOrNull false
            // Never from the future. An old proposal stays signable: its signers are locked to it,
            // so refusing it after an outage would stall the game forever.
            b.seq == seq && b.at > r.lastAt && b.at <= now + CLOCK_SKEW_MS &&
                b.events.distinct().size == b.events.size && b.events.all { it in pending }
        } ?: return
        publish(Kinds.BATCH, sound, r.id)
    }

    private fun pendingOf(r: Round) =
        playerEvents[r.id].orEmpty().values.filter { it.id !in r.finalized }.sortedWith(compareBy({ it.created_at }, { it.id }))

    /**
     * Runs one final batch through the engine. [live] publishes this referee's outcome;
     * [deliver] (the proposer only) also sends pings, commitments, the reveal and the radio.
     */
    private suspend fun apply(r: Round, b: Batch, events: List<Event>, live: Boolean, deliver: Boolean) {
        // Seeded by the batch's contents and its leader's millisecond clock too: a challenge drawn here
        // can't be known, or steered by crafting one's own event, before the batch exists.
        val batchSeed = Nostr.sha256(r.seed!! + b.seq.bytes() + b.at.bytes() + Nostr.sha256(b.events.joinToString(",").toByteArray()))
        val engine = GameEngine(Random(batchSeed.long()), levelOf = { id -> Leaderboard.levelOf(ledger, id) }, isRookie = { id -> ledger.none { it.user == id } })
        val before = r.game!!
        var total = engine.tick(before, b.at)
        val verdicts = mutableMapOf<String, String>()
        for (e in events) {
            if (e.kind == Kinds.RULING) {
                val step = vote(r, engine, total.game, e, b.at) ?: continue
                verdicts[e.id] = (step.verdict as? Verdict.Rejected)?.reason ?: "ok"
                total += step
                continue
            }
            val body = Sealed.open(e.content, keys, e.pubkey)
            if (body == null) { verdicts[e.id] = "unreadable"; continue }
            val step = runCatching { step(r, engine, total.game, e, body, b.at) }.getOrElse { verdicts[e.id] = "malformed"; null } ?: continue
            verdicts[e.id] = (step.verdict as? Verdict.Rejected)?.reason ?: "ok"
            total += step
        }
        r.game = total.game
        ledger += total.awards
        if (live) review(r, total.game)
        if (live) publish(Kinds.OUTCOME, Nostr.json.encodeToString(Outcome.serializer(), Outcome(
            b.seq, verdicts, total.awards.map { Outcome.AwardDto(it.user, it.points, it.reason) }, total.notices, total.game.phase::class.simpleName ?: "",
        )), r.id)
        if (!deliver) { r.uncommitted.clear(); return }
        for (p in total.pings) {
            val body = Nostr.json.encodeToString(PingDto.serializer(), PingDto(p.number, p.location.lat, p.location.lng, p.radiusM, p.kind.toString()))
            // One event per recipient: nobody else can read it, and nobody else is named on it.
            for (to in p.recipients) publish(Kinds.PING, Nip44.seal(body, keys, to), r.id, listOf(listOf("p", to)))
        }
        for (s in r.uncommitted) publish(Kinds.COMMIT, Nostr.json.encodeToString(Commit.serializer(), Commit(s.what, s.who, s.team, s.commitment)), r.id)
        r.uncommitted.clear()
        if (before.phase !is GamePhase.Ended && total.game.phase is GamePhase.Ended) {
            publish(Kinds.REVEAL, Nostr.json.encodeToString(Reveal.serializer(), Reveal(r.secrets.toList())), r.id)
        }
        // Everyone's view of where things stand: sealed to each player, and one in the clear for onlookers.
        val viewers = total.game.players.keys + total.game.signups.map { it.id }
        // Tagged with the batch sequence: a phone keeps the view with the highest one.
        val seq = listOf("s", b.seq.toString())
        for (who in viewers) publish(Kinds.VIEW, Nip44.seal(Views.encode(GameView.of(total.game, who)), keys, who), r.id, listOf(listOf("p", who), seq))
        publish(Kinds.PUBLIC_VIEW, Views.encode(GameView.of(total.game, null)), r.id, listOf(listOf("c", r.city), seq))
        booth.narrate(before, total.game, total.awards, total.notices, b.at, r.lastLook, total.highlights).forEach {
            publish(Kinds.RADIO, it.text, r.id, listOf(listOf("c", r.city)))
        }
        r.lastLook = b.at
    }

    /**
     * One player event, already decrypted to [body], as an engine call. Null for events that
     * don't reach the engine (bookkeeping only).
     */
    private fun step(r: Round, engine: GameEngine, g: Game, e: Event, body: String, now: Long): Transition? {
        val who = e.pubkey
        return when (e.kind) {
            Kinds.POSITION -> engine.reportLocation(g, who, Nostr.json.decodeFromString(Position.serializer(), body).fix())
            Kinds.BLE_KEY -> { r.bleKeys[who] = body.hex(); r.hold(Secret("ble", who, null, body, salt(e, body))); null }
            Kinds.ACTION -> when (val a = Nostr.json.decodeFromString(Action.serializer(), body)) {
                is Action.Open -> null
                is Action.Join -> engine.join(g, User(who, a.name, a.selfie))
                is Action.CoCaptains -> engine.appointCoCaptains(g, who, a.picks)
                is Action.PlaceFlag -> engine.placeFlag(g, who, a.venue, a.kind, a.address, GeoPoint(a.lat, a.lng), a.photo.toModel(), now).also {
                    if (it.verdict !is Verdict.Rejected) {
                        val photoHash = Nostr.sha256(a.photo.image.toByteArray()).toHex()
                        r.hold(Secret("flag", who, g.players[who]?.team?.name, "${a.lat},${a.lng},$photoHash", salt(e, body)))
                    }
                }
                is Action.PlaceJail -> engine.placeJail(g, who, a.venue, a.address, GeoPoint(a.lat, a.lng), a.photo.toModel(), now)
                is Action.GoLive -> engine.goLive(g, who, a.stream, a.purpose, a.fix.fix(), now)
                is Action.Frame -> engine.streamFrame(g, who, a.stream, a.fix.fix(), a.chunk, now)
                is Action.EndStream -> engine.endStream(g, who, a.stream, a.photo.toModel(), now)
                is Action.Dispute -> engine.dispute(g, who, a.stream, a.reason, now)
                is Action.Appeal -> engine.appeal(g, who, a.stream, now)
                is Action.LocationOff -> engine.locationOff(g, who, now)
                is Action.Tag -> engine.tag(g, who, a.target, a.photo.toModel(), now, ble(r), GameRules.HOUR)
                is Action.Decoy -> engine.decoy(g, who, GeoPoint(a.lat, a.lng), now)
                is Action.Vanish -> engine.vanish(g, who, now)
                is Action.Interrogate -> engine.interrogate(g, who, a.subject, now)
                is Action.Bounty -> engine.bounty(g, who, a.target)
            }
            else -> null
        }
    }

    private fun Round.hold(s: Secret) { secrets += s; uncommitted += s }

    /** A referee's vote, counted once per referee per review. A majority of the panel settles it. */
    private fun vote(r: Round, engine: GameEngine, g: Game, e: Event, now: Long): Transition? {
        if (e.pubkey !in r.panel) return null
        val v = runCatching { Nostr.json.decodeFromString(Ruling.serializer(), e.content) }.getOrNull() ?: return null
        val key = "${v.stream}|${v.review}"
        val tally = r.votes.getOrPut(key) { mutableMapOf() }
        if (tally.putIfAbsent(e.pubkey, v.upheld) != null) return null
        val decided = tally.values.groupingBy { it }.eachCount().entries.firstOrNull { it.value >= r.quorum }?.key ?: return null
        return engine.rule(g, v.stream, v.review, decided, now)
    }

    /**
     * Reviews every disputed stream awaiting this referee's vote: runs the checks, publishes the
     * vote, and seals the full report to each leader of both teams, who see it before anyone.
     */
    private suspend fun review(r: Round, g: Game) {
        for (s in g.streams.values) {
            if (!s.pending || s.dispute == null || s.ruling != null) continue
            val key = "${s.id}|${s.review}"
            if (key in r.voted) continue
            val report = judge.review(g, s)
            publish(Kinds.RULING, Nostr.json.encodeToString(Ruling.serializer(), Ruling(s.id, s.review, report.upheld)), r.id)
            val body = Nostr.json.encodeToString(ReviewReport.serializer(), report)
            for (leader in g.players.values.filter { it.isLeader }) {
                publish(Kinds.REPORT, Nip44.seal(body, keys, leader.id), r.id, listOf(listOf("p", leader.id)))
            }
        }
    }

    /**
     * A commitment's salt, keyed by the sealed body: every referee on the panel derives the same
     * one, so any of them can reveal what another committed to, and nobody without the body
     * can. Derived from the seed, it would be public.
     */
    private fun salt(e: Event, body: String): String = hmac(Nostr.sha256(body.toByteArray()), e.id.hex()).toHex()

    /**
     * BLE tokens are `HMAC(k, window)` truncated to 8 bytes, where `k` is the player's key
     * (held by referees only) and `window` is the 15-minute rotation index. The referee resolves
     * a sighting by recomputing every player's token for that window.
     */
    private fun ble(r: Round) = BleTokenRegistry { token, at ->
        val window = at / GameRules.BLE_TOKEN_ROTATION
        r.bleKeys.entries.firstOrNull { (_, k) -> Ble.token(k, window) == token }?.key
    }

    private suspend fun publish(kind: Int, content: String, game: String, extra: List<List<String>> = emptyList()) {
        val e = keys.sign(kind, content, listOf(listOf("g", game)) + extra)
        store.add(e)
        ingest(e)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ByteArray.long() = ByteBuffer.wrap(this, 0, 8).long
    private fun Long.bytes() = ByteBuffer.allocate(8).putLong(this).array()

    companion object {
        const val PANEL_SIZE = 5
        /** How long a leader has to get its batch signed before the next panel member proposes. */
        const val LEADER_TURN_MS = 15_000L
        /** A batch goes out at least this often, empty or not, so timed rules advance. */
        const val TICK_MS = 60_000L
        /** A batch's time may be at most this far ahead of the endorser's own clock. */
        const val CLOCK_SKEW_MS = 30_000L
    }
}

object Ble {
    /** The token a player with key [k] advertises during rotation [window]. */
    fun token(k: ByteArray, window: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }
        return mac.doFinal(ByteBuffer.allocate(8).putLong(window).array()).copyOf(8).toHex()
    }
}
