package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.StreamPurpose

/**
 * The game as one person may see it. Referees hold the whole [Game]; each player gets this,
 * sealed to them, after every batch; onlookers get it with no viewer. The phone renders it and
 * nothing more, so whatever isn't here can't leak through the app.
 *
 * Public: the city and its split, the phase, the roster (teams, roles, levels, who's jailed),
 * both jails, streams (everybody watches), round stats and points, appeals used.
 *
 * Yours alone: your position and incursion, your perks' use, your trails and decoys, pings
 * you've been sent, whether you're near the enemy flag or gone dark.
 *
 * Your team's: your own flag, your team's bounties.
 *
 * Never: the enemy flag, anyone else's position or incursion. A flag run's target is the
 * enemy flag, so only the defenders see it; everyone else sees the streamer's own last frame.
 */
object GameView {
    fun of(game: Game, viewer: PlayerId?): Game {
        val me = viewer?.let { game.players[it] }
        fun <V> mine(m: Map<PlayerId, V>) = if (me == null) emptyMap() else m.filterKeys { it == me.id }
        return game.copy(
            territory = game.territory.copy(cells = emptyList()),
            flags = if (me == null) emptyMap() else game.flags.filterKeys { it == me.team },
            lastFix = mine(game.lastFix),
            incursions = mine(game.incursions),
            scoredTags = game.scoredTags.filterTo(mutableSetOf()) { me != null && it.first == me.id },
            decoysUsed = mine(game.decoysUsed),
            interrogationsUsed = mine(game.interrogationsUsed),
            vanishesUsed = mine(game.vanishesUsed),
            vanishPending = game.vanishPending.filterTo(mutableSetOf()) { it == me?.id },
            lastStandsUsed = mine(game.lastStandsUsed),
            bounties = if (me == null) emptyMap() else game.bounties.filterKeys { game.players[it]?.team == me.team },
            trails = game.trails.filterKeys { it.first == me?.id },
            decoyWalks = game.decoyWalks.filter { it.sender == me?.id },
            pingedAbout = mine(game.pingedAbout),
            streams = game.streams.mapValues { (_, s) ->
                val defender = me != null && game.players[s.by]?.team == me.team.opponent
                if (s.purpose == StreamPurpose.CAPTURE && !defender) s.copy(target = s.lastFrame.point) else s
            },
            flagZone = mine(game.flagZone),
            dark = game.dark.filterTo(mutableSetOf()) { it == me?.id },
        )
    }
}
