package com.xxxifan.dashcam.data

import com.tencent.mmkv.MMKV

data class RecordingStopAlert(
    val message: String,
    val fallbackGuidance: String?,
    val occurredAtMillis: Long,
    val reason: String? = null,
)

class RecordingAlertStore(
    private val mmkv: MMKV = MMKV.mmkvWithID("recording_alerts"),
) {
    fun getLastStopAlert(): RecordingStopAlert? {
        val message = mmkv.decodeString(KEY_MESSAGE).orEmpty()
        if (message.isBlank()) {
            return null
        }
        return RecordingStopAlert(
            message = message,
            fallbackGuidance = mmkv.decodeString(KEY_GUIDANCE)?.takeIf { it.isNotBlank() },
            occurredAtMillis = mmkv.decodeLong(KEY_OCCURRED_AT, 0L),
            reason = mmkv.decodeString(KEY_REASON)?.takeIf { it.isNotBlank() },
        )
    }

    fun saveStopAlert(
        message: String,
        fallbackGuidance: String?,
        reason: String? = null,
    ) {
        mmkv.encode(KEY_MESSAGE, message)
        mmkv.encode(KEY_GUIDANCE, fallbackGuidance.orEmpty())
        mmkv.encode(KEY_OCCURRED_AT, System.currentTimeMillis())
        mmkv.encode(KEY_REASON, reason.orEmpty())
    }

    fun clearLastStopAlert() {
        mmkv.removeValuesForKeys(arrayOf(KEY_MESSAGE, KEY_GUIDANCE, KEY_OCCURRED_AT, KEY_REASON))
    }

    private companion object {
        const val KEY_MESSAGE = "last_stop_message"
        const val KEY_GUIDANCE = "last_stop_guidance"
        const val KEY_OCCURRED_AT = "last_stop_occurred_at"
        const val KEY_REASON = "last_stop_reason"
    }
}
