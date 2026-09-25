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
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.RoundStats
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import com.hereliesaz.capturetheflag.rules.Honors
import com.hereliesaz.capturetheflag.rules.Most
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HonorsTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private val users = (1..6).map { User("p$it", "Name$it", "s") }
    private val engine = GameEngine(Random(12))
    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun jailFor(h: GeoPoint) = GeoPoint(h.lat + if (h.lat > 30.0) 0.02 else -0.02, h.lng)
    private fun photo(at: GeoPoint, t: Long, ble: List<BleSighting> = emptyList()) = PhotoEvidence("i", at, t, LocationFix(at, t, 5.0), ble)

    private fun active(): Game {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        for (t in Team.entries) {
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "Secret", FlagVenueKind.PUBLIC_SPACE, "Hidden", home(t), photo(home(t), DAY), DAY).game
            g = engine.placeJail(g, cap.id, "J", "b", jailFor(home(t)), photo(jailFor(home(t)), DAY), DAY).game
        }
        return g
    }

    private fun tag(g0: Game, jailer: Player, prisoner: Player, t: Long): Game {
        var g = engine.reportLocation(g0, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == prisoner.id) prisoner.id else null }
        g = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting(prisoner.id, t, -50))), t, ble).game
        return g
    }

    @Test fun statsAreCountedAsTheyHappen() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val ble = BleTokenRegistry { _, _ -> null }
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), DAY + 1, 5.0)).game
        g = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), DAY + 1), DAY + 1, ble).game // miss
        g = tag(g, jailer, prisoner, DAY + 2)
        assertEquals(RoundStats(tags = 1, nearMisses = 1), g.stats[jailer.id])
        assertEquals(1, g.stats[prisoner.id]?.timesJailed)
        val collector = Honors.mosts(g).single { it.most == Most.COLLECTOR }
        assertEquals(listOf(jailer.id), collector.holders)
    }

    @Test fun theWhistlePaysMvpsAndMosts() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        g = tag(g, jailer, prisoner, DAY + 1)
        val flag = g.flags.getValue(jailer.team.opponent).location
        val won = engine.captureFlag(g, jailer.id, photo(flag, DAY + 2), DAY + 2)
        val reasons = won.awards.filter { it.user == jailer.id }.map { it.reason }
        assertTrue("MVP: ${jailer.team.name}" in reasons, reasons.toString())
        assertTrue("Most: The Collector" in reasons)
        assertTrue(won.awards.any { it.user == prisoner.id && it.reason == "Most: Frequent Flyer" })
    }

    @Test fun theDisqualifiedHoldNothing() {
        val g = active()
        val p = g.players.values.first()
        val dq = g.copy(
            players = g.players + (p.id to p.copy(jailedAt = DAY, disqualified = true)),
            stats = mapOf(p.id to RoundStats(tags = 9)),
        )
        assertTrue(Honors.mosts(dq).isEmpty())
    }

    @Test fun titlesChangingHandsAreCalledAsRivalries() {
        val g = active()
        val (x, y) = g.team(Team.NOIR).take(1) + g.team(Team.BLANC).take(1)
        val before = g.copy(stats = mapOf(x.id to RoundStats(tags = 2), y.id to RoundStats(tags = 1)))
        val after = before.copy(stats = mapOf(x.id to RoundStats(tags = 2), y.id to RoundStats(tags = 3)))
        val booth = Commentator(Random(1), career = { Career(titles = mapOf("The Collector" to 2)) })
        val lines = booth.narrate(before, after, emptyList(), emptyList(), DAY + MINUTE).map { it.text }
        val line = lines.single { "The Collector" in it }
        assertTrue(y.user.displayName in line && x.user.displayName in line && "rivalry" in line, line)
    }

    @Test fun theWhistleRecordsWhoMadeTheLists() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        g = tag(g, jailer, prisoner, DAY + 1)
        val won = engine.captureFlag(g, jailer.id, photo(g.flags.getValue(jailer.team.opponent).location, DAY + 2), DAY + 2)
        val listed = won.highlights.filter { it.kind == com.hereliesaz.capturetheflag.model.HighlightKind.MADE_LIST }
        assertTrue(listed.any { it.user == jailer.id && it.note == "The Collector" && it.value == 1 })
        assertTrue(listed.any { it.user == prisoner.id && it.note == "Frequent Flyer" })
    }

    @Test fun offTheListsMeansOffTheRecord() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val past = listOf(com.hereliesaz.capturetheflag.model.Highlight(
            com.hereliesaz.capturetheflag.model.HighlightKind.LAST_STAND, prisoner.id, jailer.id, "old", "memphis", 1,
        ))
        fun booth(listed: Boolean) = Commentator(
            Random(9),
            antics = { id -> past.filter { it.user == id } },
            listed = { _, _ -> listed },
            nameOf = { id -> g.players[id]?.user?.displayName },
            cityName = { it.replaceFirstChar { c -> c.uppercase() } },
        )
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(home(jailer.team), t, 5.0)).game
        val ble = BleTokenRegistry { k, _ -> if (k == prisoner.id) prisoner.id else null }
        val tagged = engine.tag(g, jailer.id, prisoner.id, photo(home(jailer.team), t, listOf(BleSighting(prisoner.id, t, -50))), t, ble)
        val on = booth(true).narrate(g, tagged.game, tagged.awards, tagged.notices, t).map { it.text }
        val off = booth(false).narrate(g, tagged.game, tagged.awards, tagged.notices, t).map { it.text }
        assertTrue(on.any { "Memphis" in it })
        assertTrue(off.none { "Memphis" in it })
    }
}
