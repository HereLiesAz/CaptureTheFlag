package com.hereliesaz.capturetheflag.model

import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.rules.CityCell

typealias PlayerId = String
typealias GameId = String
typealias CityId = String

/** Epoch milliseconds. Kept as Long so the domain has no clock/library dependency. */
typealias Millis = Long

enum class Team {
    /** Side where [DividingLine.sideOf] == +1. */
    NOIR,
    /** Side where [DividingLine.sideOf] == -1. */
    BLANC;

    val opponent: Team get() = if (this == NOIR) BLANC else NOIR
    val lineSide: Int get() = if (this == NOIR) 1 else -1
}

/** A metro area as requested by the initiator. */
data class City(
    val id: CityId,
    val name: String,
    val boundary: Polygon,
    /** Local offset from UTC, for time-of-day perks. */
    val utcOffsetMinutes: Int = 0,
)

/** The city split in two. Everything inside [City.boundary] belongs to exactly one team. */
data class Territory(
    val city: City,
    val line: DividingLine,
    /** Statistical grid the split was computed from; used for population-density perks. */
    val cells: List<CityCell> = emptyList(),
) {
    /** Team owning [p], or null if outside the city. */
    fun ownerOf(p: GeoPoint): Team? = when {
        p !in city.boundary -> null
        line.sideOf(p) == Team.NOIR.lineSide -> Team.NOIR
        else -> Team.BLANC
    }

    /** Population of the cell nearest [p] relative to the city mean. 1.0 when unknown. */
    fun densityRatio(p: GeoPoint): Double {
        if (cells.isEmpty()) return 1.0
        val mean = cells.sumOf { it.population } / cells.size
        if (mean <= 0) return 1.0
        return cells.minBy { it.center.distanceTo(p) }.population / mean
    }
}

/** An app-registered user. Onlookers are users who never join a game. */
data class User(val id: PlayerId, val displayName: String, val selfieUrl: String?)

enum class Role { PLAYER, CAPTAIN, CO_CAPTAIN }

data class Player(
    val user: User,
    val team: Team,
    val role: Role = Role.PLAYER,
    val jailedAt: Millis? = null,
    /** Must finish reporting to the enemy jail by this time, or be disqualified. */
    val jailDeadline: Millis? = null,
    /** Continuous presence at the jail began here; reset on leaving. */
    val reportingSince: Millis? = null,
    /** Report completed. Only reported prisoners can be paroled; all can be broken out. */
    val reportedAt: Millis? = null,
    /** Failed to report: out for the round, no points from it. Stays jailed. */
    val disqualified: Boolean = false,
    /** A jailbreak in progress: its live stream has been at the enemy jail, unbroken, since this time. */
    val breakoutSince: Millis? = null,
    /** Level snapshotted when teams are dealt; fixed for the round. */
    val level: Int = 1,
    /** Had never finished a round when teams were dealt. Rookies don't lead unless everyone is one. */
    val rookie: Boolean = false,
) {
    val id: PlayerId get() = user.id
    val isLeader: Boolean get() = role != Role.PLAYER
    val isJailed: Boolean get() = jailedAt != null
}

/** Position report from a device. [accuracyM] is the reported horizontal accuracy. */
data class LocationFix(val point: GeoPoint, val at: Millis, val accuracyM: Double)

/**
 * A photo plus everything used to judge it.
 * [exifLocation]/[exifTakenAt] come from the image; [deviceFix] is the live fused fix
 * sampled by the app at shutter time; [bleSightings] are rotating BLE tokens heard around then.
 */
data class PhotoEvidence(
    val imageUri: String,
    val exifLocation: GeoPoint?,
    val exifTakenAt: Millis?,
    val deviceFix: LocationFix?,
    val bleSightings: List<BleSighting> = emptyList(),
    /** The facing the photo claims in its EXIF (GPSImgDirection), degrees from true north. */
    val exifDirection: Double? = null,
    /** Where the phone was actually pointing at the shutter, from its motion sensors. */
    val pose: DevicePose? = null,
)

/**
 * The phone's orientation at the shutter, read from the rotation-vector sensor.
 * [azimuthDeg] is where the back camera points, degrees from true north. [pitchDeg] is the
 * camera axis above (+) or below (−) the horizon. [rollDeg] is rotation about that axis.
 */
data class DevicePose(val azimuthDeg: Double, val pitchDeg: Double, val rollDeg: Double, val at: Millis)

/** A rotating token heard over BLE. Tokens map to players server-side only. */
data class BleSighting(val token: String, val at: Millis, val rssi: Int)

enum class FlagVenueKind { PUBLIC_SPACE, PUBLIC_BUILDING, BUSINESS }

/** Where a team's prisoners must report. Public to both teams. */
data class Jail(
    val team: Team,
    val venueName: String,
    val address: String,
    val location: GeoPoint,
    val photo: PhotoEvidence,
    val placedBy: PlayerId,
    val placedAt: Millis,
)

data class Flag(
    val team: Team,
    val venueName: String,
    val venueKind: FlagVenueKind,
    val address: String,
    val location: GeoPoint,
    val photo: PhotoEvidence,
    val placedBy: PlayerId,
    val placedAt: Millis,
)

sealed interface GamePhase {
    /** Accepting sign-ups until [deadline]. */
    data class Signup(val deadline: Millis) : GamePhase
    /** Leaders choose and register a flag until [deadline]. */
    data class FlagPlacement(val deadline: Millis) : GamePhase
    /** Live play until [deadline], after which a tie is called. */
    data class Active(val deadline: Millis) : GamePhase
    data class Ended(val outcome: Outcome, val at: Millis) : GamePhase
}

sealed interface Outcome {
    data class FlagCaptured(val winner: Team, val by: PlayerId) : Outcome
    data class Forfeit(val loser: Team, val reason: String) : Outcome
    data object Tie : Outcome
    /** Not enough players signed up to field two teams. */
    data object Cancelled : Outcome
}

/** An enemy-territory incursion in progress. */
data class Incursion(
    val playerId: PlayerId,
    val enteredAt: Millis,
    /** Pings already sent; the next one is ping number [pingsSent] + 1. */
    val pingsSent: Int = 0,
)

enum class PingKind {
    /** Scheduled incursion ping. Decoys are sent as this kind too. */
    INCURSION,
    /** Bloodhound: live follow-up after a ping. */
    TRACKING,
    /** Tripwire: an enemy crossed in near your flag. */
    TRIPWIRE,
    /** Interrogate: forced on-demand ping. */
    INTERROGATION,
    /**
     * To the player alone: they're within [com.hereliesaz.capturetheflag.rules.GameRules.FLAG_ZONE_M]
     * of the enemy flag and should go live, or a capture won't count. [Ping.location] is their own
     * position, never the flag's.
     */
    GO_LIVE,
}

/**
 * One location broadcast. [identified] is null until the identity threshold is reached.
 *
 * [subjectLevel] and [decoyRevealedTo] are per-recipient knowledge. A networked server must
 * strip [subjectLevel] for recipients without Keen Eye and [decoyRevealedTo] down to the
 * receiving player before delivery; [subject] never leaves the server for decoys.
 */
data class Ping(
    val subject: PlayerId,
    val number: Int,
    val location: GeoPoint,
    val at: Millis,
    val identified: User?,
    val recipients: Set<PlayerId>,
    val kind: PingKind = PingKind.INCURSION,
    /** Blur radius: the subject is somewhere within this distance of [location]. */
    val radiusM: Double = 0.0,
    val subjectLevel: Int? = null,
    /** Recipients whose Counterintel exposes this as a decoy. */
    val decoyRevealedTo: Set<PlayerId> = emptySet(),
)

/** A Doppelgänger decoy still walking: one more ping per waypoint. */
data class DecoyWalk(val sender: PlayerId, val waypoints: List<GeoPoint>, val nextAt: Millis)

data class Game(
    val id: GameId,
    val city: City,
    val territory: Territory,
    val phase: GamePhase,
    val players: Map<PlayerId, Player> = emptyMap(),
    /** Sign-ups before team assignment. */
    val signups: List<User> = emptyList(),
    val flags: Map<Team, Flag> = emptyMap(),
    val jails: Map<Team, Jail> = emptyMap(),
    /** What each player has done this round. Feeds MVPs and the Mosts. */
    val stats: Map<PlayerId, RoundStats> = emptyMap(),
    /** Net points each player has earned this round. Zeroed on disqualification. */
    val earned: Map<PlayerId, Long> = emptyMap(),
    val lastFix: Map<PlayerId, LocationFix> = emptyMap(),
    val incursions: Map<PlayerId, Incursion> = emptyMap(),
    /** (tagger, target) pairs already scored this round. Re-jailing the same player pays nothing. */
    val scoredTags: Set<Pair<PlayerId, PlayerId>> = emptySet(),
    val decoysUsed: Map<PlayerId, Int> = emptyMap(),
    val interrogationsUsed: Map<PlayerId, Int> = emptyMap(),
    val vanishesUsed: Map<PlayerId, Int> = emptyMap(),
    /** Players whose next scheduled ping will be swallowed. */
    val vanishPending: Set<PlayerId> = emptySet(),
    val lastStandsUsed: Map<PlayerId, Int> = emptyMap(),
    /** Marker → marked enemy. One Bounty per marker per round. */
    val bounties: Map<PlayerId, PlayerId> = emptyMap(),
    /** (hunter, intruder) → Bloodhound trail expiry. */
    val trails: Map<Pair<PlayerId, PlayerId>, Millis> = emptyMap(),
    val decoyWalks: List<DecoyWalk> = emptyList(),
    /** Recipient → intruders they have been pinged about this round. Gates Interrogate. */
    val pingedAbout: Map<PlayerId, Set<PlayerId>> = emptyMap(),
    /** Capture and jailbreak streams this round, by stream id: live, awaiting disputes, or done. */
    val streams: Map<String, LiveStream> = emptyMap(),
    /** Player → when they came within [com.hereliesaz.capturetheflag.rules.GameRules.FLAG_ZONE_M] of the enemy flag, this approach. */
    val flagZone: Map<PlayerId, Millis> = emptyMap(),
    /** Players who switched location off on enemy ground: their next fix at home jails them. */
    val dark: Set<PlayerId> = emptySet(),
    /** Teams that have used their one appeal this round. */
    val appealsUsed: Set<Team> = emptySet(),
) {
    fun team(t: Team): List<Player> = players.values.filter { it.team == t }
}

/** What a live stream is trying to prove. */
enum class StreamPurpose { CAPTURE, JAILBREAK }

/**
 * A capture or jailbreak, streamed live to everybody. The phone sends a frame every few
 * seconds: its fix and the hash of the video written since the last frame, so the video can't
 * be swapped afterward. The [challenge] is shown from the start and said with the winning
 * frame ([qualifiedAt]); the referees' footage ends 30 s later ([endedAt]), and the stream may
 * go on as a victory lap. Defenders then have until [contestUntil] to dispute; undisputed, it counts.
 */
data class LiveStream(
    val id: String,
    val by: PlayerId,
    val purpose: StreamPurpose,
    val target: GeoPoint,
    val startedAt: Millis,
    val lastFrame: LocationFix,
    val chunks: List<String> = emptyList(),
    /** Two words said at the start of the stream, drawn from the batch it went live in: nobody knew them before. */
    val challenge: String? = null,
    val challengeAt: Millis? = null,
    /** The winning frame: said with the challenge. The referees' footage ends 30 s later, at [endedAt]. */
    val qualifiedAt: Millis? = null,
    /** Flag run: when the player came within 200 m of the flag, as of the winning frame. */
    val zoneAt: Millis? = null,
    /** Jailbreak: first frame inside the jail radius. */
    val arrivedAt: Millis? = null,
    val endedAt: Millis? = null,
    /** The still it finished on, kept for review. */
    val finish: PhotoEvidence? = null,
    val contestUntil: Millis? = null,
    val dispute: Dispute? = null,
    /** Which review this is: 0 the first, 1 after an appeal. Referees vote per review. */
    val review: Int = 0,
    /** When the current review began: the dispute, or the appeal. */
    val reviewSince: Millis? = null,
    /** The referees' ruling on the current review, and when; it takes effect once the appeal window closes. */
    val ruling: Boolean? = null,
    val ruledAt: Millis? = null,
    /** The team that appealed, if one did. */
    val appealedBy: Team? = null,
    /** Null while undecided; then true (it counted) or false (it didn't). */
    val upheld: Boolean? = null,
    /** Why it failed, if it did. */
    val void: String? = null,
) {
    val open: Boolean get() = endedAt == null && void == null
    val pending: Boolean get() = endedAt != null && void == null && upheld == null
}

/**
 * A defender's objection to a stream. Settled by the referees' automated checks, never by a
 * person. Each team's leaders see every referee's report first and may appeal once per round.
 */
data class Dispute(val by: PlayerId, val at: Millis, val reason: String)

/** Points granted to one user by one verified event. Summed into global and per-city standings. */
data class Award(
    val user: PlayerId,
    val city: CityId,
    val game: GameId,
    val points: Long,
    val reason: String,
    val at: Millis,
)

/** What kind of moment a [Highlight] records. */
enum class HighlightKind {
    /** A tag attempt on an opponent that failed its checks. [Highlight.other] is the target. */
    NEAR_MISS,
    /** A tag thrown out by Last Stand. [Highlight.user] made the stand, [Highlight.other] took the photo. */
    LAST_STAND,
    /** A decoy sent. [Highlight.value] is how many extra waypoints it walked. Secret until the round ends. */
    DECOY,
    /** A scheduled ping swallowed. Secret until the round ends. */
    VANISH,
    /** A forced private ping. [Highlight.other] is the intruder. Secret until the round ends. */
    INTERROGATION,
    /** A tag paid out on a Bounty. [Highlight.other] is the prisoner, [Highlight.value] the multiplier ×10. */
    BOUNTY_COLLECTED,
    /** A jail report completed. [Highlight.value] is seconds to spare before the deadline. */
    REPORTED,
    /** A breakout abandoned. [Highlight.value] is minutes held before leaving. */
    BREAKOUT_ABANDONED,
    /** Released by Parole. */
    PAROLE,
    /** A Tripwire alarm. [Highlight.user] is the defender, [Highlight.other] the intruder. Secret until the round ends. */
    TRIPWIRE,
    /**
     * Made a Mosts list when the round ended. [Highlight.note] is the title, [Highlight.value] the rank.
     * Only players who made a list in a round may have that round's antics and rivalries aired later.
     */
    MADE_LIST,
    /**
     * Led a team: captain or co-captain ([Highlight.note] is the role). Like [MADE_LIST], it opens
     * that round's antics and rivalries to the booth: leaders are notable by office.
     */
    LED,
}

/**
 * A moment worth remembering that the points ledger does not capture. Kept for good, so the
 * booth can bring it up in later rounds. [secret] moments must not be aired while their round
 * is still being played.
 */
data class Highlight(
    val kind: HighlightKind,
    val user: PlayerId,
    val other: PlayerId? = null,
    val game: GameId,
    val city: CityId,
    val at: Millis,
    val value: Int = 0,
    val note: String? = null,
) {
    val secret: Boolean get() = kind in setOf(HighlightKind.DECOY, HighlightKind.VANISH, HighlightKind.INTERROGATION, HighlightKind.TRIPWIRE)
}

/** One player's round, counted as it happens. */
data class RoundStats(
    val tags: Int = 0,
    val timesJailed: Int = 0,
    /** Pings endured across every incursion survived. */
    val pingsSurvived: Int = 0,
    /** Most pings endured in a single incursion survived. */
    val deepest: Int = 0,
    val freed: Int = 0,
    val nearMisses: Int = 0,
    /** Fewest seconds to spare on a jail check-in; null if never checked in. */
    val closestReportSec: Int? = null,
    val bountiesCashed: Int = 0,
)
