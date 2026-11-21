plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jaygordonai.audioverse.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jaygordonai.audioverse"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-gate"
    }

    signingConfigs {
        getByName("debug") {
            // Stable debug key from the DEBUG_KEYSTORE_BASE64 GitHub secret (written by CI).
            // Without it, each CI build gets a new debug key and you must uninstall before reinstalling.
            val ks = rootProject.file("keystore/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.annotation.experimental)
    implementation(project(":core:dsp"))
    implementation(project(":core:network"))
    implementation(project(":core:playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.extractor)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
}
