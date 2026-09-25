package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.bearingTo
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.geo.headingDelta
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.PlayerId
import kotlin.math.abs
import kotlin.math.max

sealed interface Verdict {
    data object Valid : Verdict
    data class Rejected(val reason: String) : Verdict
}

/** Resolves a rotating BLE token to the player who was advertising it at [at]. Server-side only. */
fun interface BleTokenRegistry {
    fun ownerOf(token: String, at: Millis): PlayerId?
}

/**
 * Pure checks over photo evidence. Every rule is layered: EXIF must exist, be fresh,
 * agree with the photographer's live fix, and then agree with the target.
 */
object Verification {

    /** Common evidence hygiene. Returns the EXIF location if the photo passes. */
    private fun sound(e: PhotoEvidence, now: Millis): Pair<GeoPoint?, String?> {
        val exif = e.exifLocation ?: return null to "Photo has no GPS EXIF"
        val taken = e.exifTakenAt ?: return null to "Photo has no EXIF timestamp"
        if (abs(now - taken) > GameRules.EVIDENCE_MAX_AGE) return null to "Photo is not fresh"
        val fix = e.deviceFix ?: return null to "No live device location at capture"
        if (fix.accuracyM > GameRules.MAX_FIX_ACCURACY_M) return null to "Device location too imprecise"
        if (abs(fix.at - taken) > GameRules.EVIDENCE_MAX_AGE) return null to "Device fix does not match photo time"
        if (exif.distanceTo(fix.point) > GameRules.EXIF_VS_DEVICE_TOLERANCE_M) {
            return null to "Photo location disagrees with device location"
        }
        // Sensors: the phone was held like a camera, when the photo says, pointing where the photo says.
        val pose = e.pose ?: return null to "No motion sensor reading at capture"
        if (abs(pose.at - taken) > GameRules.POSE_MAX_SKEW) return null to "Sensor reading does not match photo time"
        if (abs(pose.pitchDeg) > GameRules.POSE_MAX_TILT_DEG) return null to "Phone wasn't held like a camera"
        val claimed = e.exifDirection ?: return null to "Photo does not record which way it faced"
        if (headingDelta(claimed, pose.azimuthDeg) > GameRules.DIRECTION_VS_SENSOR_DEG) {
            return null to "Photo's facing disagrees with the phone's sensors"
        }
        return exif to null
    }

    /**
     * The camera pointed at [target] from [from]. Skipped when standing on top of it; widened
     * by how much the GPS uncertainty could swing the bearing up close.
     */
    private fun facing(e: PhotoEvidence, from: GeoPoint, target: GeoPoint, accuracyM: Double): String? {
        val d = from.distanceTo(target)
        if (d < GameRules.FACING_MIN_DISTANCE_M) return null
        val slack = kotlin.math.atan2(accuracyM, d) * 180 / kotlin.math.PI
        val heading = e.pose?.azimuthDeg ?: return "No motion sensor reading at capture"
        return if (headingDelta(heading, from.bearingTo(target)) > GameRules.FACING_TOLERANCE_DEG + slack) {
            "Camera wasn't pointed at the target"
        } else null
    }

    /** Leader registering the team flag at a venue they identified by address/coordinates. */
    fun flagRegistration(game: Game, by: PlayerId, venue: GeoPoint, photo: PhotoEvidence, now: Millis): Verdict {
        val p = game.players[by] ?: return Verdict.Rejected("Not in this game")
        if (!p.isLeader) return Verdict.Rejected("Only the captain or co-captains place the flag")
        if (game.flags.containsKey(p.team)) return Verdict.Rejected("Flag already placed")
        val (exif, err) = sound(photo, now)
        if (exif == null) return Verdict.Rejected(err!!)
        if (exif.distanceTo(venue) > GameRules.FLAG_REGISTRATION_TOLERANCE_M) {
            return Verdict.Rejected("Photo was not taken at the stated venue")
        }
        if (game.territory.ownerOf(venue) != p.team) return Verdict.Rejected("Flag must be inside your own territory")
        return Verdict.Valid
    }

    /** Leader registering the team jail. Flag first, then a jail well away from it. */
    fun jailRegistration(game: Game, by: PlayerId, venue: GeoPoint, photo: PhotoEvidence, now: Millis): Verdict {
        val p = game.players[by] ?: return Verdict.Rejected("Not in this game")
        if (!p.isLeader) return Verdict.Rejected("Only the captain or co-captains place the jail")
        if (game.jails.containsKey(p.team)) return Verdict.Rejected("Jail already placed")
        val flag = game.flags[p.team] ?: return Verdict.Rejected("Place the flag first")
        val (exif, err) = sound(photo, now)
        if (exif == null) return Verdict.Rejected(err!!)
        if (exif.distanceTo(venue) > GameRules.FLAG_REGISTRATION_TOLERANCE_M) {
            return Verdict.Rejected("Photo was not taken at the stated venue")
        }
        if (game.territory.ownerOf(venue) != p.team) return Verdict.Rejected("Jail must be inside your own territory")
        if (venue.distanceTo(flag.location) < GameRules.JAIL_MIN_FROM_FLAG_M) {
            return Verdict.Rejected("Jail must be at least ${GameRules.JAIL_MIN_FROM_FLAG_M.toInt()} m from your flag")
        }
        return Verdict.Valid
    }

    /** Jailbreak: a free player photographs the enemy jail. */
    fun jailbreak(game: Game, by: PlayerId, photo: PhotoEvidence, now: Millis): Verdict {
        val p = game.players[by] ?: return Verdict.Rejected("Not in this game")
        if (p.isJailed) return Verdict.Rejected("Jailed players cannot break anyone out")
        val jail = game.jails[p.team.opponent] ?: return Verdict.Rejected("Enemy jail not placed")
        val (exif, err) = sound(photo, now)
        if (exif == null) return Verdict.Rejected(err!!)
        if (exif.distanceTo(jail.location) > GameRules.JAIL_TOLERANCE_M) {
            return Verdict.Rejected("Photo was not taken at the enemy jail")
        }
        facing(photo, exif, jail.location, photo.deviceFix!!.accuracyM)?.let { return Verdict.Rejected(it) }
        return Verdict.Valid
    }

    /** Win condition: an opponent photographs the flag where it was registered. */
    fun flagCapture(game: Game, by: PlayerId, photo: PhotoEvidence, now: Millis): Verdict {
        val p = game.players[by] ?: return Verdict.Rejected("Not in this game")
        if (p.isJailed) return Verdict.Rejected("Jailed players cannot capture")
        val flag = game.flags[p.team.opponent] ?: return Verdict.Rejected("Enemy flag not placed")
        val (exif, err) = sound(photo, now)
        if (exif == null) return Verdict.Rejected(err!!)
        if (exif.distanceTo(flag.location) > GameRules.FLAG_CAPTURE_TOLERANCE_M) {
            return Verdict.Rejected("Photo was not taken at the enemy flag")
        }
        facing(photo, exif, flag.location, photo.deviceFix!!.accuracyM)?.let { return Verdict.Rejected(it) }
        return Verdict.Valid
    }

    /**
     * Jailing: [by] photographs [target], who must be an opponent standing in [by]'s territory.
     * Checks the photo location against the target's last fix and requires a BLE sighting
     * of the target's token near the photo time.
     */
    fun tag(
        game: Game,
        by: PlayerId,
        target: PlayerId,
        photo: PhotoEvidence,
        now: Millis,
        ble: BleTokenRegistry,
    ): Verdict {
        val tagger = game.players[by] ?: return Verdict.Rejected("Not in this game")
        val t = game.players[target] ?: return Verdict.Rejected("Identified player is not in this game")
        if (tagger.isJailed) return Verdict.Rejected("Jailed players cannot tag")
        if (t.team == tagger.team) return Verdict.Rejected("That player is your teammate")
        if (t.isJailed) return Verdict.Rejected("Already jailed")
        val (exif, err) = sound(photo, now)
        if (exif == null) return Verdict.Rejected(err!!)
        val targetFix = game.lastFix[target] ?: return Verdict.Rejected("No location on record for that player")
        if (abs(targetFix.at - photo.exifTakenAt!!) > GameRules.EVIDENCE_MAX_AGE) {
            return Verdict.Rejected("That player's location is stale")
        }
        if (game.territory.ownerOf(targetFix.point) != tagger.team) {
            return Verdict.Rejected("That player is not in your territory")
        }
        val perks = Progression.perksFor(tagger.level)
        facing(photo, exif, targetFix.point, max(photo.deviceFix!!.accuracyM, targetFix.accuracyM))?.let { return Verdict.Rejected(it) }
        if (exif.distanceTo(targetFix.point) > GameRules.TAG_TOLERANCE_M + perks.sharpLensM) {
            return Verdict.Rejected("That player was not where the photo was taken")
        }
        val window = GameRules.BLE_WINDOW + perks.bleWindowBonusMs
        val heard = photo.bleSightings.any {
            abs(it.at - photo.exifTakenAt) <= window && ble.ownerOf(it.token, it.at) == target
        }
        if (!heard) return Verdict.Rejected("That player's phone was not nearby")
        return Verdict.Valid
    }
}
