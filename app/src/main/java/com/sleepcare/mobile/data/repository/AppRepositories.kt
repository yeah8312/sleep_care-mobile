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
import com.sleepcare.mobile.domain.StudySessionPhase
import com.sleepcare.mobile.domain.StudySessionRepository
import com.sleepcare.mobile.domain.StudySessionState
import com.sleepcare.mobile.domain.UserGoals
import com.sleepcare.mobile.domain.WatchFlushPolicy
import com.sleepcare.mobile.domain.WatchSessionClosed
import com.sleepcare.mobile.domain.WatchSessionConfig
import com.sleepcare.mobile.domain.WatchSessionDataSource
import com.sleepcare.mobile.domain.WatchSessionError
import com.sleepcare.mobile.domain.WatchSessionReady
import com.sleepcare.mobile.data.source.HealthConnectSleepDataSource
import com.sleepcare.mobile.data.source.HealthConnectSleepState
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
            sleepSessionDao.clear()
            sleepSessionDao.upsertAll(sessions.map { it.toEntity() })
            val current = preferencesStore.lastSyncState.first()
            preferencesStore.updateLastSyncState(current.copy(sleepSyncedAt = LocalDateTime.now()))
        } else if (sleepDataSource.state.value.shouldClearCachedSleep()) {
            sleepSessionDao.clear()
        }
    }
}

@Singleton
class DrowsinessRepositoryImpl @Inject constructor(
    private val drowsinessEventDao: DrowsinessEventDao,
    private val piNetworkDataSource: PiNetworkDataSource,
    private val preferencesStore: PreferencesStore,
) : DrowsinessRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
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

@Singleton
class DeviceConnectionRepositoryImpl @Inject constructor(
    private val piNetworkDataSource: PiNetworkDataSource,
    private val watchSessionDataSource: WatchSessionDataSource,
) : DeviceConnectionRepository {
    override fun observeDevices(): Flow<List<ConnectedDeviceState>> =
        combine(piNetworkDataSource.observeConnectionState(), watchSessionDataSource.observeConnectionState()) { pi, watch ->
            listOf(pi, watch)
        }

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
}

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
            watchSessionDataSource.observeHeartRateBatches().collect { batch ->
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
            piNetworkDataSource.observeRiskState().collect { risk ->
                if (risk == null) return@collect
                if (risk.sessionId == sessionState.value.sessionId && risk.recommendedFlushSec != null) {
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
            piNetworkDataSource.observeAlerts().collect { alert ->
                watchSessionDataSource.sendVibrationAlert(
                    sessionId = alert.sessionId,
                    level = alert.level,
                    pattern = "200,100,200,100,400",
                )
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

    override suspend fun startSession() {
        val current = sessionState.value
        if (current.phase in listOf(
                StudySessionPhase.ArmingWatch,
                StudySessionPhase.DiscoveringPi,
                StudySessionPhase.ConnectingPi,
                StudySessionPhase.OpeningSession,
                StudySessionPhase.Running,
                StudySessionPhase.Alerting,
            )
        ) {
            return
        }

        val startedAt = LocalDateTime.now()
        val sessionId = "study-${startedAt.toLocalDate()}-${UUID.randomUUID().toString().take(8)}"
        sessionState.value = StudySessionState(
            sessionId = sessionId,
            phase = StudySessionPhase.ArmingWatch,
            startedAt = startedAt,
            message = "Galaxy Watch 세션을 준비하는 중입니다.",
        )
        persistCurrentState()

        val watchReady = watchSessionDataSource.refreshConnection()
        if (!watchReady) {
            sessionState.value = StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Error,
                startedAt = startedAt,
                message = "Galaxy Watch 연결을 찾지 못했습니다.",
            )
            persistCurrentState()
            return
        }

        val watchStarted = watchSessionDataSource.startSession(WatchSessionConfig(sessionId = sessionId))
        if (!watchStarted) {
            sessionState.value = StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Error,
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
                    startedAt = startedAt,
                    message = "Galaxy Watch 준비 응답이 시간 내에 오지 않았습니다.",
                )
                persistCurrentState()
                return
            }
        }

        sessionState.value = sessionState.value.copy(
            phase = StudySessionPhase.DiscoveringPi,
            message = "로컬 Wi-Fi에서 라즈베리파이를 찾는 중입니다.",
        )
        persistCurrentState()

        val connected = piNetworkDataSource.discoverAndConnect()
        if (!connected) {
            watchSessionDataSource.stopSession(sessionId)
            sessionState.value = StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Error,
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
            watchAvailable = true,
            eyeOnly = false,
        )
        sessionState.value = if (opened) {
            StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Running,
                startedAt = startedAt,
                message = "학습 세션이 진행 중입니다.",
            )
        } else {
            watchSessionDataSource.stopSession(sessionId)
            StudySessionState(
                sessionId = sessionId,
                phase = StudySessionPhase.Error,
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

        watchSessionDataSource.stopSession(sessionId)
        withTimeoutOrNull(5_000) {
            watchSessionDataSource.observeSessionEvents()
                .filter { it.sessionId == sessionId && it is WatchSessionClosed }
                .first()
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

@Singleton
class SleepCareRecommendationEngine @Inject constructor() : RecommendationEngine {
    override fun generate(input: RecommendationInput): RecommendationSnapshot {
        val generatedAt = input.generatedAt
        val recentSleep = input.sleepSessions.sortedByDescending { it.startTime }.take(3)
        val recentDrowsiness = input.drowsinessEvents.sortedByDescending { it.timestamp }.take(5)
        val averageSleepMinutes = recentSleep.map { it.totalMinutes.toDouble() }.averageOrNull()?.toInt()
        val needsExtraRecovery = (averageSleepMinutes != null && averageSleepMinutes < 390) || recentDrowsiness.size >= 3
        val targetSleepMinutes = if (needsExtraRecovery) 480 else 450

        val examWakeCandidate = input.exams
            .filter { !it.date.isBefore(generatedAt.toLocalDate()) && !it.date.isAfter(generatedAt.toLocalDate().plusDays(14)) }
            .minWithOrNull(compareBy<ExamSchedule> { it.date }.thenBy { it.startTime })
            ?.let { it.startTime.minusMinutes(90) }

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
    val recent = events.sortedByDescending { it.timestamp }.take(8)
    val grouped = events.groupBy { it.timestamp.hour }
    val peakHour = grouped.maxByOrNull { (_, value) -> value.size }?.key
    val averageSleepMinutes = sessions.map { it.totalMinutes.toDouble() }.averageOrNull()?.toInt() ?: 390
    return DrowsinessAnalysisSnapshot(
        totalCount = recent.size,
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
