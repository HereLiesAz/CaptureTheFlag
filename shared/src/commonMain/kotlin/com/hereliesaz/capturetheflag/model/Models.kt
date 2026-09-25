package com.hereliesaz.capturetheflag.model

import com.hereliesaz.capturetheflag.geo.DividingLine
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon

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
data class City(val id: CityId, val name: String, val boundary: Polygon)

/** The city split in two. Everything inside [City.boundary] belongs to exactly one team. */
data class Territory(val city: City, val line: DividingLine) {
    /** Team owning [p], or null if outside the city. */
    fun ownerOf(p: GeoPoint): Team? = when {
        p !in city.boundary -> null
        line.sideOf(p) == Team.NOIR.lineSide -> Team.NOIR
        else -> Team.BLANC
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
)

/** A rotating token heard over BLE. Tokens map to players server-side only. */
data class BleSighting(val token: String, val at: Millis, val rssi: Int)

enum class FlagVenueKind { PUBLIC_SPACE, PUBLIC_BUILDING, BUSINESS }

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

/** One location broadcast. [identified] is null until the identity threshold is reached. */
data class Ping(
    val subject: PlayerId,
    val number: Int,
    val location: GeoPoint,
    val at: Millis,
    val identified: User?,
    val recipients: Set<PlayerId>,
)

data class Game(
    val id: GameId,
    val city: City,
    val territory: Territory,
    val phase: GamePhase,
    val players: Map<PlayerId, Player> = emptyMap(),
    /** Sign-ups before team assignment. */
    val signups: List<User> = emptyList(),
    val flags: Map<Team, Flag> = emptyMap(),
    val lastFix: Map<PlayerId, LocationFix> = emptyMap(),
    val incursions: Map<PlayerId, Incursion> = emptyMap(),
) {
    fun team(t: Team): List<Player> = players.values.filter { it.team == t }
}
