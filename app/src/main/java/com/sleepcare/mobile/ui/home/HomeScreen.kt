package com.sleepcare.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.InsightCallout
import com.sleepcare.mobile.ui.components.MetricHeroCard
import com.sleepcare.mobile.ui.components.TimelineBar
import com.sleepcare.mobile.ui.components.TimelineSegment
import com.sleepcare.mobile.ui.components.toDisplayDate
import com.sleepcare.mobile.ui.components.toDisplayTime
import com.sleepcare.mobile.ui.components.toDurationText
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val snapshot: HomeDashboardSnapshot = HomeDashboardSnapshot(null, 0, null, null),
    val timelineSegments: List<TimelineSegment> = emptyList(),
)

@Composable
fun HomeScreen(
    paddingValues: PaddingValues,
    onOpenAnalysis: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("오늘의 대시보드", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            MetricHeroCard(
                title = "어제 수면 상태",
                value = uiState.snapshot.latestSleep?.sleepScore?.toString() ?: "--",
                subtitle = uiState.snapshot.latestSleep?.totalMinutes?.toDurationText() ?: "데이터 준비 중",
                supportingText = "수면 점수와 총 수면 시간을 기준으로 회복 상태를 요약합니다.",
                onClick = onOpenAnalysis,
            )
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
                    ?: "추천 계산 전입니다. 샘플 데이터를 바탕으로 루틴을 생성합니다.",
                actionLabel = "수면 스케줄 보기",
                onActionClick = onOpenSchedule,
            )
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("공부 피로 타임라인", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "최근 24시간의 졸음 이벤트를 바탕으로 집중 저하 구간을 요약합니다.",
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
) : ViewModel() {
    val uiState = combine(
        sleepRepository.observeSleepSessions(),
        drowsinessRepository.observeDrowsinessEvents(),
        recommendationRepository.observeLatestRecommendation(),
        examScheduleRepository.observeExamSchedules(),
    ) { sleeps, drowsiness, recommendation, exams ->
        HomeUiState(
            snapshot = HomeDashboardSnapshot(
                latestSleep = sleeps.firstOrNull(),
                recentDrowsinessCount = drowsiness.take(24).size,
                recommendation = recommendation,
                nextExam = exams.firstOrNull(),
            ),
            timelineSegments = listOf(
                TimelineSegment("안정 집중", 0.32f, SleepCarePrimary.copy(alpha = 0.22f), "오전 집중 구간"),
                TimelineSegment("졸음 경고", 0.08f + (drowsiness.size.coerceAtMost(3) * 0.01f), Color(0xFFFFB4AB), "오후 피로"),
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
}
