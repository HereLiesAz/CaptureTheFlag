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

    /** Whether the device's location is switched on at all (GPS, Wi-Fi, cell and the rest come as one). */
    val locationOn: StateFlow<Boolean>

    /** Takes a photo and returns it with EXIF, live fix and BLE sightings attached. Null if cancelled. */
    suspend fun takePhoto(): PhotoEvidence?

    /**
     * The live camera for a capture or jailbreak stream. Records video with sound in segments of
     * a few seconds and calls [onFrame] with a fresh fix, each segment's SHA-256, and the segment. Shows [challenge] once issued, and [status].
     * [finishLabel] names the button that takes the qualifying or winning frame, a still handed
     * to [onFinish] while recording carries on; null hides it. In [lap]
     * mode (after the winning frame) segments go to [onLapSegment] instead: the stream is the player's own, and
     * ending it hands [onFinish] null, as does abandoning a stream.
     */
    @Composable
    fun LiveCamera(
        challenge: String?,
        status: String,
        lap: Boolean,
        finishLabel: String?,
        onFrame: suspend (LocationFix, String, ByteArray) -> Unit,
        onLapSegment: suspend (String, ByteArray) -> Unit,
        onFinish: (PhotoEvidence?) -> Unit,
        modifier: Modifier,
    )

    /** Plays a stream's segments one after another, picking up new ones as they're added. */
    @Composable
    fun StreamPlayer(urls: List<String>, modifier: Modifier)

    /** The bytes of a photo or selfie this platform produced, by its reference. */
    suspend fun readMedia(ref: String): ByteArray?

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
