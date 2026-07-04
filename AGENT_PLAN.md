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
- Default quality: 1080p30.
- Default codec: auto.
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
- Locked/protected flag reserved for after v1.
- Exported flag.

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
- Resolution: auto, 720p, 1080p, 4K where supported. Default: 1080p.
- Fps: auto, 24, 30, 60 where supported.
- Codec: auto, H.264, H.265/HEVC, and any other supported codec exposed by the platform. Default: auto.
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

## UI Plan

Screens:

- Home/recording control screen.
- Recording preview screen.
- Black-screen recording screen.
- Settings screen.
- Recording library screen.
- Playback/detail screen.
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
- File duration, size, and quality.
- Playback.
- Delete.
- Lock/protect is reserved for after v1.
- Export.
- Share.
- Multi-select later if not in first slice.

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

### Phase 0: Project Setup

- Create Android project.
- Configure Kotlin, Compose, CameraX, MMKV, Media3, and FileProvider.
- Use package name `com.xxxifan.dashcam`.
- Use app display name `DashCam`.
- Set minimum SDK to Android 16 / API 36 for the Pixel 10a baseline.
- Add manifest permissions and foreground service declarations.
- Add formatter configuration.
- Add initial package/module structure.

### Phase 1: Basic Recording

- Implement foreground `RecordingService`.
- Implement CameraX video recording to app-specific files.
- Implement start/stop from UI.
- Show a pre-recording confirmation dialog before starting capture.
- Implement basic notification.
- Verify one segment can be recorded and played back.

### Phase 2: Segment Rotation And Loop Storage

- Implement segment duration with 2 minutes as the default.
- Implement automatic stop/start segment rotation.
- Implement metadata persistence.
- Implement hidden recording folder and `.nomedia`.
- Implement quota cleanup of oldest deletable recordings.
- Implement 10% storage reserve.
- Implement quota picker from 2GB to available space after reserve.
- Implement startup quality fallback when recording space is too low for high-quality profiles.

### Phase 3: Lock-Screen And Black-Screen Modes

- Verify foreground-started lock-screen recording path.
- Add black-screen low-brightness mode.
- Add UI toggle between preview and black-screen mode.
- Add fallback handling when lock-screen recording fails or is unstable.

### Phase 4: Settings And Capability Query

- Implement runtime capability query.
- Populate only supported resolution/fps/codec/stabilization options.
- Apply selected settings to new recordings.
- Handle unsupported combinations gracefully.

### Phase 5: Health Monitoring And Safety Policy

- Implement thermal, battery, storage, and recording pipeline monitors.
- Implement `RecordingHealthSnapshot`.
- Implement public `RecordingSafetyPolicy`.
- Implement safety decisions and event sinks.
- Implement auto-downgrade setting.
- Implement emergency stop behavior.

### Phase 6: Recording Library, Export, Share

- Implement recording list in reverse chronological order.
- Add date sticky headers.
- Implement playback/detail screen.
- Implement delete.
- Do not implement lock/protect in v1.
- Implement export to MediaStore.
- Implement share via FileProvider.
- Add clipping/editing placeholder entry.

### Phase 7: Pixel 10a Tuning And Long-Run Testing

- Test lock-screen recording.
- Test black-screen fallback.
- Test 4K/1080p, 60fps/30fps, H.264/H.265.
- Test stabilization modes.
- Test thermal downgrade behavior.
- Test low storage and loop deletion behavior.
- Test low battery behavior.
- Test 30-minute and longer continuous recording.

## Acceptance Criteria

- User can start recording from foreground UI.
- Foreground notification remains visible while recording.
- Locking the screen after starting recording continues writing valid video segments.
- Black-screen lowest-brightness mode works as fallback.
- Segment rotation produces playable files.
- Loop recording respects configured storage quota.
- Old deletable recordings are deleted before stopping for storage pressure.
- Lock/protect behavior is intentionally deferred after v1.
- Default recordings do not appear in gallery apps.
- Exported recordings appear in user-visible media storage.
- Sharing works through Android share sheet.
- Safety monitor can notify about thermal, battery, and storage pressure.
- Auto-downgrade setting changes behavior under pressure.
- Emergency thermal/storage/battery conditions stop recording even when auto-downgrade is disabled.
- `RecordingSafetyPolicy` remains reusable by future TTS/sound/vibration event sinks.

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
9. Default recording quality: 1080p30.
10. Default codec: auto.
11. Resource-pressure auto-downgrade default: on.
12. Lock/protect-current-segment: not included in v1.
13. Persistence: MMKV for settings and lightweight recording metadata.
14. UI: Compose-only.
15. Pre-recording guidance: show a confirmation dialog before starting recording.
16. Default stabilization: standard.
17. Stabilization choices: expose off, standard, and enhanced/active where supported.
18. Recording library: reverse chronological list with date sticky headers.

## Details To Confirm Before Implementation

No blocking product decisions remain before starting implementation. Reconfirm any details only if platform/API constraints force a change.
