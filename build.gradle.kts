plugins {
    id("com.android.application") version "9.1.1" apply false
    // AGP 9 compile le Kotlin lui-même ; ce plugin ajoute le compilateur Compose.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
