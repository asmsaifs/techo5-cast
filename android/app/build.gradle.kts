// TECHO5 Cast: the app. M0 spike stage — just enough to prove the protocol module runs on Android
// itself (ART's crypto and networking, not just a desktop JVM) against a real device. The engine
// (ExoPlayer capture, the timeline, the send loop) and the rest of android-app-plan.md's screens come
// after this is proven.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.techo5.cast.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.techo5.cast.app"
        minSdk = 29 // mirror mode (AudioPlaybackCaptureConfiguration) needs this; see android-app-plan.md §2
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // org.json:json (in :protocol, for the JVM interop tests) duplicates classes Android already
    // provides on its boot classpath; without this exclusion, dexing the app fails on "duplicate
    // class org.json.*". Android's own copy is what actually runs on the device.
    configurations.all {
        exclude(group = "org.json", module = "json")
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
