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
    private var pending: CompletableDeferred<Boolean>? = null
    private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) {
        pending?.complete(it)
    }

    override val location: StateFlow<com.hereliesaz.capturetheflag.model.LocationFix?> = Tracking.location

    private suspend fun shoot(prefix: String): Uri? {
        val dir = File(activity.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file)
        val done = CompletableDeferred<Boolean>().also { pending = it }
        camera.launch(uri)
        return uri.takeIf { done.await() && file.length() > 0 }
    }

    override suspend fun takeSelfie(): String? = shoot("selfie")?.toString()

    @SuppressLint("MissingPermission")
    override suspend fun takePhoto(): PhotoEvidence? {
        val uri = shoot("evidence") ?: return null
        // Live fix sampled right after the shutter; compared server-side against the EXIF.
        val live = runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
        live?.let { Tracking.publish(it.latitude, it.longitude, it.time, it.accuracy.toDouble()) }
        val (gps, taken) = withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)!!.use { s ->
                val exif = ExifInterface(s)
                exif.latLong?.let { GeoPoint(it[0], it[1]) } to exif.dateTimeOriginal
            }
        }
        return PhotoEvidence(
            imageUri = uri.toString(),
            exifLocation = gps,
            exifTakenAt = taken,
            deviceFix = Tracking.location.value,
            bleSightings = taken?.let { proximity.around(it) } ?: proximity.around(System.currentTimeMillis()),
        )
    }

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
