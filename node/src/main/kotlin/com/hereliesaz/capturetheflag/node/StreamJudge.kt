package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.*
import com.hereliesaz.capturetheflag.data.PhotoMatcher
import com.hereliesaz.capturetheflag.net.Media
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LiveStream
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Verdict
import com.hereliesaz.capturetheflag.rules.Verification
import com.hereliesaz.capturetheflag.net.ReviewReport.Check
import com.hereliesaz.capturetheflag.net.ReviewReport.Result

/**
 * A referee's review of a disputed stream: software, never a person. Every check is recorded,
 * including the ones this node couldn't run and why, so the leaders can see exactly what the
 * ruling rests on. The stream stands unless a check fails.
 *
 * [matcher] scores the final still against the leader's registration photo, when this node
 * runs one. [media] is this node's store: the stream's segments are checked against the hashes
 * sent live. [ears] listens for the challenge in the audio, on a node with a speech model.
 */
class StreamJudge(
    private val referee: String,
    private val matcher: PhotoMatcher? = null,
    /** This node's media store, by hash. */
    private val media: (String) -> ByteArray? = { null },
    /** Speech recognition, for the challenge. Null on a node without a speech model. */
    private val ears: Ears? = null,
) {
    suspend fun review(g: Game, s: LiveStream): ReviewReport {
        val checks = listOf(continuity(s), liveInTime(s), challengeTiming(s), finalStill(g, s), visualMatch(g, s), heard(s), integrity(s))
        return ReviewReport(s.id, s.review, referee, checks.none { it.result == Result.FAIL }, checks)
    }

    /** No gap longer than the limit: at least one frame per [GameRules.STREAM_MAX_GAP] of stream. */
    private fun continuity(s: LiveStream): Check {
        val span = (s.endedAt ?: s.lastFrame.at) - s.startedAt
        val needed = (span / GameRules.STREAM_MAX_GAP).toInt()
        val ok = s.chunks.size >= needed
        return Check("Unbroken stream", if (ok) Result.PASS else Result.FAIL, "${s.chunks.size} frames over ${span / 1000} s; at least $needed needed")
    }

    /** Said with the winning frame: the footage must run the full window past it. */
    private fun challengeTiming(s: LiveStream): Check {
        val at = s.qualifiedAt ?: return Check("Challenge on camera", Result.FAIL, "No winning frame was taken")
        val after = (s.endedAt ?: s.lastFrame.at) - at
        val ok = after >= GameRules.STREAM_CHALLENGE_WINDOW
        return Check("Challenge on camera", if (ok) Result.PASS else Result.FAIL, "\"${s.challenge}\" to be said with the winning frame; ${after / 1000} s of footage after it, ${GameRules.STREAM_CHALLENGE_WINDOW / 1000} s needed")
    }

    /** A flag run counts only from a stream live since the player came within range of the flag. */
    private fun liveInTime(s: LiveStream): Check {
        if (s.purpose != StreamPurpose.CAPTURE) return Check("Live before the approach", Result.PASS, "A jailbreak starts ${GameRules.STREAM_APPROACH_M.toInt()} m out")
        val zone = s.zoneAt ?: return Check("Live before the approach", Result.PASS, "Live before coming within ${GameRules.FLAG_ZONE_M.toInt()} m")
        val late = s.startedAt - zone
        val ok = late <= GameRules.STREAM_ZONE_GRACE
        return Check("Live before the approach", if (ok) Result.PASS else Result.FAIL,
            if (late <= 0) "Live ${-late / 1000} s before coming within ${GameRules.FLAG_ZONE_M.toInt()} m" else "Went live ${late / 1000} s after coming within ${GameRules.FLAG_ZONE_M.toInt()} m; ${GameRules.STREAM_ZONE_GRACE / 1000} s allowed")
    }

    /**
     * The winning frame, checked again as when it was taken: location, time, pose, facing, the
     * reference photo's geometry.
     */
    private fun finalStill(g: Game, s: LiveStream): Check {
        val photo = s.finish ?: return Check("Final still", Result.FAIL, "No still was kept")
        val at = s.qualifiedAt ?: return Check("Final still", Result.FAIL, "No winning frame was taken")
        // Judge the still as of the finish: being jailed since doesn't unmake it.
        val then = g.copy(players = g.players + (s.by to g.players.getValue(s.by).copy(jailedAt = null)))
        val v = when (s.purpose) {
            StreamPurpose.CAPTURE -> Verification.flagCapture(then, s.by, photo, at)
            StreamPurpose.JAILBREAK -> {
                val held = s.arrivedAt?.let { at - it } ?: 0L
                if (held < GameRules.JAILBREAK_HOLD) Verdict.Rejected("Held the jail ${held / 60_000} min of ${GameRules.JAILBREAK_HOLD / 60_000}")
                else Verification.jailbreak(then, s.by, photo, at)
            }
        }
        return when (v) {
            Verdict.Valid -> Check("Final still", Result.PASS, "Location, time, pose and facing agree with the target")
            is Verdict.Rejected -> Check("Final still", Result.FAIL, v.reason)
        }
    }

    private suspend fun visualMatch(g: Game, s: LiveStream): Check {
        val m = matcher ?: return Check("Matches the registration photo", Result.NOT_RUN, "This node runs no photo matcher")
        val enemy = g.players.getValue(s.by).team.opponent
        val ref = (if (s.purpose == StreamPurpose.CAPTURE) g.flags[enemy]?.photo else g.jails[enemy]?.photo)
            ?: return Check("Matches the registration photo", Result.FAIL, "No registration photo to compare")
        val photo = s.finish ?: return Check("Matches the registration photo", Result.FAIL, "No still was kept")
        val score = m.similarity(photo.imageUri, ref.imageUri)
            ?: return Check("Matches the registration photo", Result.NOT_RUN, "The matcher couldn't compare these images")
        val ok = score >= GameRules.VISUAL_MATCH_MIN
        return Check("Matches the registration photo", if (ok) Result.PASS else Result.FAIL, "Similarity %.2f; %.2f needed".format(score, GameRules.VISUAL_MATCH_MIN))
    }

    /**
     * The challenge, listened for in the footage after the winning frame: the last segments of
     * the referees' footage, which ends [GameRules.STREAM_CHALLENGE_WINDOW] after it.
     */
    private suspend fun heard(s: LiveStream): Check {
        val name = "Challenge heard on the audio"
        val words = s.challenge?.split(' ')?.filter { it.isNotBlank() } ?: return Check(name, Result.FAIL, "No challenge was issued")
        val e = ears ?: return Check(name, Result.NOT_RUN, "This node has no speech model")
        val window = (GameRules.STREAM_CHALLENGE_WINDOW / SEGMENT_MS + 2).toInt()
        val wanted = s.chunks.takeLast(window)
        val segments = wanted.mapNotNull { media(it) }
        if (segments.size < wanted.size) return Check(name, Result.NOT_RUN, "${wanted.size - segments.size} of ${wanted.size} segments around the winning frame aren't on this node")
        val h = e.heard(segments, words) ?: return Check(name, Result.NOT_RUN, "The audio couldn't be read")
        return Check(name, if (h.said) Result.PASS else Result.FAIL,
            if (h.said) "Heard \"${s.challenge}\"" else "Listened for \"${s.challenge}\"; heard ${h.transcript.ifBlank { "neither word" }.let { "\"$it\"" }}")
    }
    /**
     * Every segment the phone reported live, fetched and hashed again. The store names files by
     * their hash, so a segment that's here is the segment that was reported; one that isn't may
     * have gone to another node.
     */
    private fun integrity(s: LiveStream): Check {
        if (s.chunks.isEmpty()) return Check("Video matches the live hashes", Result.FAIL, "No segments were reported")
        val here = s.chunks.count { c -> media(c)?.let { Media.sha(it) == c } == true }
        return when (here) {
            s.chunks.size -> Check("Video matches the live hashes", Result.PASS, "All ${s.chunks.size} segments here, each matching the hash sent live")
            0 -> Check("Video matches the live hashes", Result.NOT_RUN, "None of the ${s.chunks.size} segments reached this node")
            else -> Check("Video matches the live hashes", Result.NOT_RUN, "$here of ${s.chunks.size} segments reached this node; the rest may be on another")
        }
    }

    private companion object {
        /** How long a phone's segments run: see the app's live camera. */
        const val SEGMENT_MS = 5_000L
    }
}
