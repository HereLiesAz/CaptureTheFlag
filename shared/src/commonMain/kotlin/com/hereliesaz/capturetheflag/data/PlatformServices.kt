package com.hereliesaz.capturetheflag.data

import com.hereliesaz.capturetheflag.model.LocationFix
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Device capabilities the shared UI needs. Implemented per platform. */
interface PlatformServices {
    /** Live location. Null until the first fix. */
    val location: StateFlow<LocationFix?>

    /** Takes a photo and returns it with EXIF, live fix and BLE sightings attached. Null if cancelled. */
    suspend fun takePhoto(): PhotoEvidence?

    /** Captures a selfie for registration. Returns a content URI, or null if cancelled. */
    suspend fun takeSelfie(): String?

    /** Starts advertising [token] and scanning for others'. Call again on each rotation. */
    fun startProximity(token: String)
    fun stopProximity()

    /** Starts background location reporting for the active game. */
    fun startTracking(cityName: String)
    fun stopTracking()

    /** An urgent, sounding alert: someone is on your ground, you've been jailed, your jail is under attack. */
    fun alert(title: String, body: String)

    /** Keeps an ongoing notification showing the newest play-by-play, newest first. Empty clears it. */
    fun showLiveFeed(lines: List<String>)

    /** Renders a selfie or photo from a URI this platform produced. */
    @Composable
    fun Portrait(uri: String?, modifier: Modifier)
}
