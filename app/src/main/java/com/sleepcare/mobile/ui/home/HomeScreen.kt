package com.sleepcare.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.data.repository.buildLatestHomeSleepSession
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.HomeDashboardSnapshot
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.domain.StudySessionMode
import com.sleepcare.mobile.domain.StudySessionPhase
import com.sleepcare.mobile.domain.StudySessionRepository
import com.sleepcare.mobile.domain.StudySessionState
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.InsightCallout
import com.sleepcare.mobile.ui.components.MetricHeroCard
import com.sleepcare.mobile.ui.components.TimelineBar
import com.sleepcare.mobile.ui.components.TimelineSegment
import com.sleepcare.mobile.ui.components.toDisplayDate
import com.sleepcare.mobile.ui.components.toDisplayDateTime
import com.sleepcare.mobile.ui.components.toDisplayTime
import com.sleepcare.mobile.ui.components.toDurationText
import com.sleepcare.mobile.ui.theme.SleepCareError
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceContainerHigh
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// 홈 화면에 필요한 여러 저장소 값을 한 번에 담는 UI 상태입니다.
data class HomeUiState(
    val snapshot: HomeDashboardSnapshot = HomeDashboardSnapshot(null, 0, null, null),
    val timelineSegments: List<TimelineSegment> = emptyList(),
    val fatigueInsightText: String = "Pi 졸음 이벤트가 들어오면 최근 24시간의 집중 저하 신호를 요약합니다.",
    val studySession: StudySessionUiState = StudySessionUiState(),
    val sleepAvailable: Boolean = false,
    val sleepEmptyReason: String = "Health Connect 수면 데이터가 아직 없습니다.",
)

// 공부 세션 버튼/타이머 표시를 단순화한 상태입니다.
data class StudySessionUiState(
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val selectedMode: StudySessionMode = StudySessionMode.WatchAndEye,
    val startedAt: LocalDateTime? = null,
    val message: String = "Galaxy Watch와 Raspberry Pi를 연결하면 학습 세션을 시작할 수 있습니다.",
)

// 앱 첫 탭 화면입니다. 오늘의 공부 세션, 수면 요약, 졸음 빈도, 추천 루틴을 카드로 보여줍니다.
@Composable
fun HomeScreen(
    paddingValues: PaddingValues,
    onOpenAnalysis: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ticker by rememberTickerMillis()
    val latestSleep = uiState.snapshot.latestSleep
    // 세션 진행 중에는 1초마다 현재 시각을 갱신해 경과 시간을 계산합니다.
    val timerText = uiState.studySession.startedAt?.let { startedAt ->
        val elapsedMillis = (ticker - startedAt.toEpochMillis()).coerceAtLeast(0L)
        elapsedMillis.toTimerText()
    }
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("오늘의 대시보드", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            StudySessionCard(
                session = uiState.studySession,
                timerText = timerText,
                onModeSelected = viewModel::selectStudySessionMode,
                onToggleClick = {
                    if (uiState.studySession.isRunning) {
                        viewModel.stopStudySession()
                    } else {
                        viewModel.startStudySession(uiState.studySession.selectedMode)
                    }
                },
            )
        }
        item {
            if (uiState.sleepAvailable && latestSleep != null) {
                MetricHeroCard(
                    title = "어제 수면 상태",
                    value = latestSleep.sleepScore.toString(),
                    subtitle = latestSleep.totalMinutes.toDurationText(),
                    supportingText = "수면 점수와 총 수면 시간을 기준으로 회복 상태를 요약합니다.",
                    onClick = onOpenAnalysis,
                )
            } else {
                SleepUnavailableCard(
                    message = uiState.sleepEmptyReason,
                    onActionClick = onOpenAnalysis,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricHeroCard(
                    modifier = Modifier.weight(1f),
                    title = "최근 졸음 빈도",
                    value = "${uiState.snapshot.recentDrowsinessCount}회",
                    subtitle = "최근 24시간",
                    accent = Color(0xFFFFB4AB),
                    onClick = onOpenAnalysis,
                )
                MetricHeroCard(
                    modifier = Modifier.weight(1f),
                    title = "오늘 추천 취침",
                    value = uiState.snapshot.recommendation?.recommendedBedtime?.toDisplayTime() ?: "--:--",
                    subtitle = "권장 기상 ${uiState.snapshot.recommendation?.recommendedWakeTime?.toDisplayTime() ?: "--:--"}",
                    accent = SleepCareTertiary,
                    onClick = onOpenSchedule,
                )
            }
        }
        item {
            InsightCallout(
                title = "오늘의 AI 제안",
                message = uiState.snapshot.recommendation?.reason
                    ?: "추천 계산 전입니다. Pi 졸음 데이터, 학습 플랜, 시험 일정을 함께 반영합니다.",
                actionLabel = "수면 스케줄 보기",
                onActionClick = onOpenSchedule,
            )
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("공부 피로 타임라인", style = MaterialTheme.typography.titleLarge)
                    Text(
                        uiState.fatigueInsightText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TimelineBar(
                        segments = uiState.timelineSegments,
                    )
                }
            }
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("다음 시험", style = MaterialTheme.typography.labelLarge, color = SleepCarePrimary)
                    Text(
                        text = uiState.snapshot.nextExam?.name ?: "예정된 시험이 없습니다",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    uiState.snapshot.nextExam?.let { exam ->
                        Text(
                            text = "${exam.date.toDisplayDate()} · ${exam.startTime.toDisplayTime()} · ${exam.location}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@HiltViewModel
// 홈 화면이 필요로 하는 수면/졸음/추천/시험/세션 상태를 하나의 Flow로 합칩니다.
class HomeViewModel @Inject constructor(
    sleepRepository: SleepRepository,
    drowsinessRepository: DrowsinessRepository,
    recommendationRepository: RecommendationRepository,
    examScheduleRepository: ExamScheduleRepository,
    private val studySessionRepository: StudySessionRepository,
) : ViewModel() {
    private val selectedStudySessionMode = MutableStateFlow(StudySessionMode.WatchAndEye)

    private val dashboardState = combine(
        sleepRepository.observeSleepSessions(),
        drowsinessRepository.observeDrowsinessEvents(),
        recommendationRepository.observeLatestRecommendation(),
        examScheduleRepository.observeExamSchedules(),
        studySessionRepository.observeSessionState(),
    ) { sleeps, drowsiness, recommendation, exams, sessionState ->
        val latestSleep = buildLatestHomeSleepSession(sleeps)
        val sleepAvailable = latestSleep != null
        // 최근 24시간 졸음 횟수만 홈 카드에 노출합니다.
        val recentDrowsinessCount = drowsiness.count { it.timestamp.isAfter(LocalDateTime.now().minusHours(24)) }
        val fatigueTimeline = buildHomeFatigueTimeline(recentDrowsinessCount)
        HomeUiState(
            snapshot = HomeDashboardSnapshot(
                latestSleep = latestSleep,
                recentDrowsinessCount = recentDrowsinessCount,
                recommendation = recommendation,
                nextExam = exams.firstOrNull(),
                sessionState = sessionState,
            ),
            studySession = sessionState.toUiState(),
            sleepAvailable = sleepAvailable,
            sleepEmptyReason = if (sleepAvailable) {
                "최근 수면 데이터를 불러왔습니다."
            } else {
                "Health Connect 수면 데이터가 아직 없습니다. 권한, 가용성, 또는 실제 기록 여부를 확인해 주세요."
            },
            timelineSegments = fatigueTimeline.segments,
            fatigueInsightText = fatigueTimeline.message,
        )
    }

    val uiState = combine(dashboardState, selectedStudySessionMode) { state, selectedMode ->
        state.copy(studySession = state.studySession.copy(selectedMode = selectedMode))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun selectStudySessionMode(mode: StudySessionMode) {
        selectedStudySessionMode.value = mode
    }

    fun startStudySession(mode: StudySessionMode) {
        viewModelScope.launch {
            studySessionRepository.startSession(mode)
        }
    }

    fun stopStudySession() {
        viewModelScope.launch {
            studySessionRepository.stopSession()
        }
    }
}

@Composable
// 세션 시작/종료를 담당하는 홈의 주요 행동 카드입니다.
private fun StudySessionCard(
    session: StudySessionUiState,
    timerText: String?,
    onModeSelected: (StudySessionMode) -> Unit,
    onToggleClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        tone = SleepCarePrimary.copy(alpha = 0.12f),
        borderColor = SleepCarePrimary.copy(alpha = 0.26f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (session.isRunning) "공부 세션 진행 중" else "공부 세션",
                style = MaterialTheme.typography.labelMedium,
                color = SleepCarePrimary,
            )
            Text(
                text = if (session.isRunning) timerText ?: "00:00" else "타이머를 시작해 집중 구간을 기록하세요.",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = session.startedAt?.toDisplayDateTime()?.let { "시작 $it · ${session.message}" } ?: session.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!session.isRunning) {
                // 명시적으로 선택한 모드만 사용합니다. 자동 eye-only 전환은 세션 의도를 흐리므로 여기서는 다루지 않습니다.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudySessionMode.values().forEach { mode ->
                        FilterChip(
                            selected = session.selectedMode == mode,
                            onClick = { onModeSelected(mode) },
                            enabled = !session.isBusy,
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
            Button(
                onClick = onToggleClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !session.isBusy,
            ) {
                Text(
                    when {
                        session.isBusy && session.isRunning -> "종료 중..."
                        session.isBusy -> "연결 중..."
                        session.isRunning -> "공부 종료"
                        else -> "공부 시작"
                    }
                )
            }
        }
    }
}

@Composable
// Health Connect 수면 데이터가 없을 때 분석 화면으로 유도하는 카드입니다.
private fun SleepUnavailableCard(
    message: String,
    onActionClick: () -> Unit,
) {
    InsightCallout(
        title = "실제 수면 연동 준비 중",
        message = message,
        icon = Icons.Filled.Bedtime,
        actionLabel = "분석 화면 보기",
        onActionClick = onActionClick,
    )
}

@Composable
// Compose 상태로 주기적인 현재 시각을 제공해 타이머만 자연스럽게 다시 그립니다.
private fun rememberTickerMillis(intervalMs: Long = 1_000L): State<Long> = produceState(initialValue = System.currentTimeMillis()) {
    while (true) {
        kotlinx.coroutines.delay(intervalMs)
        value = System.currentTimeMillis()
    }
}

private fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun Long.toTimerText(): String {
    val totalSeconds = (this / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal data class HomeFatigueTimeline(
    val message: String,
    val segments: List<TimelineSegment>,
)

// 실제 Pi 이벤트가 없는 날에는 예시 하루 패턴을 만들지 않고, 수신 대기 상태를 그대로 보여줍니다.
internal fun buildHomeFatigueTimeline(recentDrowsinessCount: Int): HomeFatigueTimeline {
    val count = recentDrowsinessCount.coerceAtLeast(0)
    return when {
        count == 0 -> HomeFatigueTimeline(
            message = "최근 24시간 동안 기록된 Pi 졸음 이벤트가 없습니다. 세션을 시작하면 감지 결과가 여기에 쌓입니다.",
            segments = listOf(
                TimelineSegment("기록 대기", 1f, SleepCareSurfaceContainerHigh, "최근 24시간 이벤트 없음"),
            ),
        )
        count < 3 -> HomeFatigueTimeline(
            message = "최근 24시간 동안 Pi 졸음 이벤트가 ${count}회 기록됐습니다. 반복 패턴을 단정하기에는 아직 표본이 적습니다.",
            segments = listOf(
                TimelineSegment("기본 관찰", 0.72f, SleepCarePrimary.copy(alpha = 0.20f), "일반 학습 구간"),
                TimelineSegment("졸음 기록", 0.18f, SleepCareError.copy(alpha = 0.82f), "${count}회"),
                TimelineSegment("회복 여유", 0.10f, SleepCareTertiary.copy(alpha = 0.24f), "짧은 휴식 권장"),
            ),
        )
        else -> HomeFatigueTimeline(
            message = "최근 24시간 동안 Pi 졸음 이벤트가 ${count}회 반복됐습니다. 같은 시간대 반복 여부는 분석 탭에서 확인하세요.",
            segments = listOf(
                TimelineSegment("기본 관찰", 0.56f, SleepCarePrimary.copy(alpha = 0.18f), "일반 학습 구간"),
                TimelineSegment("반복 졸음", 0.28f, SleepCareError, "${count}회"),
                TimelineSegment("휴식 우선", 0.16f, SleepCareTertiary.copy(alpha = 0.34f), "강도 조절 필요"),
            ),
        )
    }
}

// 도메인 세션 단계는 많지만 홈 화면에는 실행 중/대기 중/바쁨 정도만 필요합니다.
private fun StudySessionState.toUiState(): StudySessionUiState {
    val busyPhases = setOf(
        StudySessionPhase.ArmingWatch,
        StudySessionPhase.DiscoveringPi,
        StudySessionPhase.ConnectingPi,
        StudySessionPhase.OpeningSession,
        StudySessionPhase.Stopping,
    )
    return StudySessionUiState(
        isRunning = phase in setOf(
            StudySessionPhase.ArmingWatch,
            StudySessionPhase.DiscoveringPi,
            StudySessionPhase.ConnectingPi,
            StudySessionPhase.OpeningSession,
            StudySessionPhase.Running,
            StudySessionPhase.Alerting,
            StudySessionPhase.Stopping,
        ),
        isBusy = phase in busyPhases,
        selectedMode = mode,
        startedAt = startedAt,
        message = message ?: when (phase) {
            StudySessionPhase.ArmingWatch -> "Galaxy Watch 세션을 준비하는 중입니다."
            StudySessionPhase.Alerting -> "라즈베리파이가 즉시 각성 알림을 보내는 중입니다."
            StudySessionPhase.Error -> "세션을 다시 시작해 주세요."
            else -> "Galaxy Watch와 Raspberry Pi 연결을 확인해 주세요."
        },
    )
}

private val StudySessionMode.label: String
    get() = when (this) {
        StudySessionMode.WatchAndEye -> "워치 포함"
        StudySessionMode.EyeOnly -> "Eye only"
    }
