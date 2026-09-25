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
     * Which team receives the odd player is itself random. Veterans are dealt first, so they
     * split evenly and each team gets one whenever there are two. One random captain per team.
     *
     * Rookies (never finished a round) don't lead unless everyone playing is a rookie. A team
     * left with only rookies while the other side has veterans (one veteran in the whole game)
     * has no choice, and gets a rookie captain.
     */
    fun assign(
        signups: List<User>,
        random: Random,
        levelOf: (PlayerId) -> Int = { 1 },
        isRookie: (PlayerId) -> Boolean = { false },
    ): Map<PlayerId, Player> {
        val first = if (random.nextBoolean()) Team.NOIR else Team.BLANC
        val (rookies, veterans) = signups.distinctBy { it.id }.shuffled(random).partition { isRookie(it.id) }
        val dealt = (veterans + rookies).mapIndexed { i, u ->
            Player(u, if (i % 2 == 0) first else first.opponent, level = levelOf(u.id), rookie = isRookie(u.id))
        }
        // Among veterans, standing tilts the draw toward the higher levels without guaranteeing it.
        val captains = Team.entries.mapNotNull { t ->
            val team = dealt.filter { it.team == t }
            val pool = team.filterNot { it.rookie }.ifEmpty { team }
            weightedPick(pool, random) { 1.0 + Progression.perksFor(it.level).standingWeight }?.id
        }.toSet()
        return dealt.associate { p -> p.id to if (p.id in captains) p.copy(role = Role.CAPTAIN) else p }
    }

    /**
     * Captain names up to [GameRules.MAX_CO_CAPTAINS] teammates. Replaces any previous choice.
     * No rookies, unless everyone in the game is one. Returns null if the request is invalid.
     */
    fun appointCoCaptains(
        players: Map<PlayerId, Player>,
        captainId: PlayerId,
        picks: Set<PlayerId>,
    ): Map<PlayerId, Player>? {
        val captain = players[captainId]?.takeIf { it.role == Role.CAPTAIN } ?: return null
        if (picks.size > GameRules.MAX_CO_CAPTAINS || captainId in picks) return null
        if (picks.any { players[it]?.team != captain.team }) return null
        if (players.values.any { !it.rookie } && picks.any { players.getValue(it).rookie }) return null
        return players.mapValues { (id, p) ->
            when {
                p.team != captain.team || p.role == Role.CAPTAIN -> p
                id in picks -> p.copy(role = Role.CO_CAPTAIN)
                else -> p.copy(role = Role.PLAYER)
            }
        }
    }

    private fun <T> weightedPick(items: List<T>, random: Random, weight: (T) -> Double): T? {
        if (items.isEmpty()) return null
        var roll = random.nextDouble() * items.sumOf(weight)
        for (it in items) { roll -= weight(it); if (roll < 0) return it }
        return items.last()
    }
}
