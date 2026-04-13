package com.sleepcare.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.sleepcare.mobile.domain.NotificationPreferences
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.toDisplayDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: NotificationPreferences = NotificationPreferences(),
    val lastSyncText: String = "워치 앱 준비 중",
)

@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    onOpenDevices: () -> Unit,
    onResetCompleted: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("설정", style = MaterialTheme.typography.headlineMedium) }
        item {
            SettingSwitchCard(
                title = "졸음 알림",
                description = "중요 졸음 이벤트가 감지되면 앱 알림으로 알려줍니다.",
                checked = uiState.preferences.drowsinessAlertsEnabled,
                onCheckedChange = {
                    viewModel.updatePreferences(uiState.preferences.copy(drowsinessAlertsEnabled = it))
                },
            )
        }
        item {
            SettingSwitchCard(
                title = "수면 리마인더",
                description = "추천 취침 시각이 가까워지면 미리 준비 시간을 알려줍니다.",
                checked = uiState.preferences.sleepRemindersEnabled,
                onCheckedChange = {
                    viewModel.updatePreferences(uiState.preferences.copy(sleepRemindersEnabled = it))
                },
            )
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("기기 관리", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "워치 앱은 아직 사용할 수 없고 Raspberry Pi 상태만 확인합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenDevices, modifier = Modifier.fillMaxWidth()) {
                        Text("기기 연결 화면 열기")
                    }
                }
            }
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("연동 상태", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "워치 앱 준비 중 · Raspberry Pi 졸음 기록만 활성화됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        uiState.lastSyncText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("데이터 초기화", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "온보딩 상태와 샘플 데이터를 다시 초기 상태로 되돌립니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onResetCompleted, modifier = Modifier.fillMaxWidth()) {
                        Text("앱 데이터 초기화")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val uiState = combine(
        settingsRepository.observeNotificationPreferences(),
        settingsRepository.observeLastSyncState(),
    ) { preferences, lastSync ->
        SettingsUiState(
            preferences = preferences,
            lastSyncText = buildString {
                append(
                    if (lastSync.drowsinessSyncedAt != null) {
                        "Pi 졸음 ${lastSync.drowsinessSyncedAt.toDisplayDateTime()}"
                    } else {
                        "Pi 졸음 기록 없음"
                    }
                )
                append(" · 워치 앱 준비 중")
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun updatePreferences(preferences: NotificationPreferences) {
        viewModelScope.launch {
            settingsRepository.updateNotificationPreferences(preferences)
        }
    }
}
