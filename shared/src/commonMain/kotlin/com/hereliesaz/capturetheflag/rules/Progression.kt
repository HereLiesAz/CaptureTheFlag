package com.hereliesaz.capturetheflag.rules

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Points → levels → perks. Levels are unbounded; each costs more than the last.
 * Perks arrive only at milestone levels, the milestones spread further apart,
 * and each milestone makes every perk stronger.
 */
object Progression {
    /** Lifetime points needed to reach [level]. Level 1 is free. Grows as level^2.5. */
    fun pointsFor(level: Int): Long {
        require(level >= 1)
        return (50.0 * (level - 1).toDouble().pow(2.5)).roundToLong()
    }

    /** Highest level whose threshold [points] meets. */
    fun levelFor(points: Long): Int {
        if (points <= 0) return 1
        // Invert the curve, then correct for rounding at the edges.
        var l = floor((points / 50.0).pow(0.4)).toInt() + 1
        while (pointsFor(l + 1) <= points) l++
        while (l > 1 && pointsFor(l) > points) l--
        return l
    }

    /**
     * Level at which perk tier [tier] (1-based) unlocks: 2, 6, 12, 20, 30, 42…
     * The gap between milestones grows by two each time.
     */
    fun milestone(tier: Int): Int {
        require(tier >= 1)
        return tier * (tier + 1)
    }

    /** Perk tiers unlocked at [level]. */
    fun tierFor(level: Int): Int {
        var t = 0
        while (milestone(t + 1) <= level) t++
        return t
    }

    fun perksFor(level: Int): Perks = Perks.forTier(tierFor(level))

    /** Points for jailing a player of [targetLevel]. Unbounded: hunting veterans pays. */
    fun tagValue(targetLevel: Int): Long = (Points.TAG_BASE * (1 + 0.2 * targetLevel)).roundToLong()
}

/** Flat point values. Tag value scales with target level via [Progression.tagValue]. */
object Points {
    const val TAG_BASE = 10
    const val JAILED = -5
    const val FLAG_CAPTURE = 100
    const val TEAM_WIN = 25
    const val TIE = 5
    /** Leaders whose flag was never taken. */
    const val FLAG_HELD = 15
    /** Per ping endured, paid on getting home without being jailed. */
    const val PER_PING_SURVIVED = 2
}

/**
 * Every advantage, as a function of perk tier. All zero at tier 0.
 * Each grows with tier; caps exist only where an uncapped value would break the game outright.
 */
data class Perks(
    /** Added to the first incursion gap (normally 60 min). */
    val firstPingDelayMs: Long,
    /** Extra pings before the roster identity is attached (normally from ping 6). */
    val identityDelayPings: Int,
    /** Intruders within this radius always ping you, even outside the random third. 0 = off. */
    val proximityAlertM: Double,
    /** Added to the BLE window when you tag someone. */
    val bleWindowBonusMs: Long,
    /** Decoy pings you may send per game. */
    val decoysPerGame: Int,
) {
    companion object {
        fun forTier(t: Int) = Perks(
            firstPingDelayMs = (5L * t * GameRules.MINUTE).coerceAtMost(60 * GameRules.MINUTE),
            identityDelayPings = t / 2,
            proximityAlertM = (250.0 * t).coerceAtMost(5_000.0),
            bleWindowBonusMs = (15_000L * t).coerceAtMost(5 * GameRules.MINUTE),
            decoysPerGame = t / 3,
        )
    }
}
