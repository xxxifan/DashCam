# DashCam Android App Agent Plan

## Conversation Rules

- Respond to the user in Chinese.
- Use UTF-8 without BOM.
- On Windows, use PowerShell-compatible commands.
- When editing files, use the built-in `functions.apply_patch` tool.
- Follow the project's formatter once the Android project exists.

## Product Goal

Build an Android dashcam app that turns a Pixel 10a-class phone into a reliable vehicle recorder.

Confirmed product defaults:

- Package name: `com.xxxifan.dashcam`.
- App display name: `DashCam`.
- Target device baseline: Pixel 10a.
- Minimum OS baseline: Android 16 / API 36, matching Pixel 10a's launch Android version.
- Default audio recording: on.
- Default segment duration: 2 minutes.
- Default quality: 720p30 standard bitrate.
- Default codec: auto, where auto is an app-level profile selector instead of simply leaving CameraX unset.
- Default storage mode: use maximum allowed recording space while reserving 10% of the storage volume.
- Default resource-pressure auto-downgrade: on.
- v1 does not include manual lock/protect-current-segment.

Primary goals:

- Record video continuously as a dashcam.
- Prefer user-started lock-screen/screen-off recording for power saving.
- Fall back to a lowest-brightness black-screen mode when lock-screen recording is unavailable or unstable.
- Support loop recording within a user-defined storage quota, or all usable free space with a safety reserve.
- Support user-selectable stabilization, resolution, segment duration, storage quota, and as many supported video options as Android exposes.
- Store recordings in an app-controlled folder that is hidden from gallery apps by default.
- Provide recording review, export, and share features.
- Reserve an entry point for future video clipping/editing.

## Non-Negotiable Safety And Privacy Boundaries

- Recording must be explicitly started by the user from the foreground UI.
- Recording must run with a visible foreground service notification.
- Do not implement hidden, stealth, or silent camera behavior.
- Do not attempt to restart camera or microphone capture from a fully background state without explicit user interaction.
- Lock-screen recording is allowed as the preferred mode only after the foreground start path has created the foreground service.
- If the system, user, or OEM restrictions stop the service, the app may notify the user but must not bypass platform restrictions.

## Technical Direction

Recommended stack:

- Kotlin.
- Jetpack Compose.
- Material 3.
- Android 16 / API 36 minimum, unless this is later broadened deliberately.
- CameraX as the primary recording API.
- Camera2 interop/capability query for advanced device features.
- MMKV for settings and lightweight recording metadata.
- Media3/ExoPlayer for playback.
- FileProvider for sharing private recording files.
- MediaStore for exporting recordings to user-visible media folders.

Persistence decision:

- Use MMKV for v1 settings and lightweight recording metadata.
- The app's v1 metadata needs are simple: persist settings, maintain a recording list, and store each segment's basic metadata.
- Keep the MMKV schema intentionally small and versioned.
- Suggested shape: store settings as keyed values, store recording IDs in a reverse-chronological list key, and store each recording metadata item under `recording:{id}` as serialized JSON or another small structured value.
- Reconsider Room only if later requirements add complex filtering, relational joins, large-scale statistics, or more demanding migrations.

Camera implementation:

- Use CameraX `Recorder`, `VideoCapture<Recorder>`, `QualitySelector`, `FileOutputOptions`, and `VideoRecordEvent`.
- Use Camera2 characteristics to discover actual device support for stabilization, fps, codec, dynamic range, physical cameras, and other advanced options.
- Never hard-code Pixel 10a feature combinations as guaranteed. Use Pixel 10a as an optimization target, then filter all options at runtime.

## Recording Modes

### Preferred Mode: Lock-Screen Recording

Flow:

1. User opens the app.
2. User taps start recording.
3. App shows a pre-recording confirmation dialog with lock-screen recording and notification guidance.
4. App validates camera, notification, and optional microphone permissions.
5. App starts `RecordingService` while the UI is foreground.
6. Service immediately becomes a foreground service.
7. User may press the power button to lock the screen.
8. Recording continues from the foreground service.

Foreground service type:

- Video only: `camera`.
- Video with audio: `camera|microphone`.

Expected controls:

- Persistent notification shows recording state.
- Notification actions should include stop recording and open app.
- A lock/protect-current-segment action is reserved for after v1.
- The app should not try to force-lock the screen. Show UI copy that indicates the user can now press the power button.

### Fallback Mode: Low-Brightness Black Screen

Use this when lock-screen recording is not available, is unstable on a specific combination, or the user prefers screen-on operation.

Behavior:

- Keep the recording Activity foreground.
- Set window brightness to the lowest practical value.
- Show a full black UI with minimal status/controls.
- Provide a button to toggle between visible preview and black-screen mode.
- Keep the foreground service notification active while recording.

## Core Architecture

Suggested components:

- `RecordingService`
  - Owns the recording lifecycle.
  - Starts/stops CameraX recording.
  - Handles segment rotation.
  - Emits recording state.
  - Hosts health monitoring and safety decisions.

- `CameraRecordingController`
  - Binds CameraX use cases.
  - Applies selected camera/video settings.
  - Starts and finalizes each segment.
  - Converts CameraX events into app-level events.

- `CameraCapabilitiesRepository`
  - Queries CameraX and Camera2 capabilities.
  - Produces supported options for UI.
  - Filters unsupported combinations.

- `RecordingSettingsRepository`
  - Stores resolution, fps, codec, stabilization, audio, segment duration, storage quota, and safety policy settings.

- `RecordingRepository`
  - Tracks saved segments and metadata.
  - Reserves lock/protect metadata for after v1.
  - Supports list, delete, export, and share operations.

- `RecordingThumbnailManager`
  - Generates thumbnails for completed `READY` segments only.
  - Uses platform video thumbnail APIs off the main thread.
  - Maintains an app-private disk cache keyed by recording ID, source file fingerprint, and thumbnail schema version.
  - Coalesces duplicate requests and limits concurrent decoding so library scrolling cannot spawn repeated full-video work.
  - Deletes cached thumbnails when the source recording is deleted, and periodically cleans orphan thumbnail files.

- `LoopStorageManager`
  - Creates the hidden recording directory.
  - Enforces storage quota.
  - Deletes oldest deletable recordings when quota is exceeded.
  - In v1, all recordings are deletable unless another rule explicitly excludes them.
  - After lock/protect is implemented, protected recordings must be excluded from automatic deletion.
  - Keeps a minimum system safety reserve.

- `RecordingHealthMonitor`
  - Aggregates thermal, storage, battery, and recording pipeline signals.
  - Emits `RecordingHealthSnapshot`.

- `RecordingSafetyPolicy`
  - Public/open extension point.
  - Evaluates health snapshots and emits safety decisions.
  - Must remain decoupled from UI, notification, sound, and TTS implementation details.

- `RecordingSafetyEventSink`
  - Consumes safety decisions.
  - Initial sinks: notification, in-app UI, log.
  - Future sinks: voice/TTS, alert sound, vibration, car mode, Bluetooth output.

## Public Safety Policy Design

Keep this boundary stable for future voice and sound alerts.

Example shape:

```kotlin
interface RecordingSafetyPolicy {
    fun evaluate(snapshot: RecordingHealthSnapshot): RecordingSafetyDecision
}

data class RecordingSafetyDecision(
    val level: SafetyLevel,
    val actions: Set<SafetyAction>,
    val reasons: Set<SafetyReason>,
    val message: String,
    val shouldNotifyUser: Boolean,
)

interface RecordingSafetyEventSink {
    fun onSafetyDecision(decision: RecordingSafetyDecision)
}
```

The policy should only decide what should happen.

Execution should stay elsewhere:

- `RecordingService` applies quality downgrade, pause, stop, or restart decisions.
- Notification/UI/log sinks inform the user.
- Future TTS or sound sinks can be added without modifying recording core logic.

Expose safety decisions as a stream, such as `Flow<RecordingSafetyDecision>`, so multiple consumers can subscribe.

## Resource Pressure Auto-Downgrade

Add a user setting:

- Name: `资源紧张时自动降低视频质量`
- Recommended default: enabled.

Behavior:

- Monitor temperature, storage, and battery.
- Build a combined safety level from those dimensions.
- If enabled, automatically reduce video quality when resources are tight.
- If disabled, do not reduce quality automatically.
- Regardless of this setting, emergency states must stop recording to protect device safety and file integrity.

Suggested levels:

- `Normal`: no action.
- `Notice`: notify only.
- `Pressure`: downgrade if auto-downgrade is enabled; otherwise notify only.
- `Emergency`: stop recording in both modes.

Suggested thermal mapping:

- `THERMAL_STATUS_LIGHT`: `Notice`.
- `THERMAL_STATUS_MODERATE`: `Pressure`.
- `THERMAL_STATUS_SEVERE`: strong pressure, downgrade aggressively.
- `THERMAL_STATUS_CRITICAL` and above: `Emergency`.

Suggested battery mapping:

- Charging: reduce battery pressure weight, but continue thermal monitoring.
- Not charging and below 20%: `Notice`.
- Not charging and below 10%: `Pressure`.
- Not charging and below 5%: `Emergency`.

Suggested storage mapping:

- Near configured quota: delete oldest deletable recordings.
- Still low after cleanup: `Pressure`.
- Below minimum safety reserve or unable to create valid next segment: `Emergency`.

Suggested downgrade order:

1. Disable HDR, 10-bit, or high dynamic range features.
2. Disable enhanced stabilization, or downgrade to standard stabilization.
3. Reduce 60fps to 30fps.
4. Reduce 4K to 1080p.
5. Switch HEVC/H.265 to H.264 if encoder instability is detected.
6. Reduce 1080p to 720p30 as the last quality-preserving fallback.

Initial recovery policy:

- Do not automatically restore higher quality in v1.
- Allow the user to manually restore configured quality.
- Consider automatic recovery only after long stable periods in a later version.

## Health Monitoring Signals

Thermal:

- Use `PowerManager.getCurrentThermalStatus()`.
- Use `PowerManager.addThermalStatusListener()`.
- On supported API levels, consider `getThermalHeadroom()` for early pressure prediction.

Battery:

- Use `BatteryManager`.
- Listen to `ACTION_BATTERY_CHANGED`.
- Listen to `ACTION_BATTERY_LOW`.
- Listen to `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED`.

Storage:

- Use app directory stats and platform storage APIs.
- Check available bytes before starting a segment.
- Check periodically during recording, but avoid excessive polling.
- Use CameraX recording stats, such as recorded bytes, to detect abnormal output.

Recording pipeline:

- Use `VideoRecordEvent.Status` for ongoing stats.
- Use `VideoRecordEvent.Finalize` for final result and errors.
- Handle insufficient storage, encoder failure, source inactive, no valid data, and unknown finalization errors.

## Storage And File Visibility

Default recording location:

- App-specific external files directory.
- Suggested path: `Android/data/<package>/files/Movies/DashCam/records`.
- Create `.nomedia` in the recording directory.

Behavior:

- Recordings are hidden from gallery apps by default.
- Export copies selected files into MediaStore, such as `Movies/DashCam`.
- Export means saving a copy to user-visible public external media storage, not asking another app to open the private recording file.
- On Android 16/API 36, use MediaStore insertion with pending/complete state rather than broad storage permissions or raw public path writes.
- The export flow should handle duplicate filenames, partial-copy cleanup, success/error feedback, and exported metadata updates.
- Sharing uses FileProvider `content://` URIs.
- Do not expose raw private file paths to other apps.

Suggested filename:

```text
dashcam_yyyyMMdd_HHmmss_1080p60_h265.mp4
```

Metadata per segment:

- File URI/path.
- Start time.
- End time.
- Duration.
- Size.
- Resolution.
- Fps.
- Codec.
- Stabilization mode.
- Audio enabled.
- Safety downgrade state, if any.
- Segment state: `RECORDING`, `FINALIZING`, `READY`, or `FAILED`.
- Thumbnail cache path, generation state, thumbnail schema version, and source file fingerprint when available.
- Locked/protected flag reserved for after v1.
- Exported flag.

Segment state behavior:

- `RECORDING`: current file is still being written. It must not be treated as a normal library item.
- `FINALIZING`: CameraX or the encoder is closing and validating the file. It remains read-only until finalization succeeds or fails.
- `READY`: recording is complete, metadata is valid, and normal playback/delete/export/share operations are allowed.
- `FAILED`: recording did not produce a valid segment. Show a clear state and allow safe cleanup only.
- Thumbnail generation, duration probing, playback, export, share, delete, loop deletion, and multi-select must only operate on `READY` segments unless a future feature explicitly defines another safe path.

## Video Thumbnails

Requirements:

- Generate thumbnails for completed recordings and show them in the recording library.
- Generate thumbnails only after a segment reaches `READY`.
- Never decode video frames from a `RECORDING` or `FINALIZING` segment.
- Thumbnail failure must not block recording, playback, export, share, or deletion.
- Missing or failed thumbnails should fall back to a stable placeholder while allowing retry later.

Generation strategy:

- Prefer platform APIs such as `ThumbnailUtils.createVideoThumbnail(file, Size, CancellationSignal)` because the app targets API 36+.
- Request the bitmap at the UI target size instead of decoding a full-resolution frame.
- Suggested list thumbnail target: 320x180 pixels for 16:9 recordings.
- If a detail/playback screen needs a larger still later, generate a separate larger cache variant instead of upscaling list thumbnails.
- Choose a stable frame slightly after the beginning when possible, such as around 1 second or 10% into the video, to avoid all-black startup frames.
- Verify rotation/orientation handling on device; regenerate or transform thumbnails if platform output does not match playback orientation.
- Run generation on `Dispatchers.IO` from service/repository work, never from Compose composition or the main thread.
- Use bounded concurrency, initially one thumbnail decode at a time, because video frame extraction can be CPU, IO, and codec heavy.
- Coalesce duplicate generation requests for the same recording ID.
- Add cancellation for UI-driven backfill jobs when the library screen leaves composition, while allowing post-finalize generation to complete in the background.

Cache strategy:

- Store thumbnails in an app-private thumbnail cache directory, such as `cache/recording_thumbnails`.
- Treat thumbnails as regenerable derived data; the recording file and metadata remain the source of truth.
- Cache keys should include recording ID, source file length, source last-modified timestamp, and thumbnail schema version.
- Persist thumbnail metadata in MMKV alongside recording metadata, or derive it deterministically from the cache key if the file exists.
- Regenerate thumbnails when the source fingerprint or schema version changes.
- Delete the thumbnail file when its recording is deleted.
- Periodically remove orphan thumbnails whose source recording metadata no longer exists.

Display performance:

- The library item should reserve a fixed 16:9 thumbnail slot so loading state never shifts row height.
- Load thumbnail image files asynchronously and decode at display size.
- Do not hand a video file URI directly to the list image loader if that causes repeated frame extraction during scrolling.
- Backfill thumbnails lazily for older recordings, prioritizing visible and near-visible items.
- Avoid eager generation for the entire library on app startup.
- Keep placeholders visually lightweight: icon or neutral surface, not a spinner per row.
- Use stable item keys in lazy lists so thumbnail state is reused during scroll and date grouping changes.
- Keep memory pressure low by caching small bitmap files and relying on the image loader's normal in-memory cache for currently visible rows.

## Loop Recording

Requirements:

- Segment duration must be user-selectable.
- Recording should rotate to a new file when the segment duration is reached.
- Default segment duration is 2 minutes.
- Storage quota can be a fixed size, custom size, or all usable free space.
- Default storage quota uses the maximum safe recording space.
- Always reserve 10% of the storage volume for the system and other apps.
- For example, a 256GB device should reserve about 25GB.
- Custom quota options should range from 2GB to `currently available space - 10% reserve`.
- If the computed maximum quota is below 2GB, disable high-resolution and high-quality options and try the lowest viable recording profile.
- If even the lowest viable recording profile cannot safely start, block recording and show a storage-insufficient error.
- When quota is exceeded, delete the oldest deletable recordings first.
- Lock/protect-current-segment is not included in v1.
- After lock/protect is implemented, protected recordings must not be deleted automatically.
- If cleanup cannot recover enough space, stop recording and notify the user.

Segment duration rationale:

- 2 minutes is the default balance for dashcam use.
- Shorter segments reduce data loss if the app, encoder, battery, or storage fails during finalization.
- Shorter segments also make loop deletion and sharing more manageable.
- Extremely short segments create more files, more metadata churn, and more frequent encoder restarts.
- Longer segments reduce file count but increase the worst-case loss window.
- Recommended user options: 1, 2, 3, 5, and 10 minutes.
- Keep 2 minutes as the default for long-running reliability and reasonable file management.

## Settings To Support

Core v1 settings:

- Recording mode: lock-screen preferred / black-screen fallback.
- Resolution: auto, 720p, 1080p, 4K where supported. Default: 720p.
- Fps: auto, 24, 30, 60 where supported.
- Codec: auto, H.264, H.265/HEVC, and any other supported codec exposed by the platform. Default: auto.
- Bitrate/quality preset: auto, space saver, standard, high quality, and possibly custom. Default: standard.
- Audio: off/on. Default: on.
- Stabilization: off, standard, enhanced/preview stabilization where supported. Default: standard.
- Pixel-oriented stabilization UI should expose three choices when supported: off, standard, and enhanced/active.
- Segment duration. Default: 2 minutes.
- Storage quota. Default: maximum safe recording space with 10% storage reserve.
- Auto-downgrade under resource pressure. Default: on.

Advanced settings to consider:

- Lens selection.
- Zoom.
- Torch.
- Exposure compensation.
- White balance / AWB lock if exposed safely.
- Focus mode if exposed safely.
- HDR/HLG/10-bit options where supported.
- Bitrate preset or quality profile if CameraX/Media APIs expose a stable path.

## Bitrate And Auto Quality Policy

CameraX `Recorder.Builder` exposes target video/audio bitrate APIs in the current dependency version, so v1 should prefer recording-time bitrate control over post-recording compression.

Why H.265 may be larger than H.264 in real tests:

- H.265 only improves size when the encoder, selected profile, scene content, bitrate control mode, and quality target are comparable.
- Device encoders may choose different default bitrate ladders for H.265 and H.264.
- CameraX/MediaRecorder profile defaults may prioritize quality or encoder stability over minimum file size.
- Complex road scenes, motion, noise, HDR, stabilization, or low light can push H.265 to a higher bitrate.
- Therefore, codec choice alone must not be treated as a guaranteed storage-saving option. Actual segment size should be measured and used to calibrate future estimates.

Suggested bitrate preset table:

| Preset | 720p30 | 1080p30 | 1080p60 | 4K30 | Intent |
| --- | ---: | ---: | ---: | ---: | --- |
| Space saver | 4 Mbps | 8 Mbps | 14 Mbps | 28 Mbps | Longest loop duration |
| Standard | 8 Mbps | 16 Mbps | 28 Mbps | 55 Mbps | Recommended dashcam balance |
| High quality | 12 Mbps | 25 Mbps | 42 Mbps | 80 Mbps | Detail priority |

Auto quality selection:

- Auto is an app-level selection mode, not merely CameraX "unset".
- Target minimum recordable duration: 8 hours.
- Evaluate supported profiles against the currently configured loop quota / remaining safe recording space.
- Candidate dimensions include resolution, fps, bitrate preset, codec, HDR, and stabilization compatibility.
- Prefer the best candidate whose estimated recordable duration is at least 8 hours.
- If multiple candidates satisfy 8 hours, rank by overall quality:
  - Prefer higher resolution first.
  - Then prefer higher fps where useful and supported.
  - Then prefer higher bitrate preset.
  - Then prefer HDR/dynamic range if it remains compatible and does not break the 8-hour target.
- Example: if standard 1080p30 and high-quality 720p30 both meet 8 hours, choose standard 1080p30 because resolution wins before bitrate preset.
- If no candidate can reach 8 hours, choose the lowest viable profile rather than blocking immediately.
- Current preferred default profile before auto tuning is implemented: 720p30 standard bitrate.
- Persist the user's explicit selections separately from the auto-resolved active profile, so the UI can explain what Auto selected.

## UI Plan

Screens:

- Home/recording control screen.
- Recording preview screen.
- Black-screen recording screen.
- Settings screen.
- Recording library screen.
- Fullscreen playback/detail screen.
- Future clipping/editing entry screen.

UI implementation:

- Use Compose-only UI for v1.
- Do not introduce XML layouts unless a platform component absolutely requires it.

Home should show:

- Start/stop recording.
- Current mode.
- Current quality.
- Stabilization state.
- Available recording time estimate.
- Last safety warning, if any.

Recording notification should show:

- Recording active state.
- Elapsed time.
- Current segment quality.
- Resource pressure warning if active.
- Stop action.
- Open app action.
- Future lock-current-segment action after v1.

Library should support:

- Reverse chronological ordering.
- Date grouping with sticky headers.
- A read-only active-recording status row when recording is in progress.
- The active status row should show `Recording`/`Finalizing` state and live elapsed time from `RecordingService`, not from repeatedly probing the partially written media file.
- The unfinished current segment must not appear as a normal recording item before it becomes `READY`.
- `RECORDING` and `FINALIZING` segments must not allow playback, delete, export, share, or multi-select actions.
- Thumbnail with fixed 16:9 slot, generated/cached only for `READY` segments.
- File duration, size, and quality.
- Playback.
- Delete.
- Lock/protect is reserved for after v1.
- Export.
- Share.
- Multi-select later if not in first slice.

Playback should support:

- Tapping a `READY` recording opens a full-screen playback screen instead of an in-dialog preview.
- Use Media3/ExoPlayer for playback.
- Keep the player immersive and focused, with lightweight overlay controls.
- Auto-rotate the playback experience for landscape videos so horizontal recordings can fill the screen naturally.
- Restore the app's normal orientation behavior when leaving playback.
- Read video dimensions/rotation from media metadata or the player track format rather than guessing from the filename.
- Provide play/pause, seek bar, current time, total duration, and buffered progress.
- Provide playback speed choices, such as 0.5x, 1x, 1.5x, and 2x.
- Provide an overflow menu with export and share actions.
- Keep export/share/delete/playback actions gated to `READY` segments only.

Export should support:

- Save selected recordings to public external media storage, such as `Movies/DashCam`, via MediaStore.
- Do not present export as "open with external app"; that behavior belongs to sharing or an optional "open exported file" follow-up.
- Show clear success, failure, and already-exported states.
- Keep the original app-private recording as the loop-recording source of truth.
- Mark exported metadata after the MediaStore copy succeeds.

Future casting should support:

- Post-v1 playback casting to external displays.
- Miracast or system screen-cast style playback where available.
- DLNA discovery/control as a later compatibility path for TVs, boxes, and in-car displays.
- Casting must be optional and must not block local playback, export, or share.

## User-Facing Copy Draft

Implementation rule:

- Do not hard-code these strings in Kotlin code.
- Put final strings in Android i18n resources, such as `res/values/strings.xml`.
- This section is only a copy draft and intent reference for agents.

Storage:

- `storage_insufficient_title`: `存储空间不足`
- `storage_insufficient_body`: `当前可用空间不足，无法安全开始录制。请释放空间，或降低画质后重试。`
- `storage_quota_too_small_body`: `可用于录制的空间低于 2GB，已尝试切换到较低画质。`
- `storage_cleanup_failed_body`: `已清理可删除的视频，但空间仍不足，录制已停止。`

Thermal:

- `thermal_notice_body`: `设备温度升高，录制仍在继续。`
- `thermal_downgrade_body`: `设备温度较高，已降低视频质量以保持录制。`
- `thermal_emergency_stop_body`: `设备温度过高，录制已停止以保护设备。`

Battery:

- `battery_low_body`: `电量较低，建议连接电源。`
- `battery_downgrade_body`: `电量较低且未连接电源，已降低视频质量以延长录制。`
- `battery_emergency_stop_body`: `电量过低，录制已停止以避免视频损坏。`

Recording:

- `recording_confirm_title`: `开始录制？`
- `recording_confirm_body`: `录制将通过常驻通知持续运行。开始后你可以按电源键锁屏，DashCam 会尽量继续录制。`
- `recording_confirm_start`: `开始录制`
- `recording_confirm_cancel`: `取消`
- `recording_start_body`: `DashCam 正在录制。你可以按电源键锁屏。`
- `recording_black_screen_body`: `黑屏省电模式已开启，录制仍在继续。`
- `recording_auto_downgrade_body`: `资源紧张，已自动调整为更稳定的录制质量。`
- `recording_emergency_stop_body`: `由于设备状态紧急，录制已停止。`

## Implementation Phases

Checklist legend:

- `[x]`: implemented or verified in the current codebase.
- `[~]`: partially implemented; follow-up work remains.
- `[ ]`: not implemented yet.
- `[deferred]`: intentionally deferred by product decision.

### Phase 0: Project Setup

Status: Mostly done.

- [x] Create Android project.
- [x] Configure Kotlin, Compose, CameraX, MMKV, Media3, and FileProvider.
- [x] Use package name `com.xxxifan.dashcam`.
- [x] Use app display name `DashCam`.
- [x] Set minimum SDK to Android 16 / API 36 for the Pixel 10a baseline.
- [x] Add manifest permissions and foreground service declarations.
- [~] Add formatter configuration.
  - Current state: project follows Kotlin/Gradle formatting conventions, but no dedicated formatter task/config is documented yet.
- [x] Add initial package/module structure.

Current anchors:

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/xxxifan/dashcam/DashCamApplication.kt`

### Phase 1: Basic Recording

Status: Mostly done.

- [x] Implement foreground `RecordingService`.
- [x] Implement CameraX video recording to app-specific files.
- [x] Implement start/stop from UI.
- [x] Show a pre-recording confirmation dialog before starting capture.
- [x] Implement basic notification.
- [~] Verify one segment can be recorded and played back.
  - Current state: short device tests have produced video files and playback UI exists.
  - Remaining: repeat after each major recording pipeline change and document the tested setting combination.

Current anchors:

- `app/src/main/java/com/xxxifan/dashcam/recording/RecordingService.kt`
- `app/src/main/java/com/xxxifan/dashcam/recording/RecordingState.kt`
- `app/src/main/java/com/xxxifan/dashcam/MainActivity.kt`

### Phase 2: Segment Rotation And Loop Storage

Status: Mostly done.

- [x] Implement segment duration with 2 minutes as the default.
- [x] Implement automatic stop/start segment rotation.
- [x] Implement metadata persistence.
- [x] Implement hidden recording folder and `.nomedia`.
- [x] Implement quota cleanup of oldest deletable recordings.
- [x] Implement 10% storage reserve.
- [x] Implement quota picker from 2GB to available space after reserve.
- [x] Estimate remaining recording space and recordable time from live stats, historical files, or selected profile heuristics.
- [x] Estimate next segment size from selected resolution/fps/codec/HDR/audio and segment duration before loop cleanup.
- [x] Implement startup quality fallback when recording space is too low for high-quality profiles.
  - Current state: startup first tries the requested settings; if storage is unsafe and auto-downgrade is enabled, the service evaluates lower-storage supported profiles, starts the lowest viable temporary recording profile, and reports the active profile in UI/notification state. If no candidate is safe, it shows the storage-insufficient error.

Current anchors:

- `app/src/main/java/com/xxxifan/dashcam/storage/LoopStorageManager.kt`
- `app/src/main/java/com/xxxifan/dashcam/storage/RecordingStorageEstimator.kt`
- `app/src/main/java/com/xxxifan/dashcam/data/RecordingRepository.kt`
- `app/src/main/java/com/xxxifan/dashcam/data/RecordingSettingsStore.kt`

### Phase 3: Lock-Screen And Black-Screen Modes

Status: Partial, with black-screen UI currently deferred.

- [~] Verify foreground-started lock-screen recording path.
  - Current state: user-started foreground service can continue recording after screen lock in current Pixel test.
  - Known issue: face unlock/front-camera activity can temporarily interrupt camera access, but recording may recover.
  - Remaining: document exact lock-screen test matrix and retest with selected lens/fps/stabilization combinations.
- [deferred] Add black-screen low-brightness mode.
  - Product update: black-screen button was removed while lock-screen recording appears viable.
  - Keep this as a future fallback if lock-screen recording proves unstable.
- [deferred] Add UI toggle between preview and black-screen mode.
- [~] Add fallback handling when lock-screen recording fails or is unstable.
  - Current state: CameraX source-inactive finalization is treated as a camera interruption signal. Repeated interruption or recording-pipeline failure now stops safely and shows foreground/screen-on and lower-quality guidance.
  - Remaining: add a dedicated black-screen low-brightness fallback screen if lock-screen recording proves unstable on broader device tests.

Current anchors:

- `app/src/main/java/com/xxxifan/dashcam/MainActivity.kt`
- `app/src/main/java/com/xxxifan/dashcam/camera/PreviewController.kt`
- `app/src/main/java/com/xxxifan/dashcam/recording/RecordingService.kt`

### Phase 4: Settings And Capability Query

Status: Mostly done.

- [x] Implement runtime capability query.
- [x] Populate supported resolution/fps/codec/stabilization options.
- [x] Populate supported HDR/dynamic range options.
- [x] Populate back lens options and expose lens selection only in settings.
- [x] Apply selected settings to new recordings.
- [x] Implement bitrate/quality preset settings and apply them with CameraX target bitrate APIs.
  - Current state: settings include Auto, Space saver, Standard, and High quality. Recording startup resolves these to target video/audio bitrate values and applies them through CameraX `Recorder.Builder`.
- [x] Implement app-level Auto quality selection based on remaining safe recording space and the 8-hour target.
  - Current state: Auto resolves to the highest supported candidate that fits the 8-hour safe-storage target, ranking resolution before fps, bitrate preset, HDR, and stabilization. If no candidate fits, the lowest-storage supported profile is used so startup fallback can still attempt a viable recording.
- [~] Handle unsupported combinations gracefully.
  - Current state: saved settings are coerced to runtime capabilities and CameraX fallback quality strategy is used.
  - Remaining: validate cross-feature combinations such as physical ultra-wide + 4K/60fps + enhanced stabilization, bitrate preset, codec, and HDR, then downgrade/disable invalid combinations before recording.

Current anchors:

- `app/src/main/java/com/xxxifan/dashcam/camera/CameraCapabilitiesRepository.kt`
- `app/src/main/java/com/xxxifan/dashcam/camera/CameraOption.kt`
- `app/src/main/java/com/xxxifan/dashcam/camera/PreviewController.kt`
- `app/src/main/java/com/xxxifan/dashcam/MainActivity.kt`

### Phase 5: Health Monitoring And Safety Policy

Status: Partial skeleton.

- [~] Implement thermal, battery, storage, and recording pipeline monitors.
  - Current state: recording service polls thermal status, battery level/charging state, and next-segment storage safety while recording. Pressure can mark the active session as downgraded and apply lower settings from the next segment.
  - Remaining: add richer recording-pipeline health scoring and device-test thresholds.
- [x] Implement `RecordingHealthSnapshot`.
- [x] Implement public `RecordingSafetyPolicy`.
- [x] Implement safety decision model and `RecordingSafetyEventSink` interface.
- [~] Implement auto-downgrade setting.
  - Current state: setting is persisted and now controls startup storage fallback plus runtime thermal, battery, and storage pressure downgrades. Downgraded sessions expose the active temporary profile and lock quality-related controls with explanatory UI text.
  - Remaining: tune thresholds through long-run device tests and decide whether later versions should restore quality automatically.
- [x] Implement emergency stop behavior driven by safety decisions.
  - Current state: thermal, storage, battery, and repeated recording-pipeline failures are evaluated through `RecordingSafetyPolicy`; emergency decisions stop recording and surface a clear message.
- [x] Stream safety decisions to UI/notification/log sinks.
  - Current state: non-normal safety decisions are stored in recording UI state, shown on the home screen, sent to the foreground notification, and logged.
- [x] Keep `RecordingSafetyPolicy` independent from future voice/TTS/sound/vibration sinks.
  - Current state: the policy only maps health snapshots to decisions. `RecordingService` handles execution and the current UI/notification/log sinks, leaving future voice/TTS/sound/vibration sinks outside the policy boundary.

Current anchors:

- `app/src/main/java/com/xxxifan/dashcam/safety/RecordingSafetyPolicy.kt`
- `app/src/main/java/com/xxxifan/dashcam/data/RecordingSettingsStore.kt`
- `app/src/main/java/com/xxxifan/dashcam/recording/RecordingService.kt`

### Phase 6: Recording Library, Export, Share

Status: Done, pending device validation.

- [x] Implement recording list in reverse chronological order.
- [x] Add date sticky headers.
- [x] Show the current active segment as a read-only recording status row instead of a normal library item.
  - Current state: the library shows the active segment from recording UI state as a non-clickable row with written duration/bytes and no playback/delete/export/share actions.
- [x] Exclude `RECORDING` and `FINALIZING` segments from playback, delete, export, share, loop deletion, and multi-select paths.
  - Current state: active segments are represented only by recording UI state and are not persisted into the library repository until CameraX finalization succeeds. The active row is read-only, so playback, delete, export, share, and loop deletion operate only on finalized repository entries.
- [x] Generate and display cached thumbnails for `READY` recordings.
  - Current state: completed segments generate 320x180 JPEG thumbnails off the main thread after CameraX finalization, and the library lazily backfills the newest visible entries without eager whole-library startup work.
  - Current state: thumbnail files live in app-private cache with a schema/version/source fingerprint, duplicate generation for the same recording is coalesced, decoding is limited to one job at a time, stale cache files are cleaned, and list rows reserve a fixed 16:9 thumbnail slot with a stable placeholder.
- [x] Implement playback preview with Media3/ExoPlayer.
  - Current state: tapping a finalized library item opens a full-screen playback/detail screen backed by Media3/ExoPlayer instead of a dialog preview.
- [x] Add full-screen playback controls.
  - Current state: full-screen playback includes play/pause, seek bar, current time, total duration, buffered progress, and playback speed menu.
- [x] Auto-rotate playback for landscape videos.
  - Current state: the playback screen reads video width/height/rotation metadata, temporarily enters landscape for landscape videos, and restores the previous orientation when leaving playback.
- [x] Add playback overflow menu for export and share actions.
- [x] Implement delete with confirmation.
- [x] Do not implement lock/protect in v1.
- [x] Implement export to MediaStore.
  - Current state: export copies the selected private recording into public `Movies/DashCam` through MediaStore with pending/complete state, cleans up partial copies on failure, and marks the recording metadata as exported only after the copy succeeds.
  - Current state: sharing remains a separate FileProvider-backed action.
- [x] Implement share via FileProvider.
- [x] Add clipping/editing placeholder entry.
  - Current state: the playback overflow menu includes a reserved clipping entry that clearly indicates the editor will arrive in a later version.

Current anchors:

- `app/src/main/java/com/xxxifan/dashcam/MainActivity.kt`
- `app/src/main/java/com/xxxifan/dashcam/data/RecordingRepository.kt`
- `app/src/main/res/xml/file_paths.xml`

### Phase 7: Pixel 10a Tuning And Long-Run Testing

Status: Pending / ongoing device validation.

- [~] Test lock-screen recording.
  - Current state: short manual lock-screen test passed; more combinations remain.
- [deferred] Test black-screen fallback.
  - Deferred until black-screen fallback is reintroduced.
- [ ] Test 4K/1080p, 60fps/30fps, H.264/H.265.
- [~] Test stabilization modes.
  - Current state: off/standard/enhanced are exposed when supported and applied through Camera2 interop.
  - Remaining: verify actual output stability and compatibility per mode.
- [ ] Test thermal downgrade behavior.
- [ ] Test low storage and loop deletion behavior.
- [ ] Test low battery behavior.
- [ ] Test 30-minute and longer continuous recording.

Current anchors:

- Use connected Pixel target with `adb`.
- Record tested app version, settings, elapsed duration, segment count, and failures in this document or a dedicated test log.

### Phase 8: Casting And External Playback

Status: Future.

- [deferred] Add Miracast/system cast entry from playback.
  - Desired behavior: allow the user to mirror or cast playback to external displays when the device and OS expose a supported path.
- [deferred] Add DLNA discovery and playback control.
  - Desired behavior: discover compatible TVs/boxes/in-car receivers, send selected recording playback, and keep local playback as the fallback.
- [deferred] Validate casting privacy and network behavior.
  - Desired behavior: casting is explicit, user-initiated, and never exposes private recordings without confirmation.

Current anchors:

- Playback/detail screen once implemented.
- Media3 playback integration.

## Acceptance Criteria

- [x] User can start recording from foreground UI.
- [x] Foreground notification remains visible while recording.
- [~] Locking the screen after starting recording continues writing valid video segments.
  - Short test passed; long-run and feature-combination tests remain.
- [deferred] Black-screen lowest-brightness mode works as fallback.
- [~] Segment rotation produces playable files.
  - Current state: implemented; needs repeated device validation after rotation fixes.
- [~] Loop recording respects configured storage quota.
  - Current state: quota enforcement and oldest-delete cleanup are implemented; low-storage stress test remains.
- [x] Old deletable recordings are deleted before stopping for storage pressure.
- [x] Lock/protect behavior is intentionally deferred after v1.
- [x] Default recordings do not appear in gallery apps.
- [x] The unfinished active segment is never exposed as a normal library item; it may appear only as a read-only live status row.
- [x] Playback, delete, export, share, loop deletion, and multi-select ignore `RECORDING` and `FINALIZING` segments.
- [x] Recording library shows thumbnails for completed videos.
- [x] Thumbnail generation is off-main-thread, cached on disk, bounded in concurrency, and does not run for unfinished segments.
- [x] Tapping a completed recording opens a full-screen player, not a dialog preview.
- [x] Full-screen playback supports seek bar, current time, total duration, buffered progress, play/pause, and playback speed selection.
- [x] Landscape videos can be viewed in a landscape full-screen playback orientation, and the app restores normal orientation after exiting playback.
- [x] Playback provides export and share actions from an overflow menu.
- [x] Exported recordings appear in user-visible media storage.
- [x] Export saves a copy to public external media storage through MediaStore instead of launching an external app chooser.
- [x] Sharing works through Android share sheet.
- [deferred] Playback can later cast through Miracast/system cast or DLNA after explicit user action.
- [ ] Safety monitor can notify about thermal, battery, and storage pressure.
- [ ] Auto-downgrade setting changes behavior under pressure.
- [ ] Emergency thermal/storage/battery conditions stop recording even when auto-downgrade is disabled.
- [~] `RecordingSafetyPolicy` remains reusable by future TTS/sound/vibration event sinks.
  - Interface boundary exists; service integration must preserve the decoupling.

## Known Risks

- Some devices or OS builds may stop camera capture after lock screen despite a valid foreground service.
- Some video setting combinations may fail despite individual features being available.
- High quality recording may trigger thermal throttling or encoder failures.
- The OS or user may still stop the foreground service.
- App-specific external storage can be removed when the app is uninstalled.
- Gallery invisibility depends on storing files outside MediaStore and using `.nomedia`; exported copies become visible by design.

## Confirmed Decisions

1. Package name: `com.xxxifan.dashcam`.
2. App display name: `DashCam`.
3. Minimum Android version: Android 16 / API 36 for Pixel 10a baseline.
4. Audio recording default: on.
5. Default segment duration: 2 minutes.
6. Default storage quota: maximum safe recording space.
7. Minimum storage reserve: 10% of storage volume.
8. Custom storage quota range: 2GB to available space after reserve.
9. Default recording quality: 720p30 standard bitrate.
10. Default codec: auto, resolved by the app-level auto quality policy rather than blindly leaving CameraX bitrate/profile unset.
11. Resource-pressure auto-downgrade default: on.
12. Lock/protect-current-segment: not included in v1.
13. Persistence: MMKV for settings and lightweight recording metadata.
14. UI: Compose-only.
15. Pre-recording guidance: show a confirmation dialog before starting recording.
16. Default stabilization: standard.
17. Stabilization choices: expose off, standard, and enhanced/active where supported.
18. Recording library: reverse chronological list with date sticky headers.
19. Active recording visibility: show the unfinished current segment only as a read-only live status row, never as a normal library item.
20. Segment operations: playback, delete, export, share, loop deletion, and multi-select are allowed only for `READY` segments.
21. Video preview target: full-screen playback/detail screen instead of dialog preview.
22. Export target: public external media storage through MediaStore, such as `Movies/DashCam`, not an external-app-open chooser.
23. Future casting: support Miracast/system cast and DLNA after local playback/export/share are solid.

## Details To Confirm Before Implementation

No blocking product decisions remain before starting implementation. Reconfirm any details only if platform/API constraints force a change.
