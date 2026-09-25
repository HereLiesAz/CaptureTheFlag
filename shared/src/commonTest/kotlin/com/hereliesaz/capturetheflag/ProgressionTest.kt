package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import com.hereliesaz.capturetheflag.rules.Leaderboard
import com.hereliesaz.capturetheflag.rules.PerkStart
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

    @Test fun milestonesAreMultiplesOfFiveAndSpreadOut() {
        val m = (1..12).map { Progression.milestone(it) }
        assertEquals(listOf(5, 15, 30, 50, 75, 105, 140, 180), m.take(8))
        assertTrue(m.all { it % 5 == 0 })
        assertTrue(m.zipWithNext { a, b -> b - a }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun multiplesOfTenGrantDoublePower() {
        assertEquals(0, Progression.powerFor(4))
        assertEquals(1, Progression.powerFor(5))
        assertEquals(2, Progression.powerFor(15))
        assertEquals(4, Progression.powerFor(30))   // 30 is a multiple of 10: +2
        assertEquals(7, Progression.powerFor(75))   // 50 → +2, 75 → +1
        assertEquals(20, Progression.powerFor(525))
    }

    @Test fun nothingBeforeLevelFiveAndEarlyPerksAreTiny() {
        (1..4).forEach { assertEquals(Progression.perksFor(1), Progression.perksFor(it)) }
        val none = Progression.perksFor(1)
        assertEquals(0, none.firstPingDelayMs); assertEquals(0.0, none.proximityAlertM)
        val early = Progression.perksFor(5)
        val late = Progression.perksFor(Progression.levelForPower(10))
        assertEquals(1 * MINUTE, early.firstPingDelayMs)
        assertEquals(25.0, early.proximityAlertM)
        assertTrue(late.firstPingDelayMs >= 50 * early.firstPingDelayMs)
        assertTrue(late.proximityAlertM >= 50 * early.proximityAlertM)
    }

    @Test fun perksStartAtTheirPowerAndOnlyGrow() {
        val atStart = Progression.perksFor(Progression.levelForPower(PerkStart.FLAG_SENSE))
        val before = Progression.perksFor(Progression.levelForPower(PerkStart.FLAG_SENSE) - 1)
        assertEquals(0.0, before.flagSenseM)
        assertEquals(3_000.0, atStart.flagSenseM)
        assertTrue(Progression.perksFor(10_000).flagSenseM < atStart.flagSenseM)
        assertTrue(Progression.perksFor(10_000).decoysPerGame > Progression.perksFor(Progression.levelForPower(PerkStart.DECOY)).decoysPerGame)
    }

    @Test fun perksDelayPings() {
        val p = Progression.perksFor(Progression.levelForPower(PerkStart.IDENTITY_DELAY))
        assertEquals(76 * MINUTE, PingSchedule.dueAt(0, 2, p)) // power 4 at level 30: 4² = 16 min
        assertTrue(!PingSchedule.identifies(6, p) && PingSchedule.identifies(7, p))
    }

    @Test fun higherLevelTargetsPayMoreUntilLegacy() {
        assertTrue(Progression.tagValue(30) > Progression.tagValue(3))
        assertEquals(12, Progression.tagValue(1))
        val legacy = Progression.levelForPower(PerkStart.LEGACY)
        assertTrue(Progression.tagValue(legacy * 3) <= Progression.tagValue(legacy))
    }

    @Test fun boardsRankGloballyAndPerCity() {
        fun a(u: String, c: String, p: Long) = Award(u, c, "g", p, "", 0)
        val ledger = listOf(a("x", "nola", 50), a("y", "nola", 50), a("y", "nyc", 500), a("z", "nyc", 10))
        assertEquals(listOf(1, 1), Leaderboard.city(ledger, "nola").map { it.rank })
        assertEquals(listOf("y", "x", "z"), Leaderboard.global(ledger).map { it.user })
        assertEquals(Progression.levelFor(550), Leaderboard.city(ledger, "nola").first { it.user == "y" }.level)
    }
}
