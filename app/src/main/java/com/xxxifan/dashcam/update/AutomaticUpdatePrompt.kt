package com.xxxifan.dashcam.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xxxifan.dashcam.BuildConfig
import java.time.LocalDate

@Composable
fun AutomaticUpdatePrompt(enabled: Boolean = true) {
    val context = LocalContext.current
    val manager = remember(context) {
        AppUpdateManager(
            context = context.applicationContext,
            repository = BuildConfig.GITHUB_REPOSITORY,
        )
    }
    val promptStore = remember(context) { UpdatePromptStore(context.applicationContext) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manager, promptStore) {
        if (promptStore.wasDismissedToday()) return@LaunchedEffect
        val existingDownload = manager.savedDownload()
        if (existingDownload != null &&
            VersionComparator.isNewer(existingDownload.tagName, BuildConfig.VERSION_NAME)
        ) {
            return@LaunchedEffect
        }
        val result = runCatching { manager.check(BuildConfig.VERSION_NAME) }.getOrNull()
        if (result is UpdateCheckResult.Available) {
            release = result.release
        }
    }

    val availableRelease = release ?: return
    if (!enabled) return

    fun dismissForToday() {
        promptStore.markDismissedToday()
        release = null
        errorMessage = null
    }

    AlertDialog(
        onDismissRequest = ::dismissForToday,
        title = { Text("发现新版本") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("新版本 ${availableRelease.tagName}", fontWeight = FontWeight.SemiBold)
                if (availableRelease.title != availableRelease.tagName) {
                    Text(availableRelease.title)
                }
                if (availableRelease.notes.isNotBlank()) {
                    Text(
                        availableRelease.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(availableRelease.pageUrl)),
                        )
                    },
                ) {
                    Text("查看完整说明")
                }
            }
        },
        confirmButton = {
            val apk = availableRelease.apk
            if (apk != null) {
                Button(
                    onClick = {
                        runCatching { manager.download(availableRelease) }
                            .onSuccess {
                                release = null
                                Toast.makeText(
                                    context,
                                    "已开始下载，可在设置页查看进度",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            .onFailure { error ->
                                errorMessage = error.message ?: "无法开始下载"
                            }
                    },
                ) {
                    Text("下载 APK${apk.sizeBytes.sizeSuffix()}")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismissForToday) {
                Text("稍后")
            }
        },
    )
}

private class UpdatePromptStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wasDismissedToday(): Boolean =
        preferences.getLong(KEY_DISMISSED_EPOCH_DAY, Long.MIN_VALUE) == LocalDate.now().toEpochDay()

    fun markDismissedToday() {
        preferences.edit()
            .putLong(KEY_DISMISSED_EPOCH_DAY, LocalDate.now().toEpochDay())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "app_update_prompt"
        private const val KEY_DISMISSED_EPOCH_DAY = "dismissed_epoch_day"
    }
}

private fun Long.sizeSuffix(): String = when {
    this <= 0L -> ""
    this >= 1024L * 1024L -> "（${this / (1024L * 1024L)} MB）"
    this >= 1024L -> "（${this / 1024L} KB）"
    else -> "（$this B）"
}
