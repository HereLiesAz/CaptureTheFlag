package com.hereliesaz.capturetheflag.rules

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Points → levels → power → perks.
 *
 * Levels are unbounded; each costs more than the last. Advantages arrive only at milestone
 * levels, which are always multiples of 5 and spread further apart: 5, 15, 30, 50, 75, 105…
 * A milestone grants one step of *power*; a milestone that is also a multiple of 10 grants two.
 * Each perk starts at some power and grows with the square of the steps since, so early
 * advantages are nearly cosmetic and late ones are decisive.
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
        var l = floor((points / 50.0).pow(0.4)).toInt() + 1
        while (pointsFor(l + 1) <= points) l++
        while (l > 1 && pointsFor(l) > points) l--
        return l
    }

    /** The [i]th milestone level (1-based): 5 × the ith triangular number. */
    fun milestone(i: Int): Int {
        require(i >= 1)
        return 5 * i * (i + 1) / 2
    }

    /** Power granted at a milestone level: double on multiples of 10. */
    fun powerAt(milestoneLevel: Int): Int = if (milestoneLevel % 10 == 0) 2 else 1

    /** Total power earned by [level]. */
    fun powerFor(level: Int): Int {
        var i = 1
        var p = 0
        while (milestone(i) <= level) { p += powerAt(milestone(i)); i++ }
        return p
    }

    /** First milestone strictly above [level]. */
    fun nextMilestone(level: Int): Int {
        var i = 1
        while (milestone(i) <= level) i++
        return milestone(i)
    }

    /** First level at which total power reaches [power]. */
    fun levelForPower(power: Int): Int {
        var i = 1
        var p = 0
        while (true) {
            p += powerAt(milestone(i))
            if (p >= power) return milestone(i)
            i++
        }
    }

    fun perksFor(level: Int): Perks = Perks.forPower(powerFor(level))

    /**
     * Points for jailing a player of [targetLevel]. Unbounded, so hunting veterans pays,
     * unless the target has Legacy, which freezes and then discounts their value.
     */
    fun tagValue(targetLevel: Int): Long {
        val legacy = perksFor(targetLevel)
        val effective = legacy.legacyCapLevel?.let { min(targetLevel, it) } ?: targetLevel
        return (Points.TAG_BASE * (1 + 0.2 * effective) * (1 - legacy.legacyDiscount)).roundToLong()
    }
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
    /** Per teammate freed in a jailbreak. */
    const val JAILBREAK_PER_FREED = 20
}

/** Power at which each perk switches on. */
object PerkStart {
    const val PING_DELAY = 1
    const val PROXIMITY_ALERT = 1
    const val BLE_WINDOW = 1
    const val IDENTITY_DELAY = 3
    const val KEEN_EYE = 3
    const val THRESHOLD = 4
    const val COUNTERINTEL = 4
    const val DECOY = 5
    const val SHARP_LENS = 5
    const val NIGHT_COVER = 5
    const val BLUR = 6
    const val WITNESS = 6
    const val STANDING = 7
    const val MENTOR = 7
    const val SHADOW = 8
    const val CROWD = 8
    const val INTERROGATE = 9
    const val BOUNTY = 9
    const val DELIBERATE = 10
    const val PAROLE = 10
    const val VANISH = 12
    const val DOPPELGANGER = 12
    const val BLOODHOUND = 15
    const val TRIPWIRE = 15
    const val FLAG_SENSE = 20
    const val LEGACY = 20
    const val LAST_STAND = 20
}

/**
 * Every advantage at a given power. All off at power 0.
 *
 * Magnitudes grow with q = k², where k = power steps since the perk started.
 * Use-counts grow as ⌈k/2⌉: counts break a game faster than magnitudes do.
 * Caps sit only where an uncapped value would break the game outright.
 */
data class Perks(
    // Hiding
    /** Added to the second incursion gap (normally 60 min). */
    val firstPingDelayMs: Long,
    /** Extra pings before roster identity is attached (normally ping 6). */
    val identityDelayPings: Int,
    /** Time on enemy ground before an incursion counts. */
    val thresholdMs: Long,
    /** Radius of uncertainty on your pings until identified. */
    val blurM: Double,
    /** Blur multiplier per unit of local population density above the city mean. */
    val crowdFactor: Double,
    /** Added to the ping recipient divisor (normally 3) between 1am and 5am local. */
    val nightCoverDivisor: Double,
    /** Added to the ping recipient divisor (normally 3) at all times. */
    val shadowDivisor: Double,
    /** Scheduled pings you may cancel per game. */
    val vanishesPerGame: Int,
    /** Tags against you rejected per game, publicly announced. */
    val lastStandsPerGame: Int,
    /** Your tag value stops rising at this level; null = no Legacy. */
    val legacyCapLevel: Int?,
    /** Fraction knocked off your tag value. */
    val legacyDiscount: Double,
    /** Automatic release this long after being jailed; null = never. */
    val paroleMs: Long?,
    // Deception
    val decoysPerGame: Int,
    /** Extra waypoints each decoy walks through, one ping per waypoint. */
    val doppelgangerSteps: Int,
    // Hunting
    /** Intruders within this radius always ping you, regardless of the random draw. */
    val proximityAlertM: Double,
    /** Teammates within this radius of you receive your proximity alerts. */
    val witnessM: Double,
    /** Added to the BLE window when you tag someone. */
    val bleWindowBonusMs: Long,
    /** Added to the tag photo distance tolerance. */
    val sharpLensM: Double,
    /** Pings you receive show the intruder's level. */
    val keenEye: Boolean,
    /** Decoys landing within this radius of you are marked as decoys (if you outrank the sender). */
    val counterintelM: Double,
    /** Forced extra pings on known intruders per game. */
    val interrogationsPerGame: Int,
    /** Tag-value multiplier on the enemy you mark; 1.0 = no Bounty. */
    val bountyMultiplier: Double,
    /** After a ping reaches you, you keep receiving that intruder's live position this long. */
    val bloodhoundMs: Long,
    /** Alerted the moment an enemy enters your territory within this radius of your flag. */
    val tripwireM: Double,
    /** Radius of a circle known to contain the enemy flag; 0 = no Flag Sense. */
    val flagSenseM: Double,
    // Leadership and team
    /** Added weight in the captain draw. */
    val standingWeight: Double,
    /** Share of a nearby lower-level teammate's points paid to them as a bonus. */
    val mentorShare: Double,
    /** Added to your team's flag-placement window, if you are a leader. */
    val deliberateMs: Long,
) {
    companion object {
        private const val MIN = GameRules.MINUTE
        private const val HOUR = GameRules.HOUR

        fun forPower(p: Int): Perks {
            fun k(start: Int) = max(0, p - start + 1)
            fun q(start: Int) = k(start).let { it * it }
            fun uses(start: Int) = (k(start) + 1) / 2
            fun on(start: Int) = k(start) > 0
            return Perks(
                firstPingDelayMs = min(MIN * q(PerkStart.PING_DELAY), 60 * MIN),
                identityDelayPings = uses(PerkStart.IDENTITY_DELAY),
                thresholdMs = min(30_000L * q(PerkStart.THRESHOLD), 15 * MIN),
                blurM = min(10.0 * q(PerkStart.BLUR), 1_000.0),
                crowdFactor = 0.05 * q(PerkStart.CROWD),
                nightCoverDivisor = 0.1 * q(PerkStart.NIGHT_COVER),
                shadowDivisor = 0.1 * q(PerkStart.SHADOW),
                vanishesPerGame = uses(PerkStart.VANISH),
                lastStandsPerGame = uses(PerkStart.LAST_STAND),
                legacyCapLevel = if (on(PerkStart.LEGACY)) Progression.levelForPower(PerkStart.LEGACY) else null,
                legacyDiscount = min(0.01 * q(PerkStart.LEGACY), 0.5),
                paroleMs = if (on(PerkStart.PAROLE)) max(2 * HOUR, 48 * HOUR - 2 * HOUR * q(PerkStart.PAROLE)) else null,
                decoysPerGame = uses(PerkStart.DECOY),
                doppelgangerSteps = if (on(PerkStart.DOPPELGANGER)) min(1 + q(PerkStart.DOPPELGANGER), 20) else 0,
                proximityAlertM = min(25.0 * q(PerkStart.PROXIMITY_ALERT), 5_000.0),
                witnessM = min(10.0 * q(PerkStart.WITNESS), 1_000.0),
                bleWindowBonusMs = min(2_000L * q(PerkStart.BLE_WINDOW), 5 * MIN),
                sharpLensM = min(3.0 * q(PerkStart.SHARP_LENS), 60.0),
                keenEye = on(PerkStart.KEEN_EYE),
                counterintelM = min(50.0 * q(PerkStart.COUNTERINTEL), 5_000.0),
                interrogationsPerGame = uses(PerkStart.INTERROGATE),
                bountyMultiplier = min(1.0 + 0.1 * q(PerkStart.BOUNTY), 3.0),
                bloodhoundMs = min(30_000L * q(PerkStart.BLOODHOUND), 10 * MIN),
                tripwireM = min(100.0 * q(PerkStart.TRIPWIRE), 3_000.0),
                flagSenseM = if (on(PerkStart.FLAG_SENSE)) max(200.0, 3_000.0 / (1 + 0.1 * (q(PerkStart.FLAG_SENSE) - 1))) else 0.0,
                standingWeight = 0.05 * q(PerkStart.STANDING),
                mentorShare = min(0.02 * q(PerkStart.MENTOR), 0.5),
                deliberateMs = min(MIN * q(PerkStart.DELIBERATE), 60 * MIN),
            )
        }
    }
}
