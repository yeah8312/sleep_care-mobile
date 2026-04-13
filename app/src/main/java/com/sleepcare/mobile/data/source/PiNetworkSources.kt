package com.sleepcare.mobile.data.source

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.annotation.RawRes
import com.sleepcare.mobile.R
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
import com.sleepcare.mobile.domain.WatchSleepDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

@Singleton
class UnavailableWatchSleepDataSource @Inject constructor() : WatchSleepDataSource {
    override suspend fun readRecentSleepSessions() = emptyList<com.sleepcare.mobile.domain.SleepSession>()
}

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

@Singleton
class PiNetworkDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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

        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Scanning,
            details = "로컬 Wi-Fi에서 SleepCare Pi를 찾는 중",
        )
        val discovered = discoverEndpoint()
        if (discovered == null) {
            connectionState.value = connectionState.value.copy(
                status = ConnectionStatus.Failed,
                details = "SleepCare Pi를 찾지 못했습니다. 같은 Wi-Fi에 연결되어 있는지 확인하세요.",
            )
            return@withContext false
        }

        endpoint = discovered
        connectWebSocket(discovered)
    }

    override suspend fun startSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        if (!discoverAndConnect()) return@withContext false

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
                    .put("watch_available", false)
                    .put("eye_only", true),
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

    private suspend fun connectWebSocket(endpoint: PiServiceEndpoint): Boolean = withContext(Dispatchers.IO) {
        val trustManager = resourceTrustManager(context, R.raw.sleepcare_pi_dev_cert)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        val okHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        val request = Request.Builder()
            .url("wss://${endpoint.host}:${endpoint.port}${endpoint.wsPath}")
            .build()

        connectionState.value = ConnectedDeviceState(
            deviceType = DeviceType.RaspberryPi,
            deviceName = endpoint.serviceName,
            status = ConnectionStatus.Scanning,
            details = "보안 채널 연결 중",
        )

        val waiter = CompletableDeferred<Boolean>()
        helloWaiter = waiter
        manualDisconnect = false
        client = okHttpClient
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                        deviceName = endpoint.serviceName,
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
        val envelope = PiProtocolCodec.parseEnvelope(text) ?: return
        connectionState.value = ConnectedDeviceState(
            deviceType = DeviceType.RaspberryPi,
            deviceName = endpoint.serviceName,
            status = ConnectionStatus.Connected,
            details = "${endpoint.deviceId} · ${endpoint.wsPath}",
            lastSeenAt = envelope.sentAtMs.toLocalDateTime(),
        )

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

            "pong", "ack" -> Unit
        }
    }

    private fun sendEnvelope(payload: String): Boolean = webSocket?.send(payload) == true

    private suspend fun discoverEndpoint(): PiServiceEndpoint? = withContext(Dispatchers.IO) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
                            if (resolved || serviceInfo.serviceType != NSD_SERVICE_TYPE) return
                            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    if (resolved) return
                                    val endpoint = serviceInfo.toEndpoint() ?: return
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

                    nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    continuation.invokeOnCancellation { stopDiscovery() }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun NsdServiceInfo.toEndpoint(): PiServiceEndpoint? {
        val attributes = attributes.mapValues { (_, value) -> value.decodeToString() }
        if (attributes["proto"] != "v1" || attributes["tls"] != "1") return null
        val path = attributes["ws"] ?: "/ws"
        val hostAddress = host?.hostAddress ?: return null
        return PiServiceEndpoint(
            serviceName = serviceName ?: "SleepCare Pi",
            host = hostAddress,
            port = port,
            wsPath = path,
            deviceId = attributes["device_id"] ?: serviceName ?: "sleepcare-pi",
        )
    }

    companion object {
        private const val NSD_SERVICE_TYPE = "_sleepcare._tcp"
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    takeIf { has(key) && !isNull(key) }?.optDouble(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    takeIf { has(key) && !isNull(key) }?.optInt(key)

private fun resourceTrustManager(context: Context, @RawRes resourceId: Int): X509TrustManager {
    val certificate = context.resources.openRawResource(resourceId).use { input ->
        CertificateFactory.getInstance("X.509").generateCertificate(input)
    }
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("sleepcare-pi", certificate)
    }
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore)
    }
    return trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
