package com.hereliesaz.capturetheflag

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
import com.hereliesaz.capturetheflag.model.Outcome
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.BleTokenRegistry
import com.hereliesaz.capturetheflag.rules.CityCell
import com.hereliesaz.capturetheflag.rules.CityPartitioner
import com.hereliesaz.capturetheflag.rules.GameRules.HOUR
import com.hereliesaz.capturetheflag.rules.GameRules.MINUTE
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.PingSchedule
import com.hereliesaz.capturetheflag.rules.TeamAssignment
import com.hereliesaz.capturetheflag.rules.Verdict
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
    /** A jail 2 km further from the line than [home], so it stays in the same territory. */
    private fun jailFor(home: GeoPoint) = GeoPoint(home.lat + if (home.lat > 30.0) 0.02 else -0.02, home.lng)

    // A square city split north/south along latitude 30.0: bearing 90° runs east.
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private fun owner(p: GeoPoint) = territory.ownerOf(p)!!

    private val users = (1..6).map { User("p$it", "Player $it", "selfie://$it") }

    @Test fun pingScheduleEscalates() {
        val due = (1..8).map { PingSchedule.dueAt(0, it) / MINUTE }
        assertEquals(listOf(0L, 60, 90, 105, 115, 120, 125, 130), due)
        assertTrue(!PingSchedule.identifies(5) && PingSchedule.identifies(6))
    }

    @Test fun recipientsAreAThirdRoundedUp() {
        val r = PingSchedule.recipients((1..10).map { "e$it" }, Random(1))
        assertEquals(4, r.size)
        assertEquals(1, PingSchedule.recipients(listOf("x"), Random(1)).size)
    }

    @Test fun teamsBalancedWithOneCaptainEach() {
        val players = TeamAssignment.assign(users + User("p7", "Seven", "s"), Random(3))
        val sizes = Team.entries.map { t -> players.values.count { it.team == t } }
        assertTrue(kotlin.math.abs(sizes[0] - sizes[1]) <= 1)
        Team.entries.forEach { t -> assertEquals(1, players.values.count { it.team == t && it.role == Role.CAPTAIN }) }
    }

    @Test fun captainAppointsAtMostTwoCoCaptains() {
        val players = TeamAssignment.assign(users, Random(5))
        val cap = players.values.first { it.role == Role.CAPTAIN }
        val mates = players.values.filter { it.team == cap.team && it.id != cap.id }.map { it.id }
        assertNotNull(TeamAssignment.appointCoCaptains(players, cap.id, mates.take(2).toSet()))
        val foe = players.values.first { it.team != cap.team }.id
        assertNull(TeamAssignment.appointCoCaptains(players, cap.id, setOf(foe)))
        assertNull(TeamAssignment.appointCoCaptains(players, mates.first(), emptySet()))
    }

    @Test fun partitionerFindsTheBalancedCut() {
        // Population all on a vertical strip: a north/south cut should balance it best.
        val cells = (0 until 10).flatMap { i -> (0 until 10).map { j ->
            CityCell(GeoPoint(29.9 + i * 0.02, -90.2 + j * 0.02), if (j == 5) 100.0 else 1.0, 1.0, 1.0, 0.0)
        } }
        val best = CityPartitioner(topK = 1).partition(cells, Random(0))
        assertTrue(best.breakdown.getValue("population") < 0.15, "population imbalance ${best.breakdown}")
    }

    // --- Engine, end to end ---

    private val engine = GameEngine(Random(42))

    private fun photo(at: GeoPoint, t: Long, ble: List<BleSighting> = emptyList()) =
        PhotoEvidence("img", at, t, LocationFix(at, t, 5.0), ble)

    private fun activeGame(): Game {
        var g = engine.newRound("g1", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        assertIs<GamePhase.FlagPlacement>(g.phase)
        for (t in Team.entries) {
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            val home = if (owner(north) == t) north else south
            val tr = engine.placeFlag(g, cap.id, "Venue", FlagVenueKind.PUBLIC_SPACE, "1 St", home, photo(home, DAY + 1), DAY + 1)
            assertEquals(Verdict.Valid, tr.verdict)
            g = tr.game
            val j = jailFor(home)
            val jt = engine.placeJail(g, cap.id, "Jail", "2 St", j, photo(j, DAY + 1), DAY + 1)
            assertEquals(Verdict.Valid, jt.verdict)
            g = jt.game
        }
        assertIs<GamePhase.Active>(g.phase)
        return g
    }

    @Test fun signupWithTooFewPlayersCancels() {
        var g = engine.newRound("g0", city, territory, 0)
        g = engine.join(g, users[0]).game
        assertEquals(Outcome.Cancelled, (engine.tick(g, DAY).game.phase as GamePhase.Ended).outcome)
    }

    @Test fun flagOutsideOwnTerritoryRejected() {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        val cap = g.players.values.first { it.role == Role.CAPTAIN }
        val enemy = if (owner(north) == cap.team) south else north
        val tr = engine.placeFlag(g, cap.id, "V", FlagVenueKind.BUSINESS, "a", enemy, photo(enemy, DAY), DAY)
        assertIs<Verdict.Rejected>(tr.verdict)
    }

    @Test fun missingFlagForfeits() {
        var g = engine.newRound("g", city, territory, 0)
        users.forEach { g = engine.join(g, it).game }
        g = engine.tick(g, DAY).game
        val cap = g.players.values.first { it.role == Role.CAPTAIN }
        val home = if (owner(north) == cap.team) north else south
        g = engine.placeFlag(g, cap.id, "V", FlagVenueKind.BUSINESS, "a", home, photo(home, DAY), DAY).game
        g = engine.placeJail(g, cap.id, "J", "b", jailFor(home), photo(jailFor(home), DAY), DAY).game
        val out = (engine.tick(g, DAY + HOUR).game.phase as GamePhase.Ended).outcome
        assertEquals(Outcome.Forfeit(cap.team.opponent, "No flag and jail placed in time"), out)
    }

    @Test fun incursionPingsEscalateAndIdentify() {
        var g = activeGame()
        val start = DAY + 2
        val p = g.players.values.first()
        val enemyGround = if (owner(north) == p.team) south else north
        var tr = engine.reportLocation(g, p.id, LocationFix(enemyGround, start, 5.0))
        assertEquals(1, tr.pings.size)
        assertNull(tr.pings.single().identified)
        g = tr.game
        tr = engine.tick(g, start + 124 * MINUTE)
        assertEquals((2..6).toList(), tr.pings.map { it.number })
        assertNotNull(tr.pings.last().identified)
        tr.pings.forEach { ping -> assertTrue(ping.recipients.all { g.players.getValue(it).team != p.team }) }
        // Going home ends the incursion.
        val home = if (enemyGround == north) south else north
        g = engine.reportLocation(tr.game, p.id, LocationFix(home, start + 126 * MINUTE, 5.0)).game
        assertTrue(p.id !in g.incursions)
    }

    @Test fun tagRequiresBleAndProximity() {
        var g = activeGame()
        val intruder = g.players.values.first()
        val defender = g.team(intruder.team.opponent).first()
        val spot = if (owner(north) == defender.team) north else south
        val t = DAY + 10 * MINUTE
        g = engine.reportLocation(g, intruder.id, LocationFix(spot, t, 5.0)).game
        val ble = BleTokenRegistry { token, _ -> if (token == "tok") intruder.id else null }

        val noBle = engine.tag(g, defender.id, intruder.id, photo(spot, t), t, ble)
        assertIs<Verdict.Rejected>(noBle.verdict)

        val ok = engine.tag(g, defender.id, intruder.id, photo(spot, t, listOf(BleSighting("tok", t, -60))), t, ble)
        assertEquals(Verdict.Valid, ok.verdict)
        assertTrue(ok.game.players.getValue(intruder.id).isJailed)
        assertEquals(listOf(defender.id to 12L, intruder.id to -5L), ok.awards.map { it.user to it.points })
        // Released and re-jailed by the same defender: no second payout.
        val again = engine.release(ok.game, intruder.id).game
        val twice = engine.tag(again, defender.id, intruder.id, photo(spot, t, listOf(BleSighting("tok", t, -60))), t, ble)
        assertEquals(Verdict.Valid, twice.verdict)
        assertTrue(twice.awards.isEmpty())
    }

    @Test fun doctoredExifRejected() {
        val g = activeGame()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent)
        val t = DAY + 20 * MINUTE
        val forged = PhotoEvidence("img", flag.location, t, LocationFix(GeoPoint(29.95, -90.19), t, 5.0))
        assertIs<Verdict.Rejected>(engine.captureFlag(g, p.id, forged, t).verdict)
        val real = engine.captureFlag(g, p.id, photo(flag.location, t), t)
        assertEquals(Outcome.FlagCaptured(p.team, p.id), (real.game.phase as GamePhase.Ended).outcome)
        val pts = real.awards.groupBy { it.user }.mapValues { (_, v) -> v.sumOf { it.points } }
        assertEquals(125L + if (p.isLeader) 15 else 0, pts[p.id])
        assertTrue(g.team(p.team.opponent).none { it.id in pts })
    }

    @Test fun sevenDaysWithoutCaptureIsATie() {
        val g = activeGame()
        val deadline = (g.phase as GamePhase.Active).deadline
        assertEquals(Outcome.Tie, (engine.tick(g, deadline).game.phase as GamePhase.Ended).outcome)
    }
}
