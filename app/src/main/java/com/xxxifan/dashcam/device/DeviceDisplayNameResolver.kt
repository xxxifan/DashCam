package com.xxxifan.dashcam.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

object DeviceDisplayNameResolver {
    fun displayName(context: Context): String {
        val productName = listOf(
            readGlobalSetting(context, DEFAULT_DEVICE_NAME_SETTING),
            Build.MODEL,
        ).firstNotNullOfOrNull { candidate ->
            candidate?.normalizedName()?.takeUnless { name -> name.isModelNumberLike() }
        }
        if (productName != null) {
            return productName
        }

        val deviceName = readGlobalSetting(context, Settings.Global.DEVICE_NAME)?.normalizedName()
        if (deviceName != null) {
            return deviceName
        }

        return Build.MODEL.normalizedName() ?: FALLBACK_DEVICE_NAME
    }

    private fun readGlobalSetting(context: Context, name: String): String? =
        runCatching {
            Settings.Global.getString(context.contentResolver, name)
        }.getOrNull()

    private fun String.normalizedName(): String? {
        val value = trim()
        return value.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals(Build.UNKNOWN, ignoreCase = true)
        }
    }

    private fun String.isModelNumberLike(): Boolean {
        if (contains(" ")) {
            return false
        }

        val normalized = uppercase(Locale.US)
        return normalized == this &&
            any(Char::isDigit) &&
            all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private const val DEFAULT_DEVICE_NAME_SETTING = "default_device_name"
    private const val FALLBACK_DEVICE_NAME = "设备"
}
