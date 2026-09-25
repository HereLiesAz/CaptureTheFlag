package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.engine.GameEngine
import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.geo.bearingTo
import com.hereliesaz.capturetheflag.geo.headingDelta
import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.model.DevicePose
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.Role
import com.hereliesaz.capturetheflag.model.Team
import com.hereliesaz.capturetheflag.model.Territory
import com.hereliesaz.capturetheflag.model.User
import com.hereliesaz.capturetheflag.rules.GameRules.DAY
import com.hereliesaz.capturetheflag.rules.Verdict
import com.hereliesaz.capturetheflag.rules.Verification
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhotoTrustTest {
    private val city = City("nola", "New Orleans", Polygon(listOf(
        GeoPoint(29.9, -90.2), GeoPoint(29.9, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.2),
    )))
    private val territory = Territory(city, DividingLine(GeoPoint(30.0, -90.1), 90.0))
    private val engine = GameEngine(Random(2))
    private val north = GeoPoint(30.05, -90.1)
    private val south = GeoPoint(29.95, -90.1)
    private fun home(t: Team) = if (territory.ownerOf(north) == t) north else south
    private fun upright(at: GeoPoint, t: Long) = PhotoEvidence("i", at, t, LocationFix(at, t, 5.0), exifDirection = 0.0, pose = DevicePose(0.0, 0.0, 0.0, t))

    private fun active(): Game {
        var g = engine.newRound("g", city, territory, 0)
        (1..4).forEach { g = engine.join(g, User("p$it", "N$it", "s")).game }
        g = engine.tick(g, DAY).game
        for (t in Team.entries) {
            val h = home(t)
            val j = GeoPoint(h.lat + if (h.lat > 30) 0.02 else -0.02, h.lng)
            val cap = g.team(t).first { it.role == Role.CAPTAIN }
            g = engine.placeFlag(g, cap.id, "F", FlagVenueKind.BUSINESS, "a", h, upright(h, DAY), DAY).game
            g = engine.placeJail(g, cap.id, "J", "b", j, upright(j, DAY), DAY).game
        }
        return g
    }

    /** A capture photo from ~30 m south of the enemy flag, with the given pose and claimed facing. */
    private fun shot(flag: GeoPoint, t: Long, heading: Double, pitch: Double = 5.0, claimed: Double = heading, poseAt: Long = t): PhotoEvidence {
        val at = GeoPoint(flag.lat - 0.00027, flag.lng)
        return PhotoEvidence("i", at, t, LocationFix(at, t, 5.0), exifDirection = claimed, pose = DevicePose(heading, pitch, 0.0, poseAt))
    }

    @Test fun bearingsAndDeltas() {
        assertEquals(0.0, GeoPoint(30.0, -90.0).bearingTo(GeoPoint(30.1, -90.0)), 0.01)
        assertEquals(90.0, GeoPoint(30.0, -90.0).bearingTo(GeoPoint(30.0, -89.9)), 0.1)
        assertEquals(20.0, headingDelta(350.0, 10.0))
    }

    @Test fun facingTheFlagIsAccepted() {
        val g = active()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val t = DAY + 60_000
        assertEquals(Verdict.Valid, Verification.flagCapture(g, p.id, shot(flag, t, heading = 3.0), t))
    }

    @Test fun eachSensorLieIsCaught() {
        val g = active()
        val p = g.players.values.first()
        val flag = g.flags.getValue(p.team.opponent).location
        val t = DAY + 60_000
        fun reason(e: PhotoEvidence) = (Verification.flagCapture(g, p.id, e, t) as Verdict.Rejected).reason
        assertEquals("Camera wasn't pointed at what the leader registered", reason(shot(flag, t, heading = 180.0)))
        assertEquals("Phone wasn't held like a camera", reason(shot(flag, t, heading = 0.0, pitch = -85.0)))
        assertEquals("Photo's facing disagrees with the phone's sensors", reason(shot(flag, t, heading = 0.0, claimed = 90.0)))
        assertEquals("Sensor reading does not match photo time", reason(shot(flag, t, heading = 0.0, poseAt = t - 10_000)))
        assertEquals("No motion sensor reading at capture", reason(shot(flag, t, 0.0).copy(pose = null)))
        assertEquals("Photo does not record which way it faced", reason(shot(flag, t, 0.0).copy(exifDirection = null)))
    }

    @Test fun withoutAReferencePoseRightOnTopOfItDirectionIsNotJudged() {
        val g0 = active()
        val p = g0.players.values.first()
        val enemy = p.team.opponent
        val bare = g0.flags.getValue(enemy).let { it.copy(photo = it.photo.copy(pose = null)) }
        val g = g0.copy(flags = g0.flags + (enemy to bare))
        val flag = g.flags.getValue(p.team.opponent).location
        val t = DAY + 60_000
        val e = PhotoEvidence("i", flag, t, LocationFix(flag, t, 5.0), exifDirection = 200.0, pose = DevicePose(200.0, 0.0, 0.0, t))
        assertEquals(Verdict.Valid, Verification.flagCapture(g, p.id, e, t))
    }

    @Test fun tagsMustFaceThePlayer() {
        var g = active()
        val prisoner = g.players.values.first()
        val jailer = g.team(prisoner.team.opponent).first()
        val t = DAY + 60_000
        val spot = home(jailer.team)
        g = engine.reportLocation(g, prisoner.id, LocationFix(spot, t, 5.0)).game
        val from = GeoPoint(spot.lat, spot.lng - 0.0004) // ~38 m west
        val away = PhotoEvidence("i", from, t, LocationFix(from, t, 5.0), exifDirection = 270.0, pose = DevicePose(270.0, 0.0, 0.0, t))
        val r = engine.tag(g, jailer.id, prisoner.id, away, t, { _, _ -> prisoner.id })
        assertIs<Verdict.Rejected>(r.verdict)
        assertTrue("pointed" in (r.verdict as Verdict.Rejected).reason)
    }

    // --- Against the leader's registration photo ---------------------------------------------

    /** A game whose enemy flag was registered by a leader standing at [at] facing [heading]. */
    private fun withReference(at: GeoPoint, heading: Double): Pair<Game, String> {
        val g = active()
        val p = g.players.values.first()
        val t = p.team.opponent
        val ref = PhotoEvidence("ref", at, DAY, LocationFix(at, DAY, 5.0), exifDirection = heading, pose = DevicePose(heading, 0.0, 0.0, DAY))
        val flag = g.flags.getValue(t).copy(location = at, photo = ref)
        return g.copy(flags = g.flags + (t to flag)) to p.id
    }

    private fun m(dNorth: Double, dEast: Double, from: GeoPoint) =
        GeoPoint(from.lat + dNorth / 111_320.0, from.lng + dEast / (111_320.0 * kotlin.math.cos(from.lat * kotlin.math.PI / 180)))

    private fun capture(g: Game, by: String, at: GeoPoint, heading: Double, t: Long = DAY + 60_000, visual: Double? = null) =
        Verification.flagCapture(g, by, PhotoEvidence("c", at, t, LocationFix(at, t, 5.0), exifDirection = heading, pose = DevicePose(heading, 0.0, 0.0, t)), t, visual)

    @Test fun raysThatMeetAtTheObjectPass() {
        // Leader stood at L facing north; the statue is ~20 m north of L.
        val l = GeoPoint(30.05, -90.1)
        val (g, by) = withReference(l, 0.0)
        val statue = m(20.0, 0.0, l)
        // Capturer 25 m east of the statue, looking west at it.
        val from = m(20.0, 25.0, l)
        val facingStatue = from.bearingTo(statue)
        assertEquals(Verdict.Valid, capture(g, by, from, facingStatue))
    }

    @Test fun raysThatMissAreRejected() {
        val l = GeoPoint(30.05, -90.1)
        val (g, by) = withReference(l, 0.0)
        // Same spot, looking east instead of west: the rays never meet in front of both.
        val from = m(20.0, 25.0, l)
        val r = capture(g, by, from, 90.0) as Verdict.Rejected
        assertEquals("Camera wasn't pointed at what the leader registered", r.reason)
    }

    @Test fun fromTheLeadersSpotYouMustFaceTheSameWay() {
        val l = GeoPoint(30.05, -90.1)
        val (g, by) = withReference(l, 120.0)
        assertEquals(Verdict.Valid, capture(g, by, m(3.0, 3.0, l), 130.0))
        assertIs<Verdict.Rejected>(capture(g, by, m(3.0, 3.0, l), 300.0))
    }

    @Test fun aLowVisualScoreFailsEvenWithPerfectSensors() {
        val l = GeoPoint(30.05, -90.1)
        val (g, by) = withReference(l, 0.0)
        val r = capture(g, by, m(3.0, 0.0, l), 0.0, visual = 0.1) as Verdict.Rejected
        assertEquals("Photo doesn't show what the leader registered", r.reason)
        assertEquals(Verdict.Valid, capture(g, by, m(3.0, 0.0, l), 0.0, visual = 0.8))
    }
}
