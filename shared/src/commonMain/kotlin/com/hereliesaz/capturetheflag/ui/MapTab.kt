package com.hereliesaz.capturetheflag.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.rules.GameRules
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The city as you are allowed to see it: both halves, the line, you, your own flag and jail,
 * the enemy jail (public), recent pings sent to you (with their blur), and your Flag Sense
 * circle if you have one. Nothing else: no teammates' positions, no enemy flag.
 */
@Composable
fun MapTab(g: Game, me: Player?, fix: LocationFix?, pings: List<Ping>, now: Millis) {
    val ink = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val shade = MaterialTheme.colorScheme.surfaceVariant
    val recent = pings.filter { now - it.at <= RECENT }
    val sense = me?.let { GameEngine.flagSense(g, it.id) }
    Column {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
            val proj = Projection(g.city.boundary.ring, size)
            val outline = Path().apply {
                g.city.boundary.ring.forEachIndexed { i, p -> proj(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            clipPath(outline) {
                // Your side shaded; theirs left dark.
                me?.let { m ->
                    val half = halfPlane(g, proj, m.team.lineSide)
                    drawPath(half, shade)
                }
                val (a, b) = lineEnds(g, proj)
                drawLine(ink, a, b, strokeWidth = 3f)
            }
            drawPath(outline, dim, style = Stroke(2f))

            me?.let { m ->
                g.flags[m.team]?.let { flagMark(proj(it.location), ink) }
                g.jails[m.team]?.let { jailMark(proj(it.location), dim) }
                g.jails[m.team.opponent]?.let { jailMark(proj(it.location), ink) }
            }
            sense?.let { (c, r) ->
                drawCircle(ink, proj.meters(r), proj(c), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
            }
            recent.forEach { p ->
                val at = proj(p.location)
                val fade = 1f - ((now - p.at).toFloat() / RECENT).coerceIn(0f, 0.8f)
                if (p.radiusM > 0) drawCircle(ink.copy(alpha = 0.15f * fade), max(proj.meters(p.radiusM), 6f), at)
                drawCircle(ink.copy(alpha = fade), 7f, at, style = Stroke(3f))
            }
            fix?.let {
                drawCircle(ink, 9f, proj(it.point))
                drawCircle(ink.copy(alpha = 0.25f), max(proj.meters(it.accuracyM), 12f), proj(it.point))
            }
        }
        Text(
            "Shaded: your ground. ● you  ▲ your flag  □ jails  ○ pings (last ${RECENT / GameRules.MINUTE} min)" +
                if (sense != null) "  ┄ Flag Sense" else "",
            style = MaterialTheme.typography.labelSmall,
            color = dim,
        )
    }
}

private const val RECENT = 30 * GameRules.MINUTE

/** Equirectangular fit of the city into the canvas, preserving aspect. */
private class Projection(ring: List<GeoPoint>, size: Size) {
    private val midLat = (ring.minOf { it.lat } + ring.maxOf { it.lat }) / 2
    private val kx = cos(midLat * PI / 180)
    private val minX = ring.minOf { it.lng } * kx
    private val maxY = ring.maxOf { it.lat }
    private val w = ring.maxOf { it.lng } * kx - minX
    private val h = maxY - ring.minOf { it.lat }
    private val scale = minOf(size.width / w, size.height / h).toFloat()
    private val ox = ((size.width - w * scale) / 2).toFloat()
    private val oy = ((size.height - h * scale) / 2).toFloat()

    operator fun invoke(p: GeoPoint) = Offset(ox + ((p.lng * kx - minX) * scale).toFloat(), oy + ((maxY - p.lat) * scale).toFloat())

    /** A ground distance in canvas pixels. */
    fun meters(m: Double) = (m / 111_320.0 * scale).toFloat()
}

/** Two points far beyond the canvas along the dividing line. */
private fun lineEnds(g: Game, proj: Projection): Pair<Offset, Offset> {
    val l = g.territory.line
    val b = l.bearingDeg * PI / 180
    val far = 2.0 // degrees: well past any city
    fun at(t: Double) = proj(GeoPoint(l.pivot.lat + cos(b) * t, l.pivot.lng + sin(b) * t / cos(l.pivot.lat * PI / 180)))
    return at(-far) to at(far)
}

/** The half-plane on [side] of the line, as a large polygon (clipped to the city by the caller). */
private fun halfPlane(g: Game, proj: Projection, side: Int): Path {
    val l = g.territory.line
    val (a, b) = lineEnds(g, proj)
    // Probe a point just off the pivot to find which screen normal points to [side].
    val dir = Offset(b.x - a.x, b.y - a.y)
    val normal = Offset(-dir.y, dir.x)
    val pivot = proj(l.pivot)
    val probeGeo = run {
        val bearing = (l.bearingDeg + 90) * PI / 180
        GeoPoint(l.pivot.lat + cos(bearing) * 0.001, l.pivot.lng + sin(bearing) * 0.001 / cos(l.pivot.lat * PI / 180))
    }
    val probe = proj(probeGeo)
    val probeSide = l.sideOf(probeGeo)
    val towardProbe = (probe.x - pivot.x) * normal.x + (probe.y - pivot.y) * normal.y > 0
    val sign = if ((probeSide == side) == towardProbe) 1f else -1f
    return Path().apply {
        moveTo(a.x, a.y); lineTo(b.x, b.y)
        lineTo(b.x + normal.x * sign, b.y + normal.y * sign)
        lineTo(a.x + normal.x * sign, a.y + normal.y * sign)
        close()
    }
}

private fun DrawScope.flagMark(at: Offset, c: Color) {
    drawPath(Path().apply { moveTo(at.x, at.y - 12f); lineTo(at.x + 10f, at.y + 8f); lineTo(at.x - 10f, at.y + 8f); close() }, c)
}

private fun DrawScope.jailMark(at: Offset, c: Color) {
    drawRect(c, Offset(at.x - 9f, at.y - 9f), Size(18f, 18f), style = Stroke(3f))
}
