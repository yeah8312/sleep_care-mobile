package com.sleepcare.mobile.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.data.repository.buildDrowsinessAnalysisSnapshot
import com.sleepcare.mobile.data.repository.buildSleepAnalysisSnapshot
import com.sleepcare.mobile.domain.DrowsinessAnalysisSnapshot
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.SleepAnalysisSnapshot
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.InsightCallout
import com.sleepcare.mobile.ui.components.MetricHeroCard
import com.sleepcare.mobile.ui.components.TimelineBar
import com.sleepcare.mobile.ui.components.TimelineSegment
import com.sleepcare.mobile.ui.components.toDisplayDateTime
import com.sleepcare.mobile.ui.components.toDurationText
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AnalysisUiState(
    val sleep: SleepAnalysisSnapshot = SleepAnalysisSnapshot(76, 410, 74, 18, listOf(405, 398, 420, 415, 390, 408, 412)),
    val drowsiness: DrowsinessAnalysisSnapshot = DrowsinessAnalysisSnapshot(3, "14:00 - 14:59", 72, emptyList()),
)

@Composable
fun AnalysisHubScreen(
    paddingValues: PaddingValues,
    onOpenSleepDetail: () -> Unit,
    onOpenDrowsinessDetail: () -> Unit,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("분석 허브", style = MaterialTheme.typography.headlineMedium) }
        item {
            MetricHeroCard(
                title = "수면 품질",
                value = "${uiState.sleep.score}",
                subtitle = "평균 ${uiState.sleep.averageMinutes.toDurationText()}",
                accent = SleepCarePrimary,
                supportingText = "규칙성 ${uiState.sleep.consistency}점 · 수면 잠들기 ${uiState.sleep.latencyMinutes}분",
                onClick = onOpenSleepDetail,
            )
        }
        item {
            MetricHeroCard(
                title = "졸음 분석",
                value = "${uiState.drowsiness.totalCount}회",
                subtitle = "피크 ${uiState.drowsiness.peakWindowLabel}",
                accent = SleepCareTertiary,
                supportingText = "포커스 점수 ${uiState.drowsiness.focusScore}점",
                onClick = onOpenDrowsinessDetail,
            )
        }
        item {
            InsightCallout(
                title = "한눈에 보는 인사이트",
                message = "최근 수면 시간이 조금 짧고 오후 2시대 졸음이 반복됩니다. 취침 시각을 앞당기면 개선 가능성이 큽니다.",
            )
        }
    }
}

@Composable
fun SleepAnalysisDetailScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DetailHeader("수면 분석", onBack) }
        item {
            MetricHeroCard(
                title = "수면 점수",
                value = "${uiState.sleep.score}",
                subtitle = "/100",
                supportingText = "최근 일주일 평균 ${uiState.sleep.averageMinutes.toDurationText()} · 규칙성 ${uiState.sleep.consistency}점",
            )
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("주간 수면 길이", style = MaterialTheme.typography.titleLarge)
                    TimelineBar(
                        segments = uiState.sleep.weeklyDurations.mapIndexed { index, minutes ->
                            TimelineSegment(
                                label = "${index + 1}일 전",
                                percentage = (minutes / 480f).coerceIn(0.08f, 0.28f),
                                color = SleepCarePrimary.copy(alpha = 0.2f + (index * 0.05f)),
                                description = minutes.toDurationText(),
                            )
                        },
                    )
                }
            }
        }
        item {
            InsightCallout(
                title = "수면 루틴 제안",
                message = "수면 지연 시간이 ${uiState.sleep.latencyMinutes}분 수준이라 취침 전 루틴만 정리해도 점수 개선 여지가 있습니다.",
                actionLabel = "추천 스케줄 보기",
                onActionClick = onOpenSchedule,
            )
        }
    }
}

@Composable
fun DrowsinessAnalysisDetailScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DetailHeader("졸음 분석", onBack) }
        item {
            MetricHeroCard(
                title = "총 졸음 이벤트",
                value = "${uiState.drowsiness.totalCount}",
                subtitle = "최근 기록",
                accent = SleepCareTertiary,
                supportingText = "피크 시간대 ${uiState.drowsiness.peakWindowLabel} · 포커스 점수 ${uiState.drowsiness.focusScore}",
            )
        }
        item {
            InsightCallout(
                title = "주의가 필요한 시간",
                message = "오후 ${uiState.drowsiness.peakWindowLabel} 구간에 경고가 반복됩니다. 이 시간대에 복습 강도를 낮추거나 짧은 휴식을 넣어 보세요.",
            )
        }
        items(uiState.drowsiness.recentEvents) { event ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(event.label, style = MaterialTheme.typography.titleMedium)
                    Text(event.timestamp.toDisplayDateTime(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("강도 ${event.severity} · 지속 ${event.durationMinutes}분", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로 가기")
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    sleepRepository: SleepRepository,
    drowsinessRepository: DrowsinessRepository,
) : ViewModel() {
    val uiState = combine(
        sleepRepository.observeSleepSessions(),
        drowsinessRepository.observeDrowsinessEvents(),
    ) { sleeps, drowsiness ->
        AnalysisUiState(
            sleep = buildSleepAnalysisSnapshot(sleeps),
            drowsiness = buildDrowsinessAnalysisSnapshot(drowsiness, sleeps),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalysisUiState(),
    )
}
