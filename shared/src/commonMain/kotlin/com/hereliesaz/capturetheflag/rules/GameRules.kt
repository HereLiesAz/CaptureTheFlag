package com.hereliesaz.capturetheflag.rules

/** Every tunable number in one place. Durations in milliseconds, distances in meters. */
object GameRules {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    const val SIGNUP_WINDOW = DAY
    const val FLAG_PLACEMENT_WINDOW = HOUR
    const val PLAY_WINDOW = 4 * DAY

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
    /**
     * Last seen on enemy ground, next seen at home, with a gap this long between: they went
     * dark to sneak back, and are jailed where they were last seen. Phones report every 15 s
     * while tracking; an ordinary signal gap is far shorter than any real crossing.
     */
    const val DARK_GAP = 2 * MINUTE
    /** No fix this long on enemy ground (or location switched off) and the screen says so: well inside a minute. */
    const val LOCATION_LOST_WARNING = 30_000L
    /** Sensor pose must be sampled this close to the photo's EXIF time. */
    const val POSE_MAX_SKEW = 3_000L
    /** The camera must point within this many degrees of the horizon: held like a camera, not flat or at the sky. */
    const val POSE_MAX_TILT_DEG = 50.0
    /** The EXIF facing and the sensor heading must agree this closely. */
    const val DIRECTION_VS_SENSOR_DEG = 25.0
    /** The heading must point at the target this closely, widened by GPS uncertainty up close. */
    const val FACING_TOLERANCE_DEG = 45.0
    /** Closer than this, direction is meaningless and isn't checked. */
    const val FACING_MIN_DISTANCE_M = 15.0

    /** The flagged object is assumed to be at most this far in front of where the leader stood to register it. */
    const val REFERENCE_REACH_M = 60.0
    /** Rays closer to parallel than this can't be triangulated; they're judged by heading and offset instead. */
    const val PARALLEL_DEG = 8.0
    /** Minimum visual similarity to the registration photo, when a matcher is available (0..1). */
    const val VISUAL_MATCH_MIN = 0.35

    /** A jail must sit at least this far from its own team's flag. */
    const val JAIL_MIN_FROM_FLAG_M = 400.0
    /** Jail photo vs. registered jail location, for registration and for jailbreak. */
    const val JAIL_TOLERANCE_M = 40.0
    /** A prisoner counts as at the jail within this radius. */
    const val JAIL_REPORT_RADIUS_M = 40.0
    /** Continuous time at the jail to complete a report. */
    const val JAIL_REPORT_HOLD = 5 * MINUTE
    /** Floor on the report window, however close the prisoner is. */
    const val JAIL_REPORT_MIN_WINDOW = 15 * MINUTE
    /** Slack on the travel estimate, then a flat buffer on top. */
    const val JAIL_TRAVEL_SLACK = 1.25
    const val JAIL_TRAVEL_BUFFER = 10 * MINUTE
    /** A rescuer must hold the enemy jail this long, unbroken, to free their team. */
    const val JAILBREAK_HOLD = 15 * MINUTE

    /** A jailbreak stream must start at least this far from the jail, so the walk-in is on camera. */
    const val STREAM_APPROACH_M = 50.0
    /** The challenge is said with the winning frame; the referees' footage runs this long past it, then ends. */
    const val STREAM_CHALLENGE_WINDOW = 30_000L
    /**
     * Within this of the enemy flag, the app asks the player to go live. Roughly a cell tower's
     * reach, as a starting point: wide enough to keep them hunting, and whoever hears it is
     * already cut off from their team (see ChatAccess.blackedOut), so it can only get out on a
     * live stream everybody hears, or by making it home.
     */
    const val FLAG_ZONE_M = 1_000.0
    /** A capture counts only from a stream live since the player came within [FLAG_ZONE_M], or started within this of it. */
    const val STREAM_ZONE_GRACE = MINUTE
    /** Frames further apart than this drop the stream. */
    const val STREAM_MAX_GAP = 20_000L
    /** How long defenders have to dispute a finished stream. */
    const val STREAM_CONTEST_WINDOW = 10 * MINUTE
    /** A review the referees can't settle in this long is dropped, and the stream counts. */
    const val STREAM_RULING_WINDOW = 30 * MINUTE
    /** After a ruling, how long either team's leaders have to appeal it (once per team per round). */
    const val STREAM_APPEAL_WINDOW = 10 * MINUTE

    /** Rotation period for BLE advertisement tokens. */
    const val BLE_TOKEN_ROTATION = 15 * MINUTE
}
