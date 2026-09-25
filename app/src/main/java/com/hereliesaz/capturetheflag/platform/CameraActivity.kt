package com.hereliesaz.capturetheflag.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToLong

/**
 * The game's own camera. Every photo is stamped with GPS EXIF from the fix the caller took at
 * the shutter, so evidence never depends on whether the stock camera app has location tagging
 * on. At the shutter it also reads the rotation-vector sensor: which way the camera faced (true
 * north), how far above or below the horizon, and its roll. The facing goes into the photo's
 * EXIF (GPSImgDirection) and the whole pose goes back with the result, so the server can check
 * the photo's claim against the phone's own sensors. Front lens for selfies, back for the rest.
 */
class CameraActivity : ComponentActivity() {
    private val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private val orientation by lazy { Orientation(this) }

    override fun onResume() {
        super.onResume()
        orientation.start()
    }

    override fun onPause() {
        orientation.stop()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = File(requireNotNull(intent.getStringExtra(EXTRA_PATH)))
        val front = intent.getBooleanExtra(EXTRA_FRONT, false)
        val fix = if (intent.hasExtra(EXTRA_LAT)) Location("fused").apply {
            latitude = intent.getDoubleExtra(EXTRA_LAT, 0.0)
            longitude = intent.getDoubleExtra(EXTRA_LNG, 0.0)
            time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis())
            accuracy = intent.getFloatExtra(EXTRA_ACCURACY, 0f)
        } else null

        setContent {
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx -> PreviewView(ctx).also { bind(it, front) } },
                    modifier = Modifier.fillMaxSize(),
                )
                error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)) }
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(48.dp).size(76.dp).clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .background(if (busy) Color.DarkGray else Color.White.copy(alpha = 0.15f))
                        .clickable(enabled = !busy) {
                            busy = true
                            val p = orientation.pose(fix)
                            shoot(out, fix, front) { ok, msg ->
                                busy = false
                                if (ok) { p?.let { stampFacing(out, it.azimuth) }; finishWith(p) } else error = msg
                            }
                        },
                )
            }
        }
    }

    private fun bind(view: PreviewView, front: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val lens = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(this, lens, preview, capture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot(out: File, fix: Location?, front: Boolean, done: (Boolean, String?) -> Unit) {
        val meta = ImageCapture.Metadata().apply {
            location = fix
            isReversedHorizontal = front
        }
        val options = ImageCapture.OutputFileOptions.Builder(out).setMetadata(meta).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) = done(true, null)
            override fun onError(e: ImageCaptureException) = done(false, "Couldn't take the photo. Try again.")
        })
    }

    private fun finishWith(p: Orientation.Pose?) {
        setResult(Activity.RESULT_OK, Intent().apply {
            p?.let {
                putExtra(EXTRA_AZIMUTH, it.azimuth); putExtra(EXTRA_PITCH, it.pitch)
                putExtra(EXTRA_ROLL, it.roll); putExtra(EXTRA_POSE_AT, it.at)
            }
        })
        finish()
    }

    /** What came back: whether a photo was taken, and the pose if the sensor had one. */
    data class Shot(val taken: Boolean, val pose: Orientation.Pose?)

    /** What to shoot: where to save, which lens, and the fix to stamp into EXIF. */
    data class Request(val path: String, val front: Boolean, val fix: Location?)

    class Contract : ActivityResultContract<Request, Shot>() {
        override fun createIntent(context: Context, input: Request) = Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_PATH, input.path)
            .putExtra(EXTRA_FRONT, input.front)
            .apply {
                input.fix?.let {
                    putExtra(EXTRA_LAT, it.latitude); putExtra(EXTRA_LNG, it.longitude)
                    putExtra(EXTRA_TIME, it.time); putExtra(EXTRA_ACCURACY, it.accuracy)
                }
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Shot {
            if (resultCode != Activity.RESULT_OK) return Shot(false, null)
            val pose = intent?.takeIf { it.hasExtra(EXTRA_AZIMUTH) }?.let {
                Orientation.Pose(it.getDoubleExtra(EXTRA_AZIMUTH, 0.0), it.getDoubleExtra(EXTRA_PITCH, 0.0), it.getDoubleExtra(EXTRA_ROLL, 0.0), it.getLongExtra(EXTRA_POSE_AT, 0))
            }
            return Shot(true, pose)
        }
    }

    private companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_FRONT = "front"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_TIME = "time"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_AZIMUTH = "azimuth"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_ROLL = "roll"
        const val EXTRA_POSE_AT = "poseAt"
    }
}

/** Writes the facing into the photo itself: GPSImgDirection, true north. */
internal fun stampFacing(file: File, azimuth: Double) = runCatching {
    ExifInterface(file).apply {
        setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(azimuth * 100).roundToLong()}/100")
        setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
        saveAttributes()
    }
}
