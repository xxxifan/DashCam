# DashCam

**EN** | [CN](README_CN.md)

[![Android](https://img.shields.io/badge/Android-API%2036%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

DashCam is an open-source app that turns an Android phone into a dashboard camera. Built with CameraX, it provides segmented recording, loop storage, automatic quality selection, safety-based quality reduction, local video management, and access to external dash cams.

> [!IMPORTANT]
> This project is currently at an early experimental stage and is intended primarily for personal use, learning, and further development. Restrictions on background camera access, lock-screen recording, encoders, and thermal management vary significantly between manufacturers. Test the app thoroughly on your target device before using it while driving.

## Features

### Dash cam recording

- Records continuously through a foreground service with a persistent notification.
- Saves recordings in 1, 2, 3, 5, or 10-minute segments.
- Automatically removes the oldest segments when the storage quota or safe free-space threshold is reached.
- Supports the main rear and ultra-wide cameras, with 1.0x to 2.0x center-crop zoom.
- Supports 720p, 1080p, and 4K; 24, 30, and 60 fps; and H.264 or H.265, depending on device capabilities.
- Supports HDR, video stabilization, optional audio, and space-saving, standard, or high-quality presets.
- Automatic quality selection can adjust resolution, frame rate, codec, quality, HDR, and stabilization based on device capabilities and available space.

### Safety and post-processing

- Automatically removes old recordings or reduces quality when storage is low.
- Monitors device temperature, battery level, and recording pipeline health, then warns, reduces quality, or stops recording when necessary.
- Reserves 10% of system storage by default.
- Analyzes audio after recording and applies noise reduction only for clearly detected low-frequency wind noise, resonance, and high-frequency broadband noise.
- Noise reduction runs only while the app is visible, the screen is on, and no recording or playback is active. A processing failure never overwrites the original video.

### Video management

- Browse, play, and continuously play local recordings.
- Delete individual videos or clean up recordings in batches by age.
- Export videos to `Movies/DashCam` in the system media library.
- Share videos through the Android system share sheet.

### External dash cams

- Currently supports the **Aieryou DC1**.
- Provides live preview, categorized remote recording lists, online playback, and resumable downloads.
- Records and exports device connection and operation logs for troubleshooting.
- Other brands and models are not yet supported and will be added gradually.

## Quick start

### Requirements

- Android Studio, or a working Android Gradle command-line environment.
- JDK 17.
- Android SDK 36.
- An Android device running API 36 or later.
- A physical device for validating camera, encoder, thermal, and lock-screen recording behavior.

The current app version is `0.1.0`, with the following project configuration:

```kotlin
compileSdk = 36
minSdk = 36
targetSdk = 36
```

### Build

```powershell
git clone https://github.com/xxxifan/DashCam.git
cd DashCam
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Install

After connecting an Android device with USB debugging enabled, run:

```powershell
.\gradlew.bat installDebug
```

You can also open the project in Android Studio and run the `app` module directly.

## Usage

### First recording

1. Grant the required camera and notification permissions. Grant microphone permission only if audio recording is enabled.
2. For an initial test, start with `1080p30 + H.265 + Standard`.
3. Set a loop-recording quota based on available space, then record several complete segments to confirm device compatibility.
4. Connect a car charger for long recordings and monitor the device temperature.

If the camera's field of view is too wide, try 1.2x or 1.4x crop zoom. If recording becomes unstable after locking the screen, keep the app in the foreground or the screen on, and try reducing the frame rate or disabling HDR or enhanced stabilization.

### Connect an external dash cam

1. Connect to the Aieryou DC1 Wi-Fi network in Android system settings.
2. Open the **Devices** tab in DashCam.
3. Wait for the app to discover the device, then use live preview, remote playback, or download features.

Only the Aieryou DC1 is currently supported. Other devices are not guaranteed to work even if they use a similar connection method.

## File storage

Videos recorded by the phone are stored in the app-specific external directory by default:

```text
Android/data/com.xxxifan.dashcam/files/Movies/DashCam/records
```

The `.nomedia` file in this directory prevents unexported videos from appearing automatically in the system gallery. Exported videos are copied to:

```text
Movies/DashCam
```

Files downloaded from an external dash cam are stored in:

```text
Downloads/DashCam
```

`Downloads/DashCam` is a public directory that can also be accessed by other apps with permission to read system downloads.

When saving TS videos from a dash cam, you can keep the original TS file or losslessly remux it to MP4 for better system gallery compatibility. The conversion process writes only the MP4 file to the public directory and removes the temporary TS file from the app cache afterward.

> [!WARNING]
> Unexported recordings made by the phone remain in the app-specific directory and may be deleted when the app is uninstalled. Export recordings that need to be kept long-term.

## Permissions

| Permission | Purpose |
| --- | --- |
| Camera | Record video |
| Microphone | Capture audio when audio recording is enabled |
| Notifications | Display the persistent recording notification |
| Foreground service | Keep the foreground service active while recording |
| Wake lock | Help keep recording stable |
| Network and Wi-Fi state | Discover and connect to external dash cams |
| Internet | Preview, play, and download content from external dash cams |

Some systems also restrict background activity, lock-screen camera access, or high-power workloads. Allow DashCam to run in the background and disable overly aggressive battery optimization for the app when necessary.

## Technology stack

- Kotlin
- Jetpack Compose / Material 3
- CameraX VideoCapture / Camera2Interop
- Media3 ExoPlayer / RTSP / Transformer
- MMKV
- Kotlin Coroutines
- Gradle Kotlin DSL

## Project structure

```text
app/src/main/java/com/xxxifan/dashcam
├── camera/       # Camera capabilities, lens selection, and preview binding
├── data/         # Settings, recording metadata, thumbnails, and event logs
├── device/       # Device information and external dash cam integration
├── recording/    # Recording service, quality policy, audio post-processing, and recording state
├── safety/       # Storage, temperature, battery, and recording pipeline safety policies
├── storage/      # Loop-recording space estimation and cleanup
└── MainActivity.kt
```

## Diagnostic logs

The app writes recording event logs to its private directory to help diagnose device capabilities, recording parameters, segment results, storage cleanup, and abnormal stop reasons. Connection and operation logs for external dash cams are stored at:

```text
files/device_logs/device-events-YYYYMMDD.log
```

Logs are never uploaded automatically. Before attaching logs to an issue, check them for local file paths, device information, or anything else you do not want to disclose.

## Known limitations

- Lock-screen recording stability depends on the device manufacturer, battery policy, and system version.
- CameraX target frame rates are requests and do not guarantee that every device will produce the exact requested frame rate.
- HDR, stabilization, physical lens selection, and H.265 support depend on device capabilities.
- Only the Aieryou DC1 external dash cam is currently supported.
- The project does not yet include Play Store publishing configuration, release signing configuration, or an official release package.
- The minimum supported system version is API 36, so older Android devices cannot install the app directly.

## Roadmap

- Support more external dash cam brands and models.
- Validate compatibility on more Android devices.
- Add diagnostics comparing requested recording parameters with actual video metadata.
- Improve the interface for landscape and in-car use.
- Add background stability tests, release builds, and automated checks.

The roadmap represents the current direction and does not promise release dates. Discussions are welcome in [Issues](https://github.com/xxxifan/DashCam/issues).

## Contributing

Issues and pull requests are welcome. For larger changes, consider opening an issue first to describe the requirement and proposed approach, which helps avoid duplicated work.

When reporting recording problems, include as much of the following as possible:

- Phone model and Android version.
- Recording settings, including resolution, frame rate, codec, quality, HDR, stabilization, and crop zoom.
- Whether the screen was locked, the device was charging, or the device was noticeably hot when the problem occurred.
- Relevant diagnostic logs with sensitive information removed.

When reporting external dash cam problems, also include the device model, firmware version, reproduction steps, and sanitized device logs.

## License

This project is licensed under the [Apache License 2.0](LICENSE).
