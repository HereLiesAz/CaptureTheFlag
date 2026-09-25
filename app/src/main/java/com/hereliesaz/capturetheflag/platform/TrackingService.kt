package com.hereliesaz.capturetheflag.platform

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.model.LocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that keeps fused location flowing while a game is live, including with
 * the screen off. Fixes land in [Tracking.location]; the game layer reports them to the server.
 */
class TrackingService : Service() {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { Tracking.publish(it.latitude, it.longitude, it.time, it.accuracy.toDouble()) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(RadioNotification.ID, RadioNotification.build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000).setMinUpdateIntervalMillis(5_000).build(),
            callback,
            Looper.getMainLooper(),
        )
        return START_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, TrackingService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, TrackingService::class.java))
    }
}

/** Process-wide latest fix, fed by [TrackingService] and one-shot requests. */
object Tracking {
    private val _location = MutableStateFlow<LocationFix?>(null)
    val location: StateFlow<LocationFix?> = _location

    fun publish(lat: Double, lng: Double, at: Long, accuracyM: Double) {
        _location.value = LocationFix(GeoPoint(lat, lng), at, accuracyM)
    }
}
