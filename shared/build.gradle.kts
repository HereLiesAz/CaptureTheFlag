plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "com.hereliesaz.capturetheflag.shared"
        compileSdk = 37
        minSdk = 28
    }
    // JVM target exists so the rules can be unit-tested without a device.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
            implementation("org.jetbrains.compose.ui:ui:1.12.1")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            // City onboarding: open-data lookups (Nominatim, WorldPop, Overpass).
            implementation("io.ktor:ktor-client-core:3.6.0")
            implementation("io.ktor:ktor-client-cio:3.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        // The node protocol (Nostr events, NIP-44, message types), shared by the phone and the node.
        // Both targets are JVMs, so it can use java.security; secp256k1 comes with each target's native half.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api("fr.acinq.secp256k1:secp256k1-kmp:0.24.0")
                implementation("io.ktor:ktor-client-websockets:3.6.0")
            }
        }
        androidMain.get().dependsOn(jvmCommonMain)
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.dependencies { implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0") }
        jvmMain.dependencies { implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:3.6.0")
        }
    }
}
