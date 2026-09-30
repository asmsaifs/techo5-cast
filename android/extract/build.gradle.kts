// Turns a link into something the player can play. The only module that knows about yt-dlp.
// See docs/android-app-plan.md section 9.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.techo5.cast.extract"
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
}

dependencies {
    implementation(libs.youtubedl)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
