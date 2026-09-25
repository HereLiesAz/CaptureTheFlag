package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.Briefing
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class BriefingTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private val users = (1..6).map { User("p$it", "Player $it", "s") }
    private val engine = GameEngine(Random(3))
    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun jailFor(h: GeoPoint) = GeoPoint(h.lat + if (h.lat > 30.0) 0.02 else -0.02, h.lng)
    private fun photo(at: GeoPoint, t: Long) = PhotoEvidence("i", at, t, LocationFix(at, t, 5.0))

    private fun active(): Game {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        for (t in Team.entries) {
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "V", FlagVenueKind.PUBLIC_SPACE, "a", home(t), photo(home(t), DAY), DAY).game
            g = engine.placeJail(g, cap.id, "J", "b", jailFor(home(t)), photo(jailFor(home(t)), DAY), DAY).game
        }
        return g
    }

    private fun List<String>.mentions(word: String) = any { it.contains(word, ignoreCase = true) }

    @Test fun homeGroundShowsTaggingNotPings() {
        val g = active()
        val p = g.players.values.first { !it.isLeader }
        val rules = Briefing.forPlayer(g, p.id, DAY + 1, p.team)
        assertTrue(rules.mentions("intruders") && !rules.mentions("next ping") && !rules.mentions("report"))
    }

    @Test fun enemyGroundShowsPingsNotTagging() {
        var g = active()
        val p = g.players.values.first()
        g = engine.reportLocation(g, p.id, LocationFix(home(p.team.opponent), DAY + 1, 5.0)).game
        val rules = Briefing.forPlayer(g, p.id, DAY + 2, p.team.opponent)
        assertTrue(rules.mentions("next ping") && rules.mentions("jail you") && !rules.mentions("intruders here"))
    }

    @Test fun prisonersSeeOnlyJailRules() {
        val g = active()
        val p = g.players.values.first()
        val jailed = g.copy(players = g.players + (p.id to p.copy(jailedAt = DAY, jailDeadline = DAY + 3_600_000)))
        val rules = Briefing.forPlayer(jailed, p.id, DAY + 1, p.team.opponent)
        assertTrue(rules.mentions("unbroken minutes") && rules.mentions("disqualified"))
        assertTrue(!rules.mentions("flag") && !rules.mentions("ping"))
        val reported = jailed.copy(players = jailed.players + (p.id to jailed.players.getValue(p.id).copy(reportedAt = DAY + 1)))
        assertTrue(Briefing.forPlayer(reported, p.id, DAY + 2, null).mentions("frozen"))
    }

    @Test fun followersDuringPlacementAreToldToWait() {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        val follower = g.players.values.first { !it.isLeader }
        val captain = g.players.values.first { it.role == Role.CAPTAIN }
        assertTrue(!Briefing.forPlayer(g, follower.id, DAY, null).mentions("photograph"))
        assertTrue(Briefing.forPlayer(g, captain.id, DAY, null).mentions("co-captains"))
    }
}
