package com.sleepcare.watch.service

import android.content.Context
import com.sleepcare.watch.contracts.WatchFlushPolicy
import com.sleepcare.watch.contracts.WatchHeartRateSample
import com.sleepcare.watch.contracts.WatchSessionConfig

// 센서 백엔드 시작 결과입니다. 실패 시 휴대폰에 session.error로 전달됩니다.
data class WatchBackendStartResult(
    val started: Boolean,
    val message: String,
    val sensorBackend: String = "unknown",
)

// 센서 SDK가 시작 이후 끊기거나 권한/정책 오류를 내는 경우 서비스가 모바일에 session.error를 보낼 수 있게 합니다.
data class WatchBackendRuntimeError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)

// 실제 Samsung Health Sensor SDK 또는 테스트용 구현이 맞춰야 하는 인터페이스입니다.
interface WatchSensorBackend {
    suspend fun start(
        config: WatchSessionConfig,
        onSample: (WatchHeartRateSample) -> Unit,
        onRuntimeError: (WatchBackendRuntimeError) -> Unit,
    ): WatchBackendStartResult

    suspend fun updateFlushPolicy(flushPolicy: WatchFlushPolicy)
    suspend fun stop()
}

// 테스트에서 센서 없이 서비스 흐름만 확인해야 할 때를 위한 백엔드입니다. 운영 factory에서는 실제 SDK 구현을 씁니다.
class NoOpWatchSensorBackend : WatchSensorBackend {
    override suspend fun start(
        config: WatchSessionConfig,
        onSample: (WatchHeartRateSample) -> Unit,
        onRuntimeError: (WatchBackendRuntimeError) -> Unit,
    ): WatchBackendStartResult = WatchBackendStartResult(
        started = true,
        message = "Samsung Health Sensor SDK not attached yet; no-op backend is active.",
        sensorBackend = "noop",
    )

    override suspend fun updateFlushPolicy(flushPolicy: WatchFlushPolicy) = Unit

    override suspend fun stop() = Unit
}

// 센서 구현 교체 지점을 한 곳으로 고정해 서비스와 테스트가 SDK 세부 타입을 직접 알지 않게 합니다.
object WatchSensorBackendFactory {
    fun create(context: Context): WatchSensorBackend = SamsungHealthSensorBackend(context.applicationContext)
}
