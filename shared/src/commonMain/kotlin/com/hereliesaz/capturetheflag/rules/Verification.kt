package com.hereliesaz.capturetheflag.rules

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.distanceTo
import com.hereliesaz.capturetheflag.model.Game
import com.hereliesaz.capturetheflag.model.Millis
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import com.hereliesaz.capturetheflag.model.PlayerId
import kotlin.math.abs

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
        return exif to null
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
        if (exif.distanceTo(targetFix.point) > GameRules.TAG_TOLERANCE_M) {
            return Verdict.Rejected("That player was not where the photo was taken")
        }
        val heard = photo.bleSightings.any {
            abs(it.at - photo.exifTakenAt) <= GameRules.BLE_WINDOW && ble.ownerOf(it.token, it.at) == target
        }
        if (!heard) return Verdict.Rejected("That player's phone was not nearby")
        return Verdict.Valid
    }
}
