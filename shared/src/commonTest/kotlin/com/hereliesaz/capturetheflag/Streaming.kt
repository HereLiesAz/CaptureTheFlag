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
 * Streams a capture or jailbreak the honest way. A flag run: live on a qualifying frame of the
 * flag, then a frame every 5 s until the footage closes itself 30 s after the challenge. A
 * jailbreak: live from 60 m out, a frame every 5 s while walking in over 30 s, holding until the
 * challenge window and the hold are done, then the winning frame. With [settle], also waits out
 * the dispute window. [stop] ends early when it returns true.
 */
fun GameEngine.stream(
    game: Game, by: PlayerId, purpose: StreamPurpose, target: GeoPoint, start: Long,
    photo: (GeoPoint, Long) -> PhotoEvidence, settle: Boolean = true, id: String = "s1", stop: (Game) -> Boolean = { false },
): Transition {
    if (purpose == StreamPurpose.CAPTURE) {
        val still = photo(target, start)
        var tr = goLive(game, by, id, purpose, still.deviceFix!!, start, still)
        if (tr.verdict != Verdict.Valid) return tr
        var t = start
        while (tr.game.streams.getValue(id).open) {
            t += FRAME_MS
            tr += streamFrame(tr.game, by, id, LocationFix(target, t, 5.0), "chunk-$t", t)
            if (stop(tr.game)) return tr
        }
        val ended = tr.game.streams.getValue(id)
        if (settle && ended.void == null) tr += tick(tr.game, ended.endedAt!! + GameRules.STREAM_CONTEST_WINDOW)
        return tr
    }
    val from = target.north(60.0)
    var tr = goLive(game, by, id, purpose, LocationFix(from, start, 5.0), start)
    if (tr.verdict != Verdict.Valid) return tr
    var t = start
    var step = 0
    while (true) {
        t += FRAME_MS; step++
        val at = if (step >= 6) target else GeoPoint(from.lat + (target.lat - from.lat) * step / 6, from.lng)
        tr += streamFrame(tr.game, by, id, LocationFix(at, t, 5.0), "chunk-$t", t)
        tr += tick(tr.game, t)
        if (stop(tr.game)) return tr
        val s = tr.game.streams.getValue(id)
        val answered = s.challengeAt?.let { t - it >= GameRules.STREAM_CHALLENGE_WINDOW } == true
        val held = s.arrivedAt?.let { t - it >= GameRules.JAILBREAK_HOLD } == true
        if (answered && held && step >= 6) break
    }
    tr += endStream(tr.game, by, id, photo(target, t), t)
    if (settle && tr.verdict == Verdict.Valid) tr += tick(tr.game, t + GameRules.STREAM_CONTEST_WINDOW)
    return tr
}
