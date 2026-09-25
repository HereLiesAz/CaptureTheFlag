package com.hereliesaz.capturetheflag.onboarding

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One square of the city grid before its numbers are filled in. */
data class GridCell(val box: BBox, val center: GeoPoint, val areaM2: Double) {
    val polygon: Polygon get() = Polygon(listOf(
        GeoPoint(box.south, box.west), GeoPoint(box.south, box.east), GeoPoint(box.north, box.east), GeoPoint(box.north, box.west),
    ))
}

/** Pure geometry for onboarding: laying the grid, measuring land and barriers. */
object Grid {
    private const val M_PER_DEG = 111_320.0

    /**
     * Square cells over the city, sized so there are roughly [target] of them, kept only if
     * their centre falls inside the boundary. Cells are never smaller than [minSideM] or larger
     * than [maxSideM], so a village and a megacity both get a sensible grid.
     */
    fun lay(boundary: Polygon, target: Int = 150, minSideM: Double = 300.0, maxSideM: Double = 3_000.0): List<GridCell> {
        val box = BBox.of(boundary.ring)
        val kx = cos((box.south + box.north) / 2 * PI / 180)
        val wM = (box.east - box.west) * kx * M_PER_DEG
        val hM = (box.north - box.south) * M_PER_DEG
        // Estimate the city's area from how much of its bounding box it fills.
        val fill = sample(box, 20).count { it in boundary }.toDouble() / 400
        val side = sqrt(max(wM * hM * fill, 1.0) / target).coerceIn(minSideM, maxSideM)
        val dLat = side / M_PER_DEG
        val dLng = side / (M_PER_DEG * kx)
        val rows = max(1, (hM / side).roundToInt())
        val cols = max(1, (wM / side).roundToInt())
        return (0 until rows).flatMap { r ->
            (0 until cols).mapNotNull { c ->
                val s = box.south + r * dLat
                val w = box.west + c * dLng
                val cell = BBox(s, w, min(s + dLat, box.north), min(w + dLng, box.east))
                val center = GeoPoint((cell.south + cell.north) / 2, (cell.west + cell.east) / 2)
                if (center in boundary) GridCell(cell, center, side * side) else null
            }
        }
    }

    /** Share of the cell that is land, by sampling a [n]×[n] lattice against the water polygons. */
    fun landFraction(cell: GridCell, water: List<Polygon>, n: Int = 4): Double {
        val nearby = water.filter { overlaps(BBox.of(it.ring), cell.box) }
        if (nearby.isEmpty()) return 1.0
        val pts = sample(cell.box, n)
        return pts.count { p -> nearby.none { p in it } }.toDouble() / pts.size
    }

    /** The strongest barrier crossing the cell, 0 if none. */
    fun barrierScore(cell: GridCell, barriers: List<Barrier>): Double =
        barriers.filter { b -> b.path.zipWithNext().any { (a, c) -> segmentHitsBox(a, c, cell.box) } }.maxOfOrNull { it.weight } ?: 0.0

    private fun sample(box: BBox, n: Int): List<GeoPoint> = (0 until n).flatMap { i ->
        (0 until n).map { j ->
            GeoPoint(box.south + (box.north - box.south) * (i + 0.5) / n, box.west + (box.east - box.west) * (j + 0.5) / n)
        }
    }

    private fun overlaps(a: BBox, b: BBox) = a.south <= b.north && a.north >= b.south && a.west <= b.east && a.east >= b.west

    /** Liang–Barsky clip: does segment a→b touch the box? */
    private fun segmentHitsBox(a: GeoPoint, b: GeoPoint, box: BBox): Boolean {
        if (a in box || b in box) return true
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.lng - a.lng
        val dy = b.lat - a.lat
        for ((p, q) in listOf(-dx to a.lng - box.west, dx to box.east - a.lng, -dy to a.lat - box.south, dy to box.north - a.lat)) {
            if (p == 0.0) { if (q < 0) return false; continue }
            val t = q / p
            if (p < 0) { if (t > t1) return false; if (t > t0) t0 = t } else { if (t < t0) return false; if (t < t1) t1 = t }
        }
        return true
    }
}
