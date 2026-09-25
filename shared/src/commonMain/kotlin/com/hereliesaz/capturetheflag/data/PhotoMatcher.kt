package com.hereliesaz.capturetheflag.data

/**
 * Compares a capture or jailbreak photo with the leader's registration photo of the same thing.
 * Returns similarity from 0 (nothing in common) to 1 (same scene), or null if it can't judge.
 *
 * Meant to run server-side with real feature matching (for example ORB or SIFT keypoints and a
 * RANSAC homography, scored by inlier ratio), which survives different angles and lighting.
 * Not implemented yet: the rules accept a score when there is one and skip the check when not.
 */
fun interface PhotoMatcher {
    suspend fun similarity(photoUri: String, referenceUri: String): Double?
}
