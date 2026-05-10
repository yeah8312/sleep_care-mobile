package com.sleepcare.mobile.data.source

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.sleepcare.mobile.BuildConfig
import com.sleepcare.mobile.domain.PiAlertFire
import com.sleepcare.mobile.domain.PiDebugEndpoint
import com.sleepcare.mobile.domain.PiDebugNsdCandidate
import com.sleepcare.mobile.domain.PiDebugPacketLogEntry
import com.sleepcare.mobile.domain.PiDebugSessionMode
import com.sleepcare.mobile.domain.PiEnvelope
import com.sleepcare.mobile.domain.PiHelloAck
import com.sleepcare.mobile.domain.PiRiskUpdate
import com.sleepcare.mobile.domain.PiSessionSummary
import com.sleepcare.mobile.domain.TrustedPiDevice
import com.sleepcare.mobile.domain.WatchHeartRateSample
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

interface PiDebugClient {
    fun observeRiskUpdates(): Flow<PiRiskUpdate>
    fun observeAlerts(): Flow<PiAlertFire>
    fun observeSessionSummaries(): Flow<PiSessionSummary>
    fun observePacketLogs(): Flow<PiDebugPacketLogEntry>
    suspend fun readServerSpki(endpoint: PiDebugEndpoint): String
    suspend fun discoverNsdCandidates(timeoutMs: Long = 8_000): List<PiDebugNsdCandidate>
    suspend fun connectDirect(endpoint: PiDebugEndpoint, expectedSpkiSha256: String): PiHelloAck
    suspend fun connectRegistered(trustedPi: TrustedPiDevice): PiHelloAck
    suspend fun startSession(sessionId: String, mode: PiDebugSessionMode): Boolean
    suspend fun sendHeartRateSamples(samples: List<WatchHeartRateSample>): Set<Long>
    suspend fun stopSession(sessionId: String): PiSessionSummary?
    suspend fun disconnect()
}

// Pi 개발자 테스트 전용 WSS 클라이언트입니다.
// 운영 PiNetworkDataSource와 소켓/상태를 공유하지 않아 직접 endpoint 실험이 운영 세션으로 새어 나가지 않습니다.
@Singleton
class PiDebugNetworkClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PiDebugClient {
    private val riskUpdates = MutableSharedFlow<PiRiskUpdate>(extraBufferCapacity = 16)
    private val alertEvents = MutableSharedFlow<PiAlertFire>(extraBufferCapacity = 16)
    private val sessionSummaries = MutableSharedFlow<PiSessionSummary>(extraBufferCapacity = 8)
    private val packetLogs = MutableSharedFlow<PiDebugPacketLogEntry>(extraBufferCapacity = 80)
    private val sequence = AtomicLong(1L)
    private val openWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val closeWaiters = ConcurrentHashMap<String, CompletableDeferred<PiSessionSummary?>>()
    private var helloWaiter: CompletableDeferred<PiHelloAck>? = null
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    override fun observeRiskUpdates(): Flow<PiRiskUpdate> = riskUpdates.asSharedFlow()

    override fun observeAlerts(): Flow<PiAlertFire> = alertEvents.asSharedFlow()

    override fun observeSessionSummaries(): Flow<PiSessionSummary> = sessionSummaries.asSharedFlow()

    override fun observePacketLogs(): Flow<PiDebugPacketLogEntry> = packetLogs.asSharedFlow()

    override suspend fun readServerSpki(endpoint: PiDebugEndpoint): String = withContext(Dispatchers.IO) {
        val parsed = endpoint.toParsedEndpoint()

        // SPKI 추출을 위해 일시적으로 모든 인증서를 허용하는 TrustManager를 사용합니다.
        // 추출된 SPKI는 이후 pairing 과정에서 사용자 확인 또는 저장된 신뢰값과 비교됩니다.
        val trustAllCerts = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllCerts), SecureRandom())

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return if (hostname == "sleepcare-pi.local") {
                        java.net.InetAddress.getAllByName(parsed.host).toList()
                    } else {
                        okhttp3.Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .build()
        val waiter = CompletableDeferred<String>()

        val request = Request.Builder().url("wss://sleepcare-pi.local:${parsed.port}${parsed.wsPath}").build()
        val socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 이 단계는 등록이 아니라 인증서 pin을 읽는 진단입니다.
                // 아직 신뢰하지 않는 인증서도 열어 보되, 결과는 pairing JSON 검증 경로를 거쳐야만 저장됩니다.
                val certificate = runCatching {
                    response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                }.getOrNull()
                if (certificate == null) {
                    waiter.completeExceptionally(IOException("WSS handshake에서 서버 인증서를 읽지 못했습니다."))
                } else {
                    waiter.complete(PiPairingCodec.certificateSpkiSha256(certificate))
                }
                webSocket.close(1000, "spki_probe_done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                waiter.completeExceptionally(IOException(response?.message ?: t.message ?: "SPKI 읽기 연결 실패", t))
            }
        })

        try {
            withTimeoutOrNull(6_000) { waiter.await() }
                ?: throw IOException("SPKI 읽기 시간이 초과되었습니다. WSS 포트, 경로, 방화벽을 확인해 주세요.")
        } finally {
            socket.cancel()
            okHttpClient.dispatcher.executorService.shutdown()
        }
    }

    override suspend fun discoverNsdCandidates(timeoutMs: Long): List<PiDebugNsdCandidate> = withContext(Dispatchers.IO) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val candidates = Collections.synchronizedList(mutableListOf<PiDebugNsdCandidate>())
        val lock = wifiManager.createMulticastLock("sleepcare-pi-debug-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        var discoveryListener: NsdManager.DiscoveryListener? = null

        fun stopDiscovery() {
            runCatching {
                discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            }
            if (lock.isHeld) lock.release()
        }

        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // Android NSD는 서비스 타입 앞뒤에 점이 붙어 있을 수 있어 contains로 확인하는 것이 안전합니다.
                    if (!serviceInfo.serviceType.contains(PiPairingCodec.SERVICE_TYPE)) return
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            candidates += serviceInfo.toDebugCandidate(error = "resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            candidates += serviceInfo.toDebugCandidate()
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    candidates += PiDebugNsdCandidate(
                        serviceName = serviceType,
                        serviceType = serviceType,
                        host = null,
                        port = null,
                        attributes = emptyMap(),
                        error = "discovery start failed: $errorCode",
                    )
                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }

            nsdManager.discoverServices(PiPairingCodec.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            delay(timeoutMs)
            candidates.toList().distinctBy { "${it.serviceName}|${it.host}|${it.port}|${it.error}" }
        } finally {
            stopDiscovery()
        }
    }

    override suspend fun connectDirect(endpoint: PiDebugEndpoint, expectedSpkiSha256: String): PiHelloAck = withContext(Dispatchers.IO) {
        PiPairingCodec.validateSpkiPin(expectedSpkiSha256)
        val parsed = endpoint.toParsedEndpoint()
        connectWebSocket(parsed, expectedSpkiSha256)
    }

    override suspend fun connectRegistered(trustedPi: TrustedPiDevice): PiHelloAck = withContext(Dispatchers.IO) {
        val candidate = discoverNsdCandidates().firstOrNull { candidate ->
            val matchesId = candidate.attributes["device_id"] == trustedPi.deviceId ||
                candidate.serviceName.contains(trustedPi.deviceId)

            candidate.error == null &&
                candidate.attributes["proto"] == "v1" &&
                candidate.attributes["tls"] == "1" &&
                matchesId &&
                (candidate.attributes["ws"] ?: trustedPi.wsPath) == trustedPi.wsPath &&
                candidate.host != null &&
                candidate.port != null
        } ?: throw IOException("등록 Pi(${trustedPi.deviceId})와 일치하는 NSD 후보를 찾지 못했습니다.")

        val endpoint = PiDebugEndpoint(
            host = candidate.host.orEmpty(),
            port = candidate.port.toString(),
            wsPath = candidate.attributes["ws"] ?: trustedPi.wsPath,
            deviceId = trustedPi.deviceId,
            displayName = trustedPi.displayName,
        )
        connectDirect(endpoint, trustedPi.spkiSha256)
    }

    override suspend fun startSession(sessionId: String, mode: PiDebugSessionMode): Boolean = withContext(Dispatchers.IO) {
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
                    .put("watch_available", mode == PiDebugSessionMode.EyeWithSyntheticHr)
                    .put("eye_only", mode == PiDebugSessionMode.EyeOnly),
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
        val delivered = mutableSetOf<Long>()
        samples.sortedBy { it.sampleSeq }.forEach { sample ->
            val sent = sendEnvelope(
                PiProtocolCodec.buildEnvelope(
                    type = "hr.ingest",
                    sequence = sequence.getAndIncrement(),
                    source = "phone",
                    sessionId = sample.sessionId,
                    body = JSONObject()
                        .put("sample_seq", sample.sampleSeq)
                        .put("watch_sensor_ts_ms", sample.sensorTimestampMs)
                        .put("phone_rx_ms", sample.receivedAt.toEpochMillis())
                        .put("bpm", sample.bpm)
                        .put("hr_quality", sample.toDebugHrQuality())
                        .put("hr_status", sample.hrStatus)
                        .put("ibi_ms", sample.ibiMs.toJsonArray()),
                )
            )
            if (sent) delivered += sample.sampleSeq
        }
        delivered
    }

    override suspend fun stopSession(sessionId: String): PiSessionSummary? = withContext(Dispatchers.IO) {
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

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
    }

    private suspend fun connectWebSocket(
        endpoint: ParsedPiDebugEndpoint,
        expectedSpkiSha256: String,
    ): PiHelloAck {
        disconnectInternal()

        // 지정된 SPKI와 일치하는지 검증하는 TrustManager를 설정합니다.
        // 이를 통해 self-signed 인증서를 사용하는 Pi와도 안전하게 핀 고정(Pinning) 연결이 가능합니다.
        val pinningTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw IOException("서버 인증서가 없습니다.")
                val actualSpki = PiPairingCodec.certificateSpkiSha256(cert)
                if (actualSpki != expectedSpkiSha256) {
                    throw IOException("인증서 SPKI가 일치하지 않습니다.\n기대: $expectedSpkiSha256\n실제: $actualSpki")
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
        val request = Request.Builder().url("wss://sleepcare-pi.local:${endpoint.port}${endpoint.wsPath}").build()
        val waiter = CompletableDeferred<PiHelloAck>()
        helloWaiter = waiter
        client = okHttpClient
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // hello는 운영 프로토콜의 첫 악수입니다. 디버그 전용 메시지를 만들지 않고 Pi 구현을 그대로 검증합니다.
                sendEnvelope(
                    PiProtocolCodec.buildEnvelope(
                        type = "hello",
                        sequence = sequence.getAndIncrement(),
                        source = "phone",
                        ackRequired = true,
                        body = JSONObject()
                            .put("role", "android-app")
                            .put("watch_available", false)
                            .put("supports_eye_only", true),
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                helloWaiter?.completeExceptionally(IOException(response?.message ?: t.message ?: "Pi WSS 연결 실패", t))
                openWaiters.values.forEach { it.complete(false) }
                closeWaiters.values.forEach { it.complete(null) }
            }
        })

        return withTimeoutOrNull(6_000) { waiter.await() }
            ?: throw IOException("hello_ack 대기 시간이 초과되었습니다. Pi 로그에서 hello 수신 여부를 확인해 주세요.")
    }

    private fun handleIncomingMessage(text: String) {
        val envelope = PiProtocolCodec.parseEnvelope(text)
        logPiIncomingPacket(text, envelope)
        packetLogs.tryEmit(text.toPacketLogEntry(envelope))
        if (envelope == null) return
        when (envelope.type) {
            "hello_ack" -> {
                PiProtocolCodec.parseHelloAck(envelope)?.let { ack ->
                    helloWaiter?.complete(ack)
                }
            }

            "session.ack" -> {
                envelope.sessionId?.let { sessionId ->
                    openWaiters.remove(sessionId)?.complete(true)
                }
            }

            "risk.update" -> {
                PiProtocolCodec.parseRiskUpdate(envelope)?.let { riskUpdates.tryEmit(it) }
            }

            "alert.fire" -> {
                PiProtocolCodec.parseAlertFire(envelope)?.let { alertEvents.tryEmit(it) }
            }

            "session.summary" -> {
                PiProtocolCodec.parseSessionSummary(envelope)?.let { summary ->
                    sessionSummaries.tryEmit(summary)
                    closeWaiters.remove(summary.sessionId)?.complete(summary)
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

    private fun disconnectInternal() {
        webSocket?.close(1000, "pi_debug_disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        helloWaiter = null
        openWaiters.clear()
        closeWaiters.clear()
    }
}

private data class ParsedPiDebugEndpoint(
    val host: String,
    val port: Int,
    val wsPath: String,
    val deviceId: String,
    val displayName: String,
) {
    val url: String = "wss://$host:$port$wsPath"
}

private fun PiDebugEndpoint.toParsedEndpoint(): ParsedPiDebugEndpoint {
    val trimmedHost = host.trim()
    if (trimmedHost.isBlank()) throw IOException("host를 입력해 주세요.")
    val parsedPort = port.trim().toIntOrNull()?.takeIf { it in 1..65_535 }
        ?: throw IOException("port는 1~65535 사이 숫자로 입력해 주세요.")
    val normalizedPath = wsPath.trim().ifBlank { "/ws" }.let { if (it.startsWith("/")) it else "/$it" }
    return ParsedPiDebugEndpoint(
        host = trimmedHost,
        port = parsedPort,
        wsPath = normalizedPath,
        deviceId = deviceId.trim().ifBlank { "sleepcare-pi" },
        displayName = displayName.trim().ifBlank { "SleepCare Pi" },
    )
}

private fun NsdServiceInfo.toDebugCandidate(error: String? = null): PiDebugNsdCandidate =
    PiDebugNsdCandidate(
        serviceName = serviceName ?: "(unknown)",
        serviceType = serviceType ?: PiPairingCodec.SERVICE_TYPE,
        host = host?.hostAddress,
        port = port.takeIf { it > 0 },
        attributes = attributes.mapValues { (_, value) -> value.decodeToString() },
        error = error,
    )

private fun WatchHeartRateSample.toDebugHrQuality(): String {
    val ibiStatusValue = ibiStatus.firstOrNull() ?: -999
    return when {
        hrStatus == 1 && ibiStatusValue == 0 -> "ok"
        hrStatus in setOf(-2, -8, -10) -> "motion_or_weak"
        hrStatus == -3 -> "detached"
        hrStatus in setOf(0, -999) -> "busy_or_initial"
        else -> "motion_or_weak"
    }
}

private fun LocalDateTime.toEpochMillis(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun List<Int>.toJsonArray() = JSONArray().apply {
    forEach { put(it) }
}

private const val PI_LOG_TAG = "SleepCarePi"

private fun logPiIncomingPacket(raw: String, envelope: PiEnvelope?) {
    if (!BuildConfig.DEBUG) return

    // Pi 개발자 모드는 운영 세션 저장 없이 프로토콜만 검증하므로,
    // 수신 원문과 envelope 요약을 함께 남겨 Pi 구현자가 필드 불일치를 바로 볼 수 있게 합니다.
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

private fun String.toPacketLogEntry(envelope: PiEnvelope?): PiDebugPacketLogEntry =
    PiDebugPacketLogEntry(
        receivedAt = LocalDateTime.now(),
        rawJson = this,
        parsedSuccessfully = envelope != null,
        type = envelope?.type,
        sessionId = envelope?.sessionId,
        sequence = envelope?.sequence,
        ackRequired = envelope?.ackRequired,
    )
