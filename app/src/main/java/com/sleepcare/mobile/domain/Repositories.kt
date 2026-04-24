package com.sleepcare.mobile.domain

import kotlinx.coroutines.flow.Flow

interface WatchSleepDataSource {
    suspend fun readRecentSleepSessions(): List<SleepSession>
}

interface WatchSessionDataSource {
    fun observeConnectionState(): Flow<ConnectedDeviceState>
    fun observeHeartRateBatches(): Flow<WatchHeartRateBatch>
    fun observeSessionEvents(): Flow<WatchSessionEvent>
    suspend fun refreshConnection(): Boolean
    suspend fun startSession(config: WatchSessionConfig): Boolean
    suspend fun stopSession(sessionId: String): Boolean
    suspend fun acknowledgeCursor(cursor: WatchCursor): Boolean
    suspend fun requestBackfill(sessionId: String, fromSampleSeq: Long): Boolean
    suspend fun updateFlushPolicy(sessionId: String, flushPolicy: WatchFlushPolicy): Boolean
    suspend fun sendVibrationAlert(sessionId: String, level: Int, pattern: String): Boolean
    suspend fun disconnect()
}

interface PiNetworkDataSource {
    fun observeConnectionState(): Flow<ConnectedDeviceState>
    fun observeRiskState(): Flow<PiRiskUpdate?>
    fun observeAlerts(): Flow<PiAlertFire>
    fun observeSessionSummaries(): Flow<PiSessionSummary>
    suspend fun discoverAndConnect(): Boolean
    suspend fun startSession(
        sessionId: String,
        watchAvailable: Boolean,
        eyeOnly: Boolean,
    ): Boolean
    suspend fun sendHeartRateSamples(samples: List<WatchHeartRateSample>): Set<Long>
    suspend fun stopSession(sessionId: String): PiSessionSummary?
    suspend fun retry(): Boolean
    suspend fun disconnect()
}

interface RecommendationEngine {
    fun generate(input: RecommendationInput): RecommendationSnapshot
}

interface SleepRepository {
    fun observeSleepSessions(): Flow<List<SleepSession>>
    suspend fun seedIfEmpty()
    suspend fun refreshFromSource()
}

interface DrowsinessRepository {
    fun observeDrowsinessEvents(): Flow<List<DrowsinessEvent>>
    suspend fun seedIfEmpty()
    suspend fun refreshFromSource()
}

interface StudyPlanRepository {
    fun observeStudyPlan(): Flow<StudyPlan?>
    suspend fun seedIfEmpty()
    suspend fun upsert(plan: StudyPlan)
}

interface ExamScheduleRepository {
    fun observeExamSchedules(): Flow<List<ExamSchedule>>
    suspend fun seedIfEmpty()
    suspend fun upsert(examSchedule: ExamSchedule)
    suspend fun delete(examId: Long)
}

interface RecommendationRepository {
    fun observeLatestRecommendation(): Flow<RecommendationSnapshot?>
    suspend fun refreshRecommendations()
}

interface DeviceConnectionRepository {
    fun observeDevices(): Flow<List<ConnectedDeviceState>>
    fun observeTrustedPi(): Flow<TrustedPiDevice?>
    suspend fun startScan()
    suspend fun retryConnection(deviceType: DeviceType)
    suspend fun disconnect(deviceType: DeviceType)
    suspend fun registerPiFromQr(rawPayload: String): Result<TrustedPiDevice>
    suspend fun forgetPi()
}

interface StudySessionRepository {
    fun observeSessionState(): Flow<StudySessionState>
    suspend fun startSession()
    suspend fun stopSession()
}

interface SettingsRepository {
    fun observeOnboardingState(): Flow<OnboardingState>
    suspend fun setOnboardingCompleted(completed: Boolean)
    fun observeNotificationPreferences(): Flow<NotificationPreferences>
    suspend fun updateNotificationPreferences(preferences: NotificationPreferences)
    fun observeUserGoals(): Flow<UserGoals>
    suspend fun updateUserGoals(goals: UserGoals)
    fun observeLastSyncState(): Flow<LastSyncState>
    suspend fun updateLastSyncState(state: LastSyncState)
    suspend fun resetAppData()
}

object ScoreCalculator {
    fun sleepQuality(
        totalMinutes: Int,
        consistencyScore: Int,
        latencyMinutes: Int,
        awakeMinutes: Int,
    ): Int {
        val durationScore = (totalMinutes / 4.8f).toInt().coerceIn(0, 40)
        val consistencyPart = (consistencyScore * 0.35f).toInt().coerceIn(0, 35)
        val latencyPenalty = (latencyMinutes / 2).coerceIn(0, 15)
        val awakePenalty = awakeMinutes.coerceIn(0, 10)
        return (durationScore + consistencyPart + 25 - latencyPenalty - awakePenalty).coerceIn(35, 100)
    }

    fun focusScore(
        recentEvents: List<DrowsinessEvent>,
        averageSleepMinutes: Int,
    ): Int {
        val eventPenalty = recentEvents.sumOf { it.severity * 5 }.coerceAtMost(35)
        val durationPenalty = recentEvents.sumOf { it.durationMinutes }.coerceAtMost(20)
        val sleepBoost = ((averageSleepMinutes - 360) / 6).coerceIn(0, 15)
        return (85 - eventPenalty - (durationPenalty / 3) + sleepBoost).coerceIn(25, 100)
    }
}
