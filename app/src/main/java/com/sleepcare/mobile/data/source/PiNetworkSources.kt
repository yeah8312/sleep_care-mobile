package com.sleepcare.mobile.data.source

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.sleepcare.mobile.BuildConfig
import com.sleepcare.mobile.data.local.PreferencesStore
import com.sleepcare.mobile.domain.ConnectionStatus
import com.sleepcare.mobile.domain.ConnectedDeviceState
import com.sleepcare.mobile.domain.DeviceType
import com.sleepcare.mobile.domain.PiAlertFire
import com.sleepcare.mobile.domain.PiEnvelope
import com.sleepcare.mobile.domain.PiHelloAck
import com.sleepcare.mobile.domain.PiNetworkDataSource
import com.sleepcare.mobile.domain.PiRiskUpdate
import com.sleepcare.mobile.domain.PiServiceEndpoint
import com.sleepcare.mobile.domain.PiSessionSummary
import com.sleepcare.mobile.domain.TrustedPiDevice
import com.sleepcare.mobile.domain.WatchHeartRateSample
import com.sleepcare.mobile.domain.WatchSleepDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// 아직 워치 수면 데이터 공급원이 없는 경우를 위한 빈 구현입니다.
@Singleton
class UnavailableWatchSleepDataSource @Inject constructor() : WatchSleepDataSource {
    override suspend fun readRecentSleepSessions() = emptyList<com.sleepcare.mobile.domain.SleepSession>()
}

// Android 앱과 Raspberry Pi 서버가 공유하는 JSON 메시지 규칙을 담당합니다.
// 문자열 파싱 실패가 앱 크래시로 이어지지 않도록 parse 함수들은 null을 반환합니다.
object PiProtocolCodec {
    private fun emptyBody() = JSONObject().toString()

    fun parseEnvelope(raw: String): PiEnvelope? = runCatching {
        val root = JSONObject(raw)
        PiEnvelope(
            version = root.optInt("v", 1),
            type = root.getString("t"),
            sessionId = root.optString("sid").takeIf { it.isNotBlank() },
            sequence = root.optLong("seq", 0L),
            source = root.optString("src", "pi"),
            sentAtMs = root.optLong("sent_at_ms", System.currentTimeMillis()),
            ackRequired = root.optBoolean("ack_required", false),
            body = (root.optJSONObject("body") ?: JSONObject()).toString(),
        )
    }.getOrNull()

    fun buildEnvelope(
        type: String,
        sequence: Long,
        source: String,
        sessionId: String? = null,
        ackRequired: Boolean = false,
        body: JSONObject = JSONObject(),
    ): String = JSONObject()
        .put("v", 1)
        .put("t", type)
        .put("sid", sessionId)
        .put("seq", sequence)
        .put("src", source)
        .put("sent_at_ms", System.currentTimeMillis())
        .put("ack_required", ackRequired)
        .put("body", body)
        .toString()

    fun parseHelloAck(envelope: PiEnvelope): PiHelloAck? {
        if (envelope.type != "hello_ack") return null
        val body = JSONObject(envelope.body.ifBlank { emptyBody() })
        return PiHelloAck(
            deviceId = body.optString("device_id", ""),
            mode = body.optString("mode").takeIf { it.isNotBlank() },
            protocol = body.optString("proto").takeIf { it.isNotBlank() },
        )
    }

    fun parseRiskUpdate(envelope: PiEnvelope): PiRiskUpdate? {
        if (envelope.type != "risk.update" || envelope.sessionId == null) return null
        val body = JSONObject(envelope.body.ifBlank { emptyBody() })
        return PiRiskUpdate(
            sessionId = envelope.sessionId,
            sequence = envelope.sequence,
            mode = body.optString("mode", "eye-only"),
            eyeScore = body.optDoubleOrNull("eye_score"),
            hrScore = body.optDoubleOrNull("hr_score"),
            fusedScore = body.optDoubleOrNull("fused_score"),
            state = body.optString("state", "BASELINE"),
            recommendedFlushSec = body.optIntOrNull("recommended_flush_sec"),
            receivedAt = envelope.sentAtMs.toLocalDateTime(),
        )
    }

    fun parseAlertFire(envelope: PiEnvelope): PiAlertFire? {
        if (envelope.type != "alert.fire" || envelope.sessionId == null) return null
        val body = JSONObject(envelope.body.ifBlank { emptyBody() })
        return PiAlertFire(
            sessionId = envelope.sessionId,
            sequence = envelope.sequence,
            level = body.optInt("level", 1),
            reason = body.optString("reason", "drowsiness_detected"),
            durationMs = body.optLong("duration_ms", 3_000L),
            receivedAt = envelope.sentAtMs.toLocalDateTime(),
        )
    }

    fun parseSessionSummary(envelope: PiEnvelope): PiSessionSummary? {
        if (envelope.type != "session.summary" || envelope.sessionId == null) return null
        val body = JSONObject(envelope.body.ifBlank { emptyBody() })
        return PiSessionSummary(
            sessionId = envelope.sessionId,
            sequence = envelope.sequence,
            finalState = body.optString("final_state", body.optString("state", "IDLE")),
            totalAlerts = body.optInt("total_alerts", body.optInt("alert_count", 0)),
            peakFusedScore = body.optDoubleOrNull("peak_fused_score"),
            mode = body.optString("mode").takeIf { it.isNotBlank() },
            summaryReason = body.optString("summary_reason", body.optString("reason")).takeIf { it.isNotBlank() },
            receivedAt = envelope.sentAtMs.toLocalDateTime(),
        )
    }
}

private fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

private fun LocalDateTime.toEpochMillis(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun List<Int>.toJsonArray() = org.json.JSONArray().apply {
    forEach { put(it) }
}

// Pi로 전달할 심박 품질 문자열을 워치 센서 상태 코드에서 단순화합니다.
private fun WatchHeartRateSample.toHrQuality(): String {
    val ibiStatusValue = ibiStatus.firstOrNull() ?: -999
    return when {
        hrStatus == 1 && ibiStatusValue == 0 -> "ok"
        hrStatus in setOf(-2, -8, -10) -> "motion_or_weak"
        hrStatus == -3 -> "detached"
        hrStatus in setOf(0, -999) -> "busy_or_initial"
        else -> "motion_or_weak"
    }
}

// 로컬 Wi-Fi에서 SleepCare Pi를 찾고 WSS WebSocket으로 실시간 위험도를 받는 데이터소스입니다.
@Singleton
class PiNetworkDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesStore: PreferencesStore,
) : PiNetworkDataSource {
    private val connectionState = MutableStateFlow(
        ConnectedDeviceState(
            deviceType = DeviceType.RaspberryPi,
            deviceName = "SleepCare Pi",
            status = ConnectionStatus.Disconnected,
            details = "같은 Wi-Fi에서 SleepCare Pi를 찾으세요.",
        )
    )
    private val riskState = MutableStateFlow<PiRiskUpdate?>(null)
    private val alertEvents = MutableSharedFlow<PiAlertFire>(extraBufferCapacity = 32)
    private val sessionSummaries = MutableSharedFlow<PiSessionSummary>(extraBufferCapacity = 8)
    private val sequence = AtomicLong(1L)
    private val openWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val closeWaiters = ConcurrentHashMap<String, CompletableDeferred<PiSessionSummary?>>()
    private var helloWaiter: CompletableDeferred<Boolean>? = null
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var endpoint: PiServiceEndpoint? = null
    private var manualDisconnect = false

    override fun observeConnectionState(): Flow<ConnectedDeviceState> = connectionState.asStateFlow()

    override fun observeRiskState(): Flow<PiRiskUpdate?> = riskState.asStateFlow()

    override fun observeAlerts(): Flow<PiAlertFire> = alertEvents.asSharedFlow()

    override fun observeSessionSummaries(): Flow<PiSessionSummary> = sessionSummaries.asSharedFlow()

    override suspend fun discoverAndConnect(): Boolean = withContext(Dispatchers.IO) {
        if (connectionState.value.status == ConnectionStatus.Connected && webSocket != null) return@withContext true

        // QR 등록으로 저장된 Pi 신뢰 정보가 있어야 NSD 검색과 TLS pin 검증을 시작할 수 있습니다.
        val trustedPi = preferencesStore.trustedPiDevice.first()
        if (trustedPi == null) {
            connectionState.value = connectionState.value.copy(
                status = ConnectionStatus.Failed,
                details = "Raspberry Pi 등록이 필요합니다. Pi 화면의 QR 코드를 먼저 스캔해 주세요.",
            )
            return@withContext false
        }

        // 저장된 deviceId와 serviceType으로 같은 Wi-Fi 안의 등록된 Pi만 찾습니다.
        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Scanning,
            details = "등록된 Pi(${trustedPi.deviceId})를 로컬 Wi-Fi에서 찾는 중입니다.",
        )
        val discovered = discoverEndpoint(trustedPi)
        if (discovered == null) {
            connectionState.value = connectionState.value.copy(
                status = ConnectionStatus.Failed,
                details = "등록된 SleepCare Pi를 찾지 못했습니다. 같은 Wi-Fi에 연결되어 있는지 확인해 주세요.",
            )
            return@withContext false
        }

        endpoint = discovered
        connectWebSocket(discovered, trustedPi)
    }

    override suspend fun startSession(
        sessionId: String,
        watchAvailable: Boolean,
        eyeOnly: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!discoverAndConnect()) return@withContext false

        // session.open은 session.ack를 받아야 성공으로 처리합니다.
        val waiter = CompletableDeferred<Boolean>()
        openWaiters[sessionId] = waiter
        val sent = sendEnvelope(
            PiProtocolCodec.buildEnvelope(
                type = "session.open",
                sequence = sequence.getAndIncrement(),
                source = "phone",
                sessionId = sessionId,
                ackRequired = true,
                body = JSONObject()
                    .put("study_mode", "focus")
                    .put("watch_available", watchAvailable)
                    .put("eye_only", eyeOnly),
            )
        )
        if (!sent) {
            openWaiters.remove(sessionId)
            return@withContext false
        }

        val opened = withTimeoutOrNull(6_000) { waiter.await() } ?: false
        openWaiters.remove(sessionId)
        opened
    }

    override suspend fun sendHeartRateSamples(samples: List<WatchHeartRateSample>): Set<Long> = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext emptySet()
        val deliveredSampleSeqs = mutableSetOf<Long>()
        // sampleSeq 순서대로 보내야 Pi 쪽 버퍼와 ACK 계산이 단순해집니다.
        samples.sortedBy { it.sampleSeq }.forEach { sample ->
            val sent = sendEnvelope(
                PiProtocolCodec.buildEnvelope(
                    type = "hr.ingest",
                    sequence = sequence.getAndIncrement(),
                    source = "phone",
                    sessionId = sample.sessionId,
                    ackRequired = false,
                    body = JSONObject()
                        .put("sample_seq", sample.sampleSeq)
                        .put("watch_sensor_ts_ms", sample.sensorTimestampMs)
                        .put("phone_rx_ms", sample.receivedAt.toEpochMillis())
                        .put("bpm", sample.bpm)
                        .put("hr_quality", sample.toHrQuality())
                        .put("hr_status", sample.hrStatus)
                        .put("ibi_ms", sample.ibiMs.toJsonArray()),
                )
            )
            if (sent) {
                deliveredSampleSeqs += sample.sampleSeq
            }
        }
        deliveredSampleSeqs
    }

    override suspend fun stopSession(sessionId: String): PiSessionSummary? = withContext(Dispatchers.IO) {
        if (webSocket == null) return@withContext null

        val waiter = CompletableDeferred<PiSessionSummary?>()
        closeWaiters[sessionId] = waiter
        val sent = sendEnvelope(
            PiProtocolCodec.buildEnvelope(
                type = "session.close",
                sequence = sequence.getAndIncrement(),
                source = "phone",
                sessionId = sessionId,
                ackRequired = true,
                body = JSONObject().put("reason", "user_stop"),
            )
        )
        if (!sent) {
            closeWaiters.remove(sessionId)
            return@withContext null
        }

        val summary = withTimeoutOrNull(8_000) { waiter.await() }
        closeWaiters.remove(sessionId)
        summary
    }

    override suspend fun retry(): Boolean {
        disconnect()
        return discoverAndConnect()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        manualDisconnect = true
        webSocket?.close(1000, "manual_disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        endpoint = null
        riskState.value = null
        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Disconnected,
            details = "연결이 해제되었습니다.",
        )
    }

    private suspend fun connectWebSocket(
        endpoint: PiServiceEndpoint,
        trustedPi: TrustedPiDevice,
    ): Boolean = withContext(Dispatchers.IO) {
        // 등록된 Pi의 SPKI pin을 검증하는 TrustManager를 설정합니다.
        // 이를 통해 network_security_config.xml에 등록되지 않은 self-signed 인증서를 사용하는 Pi와도
        // 등록 시 확인된 SPKI 기반으로 안전하게 연결할 수 있습니다.
        val pinningTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw IOException("서버 인증서가 없습니다.")
                val actualSpki = PiPairingCodec.certificateSpkiSha256(cert)
                if (actualSpki != trustedPi.spkiSha256) {
                    throw IOException("인증서 SPKI가 일치하지 않습니다.\n기대: ${trustedPi.spkiSha256}\n실제: $actualSpki")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())

        val okHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, pinningTrustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return if (hostname == "sleepcare-pi.local") {
                        java.net.InetAddress.getAllByName(endpoint.host).toList()
                    } else {
                        okhttp3.Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .build()
        val request = Request.Builder()
            .url("wss://sleepcare-pi.local:${endpoint.port}${endpoint.wsPath}")
            .build()

        connectionState.value = ConnectedDeviceState(
            deviceType = DeviceType.RaspberryPi,
            deviceName = trustedPi.displayName,
            status = ConnectionStatus.Scanning,
            details = "SPKI pin 검증으로 보안 채널 연결 중입니다.",
        )

        val waiter = CompletableDeferred<Boolean>()
        helloWaiter = waiter
        manualDisconnect = false
        client = okHttpClient
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 연결 직후 hello를 보내 Pi가 SleepCare 프로토콜을 지원하는지 확인합니다.
                sendEnvelope(
                    PiProtocolCodec.buildEnvelope(
                        type = "hello",
                        sequence = sequence.getAndIncrement(),
                        source = "phone",
                        ackRequired = true,
                        body = JSONObject()
                            .put("role", "android-app")
                            .put("watch_available", true)
                            .put("supports_eye_only", false),
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text, endpoint)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manualDisconnect) {
                    connectionState.value = connectionState.value.copy(
                        status = ConnectionStatus.Disconnected,
                        details = "라즈베리파이 연결이 종료되었습니다.",
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                helloWaiter?.complete(false)
                openWaiters.values.forEach { it.complete(false) }
                closeWaiters.values.forEach { it.complete(null) }
                if (!manualDisconnect) {
                    connectionState.value = ConnectedDeviceState(
                        deviceType = DeviceType.RaspberryPi,
                        deviceName = trustedPi.displayName,
                        status = ConnectionStatus.Failed,
                        details = t.message ?: "보안 채널 연결에 실패했습니다.",
                    )
                }
            }
        })

        val helloAck = withTimeoutOrNull(6_000) { waiter.await() } ?: false
        if (!helloAck) {
            webSocket?.cancel()
        }
        helloAck
    }

    private fun handleIncomingMessage(text: String, endpoint: PiServiceEndpoint) {
        val envelope = PiProtocolCodec.parseEnvelope(text)
        logPiIncomingPacket(text, envelope)
        if (envelope == null) return
        connectionState.value = ConnectedDeviceState(
            deviceType = DeviceType.RaspberryPi,
            deviceName = endpoint.serviceName,
            status = ConnectionStatus.Connected,
            details = "${endpoint.deviceId} · ${endpoint.wsPath}",
            lastSeenAt = envelope.sentAtMs.toLocalDateTime(),
        )

        // 메시지 타입별로 상태 Flow, 이벤트 Flow, 대기 중인 Deferred를 갱신합니다.
        when (envelope.type) {
            "hello_ack" -> {
                val ack = PiProtocolCodec.parseHelloAck(envelope)
                connectionState.value = connectionState.value.copy(
                    details = listOfNotNull(
                        ack?.deviceId?.takeIf { it.isNotBlank() },
                        ack?.mode,
                    ).joinToString(" · ").ifBlank { connectionState.value.details },
                )
                helloWaiter?.complete(true)
            }

            "session.ack" -> {
                envelope.sessionId?.let { sessionId ->
                    openWaiters.remove(sessionId)?.complete(true)
                }
            }

            "risk.update" -> {
                PiProtocolCodec.parseRiskUpdate(envelope)?.let { update ->
                    riskState.value = update
                }
            }

            "alert.fire" -> {
                PiProtocolCodec.parseAlertFire(envelope)?.let { alert ->
                    alertEvents.tryEmit(alert)
                }
            }

            "session.summary" -> {
                PiProtocolCodec.parseSessionSummary(envelope)?.let { summary ->
                    sessionSummaries.tryEmit(summary)
                    closeWaiters.remove(summary.sessionId)?.complete(summary)
                    riskState.value = null
                }
            }

            "ping" -> {
                sendEnvelope(
                    PiProtocolCodec.buildEnvelope(
                        type = "pong",
                        sequence = sequence.getAndIncrement(),
                        source = "phone",
                        sessionId = envelope.sessionId,
                        ackRequired = false,
                        body = JSONObject(),
                    )
                )
            }

            "pong", "ack" -> Unit
        }
    }

    private fun sendEnvelope(payload: String): Boolean = webSocket?.send(payload) == true

    private suspend fun discoverEndpoint(trustedPi: TrustedPiDevice): PiServiceEndpoint? = withContext(Dispatchers.IO) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Android에서 mDNS/NSD 패킷을 받으려면 Wi-Fi multicast lock이 필요합니다.
        val lock = wifiManager.createMulticastLock("sleepcare-pi-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { continuation ->
                    var discoveryListener: NsdManager.DiscoveryListener? = null
                    var resolved = false

                    fun stopDiscovery() {
                        runCatching {
                            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
                        }
                        if (lock.isHeld) lock.release()
                    }

                    discoveryListener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) = Unit

                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            // Android NSD는 서비스 타입 앞뒤에 점이 붙어 있을 수 있어 contains로 확인하는 것이 안전합니다.
                            if (resolved || !serviceInfo.serviceType.contains(trustedPi.serviceType)) return
                            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    if (resolved) return
                                    val endpoint = serviceInfo.toEndpoint(trustedPi) ?: return
                                    resolved = true
                                    stopDiscovery()
                                    if (continuation.isActive) continuation.resume(endpoint)
                                }
                            })
                        }

                        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                        override fun onDiscoveryStopped(serviceType: String) = Unit

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            stopDiscovery()
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                            stopDiscovery()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    nsdManager.discoverServices(trustedPi.serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    continuation.invokeOnCancellation { stopDiscovery() }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun NsdServiceInfo.toEndpoint(trustedPi: TrustedPiDevice): PiServiceEndpoint? {
        val attributes = attributes.mapValues { (_, value) -> value.decodeToString() }
        // SleepCare v1 + TLS를 광고하는 서비스만 실제 Pi로 인정합니다.
        // TXT 레코드가 아직 안 왔거나 프로토콜이 다르면 무시합니다.
        if (attributes["proto"] != "v1" || attributes["tls"] != "1") return null
        
        // device_id가 TXT 레코드에 없으면 서비스 이름을 차선책으로 사용합니다.
        val discoveredId = attributes["device_id"] ?: serviceName ?: return null
        if (discoveredId != trustedPi.deviceId && !serviceName.contains(trustedPi.deviceId)) return null

        val path = attributes["ws"] ?: trustedPi.wsPath
        if (path != trustedPi.wsPath) return null
        val hostAddress = host?.hostAddress ?: return null
        return PiServiceEndpoint(
            serviceName = trustedPi.displayName,
            host = hostAddress,
            port = port,
            wsPath = path,
            deviceId = discoveredId,
        )
    }


}

// JSONObject는 null과 누락을 같은 방식으로 다루지 않으므로, 선택 필드를 명시적으로 nullable로 읽습니다.
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    takeIf { has(key) && !isNull(key) }?.optDouble(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    takeIf { has(key) && !isNull(key) }?.optInt(key)

private const val PI_LOG_TAG = "SleepCarePi"

private fun logPiIncomingPacket(raw: String, envelope: PiEnvelope?) {
    if (!BuildConfig.DEBUG) return

    // Pi 개발자가 앱 내부 상태를 열어 보지 않아도 WSS 수신 여부와 파싱 결과를 logcat에서 확인하도록 남깁니다.
    // raw JSON에는 세션 정보가 포함될 수 있으므로 debug 빌드에서만 출력합니다.
    if (envelope == null) {
        Log.d(PI_LOG_TAG, "recv invalid raw=$raw")
        return
    }

    Log.d(PI_LOG_TAG, "recv raw=$raw")
    Log.d(
        PI_LOG_TAG,
        "recv type=${envelope.type} sid=${envelope.sessionId} seq=${envelope.sequence} ackRequired=${envelope.ackRequired}"
    )
}
