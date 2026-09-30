// TECHO5 Cast: the app. M0 spike stage — just enough to prove the protocol module runs on Android
// itself (ART's crypto and networking, not just a desktop JVM) against a real device. The engine
// (ExoPlayer capture, the timeline, the send loop) and the rest of android-app-plan.md's screens come
// after this is proven.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment (CI) or android/keystore.properties (your own machine,
// git-ignored); with neither, the release build is unsigned, which is what F-Droid wants (it signs
// its own). See docs/releasing.md.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "dev.techo5.cast.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.techo5.cast.app"
        minSdk = 29 // mirror mode (AudioPlaybackCaptureConfiguration) needs this; see android-app-plan.md §2
        targetSdk = 36
        // A release sets these from its tag (-PversionName=0.1.0 -PversionCode=100).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 100
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    val releaseStore = signingValue("CAST_KEYSTORE", "storeFile")
    if (releaseStore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signingValue("CAST_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("CAST_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("CAST_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    // One APK per CPU, plus a universal one: yt-dlp's Python runtime is most of the size, and a phone
    // only needs its own. Only for release builds, so a debug build is still one app-debug.apk.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Not shrunk: yt-dlp's runtime and the Noise library are loaded by name, and a release
            // that only works unminified is worth more here than a smaller one that might not.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // yt-dlp's Python runtime is shipped as native libraries and unpacked at install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation(project(":engine"))
    implementation(project(":protocol"))
    implementation(project(":discovery"))
    implementation(project(":extract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.zxing.embedded)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
