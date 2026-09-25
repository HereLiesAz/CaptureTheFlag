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

    /**
     * The live camera for a capture or jailbreak stream. Records video with sound (a jailbreak's
     * challenge is spoken) and calls [onFrame] every few seconds with a fresh fix and the SHA-256
     * of the video written since the previous frame. Shows [challenge] once issued, and [status].
     * The winning frame is a still, handed to [onFinish], and recording carries on. In [lap]
     * mode (after the winning frame) no frames are sent: the stream is the player's own, and
     * ending it hands [onFinish] null, as does abandoning a stream.
     */
    @Composable
    fun LiveCamera(
        challenge: String?,
        status: String,
        lap: Boolean,
        onFrame: suspend (LocationFix, String) -> Unit,
        onFinish: (PhotoEvidence?) -> Unit,
        modifier: Modifier,
    )

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
