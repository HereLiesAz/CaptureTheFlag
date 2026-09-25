package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import com.hereliesaz.capturetheflag.rules.Leaderboard
import com.hereliesaz.capturetheflag.rules.PingSchedule
import com.hereliesaz.capturetheflag.rules.Progression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionTest {
    @Test fun levelsAreUnboundedAndEachCostsMore() {
        val steps = (1..200).map { Progression.pointsFor(it + 1) - Progression.pointsFor(it) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1, Progression.levelFor(0))
        for (l in listOf(1, 2, 7, 50, 500)) {
            assertEquals(l, Progression.levelFor(Progression.pointsFor(l)))
            if (l > 1) assertEquals(l - 1, Progression.levelFor(Progression.pointsFor(l) - 1))
        }
    }

    @Test fun milestonesSpreadOutAndPerksGrow() {
        val m = (1..10).map { Progression.milestone(it) }
        assertEquals(listOf(2, 6, 12, 20, 30), m.take(5))
        assertTrue(m.zipWithNext { a, b -> b - a }.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0, Progression.tierFor(1))
        assertEquals(1, Progression.tierFor(5))
        assertEquals(2, Progression.tierFor(6))
        val low = Progression.perksFor(6); val high = Progression.perksFor(42)
        assertTrue(high.firstPingDelayMs > low.firstPingDelayMs && high.proximityAlertM > low.proximityAlertM)
        assertTrue(Progression.perksFor(10_000).decoysPerGame > high.decoysPerGame)
    }

    @Test fun perksDelayPings() {
        val p = Progression.perksFor(Progression.milestone(2))
        assertEquals(70 * MINUTE, PingSchedule.dueAt(0, 2, p))
        assertTrue(!PingSchedule.identifies(6, p) && PingSchedule.identifies(7, p))
    }

    @Test fun higherLevelTargetsPayMore() {
        assertTrue(Progression.tagValue(30) > Progression.tagValue(3))
        assertEquals(12, Progression.tagValue(1))
    }

    @Test fun boardsRankGloballyAndPerCity() {
        fun a(u: String, c: String, p: Long) = Award(u, c, "g", p, "", 0)
        val ledger = listOf(a("x", "nola", 50), a("y", "nola", 50), a("y", "nyc", 500), a("z", "nyc", 10))
        assertEquals(listOf(1, 1), Leaderboard.city(ledger, "nola").map { it.rank })
        assertEquals(listOf("y", "x", "z"), Leaderboard.global(ledger).map { it.user })
        // City board shows lifetime level, not city-only level.
        assertEquals(Progression.levelFor(550), Leaderboard.city(ledger, "nola").first { it.user == "y" }.level)
    }
}
