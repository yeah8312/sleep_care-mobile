package com.sleepcare.mobile.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.domain.ConnectionStatus
import com.sleepcare.mobile.domain.ConnectedDeviceState
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.DeviceType
import com.sleepcare.mobile.ui.components.DeviceStatusCard
import com.sleepcare.mobile.ui.components.DeviceVisualStatus
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.toDisplayDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DevicesUiState(
    val devices: List<ConnectedDeviceState> = emptyList(),
)

@Composable
fun DeviceConnectionScreen(
    paddingValues: PaddingValues,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("기기 연결", style = MaterialTheme.typography.headlineMedium) }
        items(uiState.devices) { device ->
            DeviceStatusCard(
                deviceName = device.deviceName,
                status = device.status.toVisualStatus(),
                subtitle = when (device.deviceType) {
                    DeviceType.RaspberryPi -> "공부 중 졸음 감지 이벤트 수신용"
                    DeviceType.Smartwatch -> "수면 기록 동기화용"
                },
                connectionDetails = buildString {
                    append(device.details ?: "준비 중")
                    device.lastSeenAt?.let { append("\n마지막 확인 ${it.toDisplayDateTime()}") }
                },
                actionLabel = when (device.status) {
                    ConnectionStatus.Connected -> "연결 해제"
                    ConnectionStatus.Scanning -> null
                    ConnectionStatus.Disconnected, ConnectionStatus.Failed -> "다시 시도"
                },
                onActionClick = {
                    when (device.status) {
                        ConnectionStatus.Connected -> viewModel.disconnect(device.deviceType)
                        ConnectionStatus.Scanning -> Unit
                        ConnectionStatus.Disconnected, ConnectionStatus.Failed -> viewModel.retry(device.deviceType)
                    }
                },
            )
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("연결 안내", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "실제 BLE/Health Connect 연동은 후속 단계에서 교체될 예정이며, 현재는 상태 흐름과 UI만 먼저 검증합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = viewModel::startScan, modifier = Modifier.fillMaxWidth()) {
                        Text("기기 스캔 시작")
                    }
                }
            }
        }
    }
}

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceConnectionRepository: DeviceConnectionRepository,
) : ViewModel() {
    val uiState = deviceConnectionRepository.observeDevices()
        .map { DevicesUiState(devices = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun startScan() {
        viewModelScope.launch { deviceConnectionRepository.startScan() }
    }

    fun retry(deviceType: DeviceType) {
        viewModelScope.launch { deviceConnectionRepository.retryConnection(deviceType) }
    }

    fun disconnect(deviceType: DeviceType) {
        viewModelScope.launch { deviceConnectionRepository.disconnect(deviceType) }
    }
}

private fun ConnectionStatus.toVisualStatus(): DeviceVisualStatus = when (this) {
    ConnectionStatus.Connected -> DeviceVisualStatus.Connected
    ConnectionStatus.Scanning -> DeviceVisualStatus.Connecting
    ConnectionStatus.Disconnected -> DeviceVisualStatus.Disconnected
    ConnectionStatus.Failed -> DeviceVisualStatus.Error
}
