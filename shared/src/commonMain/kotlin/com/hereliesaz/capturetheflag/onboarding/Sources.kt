package com.hereliesaz.capturetheflag.onboarding

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon

/** A rectangle in degrees. */
data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    operator fun contains(p: GeoPoint) = p.lat in south..north && p.lng in west..east

    companion object {
        fun of(points: List<GeoPoint>) = BBox(points.minOf { it.lat }, points.minOf { it.lng }, points.maxOf { it.lat }, points.maxOf { it.lng })
    }
}

/** A city as the boundary service knows it. */
data class FoundCity(
    /** Stable, lowercase, used as the city id everywhere. */
    val id: String,
    val name: String,
    val displayName: String,
    val boundary: Polygon,
)

/** Something that stops people crossing: a river, a rail line, a motorway. */
data class Barrier(val path: List<GeoPoint>, val weight: Double)

/** Finds a city's administrative boundary by name. */
fun interface BoundarySource {
    suspend fun find(cityName: String): FoundCity?
}

/** People living inside a polygon. */
fun interface PopulationSource {
    suspend fun population(area: Polygon): Double
}

/** Map features from OpenStreetMap. */
interface MapFeatureSource {
    /** Buildings inside [box]. */
    suspend fun buildingCount(box: BBox): Int
    /** Water bodies (lakes, rivers as areas, bays) touching [box], as outer rings. */
    suspend fun water(box: BBox): List<Polygon>
    /** Linear barriers touching [box]: rivers, canals, railways, motorways. */
    suspend fun barriers(box: BBox): List<Barrier>
}
