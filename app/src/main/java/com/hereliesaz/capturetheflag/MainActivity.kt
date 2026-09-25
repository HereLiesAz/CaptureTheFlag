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
import androidx.lifecycle.lifecycleScope
import com.hereliesaz.capturetheflag.data.GameBackend
import com.hereliesaz.capturetheflag.net.MediaClient
import com.hereliesaz.capturetheflag.net.NodeBackend
import com.hereliesaz.capturetheflag.net.RelayClient
import com.hereliesaz.capturetheflag.platform.AndroidPlatformServices
import com.hereliesaz.capturetheflag.platform.Identity
import kotlinx.coroutines.launch
import com.hereliesaz.capturetheflag.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = AndroidPlatformServices(this)
        val identity = Identity(this)
        // On a node: the real, networked game. Without one: the built-in single-phone test server,
        // which gathers city data itself from open data (OpenStreetMap, WorldPop).
        val backend: GameBackend = identity.node?.let { url ->
            NodeBackend(
                identity.keys, RelayClient.open(url, lifecycleScope), lifecycleScope,
                media = MediaClient.open(url), read = platform::readMedia,
            ).also { node ->
                identity.profile?.let { (name, selfie) -> lifecycleScope.launch { node.register(name, selfie) } }
                lifecycleScope.launch { node.me.collect { u -> u?.let { identity.profile = it.displayName to (it.selfieUrl ?: "") } } }
            }
        } ?: run {
            val open = OpenData()
            InMemoryBackend(OnboardedDirectory(CityOnboarding(open.boundaries, open.population, open.features)), System::currentTimeMillis)
        }

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

        setContent {
            App(backend, platform, System::currentTimeMillis, node = identity.node) { url ->
                identity.node = url
                recreate()
            }
        }
    }
}
