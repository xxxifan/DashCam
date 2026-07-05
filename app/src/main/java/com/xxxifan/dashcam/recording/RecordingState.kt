package com.xxxifan.dashcam.recording

import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.safety.RecordingSafetyDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingDowngradeReason {
    StartupStorage,
    Thermal,
    Battery,
}

enum class RecordingStopReason {
    Manual,
    AppSafetyStorage,
    AppSafetyThermal,
    AppSafetyBattery,
    AppSafetyPipeline,
    CameraXError,
    SourceInactive,
    PermissionMissing,
    SystemInterrupted,
    Unknown,
}

data class RecordingDowngradeState(
    val reasons: Set<RecordingDowngradeReason>,
    val requestedSettings: RecordingSettings,
    val activeSettings: RecordingSettings,
    val message: String,
)

data class RecordingUiState(
    val isRecording: Boolean = false,
    val message: String = "准备录制",
    val activeSettings: RecordingSettings? = null,
    val downgradeState: RecordingDowngradeState? = null,
    val safetyDecision: RecordingSafetyDecision? = null,
    val fallbackGuidance: String? = null,
    val stopReason: RecordingStopReason? = null,
    val startedAtMillis: Long? = null,
    val currentSegmentPath: String? = null,
    val recordedBytes: Long = 0L,
    val recordedDurationNanos: Long = 0L,
)

object RecordingStateBus {
    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    fun update(state: RecordingUiState) {
        _state.value = state
    }
}
