package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.model.Team

/**
 * The rules that apply to one player right now, and nothing else.
 * Ordered by urgency: what can end their round first, then what they can do.
 */
object Briefing {
    private const val MIN = GameRules.MINUTE

    fun forPlayer(game: Game, user: PlayerId?, now: Millis, standingIn: Team?): List<String> {
        val me = user?.let { game.players[it] }
        return when (val ph = game.phase) {
            is GamePhase.Signup -> signup(game, user)
            is GamePhase.FlagPlacement -> me?.let { placement(game, it, ph.deadline) } ?: spectator()
            is GamePhase.Active -> me?.let { active(game, it, now, standingIn) } ?: spectator()
            is GamePhase.Ended -> listOf("The round is over. Request the city again to open the next sign-up.")
        }
    }

    private fun spectator() = listOf("You are watching this round. The city chat is open to you.")

    private fun signup(game: Game, user: PlayerId?) = if (game.signups.any { it.id == user }) listOf(
        "When sign-up closes you are dealt to a team at random.",
        "One player per team is drawn as captain.",
    ) else listOf(
        "Sign up before the deadline to play this round.",
        "Teams are dealt at random. You do not choose a side.",
    )

    private fun placement(game: Game, me: Player, base: Millis): List<String> = buildList {
        if (!me.isLeader) {
            add("Your leaders are placing the flag and jail. Nothing is live yet.")
            add("If they miss the deadline, your team forfeits.")
            return@buildList
        }
        if (me.role == Role.CAPTAIN) add("You may name up to ${GameRules.MAX_CO_CAPTAINS} co-captains to share the leadership.")
        if (me.team !in game.flags) {
            add("Place the flag: a public space, public building or business inside your territory.")
            add("The flag is not an object you carry. It is something already there that cannot move: a statue, a doorway, a mural, a bench bolted down. Photograph it on site.")
        } else if (me.team !in game.jails) {
            add("Place the jail: public, inside your territory, at least ${GameRules.JAIL_MIN_FROM_FLAG_M.toInt()} m from your flag.")
            add("The enemy will see where it is.")
        } else add("Flag and jail are placed. Play starts when both teams are ready.")
        add("Missing either when time runs out forfeits the round.")
    }

    private fun active(game: Game, me: Player, now: Millis, standingIn: Team?): List<String> = when {
        me.disqualified -> listOf("You never reported to jail. You are out for this round and it counts for nothing.")
        me.isJailed && me.reportedAt == null -> buildList {
            val jail = game.jails[me.team.opponent]
            add("Go to ${jail?.venueName ?: "the enemy jail"} and stay within ${GameRules.JAIL_REPORT_RADIUS_M.toInt()} m for ${GameRules.JAIL_REPORT_HOLD / MIN} unbroken minutes.")
            me.jailDeadline?.let { add("Finish by the deadline (${(it - now).coerceAtLeast(0) / MIN} min left) or be disqualified and lose the round's points.") }
            add("Until released you earn nothing and cannot tag, capture or break anyone out.")
        }
        me.isJailed -> buildList {
            add("You are frozen: no points, no tagging, no capture, no jailbreak.")
            add("You are free when a teammate streams a ${GameRules.JAILBREAK_HOLD / MIN}-minute hold of the enemy jail, or when the round ends.")
            Progression.perksFor(me.level).paroleMs?.let { add("Parole frees you ${it / GameRules.HOUR} h after you were jailed.") }
        }
        game.streams.values.any { it.by == me.id && it.open } -> buildList {
            val s = game.streams.values.first { it.by == me.id && it.open }
            add("You are live. Keep the camera running: a gap over ${GameRules.STREAM_MAX_GAP / 1000} seconds voids the stream.")
            s.challenge?.let { add("Say your challenge on camera within ${GameRules.STREAM_CHALLENGE_WINDOW / 1000} seconds: \"$it\".") }
            if (s.purpose == StreamPurpose.CAPTURE) {
                add("Your frame of the flag qualified. Keep it rolling while you say the challenge; the footage for the referees ends ${GameRules.STREAM_CHALLENGE_WINDOW / 1000} seconds in.")
            } else {
                add("Stay within ${GameRules.JAIL_REPORT_RADIUS_M.toInt()} m of the jail for ${GameRules.JAILBREAK_HOLD / MIN} minutes on camera, then take the winning frame. Leave and it is over.")
            }
            add("After that, keep streaming as long as you like. The referees' footage stops; the rest is yours.")
            add("You can be tagged while you stream. Jailed mid-stream, it's void.")
        }
        game.streams.values.any { it.by == me.id && it.pending } -> listOf(
            "Your stream is in. The defenders have ${GameRules.STREAM_CONTEST_WINDOW / MIN} minutes to dispute it; undisputed, it counts.",
            "A dispute goes to the referees' automated checks: GPS, timing and sensors all the way through, the challenge heard at the start, and the target matched against the leader's registration photo.",
        )
        standingIn == me.team.opponent -> buildList {
            val inc = game.incursions[me.id]
            val perks = Progression.perksFor(me.level)
            add("You are on enemy ground. They are told where you are, more often the longer you stay.")
            if (inc != null) {
                val next = PingSchedule.dueAt(inc.enteredAt, inc.pingsSent + 1, perks)
                add("Next ping in ${((next - now).coerceAtLeast(0) + MIN - 1) / MIN} min.")
                val reveal = GameRules.IDENTIFY_FROM_PING + perks.identityDelayPings
                if (inc.pingsSent < reveal) add("From ping $reveal they also learn who you are.")
                else add("They know who you are.")
            }
            add("Anyone on this side can jail you with a photo while your phones are close.")
            add("Find their flag and go live on it to win: the stream starts on a frame of the flag, then you say a challenge. Stream a ${GameRules.JAILBREAK_HOLD / MIN}-minute hold of their jail, from ${GameRules.STREAM_APPROACH_M.toInt()} m out, to free teammates.")
            add("Get home unjailed to score for every ping you endured.")
        }
        else -> buildList {
            if (standingIn == null) add("You are outside the city. Nothing here counts.")
            else {
                add("You are on home ground. Intruders here can be jailed: photograph them, name them from the roster, and be close enough for your phones to meet.")
                add("Crossing into enemy ground starts pings about you.")
            }
            if (game.team(me.team).any { it.isJailed && !it.disqualified }) {
                add("Teammates are jailed. Stream a ${GameRules.JAILBREAK_HOLD / MIN}-minute hold of the enemy jail to free them.")
            }
            if (game.streams.values.any { it.pending && it.dispute == null && game.players[it.by]?.team == me.team.opponent }) {
                add("An enemy stream is waiting out its dispute window. If it didn't show your flag or jail, the challenge said, or live footage, dispute it.")
            }
            if (me.isLeader) add("Your flag can't move, so it can't run. Someone has to guard it.")
        }
    }
}
