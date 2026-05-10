package com.sleepcare.mobile.domain

import kotlinx.coroutines.flow.Flow

// 도메인 계층의 포트(인터페이스) 모음입니다.
// 화면과 ViewModel은 구현체를 몰라도 이 계약만 보고 데이터를 읽고 명령을 보냅니다.

// Health Connect 같은 외부 수면 데이터 공급원을 추상화합니다.
interface WatchSleepDataSource {
    suspend fun readRecentSleepSessions(): List<SleepSession>
}

// 모바일 앱이 Wear OS 워치와 세션/심박 메시지를 주고받는 계약입니다.
interface WatchSessionDataSource {
    fun observeConnectionState(): Flow<ConnectedDeviceState>
    fun observeHeartRateBatches(): Flow<WatchHeartRateBatch>
    fun observeSessionEvents(): Flow<WatchSessionEvent>
    suspend fun refreshConnection(): Boolean
    suspend fun startSession(
        config: WatchSessionConfig,
        targetPolicy: WatchCommandTargetPolicy = WatchCommandTargetPolicy.CapabilityOnly,
    ): Boolean
    suspend fun stopSession(
        sessionId: String,
        targetPolicy: WatchCommandTargetPolicy = WatchCommandTargetPolicy.CapabilityOnly,
    ): Boolean
    suspend fun acknowledgeCursor(
        cursor: WatchCursor,
        targetPolicy: WatchCommandTargetPolicy = WatchCommandTargetPolicy.CapabilityOnly,
    ): Boolean
    suspend fun requestBackfill(
        sessionId: String,
        fromSampleSeq: Long,
        targetPolicy: WatchCommandTargetPolicy = WatchCommandTargetPolicy.CapabilityOnly,
    ): Boolean
    suspend fun updateFlushPolicy(
        sessionId: String,
        flushPolicy: WatchFlushPolicy,
        targetPolicy: WatchCommandTargetPolicy = WatchCommandTargetPolicy.CapabilityOnly,
    ): Boolean
    suspend fun sendVibrationAlert(
        sessionId: String,
        level: Int,
        pattern: String,
        targetPolicy: WatchCommandTargetPolicy = WatchCommandTargetPolicy.CapabilityOnly,
    ): Boolean
    suspend fun disconnect()
}

// 모바일 앱과 Raspberry Pi의 NSD + WebSocket 통신을 추상화합니다.
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

// 수면, 졸음, 공부 계획을 입력받아 취침/기상 추천을 계산합니다.
interface RecommendationEngine {
    fun generate(input: RecommendationInput): RecommendationSnapshot
}

// 아래 Repository들은 UI가 로컬 DB, DataStore, 네트워크 세부 구현을 직접 알지 않게 해 줍니다.
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

// 개발자 모드에서 Pi 없이 폰-워치 Data Layer 계약만 검증하기 위한 포트입니다.
// 운영 공부 세션 저장/연결 흐름을 건드리지 않도록 별도 Repository로 둡니다.
interface WatchDebugRepository {
    fun observeDebugState(): Flow<WatchDebugState>
    suspend fun refreshConnection()
    suspend fun startTestSession()
    suspend fun sendFlushPolicy()
    suspend fun sendVibrationAlert()
    suspend fun sendAck()
    suspend fun requestBackfill()
    suspend fun stopTestSession()
}

// 개발자 모드에서 Pi 구현 상태를 단계별로 진단하기 위한 별도 Repository입니다.
// 운영 공부 세션 저장, 워치 Data Layer, PiNetworkDataSource의 자동 연결 흐름을 호출하지 않습니다.
interface PiDebugRepository {
    fun observeDebugState(): Flow<PiDebugState>
    suspend fun updateEndpoint(endpoint: PiDebugEndpoint)
    suspend fun setConnectionMode(mode: PiDebugConnectionMode)
    suspend fun readServerSpki()
    suspend fun generatePairingJson()
    suspend fun registerGeneratedPairingJson()
    suspend fun discoverNsdCandidates()
    suspend fun sendHello()
    suspend fun startEyeOnlySession()
    suspend fun startEyeWithSyntheticHrSession()
    suspend fun sendSyntheticHeartRate()
    suspend fun stopTestSession()
    suspend fun clearPacketLogs()
}

interface StudySessionRepository {
    fun observeSessionState(): Flow<StudySessionState>
    suspend fun startSession(mode: StudySessionMode = StudySessionMode.WatchAndEye)
    suspend fun stopSession()
}

interface SettingsRepository {
    fun observeOnboardingState(): Flow<OnboardingState>
    suspend fun setOnboardingCompleted(completed: Boolean)
    fun observeNotificationPreferences(): Flow<NotificationPreferences>
    suspend fun updateNotificationPreferences(preferences: NotificationPreferences)
    fun observeDeveloperModeEnabled(): Flow<Boolean>
    suspend fun setDeveloperModeEnabled(enabled: Boolean)
    fun observeUserGoals(): Flow<UserGoals>
    suspend fun updateUserGoals(goals: UserGoals)
    fun observeLastSyncState(): Flow<LastSyncState>
    suspend fun updateLastSyncState(state: LastSyncState)
    suspend fun resetAppData()
}

// 점수 계산은 저장소와 화면 테스트에서 재사용되므로 순수 함수로 분리해 둡니다.
object ScoreCalculator {
    // 총 수면 시간, 규칙성, 잠들기까지 걸린 시간, 중간 각성을 합쳐 35~100점으로 보정합니다.
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

    // 최근 졸음 이벤트가 많을수록 감점하고, 충분한 수면이 있으면 약간 보정합니다.
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
