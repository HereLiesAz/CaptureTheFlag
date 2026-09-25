package com.hereliesaz.capturetheflag.commentary

import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.PingSchedule
import com.hereliesaz.capturetheflag.rules.Progression
import kotlin.random.Random

/** One line of the broadcast. */
data class Commentary(val at: Millis, val text: String)

/**
 * The radio booth. Turns state changes into live play-by-play for everyone in the city:
 * crossings, how long someone has been over the line, breakouts and check-ins as they happen,
 * tags, captures, the clock.
 *
 * It is live, and it is public, so both sides hear it. Live play is told by team, never by
 * name or place: no coordinates, no flag, no which-jail, no who-is-where. Names come out only
 * once something is over and already on the roster: a tag, a report, a breakout, a capture.
 */
class Commentator(private val random: Random = Random.Default) {

    /**
     * Lines for the change from [before] to [after]. [awards] and [notices] come from the same
     * transition; [previousNow] is when the booth last looked, so clock milestones fire once.
     */
    fun narrate(
        before: Game?,
        after: Game,
        awards: List<Award>,
        notices: List<String>,
        now: Millis,
        previousNow: Millis? = null,
    ): List<Commentary> {
        val lines = mutableListOf<String>()
        if (before == null || before.id != after.id) {
            lines += pick(
                "Good evening from ${after.city.name}, where the city has been cut clean in half and nobody asked the city.",
                "We're coming to you live from ${after.city.name}. Sign-ups are open. The line has been drawn. Somebody lives on the wrong side of it.",
            )
            return lines.stamp(now)
        }
        lines += phase(before, after)
        if (after.phase is GamePhase.Active || after.phase is GamePhase.Ended) lines += players(before, after, awards)
        if (after.phase is GamePhase.Active) lines += incursions(before, after)
        lines += notices.filter { "Last Stand" in it }.map {
            pick("$it The photo is thrown out. The crowd is not sure whether to cheer.", "$it Denied. You don't see that twice in a career. Maybe once.")
        }
        if (previousNow != null) lines += clock(after, previousNow, now)
        return lines.stamp(now)
    }

    /** Colour commentary for a quiet stretch. Only facts already on the public roster. */
    fun lull(game: Game, now: Millis): Commentary? {
        val ph = game.phase as? GamePhase.Active ?: return null
        val held = Team.entries.associateWith { t -> game.team(t).count { it.isJailed && !it.disqualified } }
        val hours = (ph.deadline - now) / GameRules.HOUR
        val over = Team.entries.associateWith { t -> game.incursions.keys.count { game.players[it]?.team == t && game.incursions.getValue(it).pingsSent > 0 } }
        val jailLine = (if (over.values.any { it > 0 }) Team.entries.joinToString(", ") { "${it.label} with ${over.getValue(it)} over the line" } + ". " else "") + when {
            held.values.all { it == 0 } -> "Both jails empty. Everyone free, everyone careful."
            else -> Team.entries.joinToString(", ") { "${it.label} with ${held.getValue(it)} behind bars" } + "."
        }
        return Commentary(now, pick(
            "Quiet out there. $jailLine $hours hours on the clock. Somewhere, a flag is sitting very still.",
            "Not much moving, folks. $jailLine $hours hours left. The quiet is a kind of noise.",
            "$jailLine $hours hours to go. If you think nothing's happening, you haven't been out there.",
        ))
    }

    private fun phase(b: Game, a: Game): List<String> {
        if (b.phase::class == a.phase::class) return placements(b, a)
        return when (val p = a.phase) {
            is GamePhase.FlagPlacement -> listOf(pick(
                "Sign-ups are closed. ${a.players.size} players, two sides, and not one of them chose which. Leaders have the hour.",
                "That's the whistle on sign-ups. ${a.players.size} dealt, captains drawn by lottery, which is how most leadership works.",
            ))
            is GamePhase.Active -> placements(b, a) + pick(
                "Flags are down, jails are open, and we are LIVE in ${a.city.name}. Seven days. Somebody's going home a hero, and everybody's going home.",
                "And we're underway in ${a.city.name}. Seven days on the clock. Watch your step, watch your phone, watch your back.",
            )
            is GamePhase.Ended -> listOf(ending(a, p.outcome))
            is GamePhase.Signup -> emptyList()
        }
    }

    private fun placements(b: Game, a: Game): List<String> =
        Team.entries.filter { it in a.flags && it !in b.flags }.map {
            pick(
                "${it.label} has planted its flag. Where? That, friends, is the entire game.",
                "${it.label}'s flag is in the ground. They'd love to tell you where. They won't.",
            )
        } + Team.entries.mapNotNull { t -> a.jails[t]?.takeIf { t !in b.jails } }.map {
            pick(
                "${it.team.label} opens its jail at ${it.venueName}. Accommodations are public. Hospitality is not.",
                "${it.team.label} will be holding prisoners at ${it.venueName}. Visiting hours are fifteen minutes, if you can stand them.",
            )
        }

    private fun players(b: Game, a: Game, awards: List<Award>): List<String> = buildList {
        for ((id, now) in a.players) {
            val was = b.players[id] ?: continue
            val name = now.user.displayName
            when {
                !was.isJailed && now.isJailed -> {
                    val by = awards.firstOrNull { it.points > 0 && it.reason == "Jailed $name" }
                        ?.let { a.players[it.user]?.user?.displayName }
                    add(if (by != null) pick(
                        "$by gets the shot! $name is going to jail, and the photo is not flattering.",
                        "OH, and $name is caught. $by with the camera, the proximity, the paperwork. Clean tag.",
                        "$name wandered one block too far and $by was waiting there like a bill.",
                    ) else "$name has been jailed.")
                }
                was.reportedAt == null && now.reportedAt != null -> add(pick(
                    "$name has reported to the ${now.team.opponent.label} jail. Punctual, if nothing else.",
                    "$name checks in at the jail. Five minutes standing still in enemy country. That's a kind of courage.",
                ))
                was.reportingSince == null && now.reportingSince != null && now.reportedAt == null -> add(pick(
                    "$name is at the ${now.team.opponent.label} jail now. Five minutes of standing still to make it official.",
                    "And there's $name at the jail, clock running on the check-in. Don't wander off.",
                ))
                was.breakoutSince == null && now.breakoutSince != null -> add(pick(
                    "Something's stirring on the ${now.team.label} side. Somebody's trying something bold. We'd tell you more. We won't.",
                    "${now.team.label} is up to something, folks. Can't say what. Can say it takes nerve.",
                ))
                was.breakoutSince != null && now.breakoutSince == null && !now.isJailed && awards.none { it.user == id && it.reason.startsWith("Freed") } -> add(pick(
                    "Whatever ${now.team.label} was attempting, it's off. For now.",
                    "And the ${now.team.label} gambit fizzles. Nobody's walking free tonight.",
                ))
                !was.disqualified && now.disqualified -> add(pick(
                    "$name never made it to jail. Disqualified. The round forgets them, and so will we, briefly.",
                    "Time's up and $name is nowhere near the jail. Out for the round, points and all. Brutal. Fair. Brutal.",
                ))
                was.isJailed && !now.isJailed && a.phase is GamePhase.Active && awards.none { it.reason.startsWith("Freed") } -> add(pick(
                    "$name walks out on parole. Good behavior, or just good seniority.",
                    "Parole for $name. The system works, for some people.",
                ))
            }
        }
        awards.filter { it.reason.startsWith("Freed") }.forEach { f ->
            val hero = a.players[f.user]?.user?.displayName ?: return@forEach
            val n = f.reason.removePrefix("Freed ").toIntOrNull() ?: 0
            add(pick(
                "JAILBREAK! $hero held that jail fifteen long minutes and $n ${if (n == 1) "prisoner walks" else "prisoners walk"} free!",
                "They said it couldn't be done in plain sight. $hero did it in plain sight. $n back in the game!",
            ))
        }
    }

    /** Crossings, escalation and escapes. By team only: the booth never says who, or where. */
    private fun incursions(b: Game, a: Game): List<String> = buildList {
        for ((id, inc) in a.incursions) {
            val was = b.incursions[id]?.pingsSent ?: 0
            if (inc.pingsSent == was) continue
            val p = a.players[id] ?: continue
            val side = p.team.label
            val host = p.team.opponent.label
            val perks = Progression.perksFor(p.level)
            val nowNamed = PingSchedule.identifies(inc.pingsSent, perks) && (was == 0 || !PingSchedule.identifies(was, perks))
            val minutes = (PingSchedule.dueAt(inc.enteredAt, inc.pingsSent, perks) - inc.enteredAt) / GameRules.MINUTE
            when {
                was == 0 -> add(pick(
                    "$side has somebody over the line! $host, check your phones.",
                    "We've got a crossing. One from $side, deep breath, into $host country.",
                    "A $side player just stepped into $host territory. Who? Ask $host. Maybe they know.",
                ))
                nowNamed -> add(pick(
                    "$host has a name on that $side intruder now. We don't. We'd never tell you anyway.",
                    "The pings just gave that $side player away to $host. Face, name, the works. Clock's really running now.",
                ))
                inc.pingsSent % 3 == 0 -> add(pick(
                    "That $side player is still inside $host lines. ${roughly(minutes)}. The pings are coming every five.",
                    "${roughly(minutes)} and a $side player still hasn't gone home. Bold. Or lost.",
                ))
            }
        }
        for ((id, inc) in b.incursions) {
            if (id in a.incursions || inc.pingsSent == 0) continue
            val p = a.players[id] ?: continue
            if (p.isJailed) continue
            add(pick(
                "A ${p.team.label} player makes it home! ${inc.pingsSent} pings survived. Exhale.",
                "Back across the line, one ${p.team.label} player, safe, a little taller than before.",
            ))
        }
    }

    /** Durations told loosely, so the broadcast can't be used as a stopwatch. */
    private fun roughly(minutes: Long) = when {
        minutes < 90 -> "Over an hour"
        minutes < 180 -> "A couple of hours"
        else -> "Hours now"
    }

    private fun ending(a: Game, o: Outcome): String = when (o) {
        is Outcome.FlagCaptured -> {
            val hero = a.players[o.by]?.user?.displayName ?: "Someone"
            pick(
                "IT'S OVER! $hero has the flag in frame! ${o.winner.label} takes ${a.city.name}! Somewhere a flag realizes it was never really theirs!",
                "THE FLAG! THE FLAG! $hero, one photograph, and ${o.winner.label} wins it all! Seven days of fear, ended by a camera phone!",
            )
        }
        is Outcome.Forfeit -> "${o.loser.label} forfeits. ${o.reason}. ${o.loser.opponent.label} wins without a shot. Victory tastes the same. Almost."
        Outcome.Tie -> "And that's the clock. A tie in ${a.city.name}. Nobody lost. Nobody won. Everybody walked a lot."
        Outcome.Cancelled -> "Not enough players showed up. The game is called before it starts. The city remains, regrettably, whole."
    }

    private fun clock(a: Game, previousNow: Millis, now: Millis): List<String> {
        val deadline = (a.phase as? GamePhase.Active)?.deadline ?: return emptyList()
        return listOf(
            GameRules.DAY to "Twenty-four hours left. Whatever you've been saving, you're out of time to save it.",
            GameRules.HOUR to "ONE HOUR REMAINING. Ladies and gentlemen, this is when people do stupid, beautiful things.",
        ).filter { (mark, _) -> deadline - previousNow > mark && deadline - now <= mark }.map { it.second }
    }

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    private fun List<String>.stamp(now: Millis) = map { Commentary(now, it) }

    private val Team.label get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
