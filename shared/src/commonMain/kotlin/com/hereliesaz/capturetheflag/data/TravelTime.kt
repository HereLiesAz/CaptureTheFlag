package com.hereliesaz.capturetheflag.data

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.rules.GameRules
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Door-to-door travel time by the best of walking and public transit, departing at [departAt].
 * Production implementations call a routing service server-side (Google Routes transit mode,
 * OpenTripPlanner over the city's GTFS feed) so schedules, frequency and night service count.
 */
fun interface TravelTimeEstimator {
    suspend fun travelMs(from: GeoPoint, to: GeoPoint, departAt: Millis): Long
}

/** Multiplier ≥ 1 for conditions that slow people down: snow, ice, extreme heat, storms. */
fun interface WeatherFactor {
    suspend fun at(point: GeoPoint, time: Millis): Double
}

/**
 * Offline fallback: straight-line distance with a detour factor, taking the faster of walking
 * (5 km/h) and transit (18 km/h in-vehicle plus a 10 minute wait and 5 minute walk).
 */
object HeuristicTravel : TravelTimeEstimator {
    private const val DETOUR = 1.3
    private const val WALK_M_PER_MS = 5_000.0 / GameRules.HOUR
    private const val TRANSIT_M_PER_MS = 18_000.0 / GameRules.HOUR
    private const val TRANSIT_OVERHEAD = 15 * GameRules.MINUTE

    override suspend fun travelMs(from: GeoPoint, to: GeoPoint, departAt: Millis): Long {
        val d = from.distanceTo(to) * DETOUR
        val walk = (d / WALK_M_PER_MS).roundToLong()
        val transit = (d / TRANSIT_M_PER_MS).roundToLong() + TRANSIT_OVERHEAD
        return min(walk, transit)
    }
}

object NoWeather : WeatherFactor {
    override suspend fun at(point: GeoPoint, time: Millis) = 1.0
}
