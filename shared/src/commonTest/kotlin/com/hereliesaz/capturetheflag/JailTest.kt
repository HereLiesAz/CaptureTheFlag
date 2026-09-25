package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.model.StreamPurpose
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
import com.hereliesaz.capturetheflag.model.DevicePose
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
        PhotoEvidence("img", at, t, LocationFix(at, t, 5.0), ble, exifDirection = 0.0, pose = DevicePose(0.0, 0.0, 0.0, t))
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

    @Test fun reportWindowScalesWithDistance() = runTest {
        val jail = GeoPoint(30.0, -90.0)
        val near = JailRules.reportWindow(HeuristicTravel.travelMs(GeoPoint(30.001, -90.0), jail, 0))
        val far = JailRules.reportWindow(HeuristicTravel.travelMs(GeoPoint(30.3, -90.0), jail, 0))
        assertEquals(GameRules.JAIL_REPORT_MIN_WINDOW + GameRules.JAIL_REPORT_HOLD, near)
        assertTrue(far > near)
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
        assertIs<Verdict.Rejected>(engine.goLive(tr.game, rescuer.id, "s1", StreamPurpose.JAILBREAK, LocationFix(jail.north(60.0), t, 5.0), t).verdict)
    }

    @Test fun prisonersAreFrozen() {
        val (g, prisoner, _) = jailOne()
        val enemyFlag = g.flags.getValue(prisoner.team.opponent).location
        val enemyJail = g.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 5 * MINUTE
        assertIs<Verdict.Rejected>(engine.goLive(g, prisoner.id, "s1", StreamPurpose.CAPTURE, LocationFix(enemyFlag.north(60.0), t, 5.0), t).verdict)
        assertIs<Verdict.Rejected>(engine.goLive(g, prisoner.id, "s2", StreamPurpose.JAILBREAK, LocationFix(enemyJail.north(60.0), t, 5.0), t).verdict)
        val someone = g.team(prisoner.team.opponent).first()
        val ble = BleTokenRegistry { _, _ -> someone.id }
        assertIs<Verdict.Rejected>(engine.tag(g, prisoner.id, someone.id, photo(home(prisoner.team), t), t, ble).verdict)
    }

    @Test fun goingLiveFreesNoOne() {
        val (g, prisoner, _) = jailOne()
        val rescuer = g.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val started = engine.goLive(g, rescuer.id, "s1", StreamPurpose.JAILBREAK, LocationFix(jail.north(60.0), t, 5.0), t)
        assertEquals(Verdict.Valid, started.verdict)
        assertTrue(started.game.p(prisoner.id).isJailed)
        assertTrue(started.awards.isEmpty())
        assertTrue(started.notices.single().contains(rescuer.user.displayName), "the city is told")
        assertNotNull(started.game.streams.getValue("s1").challenge, "the challenge is shown at the start")
        // Too close: the approach has to be on camera.
        assertIs<Verdict.Rejected>(engine.goLive(g, rescuer.id, "s2", StreamPurpose.JAILBREAK, LocationFix(jail.north(10.0), t, 5.0), t).verdict)
    }

    @Test fun streamingAFifteenMinuteHoldFreesEveryoneOnceTheWindowCloses() {
        val (g0, prisoner, _) = jailOne(window = 10 * DAY)
        val rescuer = g0.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g0.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val ended = engine.stream(g0, rescuer.id, StreamPurpose.JAILBREAK, jail, t, ::photo, settle = false)
        assertEquals(Verdict.Valid, ended.verdict)
        val s = ended.game.streams.getValue("s1")
        assertNotNull(s.challenge)
        assertTrue(s.pending)
        assertTrue(ended.game.p(prisoner.id).isJailed, "not until the defenders have had their chance")
        val at = s.endedAt!! + GameRules.STREAM_CONTEST_WINDOW
        val done = engine.tick(ended.game, at)
        assertTrue(!done.game.p(prisoner.id).isJailed)
        assertNull(done.game.p(rescuer.id).breakoutSince)
        assertEquals(20L, done.awards.single { it.reason.startsWith("Freed") }.points)
        assertTrue(done.notices.any { "broke 1 out" in it })
    }

    @Test fun finishingBeforeTheChallengeIsAnsweredIsRefused() {
        val (g0, prisoner, _) = jailOne(window = 10 * DAY)
        val rescuer = g0.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g0.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val live = engine.goLive(g0, rescuer.id, "s1", StreamPurpose.JAILBREAK, LocationFix(jail.north(60.0), t, 5.0), t)
        val early = engine.endStream(live.game, rescuer.id, "s1", photo(jail, t + 1), t + 1)
        assertIs<Verdict.Rejected>(early.verdict)
    }

    @Test fun leavingTheJailVoidsTheStream() {
        val (g0, prisoner, _) = jailOne(window = 10 * DAY)
        val rescuer = g0.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g0.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val arrived = engine.stream(g0, rescuer.id, StreamPurpose.JAILBREAK, jail, t, ::photo) { it.p(rescuer.id).breakoutSince != null }
        val at = arrived.game.streams.getValue("s1").lastFrame.at + FRAME_MS
        val left = engine.streamFrame(arrived.game, rescuer.id, "s1", LocationFix(jail.north(500.0), at, 5.0), "c", at)
        assertEquals("Left the jail", left.game.streams.getValue("s1").void)
        assertNull(left.game.p(rescuer.id).breakoutSince)
        assertTrue(left.highlights.any { it.kind == com.hereliesaz.capturetheflag.model.HighlightKind.BREAKOUT_ABANDONED })
        assertTrue(engine.tick(left.game, at + GameRules.JAILBREAK_HOLD).game.p(prisoner.id).isJailed)
    }

    @Test fun aStreamThatGoesQuietIsDropped() {
        val (g0, prisoner, _) = jailOne(window = 10 * DAY)
        val rescuer = g0.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g0.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val live = engine.goLive(g0, rescuer.id, "s1", StreamPurpose.JAILBREAK, LocationFix(jail.north(60.0), t, 5.0), t)
        val quiet = engine.tick(live.game, t + GameRules.STREAM_MAX_GAP + 1)
        assertEquals("Stream dropped", quiet.game.streams.getValue("s1").void)
    }

    @Test fun jailingTheRescuerEndsTheBreakout() {
        val (g0, prisoner, jailer) = jailOne(window = 10 * DAY)
        val rescuer = g0.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g0.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val arrived = engine.stream(g0, rescuer.id, StreamPurpose.JAILBREAK, jail, t, ::photo) { it.p(rescuer.id).breakoutSince != null }
        val at = arrived.game.streams.getValue("s1").lastFrame.at + 1
        val ble = BleTokenRegistry { tok, _ -> if (tok == "r") rescuer.id else null }
        val tagged = engine.tag(arrived.game, jailer.id, rescuer.id, photo(jail, at, listOf(BleSighting("r", at, -40))), at, ble)
        assertEquals(Verdict.Valid, tagged.verdict)
        assertEquals("Caught", tagged.game.streams.getValue("s1").void, "the moment the tag lands")
        val after = engine.tick(tagged.game, at + 1)
        assertNull(after.game.p(rescuer.id).breakoutSince)
        assertTrue(after.game.p(prisoner.id).isJailed)
    }

    @Test fun aDisputeWaitsForTheRefereesAndTheirRulingDecides() {
        val (g0, prisoner, jailer) = jailOne(window = 10 * DAY)
        val rescuer = g0.team(prisoner.team).first { it.id != prisoner.id }
        val jail = g0.jails.getValue(prisoner.team.opponent).location
        val t = DAY + 30 * MINUTE
        val ended = engine.stream(g0, rescuer.id, StreamPurpose.JAILBREAK, jail, t, ::photo, settle = false)
        val end = ended.game.streams.getValue("s1").endedAt!!
        assertIs<Verdict.Rejected>(engine.dispute(ended.game, rescuer.id, "s1", "mine", end + 1).verdict, "only defenders dispute")
        val disputed = engine.dispute(ended.game, jailer.id, "s1", "Challenge not said on camera", end + MINUTE)
        assertEquals(Verdict.Valid, disputed.verdict)
        // The window closing doesn't settle a disputed stream.
        assertTrue(engine.tick(disputed.game, end + GameRules.STREAM_CONTEST_WINDOW).game.p(prisoner.id).isJailed)
        val ruledAt = end + 2 * MINUTE
        val afterAppeals = ruledAt + GameRules.STREAM_APPEAL_WINDOW
        // A ruling waits out the appeal window before it counts.
        val thrownOut = engine.rule(disputed.game, "s1", review = 0, upheld = false, now = ruledAt)
        assertTrue(thrownOut.game.streams.getValue("s1").pending)
        assertEquals("Failed review", engine.tick(thrownOut.game, afterAppeals).game.streams.getValue("s1").void)
        val upheld = engine.rule(disputed.game, "s1", review = 0, upheld = true, now = ruledAt)
        assertTrue(upheld.game.p(prisoner.id).isJailed)
        assertTrue(!engine.tick(upheld.game, afterAppeals).game.p(prisoner.id).isJailed)
        // A leader appeals: a second review, and its ruling is final at once. One appeal per team per round.
        val defenderLeader = disputed.game.team(jailer.team).first { it.isLeader }
        disputed.game.team(jailer.team).firstOrNull { !it.isLeader }?.let {
            assertIs<Verdict.Rejected>(engine.appeal(upheld.game, it.id, "s1", ruledAt + MINUTE).verdict, "only leaders appeal")
        }
        val appealed = engine.appeal(upheld.game, defenderLeader.id, "s1", ruledAt + MINUTE)
        assertEquals(Verdict.Valid, appealed.verdict)
        assertTrue(jailer.team in appealed.game.appealsUsed)
        assertIs<Verdict.Rejected>(engine.rule(appealed.game, "s1", review = 0, upheld = false, now = ruledAt + 2 * MINUTE).verdict, "a vote on the old review doesn't count")
        val final = engine.rule(appealed.game, "s1", review = 1, upheld = false, now = ruledAt + 2 * MINUTE)
        assertEquals("Failed review", final.game.streams.getValue("s1").void)
        // A review that never reports back doesn't hold the game hostage.
        val late = end + MINUTE + GameRules.STREAM_RULING_WINDOW
        val timedOut = engine.tick(disputed.game, late)
        assertTrue(!timedOut.game.p(prisoner.id).isJailed)
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
        val won = engine.stream(g, capturer.id, StreamPurpose.CAPTURE, flag, t, ::photo)
        val paid = won.awards.map { it.user }.toSet()
        assertTrue(mate.id in paid)
        assertTrue(prisoner.id !in paid)
    }
}
