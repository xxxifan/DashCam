package com.xxxifan.dashcam.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingUiState(
    val isRecording: Boolean = false,
    val message: String = "准备录制",
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
