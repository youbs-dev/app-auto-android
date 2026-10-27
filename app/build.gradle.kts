plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.brice.scenictrajets"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.youbs.scenictrajets"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6-test"
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
        // Clé d'envoi Google Play : fournie par les secrets GitHub (jamais dans le dépôt).
        System.getenv("UPLOAD_KEYSTORE_PATH")?.let { path ->
            create("upload") {
                storeFile = file(path)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("upload")?.let { signingConfig = it }
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
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Détection « en voiture » pour démarrer les trajets automatiquement
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
