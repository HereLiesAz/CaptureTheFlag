package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.PlayerId
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Escalating incursion pings: on entry, +60m, +30m, +15m, +10m, +5m, then every 5m.
 * Times are measured from the moment of entry.
 */
object PingSchedule {
    /** Gap preceding ping [n] (1-based). */
    fun gapBefore(n: Int): Long {
        require(n >= 1)
        return GameRules.PING_GAPS.getOrElse(n - 1) { GameRules.PING_GAPS.last() }
    }

    /** When ping [n] is due for an incursion that began at [enteredAt]. */
    fun dueAt(enteredAt: Millis, n: Int): Millis = enteredAt + (1..n).sumOf { gapBefore(it) }

    fun identifies(n: Int): Boolean = n >= GameRules.IDENTIFY_FROM_PING

    /** A fresh random third (rounded up, at least one) of [opponents]. */
    fun recipients(opponents: Collection<PlayerId>, random: Random): Set<PlayerId> {
        if (opponents.isEmpty()) return emptySet()
        val n = ceil(opponents.size * GameRules.PING_RECIPIENT_FRACTION).toInt().coerceAtLeast(1)
        return opponents.shuffled(random).take(n).toSet()
    }
}
