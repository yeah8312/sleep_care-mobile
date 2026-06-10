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
import com.sleepcare.mobile.domain.RecommendationActionBlock
import com.sleepcare.mobile.domain.RecommendationActionBlockType
import com.sleepcare.mobile.domain.RecommendationEngine
import com.sleepcare.mobile.domain.RecommendationFactor
import com.sleepcare.mobile.domain.RecommendationFactorSeverity
import com.sleepcare.mobile.domain.RecommendationFactorType
import com.sleepcare.mobile.domain.RecommendationInput
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.RecommendationSnapshot
import com.sleepcare.mobile.domain.RecommendationStatus
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

// 공부 계획은 사용자가 직접 입력한 학습 가능 시간대만 저장합니다.
// 데모 기본값을 심으면 추천이 실제 개인 데이터처럼 보이므로 초기 상태는 비워 둡니다.
@Singleton
class StudyPlanRepositoryImpl @Inject constructor(
    private val studyPlanDao: StudyPlanDao,
) : StudyPlanRepository {
    override fun observeStudyPlan(): Flow<StudyPlan?> = studyPlanDao.observeById().map { it?.toDomain() }

    override suspend fun seedIfEmpty() = Unit

    override suspend fun upsert(plan: StudyPlan) {
        studyPlanDao.upsert(plan.toEntity())
    }
}

// 시험 일정도 실제 사용자가 입력한 항목만 추천에 반영합니다.
@Singleton
class ExamScheduleRepositoryImpl @Inject constructor(
    private val examScheduleDao: ExamScheduleDao,
) : ExamScheduleRepository {
    override fun observeExamSchedules(): Flow<List<ExamSchedule>> =
        examScheduleDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun seedIfEmpty() = Unit

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
// 한 번에 이상적인 시간을 찍기보다, 실제 수면 리듬에서 오늘 이동 가능한 폭까지 함께 계산합니다.
@Singleton
class SleepCareRecommendationEngine @Inject constructor() : RecommendationEngine {
    override fun generate(input: RecommendationInput): RecommendationSnapshot {
        val generatedAt = input.generatedAt
        val sleepProfile = analyzeSleepRhythm(input.sleepSessions, generatedAt)
        val drowsinessProfile = analyzeDrowsinessPattern(input.drowsinessEvents, generatedAt)
        val academicProfile = analyzeAcademicPressure(input.exams, generatedAt)
        val needsSetup = input.userGoals.targetWakeTime == null &&
            input.userGoals.preferredBedtime == null &&
            input.studyPlan == null &&
            sleepProfile.averageMinutes == null &&
            academicProfile.nextExam == null

        val targetSleepMinutes = calculateTargetSleepMinutes(sleepProfile, drowsinessProfile, academicProfile)
        val normalWakeTime = input.userGoals.targetWakeTime
            ?: sleepProfile.medianWakeTime
            ?: LocalTime.of(6, 30)
        val wakeFromPreferredBedtime = input.userGoals.preferredBedtime
            ?.plusMinutes((targetSleepMinutes + SLEEP_PREP_MINUTES).toLong())
        val desiredWakeTime = when {
            academicProfile.wakeDeadline != null && normalWakeTime.isAfter(academicProfile.wakeDeadline) ->
                academicProfile.wakeDeadline
            input.userGoals.targetWakeTime == null && sleepProfile.medianWakeTime == null && wakeFromPreferredBedtime != null ->
                wakeFromPreferredBedtime
            else -> normalWakeTime
        }
        val urgentWakeDeadline = academicProfile.wakeDeadline != null && (academicProfile.daysUntil ?: 99) <= 3
        val recommendedWakeTime = if (urgentWakeDeadline) {
            desiredWakeTime
        } else {
            shiftByConsistency(
                current = sleepProfile.medianWakeTime,
                target = desiredWakeTime,
                mode = sleepProfile.mode,
                forWake = true,
            )
        }
        val desiredBedtime = recommendedWakeTime.minusMinutes((targetSleepMinutes + SLEEP_PREP_MINUTES).toLong())
        val recommendedBedtime = shiftByConsistency(
            current = sleepProfile.medianBedtime,
            target = desiredBedtime,
            mode = sleepProfile.mode,
            forWake = false,
        )
        val routineShiftMinutes = sleepProfile.medianBedtime
            ?.let { circularDiffMinutes(it, recommendedBedtime) }
            ?: 0
        val status = when {
            needsSetup -> RecommendationStatus.NeedsSetup
            sleepProfile.mode == ConsistencyMode.InsufficientData -> RecommendationStatus.LowConfidence
            else -> RecommendationStatus.Ready
        }
        val factors = buildRecommendationFactors(
            sleepProfile = sleepProfile,
            drowsinessProfile = drowsinessProfile,
            academicProfile = academicProfile,
            studyPlan = input.studyPlan,
            userGoals = input.userGoals,
            recommendedBedtime = recommendedBedtime,
        )
        val actionBlocks = buildRecommendationActionBlocks(
            recommendedBedtime = recommendedBedtime,
            studyPlan = input.studyPlan,
            drowsinessProfile = drowsinessProfile,
            academicProfile = academicProfile,
        )
        val reason = buildRecommendationReason(
            status = status,
            sleepProfile = sleepProfile,
            drowsinessProfile = drowsinessProfile,
            academicProfile = academicProfile,
            hasStudyPlan = input.studyPlan != null,
            hasUserGoal = input.userGoals.targetWakeTime != null || input.userGoals.preferredBedtime != null,
        )
        val tips = buildRecommendationTips(
            recommendedBedtime = recommendedBedtime,
            sleepProfile = sleepProfile,
            drowsinessProfile = drowsinessProfile,
            academicProfile = academicProfile,
            studyPlan = input.studyPlan,
        )

        return RecommendationSnapshot(
            recommendedBedtime = recommendedBedtime,
            recommendedWakeTime = recommendedWakeTime,
            targetSleepMinutes = targetSleepMinutes,
            reason = reason,
            routineShiftMinutes = routineShiftMinutes,
            status = status,
            factors = factors,
            actionBlocks = actionBlocks,
            tips = tips.take(3),
            generatedAt = generatedAt,
        )
    }
}

private const val BASE_TARGET_SLEEP_MINUTES = 450
private const val SLEEP_PREP_MINUTES = 15
private const val DROWSINESS_LOOKBACK_DAYS = 14L

private enum class ConsistencyMode {
    Stable,
    Drifting,
    Irregular,
    InsufficientData,
}

private enum class DrowsinessBucket(val label: String) {
    Dawn("새벽"),
    Morning("오전"),
    Afternoon("오후"),
    Night("밤"),
}

private enum class ExamTimeBand(val label: String) {
    EarlyMorning("이른 오전"),
    LateMorning("늦은 오전"),
    Afternoon("오후"),
    Evening("저녁"),
}

private data class SleepRhythmProfile(
    val dayCount: Int,
    val averageMinutes: Int?,
    val medianBedtime: LocalTime?,
    val medianWakeTime: LocalTime?,
    val bedtimeDeviationMinutes: Int?,
    val wakeDeviationMinutes: Int?,
    val mode: ConsistencyMode,
)

private data class DrowsinessPatternProfile(
    val recentEvents: List<DrowsinessEvent>,
    val peakBucket: DrowsinessBucket?,
    val peakCount: Int,
    val extraSleepMinutes: Int,
    val severity: RecommendationFactorSeverity,
)

private data class AcademicPressureProfile(
    val nextExam: ExamSchedule?,
    val daysUntil: Int?,
    val band: ExamTimeBand?,
    val wakeDeadline: LocalTime?,
    val shouldProtectSleep: Boolean,
)

private fun analyzeSleepRhythm(
    sessions: List<com.sleepcare.mobile.domain.SleepSession>,
    generatedAt: LocalDateTime,
): SleepRhythmProfile {
    val recentDays = sessions
        .filter { !it.endTime.isAfter(generatedAt) }
        .let(::buildWeeklySleepDaySummaries)
        .take(7)
    if (recentDays.isEmpty()) {
        return SleepRhythmProfile(0, null, null, null, null, null, ConsistencyMode.InsufficientData)
    }

    // 수면 분석 화면과 같은 "최근 일별 수면 요약"을 사용해야 평균 수면 시간이 서로 다르게 보이지 않습니다.
    // 같은 밤에 분할 기록된 Health Connect 세션은 먼저 합쳐지고, 낮잠은 같은 날짜의 추가 수면으로만 반영됩니다.
    val bedtimeMinutes = recentDays.map { it.primarySession.startTime.toLocalTime().toBedtimeAxisMinute() }
    val wakeMinutes = recentDays.map { it.primarySession.endTime.toLocalTime().toMinuteOfDay() }
    val medianBedtimeMinute = bedtimeMinutes.medianInt()
    val medianWakeMinute = wakeMinutes.medianInt()
    val bedtimeDeviation = bedtimeMinutes.averageAbsoluteDeviation(medianBedtimeMinute)
    val wakeDeviation = wakeMinutes.averageAbsoluteDeviation(medianWakeMinute)
    val maxDeviation = maxOf(bedtimeDeviation, wakeDeviation)
    val mode = when {
        recentDays.size < 3 -> ConsistencyMode.InsufficientData
        maxDeviation <= 45 -> ConsistencyMode.Stable
        maxDeviation <= 90 -> ConsistencyMode.Drifting
        else -> ConsistencyMode.Irregular
    }

    return SleepRhythmProfile(
        dayCount = recentDays.size,
        averageMinutes = recentDays.map { it.totalMinutes }.average().toInt(),
        medianBedtime = LocalTime.of((medianBedtimeMinute.floorModDay()) / 60, medianBedtimeMinute.floorModDay() % 60),
        medianWakeTime = LocalTime.of(medianWakeMinute / 60, medianWakeMinute % 60),
        bedtimeDeviationMinutes = bedtimeDeviation,
        wakeDeviationMinutes = wakeDeviation,
        mode = mode,
    )
}

private fun analyzeDrowsinessPattern(
    events: List<DrowsinessEvent>,
    generatedAt: LocalDateTime,
): DrowsinessPatternProfile {
    val cutoff = generatedAt.minusDays(DROWSINESS_LOOKBACK_DAYS)
    val recent = events
        .filter { !it.timestamp.isBefore(cutoff) && !it.timestamp.isAfter(generatedAt) }
        .sortedByDescending { it.timestamp }
    if (recent.isEmpty()) {
        return DrowsinessPatternProfile(emptyList(), null, 0, 0, RecommendationFactorSeverity.Unknown)
    }

    val bucketCounts = recent.groupingBy { it.timestamp.toLocalTime().toDrowsinessBucket() }.eachCount()
    val peak = bucketCounts.maxWith(compareBy<Map.Entry<DrowsinessBucket, Int>> { it.value }.thenBy { it.key.ordinal })
    val severity = when {
        peak.value >= 3 -> RecommendationFactorSeverity.NeedsAction
        recent.size >= 3 -> RecommendationFactorSeverity.Watch
        else -> RecommendationFactorSeverity.Good
    }
    val extraSleep = when {
        severity == RecommendationFactorSeverity.NeedsAction && peak.key == DrowsinessBucket.Morning -> 30
        severity == RecommendationFactorSeverity.NeedsAction -> 15
        severity == RecommendationFactorSeverity.Watch -> 15
        else -> 0
    }

    return DrowsinessPatternProfile(
        recentEvents = recent,
        peakBucket = peak.key,
        peakCount = peak.value,
        extraSleepMinutes = extraSleep,
        severity = severity,
    )
}

private fun analyzeAcademicPressure(
    exams: List<ExamSchedule>,
    generatedAt: LocalDateTime,
): AcademicPressureProfile {
    val today = generatedAt.toLocalDate()
    val nextExam = exams
        .filter { exam ->
            !exam.date.isBefore(today) &&
                !exam.date.isAfter(today.plusDays(14)) &&
                !(exam.date == today && !exam.startTime.isAfter(generatedAt.toLocalTime()))
        }
        .minWithOrNull(compareBy<ExamSchedule> { it.date }.thenBy { it.startTime }.thenBy { it.priority })
    val daysUntil = nextExam?.let { Duration.between(today.atStartOfDay(), it.date.atStartOfDay()).toDays().toInt() }
    val band = nextExam?.startTime?.toExamTimeBand()
    val wakeDeadline = when (band) {
        ExamTimeBand.EarlyMorning -> nextExam.startTime.minusMinutes(120)
        ExamTimeBand.LateMorning -> nextExam.startTime.minusMinutes(90)
        ExamTimeBand.Afternoon,
        ExamTimeBand.Evening,
        null -> null
    }

    return AcademicPressureProfile(
        nextExam = nextExam,
        daysUntil = daysUntil,
        band = band,
        wakeDeadline = wakeDeadline,
        shouldProtectSleep = daysUntil != null && daysUntil <= 3,
    )
}

private fun calculateTargetSleepMinutes(
    sleepProfile: SleepRhythmProfile,
    drowsinessProfile: DrowsinessPatternProfile,
    academicProfile: AcademicPressureProfile,
): Int {
    var target = BASE_TARGET_SLEEP_MINUTES
    val averageSleep = sleepProfile.averageMinutes
    if (averageSleep != null && averageSleep < 390) target += 30
    if (averageSleep != null && averageSleep in 390 until 420) target += 15
    if (sleepProfile.mode == ConsistencyMode.Irregular) target += 15
    target += drowsinessProfile.extraSleepMinutes
    if (academicProfile.shouldProtectSleep) target = maxOf(target, 480)
    return target.coerceIn(420, 540)
}

private fun buildRecommendationFactors(
    sleepProfile: SleepRhythmProfile,
    drowsinessProfile: DrowsinessPatternProfile,
    academicProfile: AcademicPressureProfile,
    studyPlan: StudyPlan?,
    userGoals: UserGoals,
    recommendedBedtime: LocalTime,
): List<RecommendationFactor> = buildList {
    add(
        RecommendationFactor(
            type = RecommendationFactorType.SleepDuration,
            title = "수면 시간",
            value = sleepProfile.averageMinutes?.let { "최근 평균 ${it.toDurationText()}" } ?: "수면 기록 없음",
            description = sleepProfile.averageMinutes?.let { "최근 ${sleepProfile.dayCount}일 수면 요약의 평균을 목표 수면량에 반영했습니다." }
                ?: "Health Connect 기록이 들어오기 전까지는 수면량 보정 없이 추천합니다.",
            severity = when {
                sleepProfile.averageMinutes == null -> RecommendationFactorSeverity.Unknown
                sleepProfile.averageMinutes < 390 -> RecommendationFactorSeverity.NeedsAction
                sleepProfile.averageMinutes < 420 -> RecommendationFactorSeverity.Watch
                else -> RecommendationFactorSeverity.Good
            },
        )
    )
    add(
        RecommendationFactor(
            type = RecommendationFactorType.SleepConsistency,
            title = "리듬 조정",
            value = sleepProfile.mode.toKoreanLabel(),
            description = sleepProfile.toConsistencyDescription(recommendedBedtime),
            severity = when (sleepProfile.mode) {
                ConsistencyMode.Stable -> RecommendationFactorSeverity.Good
                ConsistencyMode.Drifting -> RecommendationFactorSeverity.Watch
                ConsistencyMode.Irregular -> RecommendationFactorSeverity.NeedsAction
                ConsistencyMode.InsufficientData -> RecommendationFactorSeverity.Unknown
            },
        )
    )
    add(
        RecommendationFactor(
            type = RecommendationFactorType.DrowsinessPattern,
            title = if (drowsinessProfile.recentEvents.isEmpty()) "Pi 이벤트" else "졸음 패턴",
            value = if (drowsinessProfile.recentEvents.isEmpty()) {
                "최근 14일 0회"
            } else {
                "${drowsinessProfile.peakBucket?.label} ${drowsinessProfile.peakCount}회 집중"
            },
            description = if (drowsinessProfile.recentEvents.isEmpty()) {
                "alert.fire 기록이 없어 해당 신호는 계산에서 제외했습니다."
            } else {
                "최근 14일 이벤트만 사용해 시간대 반복 여부를 봤습니다."
            },
            severity = drowsinessProfile.severity,
        )
    )
    add(
        RecommendationFactor(
            type = RecommendationFactorType.AcademicSchedule,
            title = "시험 일정",
            value = academicProfile.nextExam?.let { "${academicProfile.band?.label ?: "시험"} · ${it.startTime.formatClock()}" } ?: "시험 일정 없음",
            description = academicProfile.toDescription(),
            severity = when {
                academicProfile.nextExam == null -> RecommendationFactorSeverity.Unknown
                academicProfile.shouldProtectSleep -> RecommendationFactorSeverity.NeedsAction
                else -> RecommendationFactorSeverity.Watch
            },
        )
    )
    add(
        RecommendationFactor(
            type = RecommendationFactorType.StudyPlan,
            title = "학습 가능 시간",
            value = studyPlan?.let { "${it.startTime.formatClock()} - ${it.endTime.formatClock()}" } ?: "미설정",
            description = studyPlan?.let { "학습 가능 시간은 첫 집중 블록과 저녁 학습 마감 판단에만 사용합니다." }
                ?: "학습 가능 시간대를 설정하면 추천 블록이 더 구체화됩니다.",
            severity = if (studyPlan == null) RecommendationFactorSeverity.Unknown else RecommendationFactorSeverity.Good,
        )
    )
    add(
        RecommendationFactor(
            type = RecommendationFactorType.UserGoal,
            title = "사용자 목표",
            value = buildUserGoalValue(userGoals),
            description = "목표 시간은 추천의 명시 기준이며, 학습 플랜 저장으로 자동 변경하지 않습니다.",
            severity = if (userGoals.targetWakeTime == null && userGoals.preferredBedtime == null) {
                RecommendationFactorSeverity.Unknown
            } else {
                RecommendationFactorSeverity.Good
            },
        )
    )
}

private fun buildRecommendationActionBlocks(
    recommendedBedtime: LocalTime,
    studyPlan: StudyPlan?,
    drowsinessProfile: DrowsinessPatternProfile,
    academicProfile: AcademicPressureProfile,
): List<RecommendationActionBlock> = buildList {
    add(
        RecommendationActionBlock(
            type = RecommendationActionBlockType.SleepPrep,
            title = "취침 준비",
            timeLabel = "${recommendedBedtime.minusMinutes(45).formatClock()} - ${recommendedBedtime.formatClock()}",
            description = "조명과 복습 강도를 낮춰 실제 취침 시각으로 부드럽게 이동합니다.",
        )
    )
    if (studyPlan != null) {
        val focusEnd = minOf(studyPlan.startTime.plusHours(2), studyPlan.endTime)
        add(
            RecommendationActionBlock(
                type = RecommendationActionBlockType.FocusStudy,
                title = "첫 집중 블록",
                timeLabel = "${studyPlan.startTime.formatClock()} - ${focusEnd.formatClock()}",
                description = "학습 가능 시간의 앞부분에 가장 부담 큰 과목을 배치합니다.",
            )
        )
    }
    if (studyPlan?.autoBreakEnabled == true && drowsinessProfile.recentEvents.isNotEmpty()) {
        add(
            RecommendationActionBlock(
                type = RecommendationActionBlockType.Recovery,
                title = "${drowsinessProfile.peakBucket?.label ?: "반복"} 회복 블록",
                timeLabel = drowsinessProfile.peakBucket?.label ?: "반복 시간대",
                description = "반복 이벤트가 몰린 시간대에는 15분 회복을 먼저 배치합니다.",
            )
        )
    }
    val exam = academicProfile.nextExam
    if (exam != null) {
        val block = when (academicProfile.band) {
            ExamTimeBand.Afternoon,
            ExamTimeBand.Evening -> RecommendationActionBlock(
                type = RecommendationActionBlockType.ExamPrep,
                title = "시험 전 집중 복습",
                timeLabel = "${exam.startTime.minusHours(3).formatClock()} - ${exam.startTime.minusHours(1).formatClock()}",
                description = "오후/저녁 시험은 기상 시간을 당기지 않고 시험 전 복습 구간을 확보합니다.",
            )
            else -> RecommendationActionBlock(
                type = RecommendationActionBlockType.ExamPrep,
                title = "오전 시험 준비",
                timeLabel = academicProfile.wakeDeadline?.let { "기상 마감 ${it.formatClock()}" } ?: "시험 전날",
                description = "오전 시험은 늦지 않는 기상 마감과 전날 수면 확보를 우선합니다.",
            )
        }
        add(block)
    }
}

private fun buildRecommendationTips(
    recommendedBedtime: LocalTime,
    sleepProfile: SleepRhythmProfile,
    drowsinessProfile: DrowsinessPatternProfile,
    academicProfile: AcademicPressureProfile,
    studyPlan: StudyPlan?,
): List<RecommendationTip> = buildList {
    add(
        RecommendationTip(
            title = if (sleepProfile.averageMinutes == null) "수면 연동 안내" else "취침 준비",
            body = if (sleepProfile.averageMinutes == null) {
                "Health Connect 기록이 들어오면 실제 수면량과 리듬을 반영해 추천을 다시 조정합니다."
            } else {
                "${recommendedBedtime.minusMinutes(45).formatClock()}부터 조명과 복습 강도를 낮춰 보세요."
            },
            iconKey = if (sleepProfile.averageMinutes == null) "sync" else "rest",
        )
    )
    if (studyPlan == null) {
        add(
            RecommendationTip(
                title = "학습 가능 시간 설정",
                body = "학습 가능 시작/종료 시간을 넣으면 첫 집중 블록과 저녁 마감 시간을 함께 제안합니다.",
                iconKey = "focus",
            )
        )
    } else {
        add(
            RecommendationTip(
                title = "첫 집중 블록",
                body = "${studyPlan.startTime.formatClock()}부터 학습 가능 시간 안에서 가장 어려운 과목을 먼저 배치해 보세요.",
                iconKey = "focus",
            )
        )
    }
    if (studyPlan?.autoBreakEnabled == true && drowsinessProfile.recentEvents.isNotEmpty()) {
        add(
            RecommendationTip(
                title = "회복 블록",
                body = "${drowsinessProfile.peakBucket?.label ?: "반복"} 시간대 이벤트가 반복되어 15분 회복 블록을 권장합니다.",
                iconKey = "rest",
            )
        )
    }
    if (academicProfile.nextExam != null) {
        add(
            RecommendationTip(
                title = "시험 준비",
                body = academicProfile.toTipText(),
                iconKey = "exam",
            )
        )
    }
}

private fun buildRecommendationReason(
    status: RecommendationStatus,
    sleepProfile: SleepRhythmProfile,
    drowsinessProfile: DrowsinessPatternProfile,
    academicProfile: AcademicPressureProfile,
    hasStudyPlan: Boolean,
    hasUserGoal: Boolean,
): String = when {
    status == RecommendationStatus.NeedsSetup ->
        "수면 목표, 학습 가능 시간, 시험 일정 중 하나를 설정하면 개인 루틴을 계산합니다."
    academicProfile.nextExam != null && academicProfile.band in setOf(ExamTimeBand.Afternoon, ExamTimeBand.Evening) ->
        "${academicProfile.band?.label} 시험은 기상 시간을 유지하고 시험 전 복습/회복 블록을 배치했어요."
    academicProfile.wakeDeadline != null ->
        "오전 시험 기상 마감과 전날 수면 확보를 함께 반영했어요."
    drowsinessProfile.recentEvents.isNotEmpty() && sleepProfile.averageMinutes != null ->
        "최근 수면 리듬과 Pi 이벤트 반복 시간대를 함께 반영했어요."
    sleepProfile.averageMinutes != null ->
        "최근 수면 리듬을 기준으로 오늘 이동 가능한 취침 시간을 제안했어요."
    hasUserGoal ->
        "사용자 목표 시간을 기준으로 기본 수면 루틴을 제안합니다."
    hasStudyPlan ->
        "학습 가능 시간대를 기준으로 기본 루틴을 제안합니다."
    else ->
        "아직 수면 기록이 부족해 기본 루틴으로 시작합니다."
}

private fun shiftByConsistency(
    current: LocalTime?,
    target: LocalTime,
    mode: ConsistencyMode,
    forWake: Boolean,
): LocalTime {
    if (current == null || mode == ConsistencyMode.Stable || mode == ConsistencyMode.InsufficientData) return target
    val maxShift = when (mode) {
        ConsistencyMode.Drifting -> 30
        ConsistencyMode.Irregular -> if (forWake) 45 else 30
        ConsistencyMode.Stable,
        ConsistencyMode.InsufficientData -> return target
    }
    val diff = circularDiffMinutes(current, target).coerceIn(-maxShift, maxShift)
    return current.plusMinutes(diff.toLong())
}

private fun SleepRhythmProfile.toConsistencyDescription(recommendedBedtime: LocalTime): String = when (mode) {
    ConsistencyMode.Stable -> "최근 리듬이 안정적이라 목표 시간에 바로 맞춰도 부담이 작습니다."
    ConsistencyMode.Drifting -> "취침/기상 편차가 있어 오늘은 ${recommendedBedtime.formatClock()}까지 완만하게 이동합니다."
    ConsistencyMode.Irregular -> "리듬이 크게 흔들려 기상 고정과 작은 취침 이동을 우선합니다."
    ConsistencyMode.InsufficientData -> "수면 세션이 3개 미만이라 일관성 판단은 보류합니다."
}

private fun AcademicPressureProfile.toDescription(): String {
    val exam = nextExam ?: return "시험 일정이 없어 기상 시간에는 반영하지 않았습니다."
    return when (band) {
        ExamTimeBand.EarlyMorning,
        ExamTimeBand.LateMorning -> "오전 시험이라 ${wakeDeadline?.formatClock()} 기상 마감을 필요한 경우에만 반영합니다."
        ExamTimeBand.Afternoon,
        ExamTimeBand.Evening -> "${exam.startTime.formatClock()} 시험이라 기상 시간 대신 시험 전 복습 블록을 배치합니다."
        null -> "시험 시간이 없어 학습 블록에만 참고합니다."
    }
}

private fun AcademicPressureProfile.toTipText(): String {
    val exam = nextExam ?: return "시험 일정이 추가되면 준비 블록을 함께 제안합니다."
    return when (band) {
        ExamTimeBand.Afternoon,
        ExamTimeBand.Evening -> "${exam.startTime.minusHours(3).formatClock()}부터 핵심 복습을 끝내고 시험 1시간 전에는 정리만 남겨두세요."
        else -> "오전 시험 전날에는 수면 시간을 먼저 확보하고, 기상 마감 ${wakeDeadline?.formatClock() ?: exam.startTime.formatClock()}을 넘기지 않습니다."
    }
}

private fun buildUserGoalValue(goals: UserGoals): String = listOfNotNull(
    goals.targetWakeTime?.let { "기상 ${it.formatClock()}" },
    goals.preferredBedtime?.let { "취침 ${it.formatClock()}" },
).ifEmpty { listOf("미설정") }.joinToString(" · ")

private fun ConsistencyMode.toKoreanLabel(): String = when (this) {
    ConsistencyMode.Stable -> "안정"
    ConsistencyMode.Drifting -> "완만 조정"
    ConsistencyMode.Irregular -> "기상 고정 우선"
    ConsistencyMode.InsufficientData -> "판단 보류"
}

private fun LocalTime.toDrowsinessBucket(): DrowsinessBucket = when (hour) {
    in 0..5 -> DrowsinessBucket.Dawn
    in 6..11 -> DrowsinessBucket.Morning
    in 12..17 -> DrowsinessBucket.Afternoon
    else -> DrowsinessBucket.Night
}

private fun LocalTime.toExamTimeBand(): ExamTimeBand = when (hour) {
    in 0..9 -> ExamTimeBand.EarlyMorning
    in 10..11 -> ExamTimeBand.LateMorning
    in 12..17 -> ExamTimeBand.Afternoon
    else -> ExamTimeBand.Evening
}

private fun Int.floorModDay(): Int = ((this % 1440) + 1440) % 1440

private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

private fun LocalTime.toBedtimeAxisMinute(): Int {
    val minute = toMinuteOfDay()
    return if (minute < 12 * 60) minute + 1440 else minute
}

private fun List<Int>.medianInt(): Int {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        ((sorted[middle - 1] + sorted[middle]) / 2)
    } else {
        sorted[middle]
    }
}

private fun List<Int>.averageAbsoluteDeviation(center: Int): Int =
    map { abs(it - center) }.average().toInt()

private fun circularDiffMinutes(from: LocalTime, to: LocalTime): Int {
    var diff = to.toMinuteOfDay() - from.toMinuteOfDay()
    while (diff > 720) diff -= 1440
    while (diff < -720) diff += 1440
    return diff
}

private fun Int.toDurationText(): String = "${this / 60}시간 ${this % 60}분"

private fun LocalTime.formatClock(): String = "%02d:%02d".format(hour, minute)

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
