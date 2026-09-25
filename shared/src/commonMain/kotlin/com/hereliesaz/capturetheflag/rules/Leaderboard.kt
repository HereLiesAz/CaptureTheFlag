package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.CityId
import com.hereliesaz.capturetheflag.model.PlayerId

data class Standing(val rank: Int, val user: PlayerId, val points: Long, val level: Int)

/**
 * Ranks users from the award ledger. Level (and therefore perks) comes from global
 * lifetime points; city boards rank only the points earned in that city.
 * Ties share a rank ("1, 1, 3").
 */
object Leaderboard {
    fun totals(ledger: List<Award>, city: CityId? = null): Map<PlayerId, Long> =
        ledger.asSequence().filter { city == null || it.city == city }
            .groupBy { it.user }.mapValues { (_, a) -> a.sumOf { it.points } }

    fun global(ledger: List<Award>): List<Standing> = rank(totals(ledger), totals(ledger))

    fun city(ledger: List<Award>, city: CityId): List<Standing> = rank(totals(ledger, city), totals(ledger))

    fun levelOf(ledger: List<Award>, user: PlayerId): Int =
        Progression.levelFor(ledger.filter { it.user == user }.sumOf { it.points })

    private fun rank(board: Map<PlayerId, Long>, lifetime: Map<PlayerId, Long>): List<Standing> {
        val sorted = board.entries.sortedWith(compareByDescending<Map.Entry<PlayerId, Long>> { it.value }.thenBy { it.key })
        var rank = 0
        var prev: Long? = null
        return sorted.mapIndexed { i, (user, pts) ->
            if (pts != prev) { rank = i + 1; prev = pts }
            Standing(rank, user, pts, Progression.levelFor(lifetime[user] ?: 0))
        }
    }
}
