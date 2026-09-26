plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.compressor.audio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.compressor.audio"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        vectorDrawables { useSupportLibrary = true }
        // arm64 covers all modern phones (v7a devices can't run the 63-333MB
        // ML models anyway); single ABI keeps the APK under Telegram's 50MB cap.
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildTypes {
        release {
            // CI builds are sideloaded for testing: sign with the debug key
            // so assembleRelease emits app-release.apk (not app-release-unsigned.apk).
            signingConfig = signingConfigs.getByName("debug")
            // Shrink to stay under Telegram's 50MB bot-API cap (Vosk+ORT natives).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Compose compiler for Kotlin 1.9.x (the compose Gradle plugin only exists for Kotlin 2+)
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packagingOptions {
        resources {
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Pure-JVM Opus encoder (no native libs, keeps the APK tiny).
    // Declared with explicit coordinates (not via version catalog).
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // On-device stem separation: ONNX Runtime + pure-JVM FFT (no NDK build).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.github.wendykierp:JTransforms:3.1")
    // Offline Arabic speech-to-text (prebuilt AAR incl. native libs).
    implementation("com.alphacephei:vosk-android:0.3.75")
}
