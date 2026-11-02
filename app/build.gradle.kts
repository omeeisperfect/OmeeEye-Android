plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nimu.omeeeye"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nimu.omeeeye"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-base"
    }

    // Shared debug keystore (see KEYSTORE_NOTE.md at the repo root).
    // If app/debug.keystore exists AND the keystore credentials are
    // provided via environment variables, debug builds are signed with
    // that shared key, so a new APK installs over the previous one
    // instead of failing with a signature mismatch. Otherwise the
    // default per-machine debug keystore is used, exactly as before.
    // No password or key material lives in this file — only env names.
    signingConfigs {
        getByName("debug") {
            val keystoreFile = file("debug.keystore")
            val storePass = System.getenv("DEBUG_KEYSTORE_PASSWORD")
            val alias = System.getenv("DEBUG_KEY_ALIAS")
            val keyPass = System.getenv("DEBUG_KEY_PASSWORD")
            if (keystoreFile.exists() && !storePass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            // Uses the debug signingConfig above (shared key when present,
            // default debug key otherwise).
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // OCR only (feature 9): on-device ML Kit text recognition. No other
    // new dependency was added for this build; image/color matching and
    // XLSX reading use only the Android framework.
    implementation("com.google.mlkit:text-recognition:2.0.0")
}
