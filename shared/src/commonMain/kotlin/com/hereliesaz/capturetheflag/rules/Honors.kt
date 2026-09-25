package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.RoundStats
import com.hereliesaz.capturetheflag.model.Team

/**
 * The round's superlatives. Each is held live by whoever leads it, shared on a tie, and pays
 * [bonus] points to every holder when the round ends. The disqualified hold nothing.
 */
enum class Most(
    val title: String,
    val blurb: String,
    val bonus: Int,
    /** The number that decides it; null means the player doesn't qualify. */
    val measure: (RoundStats) -> Int?,
    val lowerWins: Boolean = false,
) {
    COLLECTOR("The Collector", "most tags", 20, { it.tags.takeIf { n -> n > 0 } }),
    LOCKSMITH("The Locksmith", "most teammates freed", 20, { it.freed.takeIf { n -> n > 0 } }),
    MOST_WANTED("Most Wanted", "most pings endured and survived", 15, { it.pingsSurvived.takeIf { n -> n > 0 } }),
    DEEP_COVER("Deep Cover", "longest single incursion survived", 15, { it.deepest.takeIf { n -> n > 0 } }),
    BOUNTY_HUNTER("Bounty Hunter", "most bounties cashed", 15, { it.bountiesCashed.takeIf { n -> n > 0 } }),
    PHOTO_FINISH("Photo Finish", "closest jail check-in", 10, { it.closestReportSec }, lowerWins = true),
    FREQUENT_FLYER("Frequent Flyer", "most times jailed", 5, { it.timesJailed.takeIf { n -> n > 0 } }),
    ALMOST("Almost", "most shots that didn't count", 5, { it.nearMisses.takeIf { n -> n > 0 } }),
}

data class Honor(val most: Most, val holders: List<PlayerId>, val value: Int)

object Honors {
    /** Points to each team's MVP at the end of the round. */
    const val MVP_BONUS = 30

    /** The list for one Most: everyone who qualifies, best first, top [n]. Holders share the top value. */
    fun board(game: Game, most: Most, n: Int = 3): List<Pair<PlayerId, Int>> =
        game.stats.filterKeys { game.players[it]?.disqualified == false }
            .mapNotNull { (id, s) -> most.measure(s)?.let { id to it } }
            .sortedWith(if (most.lowerWins) compareBy({ it.second }, { it.first }) else compareBy({ -it.second }, { it.first }))
            .take(n)

    /** Current holder(s) of every Most that anyone qualifies for. */
    fun mosts(game: Game): List<Honor> {
        val eligible = game.stats.filterKeys { id -> game.players[id]?.disqualified == false }
        return Most.entries.mapNotNull { m ->
            val scored = eligible.mapNotNull { (id, s) -> m.measure(s)?.let { id to it } }
            if (scored.isEmpty()) return@mapNotNull null
            val best = if (m.lowerWins) scored.minOf { it.second } else scored.maxOf { it.second }
            Honor(m, scored.filter { it.second == best }.map { it.first }.sorted(), best)
        }
    }

    /**
     * Each team's MVP: most points earned this round, then most tags, then most freed.
     * Nobody is MVP on zero or fewer points, and the disqualified never are.
     */
    fun mvps(game: Game): Map<Team, PlayerId> = Team.entries.mapNotNull { t ->
        game.team(t).filter { !it.disqualified && (game.earned[it.id] ?: 0) > 0 }
            .maxWithOrNull(compareBy<com.hereliesaz.capturetheflag.model.Player>(
                { game.earned[it.id] ?: 0 },
                { game.stats[it.id]?.tags ?: 0 },
                { game.stats[it.id]?.freed ?: 0 },
            ).thenByDescending { it.id })
            ?.let { t to it.id }
    }.toMap()
}
