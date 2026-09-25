package com.hereliesaz.capturetheflag.ui

import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PingKind
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.rules.GameRules

/**
 * What deserves to make a phone buzz, as (title, body). Pure, so it's testable and the
 * same on every platform. Everything else waits in the app or on the radio.
 */
object Alerts {
    fun forPing(p: Ping, me: PlayerId?): Pair<String, String>? {
        if (me == null || me !in p.recipients) return null
        val who = p.identified?.displayName ?: "An intruder"
        val blur = if (p.radiusM > 0) " (within ${p.radiusM.toInt()} m)" else ""
        return when (p.kind) {
            PingKind.INCURSION -> "Intruder" to "$who is on your ground. Ping ${p.number}$blur. Open the map."
            PingKind.TRIPWIRE -> "Tripwire" to "Someone just crossed in near your flag."
            PingKind.TRACKING -> null // live trail updates would buzz every few seconds
            PingKind.INTERROGATION -> "Interrogation" to "$who, right now$blur."
            PingKind.GO_LIVE -> "Go live" to "You're within ${GameRules.FLAG_ZONE_M.toInt()} m of their flag. Go live now, or a capture won't count."
        }
    }

    fun forChange(before: Game, after: Game, me: PlayerId?, now: Millis): List<Pair<String, String>> = buildList {
        val was = me?.let { before.players[it] } ?: return@buildList
        val cur = after.players[me] ?: return@buildList
        if (!was.isJailed && cur.isJailed) {
            val jail = after.jails[cur.team.opponent]
            val mins = cur.jailDeadline?.let { (it - now) / GameRules.MINUTE }
            add("Jailed" to "Report to ${jail?.venueName ?: "the enemy jail"}${mins?.let { " within $it min" } ?: ""}, or you're out for the round.")
        }
        if (was.isJailed && !cur.isJailed && after.phase is GamePhase.Active) add("Free" to "You're out. Back in the game.")
        if (!was.disqualified && cur.disqualified) add("Disqualified" to "You never reported. You're out for this round.")
        // Your jail under attack: an enemy started holding it.
        val raiders = after.players.values.filter { it.team == cur.team.opponent && it.breakoutSince != null && before.players[it.id]?.breakoutSince == null }
        if (raiders.isNotEmpty() && !cur.isJailed) add("Jailbreak" to "${raiders.joinToString { it.user.displayName }} is holding your jail. ${GameRules.JAILBREAK_HOLD / GameRules.MINUTE} minutes to stop it.")
        // An enemy live on a flag run, and enemy streams waiting on a dispute.
        for (st in after.streams.values) {
            val streamer = after.players[st.by] ?: continue
            if (streamer.team != cur.team.opponent) continue
            val had = before.streams[st.id]
            if (had == null && st.purpose == StreamPurpose.CAPTURE) add("Flag run" to "${streamer.user.displayName} is live on a run at your flag.")
            if (had?.endedAt == null && st.endedAt != null) add("Dispute?" to "${streamer.user.displayName}'s ${if (st.purpose == StreamPurpose.CAPTURE) "flag run" else "jailbreak"} is in. ${GameRules.STREAM_CONTEST_WINDOW / GameRules.MINUTE} minutes to dispute it.")
        }
        if (before.phase !is GamePhase.Active && after.phase is GamePhase.Active) add("Live" to "Flags are down. The game is on.")
        (after.phase as? GamePhase.Ended)?.takeIf { before.phase !is GamePhase.Ended }?.let { add("Game over" to "The round in ${after.city.name} has ended.") }
    }

    /**
     * Location lost on enemy ground: switched off, or no fix for [GameRules.LOCATION_LOST_WARNING].
     * Says where they were last seen, what to do, and exactly what happens if they don't. Null
     * when it doesn't apply. [lastFixAt] is the phone's own newest fix.
     */
    fun locationLost(game: Game, me: PlayerId?, lastFixAt: Millis?, locationOn: Boolean, now: Millis): Pair<String, String>? {
        if (game.phase !is GamePhase.Active) return null
        val p = me?.let { game.players[it] }?.takeIf { !it.isJailed } ?: return null
        val seen = game.lastFix[p.id] ?: return null
        if (game.territory.ownerOf(seen.point) != p.team.opponent) return null
        val lost = !locationOn || lastFixAt == null || now - lastFixAt >= GameRules.LOCATION_LOST_WARNING
        if (!lost) return null
        val where = "%.5f, %.5f".fmt(seen.point.lat, seen.point.lng)
        return "Location lost" to
            "You were last seen on enemy ground at $where. ${if (!locationOn) "Turn location back on and go" else "Go"} back there until it returns. " +
            "If you cross to your side without it, you'll be jailed where you were last seen."
    }

    /** Warning when a prisoner's report deadline is this close. Scheduled by the screen, not by state changes. */
    const val DEADLINE_WARNING = 10 * GameRules.MINUTE
}
