package com.sleepcare.mobile.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.data.source.PiPairingCodec
import com.sleepcare.mobile.domain.ConnectionStatus
import com.sleepcare.mobile.domain.ConnectedDeviceState
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.DeviceType
import com.sleepcare.mobile.domain.PiDebugConnectionMode
import com.sleepcare.mobile.domain.PiDebugEndpoint
import com.sleepcare.mobile.domain.PiDebugPacketLogEntry
import com.sleepcare.mobile.domain.PiDebugRepository
import com.sleepcare.mobile.domain.PiDebugState
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.domain.TrustedPiDevice
import com.sleepcare.mobile.domain.WatchDebugRepository
import com.sleepcare.mobile.domain.WatchDebugState
import com.sleepcare.mobile.ui.components.DeviceStatusCard
import com.sleepcare.mobile.ui.components.DeviceVisualStatus
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.components.toDisplayDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// 기기 연결 화면은 Repository가 합쳐 준 Pi/Watch 상태 리스트를 그대로 표시합니다.
data class DevicesUiState(
    val devices: List<ConnectedDeviceState> = emptyList(),
    val trustedPi: TrustedPiDevice? = null,
    val developerModeEnabled: Boolean = false,
    val watchDebugState: WatchDebugState = WatchDebugState(),
    val piDebugState: PiDebugState = PiDebugState(),
)

// Galaxy Watch와 Raspberry Pi 연결 상태를 카드로 보여주고 재시도/연결 해제를 제공합니다.
@Composable
fun DeviceConnectionScreen(
    paddingValues: PaddingValues,
    onOpenPiPairing: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.padding(paddingValues),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("기기 연결", style = MaterialTheme.typography.headlineMedium) }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Raspberry Pi 등록", style = MaterialTheme.typography.titleMedium)
                    val trustedPi = uiState.trustedPi
                    if (trustedPi == null) {
                        Text(
                            "Pi 화면의 QR 코드를 스캔해 이 앱이 신뢰할 SPKI fingerprint를 등록합니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${trustedPi.displayName} · ${trustedPi.deviceId}\nSPKI ${PiPairingCodec.shortPin(trustedPi.spkiSha256)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onOpenPiPairing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (trustedPi == null) "새 Pi QR 등록" else "QR로 재등록")
                    }
                    if (trustedPi != null) {
                        OutlinedButton(onClick = viewModel::forgetPi, modifier = Modifier.fillMaxWidth()) {
                            Text("등록 해제")
                        }
                    }
                }
            }
        }
        items(uiState.devices) { device ->
            DeviceStatusCard(
                deviceName = if (device.deviceType == DeviceType.Smartwatch) "Galaxy Watch" else device.deviceName,
                status = device.status.toVisualStatus(),
                subtitle = if (device.deviceType == DeviceType.Smartwatch) {
                    "심박/IBI 수집과 워치 진동 보조 경고용"
                } else {
                    "공부 중 졸음 감지 이벤트 수신용"
                },
                connectionDetails = if (device.deviceType == DeviceType.Smartwatch) {
                    buildString {
                        append(device.details ?: "Galaxy Watch를 찾는 중")
                        append("\n수면 데이터 연동은 워치 앱 구현 범위에서 함께 정리합니다.")
                        device.lastSeenAt?.let { append("\n마지막 확인 ${it.toDisplayDateTime()}") }
                    }
                } else {
                    buildString {
                        append(device.details ?: "준비 중")
                        device.lastSeenAt?.let { append("\n마지막 확인 ${it.toDisplayDateTime()}") }
                    }
                },
                statusLabel = if (device.deviceType == DeviceType.Smartwatch && device.status == ConnectionStatus.Connected) {
                    "Galaxy Watch 연결됨"
                } else {
                    null
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
        if (uiState.developerModeEnabled) {
            item {
                PiDebugCard(
                    state = uiState.piDebugState,
                    onEndpointChanged = viewModel::updatePiDebugEndpoint,
                    onConnectionModeChanged = viewModel::setPiDebugConnectionMode,
                    onReadSpki = viewModel::readPiDebugServerSpki,
                    onGenerateJson = viewModel::generatePiDebugPairingJson,
                    onRegisterJson = viewModel::registerPiDebugPairingJson,
                    onDiscoverNsd = viewModel::discoverPiDebugNsdCandidates,
                    onHello = viewModel::sendPiDebugHello,
                    onStartEyeOnly = viewModel::startPiDebugEyeOnlySession,
                    onStartEyeWithHr = viewModel::startPiDebugEyeWithSyntheticHrSession,
                    onSendHr = viewModel::sendPiDebugSyntheticHeartRate,
                    onStop = viewModel::stopPiDebugSession,
                    onClearPacketLogs = viewModel::clearPiDebugPacketLogs,
                )
            }
            item {
                WatchDebugCard(
                    state = uiState.watchDebugState,
                    onRefresh = viewModel::refreshWatchDebugConnection,
                    onStart = viewModel::startWatchDebugSession,
                    onFlush = viewModel::sendWatchDebugFlushPolicy,
                    onVibrate = viewModel::sendWatchDebugVibration,
                    onAck = viewModel::sendWatchDebugAck,
                    onBackfill = viewModel::requestWatchDebugBackfill,
                    onStop = viewModel::stopWatchDebugSession,
                )
            }
        }
        item {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("연결 안내", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "모바일 앱은 Galaxy Watch와 Wear OS Data Layer로 연결되고, Raspberry Pi는 로컬 Wi-Fi에서 찾습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = viewModel::startScan, modifier = Modifier.fillMaxWidth()) {
                        Text("로컬 Pi 다시 찾기")
                    }
                }
            }
        }
    }
}

@Composable
private fun PiDebugCard(
    state: PiDebugState,
    onEndpointChanged: (PiDebugEndpoint) -> Unit,
    onConnectionModeChanged: (PiDebugConnectionMode) -> Unit,
    onReadSpki: () -> Unit,
    onGenerateJson: () -> Unit,
    onRegisterJson: () -> Unit,
    onDiscoverNsd: () -> Unit,
    onHello: () -> Unit,
    onStartEyeOnly: () -> Unit,
    onStartEyeWithHr: () -> Unit,
    onSendHr: () -> Unit,
    onStop: () -> Unit,
    onClearPacketLogs: () -> Unit,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("개발자 모드 · Pi 개발 테스트", style = MaterialTheme.typography.titleMedium)
            Text(
                "QR/Avahi 없이도 WSS, hello, session.open, hr.ingest, session.close를 기존 프로토콜 그대로 확인합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.endpoint.host,
                onValueChange = { onEndpointChanged(state.endpoint.copy(host = it)) },
                label = { Text("host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.endpoint.port,
                onValueChange = { onEndpointChanged(state.endpoint.copy(port = it)) },
                label = { Text("port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.endpoint.wsPath,
                onValueChange = { onEndpointChanged(state.endpoint.copy(wsPath = it)) },
                label = { Text("wsPath") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.endpoint.deviceId,
                onValueChange = { onEndpointChanged(state.endpoint.copy(deviceId = it)) },
                label = { Text("deviceId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.endpoint.displayName,
                onValueChange = { onEndpointChanged(state.endpoint.copy(displayName = it)) },
                label = { Text("displayName") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onConnectionModeChanged(PiDebugConnectionMode.DirectEndpoint) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.commandInFlight,
            ) {
                Text(if (state.connectionMode == PiDebugConnectionMode.DirectEndpoint) "연결 모드: 직접 endpoint 선택됨" else "연결 모드: 직접 endpoint")
            }
            OutlinedButton(
                onClick = { onConnectionModeChanged(PiDebugConnectionMode.RegisteredPiNsd) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.commandInFlight,
            ) {
                Text(if (state.connectionMode == PiDebugConnectionMode.RegisteredPiNsd) "연결 모드: 등록 Pi(NSD) 선택됨" else "연결 모드: 등록 Pi(NSD)")
            }
            Text(
                buildString {
                    append("마지막 명령: ${state.lastCommandStatus}")
                    append("\n현재 모드: ${state.connectionMode.toUiLabel()}")
                    append("\nSPKI: ${state.serverSpkiSha256?.let(PiPairingCodec::shortPin) ?: "아직 없음"}")
                    append("\n테스트 세션: ${state.sessionId ?: "아직 없음"}")
                    state.lastHelloSummary?.let { append("\nhello: $it") }
                    state.lastRiskSummary?.let { append("\nrisk: $it") }
                    state.lastAlertSummary?.let { append("\nalert: $it") }
                    state.lastSummary?.let { append("\nsummary: $it") }
                    state.lastError?.let { append("\n오류: $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onReadSpki, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("SPKI 읽기")
            }
            OutlinedButton(onClick = onGenerateJson, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("pairing JSON 생성")
            }
            OutlinedButton(onClick = onRegisterJson, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("JSON으로 등록")
            }
            OutlinedButton(onClick = onDiscoverNsd, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("NSD 후보 검색")
            }
            OutlinedButton(onClick = onHello, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("hello 테스트")
            }
            Button(onClick = onStartEyeOnly, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("Eye-only 시작")
            }
            Button(onClick = onStartEyeWithHr, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("Eye+Synthetic HR 시작")
            }
            OutlinedButton(onClick = onSendHr, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("Synthetic HR 전송")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("종료")
            }
            if (state.generatedPairingJson != null) {
                Text("생성된 pairing JSON", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        state.generatedPairingJson,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.nsdCandidates.isNotEmpty()) {
                Text("NSD 후보", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        state.nsdCandidates.joinToString("\n\n") { candidate ->
                            buildString {
                                append(candidate.serviceName)
                                append(" · ${candidate.host ?: "host 없음"}:${candidate.port ?: 0}")
                                append("\nTXT ${candidate.attributes}")
                                candidate.error?.let { append("\n오류 $it") }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("패킷 로그", style = MaterialTheme.typography.labelLarge)
            Text(
                "앱 안의 패킷 로그는 Pi 개발 테스트 연결에서 받은 패킷만 보여줍니다. 운영 공부 세션 패킷은 adb logcat -s SleepCarePi로 확인합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onClearPacketLogs,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.packetLogs.isNotEmpty(),
            ) {
                Text("로그 지우기")
            }
            if (state.packetLogs.isEmpty()) {
                Text(
                    "아직 수신 패킷 없음",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(
                        state.packetLogs.joinToString("\n\n") { it.toUiText() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchDebugCard(
    state: WatchDebugState,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onFlush: () -> Unit,
    onVibrate: () -> Unit,
    onAck: () -> Unit,
    onBackfill: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("개발자 모드 · 워치 통신 테스트", style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append("Pi 공부 세션 없이 Wear OS Data Layer만 점검합니다.")
                    append("\n전송 성공은 워치 앱 수신 확인이 아니므로, 워치 설정의 Message Log도 함께 확인합니다.")
                    append("\n세션: ${state.sessionId ?: "아직 없음"}")
                    append("\n마지막 명령: ${state.lastCommandStatus}")
                    state.watchConnectionDetails?.let { append("\n워치 연결: $it") }
                    state.lastSessionEvent?.let { append("\n워치 이벤트: $it") }
                    state.latestHeartRateSummary?.let { append("\n심박 배치: $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("워치 연결 새로고침")
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("테스트 세션 시작")
            }
            OutlinedButton(onClick = onFlush, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("Flush policy 전송")
            }
            OutlinedButton(onClick = onVibrate, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("진동 테스트")
            }
            OutlinedButton(onClick = onAck, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("ACK 전송")
            }
            OutlinedButton(onClick = onBackfill, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("Backfill 요청")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth(), enabled = !state.commandInFlight) {
                Text("테스트 세션 종료")
            }
        }
    }
}

@HiltViewModel
// 기기 상태 Flow를 UI 상태로 감싸고 버튼 명령을 Repository로 전달합니다.
class DevicesViewModel @Inject constructor(
    private val deviceConnectionRepository: DeviceConnectionRepository,
    private val settingsRepository: SettingsRepository,
    private val watchDebugRepository: WatchDebugRepository,
    private val piDebugRepository: PiDebugRepository,
) : ViewModel() {
    val uiState = combine(
        deviceConnectionRepository.observeDevices(),
        deviceConnectionRepository.observeTrustedPi(),
        settingsRepository.observeDeveloperModeEnabled(),
        watchDebugRepository.observeDebugState(),
        piDebugRepository.observeDebugState(),
    ) { devices, trustedPi, developerModeEnabled, watchDebugState, piDebugState ->
        DevicesUiState(
            devices = devices,
            trustedPi = trustedPi,
            developerModeEnabled = developerModeEnabled,
            watchDebugState = watchDebugState,
            piDebugState = piDebugState,
        )
    }
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

    fun forgetPi() {
        viewModelScope.launch { deviceConnectionRepository.forgetPi() }
    }

    fun refreshWatchDebugConnection() {
        viewModelScope.launch { watchDebugRepository.refreshConnection() }
    }

    fun startWatchDebugSession() {
        viewModelScope.launch { watchDebugRepository.startTestSession() }
    }

    fun sendWatchDebugFlushPolicy() {
        viewModelScope.launch { watchDebugRepository.sendFlushPolicy() }
    }

    fun sendWatchDebugVibration() {
        viewModelScope.launch { watchDebugRepository.sendVibrationAlert() }
    }

    fun sendWatchDebugAck() {
        viewModelScope.launch { watchDebugRepository.sendAck() }
    }

    fun requestWatchDebugBackfill() {
        viewModelScope.launch { watchDebugRepository.requestBackfill() }
    }

    fun stopWatchDebugSession() {
        viewModelScope.launch { watchDebugRepository.stopTestSession() }
    }

    fun updatePiDebugEndpoint(endpoint: PiDebugEndpoint) {
        viewModelScope.launch { piDebugRepository.updateEndpoint(endpoint) }
    }

    fun setPiDebugConnectionMode(mode: PiDebugConnectionMode) {
        viewModelScope.launch { piDebugRepository.setConnectionMode(mode) }
    }

    fun readPiDebugServerSpki() {
        viewModelScope.launch { piDebugRepository.readServerSpki() }
    }

    fun generatePiDebugPairingJson() {
        viewModelScope.launch { piDebugRepository.generatePairingJson() }
    }

    fun registerPiDebugPairingJson() {
        viewModelScope.launch { piDebugRepository.registerGeneratedPairingJson() }
    }

    fun discoverPiDebugNsdCandidates() {
        viewModelScope.launch { piDebugRepository.discoverNsdCandidates() }
    }

    fun sendPiDebugHello() {
        viewModelScope.launch { piDebugRepository.sendHello() }
    }

    fun startPiDebugEyeOnlySession() {
        viewModelScope.launch { piDebugRepository.startEyeOnlySession() }
    }

    fun startPiDebugEyeWithSyntheticHrSession() {
        viewModelScope.launch { piDebugRepository.startEyeWithSyntheticHrSession() }
    }

    fun sendPiDebugSyntheticHeartRate() {
        viewModelScope.launch { piDebugRepository.sendSyntheticHeartRate() }
    }

    fun stopPiDebugSession() {
        viewModelScope.launch { piDebugRepository.stopTestSession() }
    }

    fun clearPiDebugPacketLogs() {
        viewModelScope.launch { piDebugRepository.clearPacketLogs() }
    }
}

// 도메인 연결 상태를 카드 컴포넌트가 이해하는 시각 상태로 변환합니다.
private fun ConnectionStatus.toVisualStatus(): DeviceVisualStatus = when (this) {
    ConnectionStatus.Connected -> DeviceVisualStatus.Connected
    ConnectionStatus.Scanning -> DeviceVisualStatus.Connecting
    ConnectionStatus.Disconnected -> DeviceVisualStatus.Disconnected
    ConnectionStatus.Failed -> DeviceVisualStatus.Error
}

private fun PiDebugConnectionMode.toUiLabel(): String = when (this) {
    PiDebugConnectionMode.DirectEndpoint -> "직접 endpoint"
    PiDebugConnectionMode.RegisteredPiNsd -> "등록 Pi(NSD)"
}

private fun PiDebugPacketLogEntry.toUiText(): String =
    buildString {
        append(receivedAt.toDisplayDateTime())
        append(" · ")
        append(type ?: "invalid")
        append(" · sid=${sessionId ?: "-"}")
        append(" · seq=${sequence ?: "-"}")
        append(" · ackRequired=${ackRequired ?: "-"}")
        if (!parsedSuccessfully) append(" · parse failed")
        append("\n")
        append(rawJson)
    }
