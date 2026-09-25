package com.hereliesaz.capturetheflag.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 coordinate. */
data class GeoPoint(val lat: Double, val lng: Double)

private const val EARTH_RADIUS_M = 6_371_008.8

private fun Double.rad() = this * PI / 180.0

/** Great-circle distance in meters. */
fun GeoPoint.distanceTo(other: GeoPoint): Double {
    val dLat = (other.lat - lat).rad()
    val dLng = (other.lng - lng).rad()
    val h = sin(dLat / 2).let { it * it } +
        cos(lat.rad()) * cos(other.lat.rad()) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Initial great-circle bearing to [other], degrees clockwise from true north, 0 until 360. */
fun GeoPoint.bearingTo(other: GeoPoint): Double {
    val phi1 = lat.rad(); val phi2 = other.lat.rad(); val dLambda = (other.lng - lng).rad()
    val y = sin(dLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
    return (kotlin.math.atan2(y, x) * 180 / PI + 360) % 360
}

/** Smallest difference between two compass headings, 0 until 180. */
fun headingDelta(a: Double, b: Double): Double {
    val d = kotlin.math.abs(((a - b) % 360 + 360) % 360)
    return if (d > 180) 360 - d else d
}

/** Closed ring of vertices; the last vertex implicitly joins the first. */
data class Polygon(val ring: List<GeoPoint>) {
    init { require(ring.size >= 3) { "Polygon needs at least 3 vertices" } }

    /** Ray-casting point-in-polygon. Adequate at city scale; not antimeridian-safe. */
    operator fun contains(p: GeoPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
            ) inside = !inside
            j = i
        }
        return inside
    }
}

/**
 * A straight dividing line through [pivot] at [bearingDeg] (0 = north, clockwise).
 * [sideOf] returns +1 or -1; points exactly on the line resolve to +1.
 * Uses a local equirectangular projection, which is accurate at city scale.
 */
data class DividingLine(val pivot: GeoPoint, val bearingDeg: Double) {
    fun sideOf(p: GeoPoint): Int {
        val dx = (p.lng - pivot.lng) * cos(pivot.lat.rad())
        val dy = p.lat - pivot.lat
        val b = bearingDeg.rad()
        val cross = sin(b) * dy - cos(b) * dx
        return if (cross >= 0) 1 else -1
    }

    /** Approximate perpendicular distance from [p] to the line, in meters. */
    fun distanceMeters(p: GeoPoint): Double {
        val mPerDegLat = 111_320.0
        val dx = (p.lng - pivot.lng) * cos(pivot.lat.rad()) * mPerDegLat
        val dy = (p.lat - pivot.lat) * mPerDegLat
        val b = bearingDeg.rad()
        return kotlin.math.abs(sin(b) * dy - cos(b) * dx)
    }
}
