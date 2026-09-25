package com.hereliesaz.capturetheflag.rules

/** Every tunable number in one place. Durations in milliseconds, distances in meters. */
object GameRules {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    const val SIGNUP_WINDOW = DAY
    const val FLAG_PLACEMENT_WINDOW = HOUR
    const val PLAY_WINDOW = 7 * DAY

    const val MIN_PLAYERS_PER_TEAM = 1
    const val MAX_CO_CAPTAINS = 2

    /** Gap before ping N (1-based). Ping 1 fires on entry; after the list runs out, the last gap repeats. */
    val PING_GAPS: List<Long> = listOf(0L, 60 * MINUTE, 30 * MINUTE, 15 * MINUTE, 10 * MINUTE, 5 * MINUTE)

    /** From this ping onward the subject's roster identity rides along with the location. */
    const val IDENTIFY_FROM_PING = 6

    /** Fraction of the opposing team that receives each ping, freshly drawn every time. */
    const val PING_RECIPIENT_FRACTION = 1.0 / 3.0

    /** Photo's EXIF location vs. the venue coordinates the leader registered. */
    const val FLAG_REGISTRATION_TOLERANCE_M = 75.0
    /** Photo's EXIF location vs. the registered flag location. */
    const val FLAG_CAPTURE_TOLERANCE_M = 40.0
    /** Photo's EXIF location vs. the tagged player's last known fix. */
    const val TAG_TOLERANCE_M = 60.0
    /** EXIF location vs. the photographer's own live fix. Catches doctored EXIF. */
    const val EXIF_VS_DEVICE_TOLERANCE_M = 60.0

    /** How old EXIF time or a location fix may be relative to submission. */
    const val EVIDENCE_MAX_AGE = 2 * MINUTE
    /** How far a BLE sighting may sit from the photo's timestamp. */
    const val BLE_WINDOW = MINUTE
    /** Fixes worse than this are rejected for verification. */
    const val MAX_FIX_ACCURACY_M = 50.0
    /** Rotation period for BLE advertisement tokens. */
    const val BLE_TOKEN_ROTATION = 15 * MINUTE
}
