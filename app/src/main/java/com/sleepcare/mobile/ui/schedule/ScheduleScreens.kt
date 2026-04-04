package com.sleepcare.mobile.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.domain.ExamSchedule
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.RecommendationSnapshot
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.domain.StudyPlan
import com.sleepcare.mobile.domain.StudyPlanRepository
import com.sleepcare.mobile.domain.UserGoals
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.ScheduleHero
import com.sleepcare.mobile.ui.components.toDisplayDate
import com.sleepcare.mobile.ui.components.toDisplayTime
import com.sleepcare.mobile.ui.components.toKoreanShort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val recommendation: RecommendationSnapshot? = null,
    val studyPlan: StudyPlan? = null,
    val exams: List<ExamSchedule> = emptyList(),
    val userGoals: UserGoals = UserGoals(),
)

@Composable
fun SleepScheduleSuggestionScreen(
    paddingValues: PaddingValues,
    onOpenStudyPlan: () -> Unit,
    onOpenExamSchedule: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("수면 스케줄 제안", style = MaterialTheme.typography.headlineMedium) }
        item {
            ScheduleHero(
                bedtime = uiState.recommendation?.recommendedBedtime?.toDisplayTime() ?: "--:--",
                wakeTime = uiState.recommendation?.recommendedWakeTime?.toDisplayTime() ?: "--:--",
                totalSleep = uiState.recommendation?.targetSleepMinutes?.let { "${it / 60}시간 ${it % 60}분" } ?: "데이터 준비 중",
                reason = uiState.recommendation?.reason ?: "추천 로직 계산 중",
                primaryActionLabel = "학습 플랜 수정",
                secondaryActionLabel = "시험 일정 관리",
                onPrimaryAction = onOpenStudyPlan,
                onSecondaryAction = onOpenExamSchedule,
            )
        }
        items(uiState.recommendation?.tips.orEmpty()) { tip ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tip.title, style = MaterialTheme.typography.titleMedium)
                    Text(tip.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun StudyPlanScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentPlan = uiState.studyPlan
    var startText by rememberSaveable(currentPlan) { mutableStateOf(currentPlan?.startTime?.toDisplayTime() ?: "08:00") }
    var endText by rememberSaveable(currentPlan) { mutableStateOf(currentPlan?.endTime?.toDisplayTime() ?: "22:30") }
    var focusHours by rememberSaveable(currentPlan) { mutableStateOf((currentPlan?.focusHours ?: 8).toString()) }
    var breakMinutes by rememberSaveable(currentPlan) { mutableStateOf((currentPlan?.breakPreferenceMinutes ?: 15).toString()) }
    var autoBreak by rememberSaveable(currentPlan) { mutableStateOf(currentPlan?.autoBreakEnabled ?: true) }
    var selectedDays by remember(currentPlan) {
        mutableStateOf(currentPlan?.days ?: setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        ))
    }

    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("학습 플랜", onBack) }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("시작 시간") },
                        )
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("종료 시간") },
                        )
                    }
                    OutlinedTextField(
                        value = focusHours,
                        onValueChange = { focusHours = it },
                        label = { Text("하루 목표 공부 시간") },
                        suffix = { Text("시간") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = breakMinutes,
                        onValueChange = { breakMinutes = it },
                        label = { Text("권장 휴식 길이") },
                        suffix = { Text("분") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("공부 요일", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in selectedDays,
                                onClick = {
                                    selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                                },
                                label = { Text(day.toKoreanShort()) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("자동 휴식 제안")
                            Text(
                                "졸음 타이밍을 참고해 쉬는 시간을 제안합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoBreak, onCheckedChange = { autoBreak = it })
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.saveStudyPlan(
                                startText = startText,
                                endText = endText,
                                focusHours = focusHours,
                                breakMinutes = breakMinutes,
                                autoBreakEnabled = autoBreak,
                                selectedDays = selectedDays,
                            )
                            onSaved()
                        },
                    ) {
                        Text("학습 플랜 저장")
                    }
                }
            }
        }
    }
}

@Composable
fun ExamScheduleScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("시험 일정 관리", onBack) }
        item {
            Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  시험 추가")
            }
        }
        items(uiState.exams) { exam ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(exam.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${exam.date.toDisplayDate()} · ${exam.startTime.toDisplayTime()} - ${exam.endTime.toDisplayTime()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            viewModel.deleteExam(exam.id)
                            onChanged()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "삭제")
                        }
                    }
                    Text("장소: ${exam.location}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "우선순위 ${exam.priority} · 기기 동기화 ${if (exam.syncEnabled) "사용" else "미사용"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showDialog) {
        ExamEditorDialog(
            onDismiss = { showDialog = false },
            onConfirm = { exam ->
                viewModel.upsertExam(exam)
                showDialog = false
                onChanged()
            },
        )
    }
}

@Composable
private fun ExamEditorDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExamSchedule) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(7).toString()) }
    var startTime by rememberSaveable { mutableStateOf("07:00") }
    var endTime by rememberSaveable { mutableStateOf("10:00") }
    var location by rememberSaveable { mutableStateOf("시험장") }
    var priority by rememberSaveable { mutableStateOf("1") }
    var syncEnabled by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시험 일정 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") })
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("날짜 (YYYY-MM-DD)") })
                OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text("시작 시간") })
                OutlinedTextField(value = endTime, onValueChange = { endTime = it }, label = { Text("종료 시간") })
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("장소") })
                OutlinedTextField(value = priority, onValueChange = { priority = it }, label = { Text("우선순위") })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("기기 동기화")
                    Switch(checked = syncEnabled, onCheckedChange = { syncEnabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    ExamSchedule(
                        name = name.ifBlank { "새 시험" },
                        date = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now().plusDays(7)),
                        startTime = runCatching { LocalTime.parse(startTime) }.getOrDefault(LocalTime.of(7, 0)),
                        endTime = runCatching { LocalTime.parse(endTime) }.getOrDefault(LocalTime.of(10, 0)),
                        location = location,
                        priority = priority.toIntOrNull() ?: 1,
                        syncEnabled = syncEnabled,
                    ),
                )
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로 가기")
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val studyPlanRepository: StudyPlanRepository,
    private val examScheduleRepository: ExamScheduleRepository,
    private val recommendationRepository: RecommendationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val uiState = combine(
        recommendationRepository.observeLatestRecommendation(),
        studyPlanRepository.observeStudyPlan(),
        examScheduleRepository.observeExamSchedules(),
        settingsRepository.observeUserGoals(),
    ) { recommendation, studyPlan, exams, goals ->
        ScheduleUiState(
            recommendation = recommendation,
            studyPlan = studyPlan,
            exams = exams,
            userGoals = goals,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    fun saveStudyPlan(
        startText: String,
        endText: String,
        focusHours: String,
        breakMinutes: String,
        autoBreakEnabled: Boolean,
        selectedDays: Set<DayOfWeek>,
    ) {
        viewModelScope.launch {
            val plan = StudyPlan(
                startTime = runCatching { LocalTime.parse(startText) }.getOrDefault(LocalTime.of(8, 0)),
                endTime = runCatching { LocalTime.parse(endText) }.getOrDefault(LocalTime.of(22, 30)),
                focusHours = focusHours.toIntOrNull() ?: 8,
                days = selectedDays.ifEmpty {
                    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                },
                breakPreferenceMinutes = breakMinutes.toIntOrNull() ?: 15,
                autoBreakEnabled = autoBreakEnabled,
            )
            studyPlanRepository.upsert(plan)
            settingsRepository.updateUserGoals(UserGoals(targetWakeTime = plan.startTime.minusMinutes(90)))
            recommendationRepository.refreshRecommendations()
        }
    }

    fun upsertExam(exam: ExamSchedule) {
        viewModelScope.launch {
            examScheduleRepository.upsert(exam)
            recommendationRepository.refreshRecommendations()
        }
    }

    fun deleteExam(id: Long) {
        viewModelScope.launch {
            examScheduleRepository.delete(id)
            recommendationRepository.refreshRecommendations()
        }
    }
}
