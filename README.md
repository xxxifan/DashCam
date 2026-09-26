<h1 align="center">DashCam</h1>

<p align="center">
  <img src="docs/images/icon.svg" width="96" height="96" alt="DashCam app icon">
</p>

<p align="center">Turn your Android phone into a dash cam.</p>

<p align="center">
  <strong>English</strong> · <a href="README_CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android 16+"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="Apache 2.0 license"></a>
  <a href="https://github.com/xxxifan/DashCam/stargazers"><img src="https://img.shields.io/github/stars/xxxifan/DashCam?style=flat" alt="GitHub stars"></a>
  <a href="https://github.com/xxxifan/DashCam/fork"><img src="https://img.shields.io/github/forks/xxxifan/DashCam?style=flat" alt="GitHub forks"></a>
</p>

<p align="center">
  <a href="https://github.com/xxxifan/DashCam/releases">Releases</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="https://github.com/xxxifan/DashCam/issues">Feedback</a> ·
  <a href="#contributing">Contribute</a>
</p>

## About

DashCam is an open-source Android dash cam app for recording with your phone and accessing footage from an external dash cam. It combines CameraX recording, automatic segmentation, loop storage, and local video management, with quality adjustments based on device capabilities and available resources.

## Preview

| Recording preview | Lens settings | Video quality |
| :---: | :---: | :---: |
| <img src="docs/images/recording.png" width="250" alt="Recording home screen with a scenic road preview"> | <img src="docs/images/settings-lens.png" width="250" alt="Lens, focus, and crop zoom settings"> | <img src="docs/images/settings-quality.png" width="250" alt="Resolution, frame rate, codec, and segment settings"> |

<sub>Captured on a Pixel 10a. Only the camera preview was replaced with an AI-generated landscape for illustration.</sub>

## Main features

- **Record in a loop** — continuous foreground recording, 1–10 minute segments, and automatic cleanup of old footage.
- **Flexible video settings** — 720p / 1080p / 4K, 24 / 30 / 60 fps, H.264 / H.265, HDR, stabilization, lens selection, focus, and crop zoom, depending on your device.
- **Automatic quality and safety** — select quality based on device capabilities and available space; monitor storage, temperature, battery, and recording health to reduce quality or stop when needed. Reserve 10% storage by default.
- **Audio and noise reduction** — optional audio recording and post-recording noise reduction for detected wind noise and other supported noise types.
- **Keep and share footage** — thumbnails, continuous playback, batch cleanup, export, and sharing.
- **Connect a dash cam** — Aieryou DC1 live preview, remote playback, and resumable downloads, with optional TS-to-MP4 remuxing.
- **Troubleshooting** — recording event logs and external device diagnostics help trace connection failures and unexpected recording stops.

Built with Kotlin, Jetpack Compose, CameraX, Media3, and MMKV.

## Quick start

**Requires Android 16+ and an ARM64 device.** Browse [Releases](https://github.com/xxxifan/DashCam/releases) for published builds, or build from source below.

1. Open the app and grant camera, notification, and optional microphone permissions.
2. Choose recording quality and storage settings, then tap **Start recording**.
3. Browse your footage in **Videos**, or connect to the Aieryou DC1 Wi-Fi and open **Devices**.

Phone recordings stay in app-specific storage until exported. Export important footage before uninstalling. Lock-screen recording and advanced camera options vary by device; test your setup before a long drive.

<details>
<summary><strong>Build from source</strong></summary>

Use Android Studio with JDK 17 and Android SDK 36. On Windows:

```powershell
git clone https://github.com/xxxifan/DashCam.git
cd DashCam
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Enable USB debugging before installation. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Release builds need your own signing key and the `DASHCAM_RELEASE_*` settings. See [app/build.gradle.kts](app/build.gradle.kts) for build configuration.

</details>

## Contributing

**Stars and Forks are welcome!** Star the project to show your support, or [Fork it](https://github.com/xxxifan/DashCam/fork) and make it your own. Bug fixes, docs, and device compatibility feedback are all appreciated.

- **Found a bug?** [Open an issue](https://github.com/xxxifan/DashCam/issues) with your device, Android version, and steps to reproduce. Include recording settings or dash cam firmware when relevant.
- **Have an improvement?** Submit a pull request with a short description and test results. Discuss larger changes in an issue first.

## License

[Apache License 2.0](LICENSE)
