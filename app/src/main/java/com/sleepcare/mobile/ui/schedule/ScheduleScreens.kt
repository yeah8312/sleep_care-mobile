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
import androidx.compose.material.icons.filled.Edit
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
import com.sleepcare.mobile.domain.RecommendationStatus
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

// 스케줄 탭에서 추천, 공부 계획, 시험 일정, 사용자 목표를 함께 들고 다니는 상태입니다.
data class ScheduleUiState(
    val recommendation: RecommendationSnapshot? = null,
    val studyPlan: StudyPlan? = null,
    val exams: List<ExamSchedule> = emptyList(),
    val userGoals: UserGoals = UserGoals(),
)

// 추천 엔진이 계산한 취침/기상 시간과 실천 팁을 보여주는 화면입니다.
@Composable
fun SleepScheduleSuggestionScreen(
    paddingValues: PaddingValues,
    onOpenStudyPlan: () -> Unit,
    onOpenExamSchedule: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recommendation = uiState.recommendation
    val hasReadyRecommendation = recommendation != null && recommendation.status != RecommendationStatus.NeedsSetup
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("수면 스케줄 제안", style = MaterialTheme.typography.headlineMedium) }
        item {
            ScheduleHero(
                bedtime = if (hasReadyRecommendation) recommendation.recommendedBedtime.toDisplayTime() else "--:--",
                wakeTime = if (hasReadyRecommendation) recommendation.recommendedWakeTime.toDisplayTime() else "--:--",
                totalSleep = if (hasReadyRecommendation) {
                    "${recommendation.targetSleepMinutes / 60}시간 ${recommendation.targetSleepMinutes % 60}분"
                } else {
                    "기준 설정 필요"
                },
                reason = recommendation?.reason ?: "수면 목표나 학습 가능 시간대를 설정하면 추천을 만들 수 있습니다.",
                primaryActionLabel = "학습 가능 시간 설정",
                secondaryActionLabel = "시험 일정 관리",
                onPrimaryAction = onOpenStudyPlan,
                onSecondaryAction = onOpenExamSchedule,
            )
        }
        item {
            UserGoalCard(
                userGoals = uiState.userGoals,
                onSave = viewModel::saveUserGoals,
            )
        }
        if (recommendation != null) {
            item { Text("판단 근거", style = MaterialTheme.typography.titleMedium) }
            items(recommendation.factors) { factor ->
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(factor.title, style = MaterialTheme.typography.titleMedium)
                        Text(factor.value, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            factor.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (recommendation.actionBlocks.isNotEmpty()) {
                item { Text("오늘의 실행 블록", style = MaterialTheme.typography.titleMedium) }
                items(recommendation.actionBlocks) { block ->
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(block.title, style = MaterialTheme.typography.titleMedium)
                            Text(block.timeLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                block.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        items(recommendation?.tips.orEmpty()) { tip ->
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
private fun UserGoalCard(
    userGoals: UserGoals,
    onSave: (UserGoals) -> Unit,
) {
    var wakeText by rememberSaveable(userGoals.targetWakeTime) { mutableStateOf(userGoals.targetWakeTime?.toDisplayTime() ?: "") }
    var bedtimeText by rememberSaveable(userGoals.preferredBedtime) { mutableStateOf(userGoals.preferredBedtime?.toDisplayTime() ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("수면 목표", style = MaterialTheme.typography.titleMedium)
            Text(
                "비워두면 해당 목표는 추천 기준에서 제외합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = wakeText,
                    onValueChange = { wakeText = it },
                    label = { Text("목표 기상") },
                    placeholder = { Text("07:00") },
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = bedtimeText,
                    onValueChange = { bedtimeText = it },
                    label = { Text("선호 취침") },
                    placeholder = { Text("23:00") },
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val result = parseUserGoalsForm(wakeText, bedtimeText)
                    if (result.error != null) {
                        error = result.error
                    } else {
                        error = null
                        onSave(result.value ?: UserGoals())
                    }
                },
            ) {
                Text("수면 목표 저장")
            }
        }
    }
}

// 사용자가 공부 가능 시간, 요일, 휴식 선호를 수정하는 화면입니다.
@Composable
fun StudyPlanScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentPlan = uiState.studyPlan
    var startText by rememberSaveable(currentPlan) { mutableStateOf(currentPlan?.startTime?.toDisplayTime() ?: "") }
    var endText by rememberSaveable(currentPlan) { mutableStateOf(currentPlan?.endTime?.toDisplayTime() ?: "") }
    var autoBreak by rememberSaveable(currentPlan) { mutableStateOf(currentPlan?.autoBreakEnabled ?: true) }
    var error by rememberSaveable(currentPlan) { mutableStateOf<String?>(null) }
    // 요일 선택은 Set으로 들고 있다가 저장할 때 StudyPlan으로 변환합니다.
    var selectedDays by remember(currentPlan) {
        mutableStateOf(currentPlan?.days ?: emptySet())
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
                            label = { Text("학습 가능 시작") },
                            placeholder = { Text("08:00") },
                        )
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("학습 가능 종료") },
                            placeholder = { Text("22:30") },
                        )
                    }
                    Text(
                        "이 시간대는 기상 시간을 강제하지 않고 첫 집중 블록과 저녁 학습 마감 판단에만 사용합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val result = parseStudyPlanForm(startText, endText, autoBreak, selectedDays)
                            if (result.error != null) {
                                error = result.error
                            } else {
                                error = null
                                viewModel.saveStudyPlan(result.value ?: return@Button)
                                onSaved()
                            }
                        },
                    ) {
                        Text("학습 가능 시간 저장")
                    }
                }
            }
        }
    }
}

// 시험 일정을 추가/삭제해 추천 기상 시각에 반영하는 화면입니다.
@Composable
fun ExamScheduleScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var editingExam by remember { mutableStateOf<ExamSchedule?>(null) }

    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("시험 일정 관리", onBack) }
        item {
            Button(onClick = {
                editingExam = null
                showDialog = true
            }, modifier = Modifier.fillMaxWidth()) {
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
                        Row {
                            IconButton(onClick = {
                                editingExam = exam
                                showDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "수정")
                            }
                            IconButton(onClick = {
                                viewModel.deleteExam(exam.id)
                                onChanged()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "삭제")
                            }
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
            initialExam = editingExam,
            onDismiss = {
                showDialog = false
                editingExam = null
            },
            onConfirm = { exam ->
                viewModel.upsertExam(exam)
                showDialog = false
                editingExam = null
                onChanged()
            },
        )
    }
}

// 간단한 시험 일정 입력 다이얼로그입니다. 추천 기준을 왜곡하지 않도록 잘못된 날짜/시간은 저장 전에 막습니다.
@Composable
private fun ExamEditorDialog(
    initialExam: ExamSchedule?,
    onDismiss: () -> Unit,
    onConfirm: (ExamSchedule) -> Unit,
) {
    var name by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.name ?: "") }
    var date by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.date?.toString() ?: "") }
    var startTime by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.startTime?.toDisplayTime() ?: "") }
    var endTime by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.endTime?.toDisplayTime() ?: "") }
    var location by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.location ?: "") }
    var priority by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.priority?.toString() ?: "1") }
    var syncEnabled by rememberSaveable(initialExam?.id) { mutableStateOf(initialExam?.syncEnabled ?: true) }
    var error by rememberSaveable(initialExam?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialExam == null) "시험 일정 추가" else "시험 일정 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") })
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("날짜 (YYYY-MM-DD)") }, placeholder = { Text(LocalDate.now().plusDays(7).toString()) })
                OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text("시작 시간") }, placeholder = { Text("09:00") })
                OutlinedTextField(value = endTime, onValueChange = { endTime = it }, label = { Text("종료 시간") }, placeholder = { Text("11:00") })
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
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = parseExamForm(
                    id = initialExam?.id ?: 0L,
                    name = name,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    priority = priority,
                    syncEnabled = syncEnabled,
                )
                if (result.error != null) {
                    error = result.error
                } else {
                    error = null
                    onConfirm(result.value ?: return@TextButton)
                }
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

internal data class FormResult<T>(
    val value: T?,
    val error: String?,
)

internal fun parseUserGoalsForm(wakeText: String, bedtimeText: String): FormResult<UserGoals> {
    val wake = if (wakeText.isBlank()) {
        null
    } else {
        parseRequiredTime(wakeText) ?: return FormResult(null, "목표 기상 시각은 HH:mm 형식으로 입력해 주세요.")
    }
    val bedtime = if (bedtimeText.isBlank()) {
        null
    } else {
        parseRequiredTime(bedtimeText) ?: return FormResult(null, "선호 취침 시각은 HH:mm 형식으로 입력해 주세요.")
    }
    return FormResult(UserGoals(targetWakeTime = wake, preferredBedtime = bedtime), null)
}

internal fun parseStudyPlanForm(
    startText: String,
    endText: String,
    autoBreakEnabled: Boolean,
    selectedDays: Set<DayOfWeek>,
): FormResult<StudyPlan> {
    val start = parseRequiredTime(startText) ?: return FormResult(null, "학습 가능 시작 시간을 HH:mm 형식으로 입력해 주세요.")
    val end = parseRequiredTime(endText) ?: return FormResult(null, "학습 가능 종료 시간을 HH:mm 형식으로 입력해 주세요.")
    if (!end.isAfter(start)) return FormResult(null, "학습 가능 종료 시간은 시작 시간보다 늦어야 합니다.")
    if (selectedDays.isEmpty()) return FormResult(null, "공부 요일을 하나 이상 선택해 주세요.")
    return FormResult(
        StudyPlan(
            startTime = start,
            endTime = end,
            focusHours = 0,
            days = selectedDays,
            breakPreferenceMinutes = 0,
            autoBreakEnabled = autoBreakEnabled,
        ),
        null,
    )
}

internal fun parseExamForm(
    id: Long,
    name: String,
    date: String,
    startTime: String,
    endTime: String,
    location: String,
    priority: String,
    syncEnabled: Boolean,
): FormResult<ExamSchedule> {
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
        ?: return FormResult(null, "시험 날짜는 YYYY-MM-DD 형식으로 입력해 주세요.")
    val start = parseRequiredTime(startTime) ?: return FormResult(null, "시험 시작 시간은 HH:mm 형식으로 입력해 주세요.")
    val end = parseRequiredTime(endTime) ?: return FormResult(null, "시험 종료 시간은 HH:mm 형식으로 입력해 주세요.")
    if (!end.isAfter(start)) return FormResult(null, "시험 종료 시간은 시작 시간보다 늦어야 합니다.")
    val parsedPriority = priority.toIntOrNull()?.takeIf { it >= 1 }
        ?: return FormResult(null, "우선순위는 1 이상의 숫자로 입력해 주세요.")
    return FormResult(
        ExamSchedule(
            id = id,
            name = name.ifBlank { "새 시험" },
            date = parsedDate,
            startTime = start,
            endTime = end,
            location = location.ifBlank { "미정" },
            priority = parsedPriority,
            syncEnabled = syncEnabled,
        ),
        null,
    )
}

private fun parseRequiredTime(raw: String): LocalTime? =
    runCatching { LocalTime.parse(raw) }.getOrNull()

@HiltViewModel
// 스케줄 관련 저장소를 합치고, 변경이 생길 때마다 추천을 다시 계산합니다.
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
        plan: StudyPlan,
    ) {
        viewModelScope.launch {
            studyPlanRepository.upsert(plan)
            recommendationRepository.refreshRecommendations()
        }
    }

    fun saveUserGoals(goals: UserGoals) {
        viewModelScope.launch {
            settingsRepository.updateUserGoals(goals)
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
