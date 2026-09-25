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

    /** Warning when a prisoner's report deadline is this close. Scheduled by the screen, not by state changes. */
    const val DEADLINE_WARNING = 10 * GameRules.MINUTE
}
