import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Releases are built by HereLiesAz/workflows: android-play-release (Play) and
// android-github-release (GitHub). Both rewrite version.properties (versionMajor..versionBuild);
// Play also passes -PversionCodeOverride and -PversionName. Both hand over the upload key as
// KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD. Local builds stay unsigned.
val versions = Properties().apply { rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
val releaseCode = listOf("versionCodeOverride", "versionCode").firstNotNullOfOrNull { findProperty(it) as String? }?.toInt()
    ?: versions.getProperty("versionBuild")?.toInt()?.takeIf { it > 0 } ?: 1
val releaseName = (findProperty("versionName") as String?)
    ?: listOf("versionMajor", "versionMinor", "versionPatch").joinToString(".") { versions.getProperty(it, "0") }
val uploadKey = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "com.hereliesaz.capturetheflag"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.capturetheflag"
        minSdk = 28
        targetSdk = 37
        versionCode = releaseCode
        versionName = releaseName
    }

    signingConfigs {
        if (uploadKey != null) create("upload") {
            storeFile = uploadKey
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8: Play releases must ship a mapping.txt (the central publisher refuses one without).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadKey != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    // A transitive dependency still asks for fragment 1.1.0, too old for the Activity Result API we use;
    // release builds fail lint on it.
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
