package com.sleepcare.mobile.data.repository

import com.sleepcare.mobile.data.local.PreferencesStore
import com.sleepcare.mobile.data.source.PiDebugClient
import com.sleepcare.mobile.data.source.PiPairingCodec
import com.sleepcare.mobile.domain.PiAlertFire
import com.sleepcare.mobile.domain.PiDebugConnectionMode
import com.sleepcare.mobile.domain.PiDebugEndpoint
import com.sleepcare.mobile.domain.PiDebugRepository
import com.sleepcare.mobile.domain.PiDebugSessionMode
import com.sleepcare.mobile.domain.PiDebugState
import com.sleepcare.mobile.domain.PiPairingPayload
import com.sleepcare.mobile.domain.PiRiskUpdate
import com.sleepcare.mobile.domain.PiSessionSummary
import com.sleepcare.mobile.domain.TrustedPiDevice
import com.sleepcare.mobile.domain.WatchHeartRateSample
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

interface PiDebugTrustedPiStore {
    fun observeTrustedPi(): Flow<TrustedPiDevice?>
    suspend fun saveTrustedPi(device: TrustedPiDevice)
}

@Singleton
class PreferencesPiDebugTrustedPiStore @Inject constructor(
    private val preferencesStore: PreferencesStore,
) : PiDebugTrustedPiStore {
    override fun observeTrustedPi(): Flow<TrustedPiDevice?> = preferencesStore.trustedPiDevice

    override suspend fun saveTrustedPi(device: TrustedPiDevice) {
        preferencesStore.updateTrustedPiDevice(device)
    }
}

object PiDebugPayloadFactory {
    fun buildPairingJson(
        endpoint: PiDebugEndpoint,
        spkiSha256: String,
        issuedAtMs: Long = System.currentTimeMillis(),
    ): String {
        PiPairingCodec.validateSpkiPin(spkiSha256)
        val normalized = endpoint.normalized()
        return JSONObject()
            .put("proto", PiPairingCodec.PROTO)
            .put("device_id", normalized.deviceId)
            .put("display_name", normalized.displayName)
            .put("service", PiPairingCodec.SERVICE_TYPE)
            .put("ws", normalized.wsPath)
            .put("tls", 1)
            .put("spki_sha256", spkiSha256)
            .put("issued_at_ms", issuedAtMs)
            .put("pin_hint", PiPairingCodec.shortPin(spkiSha256))
            .toString(2)
    }

    fun syntheticHeartRateSamples(
        sessionId: String,
        firstSampleSeq: Long,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<WatchHeartRateSample> {
        // Pi 개발자가 실제 워치 구현 없이도 hr.ingest 파서를 확인할 수 있도록 정상 품질 샘플 3건만 보냅니다.
        return listOf(72, 74, 73).mapIndexed { index, bpm ->
            val seq = firstSampleSeq + index
            WatchHeartRateSample(
                sessionId = sessionId,
                messageSequence = seq,
                sampleSeq = seq,
                sensorTimestampMs = System.currentTimeMillis() + index * 1_000L,
                bpm = bpm,
                hrStatus = 1,
                ibiMs = listOf(820 - index * 8),
                ibiStatus = listOf(0),
                deliveryMode = "pi-debug",
                receivedAt = now.plusSeconds(index.toLong()),
            )
        }
    }
}

// Pi 개발 테스트는 QR/Avahi/WSS/세션 중 어디까지 되는지 분리해서 확인하는 용도입니다.
// 운영 StudySessionRepository를 통하지 않아 DB 세션 저장, 워치 명령, 운영 Pi 자동 연결을 건드리지 않습니다.
@Singleton
class PiDebugRepositoryImpl @Inject constructor(
    private val piDebugClient: PiDebugClient,
    private val trustedPiStore: PiDebugTrustedPiStore,
) : PiDebugRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debugState = MutableStateFlow(PiDebugState())
    private var nextSyntheticSampleSeq = 1L

    init {
        scope.launch {
            piDebugClient.observeRiskUpdates().collect { risk ->
                val sessionId = debugState.value.sessionId ?: return@collect
                if (risk.sessionId != sessionId) return@collect
                debugState.update { current -> current.copy(lastRiskSummary = risk.toDebugSummary()) }
            }
        }
        scope.launch {
            piDebugClient.observeAlerts().collect { alert ->
                val sessionId = debugState.value.sessionId ?: return@collect
                if (alert.sessionId != sessionId) return@collect
                debugState.update { current -> current.copy(lastAlertSummary = alert.toDebugSummary()) }
            }
        }
        scope.launch {
            piDebugClient.observeSessionSummaries().collect { summary ->
                val sessionId = debugState.value.sessionId ?: return@collect
                if (summary.sessionId != sessionId) return@collect
                debugState.update { current -> current.copy(lastSummary = summary.toDebugSummary()) }
            }
        }
        scope.launch {
            piDebugClient.observePacketLogs().collect { entry ->
                debugState.update { current ->
                    // 개발자 화면은 즉시 진단이 목적이므로 최신 패킷을 위에 두고 메모리에는 최근 80개만 유지합니다.
                    current.copy(packetLogs = (listOf(entry) + current.packetLogs).take(MAX_PACKET_LOGS))
                }
            }
        }
    }

    override fun observeDebugState(): Flow<PiDebugState> = debugState

    override suspend fun updateEndpoint(endpoint: PiDebugEndpoint) {
        debugState.update { current ->
            val sameServer = current.endpoint.host == endpoint.host &&
                current.endpoint.port == endpoint.port &&
                current.endpoint.wsPath == endpoint.wsPath
            current.copy(
                endpoint = endpoint,
                serverSpkiSha256 = if (sameServer) current.serverSpkiSha256 else null,
                generatedPairingJson = null,
                lastError = null,
            )
        }
    }

    override suspend fun setConnectionMode(mode: PiDebugConnectionMode) {
        debugState.update { current -> current.copy(connectionMode = mode, lastError = null) }
    }

    override suspend fun readServerSpki() {
        runPiCommand("SPKI 읽기") {
            val spki = piDebugClient.readServerSpki(debugState.value.endpoint)
            debugState.update { current ->
                current.copy(
                    serverSpkiSha256 = spki,
                    generatedPairingJson = null,
                )
            }
            "SPKI 읽기 성공: ${PiPairingCodec.shortPin(spki)}"
        }
    }

    override suspend fun generatePairingJson() {
        runPiCommand("pairing JSON 생성") {
            val endpoint = debugState.value.endpoint
            val spki = debugState.value.serverSpkiSha256
            if (spki == null) {
                throw IllegalStateException("먼저 SPKI 읽기를 실행해 주세요.")
            }
            val json = PiDebugPayloadFactory.buildPairingJson(endpoint, spki)
            // 생성 직후 기존 QR 파서로 검증해, 앱이 저장할 수 없는 JSON을 화면에 보여주지 않습니다.
            PiPairingCodec.parse(json)
            debugState.update { current -> current.copy(generatedPairingJson = json) }
            "pairing JSON 생성 성공"
        }
    }

    override suspend fun registerGeneratedPairingJson() {
        runPiCommand("JSON으로 등록") {
            val json = debugState.value.generatedPairingJson
                ?: throw IllegalStateException("먼저 pairing JSON 생성을 실행해 주세요.")
            val payload: PiPairingPayload = PiPairingCodec.parse(json)
            val trustedPi = PiPairingCodec.toTrustedDevice(payload)
            // 저장은 반드시 PiPairingCodec 검증을 통과한 뒤에만 수행합니다.
            // 직접 endpoint 입력값은 이 단계를 누르기 전까지 운영 trusted Pi로 자동 반영되지 않습니다.
            trustedPiStore.saveTrustedPi(trustedPi)
            "JSON 등록 성공: ${trustedPi.displayName} · ${PiPairingCodec.shortPin(trustedPi.spkiSha256)}"
        }
    }

    override suspend fun discoverNsdCandidates() {
        runPiCommand("NSD 후보 검색") {
            val candidates = piDebugClient.discoverNsdCandidates()
            debugState.update { current -> current.copy(nsdCandidates = candidates) }
            "NSD 후보 ${candidates.size}개 확인"
        }
    }

    override suspend fun sendHello() {
        runPiCommand("hello 테스트") {
            val ack = connectForSelectedMode()
            debugState.update { current -> current.copy(lastHelloSummary = "hello_ack · ${ack.deviceId} · ${ack.mode ?: "-"} · ${ack.protocol ?: "-"}") }
            "hello_ack 수신: ${ack.deviceId.ifBlank { "device_id 없음" }}"
        }
    }

    override suspend fun startEyeOnlySession() {
        startSession(PiDebugSessionMode.EyeOnly)
    }

    override suspend fun startEyeWithSyntheticHrSession() {
        startSession(PiDebugSessionMode.EyeWithSyntheticHr)
    }

    override suspend fun sendSyntheticHeartRate() {
        runPiCommand("Synthetic HR 전송") {
            val sessionId = debugState.value.sessionId
                ?: throw IllegalStateException("먼저 Pi 테스트 세션을 시작해 주세요.")
            val samples = PiDebugPayloadFactory.syntheticHeartRateSamples(sessionId, nextSyntheticSampleSeq)
            val delivered = piDebugClient.sendHeartRateSamples(samples)
            if (delivered.isNotEmpty()) {
                nextSyntheticSampleSeq = (delivered.maxOrNull() ?: nextSyntheticSampleSeq) + 1L
            }
            "hr.ingest ${delivered.size}/${samples.size}건 전송"
        }
    }

    override suspend fun stopTestSession() {
        runPiCommand("테스트 세션 종료") {
            val sessionId = debugState.value.sessionId
                ?: throw IllegalStateException("종료할 Pi 테스트 세션이 없습니다.")
            val summary = piDebugClient.stopSession(sessionId)
            summary?.let { received ->
                debugState.update { current -> current.copy(lastSummary = received.toDebugSummary()) }
            }
            "session.close 전송${if (summary == null) " · summary 대기 시간 초과" else ""}"
        }
    }

    override suspend fun clearPacketLogs() {
        debugState.update { current -> current.copy(packetLogs = emptyList()) }
    }

    private suspend fun startSession(mode: PiDebugSessionMode) {
        runPiCommand(if (mode == PiDebugSessionMode.EyeOnly) "Eye-only 시작" else "Eye+Synthetic HR 시작") {
            connectForSelectedMode()
            val sessionId = "pi-debug-${LocalDate.now()}-${UUID.randomUUID().toString().take(8)}"
            nextSyntheticSampleSeq = 1L
            debugState.update { current ->
                current.copy(
                    sessionId = sessionId,
                    lastRiskSummary = null,
                    lastAlertSummary = null,
                    lastSummary = null,
                )
            }
            val opened = piDebugClient.startSession(sessionId, mode)
            if (!opened) throw IllegalStateException("session.ack 대기 시간이 초과되었습니다. Pi 로그에서 session.open 처리 여부를 확인해 주세요.")
            "session.open 성공: $sessionId"
        }
    }

    private suspend fun connectForSelectedMode() = when (debugState.value.connectionMode) {
        PiDebugConnectionMode.DirectEndpoint -> {
            val endpoint = debugState.value.endpoint
            val spki = debugState.value.serverSpkiSha256
            if (spki == null) {
                throw IllegalStateException("직접 endpoint 모드는 먼저 SPKI 읽기가 필요합니다.")
            }
            piDebugClient.connectDirect(endpoint, spki)
        }

        PiDebugConnectionMode.RegisteredPiNsd -> {
            val trustedPi = trustedPiStore.observeTrustedPi().first()
                ?: throw IllegalStateException("등록 Pi(NSD) 모드는 먼저 pairing JSON/QR 등록이 필요합니다.")
            piDebugClient.connectRegistered(trustedPi)
        }
    }

    private suspend fun runPiCommand(
        label: String,
        block: suspend () -> String,
    ) {
        debugState.update { current ->
            current.copy(commandInFlight = true, lastCommandStatus = "$label 요청 중", lastError = null)
        }
        val result = runCatching { block() }
        debugState.update { current ->
            current.copy(
                commandInFlight = false,
                lastCommandStatus = result.getOrElse { "$label 실패" },
                lastError = result.exceptionOrNull()?.message,
            )
        }
    }
}

private fun PiDebugEndpoint.normalized(): PiDebugEndpoint =
    copy(
        host = host.trim(),
        port = port.trim(),
        wsPath = wsPath.trim().ifBlank { "/ws" }.let { if (it.startsWith("/")) it else "/$it" },
        deviceId = deviceId.trim().ifBlank { "sleepcare-pi" },
        displayName = displayName.trim().ifBlank { "SleepCare Pi" },
    )

private fun PiRiskUpdate.toDebugSummary(): String =
    "risk.update · state=$state · eye=${eyeScore ?: "-"} · hr=${hrScore ?: "-"} · fused=${fusedScore ?: "-"}"

private fun PiAlertFire.toDebugSummary(): String =
    "alert.fire · level=$level · reason=$reason · duration=${durationMs}ms"

private fun PiSessionSummary.toDebugSummary(): String =
    "session.summary · state=$finalState · alerts=$totalAlerts · mode=${mode ?: "-"} · reason=${summaryReason ?: "-"}"

private const val MAX_PACKET_LOGS = 80
