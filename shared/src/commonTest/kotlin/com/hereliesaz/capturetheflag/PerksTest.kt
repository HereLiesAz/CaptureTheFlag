package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.model.BleSighting
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.PingKind
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.GameRules.HOUR
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import com.hereliesaz.capturetheflag.rules.PerkStart
import com.hereliesaz.capturetheflag.rules.Progression
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerksTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private val users = (1..6).map { User("p$it", "Player $it", "s") }
    private val engine = GameEngine(Random(7))

    private fun lv(power: Int) = Progression.levelForPower(power)
    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun away(t: Team) = home(t.opponent)
    private fun photo(at: GeoPoint, t: Long, ble: List<BleSighting> = emptyList()) =
        PhotoEvidence("img", at, t, LocationFix(at, t, 5.0), ble)

    private fun active(): Game {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        for (t in Team.entries) {
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "V", FlagVenueKind.PUBLIC_SPACE, "a", home(t), photo(home(t), DAY), DAY).game
        }
        assertIs<GamePhase.Active>(g.phase)
        return g
    }

    private fun Game.level(p: Player, level: Int) = copy(players = players + (p.id to p.copy(level = level)))
    private fun Game.p(id: String) = players.getValue(id)

    @Test fun thresholdDelaysTheFirstPing() {
        var g = active()
        val p = g.players.values.first()
        g = g.level(p, lv(PerkStart.THRESHOLD))
        val t0 = DAY + 1
        val tr = engine.reportLocation(g, p.id, LocationFix(away(p.team), t0, 5.0))
        assertTrue(tr.pings.none { it.kind == PingKind.INCURSION })
        assertEquals(1, engine.tick(tr.game, t0 + 30_000).pings.count { it.kind == PingKind.INCURSION })
    }

    @Test fun vanishSwallowsOnePing() {
        var g = active()
        val p = g.players.values.first()
        g = g.level(p, lv(PerkStart.VANISH))
        g = engine.reportLocation(g, p.id, LocationFix(away(p.team), DAY + 1, 5.0)).game
        g = engine.tick(g, DAY + HOUR).game
        assertEquals(Verdict.Valid, engine.vanish(g, p.id).verdict)
        g = engine.vanish(g, p.id).game
        val incursion = g.incursions.getValue(p.id)
        val next = engine.tick(g, DAY + 1 + 24 * HOUR)
        val numbers = next.pings.filter { it.subject == p.id && it.kind == PingKind.INCURSION }.map { it.number }
        assertTrue((incursion.pingsSent + 1) !in numbers)
        assertTrue((incursion.pingsSent + 2) in numbers)
    }

    @Test fun lastStandRejectsOneTagPublicly() {
        var g = active()
        val intruder = g.players.values.first()
        val defender = g.team(intruder.team.opponent).first()
        g = g.level(intruder, lv(PerkStart.LAST_STAND))
        val spot = away(intruder.team)
        val t = DAY + 10 * MINUTE
        g = engine.reportLocation(g, intruder.id, LocationFix(spot, t, 5.0)).game
        val ble = BleTokenRegistry { tok, _ -> if (tok == "k") intruder.id else null }
        val ev = photo(spot, t, listOf(BleSighting("k", t, -50)))
        val first = engine.tag(g, defender.id, intruder.id, ev, t, ble)
        assertIs<Verdict.Rejected>(first.verdict)
        assertEquals(1, first.notices.size)
        val second = engine.tag(first.game, defender.id, intruder.id, ev, t, ble)
        assertEquals(Verdict.Valid, second.verdict)
    }

    @Test fun bountyMultipliesTagValue() {
        var g = active()
        val intruder = g.players.values.first()
        val marker = g.team(intruder.team.opponent).first()
        g = g.level(marker, lv(PerkStart.BOUNTY))
        g = engine.bounty(g, marker.id, intruder.id).game
        val spot = away(intruder.team)
        val t = DAY + 10 * MINUTE
        g = engine.reportLocation(g, intruder.id, LocationFix(spot, t, 5.0)).game
        val ble = BleTokenRegistry { tok, _ -> if (tok == "k") intruder.id else null }
        val tr = engine.tag(g, marker.id, intruder.id, photo(spot, t, listOf(BleSighting("k", t, -50))), t, ble)
        val paid = tr.awards.first { it.user == marker.id }.points
        val mult = Progression.perksFor(lv(PerkStart.BOUNTY)).bountyMultiplier
        assertTrue(mult > 1.0)
        assertEquals((Progression.tagValue(1) * mult).toLong(), paid)
    }

    @Test fun tripwireOutranksThreshold() {
        var g = active()
        val intruder = g.players.values.first()
        val defender = g.team(intruder.team.opponent).first()
        g = g.level(intruder, lv(PerkStart.THRESHOLD)).level(defender, lv(PerkStart.TRIPWIRE))
        val nearFlag = g.flags.getValue(defender.team).location
        val tr = engine.reportLocation(g, intruder.id, LocationFix(nearFlag, DAY + 5, 5.0))
        val trip = tr.pings.single { it.kind == PingKind.TRIPWIRE }
        assertEquals(setOf(defender.id), trip.recipients)
    }

    @Test fun flagSenseCircleContainsTheFlag() {
        var g = active()
        val p = g.players.values.first()
        assertEquals(null, GameEngine.flagSense(g, p.id))
        g = g.level(p, lv(PerkStart.FLAG_SENSE) * 2)
        val (center, r) = assertNotNull(GameEngine.flagSense(g, p.id))
        assertTrue(center.distanceTo(g.flags.getValue(p.team.opponent).location) <= r)
        assertEquals(center, GameEngine.flagSense(g, p.id)!!.first)
    }

    @Test fun deliberateExtendsOnlyThatTeamsWindow() {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        val cap = g.players.values.first { it.role == Role.CAPTAIN }
        g = g.level(cap, lv(PerkStart.DELIBERATE))
        val other = g.team(cap.team.opponent).first { it.role == Role.CAPTAIN }
        g = engine.placeFlag(g, other.id, "V", FlagVenueKind.BUSINESS, "a", home(other.team), photo(home(other.team), DAY), DAY).game
        assertIs<GamePhase.FlagPlacement>(engine.tick(g, DAY + HOUR).game.phase)
        val late = engine.tick(g, DAY + HOUR + MINUTE).game.phase
        assertEquals(Outcome.Forfeit(cap.team, "No flag placed in time"), (late as GamePhase.Ended).outcome)
    }

    @Test fun paroleReleases() {
        var g = active()
        val p = g.players.values.first()
        g = g.level(p, lv(PerkStart.PAROLE))
        g = g.copy(players = g.players + (p.id to g.p(p.id).copy(jailedAt = DAY)))
        val ms = Progression.perksFor(g.p(p.id).level).paroleMs!!
        assertTrue(engine.tick(g, DAY + ms - 1).game.p(p.id).isJailed)
        val out = engine.tick(g, DAY + ms)
        assertTrue(!out.game.p(p.id).isJailed)
        assertEquals(1, out.notices.size)
    }

    @Test fun counterintelExposesDecoysOnlyToEqualOrHigherRank() {
        var g = active()
        val sender = g.players.values.first()
        val defenders = g.team(sender.team.opponent)
        val spot = away(sender.team)
        g = g.level(sender, lv(PerkStart.DECOY))
        g = g.level(defenders[0], lv(PerkStart.DECOY)) // outranks nobody, but equal: sees through
        g = g.level(defenders[1], lv(PerkStart.COUNTERINTEL)) // lower than sender: fooled
        defenders.take(2).forEach { d -> g = g.copy(lastFix = g.lastFix + (d.id to LocationFix(spot, DAY, 5.0))) }
        val ping = engine.decoy(g, sender.id, spot, DAY + 1).pings.single()
        assertEquals(setOf(defenders[0].id), ping.decoyRevealedTo)
    }
}
