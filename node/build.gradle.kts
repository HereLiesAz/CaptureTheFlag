plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// A node: Nostr relay + referee + surveyor. See docs/DECENTRALIZED.md.
application {
    mainClass.set("com.hereliesaz.capturetheflag.node.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    // Following peer nodes: their relays and media stores.
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-websockets:3.6.0")
    // Offline speech recognition, for hearing the challenge in stream audio.
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp:0.24.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-client-websockets:3.6.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
