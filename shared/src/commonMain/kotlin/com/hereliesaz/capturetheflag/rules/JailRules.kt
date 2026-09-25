package com.hereliesaz.capturetheflag.rules

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * How long a newly jailed player has to report. Travel is a general door-to-door estimate
 * (walking or transit, see [com.hereliesaz.capturetheflag.data.HeuristicTravel]),
 * scaled by weather, given slack and a buffer, then the report hold is added on top.
 */
object JailRules {
    fun reportWindow(travelMs: Long, weatherFactor: Double = 1.0): Long {
        val travel = (travelMs * weatherFactor.coerceAtLeast(1.0) * GameRules.JAIL_TRAVEL_SLACK).roundToLong()
        return max(GameRules.JAIL_REPORT_MIN_WINDOW, travel + GameRules.JAIL_TRAVEL_BUFFER) + GameRules.JAIL_REPORT_HOLD
    }
}
