import com.google.firebase.appdistribution.gradle.firebaseAppDistribution

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("com.google.firebase.appdistribution")
    id("io.github.takahirom.roborazzi")
}

val ciKeystorePath = System.getenv("PARKERTUNES_KEYSTORE_PATH")
val ciKeystorePassword = System.getenv("PARKERTUNES_KEYSTORE_PASSWORD")
val appVersionCode = System.getenv("PARKERTUNES_VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName = System.getenv("PARKERTUNES_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"

android {
    namespace = "com.parker.tunes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.parker.tunes"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }
    signingConfigs {
        if (!ciKeystorePath.isNullOrBlank()) {
            create("ciDebug") {
                storeFile = file(ciKeystorePath)
                storePassword = ciKeystorePassword
                keyAlias = "parkertunes-ci"
                keyPassword = ciKeystorePassword
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!ciKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        getByName("debug") {
            if (!ciKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
            firebaseAppDistribution {
                artifactType = "APK"
                groups = "testers"
                releaseNotes = "Plyra automated debug build"
            }
        }
    }
}

dependencies {
    implementation(project(":bitchord-shared-ui"))
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // NewPipe Extractor and its HTTP downloader.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.20")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.50.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
