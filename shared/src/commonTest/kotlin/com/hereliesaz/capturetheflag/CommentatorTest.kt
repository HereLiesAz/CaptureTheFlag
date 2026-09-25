package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.commentary.Career
import com.hereliesaz.capturetheflag.commentary.Commentator
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.model.BleSighting
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
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
    private val colourBooth = Commentator(Random(1)) { veteran }
    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun jailFor(h: GeoPoint) = GeoPoint(h.lat + if (h.lat > 30.0) 0.02 else -0.02, h.lng)
    private fun photo(at: GeoPoint, t: Long, ble: List<BleSighting> = emptyList()) = PhotoEvidence("i", at, t, LocationFix(at, t, 5.0), ble)

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
        val start = engine.jailbreak(g, rescuer.id, photo(jail.location, t + MINUTE), t + MINUTE)
        val lines = booth.narrate(g, start.game, start.awards, start.notices, t + MINUTE).map { it.text }
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
}
