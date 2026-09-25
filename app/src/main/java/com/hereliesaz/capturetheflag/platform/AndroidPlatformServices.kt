package com.hereliesaz.capturetheflag.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hereliesaz.capturetheflag.data.PlatformServices
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.DevicePose
import com.hereliesaz.capturetheflag.model.PhotoEvidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android implementation. Must be constructed in [ComponentActivity.onCreate] (before STARTED)
 * so activity-result launchers register in time.
 */
class AndroidPlatformServices(private val activity: ComponentActivity) : PlatformServices {
    private val proximity = Proximity(activity)
    private val fused = LocationServices.getFusedLocationProviderClient(activity)
    private var pending: CompletableDeferred<CameraActivity.Shot>? = null
    private val camera = activity.registerForActivityResult(CameraActivity.Contract()) {
        pending?.complete(it)
    }

    override val location: StateFlow<com.hereliesaz.capturetheflag.model.LocationFix?> = Tracking.location
    override val locationOn: StateFlow<Boolean> = Tracking.watchEnabled(activity)

    /**
     * Opens the in-app camera. A fresh fix is taken first and handed to the camera, which
     * stamps it into the photo's EXIF; that same fix is published as the live device fix.
     */
    @SuppressLint("MissingPermission")
    private suspend fun shoot(prefix: String, front: Boolean): Pair<Uri, Orientation.Pose?>? {
        val dir = File(activity.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        val live = runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
        live?.let { Tracking.publish(it.latitude, it.longitude, it.time, it.accuracy.toDouble()) }
        val done = CompletableDeferred<CameraActivity.Shot>().also { pending = it }
        camera.launch(CameraActivity.Request(file.path, front, live))
        val shot = done.await()
        if (!shot.taken || file.length() == 0L) return null
        return FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file) to shot.pose
    }

    override suspend fun takeSelfie(): String? = shoot("selfie", front = true)?.first?.toString()

    @SuppressLint("MissingPermission")
    override suspend fun takePhoto(): PhotoEvidence? {
        val (uri, pose) = shoot("evidence", front = false) ?: return null
        return evidence(uri, pose)
    }

    /** A saved JPEG as evidence: what its EXIF claims, the live fix, the BLE tokens heard, the pose. */
    internal suspend fun evidence(file: File, pose: Orientation.Pose?): PhotoEvidence =
        evidence(FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file), pose)

    private suspend fun evidence(uri: Uri, pose: Orientation.Pose?): PhotoEvidence {
        val (gps, taken, facing) = withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)!!.use { s ->
                val exif = ExifInterface(s)
                Triple(
                    exif.latLong?.let { GeoPoint(it[0], it[1]) },
                    exif.dateTimeOriginal,
                    exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN).takeUnless { it.isNaN() },
                )
            }
        }
        return PhotoEvidence(
            imageUri = uri.toString(),
            exifLocation = gps,
            exifTakenAt = taken,
            deviceFix = Tracking.location.value,
            bleSightings = taken?.let { proximity.around(it) } ?: proximity.around(System.currentTimeMillis()),
            exifDirection = facing,
            pose = pose?.let { DevicePose(it.azimuth, it.pitch, it.roll, it.at) },
        )
    }

    @Composable
    override fun LiveCamera(
        challenge: String?,
        status: String,
        lap: Boolean,
        finishLabel: String?,
        onFrame: suspend (com.hereliesaz.capturetheflag.model.LocationFix, String) -> Unit,
        onFinish: (PhotoEvidence?) -> Unit,
        modifier: Modifier,
    ) = LiveCameraView(this, challenge, status, lap, finishLabel, onFrame, onFinish, modifier)

    override fun startProximity(token: String) = proximity.start(token)
    override fun stopProximity() = proximity.stop()
    /**
     * Android 11+ grants "all the time" location only as a separate request, after foreground
     * location. Without it pings and jail check-ins stop the moment the screen goes dark.
     */
    private val background = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun startTracking(cityName: String) {
        val fine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val always = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine && !always) runCatching { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        TrackingService.start(activity)
    }

    override fun alert(title: String, body: String) = AlertNotification.post(activity, title, body)
    override fun stopTracking() { TrackingService.stop(activity) }
    override fun showLiveFeed(lines: List<String>) = RadioNotification.post(activity, lines)

    @Composable
    override fun Portrait(uri: String?, modifier: Modifier) {
        val bmp = remember(uri) {
            uri?.let { u ->
                runCatching {
                    activity.contentResolver.openInputStream(Uri.parse(u))?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                }.getOrNull()
            }
        }
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, modifier.clip(androidx.compose.foundation.shape.CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF1C1C1C)))
        }
    }
}
