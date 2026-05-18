package com.sleepcare.watch.runtime

import com.sleepcare.watch.contracts.WatchFlushPolicy
import com.sleepcare.watch.contracts.WatchHeartRateSample
import com.sleepcare.watch.model.WatchScreen
import com.sleepcare.watch.model.WatchUiState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// 워치 UI의 가벼운 전역 상태 저장소입니다.
// 서비스와 Compose 화면이 같은 세션/알림/권한 상태를 공유할 수 있게 합니다.
object WatchSessionStore {
    private val _state = MutableStateFlow(WatchUiState())
    val state: StateFlow<WatchUiState> = _state.asStateFlow()
    private val logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun showConnectionWaiting(
        subtitle: String = "Open app on phone to start session",
        sessionId: String? = null,
    ) {
        _state.update {
            it.copy(
                screen = WatchScreen.ConnectionWaiting,
                sessionId = sessionId,
                connectionTitle = "Ready to Sync",
                connectionSubtitle = subtitle,
                connectionBadge = it.lastIncomingPath?.let { path -> "Last ${path.shortPath()}" } ?: "Waiting...",
                trackingStatus = "Foreground tracking idle",
                lastSyncLabel = "Waiting for phone",
            )
        }
    }

    fun startDemoSession(sessionId: String = "demo-session") {
        _state.update {
            // 데모 시작은 화면 전환 확인용입니다. 실제 세션도 같은 상태 전환을 쓰므로 변경 필드를 최소화합니다.
            it.copy(
                screen = WatchScreen.ActiveSession,
                sessionId = sessionId,
                connectionTitle = "Ready to Sync",
                connectionSubtitle = "Foreground tracking is active",
                connectionBadge = "Sensor",
                trackingStatus = "Foreground tracking active",
                lastSyncLabel = "Last sync: just now",
                latestHeartRate = it.latestHeartRate.takeIf { value -> value > 0 } ?: 72,
                latestIbiMs = it.latestIbiMs.takeIf { value -> value > 0 } ?: 840,
            )
        }
    }

    fun startTrackingSession(sessionId: String) {
        _state.update {
            // 실제 SDK 세션은 첫 샘플이 오기 전까지 가짜 심박값을 보여주지 않습니다.
            // 연결 성공과 센서값 수신을 UI에서 구분할 수 있어 현장 디버깅이 쉬워집니다.
            it.copy(
                screen = WatchScreen.ActiveSession,
                sessionId = sessionId,
                connectionTitle = "Ready to Sync",
                connectionSubtitle = "Foreground tracking is active",
                connectionBadge = "Sensor",
                trackingStatus = "Foreground tracking active",
                lastSyncLabel = "Waiting for first sample",
                latestHeartRate = 0,
                latestIbiMs = 0,
                latestSample = null,
            )
        }
    }

    fun restorePrimaryScreen() {
        _state.update {
            it.copy(
                screen = if (it.sessionId == null) WatchScreen.ConnectionWaiting else WatchScreen.ActiveSession,
            )
        }
    }

    fun markAlerting(
        badge: String = "94% vigilance drop",
        body: String = "Critical fatigue levels detected. Please take a break.",
    ) {
        _state.update {
            it.copy(
                screen = WatchScreen.Alerting,
                alertBadge = badge,
                alertBody = body,
            )
        }
    }

    fun dismissAlert() {
        restorePrimaryScreen()
    }

    fun stopTracking(reason: String = "Foreground tracking idle") {
        _state.update {
            it.copy(
                screen = WatchScreen.ConnectionWaiting,
                sessionId = null,
                connectionTitle = "Ready to Sync",
                connectionSubtitle = "Open app on phone to start session",
                connectionBadge = "Waiting...",
                trackingStatus = reason,
                lastSyncLabel = "Waiting for phone",
            )
        }
    }

    fun showSettings() {
        _state.update {
            it.copy(screen = WatchScreen.WatchSettings)
        }
    }

    fun updatePermissions(granted: Boolean) {
        _state.update {
            // 실제 Android 권한 요청은 MainActivity에서 처리하고, Store는 UI 표시 상태만 보관합니다.
            it.copy(
                permissionsGranted = granted,
                permissionsStatusLabel = if (granted) "Granted" else "Pending",
            )
        }
    }

    fun updateSleepSyncStatus(status: String) {
        _state.update {
            it.copy(sleepSyncStatus = status)
        }
    }

    fun updateFlushPolicy(policy: WatchFlushPolicy) {
        _state.update {
            it.copy(flushPolicy = policy)
        }
    }

    fun updateHeartRate(sample: WatchHeartRateSample) {
        _state.update {
            // 알림/설정 화면을 보고 있을 때는 화면을 강제로 세션 화면으로 돌리지 않습니다.
            val nextScreen = when (it.screen) {
                WatchScreen.Alerting -> WatchScreen.Alerting
                WatchScreen.WatchSettings -> WatchScreen.WatchSettings
                else -> WatchScreen.ActiveSession
            }
            it.copy(
                screen = nextScreen,
                sessionId = sample.sessionId,
                latestSample = sample,
                latestHeartRate = sample.bpm,
                latestIbiMs = sample.ibiMs.firstOrNull() ?: it.latestIbiMs,
                lastSyncLabel = "Last sync: just now",
                trackingStatus = "Foreground tracking active",
            )
        }
    }

    fun recordIncomingMessage(
        path: String,
        sessionId: String?,
        parsed: Boolean,
    ) {
        appendDebugLog(
            message = "RX ${path.shortPath()} sid=${sessionId ?: "none"} parsed=${if (parsed) "ok" else "fail"}",
            lastIncomingPath = path,
        )
    }

    fun recordCommandHandled(
        label: String,
        sessionId: String? = null,
    ) {
        appendDebugLog("OK $label${sessionId?.let { " sid=$it" } ?: ""}")
    }

    fun recordCommandError(
        label: String,
        sessionId: String? = null,
        detail: String? = null,
    ) {
        val suffix = buildString {
            sessionId?.let { append(" sid=$it") }
            detail?.let { append(" · ${it.take(48)}") }
        }
        appendDebugLog("ERR $label$suffix")
    }

    private fun appendDebugLog(
        message: String,
        lastIncomingPath: String? = null,
    ) {
        val line = "${LocalTime.now().format(logTimeFormatter)} $message"
        _state.update { current ->
            val nextLog = (listOf(line) + current.messageLog).take(MaxMessageLogItems)
            val nextIncomingPath = lastIncomingPath ?: current.lastIncomingPath
            current.copy(
                lastIncomingPath = nextIncomingPath,
                messageLog = nextLog,
                connectionSubtitle = if (current.screen == WatchScreen.ConnectionWaiting && nextIncomingPath != null) {
                    "Last message: ${nextIncomingPath.shortPath()}"
                } else {
                    current.connectionSubtitle
                },
                connectionBadge = if (current.screen == WatchScreen.ConnectionWaiting && nextIncomingPath != null) {
                    "RX ${nextIncomingPath.shortPath()}"
                } else {
                    current.connectionBadge
                },
            )
        }
    }

    private fun String.shortPath(): String = substringAfterLast('/').ifBlank { this }

    private const val MaxMessageLogItems = 5
}
