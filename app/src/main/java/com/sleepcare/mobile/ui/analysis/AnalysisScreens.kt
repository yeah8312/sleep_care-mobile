package com.sleepcare.mobile.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.data.repository.buildDrowsinessAnalysisSnapshot
import com.sleepcare.mobile.data.repository.buildSleepAnalysisSnapshot
import com.sleepcare.mobile.data.repository.buildWeeklySleepDaySummaries
import com.sleepcare.mobile.data.source.HealthConnectSleepDataSource
import com.sleepcare.mobile.data.source.HealthConnectSleepState
import com.sleepcare.mobile.domain.DrowsinessAnalysisSnapshot
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.SleepAnalysisSnapshot
import com.sleepcare.mobile.domain.SleepDaySummary
import com.sleepcare.mobile.domain.SleepSession
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.domain.StudySessionRepository
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.InsightCallout
import com.sleepcare.mobile.ui.components.MetricHeroCard
import com.sleepcare.mobile.ui.components.TimelineSegment
import com.sleepcare.mobile.ui.components.toDisplayDateTime
import com.sleepcare.mobile.ui.components.toDurationText
import com.sleepcare.mobile.ui.theme.SleepCareOnPrimary
import com.sleepcare.mobile.ui.theme.SleepCareOnSurface
import com.sleepcare.mobile.ui.theme.SleepCareOnSurfaceVariant
import com.sleepcare.mobile.ui.theme.SleepCareOutlineVariant
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCarePrimaryContainer
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import com.sleepcare.mobile.ui.theme.SleepCareTertiaryContainer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AnalysisUiState(
    val sleep: SleepAnalysisSnapshot = SleepAnalysisSnapshot(0, 0, 0, 0, 0, emptyList()),
    val drowsiness: DrowsinessAnalysisSnapshot = DrowsinessAnalysisSnapshot(0, "대기 중", 0, emptyList()),
    val sleepAvailable: Boolean = false,
    val sleepEmptyReason: String = "Health Connect 수면 상태를 확인하는 중입니다.",
    val weeklySleepDays: List<SleepDaySummary> = emptyList(),
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
            if (uiState.sleepAvailable) {
                MetricHeroCard(
                    title = "수면 품질",
                    value = "${uiState.sleep.score}",
                    subtitle = "평균 ${uiState.sleep.averageMinutes.toDurationText()}",
                    accent = SleepCarePrimary,
                    supportingText = "규칙성 ${uiState.sleep.consistency}점 · 밤중 각성 ${uiState.sleep.awakeMinutes}분",
                    onClick = onOpenSleepDetail,
                )
            } else {
                SleepUnavailableCallout(
                    message = uiState.sleepEmptyReason,
                    onActionClick = onOpenSleepDetail,
                )
            }
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
                message = if (uiState.sleepAvailable) {
                    "최근 수면 시간이 조금 짧고 오후 2시대 졸음이 반복됩니다. 취침 시각을 앞당기면 개선 가능성이 큽니다."
                } else {
                    uiState.sleepEmptyReason
                },
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
    val latestSleep = uiState.weeklySleepDays.firstOrNull()?.primarySession
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DetailHeader("수면 분석", onBack) }
        item {
            if (uiState.sleepAvailable) {
                SleepAnalysisHero(
                    sleep = uiState.sleep,
                    latestSleep = latestSleep,
                )
            } else {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Health Connect 수면 연동 상태", style = MaterialTheme.typography.titleLarge)
                        Text(
                            uiState.sleepEmptyReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Health Connect 권한과 수면 기록이 들어오면 수면 길이, 규칙성, 밤중 각성 시간을 여기에 채워 넣습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            if (uiState.sleepAvailable) {
                LatestSleepStructureCard(
                    sleep = uiState.sleep,
                    latestSleep = latestSleep,
                )
            } else {
                SleepUnavailableCallout(
                    message = uiState.sleepEmptyReason,
                    actionLabel = "추천 스케줄 보기",
                    onActionClick = onOpenSchedule,
                )
            }
        }
        item {
            if (uiState.sleepAvailable) {
                SleepMetricGrid(sleep = uiState.sleep)
            }
        }
        item {
            if (uiState.sleepAvailable) {
                WeeklySleepRhythmCard(
                    days = uiState.weeklySleepDays,
                    averageMinutes = uiState.sleep.averageMinutes,
                )
            }
        }
        item {
            if (uiState.sleepAvailable) {
                InsightCallout(
                    title = "수면 루틴 제안",
                    message = buildSleepRoutineInsight(uiState.sleep, latestSleep),
                    actionLabel = "추천 스케줄 보기",
                    onActionClick = onOpenSchedule,
                )
            } else {
                InsightCallout(
                    title = "수면 루틴은 지금 비어 있습니다",
                    message = "Health Connect 수면 연동이 붙으면 수면 길이와 규칙성을 반영한 루틴 제안을 다시 보여드릴게요.",
                    actionLabel = "추천 스케줄 보기",
                    onActionClick = onOpenSchedule,
                )
            }
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
    studySessionRepository: StudySessionRepository,
    private val sleepDataSource: HealthConnectSleepDataSource,
) : ViewModel() {
    val uiState = combine(
        sleepRepository.observeSleepSessions(),
        drowsinessRepository.observeDrowsinessEvents(),
        studySessionRepository.observeSessionState(),
        sleepDataSource.state,
    ) { sleeps, drowsiness, sessionState, sleepState ->
        val weeklySleepDays = buildWeeklySleepDaySummaries(sleeps).take(7)
        val sleepAvailable = weeklySleepDays.isNotEmpty()
        AnalysisUiState(
            sleep = if (sleepAvailable) {
                buildSleepAnalysisSnapshot(sleeps)
            } else {
                buildSleepAnalysisSnapshot(emptyList())
            },
            drowsiness = buildDrowsinessAnalysisSnapshot(drowsiness, sleeps, sessionState.latestRisk),
            sleepAvailable = sleepAvailable,
            sleepEmptyReason = sleepState.toAnalysisCopy(sleepAvailable),
            weeklySleepDays = weeklySleepDays,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalysisUiState(),
    )
}

@Composable
private fun SleepUnavailableCallout(
    message: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    InsightCallout(
        title = "Health Connect 수면 연동 상태",
        message = message,
        icon = Icons.Filled.Bedtime,
        actionLabel = actionLabel ?: if (onActionClick != null) "수면 분석 보기" else null,
        onActionClick = onActionClick,
    )
}

@Composable
private fun SleepAnalysisHero(
    sleep: SleepAnalysisSnapshot,
    latestSleep: SleepSession?,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        tone = SleepCarePrimaryContainer,
        borderColor = SleepCarePrimary.copy(alpha = 0.34f),
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                text = "최근 7일 수면 점수",
                style = MaterialTheme.typography.labelMedium,
                color = SleepCarePrimary.copy(alpha = 0.88f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "수면 점수",
                    style = MaterialTheme.typography.titleLarge,
                    color = SleepCareOnSurface,
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${sleep.score}",
                        style = MaterialTheme.typography.displayLarge,
                        color = SleepCareOnSurface,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "/ 100",
                        style = MaterialTheme.typography.headlineSmall,
                        color = SleepCarePrimary.copy(alpha = 0.92f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = "최근 일주일 평균 ${sleep.averageMinutes.toDurationText()} · 규칙성 ${sleep.consistency}점",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepCarePrimary.copy(alpha = 0.86f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(SleepCareTertiaryContainer.copy(alpha = 0.86f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Insights,
                    contentDescription = null,
                    tint = SleepCareTertiary,
                )
                Text(
                    text = latestSleep?.let {
                        "${formatSessionWindow(it)} 기준으로 총 수면 ${it.totalMinutes.toDurationText()}, 밤중 깸 ${it.awakeMinutes}분이 기록됐습니다."
                    } ?: "최근 동기화 데이터를 기준으로 수면 구조를 다시 정리했습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepCareOnSurface,
                )
            }
        }
    }
}

@Composable
private fun LatestSleepStructureCard(
    sleep: SleepAnalysisSnapshot,
    latestSleep: SleepSession?,
) {
    val sleepMinutes = sleep.averageMinutes.coerceAtLeast(1)
    val total = (sleep.awakeMinutes + sleepMinutes).coerceAtLeast(1)
    val segments = listOf(
        TimelineSegment(
            label = "실제 수면",
            percentage = sleepMinutes.toFloat() / total,
            color = SleepCarePrimary.copy(alpha = 0.9f),
            description = sleepMinutes.toDurationText(),
        ),
        TimelineSegment(
            label = "중간 각성",
            percentage = sleep.awakeMinutes.toFloat() / total,
            color = SleepCarePrimary.copy(alpha = 0.38f),
            description = "${sleep.awakeMinutes}분",
        ),
    )

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        tone = SleepCarePrimaryContainer.copy(alpha = 0.34f),
        borderColor = SleepCarePrimary.copy(alpha = 0.26f),
        contentPadding = PaddingValues(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("최근 수면 구조", style = MaterialTheme.typography.titleLarge)
                    Text(
                        latestSleep?.let { "${formatSessionWindow(it)} · ${it.source}" }
                            ?: "최근 동기화 기준 구조 요약",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Bedtime,
                    contentDescription = null,
                    tint = SleepCarePrimary,
                )
            }
            WeeklyBreakdownBar(segments = segments)
            Text(
                text = "원본 앱마다 수면 단계 상세 정도가 달라서, 현재 화면은 총 수면 시간과 밤중 각성을 중심으로 보여줍니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SleepMetricGrid(
    sleep: SleepAnalysisSnapshot,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SleepMetricTile(
                modifier = Modifier.weight(1f),
                title = "평균 수면",
                value = sleep.averageMinutes.toDurationText(),
                suffix = "",
                accent = SleepCareTertiary,
                icon = Icons.Filled.Bedtime,
                supporting = "최근 일주일 평균 수면 길이",
            )
            SleepMetricTile(
                modifier = Modifier.weight(1f),
                title = "중간 각성",
                value = "${sleep.awakeMinutes}",
                suffix = "min",
                accent = SleepCarePrimary,
                icon = Icons.Filled.Timeline,
                supporting = "밤중 각성 누적 시간",
            )
        }
        SleepMetricTile(
            modifier = Modifier.fillMaxWidth(),
            title = "규칙성",
            value = "${sleep.consistency}",
            suffix = "%",
            accent = SleepCarePrimary,
            icon = Icons.Filled.Star,
            supporting = "취침-기상 패턴의 안정성 점수",
            emphasized = true,
        )
    }
}

@Composable
private fun SleepMetricTile(
    title: String,
    value: String,
    suffix: String,
    accent: Color,
    supporting: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Insights,
    emphasized: Boolean = false,
) {
    val tone = if (emphasized) SleepCarePrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    val contentColor = if (emphasized) SleepCareOnPrimary else SleepCareOnSurface
    val muted = if (emphasized) SleepCareOnPrimary.copy(alpha = 0.76f) else SleepCareOnSurfaceVariant

    GlassCard(
        modifier = modifier,
        tone = tone,
        borderColor = accent.copy(alpha = 0.22f),
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (emphasized) SleepCareOnPrimary else accent,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = contentColor,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}

@Composable
private fun WeeklySleepRhythmCard(
    days: List<SleepDaySummary>,
    averageMinutes: Int,
) {
    val chronological = days.sortedBy { it.date }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        tone = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
        borderColor = SleepCareOutlineVariant.copy(alpha = 0.42f),
        contentPadding = PaddingValues(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("주간 수면 리듬", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "최근 ${chronological.size}일 수면 기준 · 평균 ${averageMinutes.toDurationText()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Timeline,
                    contentDescription = null,
                    tint = SleepCarePrimary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(248.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                chronological.forEach { day ->
                    WeeklySleepRhythmDay(
                        modifier = Modifier.weight(1f),
                        day = day,
                        isLatest = day.date == days.firstOrNull()?.date,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklySleepRhythmDay(
    day: SleepDaySummary,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    val session = day.primarySession
    val startFraction = session.sleepWindowStartFraction()
    val durationFraction = session.sleepWindowDurationFraction()

    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = day.date.dayOfWeek.toKoreanShortLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = if (isLatest) SleepCarePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontWeight = if (isLatest) FontWeight.Bold else FontWeight.Medium,
        )
        Text(
            text = "잠듦 ${formatClock(session.startTime)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val trackHeight = maxHeight
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(24.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(SleepCarePrimaryContainer.copy(alpha = 0.2f))
                    .border(1.dp, SleepCareOutlineVariant.copy(alpha = 0.24f), MaterialTheme.shapes.extraLarge),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = trackHeight * startFraction)
                    .width(if (isLatest) 18.dp else 16.dp)
                    .height((trackHeight * durationFraction).coerceAtLeast(18.dp))
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                SleepCareTertiary.copy(alpha = if (isLatest) 0.92f else 0.72f),
                                SleepCarePrimary.copy(alpha = if (isLatest) 0.95f else 0.78f),
                            )
                        )
                    ),
            )
        }
        Text(
            text = "기상 ${formatClock(session.endTime)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = day.totalMinutes.toDurationText(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isLatest) SleepCarePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (day.extraSleepMinutes > 0) {
            Text(
                text = "추가 수면 ${day.extraSleepMinutes.toDurationText()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun buildSleepRoutineInsight(
    sleep: SleepAnalysisSnapshot,
    latestSleep: SleepSession?,
): String {
    val base = when {
        sleep.awakeMinutes >= 20 ->
            "밤중 각성이 평균 ${sleep.awakeMinutes}분으로 잡혀 있어 늦은 카페인과 취침 직전 수분 섭취를 줄이면 안정감이 올라갈 수 있습니다."
        sleep.consistency < 75 ->
            "수면 길이는 괜찮지만 규칙성이 ${sleep.consistency}점이라 기상 시각을 먼저 고정하는 편이 회복감 개선에 더 유리합니다."
        else ->
            "최근 패턴은 안정적이라 현재 취침 루틴을 유지하면서 시험 기간에만 취침 시각을 15분 앞당기는 정도면 충분합니다."
    }
    return latestSleep?.let { "$base 최근 세션은 ${formatSessionWindow(it)}에 기록됐습니다." } ?: base
}

private fun formatSessionWindow(session: SleepSession): String =
    "${formatClock(session.startTime)} - ${formatClock(session.endTime)}"

private fun formatClock(dateTime: java.time.LocalDateTime): String =
    dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun WeeklyBreakdownBar(
    segments: List<TimelineSegment>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(SleepCarePrimaryContainer.copy(alpha = 0.26f))
                .border(1.dp, SleepCareOutlineVariant.copy(alpha = 0.34f), MaterialTheme.shapes.extraLarge),
        ) {
            segments.forEach { segment ->
                Box(
                    modifier = Modifier
                        .weight(segment.percentage.coerceIn(0.08f, 1f))
                        .fillMaxHeight()
                        .background(segment.color)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.forEach { segment ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(segment.color)
                    )
                    Text(
                        text = segment.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = SleepCareOnSurfaceVariant,
                    )
                    segment.description?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = SleepCareOnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun SleepSession.sleepWindowStartFraction(): Float {
    val windowStartMinutes = 18 * 60
    val totalWindowMinutes = 18 * 60f
    val minutes = startTime.hour * 60 + startTime.minute
    val normalized = if (minutes < 12 * 60) minutes + 24 * 60 else minutes
    return ((normalized - windowStartMinutes) / totalWindowMinutes).coerceIn(0f, 0.92f)
}

private fun SleepSession.sleepWindowDurationFraction(): Float {
    val totalWindowMinutes = 18 * 60f
    val duration = totalMinutes.coerceAtLeast(45)
    return (duration / totalWindowMinutes).coerceIn(0.1f, 0.72f)
}

private fun java.time.DayOfWeek.toKoreanShortLabel(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "월"
    java.time.DayOfWeek.TUESDAY -> "화"
    java.time.DayOfWeek.WEDNESDAY -> "수"
    java.time.DayOfWeek.THURSDAY -> "목"
    java.time.DayOfWeek.FRIDAY -> "금"
    java.time.DayOfWeek.SATURDAY -> "토"
    java.time.DayOfWeek.SUNDAY -> "일"
}

private fun HealthConnectSleepState.toAnalysisCopy(sleepAvailable: Boolean): String = when {
    sleepAvailable -> "최근 Health Connect 수면 데이터를 불러왔습니다."
    this is HealthConnectSleepState.PermissionDenied -> "Health Connect 수면 권한이 없어 최근 수면 기록을 읽지 못했습니다."
    this is HealthConnectSleepState.Unavailable -> "Health Connect가 이 기기에서 아직 사용할 수 없습니다."
    this is HealthConnectSleepState.ProviderUpdateRequired -> "Health Connect 제공자 업데이트가 필요합니다."
    this is HealthConnectSleepState.NoData -> "Health Connect 권한은 있지만 읽을 수면 데이터가 아직 없습니다."
    this is HealthConnectSleepState.Error -> "Health Connect 수면 동기화 중 오류가 발생했습니다."
    else -> "Health Connect 수면 상태를 확인하는 중입니다."
}
