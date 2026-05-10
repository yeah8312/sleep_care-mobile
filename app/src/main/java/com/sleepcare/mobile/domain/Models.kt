package com.sleepcare.mobile.domain

import com.sleepcare.watch.contracts.WatchCursor as SharedWatchCursor
import com.sleepcare.watch.contracts.WatchFlushPolicy as SharedWatchFlushPolicy
import com.sleepcare.watch.contracts.WatchHeartRateBatch as SharedWatchHeartRateBatch
import com.sleepcare.watch.contracts.WatchHeartRateSample as SharedWatchHeartRateSample
import com.sleepcare.watch.contracts.WatchSessionClosed as SharedWatchSessionClosed
import com.sleepcare.watch.contracts.WatchSessionConfig as SharedWatchSessionConfig
import com.sleepcare.watch.contracts.WatchSessionError as SharedWatchSessionError
import com.sleepcare.watch.contracts.WatchSessionEvent as SharedWatchSessionEvent
import com.sleepcare.watch.contracts.WatchSessionReady as SharedWatchSessionReady
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// 앱 전체에서 공유하는 순수 도메인 모델 모음입니다.
// UI, 저장소, 네트워크 계층이 같은 개념을 같은 이름으로 다루도록 이 파일에 모아 둡니다.

// 연결 화면에서 보여줄 실제 기기 종류입니다.
enum class DeviceType {
    RaspberryPi,
    Smartwatch,
}

// 기기 검색/연결 흐름의 공통 상태입니다.
enum class ConnectionStatus {
    Disconnected,
    Scanning,
    Connected,
    Failed,
}

// 공부 세션이 워치 준비, Pi 연결, 실행, 알림, 종료 중 어디에 있는지 나타냅니다.
enum class StudySessionPhase {
    Idle,
    ArmingWatch,
    DiscoveringPi,
    ConnectingPi,
    OpeningSession,
    Running,
    Alerting,
    Stopping,
    Error,
}

// 공부 세션을 어떤 센서 조합으로 열지 나타냅니다. EyeOnly는 워치가 없어도 Pi 카메라만으로 운영할 수 있는 모드입니다.
enum class StudySessionMode {
    WatchAndEye,
    EyeOnly,
}

// Health Connect에서 가져오거나 로컬 DB에 저장하는 하루 수면 세션 요약입니다.
data class SleepSession(
    val id: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val totalMinutes: Int,
    val sleepScore: Int,
    val consistencyScore: Int,
    val latencyMinutes: Int,
    val awakeMinutes: Int,
    val source: String = "Unavailable",
)

// Raspberry Pi가 감지한 졸음 이벤트를 앱 분석 화면에서 쓰기 좋은 형태로 보관합니다.
data class DrowsinessEvent(
    val id: String,
    val timestamp: LocalDateTime,
    val severity: Int,
    val durationMinutes: Int,
    val label: String,
    val deviceId: String,
    val sessionId: String? = null,
)

// 사용자가 정하는 공부 가능 시간대와 휴식 선호 설정입니다.
data class StudyPlan(
    val id: Int = DEFAULT_ID,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val focusHours: Int,
    val days: Set<DayOfWeek>,
    val breakPreferenceMinutes: Int,
    val autoBreakEnabled: Boolean,
) {
    companion object {
        const val DEFAULT_ID = 1
    }
}

// 시험 일정은 추천 기상 시각과 공부 루틴 계산에 직접 영향을 줍니다.
data class ExamSchedule(
    val id: Long = 0L,
    val name: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val location: String,
    val priority: Int,
    val syncEnabled: Boolean,
)

// 추천 카드 한 줄에 들어가는 제목, 설명, 아이콘 키입니다.
data class RecommendationTip(
    val title: String,
    val body: String,
    val iconKey: String,
)

// 오늘의 권장 취침/기상 시간과 그 이유를 한 번 계산한 결과입니다.
data class RecommendationSnapshot(
    val id: Long = 1L,
    val recommendedBedtime: LocalTime,
    val recommendedWakeTime: LocalTime,
    val targetSleepMinutes: Int,
    val reason: String,
    val routineShiftMinutes: Int,
    val tips: List<RecommendationTip>,
    val generatedAt: LocalDateTime,
)

// 온보딩 완료 여부처럼 앱 시작 흐름에 필요한 최소 상태입니다.
data class OnboardingState(
    val completed: Boolean = false,
)

// 설정 화면의 알림 토글 값을 DataStore에 저장하기 위한 모델입니다.
data class NotificationPreferences(
    val drowsinessAlertsEnabled: Boolean = true,
    val sleepRemindersEnabled: Boolean = true,
)

// 기기 연결 화면에서 Raspberry Pi와 워치 상태를 동일한 카드 형식으로 보여주기 위한 모델입니다.
data class ConnectedDeviceState(
    val deviceType: DeviceType,
    val deviceName: String,
    val status: ConnectionStatus,
    val details: String? = null,
    val lastSeenAt: LocalDateTime? = null,
)

// QR 등록 후 저장하는 Pi 신뢰 정보입니다. 이후 NSD 검색과 TLS pin 검증의 기준이 됩니다.
data class TrustedPiDevice(
    val deviceId: String,
    val displayName: String,
    val serviceType: String,
    val wsPath: String,
    val spkiSha256: String,
    val registeredAtMs: Long,
)

// Pi 화면의 QR 코드에서 읽는 최초 등록 payload입니다.
data class PiPairingPayload(
    val proto: String,
    val deviceId: String,
    val displayName: String?,
    val service: String,
    val ws: String,
    val tls: Int,
    val spkiSha256: String,
    val issuedAtMs: Long? = null,
    val keyId: String? = null,
    val pinHint: String? = null,
)

// NSD로 발견한 Raspberry Pi의 WebSocket 접속 정보입니다.
data class PiServiceEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val wsPath: String,
    val deviceId: String,
)

// 앱과 Raspberry Pi 사이에서 주고받는 공통 메시지 껍데기입니다.
data class PiEnvelope(
    val version: Int,
    val type: String,
    val sessionId: String?,
    val sequence: Long,
    val source: String,
    val sentAtMs: Long,
    val ackRequired: Boolean,
    val body: String = "{}",
)

// hello 응답은 Pi가 어떤 장치/프로토콜로 동작 중인지 확인하는 첫 핸드셰이크입니다.
data class PiHelloAck(
    val deviceId: String,
    val mode: String?,
    val protocol: String?,
)

// Pi가 실시간으로 계산한 졸음 위험도입니다.
data class PiRiskUpdate(
    val sessionId: String,
    val sequence: Long,
    val mode: String,
    val eyeScore: Double?,
    val hrScore: Double?,
    val fusedScore: Double?,
    val state: String,
    val recommendedFlushSec: Int?,
    val receivedAt: LocalDateTime,
)

// Pi가 즉시 깨움 알림을 발생시켰을 때 앱에 전달되는 이벤트입니다.
data class PiAlertFire(
    val sessionId: String,
    val sequence: Long,
    val level: Int,
    val reason: String,
    val durationMs: Long,
    val receivedAt: LocalDateTime,
)

// 세션 종료 후 Pi가 보내는 최종 통계입니다.
data class PiSessionSummary(
    val sessionId: String,
    val sequence: Long,
    val finalState: String,
    val totalAlerts: Int,
    val peakFusedScore: Double?,
    val mode: String?,
    val summaryReason: String?,
    val receivedAt: LocalDateTime,
)

// watch-contracts 모듈의 모델을 모바일 도메인 이름공간에서도 그대로 쓰기 위한 별칭입니다.
enum class PiDebugSessionMode {
    EyeOnly,
    EyeWithSyntheticHr,
}

enum class PiDebugConnectionMode {
    DirectEndpoint,
    RegisteredPiNsd,
}

// 개발자 도구에서 직접 입력하는 Pi WSS endpoint입니다.
// 운영 연결은 QR/NSD로 검증된 TrustedPiDevice만 사용하므로, 이 값은 디버그 카드 내부 진단에만 머뭅니다.
data class PiDebugEndpoint(
    val host: String = "",
    val port: String = "8765",
    val wsPath: String = "/ws",
    val deviceId: String = "deskpi-a1",
    val displayName: String = "SleepCare Pi",
)

// NSD 후보는 자동 등록하지 않고 원본 TXT record를 그대로 보여줍니다.
// Avahi 설정의 proto/tls/device_id/ws 불일치를 개발자가 눈으로 확인하기 위한 진단 모델입니다.
data class PiDebugNsdCandidate(
    val serviceName: String,
    val serviceType: String,
    val host: String?,
    val port: Int?,
    val attributes: Map<String, String>,
    val error: String? = null,
)

// Pi 개발 테스트에서 폰이 실제로 받은 WSS 패킷을 화면에 남기기 위한 메모리 전용 로그입니다.
// 운영 공부 세션의 패킷은 logcat으로만 확인하고, 이 모델은 개발자 카드 안의 직접 테스트 흐름에만 사용합니다.
data class PiDebugPacketLogEntry(
    val receivedAt: LocalDateTime,
    val rawJson: String,
    val parsedSuccessfully: Boolean,
    val type: String?,
    val sessionId: String?,
    val sequence: Long?,
    val ackRequired: Boolean?,
)

// Pi 개발자 모드는 운영 공부 세션과 완전히 분리된 진단 상태입니다.
// 여기의 sessionId는 Room에 저장하지 않고, 기존 Pi wire protocol을 실제로 보낼 때만 사용합니다.
data class PiDebugState(
    val endpoint: PiDebugEndpoint = PiDebugEndpoint(),
    val connectionMode: PiDebugConnectionMode = PiDebugConnectionMode.DirectEndpoint,
    val sessionId: String? = null,
    val commandInFlight: Boolean = false,
    val lastCommandStatus: String = "Pi 개발 테스트 대기 중",
    val serverSpkiSha256: String? = null,
    val generatedPairingJson: String? = null,
    val nsdCandidates: List<PiDebugNsdCandidate> = emptyList(),
    val lastHelloSummary: String? = null,
    val lastRiskSummary: String? = null,
    val lastAlertSummary: String? = null,
    val lastSummary: String? = null,
    val lastError: String? = null,
    val packetLogs: List<PiDebugPacketLogEntry> = emptyList(),
)

typealias WatchFlushPolicy = SharedWatchFlushPolicy
typealias WatchSessionConfig = SharedWatchSessionConfig
typealias WatchHeartRateSample = SharedWatchHeartRateSample
typealias WatchHeartRateBatch = SharedWatchHeartRateBatch
typealias WatchCursor = SharedWatchCursor
typealias WatchSessionEvent = SharedWatchSessionEvent
typealias WatchSessionReady = SharedWatchSessionReady
typealias WatchSessionError = SharedWatchSessionError
typealias WatchSessionClosed = SharedWatchSessionClosed

// 워치 명령을 보낼 때 어떤 노드를 신뢰할지 정하는 정책입니다.
// 운영 공부 세션은 capability가 확인된 SleepCare 워치 앱만 대상으로 삼고,
// 개발자 테스트는 capability 발견 문제를 분리 진단하기 위해 페어링된 Wear OS 노드 fallback을 허용합니다.
enum class WatchCommandTargetPolicy {
    CapabilityOnly,
    DebugAllowPairedFallback,
}

// 개발자 모드의 워치 단독 통신 테스트 상태입니다.
// 운영 공부 세션 상태와 분리해 Pi 연결 없이 Wear OS Data Layer만 점검할 수 있게 합니다.
data class WatchDebugState(
    val sessionId: String? = null,
    val commandInFlight: Boolean = false,
    val lastCommandStatus: String = "워치 통신 테스트 대기 중",
    val watchConnectionDetails: String? = null,
    val lastSessionEvent: String? = null,
    val latestHeartRateSummary: String? = null,
    val latestSampleSeq: Long? = null,
)

// 현재 공부 세션의 모든 실시간 상태를 홈 화면과 저장소가 함께 관찰합니다.
data class StudySessionState(
    val sessionId: String? = null,
    val phase: StudySessionPhase = StudySessionPhase.Idle,
    val mode: StudySessionMode = StudySessionMode.WatchAndEye,
    val startedAt: LocalDateTime? = null,
    val latestRisk: PiRiskUpdate? = null,
    val latestAlert: PiAlertFire? = null,
    val latestSummary: PiSessionSummary? = null,
    val message: String? = null,
)

// 사용자가 선호하는 기상/취침 목표입니다. 추천 엔진의 기본값을 덮어쓸 수 있습니다.
data class UserGoals(
    val targetWakeTime: LocalTime? = null,
    val preferredBedtime: LocalTime? = null,
)

// 설정 화면에서 마지막 동기화 시간을 보여주기 위한 상태입니다.
data class LastSyncState(
    val sleepSyncedAt: LocalDateTime? = null,
    val drowsinessSyncedAt: LocalDateTime? = null,
)

// 추천 엔진이 한 번의 추천을 계산할 때 필요한 입력 데이터 묶음입니다.
data class RecommendationInput(
    val sleepSessions: List<SleepSession>,
    val drowsinessEvents: List<DrowsinessEvent>,
    val studyPlan: StudyPlan?,
    val exams: List<ExamSchedule>,
    val userGoals: UserGoals,
    val generatedAt: LocalDateTime = LocalDateTime.now(),
)

// 홈 화면이 한 번에 그릴 수 있도록 여러 저장소의 데이터를 합친 스냅샷입니다.
data class HomeDashboardSnapshot(
    val latestSleep: SleepSession?,
    val recentDrowsinessCount: Int,
    val recommendation: RecommendationSnapshot?,
    val nextExam: ExamSchedule?,
    val sessionState: StudySessionState = StudySessionState(),
)

// 수면 분석 화면에 필요한 핵심 지표입니다. 데이터가 없을 때도 같은 타입으로 empty state를 표현합니다.
data class SleepAnalysisSnapshot(
    val score: Int,
    val averageMinutes: Int,
    val consistency: Int,
    val latencyMinutes: Int,
    val awakeMinutes: Int,
    val weeklyDurations: List<Int>,
    val isAvailable: Boolean = true,
    val emptyReason: String? = null,
)

// 여러 수면 세션을 하루 단위로 합친 결과입니다.
data class SleepDaySummary(
    val date: LocalDate,
    val primarySession: SleepSession,
    val totalMinutes: Int,
    val extraSleepMinutes: Int,
)

// 졸음 분석 화면에 필요한 카운트, 피크 시간대, 포커스 점수 묶음입니다.
data class DrowsinessAnalysisSnapshot(
    val totalCount: Int,
    val peakWindowLabel: String,
    val focusScore: Int,
    val recentEvents: List<DrowsinessEvent>,
    val liveRisk: PiRiskUpdate? = null,
)
