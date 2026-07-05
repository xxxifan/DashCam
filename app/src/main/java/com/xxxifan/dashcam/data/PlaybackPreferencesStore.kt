package com.xxxifan.dashcam.data

import com.tencent.mmkv.MMKV

class PlaybackPreferencesStore(
    private val mmkv: MMKV = MMKV.mmkvWithID("playback_preferences"),
) {
    fun isContinuousPlayEnabled(): Boolean =
        mmkv.decodeBool(KEY_CONTINUOUS_PLAY_ENABLED, false)

    fun setContinuousPlayEnabled(enabled: Boolean) {
        mmkv.encode(KEY_CONTINUOUS_PLAY_ENABLED, enabled)
    }

    private companion object {
        const val KEY_CONTINUOUS_PLAY_ENABLED = "continuous_play_enabled"
    }
}
