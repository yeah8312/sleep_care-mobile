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
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.HomeDashboardSnapshot
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.SleepRepository
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
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val snapshot: HomeDashboardSnapshot = HomeDashboardSnapshot(null, 0, null, null),
    val timelineSegments: List<TimelineSegment> = emptyList(),
    val studySession: StudySessionUiState = StudySessionUiState(),
    val sleepAvailable: Boolean = false,
    val sleepEmptyReason: String = "워치 앱이 아직 준비되지 않아 실제 수면 기록을 불러올 수 없습니다.",
)

data class StudySessionUiState(
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val startedAt: LocalDateTime? = null,
    val message: String = "워치 앱이 없어도 Pi 기반 공부 세션은 바로 시작할 수 있습니다.",
)

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
                onToggleClick = {
                    if (uiState.studySession.isRunning) {
                        viewModel.stopStudySession()
                    } else {
                        viewModel.startStudySession()
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
                        "최근 졸음 이벤트를 바탕으로 집중 저하 구간을 요약합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TimelineBar(
                        segments = uiState.timelineSegments,
                        labels = listOf("08:00", "12:00", "16:00", "20:00"),
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
class HomeViewModel @Inject constructor(
    sleepRepository: SleepRepository,
    drowsinessRepository: DrowsinessRepository,
    recommendationRepository: RecommendationRepository,
    examScheduleRepository: ExamScheduleRepository,
    private val studySessionRepository: StudySessionRepository,
) : ViewModel() {
    val uiState = combine(
        sleepRepository.observeSleepSessions(),
        drowsinessRepository.observeDrowsinessEvents(),
        recommendationRepository.observeLatestRecommendation(),
        examScheduleRepository.observeExamSchedules(),
        studySessionRepository.observeSessionState(),
    ) { sleeps, drowsiness, recommendation, exams, sessionState ->
        val sleepAvailable = sleeps.isNotEmpty()
        val recentDrowsinessCount = drowsiness.count { it.timestamp.isAfter(LocalDateTime.now().minusHours(24)) }
        HomeUiState(
            snapshot = HomeDashboardSnapshot(
                latestSleep = sleeps.firstOrNull(),
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
                "워치 앱이 아직 준비되지 않아 실제 수면 기록을 불러올 수 없습니다."
            },
            timelineSegments = listOf(
                TimelineSegment("안정 집중", 0.32f, SleepCarePrimary.copy(alpha = 0.22f), "오전 집중 구간"),
                TimelineSegment("졸음 경고", 0.08f + (recentDrowsinessCount.coerceAtMost(3) * 0.01f), Color(0xFFFFB4AB), "오후 피로"),
                TimelineSegment("회복 집중", 0.18f, SleepCarePrimary.copy(alpha = 0.18f), "짧은 회복"),
                TimelineSegment("저녁 저하", 0.10f, Color(0xFFFFDAD6), "저녁 복습"),
                TimelineSegment("마무리", 0.32f, SleepCarePrimary.copy(alpha = 0.16f), "취침 전"),
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun startStudySession() {
        viewModelScope.launch {
            studySessionRepository.startSession()
        }
    }

    fun stopStudySession() {
        viewModelScope.launch {
            studySessionRepository.stopSession()
        }
    }
}

@Composable
private fun StudySessionCard(
    session: StudySessionUiState,
    timerText: String?,
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

private fun StudySessionState.toUiState(): StudySessionUiState {
    val busyPhases = setOf(
        StudySessionPhase.DiscoveringPi,
        StudySessionPhase.ConnectingPi,
        StudySessionPhase.OpeningSession,
        StudySessionPhase.Stopping,
    )
    return StudySessionUiState(
        isRunning = phase in setOf(
            StudySessionPhase.DiscoveringPi,
            StudySessionPhase.ConnectingPi,
            StudySessionPhase.OpeningSession,
            StudySessionPhase.Running,
            StudySessionPhase.Alerting,
            StudySessionPhase.Stopping,
        ),
        isBusy = phase in busyPhases,
        startedAt = startedAt,
        message = message ?: when (phase) {
            StudySessionPhase.Alerting -> "라즈베리파이가 즉시 각성 알림을 보내는 중입니다."
            StudySessionPhase.Error -> "세션을 다시 시작해 주세요."
            else -> "워치 앱이 없어도 Pi 기반 공부 세션은 바로 시작할 수 있습니다."
        },
    )
}
