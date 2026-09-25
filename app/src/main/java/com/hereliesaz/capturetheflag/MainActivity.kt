package com.hereliesaz.capturetheflag

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.hereliesaz.capturetheflag.data.OnboardedDirectory
import com.hereliesaz.capturetheflag.data.InMemoryBackend
import com.hereliesaz.capturetheflag.onboarding.CityOnboarding
import com.hereliesaz.capturetheflag.onboarding.OpenData
import com.hereliesaz.capturetheflag.platform.AndroidPlatformServices
import com.hereliesaz.capturetheflag.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = AndroidPlatformServices(this)
        // TODO: swap for the networked backend once chosen; the in-memory one is single-device.
        // City data is gathered on first registration from open data (OpenStreetMap, WorldPop).
        val open = OpenData()
        val cities = OnboardedDirectory(CityOnboarding(open.boundaries, open.population, open.features))
        val backend = InMemoryBackend(cities, System::currentTimeMillis)

        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )

        setContent { App(backend, platform, System::currentTimeMillis) }
    }
}
