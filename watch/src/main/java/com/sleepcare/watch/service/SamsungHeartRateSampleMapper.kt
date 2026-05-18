package com.sleepcare.watch.service

import com.sleepcare.watch.contracts.WatchHeartRateSample
import java.time.LocalDateTime

// Samsung SDK의 DataPoint를 바로 공통 계약으로 흘리지 않고, 이 내부 모델을 거쳐 의미를 고정합니다.
// SDK 버전별 ValueKey 차이가 생겨도 모바일/워치 공통 JSON 계약은 이 경계 뒤에서 유지됩니다.
data class SamsungHeartRateReading(
    val sensorTimestampMs: Long,
    val bpm: Int,
    val hrStatus: Int,
    val ibiMs: List<Int>,
    val ibiStatus: List<Int>,
)

class SamsungHeartRateSampleMapper(
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    fun toSample(
        sessionId: String,
        reading: SamsungHeartRateReading,
        messageSequence: Long,
        sampleSeq: Long,
    ): WatchHeartRateSample =
        WatchHeartRateSample(
            sessionId = sessionId,
            messageSequence = messageSequence,
            sampleSeq = sampleSeq,
            sensorTimestampMs = reading.sensorTimestampMs,
            bpm = reading.bpm,
            hrStatus = reading.hrStatus,
            ibiMs = reading.ibiMs,
            ibiStatus = reading.ibiStatus,
            deliveryMode = "live",
            receivedAt = now(),
        )
}
