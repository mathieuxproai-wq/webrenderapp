plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hyperframe.render"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hyperframe.render"
        // Garde-fou 4 : compileSdk 36 / targetSdk 36 / minSdk 29
        // (minSdk 29 requis par MediaStore.Downloads ; targetSdk 36 évite
        //  le blocage réseau local introduit au SDK 37 / ACCESS_LOCAL_NETWORK)
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-phase1"
    }

    // PoC headless strict : pas de Compose, pas de bibliothèque tierce lourde.
    buildTypes {
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
    // Garde-fou 2 : dépendance minimale unique (pas de Jetpack Compose,
    // pas de Chromium embarqué, pas de GeckoView, pas de Crosswalk, pas de FFmpeg).
    implementation("androidx.core:core-ktx:1.8.0")
}
