plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.brice.scenictrajets"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.brice.scenictrajets"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-test"
    }

    // Clé de debug fixe, versionnée : chaque compilation GitHub signe pareil,
    // donc une nouvelle version s'installe par-dessus l'ancienne.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Car App Library : interface sur l'écran de la voiture + accès aux données du véhicule
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
