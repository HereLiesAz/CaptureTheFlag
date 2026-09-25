package com.hereliesaz.capturetheflag.engine

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.Flag
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GameId
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Incursion
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Ping
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
import kotlin.random.Random

/** New game state plus side effects the caller must deliver. */
data class Transition(
    val game: Game,
    val verdict: Verdict = Verdict.Valid,
    val pings: List<Ping> = emptyList(),
    /** Points earned by this transition. The caller appends them to the ledger. */
    val awards: List<Award> = emptyList(),
)

/**
 * The whole rulebook as pure functions of (state, input, time, randomness).
 * Intended to run server-side, where it is authoritative; clients only render its output.
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

    /** Advances timed phases and emits due pings. Call on a schedule and after every input. */
    fun tick(game: Game, now: Millis): Transition = when (val ph = game.phase) {
        is GamePhase.Signup -> if (now < ph.deadline) Transition(game) else closeSignup(game, now)
        is GamePhase.FlagPlacement -> if (now < ph.deadline) Transition(game) else closePlacement(game, now)
        is GamePhase.Active -> if (now >= ph.deadline) end(game, Outcome.Tie, now) else duePings(game, now)
        is GamePhase.Ended -> Transition(game)
    }

    private fun closeSignup(game: Game, now: Millis): Transition {
        if (game.signups.size < 2 * GameRules.MIN_PLAYERS_PER_TEAM) return end(game, Outcome.Cancelled, now)
        val players = TeamAssignment.assign(game.signups, random).mapValues { (id, p) -> p.copy(level = levelOf(id)) }
        return Transition(
            game.copy(players = players, phase = GamePhase.FlagPlacement(now + GameRules.FLAG_PLACEMENT_WINDOW)),
        )
    }

    private fun closePlacement(game: Game, now: Millis): Transition {
        val missing = Team.entries.filter { it !in game.flags }
        return when (missing.size) {
            0 -> Transition(game.copy(phase = GamePhase.Active(now + GameRules.PLAY_WINDOW)))
            1 -> end(game, Outcome.Forfeit(missing.single(), "No flag placed in time"), now)
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
        if (game.phase !is GamePhase.FlagPlacement) return game.reject("Flag placement window is closed")
        val v = Verification.flagRegistration(game, by, venue, photo, now)
        if (v != Verdict.Valid) return Transition(game, v)
        val team = game.players.getValue(by).team
        val flag = Flag(team, venueName, kind, address, venue, photo, by, now)
        val placed = game.copy(flags = game.flags + (team to flag))
        // Both flags down early: start the clock now rather than idling out the hour.
        return if (placed.flags.size == Team.entries.size) {
            Transition(placed.copy(phase = GamePhase.Active(now + GameRules.PLAY_WINDOW)))
        } else Transition(placed)
    }

    /** Ingests a device fix; opens or closes incursions as players cross the line. */
    fun reportLocation(game: Game, player: PlayerId, fix: LocationFix): Transition {
        val p = game.players[player] ?: return game.reject("Not in this game")
        val g = game.copy(lastFix = game.lastFix + (player to fix))
        if (g.phase !is GamePhase.Active || p.isJailed) return Transition(g.copy(incursions = g.incursions - player))
        val inEnemy = g.territory.ownerOf(fix.point) == p.team.opponent
        val open = g.incursions[player]
        val next = when {
            inEnemy && open == null -> g.copy(incursions = g.incursions + (player to Incursion(player, fix.at)))
            !inEnemy && open != null -> g.copy(incursions = g.incursions - player)
            else -> g
        }
        // Made it home unjailed: paid per ping endured. Leaving the city pays nothing.
        val survived = if (open != null && !inEnemy && g.territory.ownerOf(fix.point) == p.team && open.pingsSent > 0) {
            listOf(g.award(player, Points.PER_PING_SURVIVED.toLong() * open.pingsSent, "Survived ${open.pingsSent} pings", fix.at))
        } else emptyList()
        return duePings(next, fix.at).let { it.copy(awards = it.awards + survived) }
    }

    private fun duePings(game: Game, now: Millis): Transition {
        val pings = mutableListOf<Ping>()
        val incursions = game.incursions.mapValues { (id, inc) ->
            var cur = inc
            val subject = game.players.getValue(id)
            val perks = Progression.perksFor(subject.level)
            while (PingSchedule.dueAt(cur.enteredAt, cur.pingsSent + 1, perks) <= now) {
                val n = cur.pingsSent + 1
                val fix = game.lastFix[id] ?: break
                val enemies = game.team(subject.team.opponent).filterNot { it.isJailed }
                // Veterans with proximity alerts always hear about intruders near them.
                val watchers = enemies.filter { e ->
                    val r = Progression.perksFor(e.level).proximityAlertM
                    r > 0 && game.lastFix[e.id]?.point?.distanceTo(fix.point)?.let { it <= r } == true
                }.map { it.id }
                pings += Ping(
                    subject = id,
                    number = n,
                    location = fix.point,
                    at = PingSchedule.dueAt(cur.enteredAt, n, perks),
                    identified = subject.user.takeIf { PingSchedule.identifies(n, perks) },
                    recipients = PingSchedule.recipients(enemies.map { it.id }, random) + watchers,
                )
                cur = cur.copy(pingsSent = n)
            }
            cur
        }
        return Transition(game.copy(incursions = incursions), pings = pings)
    }

    fun captureFlag(game: Game, by: PlayerId, photo: PhotoEvidence, now: Millis): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val v = Verification.flagCapture(game, by, photo, now)
        if (v != Verdict.Valid) return Transition(game, v)
        return end(game, Outcome.FlagCaptured(game.players.getValue(by).team, by), now)
    }

    /**
     * Sends a fake anonymous ping to the enemy from [at], which must be in their territory.
     * Indistinguishable from a real first ping. Limited by the sender's perks.
     */
    fun decoy(game: Game, by: PlayerId, at: GeoPoint, now: Millis): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val p = game.players[by] ?: return game.reject("Not in this game")
        if (p.isJailed) return game.reject("Jailed players cannot send decoys")
        val used = game.decoysUsed[by] ?: 0
        if (used >= Progression.perksFor(p.level).decoysPerGame) return game.reject("No decoys left")
        if (game.territory.ownerOf(at) != p.team.opponent) return game.reject("Decoys must land in enemy territory")
        val enemies = game.team(p.team.opponent).filterNot { it.isJailed }.map { it.id }
        val ping = Ping(by, 1, at, now, null, PingSchedule.recipients(enemies, random))
        return Transition(game.copy(decoysUsed = game.decoysUsed + (by to used + 1)), pings = listOf(ping))
    }

    fun tag(game: Game, by: PlayerId, target: PlayerId, photo: PhotoEvidence, now: Millis, ble: BleTokenRegistry): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val v = Verification.tag(game, by, target, photo, now, ble)
        if (v != Verdict.Valid) return Transition(game, v)
        val victim = game.players.getValue(target)
        val key = by to target
        val awards = if (key in game.scoredTags) emptyList() else listOf(
            game.award(by, Progression.tagValue(victim.level), "Jailed ${victim.user.displayName}", now),
            game.award(target, Points.JAILED.toLong(), "Jailed", now),
        )
        return Transition(
            game.copy(
                players = game.players + (target to victim.copy(jailedAt = now)),
                incursions = game.incursions - target,
                scoredTags = game.scoredTags + key,
            ),
            awards = awards,
        )
    }

    /**
     * Frees a jailed player. Jailbreak rules are not yet specified; this is the hook
     * whatever mechanic is chosen will call once it verifies the rescue.
     */
    fun release(game: Game, player: PlayerId): Transition {
        val p = game.players[player]?.takeIf { it.isJailed } ?: return game.reject("Not jailed")
        return Transition(game.copy(players = game.players + (player to p.copy(jailedAt = null))))
    }

    /** Flag moved (or any other disqualifying breach) as determined by moderation or detection. */
    fun forfeit(game: Game, loser: Team, reason: String, now: Millis): Transition =
        if (game.phase is GamePhase.Ended) game.reject("Game already over") else end(game, Outcome.Forfeit(loser, reason), now)

    private fun end(game: Game, outcome: Outcome, now: Millis): Transition {
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
        return Transition(game.copy(phase = GamePhase.Ended(outcome, now), incursions = emptyMap()), awards = awards)
    }

    private fun Game.award(user: PlayerId, points: Long, reason: String, at: Millis) =
        Award(user, city.id, id, points, reason, at)

    private fun Game.reject(reason: String) = Transition(this, Verdict.Rejected(reason))
}
