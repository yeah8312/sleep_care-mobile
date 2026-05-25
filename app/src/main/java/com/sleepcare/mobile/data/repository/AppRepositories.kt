package com.sleepcare.mobile.data.repository

import com.sleepcare.mobile.data.local.DrowsinessEventDao
import com.sleepcare.mobile.data.local.ExamScheduleDao
import com.sleepcare.mobile.data.local.PreferencesStore
import com.sleepcare.mobile.data.local.RecommendationSnapshotDao
import com.sleepcare.mobile.data.local.SleepCareDatabase
import com.sleepcare.mobile.data.local.SleepSessionDao
import com.sleepcare.mobile.data.local.StudyPlanDao
import com.sleepcare.mobile.data.local.StudySessionDao
import com.sleepcare.mobile.data.local.toDomain
import com.sleepcare.mobile.data.local.toEntity
import com.sleepcare.mobile.data.source.PiPairingCodec
import com.sleepcare.mobile.domain.ConnectionStatus
import com.sleepcare.mobile.domain.ConnectedDeviceState
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.DeviceType
import com.sleepcare.mobile.domain.DrowsinessAnalysisSnapshot
import com.sleepcare.mobile.domain.DrowsinessEvent
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamSchedule
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.LastSyncState
import com.sleepcare.mobile.domain.NotificationPreferences
import com.sleepcare.mobile.domain.OnboardingState
import com.sleepcare.mobile.domain.PiAlertFire
import com.sleepcare.mobile.domain.PiNetworkDataSource
import com.sleepcare.mobile.domain.PiRiskUpdate
import com.sleepcare.mobile.domain.PiSessionSummary
import com.sleepcare.mobile.domain.RecommendationEngine
import com.sleepcare.mobile.domain.RecommendationInput
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.RecommendationSnapshot
import com.sleepcare.mobile.domain.RecommendationTip
import com.sleepcare.mobile.domain.ScoreCalculator
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.domain.SleepAnalysisSnapshot
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.domain.StudyPlan
import com.sleepcare.mobile.domain.StudyPlanRepository
import com.sleepcare.mobile.domain.StudySessionMode
import com.sleepcare.mobile.domain.StudySessionPhase
import com.sleepcare.mobile.domain.StudySessionRepository
import com.sleepcare.mobile.domain.StudySessionState
import com.sleepcare.mobile.domain.TrustedPiDevice
import com.sleepcare.mobile.domain.UserGoals
import com.sleepcare.mobile.domain.WatchCommandTargetPolicy
import com.sleepcare.mobile.domain.WatchFlushPolicy
import com.sleepcare.mobile.domain.WatchCursor
import com.sleepcare.mobile.domain.WatchDebugRepository
import com.sleepcare.mobile.domain.WatchDebugState
import com.sleepcare.mobile.domain.WatchHeartRateBatch
import com.sleepcare.mobile.domain.WatchSessionClosed
import com.sleepcare.mobile.domain.WatchSessionConfig
import com.sleepcare.mobile.domain.WatchSessionDataSource
import com.sleepcare.mobile.domain.WatchSessionError
import com.sleepcare.mobile.domain.WatchSessionEvent
import com.sleepcare.mobile.domain.WatchSessionReady
import com.sleepcare.mobile.data.source.HealthConnectSleepState
import com.sleepcare.mobile.data.source.HealthConnectSleepDataSource
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 앱의 실제 저장소 구현과 추천/분석 계산 함수를 모은 파일입니다.
// ViewModel은 아래 Repository 인터페이스만 보지만, 이 파일이 DB·DataStore·기기 통신을 이어 줍니다.

// Health Connect 수면 데이터를 로컬 DB 캐시로 동기화합니다.
@Singleton
class SleepRepositoryImpl @Inject constructor(
    private val sleepSessionDao: SleepSessionDao,
    private val sleepDataSource: HealthConnectSleepDataSource,
    private val preferencesStore: PreferencesStore,
) : SleepRepository {
    override fun observeSleepSessions(): Flow<List<com.sleepcare.mobile.domain.SleepSession>> =
        sleepSessionDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun seedIfEmpty() {
        refreshFromSource()
    }

    override suspend fun refreshFromSource() {
        val sessions = sleepDataSource.readRecentSleepSessions()
        if (sessions.isNotEmpty()) {
            // Health Connect를 현재의 진실한 원본으로 보고 전체 캐시를 최신 목록으로 교체합니다.
            sleepSessionDao.clear()
            sleepSessionDao.upsertAll(sessions.map { it.toEntity() })
            val current = preferencesStore.lastSyncState.first()
            preferencesStore.updateLastSyncState(current.copy(sleepSyncedAt = LocalDateTime.now()))
        } else if (sleepDataSource.state.value.shouldClearCachedSleep()) {
            sleepSessionDao.clear()
        }
    }
}

// Raspberry Pi에서 들어오는 alert.fire 이벤트를 졸음 이벤트 테이블에 계속 누적합니다.
@Singleton
class DrowsinessRepositoryImpl @Inject constructor(
    private val drowsinessEventDao: DrowsinessEventDao,
    private val piNetworkDataSource: PiNetworkDataSource,
    private val preferencesStore: PreferencesStore,
) : DrowsinessRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 앱이 살아 있는 동안 Pi 알림 Flow를 구독해 분석 화면 데이터로 변환합니다.
        scope.launch {
            piNetworkDataSource.observeAlerts().collect { alert ->
                drowsinessEventDao.upsertAll(listOf(alert.toEvent().toEntity()))
                val current = preferencesStore.lastSyncState.first()
                preferencesStore.updateLastSyncState(current.copy(drowsinessSyncedAt = alert.receivedAt))
            }
        }
    }

    override fun observeDrowsinessEvents(): Flow<List<com.sleepcare.mobile.domain.DrowsinessEvent>> =
        drowsinessEventDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun seedIfEmpty() = Unit

    override suspend fun refreshFromSource() = Unit
}

// 공부 계획은 한 개의 기본 플랜을 유지하고, 없으면 MVP 기본값을 심어 줍니다.
@Singleton
class StudyPlanRepositoryImpl @Inject constructor(
    private val studyPlanDao: StudyPlanDao,
) : StudyPlanRepository {
    override fun observeStudyPlan(): Flow<StudyPlan?> = studyPlanDao.observeById().map { it?.toDomain() }

    override suspend fun seedIfEmpty() {
        if (studyPlanDao.count() == 0) {
            upsert(
                StudyPlan(
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(22, 30),
                    focusHours = 8,
                    days = setOf(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY,
                        DayOfWeek.SATURDAY,
                    ),
                    breakPreferenceMinutes = 15,
                    autoBreakEnabled = true,
                )
            )
        }
    }

    override suspend fun upsert(plan: StudyPlan) {
        studyPlanDao.upsert(plan.toEntity())
    }
}

// 시험 일정은 추천 기상 시각 계산에서 가까운 시험을 찾는 입력으로 쓰입니다.
@Singleton
class ExamScheduleRepositoryImpl @Inject constructor(
    private val examScheduleDao: ExamScheduleDao,
) : ExamScheduleRepository {
    override fun observeExamSchedules(): Flow<List<ExamSchedule>> =
        examScheduleDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun seedIfEmpty() {
        if (examScheduleDao.count() == 0) {
            upsert(
                ExamSchedule(
                    name = "모의고사",
                    date = LocalDate.now().plusDays(15),
                    startTime = LocalTime.of(7, 0),
                    endTime = LocalTime.of(12, 0),
                    location = "본관 2층",
                    priority = 1,
                    syncEnabled = true,
                )
            )
            upsert(
                ExamSchedule(
                    name = "수학 특강 테스트",
                    date = LocalDate.now().plusDays(5),
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(10, 30),
                    location = "스터디룸 A",
                    priority = 2,
                    syncEnabled = false,
                )
            )
        }
    }

    override suspend fun upsert(examSchedule: ExamSchedule) {
        examScheduleDao.upsert(examSchedule.toEntity())
    }

    override suspend fun delete(examId: Long) {
        examScheduleDao.delete(examId)
    }
}

// 여러 저장소의 현재 값을 모아 추천 엔진에 넣고, 결과 스냅샷을 저장합니다.
@Singleton
class RecommendationRepositoryImpl @Inject constructor(
    private val recommendationSnapshotDao: RecommendationSnapshotDao,
    private val sleepRepository: SleepRepository,
    private val drowsinessRepository: DrowsinessRepository,
    private val studyPlanRepository: StudyPlanRepository,
    private val examScheduleRepository: ExamScheduleRepository,
    private val settingsRepository: SettingsRepository,
    private val recommendationEngine: RecommendationEngine,
) : RecommendationRepository {
    override fun observeLatestRecommendation(): Flow<RecommendationSnapshot?> =
        recommendationSnapshotDao.observeLatest().map { it?.toDomain() }

    override suspend fun refreshRecommendations() {
        val snapshot = recommendationEngine.generate(
            RecommendationInput(
                sleepSessions = sleepRepository.observeSleepSessions().first(),
                drowsinessEvents = drowsinessRepository.observeDrowsinessEvents().first(),
                studyPlan = studyPlanRepository.observeStudyPlan().first(),
                exams = examScheduleRepository.observeExamSchedules().first(),
                userGoals = settingsRepository.observeUserGoals().first(),
            )
        )
        recommendationSnapshotDao.upsert(snapshot.toEntity())
    }
}

// 기기 연결 화면이 Pi와 Watch 상태를 하나의 리스트로 볼 수 있게 합칩니다.
@Singleton
class DeviceConnectionRepositoryImpl @Inject constructor(
    private val piNetworkDataSource: PiNetworkDataSource,
    private val watchSessionDataSource: WatchSessionDataSource,
    private val preferencesStore: PreferencesStore,
) : DeviceConnectionRepository {
    override fun observeDevices(): Flow<List<ConnectedDeviceState>> =
        combine(piNetworkDataSource.observeConnectionState(), watchSessionDataSource.observeConnectionState()) { pi, watch ->
            listOf(pi, watch)
        }

    override fun observeTrustedPi(): Flow<TrustedPiDevice?> = preferencesStore.trustedPiDevice

    override suspend fun startScan() {
        watchSessionDataSource.refreshConnection()
        piNetworkDataSource.discoverAndConnect()
    }

    override suspend fun retryConnection(deviceType: DeviceType) {
        when (deviceType) {
            DeviceType.RaspberryPi -> piNetworkDataSource.retry()
            DeviceType.Smartwatch -> watchSessionDataSource.refreshConnection()
        }
    }

    override suspend fun disconnect(deviceType: DeviceType) {
        when (deviceType) {
            DeviceType.RaspberryPi -> piNetworkDataSource.disconnect()
            DeviceType.Smartwatch -> watchSessionDataSource.disconnect()
        }
    }

    override suspend fun registerPiFromQr(rawPayload: String): Result<TrustedPiDevice> = runCatching {
        val trustedPi = PiPairingCodec.toTrustedDevice(PiPairingCodec.parse(rawPayload))
        preferencesStore.updateTrustedPiDevice(trustedPi)
        piNetworkDataSource.disconnect()
        piNetworkDataSource.discoverAndConnect()
        trustedPi
    }

    override suspend fun forgetPi() {
        piNetworkDataSource.disconnect()
        preferencesStore.clearTrustedPiDevice()
    }
}

// 개발자 모드의 워치 통신 테스트는 실제 공부 세션/Pi 연결과 분리해 Data Layer만 직접 검증합니다.
// 같은 WatchSessionDataSource를 쓰므로 capability 확인, 메시지 경로, codec 계약은 운영 경로와 동일합니다.
@Singleton
class WatchDebugRepositoryImpl @Inject constructor(
    private val watchSessionDataSource: WatchSessionDataSource,
) : WatchDebugRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debugState = MutableStateFlow(WatchDebugState())

    init {
        scope.launch {
            watchSessionDataSource.observeSessionEvents().collect { event ->
                val currentSessionId = debugState.value.sessionId ?: return@collect
                if (event.sessionId != currentSessionId) return@collect
                debugState.update { current ->
                    current.copy(lastSessionEvent = event.toDebugSummary())
                }
            }
        }
        scope.launch {
            watchSessionDataSource.observeHeartRateBatches().collect { batch ->
                val currentSessionId = debugState.value.sessionId ?: return@collect
                if (batch.sessionId != currentSessionId) return@collect
                val latestSampleSeq = batch.samples.maxOfOrNull { it.sampleSeq } ?: batch.messageSequence
                debugState.update { current ->
                    current.copy(
                        latestSampleSeq = maxOf(current.latestSampleSeq ?: 0L, latestSampleSeq),
                        latestHeartRateSummary = batch.toDebugSummary(latestSampleSeq),
                    )
                }
            }
        }
        scope.launch {
            watchSessionDataSource.observeConnectionState().collect { connection ->
                debugState.update { current ->
                    current.copy(watchConnectionDetails = connection.details ?: connection.status.name)
                }
            }
        }
    }

    override fun observeDebugState(): Flow<WatchDebugState> = debugState

    override suspend fun refreshConnection() {
        runWatchCommand("워치 연결 새로고침") {
            watchSessionDataSource.refreshConnection()
        }
    }

    override suspend fun startTestSession() {
        val sessionId = "watch-debug-${LocalDate.now()}-${UUID.randomUUID().toString().take(8)}"
        val currentConnectionDetails = debugState.value.watchConnectionDetails
        debugState.value = WatchDebugState(
            sessionId = sessionId,
            commandInFlight = true,
            lastCommandStatus = "워치 테스트 세션 시작 요청 중",
            watchConnectionDetails = currentConnectionDetails,
        )
        // 개발자 테스트 세션은 Pi session.open이나 Room 저장을 거치지 않습니다.
        // capability를 먼저 갱신한 뒤, capability 탐지가 실패해도 페어링된 Wear OS 노드로 보내 실제 수신 여부를 분리 진단합니다.
        watchSessionDataSource.refreshConnection()
        val success = watchSessionDataSource.startSession(
            WatchSessionConfig(
                sessionId = sessionId,
                studyMode = "debug-watch",
                hrRequired = true,
                watchVibrationEnabled = true,
            ),
            targetPolicy = WatchCommandTargetPolicy.DebugAllowPairedFallback,
        )
        debugState.update { current ->
            current.copy(
                commandInFlight = false,
                lastCommandStatus = if (success) {
                    "워치 테스트 세션 시작 요청 전송됨 · ready/error 대기 중"
                } else {
                    "워치 테스트 세션 시작 실패"
                },
            )
        }
    }

    override suspend fun sendFlushPolicy() {
        withDebugSession("Flush policy 전송") { sessionId ->
            watchSessionDataSource.updateFlushPolicy(
                sessionId = sessionId,
                flushPolicy = WatchFlushPolicy(normalSec = 15, suspectSec = 5, alertSec = 2),
                targetPolicy = WatchCommandTargetPolicy.DebugAllowPairedFallback,
            )
        }
    }

    override suspend fun sendVibrationAlert() {
        withDebugSession("진동 테스트 전송") { sessionId ->
            watchSessionDataSource.sendVibrationAlert(
                sessionId = sessionId,
                level = 2,
                pattern = "200,100,200",
                targetPolicy = WatchCommandTargetPolicy.DebugAllowPairedFallback,
            )
        }
    }

    override suspend fun sendAck() {
        withDebugSession("ACK 전송") { sessionId ->
            val latestSampleSeq = debugState.value.latestSampleSeq ?: 0L
            watchSessionDataSource.acknowledgeCursor(
                WatchCursor(
                    sessionId = sessionId,
                    highestContiguousSampleSeq = latestSampleSeq,
                    lastAckSentAt = LocalDateTime.now(),
                ),
                targetPolicy = WatchCommandTargetPolicy.DebugAllowPairedFallback,
            )
        }
    }

    override suspend fun requestBackfill() {
        withDebugSession("Backfill 요청") { sessionId ->
            val latestSampleSeq = debugState.value.latestSampleSeq
            val fromSampleSeq = latestSampleSeq?.let { maxOf(1L, it - 2L) } ?: 1L
            watchSessionDataSource.requestBackfill(
                sessionId = sessionId,
                fromSampleSeq = fromSampleSeq,
                targetPolicy = WatchCommandTargetPolicy.DebugAllowPairedFallback,
            )
        }
    }

    override suspend fun stopTestSession() {
        withDebugSession("테스트 세션 종료") { sessionId ->
            watchSessionDataSource.stopSession(
                sessionId = sessionId,
                targetPolicy = WatchCommandTargetPolicy.DebugAllowPairedFallback,
            )
        }
    }

    private suspend fun withDebugSession(
        label: String,
        block: suspend (String) -> Boolean,
    ) {
        val sessionId = debugState.value.sessionId
        if (sessionId == null) {
            debugState.update { current -> current.copy(lastCommandStatus = "테스트 세션을 먼저 시작해 주세요.") }
            return
        }
        runWatchCommand(label) { block(sessionId) }
    }

    private suspend fun runWatchCommand(
        label: String,
        block: suspend () -> Boolean,
    ) {
        debugState.update { current ->
            current.copy(commandInFlight = true, lastCommandStatus = "$label 요청 중")
        }
        val success = runCatching { block() }.getOrDefault(false)
        debugState.update { current ->
            current.copy(
                commandInFlight = false,
                lastCommandStatus = if (success) "$label 성공" else "$label 실패",
            )
        }
    }
}

private fun WatchSessionEvent.toDebugSummary(): String = when (this) {
    is WatchSessionReady -> "session.ready · ${sensorBackend} · $trackerMode"
    is WatchSessionError -> "session.error · $code · $detailMessage"
    is WatchSessionClosed -> buildString {
        append("session.closed · ")
        append(reason)
        finalSampleSeq?.let { append(" · final sample $it") }
    }
}

private fun WatchHeartRateBatch.toDebugSummary(latestSampleSeq: Long): String =
    "HR ${samples.size}개 · $deliveryMode · latest sample $latestSampleSeq"

// 공부 세션의 오케스트레이터입니다.
// 워치 센서 시작, Pi 세션 열기, 심박 샘플 릴레이, 알림 진동, 세션 요약 저장을 한곳에서 조율합니다.
@Singleton
class StudySessionRepositoryImpl @Inject constructor(
    private val studySessionDao: StudySessionDao,
    private val piNetworkDataSource: PiNetworkDataSource,
    private val watchSessionDataSource: WatchSessionDataSource,
    private val watchRelayStore: WatchRelayStore,
) : StudySessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionState = MutableStateFlow(
        StudySessionState(message = "Galaxy Watch와 라즈베리파이를 연결하면 학습 세션을 시작할 수 있습니다.")
    )
    private val alertCounts = mutableMapOf<String, Int>()

    init {
        scope.launch {
            // 워치 심박 배치는 먼저 로컬에 저장하고, 새 샘플만 Pi로 전달합니다.
            // Eye-only 세션은 워치 샘플을 기대하지 않으므로 현재 세션과 겹치는 배치는 무시합니다.
            watchSessionDataSource.observeHeartRateBatches().collect { batch ->
                val current = sessionState.value
                if (current.sessionId == batch.sessionId && current.mode == StudySessionMode.EyeOnly) {
                    return@collect
                }
                val result = watchRelayStore.recordIncomingBatch(batch)
                val deliveredSampleSeqs = piNetworkDataSource.sendHeartRateSamples(result.newSamples)
                if (deliveredSampleSeqs.isNotEmpty()) {
                    watchRelayStore.markForwarded(batch.sessionId, deliveredSampleSeqs)
                }
                val cursor = watchRelayStore.touchCursorAck(result.cursor)
                watchSessionDataSource.acknowledgeCursor(cursor)
                cursor.pendingBackfillFrom?.let { missingSeq ->
                    watchSessionDataSource.requestBackfill(batch.sessionId, missingSeq)
                }
            }
        }
        scope.launch {
            // 워치 세션 준비/오류/종료 이벤트는 현재 세션과 일치할 때만 UI 상태에 반영합니다.
            watchSessionDataSource.observeSessionEvents().collect { event ->
                when (event) {
                    is WatchSessionReady -> {
                        sessionState.update { current ->
                            if (current.sessionId != event.sessionId) current
                            else current.copy(message = "Galaxy Watch가 세션 준비를 마쳤습니다.")
                        }
                    }

                    is WatchSessionError -> {
                        sessionState.update { current ->
                            if (current.sessionId != event.sessionId) current
                            else current.copy(
                                phase = StudySessionPhase.Error,
                                message = "Galaxy Watch 오류: ${event.detailMessage}",
                            )
                        }
                        persistCurrentState()
                    }

                    is WatchSessionClosed -> {
                        sessionState.update { current ->
                            if (current.sessionId != event.sessionId || current.phase == StudySessionPhase.Idle) current
                            else current.copy(
                                message = "Galaxy Watch 세션 종료 완료",
                            )
                        }
                    }
                }
            }
        }
        scope.launch {
            // Pi가 재연결되면 이전에 못 보낸 pending 심박 샘플을 다시 밀어 넣습니다.
            piNetworkDataSource.observeConnectionState().collect { state ->
                if (state.status != ConnectionStatus.Connected) return@collect
                watchRelayStore.getPendingSessionIds().forEach { sessionId ->
                    val deliveredSampleSeqs = piNetworkDataSource.sendHeartRateSamples(
                        watchRelayStore.getPendingSamples(sessionId)
                    )
                    if (deliveredSampleSeqs.isNotEmpty()) {
                        watchRelayStore.markForwarded(sessionId, deliveredSampleSeqs)
                    }
                }
            }
        }
        scope.launch {
            // Pi 위험도가 올라가면 UI 상태를 갱신하고, 워치 포함 모드일 때만 전송 주기를 조정합니다.
            piNetworkDataSource.observeRiskState().collect { risk ->
                if (risk == null) return@collect
                val current = sessionState.value
                if (
                    risk.sessionId == current.sessionId &&
                    current.mode == StudySessionMode.WatchAndEye &&
                    risk.recommendedFlushSec != null
                ) {
                    watchSessionDataSource.updateFlushPolicy(
                        sessionId = risk.sessionId,
                        flushPolicy = WatchFlushPolicy(
                            normalSec = 15,
                            suspectSec = risk.recommendedFlushSec.coerceAtLeast(2),
                            alertSec = 2,
                        ),
                    )
                }
                sessionState.update { current ->
                    if (current.sessionId != risk.sessionId) current
                    else current.copy(
                        phase = if (risk.state.equals("ALERTING", ignoreCase = true)) {
                            StudySessionPhase.Alerting
                        } else {
                            StudySessionPhase.Running
                        },
                        latestRisk = risk,
                        message = risk.state.toSessionMessage(),
                    )
                }
                persistCurrentState()
            }
        }
        scope.launch {
            // alert.fire는 워치 포함 모드에서만 진동으로 전달하고, 공통으로 세션별 알림 횟수를 누적합니다.
            piNetworkDataSource.observeAlerts().collect { alert ->
                val current = sessionState.value
                val usesWatch = current.sessionId == alert.sessionId && current.mode == StudySessionMode.WatchAndEye
                if (usesWatch) {
                    watchSessionDataSource.sendVibrationAlert(
                        sessionId = alert.sessionId,
                        level = alert.level,
                        pattern = "200,100,200,100,400",
                    )
                }
                alertCounts[alert.sessionId] = (alertCounts[alert.sessionId] ?: 0) + 1
                sessionState.update { current ->
                    if (current.sessionId != alert.sessionId) current
                    else current.copy(
                        phase = StudySessionPhase.Alerting,
                        latestAlert = alert,
                        message = "라즈베리파이가 즉시 각성 알림을 보냈습니다.",
                    )
                }
                persistCurrentState()
            }
        }
        scope.launch {
            // Pi의 session.summary가 최종 종료 기록이므로 로컬 세션 행을 마감합니다.
            piNetworkDataSource.observeSessionSummaries().collect { summary ->
                persistSummary(summary)
                alertCounts.remove(summary.sessionId)
                sessionState.value = StudySessionState(
                    latestSummary = summary,
                    message = "세션 요약이 저장되었습니다.",
                )
            }
        }
    }

    override fun observeSessionState(): Flow<StudySessionState> = sessionState

    override suspend fun startSession(mode: StudySessionMode) {
        val current = sessionState.value
        // 이미 시작/종료 진행 중인 세션이 있으면 중복 시작을 막습니다.
        if (current.phase in listOf(
                StudySessionPhase.ArmingWatch,
                StudySessionPhase.DiscoveringPi,
                StudySessionPhase.ConnectingPi,
                StudySessionPhase.OpeningSession,
                StudySessionPhase.Running,
                StudySessionPhase.Alerting,
                StudySessionPhase.Stopping,
            )
        ) {
            return
        }

        val startedAt = LocalDateTime.now()
        val sessionId = "study-${startedAt.toLocalDate()}-${UUID.randomUUID().toString().take(8)}"
        sessionState.value = StudySessionState(
            sessionId = sessionId,
            phase = if (mode == StudySessionMode.WatchAndEye) {
                StudySessionPhase.ArmingWatch
            } else {
                StudySessionPhase.DiscoveringPi
            },
            mode = mode,
            startedAt = startedAt,
            message = if (mode == StudySessionMode.WatchAndEye) {
                "Galaxy Watch 세션을 준비하는 중입니다."
            } else {
                "워치 없이 Pi 카메라만으로 세션을 준비합니다."
            },
        )
        persistCurrentState()

        if (mode == StudySessionMode.WatchAndEye) {
            val watchReady = watchSessionDataSource.refreshConnection()
            if (!watchReady) {
                sessionState.value = StudySessionState(
                    sessionId = sessionId,
                    phase = StudySessionPhase.Error,
                    mode = mode,
                    startedAt = startedAt,
                    message = "Galaxy Watch 연결을 찾지 못했습니다.",
                )
                persistCurrentState()
                return
            }

            // 워치가 실제 센서 세션을 준비했다는 ready 응답을 기다린 뒤 Pi 세션을 엽니다.
            val watchStarted = watchSessionDataSource.startSession(WatchSessionConfig(sessionId = sessionId))
            if (!watchStarted) {
                sessionState.value = StudySessionState(
                    sessionId = sessionId,
                    phase = StudySessionPhase.Error,
                    mode = mode,
                    startedAt = startedAt,
                    message = "Galaxy Watch 세션 시작에 실패했습니다.",
                )
                persistCurrentState()
                return
            }

            val watchPrepared = withTimeoutOrNull(8_000) {
                watchSessionDataSource.observeSessionEvents()
                    .filter { event ->
                        event.sessionId == sessionId &&
                            (event is WatchSessionReady || event is WatchSessionError)
                    }
                    .first()
            }
            when (watchPrepared) {
                is WatchSessionError -> {
                    watchSessionDataSource.stopSession(sessionId)
                    sessionState.value = StudySessionState(
                        sessionId = sessionId,
                        phase = StudySessionPhase.Error,
                        mode = mode,
                        startedAt = startedAt,
                        message = "Galaxy Watch 오류: ${watchPrepared.detailMessage}",
                    )
                    persistCurrentState()
                    return
                }

                is WatchSessionReady -> Unit

                is WatchSessionClosed -> {
                    sessionState.value = StudySessionState(
                        sessionId = sessionId,
                        phase = StudySessionPhase.Error,
                        mode = mode,
                        startedAt = startedAt,
                        message = "Galaxy Watch가 준비 전에 세션을 종료했습니다. 다시 시도해 주세요.",
                    )
                    persistCurrentState()
                    return
                }

                null -> {
                    watchSessionDataSource.stopSession(sessionId)
                    sessionState.value = StudySessionState(
                        sessionId = sessionId,
                        phase = StudySessionPhase.Error,
                        mode = mode,
                        startedAt = startedAt,
                        message = "Galaxy Watch 준비 응답이 시간 내에 오지 않았습니다.",
                    )
                    persistCurrentState()
                    return
                }
            }
        }

        sessionState.value = sessionState.value.copy(
            phase = StudySessionPhase.DiscoveringPi,
            message = "로컬 Wi-Fi에서 라즈베리파이를 찾는 중입니다.",
        )
        persistCurrentState()

        // Pi 연결 실패 시 워치 세션도 함께 닫아 양쪽 상태가 엇갈리지 않게 합니다.
        val connected = piNetworkDataSource.discoverAndConnect()
        if (!connected) {
            if (mode == StudySessionMode.WatchAndEye) {
                watchSessionDataSource.stopSession(sessionId)
            }
            sessionState.value = StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Error,
                mode = mode,
                startedAt = startedAt,
                message = "라즈베리파이 연결에 실패했습니다.",
            )
            persistCurrentState()
            return
        }

        sessionState.value = sessionState.value.copy(
            phase = StudySessionPhase.OpeningSession,
            message = "학습 세션을 여는 중입니다.",
        )
        persistCurrentState()

        val opened = piNetworkDataSource.startSession(
            sessionId = sessionId,
            watchAvailable = mode == StudySessionMode.WatchAndEye,
            eyeOnly = mode == StudySessionMode.EyeOnly,
        )
        sessionState.value = if (opened) {
            StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Running,
                mode = mode,
                startedAt = startedAt,
                message = if (mode == StudySessionMode.WatchAndEye) {
                    "학습 세션이 진행 중입니다."
                } else {
                    "Eye only 세션이 진행 중입니다."
                },
            )
        } else {
            if (mode == StudySessionMode.WatchAndEye) {
                watchSessionDataSource.stopSession(sessionId)
            }
            StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Error,
                mode = mode,
                startedAt = startedAt,
                message = "라즈베리파이가 세션 시작을 승인하지 않았습니다.",
            )
        }
        persistCurrentState()
    }

    override suspend fun stopSession() {
        val current = sessionState.value
        val sessionId = current.sessionId ?: return
        sessionState.value = current.copy(
            phase = StudySessionPhase.Stopping,
            message = "학습 세션을 종료하는 중입니다.",
        )
        persistCurrentState()

        if (current.mode == StudySessionMode.WatchAndEye) {
            watchSessionDataSource.stopSession(sessionId)
            withTimeoutOrNull(5_000) {
                watchSessionDataSource.observeSessionEvents()
                    .filter { it.sessionId == sessionId && it is WatchSessionClosed }
                    .first()
            }
        }
        val summary = piNetworkDataSource.stopSession(sessionId)
        if (summary == null) {
            persistCurrentState(endedAt = LocalDateTime.now())
            sessionState.value = StudySessionState(
                message = "세션 종료 응답이 없어서 로컬 상태만 정리했습니다.",
            )
        }
    }

    private suspend fun persistCurrentState(endedAt: LocalDateTime? = null) {
        val current = sessionState.value
        val sessionId = current.sessionId ?: return
        val existing = studySessionDao.getById(sessionId)
        val alertCount = alertCounts[sessionId] ?: existing?.alertCount ?: 0
        // 부분 업데이트가 자주 일어나므로 기존 값과 새 값을 합쳐 한 행으로 덮어씁니다.
        studySessionDao.upsert(
            com.sleepcare.mobile.data.local.StudySessionEntity(
                id = sessionId,
                startedAt = current.startedAt ?: existing?.startedAt ?: LocalDateTime.now(),
                endedAt = endedAt ?: existing?.endedAt,
                phase = current.phase.name,
                latestRiskState = current.latestRisk?.state ?: existing?.latestRiskState,
                latestFusedScore = current.latestRisk?.fusedScore ?: existing?.latestFusedScore,
                alertCount = alertCount,
                summaryMode = current.latestSummary?.mode ?: existing?.summaryMode,
                summaryReason = current.latestSummary?.summaryReason ?: existing?.summaryReason,
                peakFusedScore = current.latestSummary?.peakFusedScore ?: existing?.peakFusedScore,
            )
        )
    }

    private suspend fun persistSummary(summary: PiSessionSummary) {
        val existing = studySessionDao.getById(summary.sessionId)
        studySessionDao.upsert(
            com.sleepcare.mobile.data.local.StudySessionEntity(
                id = summary.sessionId,
                startedAt = existing?.startedAt ?: summary.receivedAt,
                endedAt = summary.receivedAt,
                phase = summary.finalState,
                latestRiskState = summary.finalState,
                latestFusedScore = summary.peakFusedScore ?: existing?.latestFusedScore,
                alertCount = maxOf(existing?.alertCount ?: 0, summary.totalAlerts),
                summaryMode = summary.mode,
                summaryReason = summary.summaryReason,
                peakFusedScore = summary.peakFusedScore,
            )
        )
    }
}

// DataStore 설정과 Room 데이터를 묶어 설정 화면의 저장/초기화를 담당합니다.
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val preferencesStore: PreferencesStore,
    private val database: SleepCareDatabase,
) : SettingsRepository {
    override fun observeOnboardingState(): Flow<OnboardingState> = preferencesStore.onboardingState

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        preferencesStore.setOnboardingCompleted(completed)
    }

    override fun observeNotificationPreferences(): Flow<NotificationPreferences> = preferencesStore.notificationPreferences

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        preferencesStore.updateNotificationPreferences(preferences)
    }

    override fun observeDeveloperModeEnabled(): Flow<Boolean> = preferencesStore.developerModeEnabled

    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        preferencesStore.setDeveloperModeEnabled(enabled)
    }

    override fun observeUserGoals(): Flow<UserGoals> = preferencesStore.userGoals

    override suspend fun updateUserGoals(goals: UserGoals) {
        preferencesStore.updateUserGoals(goals)
    }

    override fun observeLastSyncState(): Flow<LastSyncState> = preferencesStore.lastSyncState

    override suspend fun updateLastSyncState(state: LastSyncState) {
        preferencesStore.updateLastSyncState(state)
    }

    override suspend fun resetAppData() {
        database.clearAllTables()
        preferencesStore.clear()
    }
}

// 규칙 기반 추천 엔진입니다.
// 최근 수면, 졸음 이벤트, 시험 일정, 사용자 목표를 조합해 권장 취침/기상 시간을 만듭니다.
@Singleton
class SleepCareRecommendationEngine @Inject constructor() : RecommendationEngine {
    override fun generate(input: RecommendationInput): RecommendationSnapshot {
        val generatedAt = input.generatedAt
        val recentSleep = input.sleepSessions.sortedByDescending { it.startTime }.take(3)
        val recentDrowsiness = input.drowsinessEvents.sortedByDescending { it.timestamp }.take(5)
        val averageSleepMinutes = recentSleep.map { it.totalMinutes.toDouble() }.averageOrNull()?.toInt()
        val needsExtraRecovery = (averageSleepMinutes != null && averageSleepMinutes < 390) || recentDrowsiness.size >= 3
        val targetSleepMinutes = if (needsExtraRecovery) 480 else 450

        // 2주 안의 가장 가까운 시험은 목표 기상 시각을 앞당기는 가장 강한 신호로 봅니다.
        val examWakeCandidate = input.exams
            .filter { !it.date.isBefore(generatedAt.toLocalDate()) && !it.date.isAfter(generatedAt.toLocalDate().plusDays(14)) }
            .minWithOrNull(compareBy<ExamSchedule> { it.date }.thenBy { it.startTime })
            ?.let { it.startTime.minusMinutes(90) }

        // 명시 목표가 없으면 공부 시작 90분 전, 그것도 없으면 06:30을 기본 기상 시각으로 둡니다.
        val baselineWakeTime = examWakeCandidate
            ?: input.userGoals.targetWakeTime
            ?: input.studyPlan?.startTime?.minusMinutes(90)
            ?: LocalTime.of(6, 30)

        val bedtime = baselineWakeTime.minusMinutes(targetSleepMinutes.toLong()).minusMinutes(15)
        val currentAverageBedtime = recentSleep
            .map { it.startTime.toLocalTime().toSecondOfDay().toDouble() }
            .averageOrNull()
            ?.let { LocalTime.ofSecondOfDay(it.toLong()) }
            ?: bedtime.plusMinutes(20)
        val routineShiftMinutes = Duration.between(bedtime, currentAverageBedtime).toMinutes().toInt().coerceIn(-180, 180)

        val reason = when {
            examWakeCandidate != null && averageSleepMinutes == null ->
                "시험 일정과 최근 졸음 패턴을 반영했어요. 수면 기록 보정은 Health Connect 연동 후 추가됩니다."
            examWakeCandidate != null ->
                "시험 대비 기상 리듬과 최근 컨디션을 함께 반영했어요."
            averageSleepMinutes == null && recentDrowsiness.isNotEmpty() ->
                "라즈베리파이의 최근 졸음 이벤트와 학습 계획 기준으로 루틴을 제안했어요."
            averageSleepMinutes == null ->
                "수면 기록 없이도 학습 계획과 사용자 목표를 기반으로 기본 루틴을 제안합니다."
            needsExtraRecovery ->
                "최근 수면 부족과 졸음 신호를 함께 반영했어요."
            else ->
                "현재 루틴을 조금만 조정하면 집중력이 더 좋아져요."
        }

        val tips = buildList {
            add(
                RecommendationTip(
                    title = "카페인 컷오프",
                    body = "${baselineWakeTime.minusHours(4)} 이후 카페인을 줄이면 취침 준비가 쉬워집니다.",
                    iconKey = "coffee",
                )
            )
            add(
                RecommendationTip(
                    title = "집중 블록",
                    body = "${baselineWakeTime.plusHours(1)}부터 2시간은 가장 어려운 과목에 배정해 보세요.",
                    iconKey = "focus",
                )
            )
            add(
                RecommendationTip(
                    title = if (averageSleepMinutes == null) "수면 연동 안내" else "회복 루틴",
                    body = if (averageSleepMinutes == null) {
                        "Health Connect 수면 동기화가 붙으면 실제 수면 기록을 반영해 추천을 더 정교하게 조정합니다."
                    } else if (needsExtraRecovery) {
                        "오후 ${recentDrowsiness.lastOrNull()?.timestamp?.toLocalTime() ?: LocalTime.of(14, 30)} 전후 15분 휴식을 권장합니다."
                    } else {
                        "${bedtime.minusMinutes(45)}부터 조명을 낮추고 복습 강도를 줄여 보세요."
                    },
                    iconKey = if (averageSleepMinutes == null) "sync" else "rest",
                )
            )
        }

        return RecommendationSnapshot(
            recommendedBedtime = bedtime,
            recommendedWakeTime = baselineWakeTime,
            targetSleepMinutes = targetSleepMinutes,
            reason = reason,
            routineShiftMinutes = routineShiftMinutes,
            tips = tips.take(3),
            generatedAt = generatedAt,
        )
    }
}

// 수면 세션 목록을 화면 지표로 바꾸는 순수 계산 함수입니다.
fun buildSleepAnalysisSnapshot(sessions: List<com.sleepcare.mobile.domain.SleepSession>): SleepAnalysisSnapshot {
    val recent = buildWeeklySleepDaySummaries(sessions).take(7)
    if (recent.isEmpty()) {
        return SleepAnalysisSnapshot(
            score = 0,
            averageMinutes = 0,
            consistency = 0,
            latencyMinutes = 0,
            awakeMinutes = 0,
            weeklyDurations = emptyList(),
            isAvailable = false,
            emptyReason = "Health Connect 수면 데이터가 아직 없습니다. 권한, 가용성, 또는 실제 기록 여부를 확인해 주세요.",
        )
    }
    val averageMinutes = recent.map { it.totalMinutes }.average().toInt()
    val consistency = calculateSleepRegularityScore(recent)
    val awakeMinutes = recent.map { it.primarySession.awakeMinutes }.average().toInt()
    return SleepAnalysisSnapshot(
        score = ScoreCalculator.sleepQuality(
            totalMinutes = averageMinutes,
            consistencyScore = consistency,
            latencyMinutes = 0,
            awakeMinutes = awakeMinutes,
        ),
        averageMinutes = averageMinutes,
        consistency = consistency,
        latencyMinutes = 0,
        awakeMinutes = awakeMinutes,
        weeklyDurations = recent.map { it.totalMinutes },
        isAvailable = true,
        emptyReason = null,
    )
}

// 홈 화면은 최신 밤의 총 수면 시간을 한 장의 대표 세션처럼 보여줍니다.
fun buildLatestHomeSleepSession(
    sessions: List<com.sleepcare.mobile.domain.SleepSession>,
): com.sleepcare.mobile.domain.SleepSession? {
    val latestDay = buildWeeklySleepDaySummaries(sessions).firstOrNull() ?: return null
    val primary = latestDay.primarySession
    return primary.copy(
        totalMinutes = latestDay.totalMinutes,
        sleepScore = ScoreCalculator.sleepQuality(
            totalMinutes = latestDay.totalMinutes,
            consistencyScore = primary.consistencyScore,
            latencyMinutes = 0,
            awakeMinutes = primary.awakeMinutes,
        ),
        latencyMinutes = 0,
    )
}

// 같은 밤에 끊겨 기록된 수면을 합치고 하루 단위 요약으로 변환합니다.
fun buildWeeklySleepDaySummaries(
    sessions: List<com.sleepcare.mobile.domain.SleepSession>,
): List<com.sleepcare.mobile.domain.SleepDaySummary> =
    mergeNearbyNightSleepSessions(sessions)
        .groupBy { it.endTime.toLocalDate() }
        .mapNotNull { (date, daySessions) ->
            val primary = daySessions.maxWithOrNull(
                compareBy<com.sleepcare.mobile.domain.SleepSession>(
                    { if (it.startTime.toLocalDate() != it.endTime.toLocalDate()) 1 else 0 },
                    { if (it.endTime.hour in 0..11) 1 else 0 },
                    { if (it.startTime.hour >= 18 || it.startTime.hour <= 10) 1 else 0 },
                    { it.totalMinutes },
                )
            ) ?: return@mapNotNull null
            val totalMinutes = daySessions.sumOf { it.totalMinutes }
            com.sleepcare.mobile.domain.SleepDaySummary(
                date = date,
                primarySession = primary,
                totalMinutes = totalMinutes,
                extraSleepMinutes = (totalMinutes - primary.totalMinutes).coerceAtLeast(0),
            )
        }
        .sortedByDescending { it.date }

fun calculateSleepRegularityScore(
    days: List<com.sleepcare.mobile.domain.SleepDaySummary>,
): Int {
    if (days.isEmpty()) return 0
    if (days.size == 1) return 85

    // 자정 전후 취침/기상 시간을 같은 축 위에 놓기 위해 분 단위로 정규화합니다.
    val bedtimeMinutes = days.map { it.primarySession.startTime.toRegularityBedtimeMinutes() }
    val wakeMinutes = days.map { it.primarySession.endTime.toRegularityWakeMinutes() }
    val averageBedtime = bedtimeMinutes.average()
    val averageWakeTime = wakeMinutes.average()
    val bedtimeDeviation = bedtimeMinutes.map { abs(it - averageBedtime) }.average()
    val wakeDeviation = wakeMinutes.map { abs(it - averageWakeTime) }.average()

    val bedtimePenalty = (bedtimeDeviation / 5f).roundToInt().coerceIn(0, 25)
    val wakePenalty = (wakeDeviation / 4f).roundToInt().coerceIn(0, 30)
    return (100 - bedtimePenalty - wakePenalty).coerceIn(35, 100)
}

private fun mergeNearbyNightSleepSessions(
    sessions: List<com.sleepcare.mobile.domain.SleepSession>,
): List<com.sleepcare.mobile.domain.SleepSession> {
    if (sessions.isEmpty()) return emptyList()

    val sorted = sessions.sortedBy { it.startTime }
    val merged = mutableListOf<com.sleepcare.mobile.domain.SleepSession>()
    var current = sorted.first()

    for (next in sorted.drop(1)) {
        val gap = Duration.between(current.endTime, next.startTime)
        // Health Connect가 짧은 각성으로 밤잠을 둘로 나누는 경우를 한 세션으로 복원합니다.
        val canMerge = !gap.isNegative &&
            gap <= Duration.ofHours(3) &&
            (current.isNightLikeSleep() || next.isNightLikeSleep())

        if (canMerge) {
            current = current.mergeWith(next, gap)
        } else {
            merged += current
            current = next
        }
    }

    merged += current
    return merged
}

private fun com.sleepcare.mobile.domain.SleepSession.isNightLikeSleep(): Boolean =
    startTime.toLocalDate() != endTime.toLocalDate() ||
        startTime.hour >= 18 ||
        endTime.hour <= 10

private fun com.sleepcare.mobile.domain.SleepSession.mergeWith(
    other: com.sleepcare.mobile.domain.SleepSession,
    gap: Duration,
): com.sleepcare.mobile.domain.SleepSession {
    val mergedStart = minOf(startTime, other.startTime)
    val mergedEnd = maxOf(endTime, other.endTime)
    val totalMinutes = Duration.between(mergedStart, mergedEnd).toMinutes().toInt().coerceAtLeast(0)
    val awakeMinutes = awakeMinutes + other.awakeMinutes + gap.toMinutes().toInt().coerceAtLeast(0)
    val actualSleepMinutes = (totalMinutes - awakeMinutes).coerceAtLeast(0)
    val consistencyScore = if (totalMinutes > 0) {
        ((actualSleepMinutes * 100) / totalMinutes).coerceIn(0, 100)
    } else {
        0
    }

    return copy(
        id = "${id}+${other.id}",
        startTime = mergedStart,
        endTime = mergedEnd,
        totalMinutes = totalMinutes,
        sleepScore = ScoreCalculator.sleepQuality(
            totalMinutes = totalMinutes,
            consistencyScore = consistencyScore,
            latencyMinutes = 0,
            awakeMinutes = awakeMinutes,
        ),
        consistencyScore = consistencyScore,
        latencyMinutes = 0,
        awakeMinutes = awakeMinutes,
    )
}

private fun LocalDateTime.toRegularityBedtimeMinutes(): Double {
    val minutes = hour * 60 + minute
    return if (minutes < 12 * 60) (minutes + 24 * 60).toDouble() else minutes.toDouble()
}

private fun LocalDateTime.toRegularityWakeMinutes(): Double {
    val minutes = hour * 60 + minute
    return if (minutes >= 18 * 60) (minutes - 24 * 60).toDouble() else minutes.toDouble()
}

fun buildDrowsinessAnalysisSnapshot(
    events: List<com.sleepcare.mobile.domain.DrowsinessEvent>,
    sessions: List<com.sleepcare.mobile.domain.SleepSession>,
    liveRisk: PiRiskUpdate? = null,
): DrowsinessAnalysisSnapshot {
    // 최근 이벤트만 리스트로 보여주되, 피크 시간대는 전체 이벤트 분포에서 계산합니다.
    val recent = events.sortedByDescending { it.timestamp }.take(8)
    val grouped = events.groupBy { it.timestamp.hour }
    val peakHour = grouped.maxByOrNull { (_, value) -> value.size }?.key
    val averageSleepMinutes = sessions.map { it.totalMinutes.toDouble() }.averageOrNull()?.toInt() ?: 390
    return DrowsinessAnalysisSnapshot(
        totalCount = events.size,
        peakWindowLabel = peakHour?.let { "%02d:00 - %02d:59".format(it, it) } ?: "실시간 연결 대기",
        focusScore = ScoreCalculator.focusScore(recent, averageSleepMinutes),
        recentEvents = recent,
        liveRisk = liveRisk,
    )
}

private fun PiAlertFire.toEvent(): DrowsinessEvent = DrowsinessEvent(
    id = "$sessionId-alert-$sequence",
    timestamp = receivedAt,
    severity = level.coerceIn(1, 4),
    durationMinutes = (durationMs / 60_000L).toInt().coerceAtLeast(1),
    label = reason.replace('_', ' '),
    deviceId = "raspberry-pi",
    sessionId = sessionId,
)

private fun String.toSessionMessage(): String = when (uppercase()) {
    "BASELINE" -> "라즈베리파이가 안정 상태로 감시 중입니다."
    "SUSPECT" -> "피로 신호가 감지되어 집중 상태를 더 촘촘히 보고 있습니다."
    "ALERTING" -> "즉시 각성이 필요한 상태입니다."
    else -> "라즈베리파이 상태를 업데이트했습니다."
}

private fun Iterable<Double>.averageOrNull(): Double? {
    var sum = 0.0
    var count = 0
    for (value in this) {
        sum += value
        count++
    }
    return if (count == 0) null else sum / count
}

private fun HealthConnectSleepState.shouldClearCachedSleep(): Boolean = when (this) {
    HealthConnectSleepState.PermissionDenied,
    HealthConnectSleepState.Unavailable,
    HealthConnectSleepState.ProviderUpdateRequired,
    HealthConnectSleepState.NoData -> true
    is HealthConnectSleepState.Error,
    HealthConnectSleepState.Checking,
    HealthConnectSleepState.Ready -> false
}
