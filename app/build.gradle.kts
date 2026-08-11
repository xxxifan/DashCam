import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: localProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)

val releaseStoreFile = releaseSigningValue("DASHCAM_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("DASHCAM_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("DASHCAM_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("DASHCAM_RELEASE_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    namespace = "com.xxxifan.dashcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xxxifan.dashcam"
        minSdk = 36
        targetSdk = 36
        versionCode = 3208
        versionName = "0.3.208"
        buildConfigField("String", "GITHUB_REPOSITORY", "\"xxxifan/DashCam\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    kotlin {
        jvmToolchain(17)
    }
}

tasks.matching { task ->
    task.name == "packageRelease" || task.name == "bundleRelease"
}.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. Set DASHCAM_RELEASE_* values in local.properties, Gradle properties, or environment variables."
        }
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
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("io.github.carguo:gsyvideoplayer-java:13.1.0")
    implementation("io.github.carguo:gsyvideoplayer-ex_so:13.1.0")
    implementation("com.tencent:mmkv:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
