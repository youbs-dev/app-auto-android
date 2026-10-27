plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.brice.scenictrajets"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.youbs.scenictrajets"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.9-test"
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
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Car App Library : interface sur l'écran de la voiture + accès aux données du véhicule
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Détection « en voiture » pour démarrer les trajets automatiquement
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Envoi vers Dawarich en arrière-plan, dès qu'Internet est disponible
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Écran du téléphone : Jetpack Compose avec Material 3 Expressive.
    // alpha24 (Compose 1.12) : les versions suivantes exigent AGP 9.1 et compileSdk 37.
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
}
