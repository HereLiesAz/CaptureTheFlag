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
 * walking in over 30 s, then holding at the target until the challenge has been answered (and,
 * for a jailbreak, the hold is done), then finishing with [photo] at the target. With [settle],
 * also waits out the dispute window. [stop] ends early, before finishing, when it returns true.
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
    while (true) {
        t += FRAME_MS; step++
        val at = if (step >= 6) target else GeoPoint(from.lat + (target.lat - from.lat) * step / 6, from.lng)
        tr += streamFrame(tr.game, by, id, LocationFix(at, t, 5.0), "chunk-$t", t)
        tr += tick(tr.game, t)
        if (stop(tr.game)) return tr
        val s = tr.game.streams.getValue(id)
        val answered = s.challengeAt?.let { t - it >= GameRules.STREAM_CHALLENGE_ANSWER } == true
        val held = purpose == StreamPurpose.CAPTURE || s.arrivedAt?.let { t - it >= GameRules.JAILBREAK_HOLD } == true
        if (answered && held && step >= 6) break
    }
    tr += endStream(tr.game, by, id, photo(target, t), t)
    if (settle && tr.verdict == Verdict.Valid) tr += tick(tr.game, t + GameRules.STREAM_CONTEST_WINDOW)
    return tr
}
