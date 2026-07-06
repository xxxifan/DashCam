# DashCam

Android dashcam app built with CameraX / Camera2 and MMKV for settings persistence.

## Shell

Use **PowerShell 7** (`pwsh`) for all shell commands. Do not use bash or cmd.

## Build & Install

```powershell
# Install debug build to connected device
.\gradlew.bat installDebug

# Build without installing
.\gradlew.bat assembleDebug
```

## Project Structure

- `app/src/main/java/com/xxxifan/dashcam/`
  - `camera/` — CameraX preview, camera options, capabilities
  - `data/` — Settings models, stores (MMKV), recording models
  - `recording/` — Recording service, state, quality resolver
  - `storage/` — Loop storage, estimator
  - `device/` — Device display name resolver
  - `safety/` — Recording safety policy

## Key Files

- `data/RecordingModels.kt` — `RecordingSettings` data class, zoom ratio constants
- `data/RecordingSettingsStore.kt` — MMKV-backed settings persistence
- `camera/PreviewController.kt` — CameraX preview binding with crop zoom
- `camera/CameraCapabilitiesRepository.kt` — Enumerates available cameras
- `recording/RecordingService.kt` — Background recording service
