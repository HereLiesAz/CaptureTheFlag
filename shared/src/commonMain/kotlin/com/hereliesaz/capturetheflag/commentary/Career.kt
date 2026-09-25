package com.hereliesaz.capturetheflag.commentary

import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.GameId
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.rules.Progression

/** A player's record, read off the points ledger. Everything the booth may bring up on air. */
data class Career(
    val points: Long = 0,
    val tags: Int = 0,
    val timesJailed: Int = 0,
    val captures: Int = 0,
    val captureCities: List<String> = emptyList(),
    val freed: Int = 0,
    val biggestBreakout: Int = 0,
    val longestSurvival: Int = 0,
    val disqualifications: Int = 0,
    val wins: Int = 0,
    val rounds: Int = 0,
    /** Consecutive results ending with the latest finished round: positive = wins, negative = losses. Ties break it. */
    val streak: Int = 0,
) {
    val level: Int get() = Progression.levelFor(points)
    val rookie: Boolean get() = rounds == 0
    /** Levels gained per finished round. */
    val pace: Double get() = if (rounds == 0) 0.0 else (level - 1).toDouble() / rounds

    companion object {
        val NONE = Career()

        /** [cityName] maps a city id to its display name. Rounds still in play, [excluding], are left out. */
        fun from(ledger: List<Award>, user: PlayerId, excluding: Set<GameId> = emptySet(), cityName: (String) -> String = { it }): Career {
            val all = ledger.filter { it.user == user }
            val past = all.filter { it.game !in excluding }
            return Career(
                points = all.sumOf { it.points },
                tags = past.count { it.points > 0 && it.reason.startsWith("Jailed ") },
                timesJailed = past.count { it.reason == "Jailed" },
                captures = past.count { it.reason == "Captured the flag" },
                captureCities = past.filter { it.reason == "Captured the flag" }.map { cityName(it.city) },
                freed = past.filter { it.reason.startsWith("Freed ") }.sumOf { it.reason.removePrefix("Freed ").toIntOrNull() ?: 0 },
                biggestBreakout = past.filter { it.reason.startsWith("Freed ") }.maxOfOrNull { it.reason.removePrefix("Freed ").toIntOrNull() ?: 0 } ?: 0,
                longestSurvival = past.filter { it.reason.startsWith("Survived ") }
                    .maxOfOrNull { it.reason.removePrefix("Survived ").substringBefore(' ').toIntOrNull() ?: 0 } ?: 0,
                disqualifications = past.count { it.reason.startsWith("Disqualified") },
                wins = past.count { it.reason == "Team won" || it.reason == "Opponent forfeited" },
                rounds = past.map { it.game }.distinct().size,
                streak = streak(past),
            )
        }

        /** Rounds in the order played (first award), each a win, tie or loss. */
        private fun streak(past: List<Award>): Int {
            val results = past.groupBy { it.game }.values.sortedBy { round -> round.minOf { it.at } }.map { round ->
                when {
                    round.any { it.reason == "Team won" || it.reason == "Opponent forfeited" } -> 1
                    round.any { it.reason == "Tie" } -> 0
                    else -> -1
                }
            }
            val last = results.lastOrNull() ?: return 0
            if (last == 0) return 0
            return results.takeLastWhile { it == last }.size * last
        }
    }
}
