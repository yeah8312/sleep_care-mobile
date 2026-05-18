package com.sleepcare.watch.service

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.sleepcare.watch.contracts.WatchFlushPolicy
import com.sleepcare.watch.contracts.WatchHeartRateSample
import com.sleepcare.watch.contracts.WatchSessionConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Samsung Health Sensor SDK와 SleepCare 워치 계약 사이의 실제 센서 어댑터입니다.
// SDK 연결, HEART_RATE_CONTINUOUS tracker 등록, flush, 런타임 오류 보고만 이 클래스가 담당합니다.
class SamsungHealthSensorBackend(
    context: Context,
    private val mapper: SamsungHeartRateSampleMapper = SamsungHeartRateSampleMapper(),
) : WatchSensorBackend {
    private val appContext = context.applicationContext
    private val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messageSequence = AtomicLong(0L)
    private val sampleSequence = AtomicLong(0L)

    @Volatile
    private var stopping = false
    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private var trackerListener: HealthTracker.TrackerEventListener? = null
    private var flushJob: kotlinx.coroutines.Job? = null
    private var currentSessionId: String? = null
    private var runtimeErrorCallback: ((WatchBackendRuntimeError) -> Unit)? = null

    override suspend fun start(
        config: WatchSessionConfig,
        onSample: (WatchHeartRateSample) -> Unit,
        onRuntimeError: (WatchBackendRuntimeError) -> Unit,
    ): WatchBackendStartResult {
        stop()
        stopping = false
        currentSessionId = config.sessionId
        runtimeErrorCallback = onRuntimeError
        messageSequence.set(0L)
        sampleSequence.set(0L)

        val startResult = connectAndStartTracking(config, onSample)
        if (startResult.started) {
            startFlushLoop(config.flushPolicy)
        } else {
            stop()
        }
        return startResult
    }

    override suspend fun updateFlushPolicy(flushPolicy: WatchFlushPolicy) {
        if (heartRateTracker == null) return
        startFlushLoop(flushPolicy)
    }

    override suspend fun stop() {
        stopping = true
        flushJob?.cancel()
        flushJob = null

        runCatching {
            heartRateTracker?.unsetEventListener()
        }.onFailure { throwable ->
            Log.d(TAG, "hr tracker unset failed", throwable)
        }
        runCatching {
            healthTrackingService?.disconnectService()
        }.onFailure { throwable ->
            Log.d(TAG, "health tracking service disconnect failed", throwable)
        }

        trackerListener = null
        heartRateTracker = null
        healthTrackingService = null
        currentSessionId = null
        runtimeErrorCallback = null
    }

    private suspend fun connectAndStartTracking(
        config: WatchSessionConfig,
        onSample: (WatchHeartRateSample) -> Unit,
    ): WatchBackendStartResult {
        val startResult = CompletableDeferred<WatchBackendStartResult>()
        var connectingService: HealthTrackingService? = null

        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                if (startResult.isCompleted) return
                val service = connectingService ?: healthTrackingService
                if (service == null) {
                    startResult.completeFailure("Samsung Health Tracking Service reference is missing.")
                    return
                }

                val result = runCatching {
                    val supportedTrackers = service.getTrackingCapability().getSupportHealthTrackerTypes()
                    if (!supportedTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
                        return@runCatching WatchBackendStartResult(
                            started = false,
                            message = "Galaxy Watch가 HEART_RATE_CONTINUOUS tracker를 지원하지 않습니다.",
                            sensorBackend = SENSOR_BACKEND,
                        )
                    }

                    val tracker = service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                    val listener = heartRateListener(onSample)
                    healthTrackingService = service
                    heartRateTracker = tracker
                    trackerListener = listener
                    tracker.setEventListener(listener)
                    if (startResult.isCompleted) {
                        tracker.unsetEventListener()
                        return@runCatching WatchBackendStartResult(
                            started = false,
                            message = "Samsung Health Tracking Service 연결 시간이 초과되었습니다.",
                            sensorBackend = SENSOR_BACKEND,
                        )
                    }

                    Log.d(TAG, "hr tracker started sid=${config.sessionId}")
                    WatchBackendStartResult(
                        started = true,
                        message = "Samsung Health Sensor SDK heart rate tracker started.",
                        sensorBackend = SENSOR_BACKEND,
                    )
                }.getOrElse { throwable ->
                    WatchBackendStartResult(
                        started = false,
                        message = "Samsung Health Sensor SDK tracker 시작 실패: ${throwable.readableMessage()}",
                        sensorBackend = SENSOR_BACKEND,
                    )
                }
                startResult.completeIfNeeded(result)
            }

            override fun onConnectionFailed(exception: HealthTrackerException) {
                if (startResult.isCompleted) return
                startResult.completeFailure(exception.toStartFailureMessage())
            }

            override fun onConnectionEnded() {
                if (!stopping && heartRateTracker != null) {
                    reportRuntimeError(
                        WatchBackendRuntimeError(
                            code = "sensor_connection_ended",
                            message = "Samsung Health Tracking Service 연결이 종료되었습니다.",
                            recoverable = true,
                        ),
                    )
                }
            }
        }

        return runCatching {
            val service = HealthTrackingService(connectionListener, appContext)
            connectingService = service
            healthTrackingService = service
            service.connectService()

            withTimeoutOrNull(ServiceConnectTimeoutMs) {
                startResult.await()
            } ?: WatchBackendStartResult(
                started = false,
                message = "Samsung Health Tracking Service 연결 시간이 초과되었습니다.",
                sensorBackend = SENSOR_BACKEND,
            ).also { timeoutResult ->
                startResult.completeIfNeeded(timeoutResult)
            }
        }.getOrElse { throwable ->
            WatchBackendStartResult(
                started = false,
                message = "Samsung Health Tracking Service 연결 실패: ${throwable.readableMessage()}",
                sensorBackend = SENSOR_BACKEND,
            )
        }
    }

    private fun heartRateListener(
        onSample: (WatchHeartRateSample) -> Unit,
    ): HealthTracker.TrackerEventListener =
        object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                val sessionId = currentSessionId ?: return
                // Samsung SDK는 화면 꺼짐/flush 상황에서 여러 DataPoint를 한 번에 주므로 순서를 유지해 sampleSeq를 붙입니다.
                dataPoints.forEach { dataPoint ->
                    val reading = dataPoint.toHeartRateReading()
                    val sample = mapper.toSample(
                        sessionId = sessionId,
                        reading = reading,
                        messageSequence = messageSequence.incrementAndGet(),
                        sampleSeq = sampleSequence.incrementAndGet(),
                    )
                    Log.d(
                        TAG,
                        "hr sdk sample sid=$sessionId seq=${sample.sampleSeq} bpm=${sample.bpm} status=${sample.hrStatus} ibi=${sample.ibiMs.size}",
                    )
                    onSample(sample)
                }
            }

            override fun onFlushCompleted() {
                Log.d(TAG, "hr sdk flush completed sid=${currentSessionId ?: "none"}")
            }

            override fun onError(error: HealthTracker.TrackerError) {
                val runtimeError = when (error) {
                    HealthTracker.TrackerError.PERMISSION_ERROR -> WatchBackendRuntimeError(
                        code = "sensor_permission_error",
                        message = "Samsung Health Sensor 권한이 부족합니다. 워치 앱에서 권한을 다시 확인해 주세요.",
                        recoverable = true,
                    )

                    HealthTracker.TrackerError.SDK_POLICY_ERROR -> WatchBackendRuntimeError(
                        code = "sdk_policy_error",
                        message = "Samsung Health Sensor SDK policy 오류입니다. 개발자 모드/패키지명/서명/Samsung 등록 상태를 확인해 주세요.",
                        recoverable = true,
                    )

                    else -> WatchBackendRuntimeError(
                        code = "sensor_runtime_error",
                        message = "Samsung Health Sensor tracker 오류: ${error.name}",
                        recoverable = true,
                    )
                }
                reportRuntimeError(runtimeError)
            }
        }

    private fun startFlushLoop(flushPolicy: WatchFlushPolicy) {
        flushJob?.cancel()
        val intervalSec = flushPolicy.activeFlushIntervalSec()
        flushJob = backendScope.launch {
            // 모바일은 Pi 위험도 추천 flush 값을 suspectSec에 실어 보내므로 워치는 이 값을 현재 활성 주기로 사용합니다.
            while (isActive) {
                delay(intervalSec * 1_000L)
                val tracker = heartRateTracker ?: return@launch
                val flushed = runCatching { tracker.flush() }.getOrElse { throwable ->
                    reportRuntimeError(
                        WatchBackendRuntimeError(
                            code = "sensor_flush_failed",
                            message = "Samsung Health Sensor flush 실패: ${throwable.readableMessage()}",
                            recoverable = true,
                        ),
                    )
                    false
                }
                Log.d(TAG, "hr sdk flush requested sid=${currentSessionId ?: "none"} intervalSec=$intervalSec result=$flushed")
            }
        }
    }

    private fun reportRuntimeError(error: WatchBackendRuntimeError) {
        if (stopping || currentSessionId == null) return
        Log.d(TAG, "hr sdk runtime error code=${error.code}, message=${error.message}")
        runtimeErrorCallback?.invoke(error)
    }

    private fun DataPoint.toHeartRateReading(): SamsungHeartRateReading =
        SamsungHeartRateReading(
            sensorTimestampMs = getTimestamp(),
            bpm = valueOrNull(ValueKey.HeartRateSet.HEART_RATE) ?: 0,
            hrStatus = valueOrNull(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0,
            ibiMs = valueOrNull(ValueKey.HeartRateSet.IBI_LIST).orEmpty(),
            ibiStatus = valueOrNull(ValueKey.HeartRateSet.IBI_STATUS_LIST).orEmpty(),
        )

    private fun <T> DataPoint.valueOrNull(key: ValueKey<T>): T? =
        runCatching { getValue(key) }.getOrNull()

    private fun CompletableDeferred<WatchBackendStartResult>.completeFailure(message: String) {
        completeIfNeeded(
            WatchBackendStartResult(
                started = false,
                message = message,
                sensorBackend = SENSOR_BACKEND,
            ),
        )
    }

    private fun CompletableDeferred<WatchBackendStartResult>.completeIfNeeded(result: WatchBackendStartResult) {
        if (!isCompleted) {
            complete(result)
        }
    }

    private fun HealthTrackerException.toStartFailureMessage(): String {
        val reason = when (getErrorCode()) {
            HealthTrackerException.PACKAGE_NOT_INSTALLED -> "Health Platform이 워치에 설치되어 있지 않습니다."
            HealthTrackerException.OLD_PLATFORM_VERSION -> "Health Platform 버전이 낮아 업데이트가 필요합니다."
            else -> "errorCode=${getErrorCode()}"
        }
        return "Samsung Health Tracking Service 연결 실패: $reason"
    }

    private fun Throwable.readableMessage(): String = message ?: javaClass.simpleName

    private fun WatchFlushPolicy.activeFlushIntervalSec(): Int =
        suspectSec.takeIf { it > 0 }?.coerceAtLeast(MinFlushIntervalSec)
            ?: normalSec.coerceAtLeast(MinFlushIntervalSec)

    companion object {
        private const val TAG = "SleepCareWatch"
        private const val SENSOR_BACKEND = "samsung-health-sensor-sdk"
        private const val ServiceConnectTimeoutMs = 5_000L
        private const val MinFlushIntervalSec = 2
    }
}
