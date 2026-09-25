package com.hereliesaz.capturetheflag.engine

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.DecoyWalk
import com.hereliesaz.capturetheflag.model.Flag
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GameId
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Incursion
import com.hereliesaz.capturetheflag.model.Jail
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PingKind
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.PingSchedule
import com.hereliesaz.capturetheflag.rules.Points
import com.hereliesaz.capturetheflag.rules.Progression
import com.hereliesaz.capturetheflag.rules.TeamAssignment
import com.hereliesaz.capturetheflag.rules.Verdict
import com.hereliesaz.capturetheflag.rules.Verification
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** New game state plus side effects the caller must deliver. */
data class Transition(
    val game: Game,
    val verdict: Verdict = Verdict.Valid,
    val pings: List<Ping> = emptyList(),
    /** Points earned by this transition. The caller appends them to the ledger. */
    val awards: List<Award> = emptyList(),
    /** Public announcements for the city channel. */
    val notices: List<String> = emptyList(),
) {
    operator fun plus(next: Transition) = Transition(
        next.game, next.verdict, pings + next.pings, awards + next.awards, notices + next.notices,
    )
}

/**
 * The whole rulebook as pure functions of (state, input, time, randomness).
 * Intended to run server-side, where it is authoritative; clients only render its output.
 *
 * When a hiding perk meets a hunting perk, the higher-level player's perk wins; ties go to the hunter.
 */
class GameEngine(
    private val random: Random = Random.Default,
    /** Current level of a user, from the points ledger. Snapshotted when teams are dealt. */
    private val levelOf: (PlayerId) -> Int = { 1 },
) {

    fun newRound(id: GameId, city: City, territory: Territory, now: Millis) =
        Game(id, city, territory, GamePhase.Signup(now + GameRules.SIGNUP_WINDOW))

    fun join(game: Game, user: User): Transition {
        if (game.phase !is GamePhase.Signup) return game.reject("Sign-up is closed")
        if (game.signups.any { it.id == user.id }) return game.reject("Already signed up")
        if (user.selfieUrl == null) return game.reject("Register a selfie first")
        return Transition(game.copy(signups = game.signups + user))
    }

    /** Advances timed phases, parole, decoy walks and due pings. Call on a schedule and after every input. */
    fun tick(game: Game, now: Millis): Transition = when (val ph = game.phase) {
        is GamePhase.Signup -> if (now < ph.deadline) Transition(game) else closeSignup(game, now)
        is GamePhase.FlagPlacement -> placementTick(game, ph.deadline, now)
        is GamePhase.Active -> if (now >= ph.deadline) end(game, Outcome.Tie, now) else {
            val judged = disqualifyLate(game, now)
            val paroled = judged + parole(judged.game, now)
            val walked = paroled + walkDecoys(paroled.game, now)
            walked + duePings(walked.game, now)
        }
        is GamePhase.Ended -> Transition(game)
    }.settled()

    private fun closeSignup(game: Game, now: Millis): Transition {
        if (game.signups.size < 2 * GameRules.MIN_PLAYERS_PER_TEAM) return end(game, Outcome.Cancelled, now)
        val players = TeamAssignment.assign(game.signups, random, levelOf)
        return Transition(
            game.copy(players = players, phase = GamePhase.FlagPlacement(now + GameRules.FLAG_PLACEMENT_WINDOW)),
        )
    }

    /** A team's placement deadline: the base hour plus its best leader's Deliberate. */
    fun placementDeadline(game: Game, team: Team, base: Millis): Millis =
        base + (game.team(team).filter { it.isLeader }.maxOfOrNull { perks(it).deliberateMs } ?: 0L)

    private fun placementTick(game: Game, base: Millis, now: Millis): Transition {
        val expired = Team.entries.filter { !game.isSetUp(it) && now >= placementDeadline(game, it, base) }
        return when (expired.size) {
            0 -> Transition(game)
            1 -> end(game, Outcome.Forfeit(expired.single(), "No flag and jail placed in time"), now)
            else -> end(game, Outcome.Tie, now)
        }
    }

    fun appointCoCaptains(game: Game, captain: PlayerId, picks: Set<PlayerId>): Transition {
        if (game.phase is GamePhase.Ended || game.phase is GamePhase.Signup) return game.reject("Teams not formed")
        val updated = TeamAssignment.appointCoCaptains(game.players, captain, picks)
            ?: return game.reject("Only the captain may appoint up to two teammates")
        return Transition(game.copy(players = updated))
    }

    fun placeFlag(
        game: Game,
        by: PlayerId,
        venueName: String,
        kind: FlagVenueKind,
        address: String,
        venue: GeoPoint,
        photo: PhotoEvidence,
        now: Millis,
    ): Transition {
        val ph = game.phase as? GamePhase.FlagPlacement ?: return game.reject("Flag placement window is closed")
        val team = game.players[by]?.team ?: return game.reject("Not in this game")
        if (now >= placementDeadline(game, team, ph.deadline)) return game.reject("Flag placement window is closed")
        val v = Verification.flagRegistration(game, by, venue, photo, now)
        if (v != Verdict.Valid) return Transition(game, v)
        val flag = Flag(team, venueName, kind, address, venue, photo, by, now)
        return startIfReady(game.copy(flags = game.flags + (team to flag)), now)
    }

    /** Registers the team jail. Requires the flag first, and must sit well away from it. */
    fun placeJail(
        game: Game,
        by: PlayerId,
        venueName: String,
        address: String,
        venue: GeoPoint,
        photo: PhotoEvidence,
        now: Millis,
    ): Transition {
        val ph = game.phase as? GamePhase.FlagPlacement ?: return game.reject("Placement window is closed")
        val team = game.players[by]?.team ?: return game.reject("Not in this game")
        if (now >= placementDeadline(game, team, ph.deadline)) return game.reject("Placement window is closed")
        val v = Verification.jailRegistration(game, by, venue, photo, now)
        if (v != Verdict.Valid) return Transition(game, v)
        val jail = Jail(team, venueName, address, venue, photo, by, now)
        return startIfReady(game.copy(jails = game.jails + (team to jail)), now)
    }

    /** Everything placed early: start the clock now rather than idling out the hour. */
    private fun startIfReady(game: Game, now: Millis) =
        if (Team.entries.all { game.isSetUp(it) }) Transition(game.copy(phase = GamePhase.Active(now + GameRules.PLAY_WINDOW)))
        else Transition(game)

    private fun Game.isSetUp(t: Team) = t in flags && t in jails

    /** Ingests a device fix; opens or closes incursions, fires Tripwires and Bloodhound trails. */
    fun reportLocation(game: Game, player: PlayerId, fix: LocationFix): Transition {
        val p = game.players[player] ?: return game.reject("Not in this game")
        var g = game.copy(lastFix = game.lastFix + (player to fix))
        if (g.phase is GamePhase.Active && p.isJailed) return reportToJail(g.copy(incursions = g.incursions - player), p, fix)
        if (g.phase !is GamePhase.Active || p.isJailed) return Transition(g.copy(incursions = g.incursions - player))
        val inEnemy = g.territory.ownerOf(fix.point) == p.team.opponent
        val open = g.incursions[player]
        val pings = mutableListOf<Ping>()
        val awards = mutableListOf<Award>()

        if (inEnemy && open == null) {
            // Threshold: the incursion (and its first ping) only starts once the grace runs out.
            g = g.copy(incursions = g.incursions + (player to Incursion(player, fix.at + perks(p).thresholdMs)))
            // Tripwire outranks Threshold: equal-or-higher defenders hear the crossing instantly.
            val tripped = tripwires(g, p, fix.point).filter { it.level >= p.level }.map { it.id }.toSet()
            if (tripped.isNotEmpty()) {
                pings += Ping(player, 0, fix.point, fix.at, null, tripped, PingKind.TRIPWIRE, subjectLevel = p.level)
            }
        } else if (!inEnemy && open != null) {
            g = g.copy(incursions = g.incursions - player, vanishPending = g.vanishPending - player)
            // Made it home unjailed: paid per ping endured. Leaving the city pays nothing.
            if (g.territory.ownerOf(fix.point) == p.team && open.pingsSent > 0) {
                awards += g.award(player, Points.PER_PING_SURVIVED.toLong() * open.pingsSent, "Survived ${open.pingsSent} pings", fix.at)
            }
        }

        // Bloodhound: hunters still on this intruder's trail get the live position.
        val trails = g.trails.filterValues { it >= fix.at }
        if (inEnemy) {
            trails.keys.filter { it.second == player }.forEach { (hunterId, _) ->
                val hunter = g.players.getValue(hunterId)
                val r = if (hunter.level >= p.level) 0.0 else blurRadius(g, p, fix.point)
                pings += Ping(player, 0, blur(fix.point, r), fix.at, null, setOf(hunterId), PingKind.TRACKING, r, p.level)
            }
        }
        g = g.copy(trails = trails)
        val held = holdBreakout(g, g.players.getValue(player), fix)
        return (held + Transition(held.game, pings = pings, awards = mentored(held.game, awards)) + duePings(held.game, fix.at)).settled()
    }

    /**
     * A prisoner's fix. Within range of the enemy jail with a good fix, the hold clock runs;
     * stepping out resets it. A full hold before the deadline completes the report.
     */
    private fun reportToJail(game: Game, p: Player, fix: LocationFix): Transition {
        if (p.disqualified || p.reportedAt != null) return Transition(game)
        val jail = game.jails[p.team.opponent] ?: return Transition(game)
        val there = fix.accuracyM <= GameRules.MAX_FIX_ACCURACY_M &&
            fix.point.distanceTo(jail.location) <= GameRules.JAIL_REPORT_RADIUS_M
        val since = if (there) p.reportingSince ?: fix.at else null
        val done = since != null && fix.at - since >= GameRules.JAIL_REPORT_HOLD &&
            fix.at <= (p.jailDeadline ?: Long.MAX_VALUE)
        val updated = p.copy(reportingSince = since, reportedAt = if (done) fix.at else null)
        return Transition(
            game.copy(players = game.players + (p.id to updated)),
            notices = if (done) listOf("${p.user.displayName} reported to jail.") else emptyList(),
        )
    }

    /** Prisoners past their deadline without a completed report are out, and forfeit the round's points. */
    private fun disqualifyLate(game: Game, now: Millis): Transition {
        val late = game.players.values.filter {
            it.isJailed && !it.disqualified && it.reportedAt == null && it.jailDeadline != null && now > it.jailDeadline
        }
        if (late.isEmpty()) return Transition(game)
        return Transition(
            game.copy(players = game.players + late.associate { it.id to it.copy(disqualified = true, reportingSince = null) }),
            awards = late.mapNotNull { p ->
                val net = game.earned[p.id] ?: 0L
                if (net > 0) game.award(p.id, -net, "Disqualified: never reported to jail", now) else null
            },
            notices = late.map { "${it.user.displayName} never reported to jail. Disqualified." },
        )
    }

    private fun duePings(game: Game, now: Millis): Transition {
        var g = game
        val pings = mutableListOf<Ping>()
        for ((id, inc) in game.incursions) {
            var cur = inc
            val subject = g.players.getValue(id)
            val sp = perks(subject)
            while (PingSchedule.dueAt(cur.enteredAt, cur.pingsSent + 1, sp) <= now) {
                val n = cur.pingsSent + 1
                val fix = g.lastFix[id] ?: break
                cur = cur.copy(pingsSent = n)
                if (id in g.vanishPending) {
                    g = g.copy(vanishPending = g.vanishPending - id)
                    continue
                }
                val ping = broadcast(g, subject, fix.point, n, PingSchedule.dueAt(cur.enteredAt, n, sp))
                pings += ping
                g = delivered(g, ping)
            }
            g = g.copy(incursions = g.incursions + (id to cur))
        }
        return Transition(g, pings = pings)
    }

    /** A real incursion ping about [subject] at [point], with every perk on both sides applied. */
    private fun broadcast(game: Game, subject: Player, point: GeoPoint, n: Int, at: Millis): Ping {
        val sp = perks(subject)
        val enemies = game.team(subject.team.opponent).filterNot { it.isJailed }
        val localHour = (((at + game.city.utcOffsetMinutes * GameRules.MINUTE) / GameRules.HOUR) % 24 + 24) % 24
        val night = localHour in 1..4
        val divisor = min(10.0, 3.0 + sp.shadowDivisor + if (night) sp.nightCoverDivisor else 0.0)
        val drawn = enemies.shuffled(random).take(max(1, ceil(enemies.size / divisor).toInt())).map { it.id }
        // Proximity alert, and Witnesses standing near an alerted teammate.
        val watchers = enemies.filter { e -> near(game, e.id, point, perks(e).proximityAlertM) }
        val witnesses = enemies.filter { w ->
            watchers.any { a -> a.id != w.id && game.lastFix[w.id]?.let { near(game, a.id, it.point, perks(a).witnessM) } == true }
        }
        val trip = tripwires(game, subject, point)
        val identified = PingSchedule.identifies(n, sp)
        val r = if (identified) 0.0 else blurRadius(game, subject, point)
        return Ping(
            subject = subject.id,
            number = n,
            location = blur(point, r),
            at = at,
            identified = subject.user.takeIf { identified },
            recipients = (drawn + watchers.map { it.id } + witnesses.map { it.id } + trip.map { it.id }).toSet(),
            radiusM = r,
            subjectLevel = subject.level,
        )
    }

    /** Bookkeeping after a real ping lands: who knows about whom, and who is now on the trail. */
    private fun delivered(game: Game, ping: Ping): Game {
        val pinged = game.pingedAbout.toMutableMap()
        val trails = game.trails.toMutableMap()
        ping.recipients.forEach { r ->
            pinged[r] = (pinged[r] ?: emptySet()) + ping.subject
            val ms = game.players[r]?.let { perks(it).bloodhoundMs } ?: 0L
            if (ms > 0) trails[r to ping.subject] = ping.at + ms
        }
        return game.copy(pingedAbout = pinged, trails = trails)
    }

    private fun tripwires(game: Game, intruder: Player, point: GeoPoint): List<Player> =
        game.team(intruder.team.opponent).filter { d ->
            val r = perks(d).tripwireM
            !d.isJailed && r > 0 && game.flags[d.team]?.location?.distanceTo(point)?.let { it <= r } == true
        }

    private fun blurRadius(game: Game, subject: Player, point: GeoPoint): Double {
        val sp = perks(subject)
        if (sp.blurM <= 0) return 0.0
        val crowd = 1 + sp.crowdFactor * max(0.0, game.territory.densityRatio(point) - 1)
        return sp.blurM * crowd
    }

    private fun blur(p: GeoPoint, radiusM: Double, rnd: Random = random) = offset(p, radiusM, rnd)

    private fun near(game: Game, who: PlayerId, point: GeoPoint, radiusM: Double) =
        radiusM > 0 && game.lastFix[who]?.point?.distanceTo(point)?.let { it <= radiusM } == true

    fun captureFlag(game: Game, by: PlayerId, photo: PhotoEvidence, now: Millis): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val v = Verification.flagCapture(game, by, photo, now)
        if (v != Verdict.Valid) return Transition(game, v)
        return end(game, Outcome.FlagCaptured(game.players.getValue(by).team, by), now).settled()
    }

    /**
     * Sends a fake anonymous ping to the enemy from [at], which must be in their territory.
     * With Doppelgänger it then walks on, one more ping per waypoint every five minutes.
     */
    fun decoy(game: Game, by: PlayerId, at: GeoPoint, now: Millis): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val p = game.players[by] ?: return game.reject("Not in this game")
        if (p.isJailed) return game.reject("Jailed players cannot send decoys")
        val used = game.decoysUsed[by] ?: 0
        if (used >= perks(p).decoysPerGame) return game.reject("No decoys left")
        if (game.territory.ownerOf(at) != p.team.opponent) return game.reject("Decoys must land in enemy territory")
        val walk = randomWalk(game, p.team.opponent, at, perks(p).doppelgangerSteps)
        val g = game.copy(
            decoysUsed = game.decoysUsed + (by to used + 1),
            decoyWalks = if (walk.isEmpty()) game.decoyWalks else game.decoyWalks + DecoyWalk(by, walk, now + DECOY_STEP),
        )
        return Transition(g, pings = listOf(decoyPing(g, p, at, now)))
    }

    private fun decoyPing(game: Game, sender: Player, at: GeoPoint, now: Millis): Ping {
        val enemies = game.team(sender.team.opponent).filterNot { it.isJailed }
        val r = blurRadius(game, sender, at)
        // Counterintel: only an equal-or-higher-level defender close enough sees through it.
        val revealed = enemies.filter { e -> e.level >= sender.level && near(game, e.id, at, perks(e).counterintelM) }
        return Ping(
            subject = sender.id,
            number = 1,
            location = blur(at, r),
            at = now,
            identified = null,
            recipients = PingSchedule.recipients(enemies.map { it.id }, random),
            radiusM = r,
            subjectLevel = sender.level,
            decoyRevealedTo = revealed.map { it.id }.toSet(),
        )
    }

    private fun walkDecoys(game: Game, now: Millis): Transition {
        val pings = mutableListOf<Ping>()
        val walks = game.decoyWalks.mapNotNull { w ->
            var cur = w
            while (cur.waypoints.isNotEmpty() && cur.nextAt <= now) {
                val sender = game.players.getValue(cur.sender)
                if (!sender.isJailed) pings += decoyPing(game, sender, cur.waypoints.first(), cur.nextAt)
                cur = cur.copy(waypoints = cur.waypoints.drop(1), nextAt = cur.nextAt + DECOY_STEP)
            }
            cur.takeIf { it.waypoints.isNotEmpty() }
        }
        return Transition(game.copy(decoyWalks = walks), pings = pings)
    }

    private fun randomWalk(game: Game, inside: Team, from: GeoPoint, steps: Int): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        var cur = from
        repeat(steps) {
            val next = (0 until 8).map { offset(cur, DECOY_STRIDE_M, random, exact = true) }
                .firstOrNull { game.territory.ownerOf(it) == inside } ?: return out
            out += next
            cur = next
        }
        return out
    }

    /** Forces an extra ping, to [by] alone, on an intruder [by] has already been pinged about. */
    fun interrogate(game: Game, by: PlayerId, subject: PlayerId, now: Millis): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val p = game.players[by] ?: return game.reject("Not in this game")
        if (p.isJailed) return game.reject("Jailed players cannot interrogate")
        val used = game.interrogationsUsed[by] ?: 0
        if (used >= perks(p).interrogationsPerGame) return game.reject("No interrogations left")
        val inc = game.incursions[subject] ?: return game.reject("That intruder is gone")
        if (subject !in (game.pingedAbout[by] ?: emptySet())) return game.reject("You have not been pinged about them")
        val s = game.players.getValue(subject)
        val fix = game.lastFix[subject] ?: return game.reject("No fix on record")
        val r = if (p.level >= s.level) 0.0 else blurRadius(game, s, fix.point)
        val ping = Ping(
            subject, inc.pingsSent, blur(fix.point, r), now,
            s.user.takeIf { PingSchedule.identifies(max(1, inc.pingsSent), perks(s)) },
            setOf(by), PingKind.INTERROGATION, r, s.level,
        )
        return Transition(game.copy(interrogationsUsed = game.interrogationsUsed + (by to used + 1)), pings = listOf(ping))
    }

    /** Swallows the caller's next scheduled incursion ping. */
    fun vanish(game: Game, by: PlayerId): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val p = game.players[by] ?: return game.reject("Not in this game")
        if (by !in game.incursions) return game.reject("Only on enemy ground")
        if (by in game.vanishPending) return game.reject("Already vanishing")
        val used = game.vanishesUsed[by] ?: 0
        if (used >= perks(p).vanishesPerGame) return game.reject("No vanishes left")
        return Transition(game.copy(vanishesUsed = game.vanishesUsed + (by to used + 1), vanishPending = game.vanishPending + by))
    }

    /** Marks one enemy per round; jailing them pays the marker's Bounty multiplier to the whole team. */
    fun bounty(game: Game, by: PlayerId, target: PlayerId): Transition {
        if (game.phase !is GamePhase.Active && game.phase !is GamePhase.FlagPlacement) return game.reject("Game is not live")
        val p = game.players[by] ?: return game.reject("Not in this game")
        if (perks(p).bountyMultiplier <= 1.0) return game.reject("Bounty not unlocked")
        if (by in game.bounties) return game.reject("Bounty already placed")
        if (game.players[target]?.team != p.team.opponent) return game.reject("Mark an enemy")
        return Transition(game.copy(bounties = game.bounties + (by to target)))
    }

    /**
     * Jails [target]. [reportWindowMs] is how long they get to reach the jail, computed by the
     * caller from travel time ([com.hereliesaz.capturetheflag.rules.JailRules.reportWindow]).
     */
    fun tag(
        game: Game,
        by: PlayerId,
        target: PlayerId,
        photo: PhotoEvidence,
        now: Millis,
        ble: BleTokenRegistry,
        reportWindowMs: Long = GameRules.HOUR,
    ): Transition = tagUnsettled(game, by, target, photo, now, ble, reportWindowMs).settled()

    private fun tagUnsettled(
        game: Game, by: PlayerId, target: PlayerId, photo: PhotoEvidence, now: Millis, ble: BleTokenRegistry, reportWindowMs: Long,
    ): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val v = Verification.tag(game, by, target, photo, now, ble)
        if (v != Verdict.Valid) return Transition(game, v)
        val victim = game.players.getValue(target)
        val tagger = game.players.getValue(by)

        // Last Stand: the tag is thrown out, and the whole city hears about it.
        val stands = game.lastStandsUsed[target] ?: 0
        if (stands < perks(victim).lastStandsPerGame) {
            return Transition(
                game.copy(lastStandsUsed = game.lastStandsUsed + (target to stands + 1)),
                Verdict.Rejected("${victim.user.displayName} made a Last Stand"),
                notices = listOf("${victim.user.displayName} made a Last Stand against ${tagger.user.displayName}."),
            )
        }

        val key = by to target
        val multiplier = game.team(tagger.team).filter { game.bounties[it.id] == target }
            .maxOfOrNull { perks(it).bountyMultiplier } ?: 1.0
        val awards = if (key in game.scoredTags) emptyList() else listOf(
            game.award(by, (Progression.tagValue(victim.level) * multiplier).toLong(), "Jailed ${victim.user.displayName}", now),
            game.award(target, Points.JAILED.toLong(), "Jailed", now),
        )
        val g = game.copy(
            players = game.players + (target to victim.copy(
                jailedAt = now, jailDeadline = now + reportWindowMs, reportingSince = null, reportedAt = null, breakoutSince = null,
            )),
            incursions = game.incursions - target,
            vanishPending = game.vanishPending - target,
            scoredTags = game.scoredTags + key,
        )
        return Transition(g, awards = mentored(g, awards))
    }

    private fun parole(game: Game, now: Millis): Transition {
        val due = game.players.values.filter { p ->
            val ms = perks(p).paroleMs
            p.jailedAt != null && ms != null && !p.disqualified && p.reportedAt != null && now >= p.jailedAt + ms
        }
        if (due.isEmpty()) return Transition(game)
        return Transition(
            game.copy(players = game.players + due.associate { it.id to it.freed() }),
            notices = due.map { "${it.user.displayName} is out on parole." },
        )
    }

    /**
     * Frees a jailed player. Jailbreak rules are not yet specified; this is the hook
     * whatever mechanic is chosen will call once it verifies the rescue.
     */
    fun release(game: Game, player: PlayerId): Transition {
        val p = game.players[player]?.takeIf { it.isJailed && !it.disqualified } ?: return game.reject("Not jailed")
        return Transition(game.copy(players = game.players + (player to p.freed())))
    }

    /**
     * Starts a jailbreak: a free player photographs the enemy jail, then must stay within range
     * of it for [GameRules.JAILBREAK_HOLD] unbroken. Leaving, or being jailed, ends the attempt.
     * On completion every teammate held (reported or en route, never the disqualified) walks free.
     */
    fun jailbreak(game: Game, by: PlayerId, photo: PhotoEvidence, now: Millis): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val v = Verification.jailbreak(game, by, photo, now)
        if (v != Verdict.Valid) return Transition(game, v)
        val rescuer = game.players.getValue(by)
        if (rescuer.breakoutSince != null) return game.reject("Jailbreak already under way")
        if (game.team(rescuer.team).none { it.isJailed && !it.disqualified }) return game.reject("No one to free")
        return Transition(game.copy(players = game.players + (by to rescuer.copy(breakoutSince = now))))
    }

    /** A rescuer's fix: still at the jail keeps the attempt alive; the full hold springs everyone. */
    private fun holdBreakout(game: Game, rescuer: Player, fix: LocationFix): Transition {
        val since = rescuer.breakoutSince ?: return Transition(game)
        val jail = game.jails[rescuer.team.opponent]
        val there = jail != null && fix.accuracyM <= GameRules.MAX_FIX_ACCURACY_M &&
            fix.point.distanceTo(jail.location) <= GameRules.JAIL_REPORT_RADIUS_M
        if (!there) return Transition(game.copy(players = game.players + (rescuer.id to rescuer.copy(breakoutSince = null))))
        if (fix.at - since < GameRules.JAILBREAK_HOLD) return Transition(game)
        val done = game.copy(players = game.players + (rescuer.id to rescuer.copy(breakoutSince = null)))
        val freed = done.team(rescuer.team).filter { it.isJailed && !it.disqualified }
        if (freed.isEmpty()) return Transition(done)
        return Transition(
            done.copy(players = done.players + freed.associate { it.id to it.freed() }),
            awards = listOf(done.award(rescuer.id, Points.JAILBREAK_PER_FREED.toLong() * freed.size, "Freed ${freed.size}", fix.at)),
            notices = listOf("${rescuer.user.displayName} broke ${freed.size} out of jail."),
        )
    }

    private fun Player.freed() = copy(jailedAt = null, jailDeadline = null, reportingSince = null, reportedAt = null)

    /** Flag moved (or any other disqualifying breach) as determined by moderation or detection. */
    fun forfeit(game: Game, loser: Team, reason: String, now: Millis): Transition =
        if (game.phase is GamePhase.Ended) game.reject("Game already over") else end(game, Outcome.Forfeit(loser, reason), now).settled()

    private fun end(game: Game, outcome: Outcome, now: Millis): Transition {
        // The freeze lifts at the final whistle, except for the disqualified.
        val game = game.copy(players = game.players.mapValues { (_, p) -> if (p.isJailed && !p.disqualified) p.freed() else p })
        val awards = when (outcome) {
            is Outcome.FlagCaptured -> buildList {
                add(game.award(outcome.by, Points.FLAG_CAPTURE.toLong(), "Captured the flag", now))
                game.team(outcome.winner).forEach { add(game.award(it.id, Points.TEAM_WIN.toLong(), "Team won", now)) }
                game.team(outcome.winner).filter { it.isLeader }
                    .forEach { add(game.award(it.id, Points.FLAG_HELD.toLong(), "Flag held", now)) }
            }
            Outcome.Tie -> game.players.values.flatMap { p ->
                listOfNotNull(
                    game.award(p.id, Points.TIE.toLong(), "Tie", now),
                    if (p.isLeader && p.team in game.flags) game.award(p.id, Points.FLAG_HELD.toLong(), "Flag held", now) else null,
                )
            }
            is Outcome.Forfeit -> game.team(outcome.loser.opponent)
                .map { game.award(it.id, Points.TEAM_WIN.toLong(), "Opponent forfeited", now) }
            Outcome.Cancelled -> emptyList()
        }
        return Transition(
            game.copy(phase = GamePhase.Ended(outcome, now), incursions = emptyMap(), decoyWalks = emptyList()),
            awards = mentored(game, awards),
        )
    }

    /** Mentor: a higher-level teammate within range pays a share of each positive award as a bonus. */
    private fun mentored(game: Game, awards: List<Award>): List<Award> = awards + awards.mapNotNull { a ->
        val p = game.players[a.user] ?: return@mapNotNull null
        val at = game.lastFix[p.id]?.point ?: return@mapNotNull null
        if (a.points <= 0) return@mapNotNull null
        val best = game.team(p.team)
            .filter { m -> m.id != p.id && m.level > p.level && perks(m).mentorShare > 0 && near(game, m.id, at, MENTOR_RANGE_M) }
            .maxByOrNull { perks(it).mentorShare } ?: return@mapNotNull null
        val bonus = (a.points * perks(best).mentorShare).toLong()
        if (bonus > 0) a.copy(points = bonus, reason = "Mentored by ${best.user.displayName}") else null
    }

    /**
     * Frozen players earn nothing: positive awards to anyone jailed (or disqualified) in the
     * resulting state are dropped. What survives is added to the round's running totals.
     */
    private fun Transition.settled(): Transition {
        val paid = awards.filter { a -> a.points <= 0 || game.players[a.user]?.isJailed != true }
        if (paid.isEmpty()) return copy(awards = paid)
        val earned = game.earned.toMutableMap()
        paid.forEach { earned[it.user] = (earned[it.user] ?: 0L) + it.points }
        return copy(game = game.copy(earned = earned), awards = paid)
    }

    private fun perks(p: Player) = Progression.perksFor(p.level)

    private fun Game.award(user: PlayerId, points: Long, reason: String, at: Millis) =
        Award(user, city.id, id, points, reason, at)

    private fun Game.reject(reason: String) = Transition(this, Verdict.Rejected(reason))

    companion object {
        const val DECOY_STEP = 5 * GameRules.MINUTE
        const val DECOY_STRIDE_M = 150.0
        const val MENTOR_RANGE_M = 200.0

        /**
         * Flag Sense: a circle guaranteed to contain the enemy flag. Its offset is fixed per
         * (game, player) so repeated looks can't be averaged down to the true spot.
         */
        fun flagSense(game: Game, player: PlayerId): Pair<GeoPoint, Double>? {
            val p = game.players[player] ?: return null
            val r = Progression.perksFor(p.level).flagSenseM
            val flag = game.flags[p.team.opponent] ?: return null
            if (r <= 0) return null
            return offset(flag.location, r * 0.8, Random((game.id + player).hashCode())) to r
        }

        /** A point up to [radiusM] from [p] (exactly [radiusM] if [exact]) in a random direction. */
        internal fun offset(p: GeoPoint, radiusM: Double, rnd: Random, exact: Boolean = false): GeoPoint {
            if (radiusM <= 0) return p
            val bearing = rnd.nextDouble() * 2 * PI
            val d = if (exact) radiusM else radiusM * kotlin.math.sqrt(rnd.nextDouble())
            val dLat = d * cos(bearing) / 111_320.0
            val dLng = d * sin(bearing) / (111_320.0 * cos(p.lat * PI / 180))
            return GeoPoint(p.lat + dLat, p.lng + dLng)
        }
    }
}
