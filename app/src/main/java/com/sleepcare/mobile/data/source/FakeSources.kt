package com.sleepcare.mobile.data.source

import com.sleepcare.mobile.domain.ConnectionStatus
import com.sleepcare.mobile.domain.ConnectedDeviceState
import com.sleepcare.mobile.domain.DeviceType
import com.sleepcare.mobile.domain.DrowsinessEvent
import com.sleepcare.mobile.domain.PiBleDataSource
import com.sleepcare.mobile.domain.SleepSession
import com.sleepcare.mobile.domain.WatchSleepDataSource
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeWatchSleepDataSource : WatchSleepDataSource {
    override suspend fun readRecentSleepSessions(): List<SleepSession> {
        val now = LocalDateTime.now()
        return listOf(
            SleepSession(
                id = "sleep-1",
                startTime = now.minusDays(1).withHour(23).withMinute(42),
                endTime = now.withHour(6).withMinute(28),
                totalMinutes = 406,
                sleepScore = 84,
                consistencyScore = 78,
                latencyMinutes = 14,
                awakeMinutes = 18,
            ),
            SleepSession(
                id = "sleep-2",
                startTime = now.minusDays(2).withHour(0).withMinute(18),
                endTime = now.minusDays(1).withHour(6).withMinute(31),
                totalMinutes = 373,
                sleepScore = 76,
                consistencyScore = 71,
                latencyMinutes = 20,
                awakeMinutes = 25,
            ),
            SleepSession(
                id = "sleep-3",
                startTime = now.minusDays(3).withHour(23).withMinute(54),
                endTime = now.minusDays(2).withHour(6).withMinute(24),
                totalMinutes = 390,
                sleepScore = 80,
                consistencyScore = 74,
                latencyMinutes = 18,
                awakeMinutes = 22,
            ),
        )
    }
}

class FakePiBleDataSource : PiBleDataSource {
    private val connectionState = MutableStateFlow(
        ConnectedDeviceState(
            deviceType = DeviceType.RaspberryPi,
            deviceName = "Sleep Care Pi Clock",
            status = ConnectionStatus.Disconnected,
            details = "최근 동기화 없음",
        )
    )
    private val eventsState = MutableStateFlow(sampleEvents())

    override fun observeConnectionState(): Flow<ConnectedDeviceState> = connectionState.asStateFlow()

    override fun observeDetectedEvents(): Flow<List<DrowsinessEvent>> = eventsState.asStateFlow()

    override suspend fun startScan() {
        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Scanning,
            details = "근처 책상 디바이스를 찾는 중",
        )
        delay(800)
        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Connected,
            details = "BLE 스텁 연결 완료",
            lastSeenAt = LocalDateTime.now(),
        )
    }

    override suspend fun retry() {
        startScan()
    }

    override suspend fun disconnect() {
        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Disconnected,
            details = "사용자 요청으로 연결 종료",
        )
    }

    private fun sampleEvents(): List<DrowsinessEvent> {
        val now = LocalDateTime.now()
        return listOf(
            DrowsinessEvent(
                id = "drowsy-1",
                timestamp = now.minusHours(18).withMinute(15),
                severity = 3,
                durationMinutes = 8,
                label = "오후 집중 저하",
                deviceId = "pi-desk-01",
            ),
            DrowsinessEvent(
                id = "drowsy-2",
                timestamp = now.minusHours(16).withMinute(45),
                severity = 2,
                durationMinutes = 5,
                label = "짧은 졸음 경고",
                deviceId = "pi-desk-01",
            ),
            DrowsinessEvent(
                id = "drowsy-3",
                timestamp = now.minusHours(2).withMinute(20),
                severity = 4,
                durationMinutes = 11,
                label = "시험 복습 중 고위험",
                deviceId = "pi-desk-01",
            ),
        )
    }
}

