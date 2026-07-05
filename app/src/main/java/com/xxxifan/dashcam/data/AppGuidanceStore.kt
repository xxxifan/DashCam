package com.xxxifan.dashcam.data

import com.tencent.mmkv.MMKV

class AppGuidanceStore(
    private val mmkv: MMKV = MMKV.mmkvWithID("app_guidance"),
) {
    fun hasShownBatteryOptimizationPrompt(): Boolean =
        mmkv.decodeBool(KEY_BATTERY_OPTIMIZATION_PROMPT_SHOWN, false)

    fun markBatteryOptimizationPromptShown() {
        mmkv.encode(KEY_BATTERY_OPTIMIZATION_PROMPT_SHOWN, true)
    }

    private companion object {
        const val KEY_BATTERY_OPTIMIZATION_PROMPT_SHOWN = "battery_optimization_prompt_shown"
    }
}
