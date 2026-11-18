plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meraassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meraassistant"
        minSdk = 29
        targetSdk = 35
        // every GitHub build gets a bigger number → installs straight over the old app (no uninstall)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = run + 1
        versionName = "3.$run"
        buildConfigField("int", "RUN", "$run")
        buildConfigField("String", "REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: "kannuverma753-cloud/Chinu"}\"")
        // Gemini AI key GitHub Secret (GEMINI_KEY) se aati hai, repo mein kabhi nahi dikhti
        buildConfigField("String", "GEMINI_KEY", "\"${System.getenv("GEMINI_KEY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Hamesha ek hi signing key, taaki naya APK purane ke upar seedha update ho jaye
    signingConfigs {
        getByName("debug") {
            storeFile = file("chinu.jks")
            storePassword = "chinu12345"
            keyAlias = "chinu"
            keyPassword = "chinu12345"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
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
}



dependencies {
    // Offline "Hi Chinu" wake word engine (no beeps, no internet)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
