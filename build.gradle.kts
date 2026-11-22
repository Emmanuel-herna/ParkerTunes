buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // BitChord's large Compose player screen needs the current D8/R8 line.
        classpath("com.android.tools:r8:8.13.23")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
    id("com.google.firebase.appdistribution") version "5.3.1" apply false
    id("io.github.takahirom.roborazzi") version "1.50.0" apply false
}
