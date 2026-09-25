package com.hereliesaz.capturetheflag.engine

import com.hereliesaz.capturetheflag.geo.GeoPoint
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
import com.hereliesaz.capturetheflag.rules.TeamAssignment
import com.hereliesaz.capturetheflag.rules.Verdict
import com.hereliesaz.capturetheflag.rules.Verification
import kotlin.random.Random

/** New game state plus side effects the caller must deliver. */
data class Transition(
    val game: Game,
    val verdict: Verdict = Verdict.Valid,
    val pings: List<Ping> = emptyList(),
)

/**
 * The whole rulebook as pure functions of (state, input, time, randomness).
 * Intended to run server-side, where it is authoritative; clients only render its output.
 */
class GameEngine(private val random: Random = Random.Default) {

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
        val players = TeamAssignment.assign(game.signups, random)
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
        return duePings(next, fix.at)
    }

    private fun duePings(game: Game, now: Millis): Transition {
        val pings = mutableListOf<Ping>()
        val incursions = game.incursions.mapValues { (id, inc) ->
            var cur = inc
            while (PingSchedule.dueAt(cur.enteredAt, cur.pingsSent + 1) <= now) {
                val n = cur.pingsSent + 1
                val subject = game.players.getValue(id)
                val fix = game.lastFix[id] ?: break
                val enemies = game.team(subject.team.opponent).filterNot { it.isJailed }.map { it.id }
                pings += Ping(
                    subject = id,
                    number = n,
                    location = fix.point,
                    at = PingSchedule.dueAt(cur.enteredAt, n),
                    identified = subject.user.takeIf { PingSchedule.identifies(n) },
                    recipients = PingSchedule.recipients(enemies, random),
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

    fun tag(game: Game, by: PlayerId, target: PlayerId, photo: PhotoEvidence, now: Millis, ble: BleTokenRegistry): Transition {
        if (game.phase !is GamePhase.Active) return game.reject("Game is not live")
        val v = Verification.tag(game, by, target, photo, now, ble)
        if (v != Verdict.Valid) return Transition(game, v)
        val jailed = game.players.getValue(target).copy(jailedAt = now)
        return Transition(game.copy(players = game.players + (target to jailed), incursions = game.incursions - target))
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

    private fun end(game: Game, outcome: Outcome, now: Millis) =
        Transition(game.copy(phase = GamePhase.Ended(outcome, now), incursions = emptyMap()))

    private fun Game.reject(reason: String) = Transition(this, Verdict.Rejected(reason))
}
