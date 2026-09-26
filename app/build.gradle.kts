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
        versionCode = 2
        versionName = "1.1.0"
        vectorDrawables { useSupportLibrary = true }
        // ORT ships prebuilt .so per ABI; arm64 covers modern phones, v7a the rest.
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a")) }
    }

    buildTypes {
        release {
            // CI builds are sideloaded for testing: sign with the debug key
            // so assembleRelease emits app-release.apk (not app-release-unsigned.apk).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
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
}
