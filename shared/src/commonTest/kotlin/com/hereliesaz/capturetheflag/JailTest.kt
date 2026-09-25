package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.data.HeuristicTravel
import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.model.BleSighting
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.GamePhase
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Player
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.GameRules
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import com.hereliesaz.capturetheflag.rules.JailRules
import com.hereliesaz.capturetheflag.rules.PerkStart
import com.hereliesaz.capturetheflag.rules.Progression
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JailTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private val users = (1..6).map { User("p$it", "Player $it", "s") }
    private val engine = GameEngine(Random(11))

    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun jailFor(home: GeoPoint) = GeoPoint(home.lat + if (home.lat > 30.0) 0.02 else -0.02, home.lng)
    private fun photo(at: GeoPoint, t: Long, ble: List<BleSighting> = emptyList()) =
        PhotoEvidence("img", at, t, LocationFix(at, t, 5.0), ble)
    private fun Game.p(id: String) = players.getValue(id)

    private fun placement(): Game {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        return engine.tick(g, DAY).game
    }

    private fun active(): Game {
        var g = placement()
        for (t in Team.entries) {
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "V", FlagVenueKind.PUBLIC_SPACE, "a", home(t), photo(home(t), DAY), DAY).game
            g = engine.placeJail(g, cap.id, "J", "b", jailFor(home(t)), photo(jailFor(home(t)), DAY), DAY).game
        }
        assertIs<GamePhase.Active>(g.phase)
        return g
    }

    /** Jails the first player of one team, standing on enemy ground. Returns (game, prisoner, jailer). */
    private fun jailOne(window: Long = GameRules.HOUR): Triple<Game, Player, Player> {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val spot = home(jailer.team)
        val t = DAY + MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(spot, t, 5.0)).game
        val ble = BleTokenRegistry { tok, _ -> if (tok == "k") prisoner.id else null }
        val tr = engine.tag(g, jailer.id, prisoner.id, photo(spot, t, listOf(BleSighting("k", t, -50))), t, ble, window)
        assertEquals(Verdict.Valid, tr.verdict)
        return Triple(tr.game, tr.game.p(prisoner.id), jailer)
    }

    @Test fun jailNeedsFlagFirstAndDistanceFromIt() {
        val g = placement()
        val cap = g.players.values.first { it.role == Role.CAPTAIN }
        val h = home(cap.team)
        assertIs<Verdict.Rejected>(engine.placeJail(g, cap.id, "J", "b", jailFor(h), photo(jailFor(h), DAY), DAY).verdict)
        val flagged = engine.placeFlag(g, cap.id, "V", FlagVenueKind.BUSINESS, "a", h, photo(h, DAY), DAY).game
        val tooClose = GeoPoint(h.lat + 0.001, h.lng) // ~110 m
        assertIs<Verdict.Rejected>(engine.placeJail(flagged, cap.id, "J", "b", tooClose, photo(tooClose, DAY), DAY).verdict)
        assertEquals(Verdict.Valid, engine.placeJail(flagged, cap.id, "J", "b", jailFor(h), photo(jailFor(h), DAY), DAY).verdict)
    }

    @Test fun reportWindowScalesWithDistanceAndWeather() = runTest {
        val jail = GeoPoint(30.0, -90.0)
        val near = JailRules.reportWindow(HeuristicTravel.travelMs(GeoPoint(30.001, -90.0), jail, 0))
        val far = JailRules.reportWindow(HeuristicTravel.travelMs(GeoPoint(30.3, -90.0), jail, 0))
        val snowy = JailRules.reportWindow(HeuristicTravel.travelMs(GeoPoint(30.3, -90.0), jail, 0), weatherFactor = 1.5)
        assertEquals(GameRules.JAIL_REPORT_MIN_WINDOW + GameRules.JAIL_REPORT_HOLD, near)
        assertTrue(far > near && snowy > far)
    }

    @Test fun reportingNeedsFiveUnbrokenMinutesAtTheJail() {
        var (g, prisoner, _) = jailOne()
        val jail = g.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 10 * MINUTE
        g = engine.reportLocation(g, prisoner.id, LocationFix(jail, t, 5.0)).game
        g = engine.reportLocation(g, prisoner.id, LocationFix(jail, t + 3 * MINUTE, 5.0)).game
        g = engine.reportLocation(g, prisoner.id, LocationFix(GeoPoint(jail.lat + 0.01, jail.lng), t + 4 * MINUTE, 5.0)).game
        assertNull(g.p(prisoner.id).reportingSince) // stepped away: clock reset
        g = engine.reportLocation(g, prisoner.id, LocationFix(jail, t + 5 * MINUTE, 5.0)).game
        assertNull(g.p(prisoner.id).reportedAt)
        val done = engine.reportLocation(g, prisoner.id, LocationFix(jail, t + 10 * MINUTE, 5.0))
        assertNotNull(done.game.p(prisoner.id).reportedAt)
        assertEquals(1, done.notices.size)
    }

    @Test fun missingTheDeadlineDisqualifiesAndVoidsTheRound() {
        var (g, prisoner, _) = jailOne(window = 20 * MINUTE)
        g = g.copy(earned = g.earned + (prisoner.id to 40L))
        val tr = engine.tick(g, DAY + MINUTE + 20 * MINUTE + 1)
        val p = tr.game.p(prisoner.id)
        assertTrue(p.disqualified && p.isJailed)
        assertEquals(-40L, tr.awards.single { it.user == prisoner.id }.points)
        assertEquals(0L, tr.game.earned[prisoner.id])
        // Out for the round: a jailbreak does not bring them back.
        val rescuer = tr.game.team(prisoner.team).first { it.id != prisoner.id }
        val jail = tr.game.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        assertIs<Verdict.Rejected>(engine.jailbreak(tr.game, rescuer.id, photo(jail, t), t).verdict)
    }

    @Test fun prisonersAreFrozen() {
        val (g, prisoner, _) = jailOne()
        val enemyFlag = g.flags.getValue(prisoner.team.opponent).location
        val enemyJail = g.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 5 * MINUTE
        assertIs<Verdict.Rejected>(engine.captureFlag(g, prisoner.id, photo(enemyFlag, t), t).verdict)
        assertIs<Verdict.Rejected>(engine.jailbreak(g, prisoner.id, photo(enemyJail, t), t).verdict)
        val someone = g.team(prisoner.team.opponent).first()
        val ble = BleTokenRegistry { _, _ -> someone.id }
        assertIs<Verdict.Rejected>(engine.tag(g, prisoner.id, someone.id, photo(home(prisoner.team), t), t, ble).verdict)
    }

    @Test fun jailbreakFreesEveryoneAndPaysTheRescuer() {
        val (g, prisoner, _) = jailOne()
        val rescuer = g.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val tr = engine.jailbreak(g, rescuer.id, photo(jail, t), t)
        assertEquals(Verdict.Valid, tr.verdict)
        assertTrue(!tr.game.p(prisoner.id).isJailed)
        assertEquals(20L, tr.awards.single().points)
        assertIs<Verdict.Rejected>(engine.jailbreak(tr.game, rescuer.id, photo(jail, t), t).verdict)
    }

    @Test fun paroleOnlyAfterReporting() {
        var (g, prisoner, _) = jailOne(window = 10 * DAY)
        g = g.copy(players = g.players + (prisoner.id to prisoner.copy(level = Progression.levelForPower(PerkStart.PAROLE))))
        val ms = Progression.perksFor(g.p(prisoner.id).level).paroleMs!!
        assertTrue(engine.tick(g, DAY + MINUTE + ms).game.p(prisoner.id).isJailed)
        g = g.copy(players = g.players + (prisoner.id to g.p(prisoner.id).copy(reportedAt = DAY + 30 * MINUTE)))
        assertTrue(!engine.tick(g, DAY + MINUTE + ms).game.p(prisoner.id).isJailed)
    }

    @Test fun endOfGameThawsTheReportedButNotTheDisqualified() {
        var (g, prisoner, _) = jailOne(window = 20 * MINUTE)
        val mate = g.team(prisoner.team).first { it.id != prisoner.id }
        g = g.copy(players = g.players + (mate.id to mate.copy(jailedAt = DAY, reportedAt = DAY + MINUTE)))
        g = engine.tick(g, DAY + 30 * MINUTE).game // prisoner misses the deadline
        val capturer = g.team(prisoner.team).first { !it.isJailed }
        val flag = g.flags.getValue(prisoner.team.opponent).location
        val t = DAY + 40 * MINUTE
        val won = engine.captureFlag(g, capturer.id, photo(flag, t), t)
        val paid = won.awards.map { it.user }.toSet()
        assertTrue(mate.id in paid)
        assertTrue(prisoner.id !in paid)
    }
}
