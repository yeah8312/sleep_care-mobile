package com.sleepcare.mobile.data.repository

import com.sleepcare.mobile.data.local.DrowsinessEventDao
import com.sleepcare.mobile.data.local.ExamScheduleDao
import com.sleepcare.mobile.data.local.PreferencesStore
import com.sleepcare.mobile.data.local.RecommendationSnapshotDao
import com.sleepcare.mobile.data.local.SleepCareDatabase
import com.sleepcare.mobile.data.local.SleepSessionDao
import com.sleepcare.mobile.data.local.StudyPlanDao
import com.sleepcare.mobile.data.local.toDomain
import com.sleepcare.mobile.data.local.toEntity
import com.sleepcare.mobile.domain.ConnectionStatus
import com.sleepcare.mobile.domain.ConnectedDeviceState
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.DeviceType
import com.sleepcare.mobile.domain.DrowsinessAnalysisSnapshot
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamSchedule
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.LastSyncState
import com.sleepcare.mobile.domain.NotificationPreferences
import com.sleepcare.mobile.domain.OnboardingState
import com.sleepcare.mobile.domain.PiBleDataSource
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
import com.sleepcare.mobile.domain.UserGoals
import com.sleepcare.mobile.domain.WatchSleepDataSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class SleepRepositoryImpl @Inject constructor(
    private val sleepSessionDao: SleepSessionDao,
    private val watchSleepDataSource: WatchSleepDataSource,
    private val preferencesStore: PreferencesStore,
) : SleepRepository {
    override fun observeSleepSessions(): Flow<List<com.sleepcare.mobile.domain.SleepSession>> =
        sleepSessionDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun seedIfEmpty() {
        if (sleepSessionDao.count() == 0) {
            sleepSessionDao.upsertAll(watchSleepDataSource.readRecentSleepSessions().map { it.toEntity() })
        }
    }

    override suspend fun refreshFromSource() {
        sleepSessionDao.upsertAll(watchSleepDataSource.readRecentSleepSessions().map { it.toEntity() })
        val current = preferencesStore.lastSyncState.first()
        preferencesStore.updateLastSyncState(current.copy(sleepSyncedAt = LocalDateTime.now()))
    }
}

@Singleton
class DrowsinessRepositoryImpl @Inject constructor(
    private val drowsinessEventDao: DrowsinessEventDao,
    private val piBleDataSource: PiBleDataSource,
    private val preferencesStore: PreferencesStore,
) : DrowsinessRepository {
    override fun observeDrowsinessEvents(): Flow<List<com.sleepcare.mobile.domain.DrowsinessEvent>> =
        drowsinessEventDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun seedIfEmpty() {
        if (drowsinessEventDao.count() == 0) {
            drowsinessEventDao.upsertAll(piBleDataSource.observeDetectedEvents().first().map { it.toEntity() })
        }
    }

    override suspend fun refreshFromSource() {
        drowsinessEventDao.upsertAll(piBleDataSource.observeDetectedEvents().first().map { it.toEntity() })
        val current = preferencesStore.lastSyncState.first()
        preferencesStore.updateLastSyncState(current.copy(drowsinessSyncedAt = LocalDateTime.now()))
    }
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
    private val piBleDataSource: PiBleDataSource,
) : DeviceConnectionRepository {
    private val watchState = MutableStateFlow(
        ConnectedDeviceState(
            deviceType = DeviceType.Smartwatch,
            deviceName = "Smartwatch / Health Connect",
            status = ConnectionStatus.Connected,
            details = "샘플 수면 데이터 연결",
            lastSeenAt = LocalDateTime.now(),
        )
    )

    override fun observeDevices(): Flow<List<ConnectedDeviceState>> =
        combine(piBleDataSource.observeConnectionState(), watchState) { pi, watch ->
            listOf(pi, watch)
        }

    override suspend fun startScan() {
        watchState.value = watchState.value.copy(status = ConnectionStatus.Scanning, details = "워치 연결 확인 중")
        piBleDataSource.startScan()
        watchState.value = watchState.value.copy(
            status = ConnectionStatus.Connected,
            details = "Health Connect 스텁 사용 가능",
            lastSeenAt = LocalDateTime.now(),
        )
    }

    override suspend fun retryConnection(deviceType: DeviceType) {
        when (deviceType) {
            DeviceType.RaspberryPi -> piBleDataSource.retry()
            DeviceType.Smartwatch -> {
                watchState.value = watchState.value.copy(status = ConnectionStatus.Connected, details = "재연결 성공")
            }
        }
    }

    override suspend fun disconnect(deviceType: DeviceType) {
        when (deviceType) {
            DeviceType.RaspberryPi -> piBleDataSource.disconnect()
            DeviceType.Smartwatch -> {
                watchState.value = watchState.value.copy(status = ConnectionStatus.Disconnected, details = "연결 해제됨")
            }
        }
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
        val averageSleepMinutes = if (recentSleep.isEmpty()) 420 else recentSleep.map { it.totalMinutes }.average().toInt()
        val needsExtraRecovery = averageSleepMinutes < 390 || recentDrowsiness.size >= 3
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
            ?: bedtime.plusMinutes(35)
        val routineShiftMinutes = java.time.Duration.between(bedtime, currentAverageBedtime).toMinutes().toInt().coerceIn(-180, 180)

        val reason = when {
            examWakeCandidate != null -> "시험 대비 기상 리듬을 앞당길 시점이에요"
            needsExtraRecovery -> "최근 수면 부족과 졸음 신호를 함께 반영했어요"
            else -> "현재 루틴을 조금만 조정하면 집중력이 더 좋아져요"
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
            if (needsExtraRecovery) {
                add(
                    RecommendationTip(
                        title = "짧은 회복 휴식",
                        body = "오후 ${recentDrowsiness.lastOrNull()?.timestamp?.toLocalTime() ?: LocalTime.of(14, 30)} 전후 15분 휴식을 권장합니다.",
                        iconKey = "rest",
                    )
                )
            } else {
                add(
                    RecommendationTip(
                        title = "윈드다운",
                        body = "${bedtime.minusMinutes(45)}부터 조명을 낮추고 복습 강도를 줄여 보세요.",
                        iconKey = "winddown",
                    )
                )
            }
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
    val recent = sessions.sortedByDescending { it.startTime }.take(7)
    val latest = recent.firstOrNull()
    return SleepAnalysisSnapshot(
        score = latest?.sleepScore ?: 76,
        averageMinutes = if (recent.isEmpty()) 420 else recent.map { it.totalMinutes }.average().toInt(),
        consistency = if (recent.isEmpty()) 72 else recent.map { it.consistencyScore }.average().toInt(),
        latencyMinutes = if (recent.isEmpty()) 18 else recent.map { it.latencyMinutes }.average().toInt(),
        weeklyDurations = recent.map { it.totalMinutes }.ifEmpty { listOf(405, 390, 415, 430, 378, 402, 410) },
    )
}

fun buildDrowsinessAnalysisSnapshot(
    events: List<com.sleepcare.mobile.domain.DrowsinessEvent>,
    sessions: List<com.sleepcare.mobile.domain.SleepSession>,
): DrowsinessAnalysisSnapshot {
    val recent = events.sortedByDescending { it.timestamp }.take(8)
    val grouped = events.groupBy { it.timestamp.hour }
    val peakHour = grouped.maxByOrNull { (_, value) -> value.size }?.key ?: 14
    val averageSleepMinutes = if (sessions.isEmpty()) 420 else sessions.map { it.totalMinutes }.average().toInt()
    return DrowsinessAnalysisSnapshot(
        totalCount = recent.size,
        peakWindowLabel = "%02d:00 - %02d:59".format(peakHour, peakHour),
        focusScore = ScoreCalculator.focusScore(recent, averageSleepMinutes),
        recentEvents = recent,
    )
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
