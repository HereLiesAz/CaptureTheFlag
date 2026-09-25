package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.User
import kotlin.random.Random

object TeamAssignment {
    /**
     * Shuffles sign-ups and deals them alternately, so team sizes differ by at most one.
     * Which team receives the odd player is itself random. One random captain per team.
     */
    fun assign(signups: List<User>, random: Random): Map<PlayerId, Player> {
        val first = if (random.nextBoolean()) Team.NOIR else Team.BLANC
        val dealt = signups.distinctBy { it.id }.shuffled(random).mapIndexed { i, u ->
            Player(u, if (i % 2 == 0) first else first.opponent)
        }
        val captains = Team.entries.mapNotNull { t -> dealt.filter { it.team == t }.randomOrNull(random)?.id }.toSet()
        return dealt.associate { p -> p.id to if (p.id in captains) p.copy(role = Role.CAPTAIN) else p }
    }

    /**
     * Captain names up to [GameRules.MAX_CO_CAPTAINS] teammates. Replaces any previous choice.
     * Returns null if the request is invalid.
     */
    fun appointCoCaptains(
        players: Map<PlayerId, Player>,
        captainId: PlayerId,
        picks: Set<PlayerId>,
    ): Map<PlayerId, Player>? {
        val captain = players[captainId]?.takeIf { it.role == Role.CAPTAIN } ?: return null
        if (picks.size > GameRules.MAX_CO_CAPTAINS || captainId in picks) return null
        if (picks.any { players[it]?.team != captain.team }) return null
        return players.mapValues { (id, p) ->
            when {
                p.team != captain.team || p.role == Role.CAPTAIN -> p
                id in picks -> p.copy(role = Role.CO_CAPTAIN)
                else -> p.copy(role = Role.PLAYER)
            }
        }
    }
}
