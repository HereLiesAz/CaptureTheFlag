package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.engine.Transition
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Verdict

/** A point [m] metres due north of this one. */
fun GeoPoint.north(m: Double) = GeoPoint(lat + m / 111_320.0, lng)

/** Stream frames every 5 s, well inside the 20 s gap limit. */
const val FRAME_MS = 5_000L

/**
 * Streams a capture or jailbreak the honest way: live from 60 m out, a frame every 5 s while
 * walking in over 30 s, (for a jailbreak, holding the jail 15 minutes), then the winning frame
 * with the challenge, and frames on until the referees' footage closes 30 s later. With
 * [settle], also waits out the dispute window. [stop] ends early when it returns true.
 */
fun GameEngine.stream(
    game: Game, by: PlayerId, purpose: StreamPurpose, target: GeoPoint, start: Long,
    photo: (GeoPoint, Long) -> PhotoEvidence, settle: Boolean = true, id: String = "s1", stop: (Game) -> Boolean = { false },
): Transition {
    val from = target.north(60.0)
    var tr = goLive(game, by, id, purpose, LocationFix(from, start, 5.0), start)
    if (tr.verdict != Verdict.Valid) return tr
    var t = start
    var step = 0
    fun frame() {
        t += FRAME_MS; step++
        val at = if (step >= 6) target else GeoPoint(from.lat + (target.lat - from.lat) * step / 6, from.lng)
        tr += streamFrame(tr.game, by, id, LocationFix(at, t, 5.0), "chunk-$t", t)
        tr += tick(tr.game, t)
    }
    while (true) {
        frame()
        if (stop(tr.game)) return tr
        val s = tr.game.streams.getValue(id)
        if (!s.open) return tr
        val held = purpose == StreamPurpose.CAPTURE || s.arrivedAt?.let { t - it >= GameRules.JAILBREAK_HOLD } == true
        if (held && step >= 6) break
    }
    val won = endStream(tr.game, by, id, photo(target, t), t)
    if (won.verdict != Verdict.Valid) return tr + won
    tr += won
    while (tr.game.streams.getValue(id).open) frame()
    val ended = tr.game.streams.getValue(id)
    if (settle && ended.void == null) tr += tick(tr.game, ended.endedAt!! + GameRules.STREAM_CONTEST_WINDOW)
    return tr
}

