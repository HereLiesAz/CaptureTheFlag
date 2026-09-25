package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.model.StreamPurpose
import com.hereliesaz.capturetheflag.commentary.Career
import com.hereliesaz.capturetheflag.commentary.Commentator
import com.hereliesaz.capturetheflag.commentary.HeadToHead
import com.hereliesaz.capturetheflag.model.Award
import com.hereliesaz.capturetheflag.model.Highlight
import com.hereliesaz.capturetheflag.model.HighlightKind
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.model.BleSighting
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.DevicePose
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.GameRules.HOUR
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentatorTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private val users = (1..6).map { User("p$it", "Name$it", "s") }
    private val engine = GameEngine(Random(5))
    private val booth = Commentator(Random(5))
    private val veteran = Career(points = 60_000, tags = 12, captures = 2, captureCities = listOf("Chicago", "Memphis"), rounds = 9, wins = 5)
    private val colourBooth = Commentator(Random(1), career = { veteran })
    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun jailFor(h: GeoPoint) = GeoPoint(h.lat + if (h.lat > 30.0) 0.02 else -0.02, h.lng)
    private fun photo(at: GeoPoint, t: Long, ble: List<BleSighting> = emptyList()) = PhotoEvidence("i", at, t, LocationFix(at, t, 5.0), ble, exifDirection = 0.0, pose = DevicePose(0.0, 0.0, 0.0, t))

    private fun active(): Game {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        for (t in Team.entries) {
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "Secret Venue", FlagVenueKind.PUBLIC_SPACE, "1 Hidden St", home(t), photo(home(t), DAY), DAY).game
            g = engine.placeJail(g, cap.id, "J", "b", jailFor(home(t)), photo(jailFor(home(t)), DAY), DAY).game
        }
        return g
    }

    /** Names and minutes are fair game. Coordinates, distances and the flag's venue are not. */
    private fun List<String>.givesNothingAway(g: Game) {
        assertTrue(isNotEmpty())
        forEach { line ->
            assertTrue(Regex("\\d+\\.\\d{3,}").find(line) == null, "leaked coordinates: $line")
            assertTrue(Regex("\\d+\\s?(m|km|metres|meters)\\b").find(line) == null, "leaked a distance: $line")
            g.flags.values.forEach { assertTrue(it.venueName !in line && it.address !in line, "leaked the flag: $line") }
        }
    }

    @Test fun crossingsAreNarratedLiveByNameAndMinute() {
        val g0 = active()
        val p = g0.players.values.first()
        var g = g0
        val lines = mutableListOf<String>()
        var t = DAY + 1
        val step = engine.reportLocation(g, p.id, LocationFix(home(p.team.opponent), t, 5.0))
        lines += booth.narrate(g, step.game, step.awards, step.notices, t).map { it.text }
        g = step.game
        repeat(10) {
            t += 30 * MINUTE
            val tick = engine.tick(g, t)
            lines += booth.narrate(g, tick.game, tick.awards, tick.notices, t).map { it.text }
            g = tick.game
        }
        assertTrue(lines.any { p.user.displayName in it })
        assertTrue(lines.any { Regex("\\d+ minutes").containsMatchIn(it) })
        lines.givesNothingAway(g)
    }

    @Test fun breakoutsAreCalledByName() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == "k") prisoner.id else null }
        g = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting("k", t, -50))), t, ble).game
        val rescuer = g.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g.jails.getValue(prisoner.team.opponent)
        val start = engine.stream(g, rescuer.id, StreamPurpose.JAILBREAK, jail.location, t + MINUTE, { at, ts -> photo(at, ts) }) { it.players.getValue(rescuer.id).breakoutSince != null }
        val lines = booth.narrate(g, start.game, start.awards, start.notices, t + 2 * MINUTE).map { it.text }
        lines.givesNothingAway(g)
        assertTrue(lines.any { rescuer.user.displayName in it })
    }

    @Test fun finishedTagsNameNames() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == "k") prisoner.id else null }
        val tagged = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting("k", t, -50))), t, ble)
        val lines = booth.narrate(g, tagged.game, tagged.awards, tagged.notices, t).map { it.text }
        assertTrue(lines.any { prisoner.user.displayName in it && jailer.user.displayName in it })
    }

    @Test fun clockMilestonesFireOnce() {
        val g = active()
        val deadline = (g.phase as com.hereliesaz.capturetheflag.model.GamePhase.Active).deadline
        val first = booth.narrate(g, g, emptyList(), emptyList(), deadline - HOUR + 1, deadline - HOUR - 1)
        val again = booth.narrate(g, g, emptyList(), emptyList(), deadline - HOUR + 2, deadline - HOUR + 1)
        assertTrue(first.any { "ONE HOUR" in it.text } && again.none { "ONE HOUR" in it.text })
    }

    @Test fun careersColourTheBroadcast() {
        val g = active()
        val lines = (1..20).mapNotNull { colourBooth.lull(g, DAY + it * HOUR)?.text }
        assertTrue(lines.any { "Chicago" in it || "Memphis" in it || "12 career tags" in it || "5 wins" in it || "level" in it })
    }

    @Test fun hunchesReadIntentNotPosition() {
        var g = active()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val start = GeoPoint(flag.lat + (if (flag.lat > 30.0) -0.03 else 0.03), flag.lng)
        val booth = Commentator(Random(2))
        var t = DAY + 1
        val lines = mutableListOf<String>()
        for (i in 0..8) {
            val pt = GeoPoint(start.lat + (flag.lat - start.lat) * i / 10.0, start.lng)
            val tr = engine.reportLocation(g, p.id, LocationFix(pt, t, 5.0))
            lines += booth.narrate(g, tr.game, tr.awards, tr.notices, t).map { it.text }
            g = tr.game
            t += 15 * MINUTE
        }
        assertTrue(lines.any { "flag" in it || "closing" in it }, lines.joinToString("\n"))
        lines.givesNothingAway(g)
    }

    @Test fun headToHeadPairsTaggerWithPrisoner() {
        fun tag(by: String, victim: String, at: Long) = listOf(
            Award(by, "nola", "g$at", 12, "Jailed ${victim.uppercase()}", at),
            Award(victim, "nola", "g$at", -5, "Jailed", at),
        )
        val ledger = tag("a", "b", 1) + tag("a", "b", 2) + tag("b", "a", 3) + tag("c", "b", 4)
        val h = HeadToHead.between(ledger, "a", "b")
        assertEquals(2, h.aJailedB); assertEquals(1, h.bJailedA)
        assertTrue(h.isRivalry)
        assertEquals(HeadToHead.NONE, HeadToHead.between(ledger, "a", "c"))
    }

    @Test fun tagsCallTheRivalry() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val booth = Commentator(Random(3), rivalry = { a, b -> if (a == jailer.id && b == prisoner.id) HeadToHead(3, 1) else HeadToHead.NONE })
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == "k") prisoner.id else null }
        val tagged = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting("k", t, -50))), t, ble)
        val lines = booth.narrate(g, tagged.game, tagged.awards, tagged.notices, t).map { it.text }
        assertTrue(lines.any { "3 to 1" in it }, lines.joinToString("\n"))
    }

    @Test fun huntersClosingInAreCalled() {
        var g = active()
        val prey = g.players.values.first()
        val preyAt = home(prey.team.opponent)
        var t = DAY + 1
        g = engine.reportLocation(g, prey.id, LocationFix(preyAt, t, 5.0)).game
        val hunter = g.team(prey.team.opponent).first { prey.id in (g.pingedAbout[it.id] ?: emptySet()) }
        val booth = Commentator(Random(4), rivalry = { a, b -> if (a == hunter.id && b == prey.id) HeadToHead(2, 2) else HeadToHead.NONE })
        val lines = mutableListOf<String>()
        for (i in 0..4) {
            t += 11 * MINUTE
            val step = GeoPoint(preyAt.lat + (if (preyAt.lat > 30.0) 1 else -1) * 0.02 * (4 - i) / 4.0, preyAt.lng)
            val tr = engine.reportLocation(g, hunter.id, LocationFix(step, t, 5.0))
            lines += booth.narrate(g, tr.game, tr.awards, tr.notices, t).map { it.text }
            g = tr.game
        }
        assertTrue(lines.any { hunter.user.displayName in it && prey.user.displayName in it && "2 to 2" in it || "Again" in it }, lines.joinToString("\n"))
        lines.givesNothingAway(g)
    }

    @Test fun streaksAndPaceReadOffTheLedger() {
        fun round(g: String, at: Long, result: String?) = listOfNotNull(
            Award("x", "nola", g, 12, "Jailed Y", at),
            result?.let { Award("x", "nola", g, 25, it, at + 1) },
        )
        val wins = round("g1", 1, null) + round("g2", 10, "Team won") + round("g3", 20, "Team won") + round("g4", 30, "Opponent forfeited")
        assertEquals(3, Career.from(wins, "x").streak)
        val losses = round("g1", 1, "Team won") + round("g2", 10, null) + round("g3", 20, null)
        assertEquals(-2, Career.from(losses, "x").streak)
        assertEquals(0, Career.from(losses + round("g4", 30, "Tie"), "x").streak)
        assertEquals(3, Career.from(wins, "x", excluding = setOf("g4")).rounds)
    }

    @Test fun levelUpsAreCalledAndMilestonesGetTheBigCall() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == "k") prisoner.id else null }
        val tagged = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting("k", t, -50))), t, ble)
        val gained = tagged.awards.single { it.user == jailer.id }.points
        // Just over the line into level 15, a milestone.
        val total = com.hereliesaz.capturetheflag.rules.Progression.pointsFor(15) + gained - 1
        val booth = Commentator(Random(6), career = { if (it == jailer.id) Career(points = total) else Career.NONE })
        val lines = booth.narrate(g, tagged.game, tagged.awards, tagged.notices, t).map { it.text }
        assertTrue(lines.any { "level 15" in it && jailer.user.displayName in it }, lines.joinToString("\n"))
    }

    @Test fun rookiesAndStreaksColourTheBroadcast() {
        val g = active()
        val hot = Commentator(Random(8), career = { Career(points = 40_000, rounds = 6, streak = 4) })
        val cold = Commentator(Random(8), career = { Career(points = 500, rounds = 9, streak = -3) })
        assertTrue((1..30).mapNotNull { hot.lull(g, DAY + it * HOUR)?.text }.any { "last 4 rounds" in it })
        assertTrue((1..30).mapNotNull { cold.lull(g, DAY + it * HOUR)?.text }.any { "lost 3 straight" in it || "Patience" in it })
    }

    @Test fun engineLogsHighlights() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == "k") prisoner.id else null }
        val miss = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t), t, ble) // no BLE: fails
        assertEquals(listOf(HighlightKind.NEAR_MISS), miss.highlights.map { it.kind })
        assertEquals(prisoner.id, miss.highlights.single().other)
    }

    @Test fun secretsStayOffAirButNearMissesGoOut() {
        val g = active()
        val a = g.players.values.first()
        val b = g.team(a.team.opponent).first()
        val secret = Highlight(HighlightKind.DECOY, a.id, null, g.id, city.id, DAY, 4)
        val miss = Highlight(HighlightKind.NEAR_MISS, b.id, a.id, g.id, city.id, DAY)
        val lines = booth.narrate(g, g, emptyList(), emptyList(), DAY, highlights = listOf(secret, miss)).map { it.text }
        assertTrue(lines.any { b.user.displayName in it && a.user.displayName in it })
        assertTrue(lines.none { "decoy" in it.lowercase() })
    }

    @Test fun pastAnticsAndSharedHistoryComeUp() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val past = listOf(Highlight(HighlightKind.LAST_STAND, prisoner.id, jailer.id, "old", "memphis", 1))
        val booth = Commentator(
            Random(9),
            antics = { id -> past.filter { it.user == id } },
            nameOf = { id -> g.players[id]?.user?.displayName },
            cityName = { it.replaceFirstChar { c -> c.uppercase() } },
        )
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == "k") prisoner.id else null }
        val tagged = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting("k", t, -50))), t, ble)
        val lines = booth.narrate(g, tagged.game, tagged.awards, tagged.notices, t, highlights = tagged.highlights).map { it.text }
        assertTrue(lines.any { "Memphis" in it && "Last Stand" in it }, lines.joinToString("\n"))
    }
}
