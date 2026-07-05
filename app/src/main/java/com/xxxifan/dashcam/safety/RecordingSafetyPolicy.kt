package com.xxxifan.dashcam.safety

enum class SafetyLevel {
    Normal,
    Notice,
    Pressure,
    Emergency,
}

enum class SafetyAction {
    Notify,
    DowngradeQuality,
    StopRecording,
}

enum class SafetyReason {
    Thermal,
    Storage,
    Battery,
    RecordingPipeline,
}

data class RecordingHealthSnapshot(
    val thermalLevel: SafetyLevel = SafetyLevel.Normal,
    val storageLevel: SafetyLevel = SafetyLevel.Normal,
    val batteryLevel: SafetyLevel = SafetyLevel.Normal,
    val pipelineLevel: SafetyLevel = SafetyLevel.Normal,
)

data class RecordingSafetyDecision(
    val level: SafetyLevel,
    val actions: Set<SafetyAction>,
    val reasons: Set<SafetyReason>,
    val message: String,
    val shouldNotifyUser: Boolean,
)

interface RecordingSafetyPolicy {
    fun evaluate(snapshot: RecordingHealthSnapshot): RecordingSafetyDecision
}

interface RecordingSafetyEventSink {
    fun onSafetyDecision(decision: RecordingSafetyDecision)
}

class DefaultRecordingSafetyPolicy(
    private val autoDowngradeEnabled: Boolean,
) : RecordingSafetyPolicy {
    override fun evaluate(snapshot: RecordingHealthSnapshot): RecordingSafetyDecision {
        val levels = listOf(
            snapshot.thermalLevel,
            snapshot.storageLevel,
            snapshot.batteryLevel,
            snapshot.pipelineLevel,
        )
        val level = levels.maxBy { it.ordinal }
        val reasons = buildSet {
            if (snapshot.thermalLevel == level && level != SafetyLevel.Normal) add(SafetyReason.Thermal)
            if (snapshot.storageLevel == level && level != SafetyLevel.Normal) add(SafetyReason.Storage)
            if (snapshot.batteryLevel == level && level != SafetyLevel.Normal) add(SafetyReason.Battery)
            if (snapshot.pipelineLevel == level && level != SafetyLevel.Normal) add(SafetyReason.RecordingPipeline)
        }
        val actions = when (level) {
            SafetyLevel.Normal -> emptySet()
            SafetyLevel.Notice -> setOf(SafetyAction.Notify)
            SafetyLevel.Pressure -> if (autoDowngradeEnabled) {
                setOf(SafetyAction.Notify, SafetyAction.DowngradeQuality)
            } else {
                setOf(SafetyAction.Notify)
            }
            SafetyLevel.Emergency -> setOf(SafetyAction.Notify, SafetyAction.StopRecording)
        }
        return RecordingSafetyDecision(
            level = level,
            actions = actions,
            reasons = reasons,
            message = decisionMessage(level, reasons),
            shouldNotifyUser = actions.contains(SafetyAction.Notify),
        )
    }

    private fun decisionMessage(
        level: SafetyLevel,
        reasons: Set<SafetyReason>,
    ): String {
        if (level == SafetyLevel.Normal) {
            return "录制状态正常"
        }
        val reasonText = reasons.sortedBy { it.ordinal }.joinToString("、") { it.label() }
        return when (level) {
            SafetyLevel.Normal -> "录制状态正常"
            SafetyLevel.Notice -> "$reasonText 状态需注意"
            SafetyLevel.Pressure -> "$reasonText 触发资源压力保护"
            SafetyLevel.Emergency -> "$reasonText 达到紧急状态，录制将停止"
        }
    }

    private fun SafetyReason.label(): String = when (this) {
        SafetyReason.Thermal -> "设备发热"
        SafetyReason.Storage -> "存储空间"
        SafetyReason.Battery -> "电量"
        SafetyReason.RecordingPipeline -> "录制管线"
    }
}
