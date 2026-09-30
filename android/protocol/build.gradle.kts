// The techo5-cast protocol (docs/protocol.md): the Noise handshake, message framing and stamps.
// Plain Kotlin/JVM, no Android — so it builds and tests without a device or an emulator, and the
// engine and app modules use it the same way a JVM interop test does.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.noise.java)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.org.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // RealDeviceSmokeTest is skipped unless these are set; the test JVM is forked, so Gradle's own
    // -D flags don't reach it without being forwarded explicitly.
    listOf("realDevice", "realKey").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging {
        showStandardStreams = true
    }
}
