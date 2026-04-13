package com.sleepcare.mobile.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class DeviceType {
    RaspberryPi,
    Smartwatch,
}

enum class ConnectionStatus {
    Disconnected,
    Scanning,
    Connected,
    Failed,
}

enum class StudySessionPhase {
    Idle,
    DiscoveringPi,
    ConnectingPi,
    OpeningSession,
    Running,
    Alerting,
    Stopping,
    Error,
}

data class SleepSession(
    val id: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val totalMinutes: Int,
    val sleepScore: Int,
    val consistencyScore: Int,
    val latencyMinutes: Int,
    val awakeMinutes: Int,
    val source: String = "Unavailable",
)

data class DrowsinessEvent(
    val id: String,
    val timestamp: LocalDateTime,
    val severity: Int,
    val durationMinutes: Int,
    val label: String,
    val deviceId: String,
    val sessionId: String? = null,
)

data class StudyPlan(
    val id: Int = DEFAULT_ID,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val focusHours: Int,
    val days: Set<DayOfWeek>,
    val breakPreferenceMinutes: Int,
    val autoBreakEnabled: Boolean,
) {
    companion object {
        const val DEFAULT_ID = 1
    }
}

data class ExamSchedule(
    val id: Long = 0L,
    val name: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val location: String,
    val priority: Int,
    val syncEnabled: Boolean,
)

data class RecommendationTip(
    val title: String,
    val body: String,
    val iconKey: String,
)

data class RecommendationSnapshot(
    val id: Long = 1L,
    val recommendedBedtime: LocalTime,
    val recommendedWakeTime: LocalTime,
    val targetSleepMinutes: Int,
    val reason: String,
    val routineShiftMinutes: Int,
    val tips: List<RecommendationTip>,
    val generatedAt: LocalDateTime,
)

data class OnboardingState(
    val completed: Boolean = false,
)

data class NotificationPreferences(
    val drowsinessAlertsEnabled: Boolean = true,
    val sleepRemindersEnabled: Boolean = true,
)

data class ConnectedDeviceState(
    val deviceType: DeviceType,
    val deviceName: String,
    val status: ConnectionStatus,
    val details: String? = null,
    val lastSeenAt: LocalDateTime? = null,
)

data class PiServiceEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val wsPath: String,
    val deviceId: String,
)

data class PiEnvelope(
    val version: Int,
    val type: String,
    val sessionId: String?,
    val sequence: Long,
    val source: String,
    val sentAtMs: Long,
    val ackRequired: Boolean,
    val body: String = "{}",
)

data class PiHelloAck(
    val deviceId: String,
    val mode: String?,
    val protocol: String?,
)

data class PiRiskUpdate(
    val sessionId: String,
    val sequence: Long,
    val mode: String,
    val eyeScore: Double?,
    val hrScore: Double?,
    val fusedScore: Double?,
    val state: String,
    val recommendedFlushSec: Int?,
    val receivedAt: LocalDateTime,
)

data class PiAlertFire(
    val sessionId: String,
    val sequence: Long,
    val level: Int,
    val reason: String,
    val durationMs: Long,
    val receivedAt: LocalDateTime,
)

data class PiSessionSummary(
    val sessionId: String,
    val sequence: Long,
    val finalState: String,
    val totalAlerts: Int,
    val peakFusedScore: Double?,
    val mode: String?,
    val summaryReason: String?,
    val receivedAt: LocalDateTime,
)

data class StudySessionState(
    val sessionId: String? = null,
    val phase: StudySessionPhase = StudySessionPhase.Idle,
    val startedAt: LocalDateTime? = null,
    val latestRisk: PiRiskUpdate? = null,
    val latestAlert: PiAlertFire? = null,
    val latestSummary: PiSessionSummary? = null,
    val message: String? = null,
)

data class UserGoals(
    val targetWakeTime: LocalTime? = null,
    val preferredBedtime: LocalTime? = null,
)

data class LastSyncState(
    val sleepSyncedAt: LocalDateTime? = null,
    val drowsinessSyncedAt: LocalDateTime? = null,
)

data class RecommendationInput(
    val sleepSessions: List<SleepSession>,
    val drowsinessEvents: List<DrowsinessEvent>,
    val studyPlan: StudyPlan?,
    val exams: List<ExamSchedule>,
    val userGoals: UserGoals,
    val generatedAt: LocalDateTime = LocalDateTime.now(),
)

data class HomeDashboardSnapshot(
    val latestSleep: SleepSession?,
    val recentDrowsinessCount: Int,
    val recommendation: RecommendationSnapshot?,
    val nextExam: ExamSchedule?,
    val sessionState: StudySessionState = StudySessionState(),
)

data class SleepAnalysisSnapshot(
    val score: Int,
    val averageMinutes: Int,
    val consistency: Int,
    val latencyMinutes: Int,
    val weeklyDurations: List<Int>,
    val isAvailable: Boolean = true,
    val emptyReason: String? = null,
)

data class DrowsinessAnalysisSnapshot(
    val totalCount: Int,
    val peakWindowLabel: String,
    val focusScore: Int,
    val recentEvents: List<DrowsinessEvent>,
    val liveRisk: PiRiskUpdate? = null,
)
