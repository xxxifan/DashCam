plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xxxifan.dashcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xxxifan.dashcam"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    val cameraVersion = "1.7.0-alpha02"

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-video:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("com.tencent:mmkv:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
