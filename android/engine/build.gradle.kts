// The engine: turns a video or audio file into what the device understands (JPEG frames and 48 kHz
// stereo PCM, stamped on one clock) and sends it. See docs/android-app-plan.md section 5.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.techo5.cast.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // The engine is built on Media3's audio-processor and frame-metadata hooks, which are marked
        // unstable: using them is the point, and media3 is pinned in the version catalog.
        disable += "UnsafeOptInUsageError"
    }

    // Same as :app: Android provides org.json itself.
    configurations.all {
        exclude(group = "org.json", module = "json")
    }
}

dependencies {
    api(project(":protocol"))
    api(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
}
