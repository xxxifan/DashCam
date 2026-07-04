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
        val levels = listOf(snapshot.thermalLevel, snapshot.storageLevel, snapshot.batteryLevel)
        val level = levels.maxBy { it.ordinal }
        val reasons = buildSet {
            if (snapshot.thermalLevel == level && level != SafetyLevel.Normal) add(SafetyReason.Thermal)
            if (snapshot.storageLevel == level && level != SafetyLevel.Normal) add(SafetyReason.Storage)
            if (snapshot.batteryLevel == level && level != SafetyLevel.Normal) add(SafetyReason.Battery)
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
            message = level.name,
            shouldNotifyUser = actions.contains(SafetyAction.Notify),
        )
    }
}
