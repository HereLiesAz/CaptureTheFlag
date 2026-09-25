package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.BleSighting
import com.hereliesaz.capturetheflag.model.DevicePose
import com.hereliesaz.capturetheflag.model.FlagVenueKind
import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.StreamPurpose
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Event kinds from docs/DECENTRALIZED.md. */
object Kinds {
    const val CITY_SURVEY = 31000
    const val GAME_OPEN = 32000
    const val SEED_REVEAL = 32001
    const val ACTION = 33000
    const val POSITION = 33001
    const val BLE_KEY = 33003
    const val BATCH = 34000
    const val OUTCOME = 34001
    const val PING = 34002
    const val COMMIT = 34003
    const val REVEAL = 34004
    const val RULING = 34005
    const val REPORT = 34006
    const val RADIO = 35000
}

/**
 * What a player asks for, as the content of a kind-33000 event. The signer's public key is the
 * player id. [Open] is plaintext tagged `["c", city]`; everything else is tagged `["g", gameId]`
 * and [Sealed] to every referee on the game's panel (listed in its `game.open`).
 */
@Serializable
sealed interface Action {
    @Serializable @SerialName("open") data class Open(val city: String) : Action
    @Serializable @SerialName("join") data class Join(val name: String, val selfie: String) : Action
    @Serializable @SerialName("coCaptains") data class CoCaptains(val picks: Set<String>) : Action
    @Serializable @SerialName("placeFlag") data class PlaceFlag(
        val venue: String, val kind: FlagVenueKind, val address: String, val lat: Double, val lng: Double, val photo: Evidence,
    ) : Action
    @Serializable @SerialName("placeJail") data class PlaceJail(
        val venue: String, val address: String, val lat: Double, val lng: Double, val photo: Evidence,
    ) : Action
    @Serializable @SerialName("goLive") data class GoLive(val stream: String, val purpose: StreamPurpose, val fix: Position) : Action
    @Serializable @SerialName("frame") data class Frame(val stream: String, val fix: Position, val chunk: String) : Action
    @Serializable @SerialName("endStream") data class EndStream(val stream: String, val photo: Evidence) : Action
    @Serializable @SerialName("dispute") data class Dispute(val stream: String, val reason: String) : Action
    @Serializable @SerialName("appeal") data class Appeal(val stream: String) : Action
    @Serializable @SerialName("locationOff") data object LocationOff : Action
    @Serializable @SerialName("tag") data class Tag(val target: String, val photo: Evidence) : Action
    @Serializable @SerialName("decoy") data class Decoy(val lat: Double, val lng: Double) : Action
    @Serializable @SerialName("vanish") data object Vanish : Action
    @Serializable @SerialName("interrogate") data class Interrogate(val subject: String) : Action
    @Serializable @SerialName("bounty") data class Bounty(val target: String) : Action
}

/** A position report, content of kind 33001, [Sealed] to the panel. */
@Serializable
data class Position(val lat: Double, val lng: Double, val at: Long, val accuracy: Double) {
    fun fix() = LocationFix(GeoPoint(lat, lng), at, accuracy)
}

/** Wire form of [PhotoEvidence]. */
@Serializable
data class Evidence(
    val image: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val takenAt: Long? = null,
    val fix: Position? = null,
    val direction: Double? = null,
    val pose: Pose? = null,
    val ble: List<Sighting> = emptyList(),
) {
    @Serializable data class Pose(val azimuth: Double, val pitch: Double, val roll: Double, val at: Long)
    @Serializable data class Sighting(val token: String, val at: Long, val rssi: Int)

    fun toModel() = PhotoEvidence(
        imageUri = image,
        exifLocation = if (lat != null && lng != null) GeoPoint(lat, lng) else null,
        exifTakenAt = takenAt,
        deviceFix = fix?.fix(),
        bleSightings = ble.map { BleSighting(it.token, it.at, it.rssi) },
        exifDirection = direction,
        pose = pose?.let { DevicePose(it.azimuth, it.pitch, it.roll, it.at) },
    )
}

/** Content of a kind-34000 batch: the canonical order of player events for one game. */
@Serializable
data class Batch(val seq: Long, val at: Long, val events: List<String>)

/** Content of a kind-34001 outcome: the public result of one batch. */
@Serializable
data class Outcome(
    val seq: Long,
    val verdicts: Map<String, String>,
    val awards: List<AwardDto>,
    val notices: List<String>,
    val phase: String,
) {
    @Serializable data class AwardDto(val user: String, val points: Long, val reason: String)
}

/** Content of a kind-34002 ping, NIP-44 encrypted to its one recipient (tagged `p`). */
@Serializable
data class PingDto(val number: Int, val lat: Double, val lng: Double, val radius: Double, val kind: String)

/**
 * A secret the referee holds during a round. [preimage] is canonical text (`lat,lng,photoHash`
 * for a flag, the hex key for BLE); the public commitment is `sha256(preimage|salt)`.
 */
@Serializable
data class Secret(val what: String, val who: String, val team: String? = null, val preimage: String, val salt: String) {
    val commitment get() = Nostr.sha256("$preimage|$salt".toByteArray()).toHex()
}

/** Content of a kind-34003 commit: what exists, not what it is. */
@Serializable
data class Commit(val what: String, val who: String, val team: String? = null, val commitment: String)

/** Content of a kind-34004 reveal, published once the round ends. */
@Serializable
data class Reveal(val secrets: List<Secret>)

/**
 * A player event's content inside a game: one NIP-44 payload per referee on the panel, keyed
 * by referee pubkey. One event, one id, so every referee orders the same thing.
 */
object Sealed {
    fun forPanel(body: String, sender: Keys, panel: List<String>): String =
        Nostr.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), panel.associateWith { Nip44.seal(body, sender, it) })

    /** This referee's copy, or null if there isn't one or it doesn't open. */
    fun open(content: String, keys: Keys, sender: String): String? = runCatching {
        Nip44.open(Nostr.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), content).getValue(keys.pub), keys, sender)
    }.getOrNull()
}

/** Content of a kind-32000 game.open: one per panel referee, each carrying its seed commitment. */
@Serializable
data class GameOpen(val open: String, val city: String, val deadline: Long, val panel: List<String>, val commit: String)

/** Content of a kind-34005 ruling: one referee's public vote on one review of a disputed stream. */
@Serializable
data class Ruling(val stream: String, val review: Int, val upheld: Boolean)

/**
 * Content of a kind-34006 report, sealed to each leader of both teams: everything one referee
 * checked, and how. Leaders see these before anyone else, and can set them side by side with
 * [Reviews.discrepancies].
 */
@Serializable
data class ReviewReport(val stream: String, val review: Int, val referee: String, val upheld: Boolean, val checks: List<Check>) {
    @Serializable data class Check(val name: String, val result: Result, val detail: String)
    @Serializable enum class Result { PASS, FAIL, NOT_RUN }
}

object Reviews {
    /** One check the referees didn't all agree on: what each of them found. */
    data class Discrepancy(val check: String, val findings: Map<String, ReviewReport.Check>)

    /** Where the referees' reports differ, check by check. Empty when they all agree. */
    fun discrepancies(reports: List<ReviewReport>): List<Discrepancy> =
        reports.flatMap { r -> r.checks.map { it.name } }.distinct().mapNotNull { name ->
            val findings = reports.mapNotNull { r -> r.checks.firstOrNull { it.name == name }?.let { r.referee to it } }.toMap()
            if (findings.values.map { it.result }.distinct().size > 1 || findings.size < reports.size) Discrepancy(name, findings) else null
        }
}
