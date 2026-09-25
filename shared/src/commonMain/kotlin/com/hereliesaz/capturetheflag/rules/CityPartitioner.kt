package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import kotlin.math.abs
import kotlin.random.Random

/**
 * One cell of a city's statistical grid, supplied by a [CityDataSource]
 * (census population, OSM building footprints, land/water area, barrier features).
 *
 * @property barrier 0..1, how strongly this cell reads as a natural divide
 *   (river, rail corridor, highway, park edge). Cuts that follow barriers read as fair on the ground.
 */
data class CityCell(
    val center: GeoPoint,
    val population: Double,
    val buildings: Double,
    val landAreaM2: Double,
    val barrier: Double,
)

/** Where cell data comes from. Implemented server-side against real datasets. */
fun interface CityDataSource {
    suspend fun cells(cityName: String): List<CityCell>
}

/**
 * Splits a city in two with a straight line, chosen at random from the fairest candidates.
 *
 * Criteria in the stated order of importance: population, geography, buildings, land.
 * Each imbalance is |a − b| / (a + b), so 0 is a perfect split. Geography is scored as
 * 1 − (mean barrier strength of cells the line passes through): lower is better.
 */
class CityPartitioner(
    private val weights: Weights = Weights(),
    private val angleStepDeg: Double = 5.0,
    /** Pivot jitter grid around the population centroid, in cell-center samples. */
    private val pivotSamples: Int = 24,
    /** Cells within this distance count as "on" the line for the geography score. */
    private val lineBandM: Double = 250.0,
    /** Random pick among this many best candidates. */
    private val topK: Int = 5,
) {
    data class Weights(
        val population: Double = 0.4,
        val geography: Double = 0.3,
        val buildings: Double = 0.2,
        val land: Double = 0.1,
    )

    data class Candidate(val line: DividingLine, val score: Double, val breakdown: Map<String, Double>)

    fun partition(cells: List<CityCell>, random: Random): Candidate {
        require(cells.size >= 2) { "Need at least two cells to split a city" }
        return candidates(cells, random).sortedBy { it.score }.take(topK).random(random)
    }

    fun candidates(cells: List<CityCell>, random: Random): List<Candidate> {
        val totalPop = cells.sumOf { it.population }
        val w: (CityCell) -> Double = if (totalPop > 0) { c -> c.population / totalPop } else { _ -> 1.0 / cells.size }
        val centroid = GeoPoint(cells.sumOf { it.center.lat * w(it) }, cells.sumOf { it.center.lng * w(it) })
        // Pivot candidates: the centroid plus randomly sampled populated cells near it.
        val pivots = listOf(centroid) + cells.shuffled(random).take(pivotSamples).map { it.center }
        val steps = (180.0 / angleStepDeg).toInt()
        return pivots.flatMap { pivot ->
            (0 until steps).map { i -> score(cells, DividingLine(pivot, i * angleStepDeg)) }
        }.filter { it.score.isFinite() }
    }

    private fun score(cells: List<CityCell>, line: DividingLine): Candidate {
        val (a, b) = cells.partition { line.sideOf(it.center) > 0 }
        if (a.isEmpty() || b.isEmpty()) return Candidate(line, Double.POSITIVE_INFINITY, emptyMap())
        fun imbalance(f: (CityCell) -> Double): Double {
            val x = a.sumOf(f); val y = b.sumOf(f)
            return if (x + y == 0.0) 0.0 else abs(x - y) / (x + y)
        }
        val onLine = cells.filter { line.distanceMeters(it.center) <= lineBandM }
        val geography = if (onLine.isEmpty()) 1.0 else 1.0 - onLine.map { it.barrier }.average()
        val parts = mapOf(
            "population" to imbalance { it.population },
            "geography" to geography,
            "buildings" to imbalance { it.buildings },
            "land" to imbalance { it.landAreaM2 },
        )
        val s = weights.population * parts.getValue("population") +
            weights.geography * parts.getValue("geography") +
            weights.buildings * parts.getValue("buildings") +
            weights.land * parts.getValue("land")
        return Candidate(line, s, parts)
    }
}
