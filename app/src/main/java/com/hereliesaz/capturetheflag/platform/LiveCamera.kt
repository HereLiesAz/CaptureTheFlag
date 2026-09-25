package com.hereliesaz.capturetheflag.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CompletableDeferred
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.LocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/** How long each segment runs, and how often a live stream sends a frame: well inside the engine's 20-second gap limit. */
private const val FRAME_EVERY_MS = 5_000L

/**
 * The live camera: preview, video with sound, and a still for the winning frame.
 *
 * Video is recorded in [FRAME_EVERY_MS] segments, each a complete little video that plays on its
 * own, so viewers can watch as they arrive. Every segment goes out with a fresh fix and its
 * SHA-256; those hashes, signed and sent as they happen, pin the video, so whoever reviews it
 * later can check every stretch against what was reported live. After the winning frame the
 * segments keep coming as the victory lap ([lap]): for viewers, not referees.
 */
@SuppressLint("MissingPermission")
@Composable
internal fun LiveCameraView(
    services: AndroidPlatformServices,
    challenge: String?,
    status: String,
    lap: Boolean,
    finishLabel: String?,
    onFrame: suspend (LocationFix, String, ByteArray) -> Unit,
    onLapSegment: suspend (String, ByteArray) -> Unit,
    onFinish: (com.hereliesaz.capturetheflag.model.PhotoEvidence?) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val orientation = remember { Orientation(context) }
    val still = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val video = remember { VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()) }
    val dir = remember { File(context.filesDir, "streams").apply { mkdirs() } }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var finished by remember { mutableStateOf<CompletableDeferred<File>?>(null) }
    val laps by rememberUpdatedState(lap)

    /** Starts the next segment. Only one recording can run at a time, so it waits on the last. */
    fun startSegment() {
        val f = File(dir, "seg-${System.nanoTime()}.mp4")
        val done = CompletableDeferred<File>().also { finished = it }
        recording = video.output.prepareRecording(context, FileOutputOptions.Builder(f).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { e -> if (e is VideoRecordEvent.Finalize) done.complete(f) }
    }

    /** Ends the current segment and starts the next; returns the finished one. */
    suspend fun cutSegment(): File? {
        val r = recording ?: return null
        val done = finished
        r.stop()
        val f = done?.await()
        startSegment()
        return f
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var micGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }

    LaunchedEffect(Unit) { if (!micGranted) askMic.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(Unit) {
        orientation.start()
        onDispose {
            orientation.stop()
            recording?.stop()
            ProcessCameraProvider.getInstance(context).get().unbind(video, still)
        }
    }

    // Segments: every few seconds, cut one, and send it with a fresh fix (or, on the lap, just send it).
    LaunchedEffect(recording != null) {
        if (recording == null) return@LaunchedEffect
        while (true) {
            delay(FRAME_EVERY_MS)
            val fix = runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
            val seg = cutSegment() ?: continue
            val bytes = withContext(Dispatchers.IO) { seg.readBytes().also { seg.delete() } }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (laps) { onLapSegment(hash, bytes); continue }
            fix?.let { Tracking.publish(it.latitude, it.longitude, it.time, it.accuracy.toDouble()) }
            val at = fix?.let { LocationFix(GeoPoint(it.latitude, it.longitude), it.time, it.accuracy.toDouble()) }
                ?: Tracking.location.value?.copy(at = System.currentTimeMillis()) ?: continue
            onFrame(at, hash, bytes)
        }
    }

    Box(modifier.background(Color.Black)) {
        if (!micGranted) {
            Text("The challenge is spoken, so live streams need the microphone.", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, still, video)
                            startSegment()
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(16.dp)) {
            Text(if (lap) "● LIVE: VICTORY LAP" else "● LIVE", color = Color.White, fontWeight = FontWeight.Bold)
            if (!lap) challenge?.let { Text("Your challenge: \"$it\"", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Text(status, color = Color.White)
            error?.let { Text(it, color = Color.White) }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !busy, onClick = { recording?.stop(); recording = null; onFinish(null) }) {
                Text(if (lap) "End stream" else "Abandon", color = Color.White)
            }
            if (!lap && finishLabel != null) Button(enabled = !busy && recording != null, onClick = {
                busy = true
                scope.launch {
                    val fix = runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
                    val shot = File(File(context.filesDir, "photos").apply { mkdirs() }, "evidence-${System.currentTimeMillis()}.jpg")
                    val pose = orientation.pose(fix)
                    val ok = shoot(still, shot, fix, context)
                    if (!ok) { busy = false; error = "Couldn't take the still. Try again."; return@launch }
                    pose?.let { stampFacing(shot, it.azimuth) }
                    fix?.let { Tracking.publish(it.latitude, it.longitude, it.time, it.accuracy.toDouble()) }
                    busy = false
                    // Recording carries on: past the winning frame is the victory lap.
                    onFinish(services.evidence(shot, pose))
                }
            }) { Text(finishLabel) }
        }
    }
}

private suspend fun shoot(still: ImageCapture, out: File, fix: Location?, context: android.content.Context): Boolean = suspendCancellableCoroutine { k ->
    val options = ImageCapture.OutputFileOptions.Builder(out).setMetadata(ImageCapture.Metadata().apply { location = fix }).build()
    still.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(r: ImageCapture.OutputFileResults) = k.resume(true)
        override fun onError(e: ImageCaptureException) = k.resume(false)
    })
}
