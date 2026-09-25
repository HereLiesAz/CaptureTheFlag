package com.hereliesaz.capturetheflag.commentary

import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.Highlight
import com.hereliesaz.capturetheflag.model.HighlightKind
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.PlayerId
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.Honors
import com.hereliesaz.capturetheflag.rules.PingSchedule
import com.hereliesaz.capturetheflag.rules.Progression
import kotlin.random.Random

/** The play window as the booth says it: "Four", not "4". Follows [GameRules.PLAY_WINDOW]. */
private val playDays = (GameRules.PLAY_WINDOW / GameRules.DAY).toInt().let {
    listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten").getOrElse(it) { n -> "$n" }
}

/** One line of the broadcast. */
data class Commentary(val at: Millis, val text: String)

/**
 * The radio booth. Turns state changes into live play-by-play for everyone in the city.
 *
 * It names names, keeps exact time, reads the play and guesses where it's going (intruders
 * after a flag or a jail, defenders closing on an intruder), brings up a player's record, and
 * works rivalries out of who has jailed whom before. Past antics and rivalries come only from
 * rounds where the player (for a rivalry, both players) made a Mosts list. What it never says is where: no
 * coordinates, no distances to anything, no flag venue. Guesses are about intent, not position.
 */
class Commentator(
    private val random: Random = Random.Default,
    /** Career record for a player, from the ledger, not counting the current round. */
    private val career: (PlayerId) -> Career = { Career.NONE },
    /** Who has jailed whom between two players, across every round including this one. */
    private val rivalry: (PlayerId, PlayerId) -> HeadToHead = { _, _ -> HeadToHead.NONE },
    /** A player's highlights from finished rounds only. Never the round in play: decoys stay secret. */
    private val antics: (PlayerId) -> List<Highlight> = { emptyList() },
    /** Whether a player made a Mosts list in a given finished round. Gates shared history. */
    private val listed: (PlayerId, String) -> Boolean = { _, _ -> true },
    /** Display name for anyone, including players not in this round. */
    private val nameOf: (PlayerId) -> String? = { null },
    /** Display name for a city id. */
    private val cityName: (String) -> String = { it },
) {
    /** Per-player speculation memory: when the booth last guessed, and the distances it guessed from. */
    private data class Hunch(val at: Millis, val toFlag: Double, val toJail: Double)
    private val hunches = mutableMapOf<PlayerId, Hunch>()
    /** (hunter, intruder) → when the booth last called that chase, and the gap between them then. */
    private val chases = mutableMapOf<Pair<PlayerId, PlayerId>, Pair<Millis, Double>>()

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
        highlights: List<Highlight> = emptyList(),
    ): List<Commentary> {
        val lines = mutableListOf<String>()
        if (before == null || before.id != after.id) {
            hunches.clear()
            chases.clear()
            lines += pick(
                "Good evening from ${after.city.name}, where the city has been cut clean in half and nobody asked the city.",
                "We're coming to you live from ${after.city.name}. Sign-ups are open. The line has been drawn. Somebody lives on the wrong side of it.",
            )
            return lines.stamp(now)
        }
        lines += phase(before, after)
        if (after.phase is GamePhase.Active || after.phase is GamePhase.Ended) lines += players(before, after, awards, now)
        if (after.phase is GamePhase.Active) {
            lines += incursions(before, after)
            lines += speculation(before, after, now)
            lines += hunts(after, now)
        }
        lines += notices.filter { "Last Stand" in it }.map {
            pick("$it The photo is thrown out. The crowd is not sure whether to cheer.", "$it Denied. You don't see that twice in a career. Maybe once.")
        }
        lines += levelUps(after, awards)
        if (after.phase is GamePhase.Active) lines += honorsRace(before, after)
        if (after.phase is GamePhase.Ended && before.phase !is GamePhase.Ended) lines += ceremony(after, awards)
        lines += moments(after, highlights)
        if (previousNow != null) lines += clock(after, previousNow, now)
        return lines.stamp(now)
    }

    /** Colour commentary for a quiet stretch. */
    fun lull(game: Game, now: Millis): Commentary? {
        val ph = game.phase as? GamePhase.Active ?: return null
        val hours = (ph.deadline - now) / GameRules.HOUR
        val out = game.incursions.values.filter { it.pingsSent > 0 }.mapNotNull { inc ->
            game.players[inc.playerId]?.let { "${it.user.displayName} (${(now - inc.enteredAt) / GameRules.MINUTE} min over)" }
        }
        val held = game.players.values.filter { it.isJailed && !it.disqualified }.map { it.user.displayName }
        val parts = listOf(
            if (out.isEmpty()) "Nobody over the line" else "Over the line: ${out.joinToString()}",
            if (held.isEmpty()) "both jails empty" else "behind bars: ${held.joinToString()}",
        ).joinToString(", ")
        val veteran = game.players.values.maxByOrNull { career(it.id).points }
            ?.let { v -> bio(v, always = true)?.let { "Keep an eye on ${v.user.displayName}, $it." } }
        return Commentary(now, pick(
            "Quiet out there. $parts. $hours hours on the clock.",
            "Not much moving, folks. $parts. $hours hours left. The quiet is a kind of noise.",
        ) + (veteran?.let { " $it" } ?: ""))
    }

    // --- Phases -----------------------------------------------------------------------------

    private fun phase(b: Game, a: Game): List<String> {
        if (b.phase::class == a.phase::class) return placements(b, a)
        return when (val p = a.phase) {
            is GamePhase.FlagPlacement -> listOf(pick(
                "Sign-ups are closed. ${a.players.size} players, two sides, and not one of them chose which. Leaders have the hour.",
                "That's the whistle on sign-ups. ${a.players.size} dealt, captains drawn by lottery, which is how most leadership works.",
            )) + captains(a)
            is GamePhase.Active -> placements(b, a) + pick(
                "Flags are down, jails are open, and we are LIVE in ${a.city.name}. $playDays days. Somebody's going home a hero, and everybody's going home.",
                "And we're underway in ${a.city.name}. $playDays days on the clock. Watch your step, watch your phone, watch your back.",
            )
            is GamePhase.Ended -> listOf(ending(a, p.outcome))
            is GamePhase.Signup -> emptyList()
        }
    }

    private fun captains(a: Game): List<String> = a.players.values.filter { it.role == Role.CAPTAIN }.map { c ->
        "${c.team.label} will be led by ${c.user.displayName}${bio(c)?.let { ", $it" } ?: ""}."
    }

    private fun placements(b: Game, a: Game): List<String> =
        Team.entries.filter { it in a.flags && it !in b.flags }.map {
            pick(
                "${it.label} has planted its flag. Where? That, friends, is the entire game.",
                "${it.label}'s flag is in the ground. They'd love to tell you where. They won't. Neither will we.",
            )
        } + Team.entries.mapNotNull { t -> a.jails[t]?.takeIf { t !in b.jails } }.map {
            pick(
                "${it.team.label} opens its jail at ${it.venueName}. Accommodations are public. Hospitality is not.",
                "${it.team.label} will be holding prisoners at ${it.venueName}. Visiting hours are fifteen minutes, if you can stand them.",
            )
        }

    // --- People -----------------------------------------------------------------------------

    private fun players(b: Game, a: Game, awards: List<Award>, now: Millis): List<String> = buildList {
        for ((id, cur) in a.players) {
            val was = b.players[id] ?: continue
            val name = cur.user.displayName
            when {
                !was.isJailed && cur.isJailed -> {
                    val tagger = awards.firstOrNull { it.points > 0 && it.reason == "Jailed $name" }?.let { a.players[it.user] }
                    val by = tagger?.user?.displayName
                    add(if (by != null) pick(
                        "$by gets the shot! $name is going to jail, and the photo is not flattering.",
                        "OH, and $name is caught. $by with the camera, the proximity, the paperwork. Clean tag.",
                        "$name wandered one block too far and $by was waiting there like a bill.",
                    ) + (tagger?.let { rivalryOnTag(it, cur) } ?: bio(cur, jailedAgain = true)?.let { " That's $name, $it." } ?: "") else "$name has been jailed.")
                    cur.jailDeadline?.let { add("$name has ${(it - now) / GameRules.MINUTE} minutes to report to the ${cur.team.opponent.label} jail. Clock's running.") }
                }
                was.reportingSince == null && cur.reportingSince != null && cur.reportedAt == null -> add(pick(
                    "$name is at the ${cur.team.opponent.label} jail now. Five minutes of standing still to make it official.",
                    "And there's $name at the jail, clock running on the check-in. Don't wander off.",
                ))
                was.breakoutSince == null && cur.breakoutSince != null -> {
                    val held = a.team(cur.team).count { it.isJailed && !it.disqualified }
                    add(pick(
                        "HOLD ON. $name is standing at the ${cur.team.opponent.label} jail! Fifteen minutes and $held ${if (held == 1) "teammate walks" else "teammates walk"}. ${cur.team.opponent.label}, you hearing this?",
                        "$name has walked right up to the ${cur.team.opponent.label} jail and planted their feet. Jailbreak on. Fifteen minutes. It'll feel like fifty.",
                    ) + (bio(cur)?.let { " $name, $it." } ?: ""))
                }
                was.breakoutSince != null && cur.breakoutSince == null && !cur.isJailed && awards.none { it.user == id && it.reason.startsWith("Freed") } -> {
                    val held = (now - was.breakoutSince) / GameRules.MINUTE
                    add(pick(
                        "$name walks away from the jail after $held minutes. Whatever they saw, they didn't like it.",
                        "And $name blinks! Off the jail at the $held-minute mark. Nobody's walking free tonight.",
                    ))
                }
                !was.disqualified && cur.disqualified -> add(pick(
                    "$name never made it to jail. Disqualified. The round forgets them, and so will we, briefly.",
                    "Time's up and $name is nowhere near the jail. Out for the round, points and all. Brutal. Fair. Brutal.",
                ))
                was.isJailed && !cur.isJailed && a.phase is GamePhase.Active && awards.none { it.reason.startsWith("Freed") } -> add(pick(
                    "$name walks out on parole. Good behavior, or just good seniority.",
                    "Parole for $name. The system works, for some people.",
                ))
            }
        }
        awards.filter { it.reason.startsWith("Freed") }.forEach { f ->
            val hero = a.players[f.user] ?: return@forEach
            val n = f.reason.removePrefix("Freed ").toIntOrNull() ?: 0
            val freed = a.team(hero.team).filter { b.players[it.id]?.isJailed == true && !it.isJailed }.joinToString { it.user.displayName }
            add(pick(
                "JAILBREAK! ${hero.user.displayName} held that jail fifteen long minutes and $freed ${if (n == 1) "walks" else "walk"} free!",
                "They said it couldn't be done in plain sight. ${hero.user.displayName} did it in plain sight. $freed, back in the game!",
            ))
        }
    }

    /** Crossings and escalation, by name, by the minute. */
    private fun incursions(b: Game, a: Game): List<String> = buildList {
        for ((id, inc) in a.incursions) {
            val was = b.incursions[id]?.pingsSent ?: 0
            if (inc.pingsSent == was) continue
            val p = a.players[id] ?: continue
            val name = p.user.displayName
            val host = p.team.opponent.label
            val perks = Progression.perksFor(p.level)
            val reveal = GameRules.IDENTIFY_FROM_PING + perks.identityDelayPings
            val minutes = (PingSchedule.dueAt(inc.enteredAt, inc.pingsSent, perks) - inc.enteredAt) / GameRules.MINUTE
            when {
                was == 0 -> add(pick(
                    "$name is over the line! Into $host territory. $host, check your phones.",
                    "And there goes $name, across into $host country. Deep breath.",
                    "$name has stepped into $host territory. Nobody on $host knows it's them. Yet.",
                ) + (nemesis(a, p)?.let { " $it" } ?: bio(p)?.let { " That's $name, $it." } ?: ""))
                inc.pingsSent == reveal -> add(pick(
                    "Ping $reveal: $host's phones now show exactly who it is. $name, $minutes minutes in.",
                    "The mask is off. $host knows it's $name out there, $minutes minutes behind their lines.",
                ))
                inc.pingsSent % 2 == 0 -> add(pick(
                    "$name, $minutes minutes inside $host territory. Ping ${inc.pingsSent}.",
                    "$minutes minutes and $name still hasn't gone home. Bold. Or lost.",
                ))
            }
        }
        for ((id, inc) in b.incursions) {
            if (id in a.incursions || inc.pingsSent == 0) continue
            val p = a.players[id] ?: continue
            if (p.isJailed) continue
            hunches.remove(id)
            val best = career(id).longestSurvival
            add(pick(
                "${p.user.displayName} makes it home! ${inc.pingsSent} pings survived. Exhale.",
                "Back across the line: ${p.user.displayName}, safe after ${inc.pingsSent} pings, a little taller than before.",
            ) + if (inc.pingsSent > best && best > 0) " That beats their old best of $best." else "")
        }
    }

    /**
     * Reads where an intruder is heading. Compares how their distance to the enemy flag and jail
     * has changed since the last hunch; says what it thinks they want, never how close they are.
     */
    private fun speculation(b: Game, a: Game, now: Millis): List<String> = buildList {
        for ((id, inc) in a.incursions) {
            if (inc.pingsSent == 0) continue
            val fix = a.lastFix[id] ?: continue
            if (b.lastFix[id] == fix) continue
            val p = a.players[id] ?: continue
            val flag = a.flags[p.team.opponent]?.location ?: continue
            val jail = a.jails[p.team.opponent]?.location ?: continue
            val toFlag = fix.point.distanceTo(flag)
            val toJail = fix.point.distanceTo(jail)
            val h = hunches[id]
            if (h == null) { hunches[id] = Hunch(now, toFlag, toJail); continue }
            if (now - h.at < HUNCH_INTERVAL) continue
            val flagGain = h.toFlag - toFlag
            val jailGain = h.toJail - toJail
            val teammatesHeld = a.team(p.team).any { it.isJailed && !it.disqualified }
            val name = p.user.displayName
            val line = when {
                teammatesHeld && jailGain > HUNCH_MIN_M && jailGain >= flagGain -> pick(
                    "$name keeps drifting toward the ${p.team.opponent.label} jail. With teammates inside? I'd bet the house on a breakout.",
                    "Watch $name. Every move since the last ping points the same way, and it's not home. That's a rescue run if I've ever seen one.",
                )
                flagGain > HUNCH_MIN_M -> pick(
                    "$name isn't wandering. $name is closing. I don't know on what, but ${p.team.opponent.label} might want to go check on something they love.",
                    "My gut says $name is hunting the flag, and my gut has been right before. Not often. But before.",
                )
                flagGain < -HUNCH_MIN_M && jailGain < -HUNCH_MIN_M -> pick(
                    "$name is backing off. Cold feet, or a feint. With $name you never know.",
                    "$name drifting away from anything that matters. Could be retreat. Could be bait.",
                )
                else -> null
            }
            hunches[id] = Hunch(now, toFlag, toJail)
            if (line != null) add(line)
        }
    }

    /**
     * Defenders who have been pinged about an intruder and are steadily closing on them.
     * Called as a hunt, with the rivalry if there is one. Never says how close.
     */
    private fun hunts(a: Game, now: Millis): List<String> = buildList {
        for ((id, inc) in a.incursions) {
            if (inc.pingsSent == 0) continue
            val prey = a.players[id] ?: continue
            val preyAt = a.lastFix[id]?.point ?: continue
            var called = false
            for (d in a.team(prey.team.opponent)) {
                if (d.isJailed || id !in (a.pingedAbout[d.id] ?: emptySet())) continue
                val at = a.lastFix[d.id]?.point ?: continue
                val gap = at.distanceTo(preyAt)
                val key = d.id to id
                val last = chases[key]
                if (last == null) { chases[key] = now to gap; continue }
                if (now - last.first < HUNCH_INTERVAL) continue
                chases[key] = now to gap
                if (called || last.second - gap <= HUNCH_MIN_M) continue
                called = true
                val h = rivalry(d.id, id)
                val hunter = d.user.displayName
                val target = prey.user.displayName
                val memory = sharedHistory(d, prey)
                add(if (!h.isRivalry && memory != null) "$hunter is closing on $target. $memory" else if (h.isRivalry) pick(
                    "$hunter and $target. Again. $hunter has put $target away ${times(h.aJailedB)}, $target has returned the favor ${times(h.bJailedA)}, and right now $hunter is closing.",
                    "Here we go. $hunter is moving on $target, and these two have history: ${h.aJailedB} to ${h.bJailedA}. Grudges don't need GPS.",
                ) else pick(
                    "$hunter has had the pings on $target and is not sitting still. That's a hunt, folks.",
                    "Every step $hunter takes lately points at $target. You don't need a map to read that.",
                    "$hunter's closing on $target. Somebody's about to have a very bad photograph taken.",
                ))
            }
        }
    }

    /** On a tag: the head-to-head, including the tag just made, if these two have a past. */
    private fun rivalryOnTag(tagger: Player, prisoner: Player): String? {
        val h = rivalry(tagger.id, prisoner.id)
        if (h.total < 2) return sharedHistory(tagger, prisoner)?.let { " $it" }
        val t = tagger.user.displayName
        val p = prisoner.user.displayName
        return when {
            h.bJailedA == 0 -> " That's ${ordinal(h.aJailedB)} time $t has jailed $p. $p has never once returned the favor."
            h.aJailedB > h.bJailedA -> " $t leads that rivalry ${h.aJailedB} to ${h.bJailedA} now."
            h.aJailedB == h.bJailedA -> " And that squares it: $t and $p, ${h.aJailedB} apiece."
            else -> " $p still leads that one ${h.bJailedA} to ${h.aJailedB}, but $t is chipping away."
        }
    }

    /** On a crossing: a defender with a history against this intruder, if there is one. */
    private fun nemesis(a: Game, intruder: Player): String? {
        if (random.nextInt(2) == 0) return null
        val (d, h) = a.team(intruder.team.opponent).filterNot { it.isJailed }
            .map { it to rivalry(it.id, intruder.id) }.filter { it.second.isRivalry }
            .maxByOrNull { it.second.total } ?: return null
        return "And somewhere on the other side, ${d.user.displayName}, who has jailed ${intruder.user.displayName} ${times(h.aJailedB)}, just got a lot more interested."
    }

    /** Anyone this transition pushed over a level line. Milestones, where advantages arrive, get the big call. */
    private fun levelUps(a: Game, awards: List<Award>): List<String> =
        awards.filter { it.points > 0 }.groupBy { it.user }.mapNotNull { (id, gained) ->
            val p = a.players[id] ?: return@mapNotNull null
            val total = career(id).points
            val from = Progression.levelFor(total - gained.sumOf { it.points })
            val to = Progression.levelFor(total)
            if (to <= from) return@mapNotNull null
            val name = p.user.displayName
            val milestone = Progression.nextMilestone(from).takeIf { it <= to }
            when {
                milestone != null && Progression.powerAt(milestone) > 1 -> "And that's level $to for $name. A double milestone. Whatever they had before, they've got more of it now."
                milestone != null -> "$name hits level $to, and level $milestone brings something new with it. Watch them."
                else -> pick("$name ticks up to level $to.", "Level $to for $name. The climb continues.")
            }
        }

    /**
     * Live calls from this transition's highlights. Secret kinds (decoys, vanishes,
     * interrogations, tripwires) are never aired live; they wait for later rounds.
     */
    private fun moments(a: Game, highlights: List<Highlight>): List<String> = highlights.filterNot { it.secret }.mapNotNull { h ->
        val who = a.players[h.user]?.user?.displayName ?: return@mapNotNull null
        val other = h.other?.let { a.players[it]?.user?.displayName }
        when (h.kind) {
            HighlightKind.NEAR_MISS -> other?.let {
                val past = antics(h.user).count { x -> x.kind == HighlightKind.NEAR_MISS && x.other == h.other }
                pick(
                    "$who takes the shot at $it and... NO! The booth says no. Somebody wasn't close enough.",
                    "$who thought they had $it. They did not have $it.",
                ) + if (past > 0) " That's ${times(past + 1)} now $who has come up empty on $it." else ""
            }
            HighlightKind.REPORTED -> if (h.value <= 180) "$who checks in with ${h.value} seconds to spare. You could hear the whole city exhale." else null
            HighlightKind.BOUNTY_COLLECTED -> other?.let { "And that's a bounty collected: $who cashes $it at ×${h.value / 10.0}." }
            else -> null
        }
    }

    /** The best story from a player's past rounds, as a clause, or null. */
    private fun anecdote(id: PlayerId): String? {
        val past = antics(id)
        fun name(x: PlayerId?) = x?.let(nameOf) ?: "someone"
        val options = buildList {
            past.filter { it.kind == HighlightKind.LAST_STAND }.maxByOrNull { it.at }?.let {
                add("who once threw out ${name(it.other)}'s photo with a Last Stand in ${cityName(it.city)}")
            }
            past.filter { it.kind == HighlightKind.DECOY }.maxByOrNull { it.value }?.let {
                add(if (it.value >= 3) "who once walked a decoy ${it.value} blocks through ${cityName(it.city)} and had half a team chasing it" else "who has been known to send a decoy or two")
            }
            if (past.any { it.kind == HighlightKind.VANISH }) add("who has vanished off the pings before, mid-incursion")
            past.filter { it.kind == HighlightKind.REPORTED && it.value <= 60 }.minByOrNull { it.value }?.let {
                add("who once reported to jail with ${it.value} seconds to spare")
            }
            past.filter { it.kind == HighlightKind.BREAKOUT_ABANDONED }.maxByOrNull { it.value }?.let {
                add("who once walked off a breakout ${it.value} minutes in")
            }
            past.filter { it.kind == HighlightKind.TRIPWIRE }.maxByOrNull { it.at }?.let {
                add("whose tripwire caught ${name(it.other)} cold in ${cityName(it.city)}")
            }
            past.filter { it.kind == HighlightKind.BOUNTY_COLLECTED }.maxByOrNull { it.at }?.let {
                add("who once cashed a bounty on ${name(it.other)}")
            }
            past.count { it.kind == HighlightKind.NEAR_MISS }.takeIf { it >= 3 }?.let { add("$it missed shots on record, and still shooting") }
            if (past.count { it.kind == HighlightKind.PAROLE } >= 2) add("paroled more than once, which says something about someone")
        }
        return options.randomOrNull(random)
    }

    /** A remembered moment between two specific players from past rounds, as a sentence, or null. */
    private fun sharedHistory(a: Player, b: Player): String? {
        val an = a.user.displayName
        val bn = b.user.displayName
        val between = (antics(a.id).filter { it.other == b.id }.map { it to true } + antics(b.id).filter { it.other == a.id }.map { it to false })
            .filter { (h, _) -> listed(a.id, h.game) && listed(b.id, h.game) }
        val (h, aFirst) = between.maxByOrNull { it.first.at } ?: return null
        val (x, y) = if (aFirst) an to bn else bn to an
        val where = cityName(h.city)
        return when (h.kind) {
            HighlightKind.LAST_STAND -> "Remember $where? $x made a Last Stand and threw $y's photo right out."
            HighlightKind.NEAR_MISS -> "Last time, in $where, $x had $y in the frame and the phones never met."
            HighlightKind.TRIPWIRE -> "Back in $where, $x's tripwire went off the second $y crossed."
            HighlightKind.INTERROGATION -> "In $where, $x interrogated $y right off the map."
            HighlightKind.BOUNTY_COLLECTED -> "$x has cashed a bounty on $y before. In $where. $y remembers."
            else -> null
        }
    }

    /** Titles changing hands, live. Anyone overtaken on a list is a rival now. */
    private fun honorsRace(b: Game, a: Game): List<String> = buildList {
        val before = Honors.mosts(b).associateBy { it.most }
        for (h in Honors.mosts(a)) {
            val old = before[h.most]
            if (old != null && old.holders == h.holders && old.value == h.value) continue
            val newcomers = h.holders - (old?.holders ?: emptyList()).toSet()
            if (newcomers.isEmpty()) continue
            val who = newcomers.mapNotNull { a.players[it] }
            val names = who.joinToString(" and ") { it.user.displayName }
            val dethroned = (old?.holders ?: emptyList()).filter { it !in h.holders }.mapNotNull { a.players[it] }
            val stat = "${h.value} for ${h.most.blurb}"
            add(when {
                old == null -> "$names opens the books on ${h.most.title}: $stat."
                dethroned.isEmpty() -> "$names draws level for ${h.most.title}. $stat, and it's shared."
                else -> pick(
                    "$names takes ${h.most.title} from ${dethroned.joinToString(" and ") { it.user.displayName }}! $stat.",
                    "New name at the top of ${h.most.title}: $names, $stat. ${dethroned.joinToString(" and ") { it.user.displayName }} will not enjoy hearing that.",
                ) + (who.firstOrNull()?.let { w -> dethroned.firstNotNullOfOrNull { d -> titleRivalry(a, w, d, h.most.title) } }?.let { " $it" } ?: "")
            })
        }
        val mvpBefore = Honors.mvps(b)
        for ((t, id) in Honors.mvps(a)) {
            if (mvpBefore[t] == id) continue
            val p = a.players[id] ?: continue
            add(pick(
                "${p.user.displayName} is ${t.label}'s MVP right now. ${a.earned[id] ?: 0} points this round.",
                "If the round ended this minute, ${t.label}'s MVP would be ${p.user.displayName}. It won't end this minute.",
            ))
        }
    }

    /** The final whistle's honors, read off the bonus awards. */
    private fun ceremony(a: Game, awards: List<Award>): List<String> = buildList {
        fun n(id: PlayerId) = a.players[id]?.user?.displayName ?: nameOf(id) ?: "someone"
        val mvps = awards.filter { it.reason.startsWith("MVP: ") }
        if (mvps.isNotEmpty()) add("Your MVPs: " + mvps.joinToString(", ") { m ->
            val t = Team.valueOf(m.reason.removePrefix("MVP: "))
            val prior = career(m.user).mvps
            "${n(m.user)} for ${t.label}" + if (prior > 0) " (MVP number ${prior + 1})" else ""
        } + ".")
        val mosts = awards.filter { it.reason.startsWith("Most: ") }.groupBy { it.reason.removePrefix("Most: ") }
        if (mosts.isNotEmpty()) add("And the Mosts: " + mosts.entries.joinToString("; ") { (title, list) ->
            "$title, ${list.joinToString(" and ") { n(it.user) }}"
        } + ". Bonus points all round. Well. Most of the way round.")
    }

    /**
     * Rivalry recognition from the lists: two players who have both held [title] in past rounds,
     * or who have jail history. Null if they're strangers.
     */
    private fun titleRivalry(a: Game, x: Player, y: Player, title: String): String? {
        val xs = career(x.id).titles[title] ?: 0
        val ys = career(y.id).titles[title] ?: 0
        val h = rivalry(x.id, y.id)
        return when {
            xs > 0 && ys > 0 -> "Both of them have worn this crown before: ${x.user.displayName} ${times(xs)}, ${y.user.displayName} ${times(ys)}. Call it what it is. A rivalry."
            h.isRivalry -> "And these two have jailed each other ${h.total} times between them. This is personal."
            else -> null
        }
    }

    private fun times(n: Int) = when (n) { 0 -> "never"; 1 -> "once"; 2 -> "twice"; else -> "$n times" }

    private fun ordinal(n: Int) = when (n) { 1 -> "the first"; 2 -> "the second"; 3 -> "the third"; else -> "the ${n}th" }

    // --- Endings and the clock ---------------------------------------------------------------

    private fun ending(a: Game, o: Outcome): String = when (o) {
        is Outcome.FlagCaptured -> {
            val hero = a.players[o.by]
            val name = hero?.user?.displayName ?: "Someone"
            val prior = hero?.let { career(it.id).captures } ?: 0
            pick(
                "IT'S OVER! $name has the flag in frame! ${o.winner.label} takes ${a.city.name}! Somewhere a flag realizes it was never really theirs!",
                "THE FLAG! THE FLAG! $name, one photograph, and ${o.winner.label} wins it all! Days of fear, ended by a camera phone!",
            ) + when (prior) {
                0 -> " First capture of $name's career."
                1 -> " That's number two for $name."
                else -> " That's capture number ${prior + 1} for $name. At some point we stop calling it luck."
            }
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

    // --- Colour ------------------------------------------------------------------------------

    /**
     * A clause from the player's record, or null. Only sometimes, unless [always]: a booth that
     * reads everyone's stats every time is a booth nobody listens to.
     */
    private fun bio(p: Player, always: Boolean = false, jailedAgain: Boolean = false): String? {
        if (!always && random.nextInt(2) == 0) return null
        val c = career(p.id)
        val options = buildList {
            if (c.rookie) add("playing their very first round")
            if (c.captures > 0) add("who took the flag in ${c.captureCities.last()}${if (c.captures > 1) " and ${c.captures - 1} more besides" else ""}")
            if (c.tags >= 3) add("${c.tags} career tags and a camera that doesn't miss")
            if (jailedAgain && c.timesJailed >= 2) add("jailed ${c.timesJailed} times before this, and they keep coming back")
            if (c.disqualifications > 0) add("who once never made it to jail at all, and we remember")
            if (c.longestSurvival >= 6) add("who once lasted ${c.longestSurvival} pings behind enemy lines")
            if (c.biggestBreakout >= 2) add("who once sprang ${c.biggestBreakout} teammates in one go")
            if (c.wins >= 3) add("${c.wins} wins and the posture to prove it")
            if (c.level >= 30) add("level ${c.level}, which around here means something")
            if (c.streak >= 3) add("winners of their last ${c.streak} rounds, and it shows in the walk")
            if (c.streak == 2) add("coming off back-to-back wins")
            if (c.streak <= -3) add("who has lost ${-c.streak} straight and is due, or doomed")
            if (c.streak == -2) add("two losses running and looking for somebody to blame")
            if (c.rounds in 1..5 && c.pace >= 3.0) add("already level ${c.level} after ${c.rounds} ${if (c.rounds == 1) "round" else "rounds"}, a climb like that doesn't happen by accident")
            if (c.mvps >= 2) add("${c.mvps}-time MVP")
            c.titles.maxByOrNull { it.value }?.let { (t, n) -> add(if (n > 1) "$n-time holder of $t" else "who once held $t") }
            if (c.rounds >= 8 && c.level <= 5) add("${c.rounds} rounds in and still level ${c.level}. Patience is also a sport")
        }
        val story = anecdote(p.id)
        return (if (story != null && random.nextBoolean()) story else options.randomOrNull(random)) ?: story
    }

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    private fun List<String>.stamp(now: Millis) = map { Commentary(now, it) }

    private val Team.label get() = name.lowercase().replaceFirstChar { it.uppercase() }

    private companion object {
        /** At most one guess per intruder this often. */
        const val HUNCH_INTERVAL = 10 * GameRules.MINUTE
        /** Movement below this is noise, not intent. */
        const val HUNCH_MIN_M = 150.0
    }
}
