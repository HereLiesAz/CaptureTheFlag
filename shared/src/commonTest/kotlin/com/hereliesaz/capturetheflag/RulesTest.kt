package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.model.StreamPurpose
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
import com.hereliesaz.capturetheflag.model.DevicePose
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
import com.hereliesaz.capturetheflag.rules.GameView
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.ui.Alerts
import com.hereliesaz.capturetheflag.ui.fmt
import kotlin.test.assertFalse
import com.hereliesaz.capturetheflag.model.PingKind
import com.hereliesaz.capturetheflag.chat.ChatAccess
import com.hereliesaz.capturetheflag.chat.Channel
import com.hereliesaz.capturetheflag.rules.GameRules
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
        PhotoEvidence("img", at, t, LocationFix(at, t, 5.0), ble, exifDirection = 0.0, pose = DevicePose(0.0, 0.0, 0.0, t))

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
        // (Standing at their flag, they're also asked to go live: that one's for them alone.)
        val incursion = tr.pings.filter { it.kind == PingKind.INCURSION }
        assertEquals(1, incursion.size)
        assertNull(incursion.single().identified)
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

    @Test fun nearTheFlagTheAppAsksYouToGoLiveAndLateIsTooLate() {
        val g = activeGame()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val t = DAY + 20 * MINUTE
        // Walking in unstreamed: the app asks, privately, from the player's own position, never the flag's.
        val near = flag.north(300.0)
        val asked = engine.reportLocation(g, p.id, LocationFix(near, t, 5.0))
        val ping = asked.pings.single { it.kind == PingKind.GO_LIVE }
        assertEquals(setOf(p.id), ping.recipients)
        assertEquals(near, ping.location)
        // Going live two minutes later is too late: the capture won't count.
        val late = t + 2 * MINUTE
        var tr = engine.goLive(asked.game, p.id, "s1", StreamPurpose.CAPTURE, LocationFix(near, late, 5.0), late)
        tr += engine.streamFrame(tr.game, p.id, "s1", LocationFix(flag, late + FRAME_MS, 5.0), "c", late + FRAME_MS)
        val refused = engine.endStream(tr.game, p.id, "s1", photo(flag, late + FRAME_MS), late + FRAME_MS)
        assertTrue((refused.verdict as Verdict.Rejected).reason.startsWith("You went live too late"))
    }

    @Test fun eachRingAroundTheFlagIsAnnouncedToEveryoneOnce() {
        val g = activeGame()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val defenders = g.team(p.team.opponent).map { it.id }.toSet()
        var game = g
        val heard = mutableListOf<Double>()
        var t = DAY + 20 * MINUTE
        // Walk in along a line: 800 m, 600, 400, 150, 40, then back out to 300.
        for (d in listOf(800.0, 600.0, 400.0, 150.0, 40.0, 300.0)) {
            t += MINUTE
            val tr = engine.reportLocation(game, p.id, LocationFix(flag.north(d), t, 5.0))
            game = tr.game
            tr.pings.filter { it.kind == PingKind.FLAG_THREAT }.forEach { threat ->
                assertEquals(defenders, threat.recipients); assertEquals(p.id, threat.identified?.id); heard += threat.radiusM
            }
            if (tr.pings.any { it.kind == PingKind.FLAG_THREAT }) assertTrue(tr.notices.any { "within" in it && p.user.displayName in it }, "the city hears it too")
        }
        assertEquals(listOf(1000.0, 500.0, 200.0, 50.0), heard, "each ring once, tightest reached; backing off says nothing")
    }

    @Test fun gettingCaughtEndsTheStreamOnTheSpot() {
        val g = activeGame()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val t = DAY + 20 * MINUTE
        val live = engine.stream(g, p.id, StreamPurpose.CAPTURE, flag, t, ::photo) { it.streams.getValue("s1").lastFrame.point == flag }
        val defender = g.team(p.team.opponent).first()
        val at = live.game.streams.getValue("s1").lastFrame.at + 1
        val ble = BleTokenRegistry { tok, _ -> if (tok == "t") p.id else null }
        val tagged = engine.tag(live.game, defender.id, p.id, photo(flag, at, listOf(BleSighting("t", at, -40))), at, ble)
        assertEquals(Verdict.Valid, tagged.verdict)
        assertEquals("Caught", tagged.game.streams.getValue("s1").void)
    }

    @Test fun sneakingHomeDarkIsAJailing() {
        val g = activeGame()
        val p = g.players.values.first()
        val away = g.flags.getValue(p.team.opponent).location.north(100.0)
        val home = g.flags.getValue(p.team).location
        val t = DAY + 20 * MINUTE
        val over = engine.reportLocation(g, p.id, LocationFix(away, t, 5.0)).game
        // Silence alone isn't a crime: they can't be seen, but they can't get home either.
        assertTrue(!engine.tick(over, t + HOUR).game.players.getValue(p.id).isJailed)
        // A short gap across the line is just signal.
        assertTrue(!engine.reportLocation(over, p.id, LocationFix(home, t + GameRules.DARK_GAP - 1, 5.0)).game.players.getValue(p.id).isJailed)
        // A long one is sneaking home: jailed where they were last seen.
        val snuck = engine.reportLocation(over, p.id, LocationFix(home, t + GameRules.DARK_GAP, 5.0))
        assertTrue(snuck.game.players.getValue(p.id).isJailed)
        assertTrue(snuck.awards.any { it.user == p.id && it.reason == "Jailed" })
        assertTrue(snuck.notices.any { "went dark" in it })
        // Switching location off says so: then even a quick crossing jails.
        val off = engine.locationOff(over, p.id, t + 1).game
        assertTrue(!off.players.getValue(p.id).isJailed)
        assertTrue(engine.reportLocation(off, p.id, LocationFix(home, t + 30_000, 5.0)).game.players.getValue(p.id).isJailed)
        // Seen again on enemy ground first, and the slate's clean.
        val seen = engine.reportLocation(off, p.id, LocationFix(away, t + 20_000, 5.0)).game
        assertTrue(!engine.reportLocation(seen, p.id, LocationFix(home, t + 30_000, 5.0)).game.players.getValue(p.id).isJailed)
    }

    @Test fun losingLocationOnEnemyGroundSaysWhereAndWhatHappens() {
        val g = activeGame()
        val p = g.players.values.first()
        val away = g.flags.getValue(p.team.opponent).location.north(100.0)
        val t = DAY + 20 * MINUTE
        val over = engine.reportLocation(g, p.id, LocationFix(away, t, 5.0)).game
        assertNull(Alerts.locationLost(over, p.id, t, true, t + GameRules.LOCATION_LOST_WARNING - 1), "a fix 29 s old is fine")
        val (title, body) = Alerts.locationLost(over, p.id, t, true, t + GameRules.LOCATION_LOST_WARNING)!!
        assertEquals("Location lost", title)
        assertTrue("jailed where you were last seen" in body && "%.5f".fmt(away.lat) in body, body)
        assertNotNull(Alerts.locationLost(over, p.id, t, false, t + 1), "switched off: at once")
        val home = engine.reportLocation(g, p.id, LocationFix(g.flags.getValue(p.team).location, t, 5.0)).game
        assertNull(Alerts.locationLost(home, p.id, t, false, t + HOUR), "at home it doesn't matter")
    }

    @Test fun eachPlayerSeesOnlyTheirSlice() {
        val g = activeGame()
        val p = g.players.values.first()
        val foe = g.team(p.team.opponent).first()
        val enemyFlag = g.flags.getValue(p.team.opponent).location
        val t = DAY + 20 * MINUTE
        var tr = engine.reportLocation(g, foe.id, LocationFix(g.flags.getValue(foe.team).location, t, 5.0))
        tr = engine.reportLocation(tr.game, p.id, LocationFix(enemyFlag.north(60.0), t, 5.0))
        tr += engine.goLive(tr.game, p.id, "s1", StreamPurpose.CAPTURE, LocationFix(enemyFlag.north(60.0), t, 5.0), t)
        val mine = GameView.of(tr.game, p.id)
        assertEquals(setOf(p.team), mine.flags.keys, "never the enemy flag")
        assertEquals(setOf(p.id), mine.lastFix.keys, "never anyone else's position")
        assertTrue(mine.territory.cells.isEmpty())
        assertTrue(mine.streams.getValue("s1").target.distanceTo(enemyFlag) > 50, "a flag run's target isn't the flag, for the attacker")
        assertEquals(enemyFlag, GameView.of(tr.game, foe.id).streams.getValue("s1").target, "the defenders know their own flag")
        val onlooker = GameView.of(tr.game, null)
        assertTrue(onlooker.flags.isEmpty() && onlooker.lastFix.isEmpty() && onlooker.incursions.isEmpty())
        assertEquals(2, onlooker.jails.size)
    }

    @Test fun onEnemyGroundOrInJailYouReCutOffFromYourTeam() {
        val g = activeGame()
        val p = g.players.values.first()
        val mate = g.team(p.team).first { it.id != p.id }
        val team = Channel.TeamRoom(g.id, p.team)
        val city = Channel.City(g.city.id)
        val dm = Channel.Direct.of(g.id, p.id, mate.id)
        val home = g.flags.getValue(p.team).location
        val away = g.flags.getValue(p.team.opponent).location
        val atHome = engine.reportLocation(g, p.id, LocationFix(home, DAY + 20 * MINUTE, 5.0)).game
        assertTrue(ChatAccess.canPost(p.id, team, atHome) && ChatAccess.canRead(p.id, dm, atHome))
        val over = engine.reportLocation(atHome, p.id, LocationFix(away, DAY + 21 * MINUTE, 5.0)).game
        assertTrue(ChatAccess.blackedOut(p.id, over))
        assertFalse(ChatAccess.canPost(p.id, team, over) || ChatAccess.canPost(p.id, dm, over) || ChatAccess.canPost(p.id, city, over), "nothing gets out")
        assertFalse(ChatAccess.canRead(p.id, team, over) || ChatAccess.canRead(p.id, dm, over), "and nothing gets in from the team")
        assertTrue(ChatAccess.canRead(p.id, city, over))
        val jailed = over.copy(players = over.players + (p.id to over.players.getValue(p.id).copy(jailedAt = DAY)))
        val back = engine.reportLocation(jailed, p.id, LocationFix(home, DAY + 22 * MINUTE, 5.0)).game
        assertTrue(ChatAccess.blackedOut(p.id, back), "jailed is cut off, wherever they stand")
    }

    @Test fun aCaptureInReviewHoldsTheFinalWhistle() {
        val g = activeGame()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val deadline = (g.phase as GamePhase.Active).deadline
        val ended = engine.stream(g, p.id, StreamPurpose.CAPTURE, flag, deadline - 5 * MINUTE, ::photo, settle = false)
        assertEquals(Verdict.Valid, ended.verdict)
        val defender = g.team(p.team.opponent).first()
        val disputed = engine.dispute(ended.game, defender.id, "s1", "Doesn't show what was registered", deadline - MINUTE)
        val pastTime = engine.tick(disputed.game, deadline + MINUTE)
        assertIs<GamePhase.Active>(pastTime.game.phase, "no tie while a capture is under review")
        val afterAppeals = deadline + 2 * MINUTE + GameRules.STREAM_APPEAL_WINDOW
        val upheld = engine.rule(pastTime.game, "s1", review = 0, upheld = true, now = deadline + 2 * MINUTE)
        assertIs<GamePhase.Active>(engine.tick(upheld.game, deadline + 3 * MINUTE).game.phase, "still open to appeal")
        assertEquals(Outcome.FlagCaptured(p.team, p.id), (engine.tick(upheld.game, afterAppeals).game.phase as GamePhase.Ended).outcome)
        val thrownOut = engine.rule(pastTime.game, "s1", review = 0, upheld = false, now = deadline + 2 * MINUTE)
        assertEquals(Outcome.Tie, (engine.tick(thrownOut.game, afterAppeals).game.phase as GamePhase.Ended).outcome)
    }

    @Test fun doctoredExifRejected() {
        val g = activeGame()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent)
        val t = DAY + 20 * MINUTE
        val forged = engine.stream(g, p.id, StreamPurpose.CAPTURE, flag.location, t, { _, at -> PhotoEvidence("img", flag.location, at, LocationFix(GeoPoint(29.95, -90.19), at, 5.0)) })
        assertIs<Verdict.Rejected>(forged.verdict)
        val real = engine.stream(g, p.id, StreamPurpose.CAPTURE, flag.location, t, ::photo)
        assertEquals(Outcome.FlagCaptured(p.team, p.id), (real.game.phase as GamePhase.Ended).outcome)
        val pts = real.awards.filterNot { it.reason.startsWith("MVP") || it.reason.startsWith("Most") }.groupBy { it.user }.mapValues { (_, v) -> v.sumOf { it.points } }
        // Capture (100) plus the team win (25); a leader takes their fixed wage instead of the win.
        val wage = when (p.role) { Role.CAPTAIN -> 300L; Role.CO_CAPTAIN -> 100L; Role.PLAYER -> 25L }
        assertEquals(100L + wage, pts[p.id])
        // The losers earn nothing from the ending, except their leaders' fixed wage.
        assertTrue(g.team(p.team.opponent).filterNot { it.isLeader }.none { it.id in pts })
        assertTrue(g.team(p.team.opponent).filter { it.role == Role.CAPTAIN }.all { pts[it.id] == 300L })
    }

    @Test fun playWindowWithoutCaptureIsATie() {
        val g = activeGame()
        val deadline = (g.phase as GamePhase.Active).deadline
        assertEquals(Outcome.Tie, (engine.tick(g, deadline).game.phase as GamePhase.Ended).outcome)
    }
}
