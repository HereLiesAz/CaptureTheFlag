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
import com.hereliesaz.capturetheflag.model.Ping
import com.hereliesaz.capturetheflag.model.PingKind
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.GameRules.HOUR
import com.hereliesaz.capturetheflag.ui.Alerts
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertsTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val engine = GameEngine(Random(1))
    private fun photo(at: GeoPoint, t: Long) = PhotoEvidence("i", at, t, LocationFix(at, t, 5.0))

    private fun active(): Game {
        var g = engine.newRound("g", city, territory, 0)
        (1..4).forEach { g = engine.join(g, User("p$it", "N$it", "s")).game }
        g = engine.tick(g, DAY).game
        for (t in Team.entries) {
            val h = if (territory.ownerOf(GeoPoint(30.05, -90.1)) == t) GeoPoint(30.05, -90.1) else GeoPoint(29.95, -90.1)
            val j = GeoPoint(h.lat + if (h.lat > 30) 0.02 else -0.02, h.lng)
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "F", FlagVenueKind.BUSINESS, "a", h, photo(h, DAY), DAY).game
            g = engine.placeJail(g, cap.id, "Jail Venue", "b", j, photo(j, DAY), DAY).game
        }
        return g
    }

    @Test fun pingsBuzzOnlyTheirRecipientsAndTrailsNeverBuzz() {
        val p = Ping("x", 3, GeoPoint(30.0, -90.1), 0, null, setOf("me"))
        assertTrue(Alerts.forPing(p, "me")!!.second.contains("Ping 3"))
        assertNull(Alerts.forPing(p, "someone else"))
        assertNull(Alerts.forPing(p.copy(kind = PingKind.TRACKING), "me"))
    }

    @Test fun beingJailedBuzzesWithWhereAndHowLong() {
        val g = active()
        val me = g.players.values.first()
        val jailed = g.copy(players = g.players + (me.id to me.copy(jailedAt = DAY, jailDeadline = DAY + HOUR)))
        val alerts = Alerts.forChange(g, jailed, me.id, DAY)
        assertEquals("Jailed", alerts.single().first)
        assertTrue("Jail Venue" in alerts.single().second && "60 min" in alerts.single().second)
    }

    @Test fun defendersAreWarnedOfABreakout() {
        val g = active()
        val me = g.players.values.first()
        val raider = g.team(me.team.opponent).first()
        val raided = g.copy(players = g.players + (raider.id to raider.copy(breakoutSince = DAY)))
        assertTrue(Alerts.forChange(g, raided, me.id, DAY).any { it.first == "Jailbreak" && raider.user.displayName in it.second })
        assertTrue(Alerts.forChange(g, raided, raider.id, DAY).none { it.first == "Jailbreak" })
    }
}
