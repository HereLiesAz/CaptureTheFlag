package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.*
import com.hereliesaz.capturetheflag.chat.Channel
import com.hereliesaz.capturetheflag.chat.ChatAccess
import com.hereliesaz.capturetheflag.commentary.Commentator
import com.hereliesaz.capturetheflag.data.CityDirectory
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.engine.Transition
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.CityPartitioner
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.GameView
import com.hereliesaz.capturetheflag.rules.Progression
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PingKind
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
    /** Checks evidence came off a genuine phone. Null accepts unattested evidence (tests, development). */
    private val attestation: KeyAttestation? = null,
    /** Reads a photo by its reference, decrypted. With it, this referee runs the photo matcher. */
    private val photos: (suspend (String) -> ByteArray?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Round(val open: Event, val id: String, val city: String, val first: List<String>) {
        /** Who referees now. Starts as [first]; a silent member is swapped out by quorum vote. */
        var panel = first
        val quorum = first.size / 2 + 1
        /** Each member's term, by batch sequence: first and last (inclusive). Signatures count only in term. */
        val terms = first.associateWith { mutableListOf(1L, Long.MAX_VALUE) }.toMutableMap()
        /** When each member last signed anything for this game, by this referee's clock. */
        val lastHeard = mutableMapOf<String, Long>()
        /** Members this referee has already voted to replace. */
        val replacing = mutableSetOf<String>()
        /** "out|into" → voters, as final batches deliver the votes. */
        val swaps = mutableMapOf<String, MutableSet<String>>()
        /** Plaintext handed over to a referee drawn mid-round, by event id; it can't open what was sealed before it came. */
        var bodies: Map<String, String> = emptyMap()
        val commits = mutableMapOf<String, String>()
        /** When this referee took up the round, by its own clock: the seed ceremony's deadline runs from here. */
        var openedAt = 0L
        /** Seed-drop votes: member → who voted to leave its share out. */
        val drops = mutableMapOf<String, MutableSet<String>>()
        /** When every sitting member had committed, by this referee's clock: the reveal deadline runs from here. */
        var committedAt: Long? = null
        /** Members this referee has voted to drop from the seed. */
        val dropping = mutableSetOf<String>()
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
        /** Team chat waiting for a cut-off reader to get home, by reader. */
        val waiting = mutableMapOf<PlayerId, MutableList<String>>()
        /** Captures and jailbreaks waiting on matcher scores, by event id: when they arrived. */
        val matchWait = mutableMapOf<String, Long>()
        /** Who has sent a score for each event, and, once batched, the scores themselves. */
        val matchers = mutableMapOf<String, MutableSet<String>>()
        val scores = mutableMapOf<String, MutableMap<String, Double>>()
        /** Every award made before this round opened, by quorum: what levels are priced from, all round. */
        var frozen: List<Award>? = null
        /** Secret highlights, aired when the round ends. */
        val held = mutableListOf<com.hereliesaz.capturetheflag.model.Highlight>()
        val uncommitted = mutableListOf<Secret>()
        /** "stream|review" → referee → its vote, as final batches deliver them. */
        val votes = mutableMapOf<String, MutableMap<String, Boolean>>()
        /** Reviews this referee has voted on, from its own published rulings. */
        val voted = mutableSetOf<String>()

        fun serving(who: String, seq: Long) = terms[who]?.let { seq in it[0]..it[1] } == true
        fun final(seq: Long) = signed[seq].orEmpty().filterKeys { serving(it, seq) }.values
            .groupingBy { it }.eachCount().entries.firstOrNull { it.value >= quorum }?.key
    }

    private val rounds = mutableMapOf<String, Round>()
    /** Referee events that arrived before the open request they belong to. */
    private val early = mutableMapOf<String, MutableList<Event>>()
    private val playerEvents = mutableMapOf<String, MutableMap<String, Event>>()
    private val seen = mutableSetOf<String>()
    private val booth = Commentator(Random.Default)
    private val lock = Mutex()
    private val caught = mutableSetOf<String>()
    /** Open requests waiting for their panel to be drawn. */
    private val opening = mutableListOf<Pair<Event, String>>()
    /** Handovers for rounds this node hasn't joined yet: game → sender → content. */
    private val handovers = mutableMapOf<String, MutableMap<String, String>>()

    val games: Map<String, Game> get() = rounds.values.mapNotNull { r -> r.game?.let { r.id to it } }.toMap()

    /** Referees seen signing two different batches for the same game and sequence. */
    val equivocators: Set<String> get() = caught

    /** Who referees [game]; players seal their events to exactly these keys. */
    fun panelOf(game: String): List<String>? = rounds[game]?.panel

    /** Takes in any event from the network and moves every game as far as it can go. */
    suspend fun accept(e: Event) {
        lock.withLock {
            if (e.kind == Kinds.CHAT) return forward(e)
            ingest(e)
            advance(live = true)
        }
        match(e)
    }

    /**
     * The photo matcher, for captures and jailbreaks: scores the winning frame against the
     * reference the leader registered, and publishes the score. Fetching and scoring happen
     * outside the lock; nothing waits on this but the capture itself, and not for long.
     */
    private suspend fun match(e: Event) {
        val read = photos ?: return
        if (e.kind != Kinds.ACTION) return
        val (r, shot, reference) = lock.withLock {
            val r = rounds[e.tag("g") ?: return] ?: return
            val g = r.game ?: return
            val a = Sealed.open(e.content, keys, e.pubkey)?.let { runCatching { Nostr.json.decodeFromString(Action.serializer(), it) }.getOrNull() } as? Action.EndStream ?: return
            val s = g.streams[a.stream] ?: return
            val enemy = g.players[s.by]?.team?.opponent ?: return
            val ref = when (s.purpose) {
                StreamPurpose.CAPTURE -> g.flags[enemy]?.photo
                StreamPurpose.JAILBREAK -> g.jails[enemy]?.photo
            }?.imageUri ?: return
            r.matchWait[e.id] = clock()
            Triple(r, a.photo.image, ref)
        }
        val score = runCatching { Matcher.score(read(shot) ?: return, read(reference) ?: return) }.getOrNull() ?: return
        lock.withLock {
            publish(Kinds.MATCH, Nostr.json.encodeToString(Match.serializer(), Match(e.id, score)), r.id)
            advance(live = true)
        }
    }

    /**
     * Team chat, through the referees: that's what makes the cut-off real. The sender's own signed
     * message goes, as is, to each teammate who may read it now; a jailed sender, or one on enemy
     * ground, gets nothing out, and a cut-off reader gets it once home. Referees can hold a message
     * back but not forge one: readers check the sender's signature.
     */
    private suspend fun forward(e: Event) {
        val r = rounds[e.tag("g") ?: return] ?: return
        val g = r.game ?: return
        val inner = Sealed.open(e.content, keys, e.pubkey)
            ?.let { runCatching { Nostr.json.decodeFromString(Event.serializer(), it) }.getOrNull() }
            ?.takeIf { it.valid() && it.pubkey == e.pubkey && it.kind == Kinds.TEAM_CHAT } ?: return
        val channel = runCatching { Nostr.json.parseToJsonElement(inner.content) as JsonObject }.getOrNull()
            ?.get("channel")?.jsonPrimitive?.content ?: return
        val me = g.players[e.pubkey] ?: return
        val parts = channel.split(':')
        val to = when {
            parts.size == 3 && parts[0] == "team" && parts[1] == g.id && parts[2] == me.team.name -> g.team(me.team).map { it.id }
            parts.size == 4 && parts[0] == "dm" && parts[1] == g.id &&
                ChatAccess.canUse(e.pubkey, Channel.Direct.of(g.id, parts[2], parts[3]), g) -> listOf(parts[2], parts[3])
            else -> return
        }
        if (ChatAccess.blackedOut(e.pubkey, g)) return
        val text = Nostr.json.encodeToString(Event.serializer(), inner)
        for (who in to) {
            if (ChatAccess.blackedOut(who, g)) r.waiting.getOrPut(who) { mutableListOf() } += text
            else publish(Kinds.TEAM_CHAT, Nip44.seal(text, keys, who), r.id, listOf(listOf("p", who)))
        }
    }

    /** The median of the panel's matcher scores for [event], once a quorum sent one; otherwise the check is skipped. */
    private fun visualMatch(r: Round, event: String): Double? {
        val s = r.scores[event]?.values?.sorted()?.takeIf { it.size >= r.quorum } ?: return null
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * Every award that stood before [before] (unix seconds), across the network: for each batch,
     * the outcome a quorum of that game's own panel signed. Read from signed events, not from what
     * this node happened to referee, so every referee prices the same player the same way, and
     * frozen at round open, so nothing that happens mid-round moves the price.
     */
    private suspend fun pastAwards(before: Long): List<Award> = standing(before).flatMap { s ->
        s.outcome.awards.map { Award(it.user, s.open.city, s.game, it.points, it.reason, s.at) }
    }

    /** One batch's outcome as a quorum of its game's own panel signed it. */
    private class Stood(val game: String, val open: GameOpen, val outcome: Outcome, val signers: List<String>, val at: Long)

    /** Every outcome that stood before [before] (unix seconds), across the network. */
    private suspend fun standing(before: Long): List<Stood> {
        val panels = store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).filter { it.created_at < before }
            .mapNotNull { e -> e.tag("g")?.let { g -> runCatching { Nostr.json.decodeFromString(GameOpen.serializer(), e.content) }.getOrNull()?.takeIf { e.pubkey in it.panel }?.let { g to it } } }
            .toMap()
        return store.query(listOf(Filter(kinds = setOf(Kinds.OUTCOME)))).filter { it.created_at < before }
            .groupBy { it.tag("g") to runCatching { Nostr.json.decodeFromString(Outcome.serializer(), it.content).seq }.getOrNull() }
            .mapNotNull { (key, signed) ->
                val (g, seq) = key
                val open = panels[g] ?: return@mapNotNull null
                if (seq == null) return@mapNotNull null
                val stood = signed.filter { it.pubkey in open.panel }.distinctBy { it.pubkey }.groupBy { it.content }
                    .entries.firstOrNull { it.value.size >= open.panel.size / 2 + 1 } ?: return@mapNotNull null
                Stood(g!!, open, Nostr.json.decodeFromString(Outcome.serializer(), stood.key), stood.value.map { it.pubkey }, stood.value.minOf { it.created_at } * 1000)
            }
    }

    /** Hands over the chat that waited while each reader was cut off, now they aren't. */
    private suspend fun release(r: Round, g: Game) {
        for (who in r.waiting.keys.filterNot { ChatAccess.blackedOut(it, g) }) {
            r.waiting.remove(who)!!.forEach { publish(Kinds.TEAM_CHAT, Nip44.seal(it, keys, who), r.id, listOf(listOf("p", who))) }
        }
    }

    /**
     * Proposes the next batch of each game this referee leads right now. The leader for a
     * sequence rotates through the panel every [LEADER_TURN_MS] it goes unsigned, so a silent
     * referee delays play by one turn, not forever.
     */
    suspend fun flush() = lock.withLock {
        val now = clock()
        for (r in rounds.values.toList()) {
            if (r.game == null) continue
            watch(r)
            if (keys.pub !in r.panel) continue
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
        store.query(listOf(Filter(kinds = setOf(Kinds.ACTION, Kinds.POSITION, Kinds.BLE_KEY, Kinds.RULING, Kinds.MATCH, Kinds.REPLACE, Kinds.HANDOVER, Kinds.GAME_OPEN, Kinds.SEED_REVEAL, Kinds.SEED_DROP, Kinds.BATCH))))
            .sortedWith(compareBy({ it.created_at }, { it.id }))
            .forEach { ingest(it) }
        advance(live = false)
        for (r in rounds.values) r.seqSince = clock()
    }

    private fun ingest(e: Event) {
        if (!seen.add(e.id)) return
        val game = e.tag("g")
        // Anything a member signs for its game (a batch, an outcome, a score) says it's alive.
        game?.let { rounds[it] }?.takeIf { e.pubkey in it.terms }?.lastHeard?.set(e.pubkey, clock())
        when (e.kind) {
            Kinds.ACTION, Kinds.POSITION, Kinds.BLE_KEY ->
                if (game != null) playerEvents.getOrPut(game) { mutableMapOf() }[e.id] = e
                else if (e.kind == Kinds.ACTION) {
                    val open = runCatching { Nostr.json.decodeFromString(Action.serializer(), e.content) }.getOrNull() as? Action.Open
                    if (open != null) opening += e to open.city
                }
            // Scores are batched too, so every referee takes the same median.
            Kinds.MATCH -> if (game != null) {
                playerEvents.getOrPut(game) { mutableMapOf() }[e.id] = e
                runCatching { Nostr.json.decodeFromString(Match.serializer(), e.content) }.getOrNull()
                    ?.let { rounds[game]?.matchers?.getOrPut(it.event) { mutableSetOf() }?.add(e.pubkey) }
            }
            // Rulings are ordered like player events, so every referee counts the same votes at the same point.
            Kinds.RULING -> if (game != null) {
                playerEvents.getOrPut(game) { mutableMapOf() }[e.id] = e
                if (e.pubkey == keys.pub) runCatching { Nostr.json.decodeFromString(Ruling.serializer(), e.content) }.getOrNull()
                    ?.let { rounds[game]?.voted?.add("${it.stream}|${it.review}") }
            }
            Kinds.REPLACE -> if (game != null) playerEvents.getOrPut(game) { mutableMapOf() }[e.id] = e
            Kinds.HANDOVER -> if (game != null && e.tag("p") == keys.pub && rounds[game] == null) {
                Nip44.runCatching { open(e.content, keys, e.pubkey) }.getOrNull()?.let { handovers.getOrPut(game) { mutableMapOf() }[e.pubkey] = it }
            }
            Kinds.GAME_OPEN, Kinds.SEED_REVEAL, Kinds.SEED_DROP, Kinds.BATCH -> {
                if (game == null) return
                val r = rounds[game] ?: run { early.getOrPut(game) { mutableListOf() } += e; return }
                ceremony(r, e)
            }
        }
    }

    /**
     * An open request draws its panel from the eligible pool, by the city's last seed and the
     * request's own id, so every node draws the same one and nobody chooses. Rounds this node
     * isn't on are none of its business.
     */
    private suspend fun openRound(e: Event, city: String) {
        val key = city.trim().lowercase()
        if (rounds.values.any { it.city == key && it.game?.phase !is GamePhase.Ended }) return
        val draw = lastSeed(key, e.created_at) + e.id
        val panel = pool(e.created_at).sortedBy { Nostr.sha256((it + draw).toByteArray()).toHex() }.take(PANEL_SIZE)
        if (keys.pub !in panel) return
        val id = "g-" + e.id.take(16)
        val r = Round(e, id, key, panel).also { rounds[id] = it; it.openedAt = clock() }
        early.remove(id)?.forEach { ceremony(r, it) }
    }

    private fun ceremony(r: Round, e: Event) {
        if (e.pubkey !in r.first && e.pubkey !in r.terms) return
        when (e.kind) {
            Kinds.GAME_OPEN -> runCatching { Nostr.json.decodeFromString(GameOpen.serializer(), e.content) }.getOrNull()
                ?.takeIf { it.open == r.open.id && it.panel == r.first }
                ?.let { r.commits.putIfAbsent(e.pubkey, it.commit) }
            Kinds.SEED_DROP -> runCatching { Nostr.json.decodeFromString(SeedDrop.serializer(), e.content).out }.getOrNull()
                ?.takeIf { it in r.first && it != e.pubkey && e.pubkey in r.first }?.let { r.drops.getOrPut(it) { mutableSetOf() }.add(e.pubkey) }
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
        while (opening.isNotEmpty()) opening.removeAt(0).let { (e, city) -> openRound(e, city) }
        for (g in handovers.keys.toList()) takeOver(g)
        for (r in rounds.values.toList()) {
            // Until the first batch, a late seed drop can still change the seed; after it, the seed is fixed.
            if (r.seed == null || (r.applied == 0L && keys.pub in r.first)) seedCeremony(r, live)
            if (r.game == null) continue
            while (true) {
                val next = r.applied + 1
                if (r.final(next) == null && live && keys.pub in r.panel) endorse(r, next)
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
            publish(Kinds.GAME_OPEN, Nostr.json.encodeToString(GameOpen.serializer(), GameOpen(r.open.id, r.city, deadline, r.first, Nostr.sha256(share).toHex())), r.id, listOf(listOf("c", r.city)))
        }
        // A member that hasn't committed within [SEED_WAIT_MS] of the open, or revealed within as long of
        // the last commitment, is voted out of the seed; a
        // quorum of votes drops its share, and the rest decide. It stays on the panel until replaced.
        val sitting = sitting(r.first, r.drops, r.quorum) ?: return
        // Nobody reveals until every sitting member has committed: nobody can pick a share after seeing others'.
        val uncommitted = sitting.filter { it !in r.commits }
        if (uncommitted.isNotEmpty()) { if (live && clock() - r.openedAt >= SEED_WAIT_MS) dropFromSeed(r, uncommitted); return }
        val since = r.committedAt ?: clock().also { r.committedAt = it }
        val late = live && clock() - since >= SEED_WAIT_MS
        if (live && keys.pub in sitting && keys.pub !in r.reveals) publish(Kinds.SEED_REVEAL, """{"share":"${share.toHex()}"}""", r.id)
        val unrevealed = sitting.filter { p -> r.reveals[p]?.let { Nostr.sha256(it).toHex() } != r.commits[p] }
        if (unrevealed.isNotEmpty()) { if (late) dropFromSeed(r, unrevealed); return }
        val seed = Nostr.sha256(sitting.map { r.reveals.getValue(it) }.reduce(ByteArray::plus))
        if (r.seed?.contentEquals(seed) != true) start(r, seed)
    }

    /** Votes, once each, to leave [who] out of the seed. Never a member whose reveal this referee holds. */
    private suspend fun dropFromSeed(r: Round, who: List<String>) {
        if (keys.pub !in r.first) return
        for (m in who) {
            if (m == keys.pub || m in r.reveals || !r.dropping.add(m)) continue
            publish(Kinds.SEED_DROP, Nostr.json.encodeToString(SeedDrop.serializer(), SeedDrop(m)), r.id)
        }
    }

    /** The members whose shares make the seed: all of [first] but those a quorum voted out. Null if fewer than a quorum remain. */
    private fun sitting(first: List<String>, drops: Map<String, Set<String>>, quorum: Int): List<String>? =
        first.filterNot { m -> drops[m].orEmpty().count { it in first && it != m } >= quorum }.takeIf { it.size >= quorum }

    /** The round's first state, from its seed: the city split, and nobody signed up yet. */
    private suspend fun start(r: Round, seed: ByteArray) {
        val (c, cells) = cities.resolve(r.city) ?: return
        val line = CityPartitioner().partition(cells, Random(seed.long()))
        r.seed = seed
        r.game = GameEngine(Random(seed.long())).newRound(r.id, c, Territory(c, line.line, cells), r.open.created_at * 1000)
        r.seqSince = clock()
        for (m in r.panel) r.lastHeard.putIfAbsent(m, clock())
    }

    /**
     * Who may referee a round opened at [before] (unix seconds): the configured roster, plus any
     * node that opted in (announced itself), has a key at least [KEY_AGE_DAYS] old, and has signed
     * at least [AGREEMENTS] outcomes that matched their quorum. Minus anyone ever caught signing
     * two batches for one sequence.
     */
    private suspend fun pool(before: Long): List<String> {
        val announced = store.query(listOf(Filter(kinds = setOf(Kinds.NODE)))).filter { it.created_at < before }.map { it.pubkey }.toSet()
        val oldEnough = announced.filter { k ->
            store.query(listOf(Filter(authors = setOf(k)))).minOfOrNull { it.created_at }?.let { it <= before - KEY_AGE_DAYS * 86_400 } == true
        }.toSet()
        val agreed = standing(before).flatMap { it.signers }.groupingBy { it }.eachCount()
        val earned = oldEnough.filter { (agreed[it] ?: 0) >= AGREEMENTS }
        val doubled = store.query(listOf(Filter(kinds = setOf(Kinds.BATCH)))).filter { it.created_at < before }
            .groupBy { Triple(it.pubkey, it.tag("g"), runCatching { Nostr.json.decodeFromString(Batch.serializer(), it.content).seq }.getOrNull()) }
            .filter { (_, es) -> es.map { it.content }.distinct().size > 1 }.keys.map { it.first }.toSet()
        return (roster + earned).distinct().filterNot { it in doubled || it in caught }.sorted()
    }

    /** The last seed drawn in [city] before [before] (unix seconds), from its public commits and reveals; empty for a city's first round. */
    private suspend fun lastSeed(city: String, before: Long): String {
        val opens = store.query(listOf(Filter(kinds = setOf(Kinds.GAME_OPEN)))).filter { it.created_at < before }
            .mapNotNull { e -> runCatching { Nostr.json.decodeFromString(GameOpen.serializer(), e.content) }.getOrNull()?.takeIf { it.city == city && e.pubkey in it.panel }?.let { e to it } }
        for ((e, o) in opens.sortedByDescending { it.first.created_at }.distinctBy { it.second.open }) {
            val g = e.tag("g") ?: continue
            val commits = opens.filter { it.second.open == o.open }.associate { it.first.pubkey to it.second.commit }
            val shares = store.query(listOf(Filter(kinds = setOf(Kinds.SEED_REVEAL), tags = mapOf("g" to setOf(g)))))
                .mapNotNull { r -> runCatching { (Nostr.json.parseToJsonElement(r.content) as JsonObject)["share"]!!.jsonPrimitive.content.hex() }.getOrNull()?.let { r.pubkey to it } }
                .filter { (who, s) -> commits[who] == Nostr.sha256(s).toHex() }.toMap()
            val drops = store.query(listOf(Filter(kinds = setOf(Kinds.SEED_DROP), tags = mapOf("g" to setOf(g)))))
                .mapNotNull { d -> runCatching { Nostr.json.decodeFromString(SeedDrop.serializer(), d.content).out }.getOrNull()?.let { it to d.pubkey } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
            val sitting = sitting(o.panel, drops, o.panel.size / 2 + 1) ?: continue
            if (sitting.all { it in shares }) return Nostr.sha256(sitting.map { shares.getValue(it) }.reduce(ByteArray::plus)).toHex()
        }
        return ""
    }

    /**
     * Silent members: nothing signed for this game in the [SILENT_MS] before the rest of the panel's latest, while the round is live. Each referee that
     * notices votes to replace one with the member the round's seed draws from the pool.
     */
    private suspend fun watch(r: Round) {
        if (keys.pub !in r.panel || r.game?.phase.let { it !is GamePhase.Signup && it !is GamePhase.FlagPlacement && it !is GamePhase.Active }) return
        // Against the panel's own latest sign of life, not the wall clock: after an outage of this
        // node's, everyone would look silent.
        val latest = r.panel.mapNotNull { r.lastHeard[it] }.maxOrNull() ?: return
        for (m in r.panel) {
            if (m == keys.pub || m in r.replacing || latest - (r.lastHeard[m] ?: latest) < SILENT_MS) continue
            val into = pool(r.open.created_at).filterNot { it in r.terms || it in caught }
                .minByOrNull { Nostr.sha256((it + r.seed!!.toHex() + m).toByteArray()).toHex() } ?: continue
            r.replacing += m
            publish(Kinds.REPLACE, Nostr.json.encodeToString(Replace.serializer(), Replace(m, into)), r.id)
        }
    }

    /**
     * A replacement vote, at its place in the batch order. When a quorum of the serving panel
     * agrees, the swap takes effect from the next batch, and the others hand the newcomer the
     * round so far and tell the phones.
     */
    private suspend fun swap(r: Round, e: Event, seq: Long, batched: Set<String>, live: Boolean) {
        if (!r.serving(e.pubkey, seq)) return
        val v = runCatching { Nostr.json.decodeFromString(Replace.serializer(), e.content) }.getOrNull() ?: return
        if (v.out !in r.panel || v.into in r.terms) return
        val voters = r.swaps.getOrPut("${v.out}|${v.into}") { mutableSetOf() }.apply { add(e.pubkey) }
        if (voters.size < r.quorum) return
        r.panel = r.panel.map { if (it == v.out) v.into else it }
        r.terms.getValue(v.out)[1] = seq
        r.terms[v.into] = mutableListOf(seq + 1, Long.MAX_VALUE)
        r.lastHeard[v.into] = clock()
        if (!live || keys.pub !in r.panel) return
        val bodies = playerEvents[r.id].orEmpty().filterKeys { it in batched }.values
            .filter { it.kind in setOf(Kinds.ACTION, Kinds.POSITION, Kinds.BLE_KEY) }
            .mapNotNull { ev -> body(r, ev)?.let { listOf(ev.id, it) } }.sortedBy { it[0] }
        val h = Handover(r.open, r.city, r.panel, r.terms.mapValues { it.value.toList() }.toSortedMap(), r.seed!!.toHex(), bodies)
        publish(Kinds.HANDOVER, Nip44.seal(Nostr.json.encodeToString(Handover.serializer(), h), keys, v.into), r.id, listOf(listOf("p", v.into)))
        publish(Kinds.PANEL, Nostr.json.encodeToString(PanelChange.serializer(), PanelChange(r.panel)), r.id)
    }

    /**
     * A round this node was drawn into mid-game: joined once a quorum of the panel handed over the
     * same round. It replays every batch from the start with the plaintext it was given.
     */
    private suspend fun takeOver(game: String) {
        val sent = handovers[game] ?: return
        val (content, senders) = sent.entries.groupBy({ it.value }, { it.key }).maxByOrNull { it.value.size } ?: return
        val h = runCatching { Nostr.json.decodeFromString(Handover.serializer(), content) }.getOrNull() ?: return
        if (keys.pub !in h.panel || senders.count { it in h.panel } < h.panel.size / 2 + 1) return
        if (!h.open.valid()) return
        val r = Round(h.open, game, h.city, h.terms.keys.filter { h.terms.getValue(it)[0] == 1L }.sorted())
        r.panel = h.panel
        r.terms.clear(); h.terms.forEach { (k, v) -> r.terms[k] = v.toMutableList() }
        r.bodies = h.bodies.associate { it[0] to it[1] }
        rounds[game] = r
        handovers.remove(game)
        start(r, h.seed.hex())
        early.remove(game)?.forEach { ceremony(r, it) }
    }

    /** A player event's plaintext: opened with this referee's key, or from its handover. */
    private fun body(r: Round, e: Event): String? = r.bodies[e.id] ?: Sealed.open(e.content, keys, e.pubkey)

    /** This referee's seed share: secret, yet the same after a restart, so a crash between commit and reveal isn't fatal. */
    private fun myShare(r: Round) = hmac(keys.secret, "seed|${r.open.id}".toByteArray())

    /** Signs the one batch for [seq] this referee finds sound, if it hasn't signed one already. */
    private suspend fun endorse(r: Round, seq: Long) {
        val signers = r.signed[seq].orEmpty()
        if (keys.pub in signers) return
        val now = clock()
        // Everything unbatched counts here, including captures this referee would still hold for scores.
        val pending = unbatched(r).map { it.id }.toSet()
        val sound = signers.values.distinct().firstOrNull { content ->
            val b = runCatching { Nostr.json.decodeFromString(Batch.serializer(), content) }.getOrNull() ?: return@firstOrNull false
            // Never from the future. An old proposal stays signable: its signers are locked to it,
            // so refusing it after an outage would stall the game forever.
            b.seq == seq && b.at > r.lastAt && b.at <= now + CLOCK_SKEW_MS &&
                b.events.distinct().size == b.events.size && b.events.all { it in pending }
        } ?: return
        publish(Kinds.BATCH, sound, r.id)
    }

    /**
     * What goes in the next batch. A capture or jailbreak waits up to [MATCH_WAIT_MS] for a
     * quorum of matcher scores, so they land in the same batch or before it; after that it goes
     * without them, and the matcher is skipped.
     */
    private fun unbatched(r: Round) = playerEvents[r.id].orEmpty().values.filter { it.id !in r.finalized }

    private fun pendingOf(r: Round): List<Event> {
        val now = clock()
        return unbatched(r)
            .filterNot { e -> r.matchWait[e.id]?.let { now - it < MATCH_WAIT_MS && r.matchers[e.id].orEmpty().count { it in r.panel } < r.quorum } == true }
            .sortedWith(compareBy({ it.created_at }, { it.id }))
    }

    /**
     * Runs one final batch through the engine. [live] publishes this referee's outcome;
     * [deliver] (the proposer only) also sends pings, commitments, the reveal and the radio.
     */
    private suspend fun apply(r: Round, b: Batch, events: List<Event>, live: Boolean, deliver: Boolean) {
        // Seeded by the batch's contents and its leader's millisecond clock too: a challenge drawn here
        // can't be known, or steered by crafting one's own event, before the batch exists.
        val batchSeed = Nostr.sha256(r.seed!! + b.seq.bytes() + b.at.bytes() + Nostr.sha256(b.events.joinToString(",").toByteArray()))
        val past = r.frozen ?: pastAwards(r.open.created_at).also { r.frozen = it }
        val engine = GameEngine(Random(batchSeed.long()), levelOf = { id -> Leaderboard.levelOf(past, id) }, isRookie = { id -> past.none { it.user == id } })
        val before = r.game!!
        var total = engine.tick(before, b.at)
        val verdicts = mutableMapOf<String, String>()
        // Scores first, wherever they sit in the batch: the capture they're for may come before them.
        for (e in events) if (e.kind == Kinds.MATCH && e.pubkey in r.panel) {
            runCatching { Nostr.json.decodeFromString(Match.serializer(), e.content) }.getOrNull()
                ?.takeIf { it.score in 0.0..1.0 }?.let { r.scores.getOrPut(it.event) { mutableMapOf() }.putIfAbsent(e.pubkey, it.score) }
        }
        val batched = r.finalized + b.events
        for (e in events) {
            if (e.kind == Kinds.MATCH) continue
            if (e.kind == Kinds.REPLACE) { swap(r, e, b.seq, batched, live); continue }
            if (e.kind == Kinds.RULING) {
                val step = vote(r, engine, total.game, e, b.at) ?: continue
                verdicts[e.id] = (step.verdict as? Verdict.Rejected)?.reason ?: "ok"
                total += step
                continue
            }
            val body = body(r, e)
            if (body == null) { verdicts[e.id] = "unreadable"; continue }
            val step = runCatching { step(r, engine, total.game, e, body, b.at) }.getOrElse { verdicts[e.id] = "malformed"; null } ?: continue
            verdicts[e.id] = (step.verdict as? Verdict.Rejected)?.reason ?: "ok"
            total += step
        }
        r.game = total.game
        r.held += total.highlights.filter { it.secret }
        val ended = before.phase !is GamePhase.Ended && total.game.phase is GamePhase.Ended
        val aired = total.highlights.filterNot { it.secret } + (if (ended) r.held.toList().also { r.held.clear() } else emptyList())
        if (live) review(r, total.game)
        if (live) release(r, total.game)
        if (live) publish(Kinds.OUTCOME, Nostr.json.encodeToString(Outcome.serializer(), Outcome(
            b.seq, verdicts, total.awards.map { Outcome.AwardDto(it.user, it.points, it.reason) }, total.notices, total.game.phase::class.simpleName ?: "", aired,
        )), r.id)
        if (!deliver) { r.uncommitted.clear(); return }
        for (p in total.pings) {
            // One event per recipient: nobody else can read it, and nobody else is named on it.
            for (to in p.recipients) publish(Kinds.PING, Nip44.seal(Pings.encode(pingFor(r, p, to, total.game)), keys, to), r.id, listOf(listOf("p", to)))
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

    private fun unattested(a: Action, who: String): String? {
        val photo = when (a) { is Action.PlaceFlag -> a.photo; is Action.PlaceJail -> a.photo; is Action.EndStream -> a.photo; is Action.Tag -> a.photo; else -> return null }
        return attestation?.check(photo, who)
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
            Kinds.ACTION -> when (val a = Nostr.json.decodeFromString(Action.serializer(), body).also { a ->
                // Evidence first: a photo off an unattested phone is refused before the engine sees it.
                unattested(a, who)?.let { return Transition(g, Verdict.Rejected(it)) }
            }) {
                is Action.Open -> null
                is Action.Join -> if (who in r.terms) Transition(g, Verdict.Rejected("Referees can't play in games they referee")) else engine.join(g, User(who, a.name, a.selfie))
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
                is Action.EndStream -> engine.endStream(g, who, a.stream, a.photo.toModel(), now, visualMatch(r, e.id))
                is Action.Dispute -> engine.dispute(g, who, a.stream, a.reason, now)
                is Action.Appeal -> engine.appeal(g, who, a.stream, now)
                is Action.LocationOff -> engine.locationOff(g, who, now)
                is Action.Tag -> engine.tag(g, who, a.target, a.photo.toModel(), now, ble(r), GameRules.HOUR)
                is Action.Decoy -> engine.decoy(g, who, GeoPoint(a.lat, a.lng), now)
                is Action.Vanish -> engine.vanish(g, who, now)
                is Action.Interrogate -> engine.interrogate(g, who, g.players.keys.firstOrNull { it == a.subject || handle(r, it) == a.subject } ?: a.subject, now)
                is Action.Bounty -> engine.bounty(g, who, a.target)
            }
            else -> null
        }
    }

    private fun Round.hold(s: Secret) { secrets += s; uncommitted += s }

    /** An intruder's stand-in name until they're identified: stable for the game, meaningless outside it. */
    private fun handle(r: Round, id: PlayerId) = "h-" + Nostr.sha256(r.seed!! + id.toByteArray()).toHex().take(16)

    /** [p] as [to] may see it: their own copy, the subject a handle until identified, the level only with Keen Eye. */
    private fun pingFor(r: Round, p: Ping, to: PlayerId, g: Game): Ping {
        val keen = g.players[to]?.let { Progression.perksFor(it.level).keenEye } == true
        return p.copy(
            subject = if (p.identified != null || p.kind == PingKind.GO_LIVE || p.kind == PingKind.CLOSER) p.subject else handle(r, p.subject),
            recipients = setOf(to),
            subjectLevel = p.subjectLevel.takeIf { keen },
            decoyRevealedTo = p.decoyRevealedTo.intersect(setOf(to)),
        )
    }

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
        const val MATCH_WAIT_MS = 30_000L
        const val SILENT_MS = 10 * 60_000L
        const val SEED_WAIT_MS = 5 * 60_000L
        const val KEY_AGE_DAYS = 30L
        const val AGREEMENTS = 20
        /** A batch's time may be at most this far ahead of the endorser's own clock. */
        const val CLOCK_SKEW_MS = 30_000L
    }
}
