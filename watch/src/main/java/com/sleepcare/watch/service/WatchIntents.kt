package com.sleepcare.watch.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sleepcare.watch.contracts.WatchEnvelope
import com.sleepcare.watch.contracts.WatchFlushPolicy
import com.sleepcare.watch.contracts.WatchProtocolCodec
import com.sleepcare.watch.contracts.WatchSessionConfig
import com.sleepcare.watch.contracts.WatchPaths

// WatchSensorTrackingService를 시작할 때 쓰는 Intent action/extra와 payload 변환 헬퍼입니다.
object WatchSessionIntents {
    const val ACTION_START_SESSION = "com.sleepcare.watch.action.START_SESSION"
    const val ACTION_STOP_SESSION = "com.sleepcare.watch.action.STOP_SESSION"
    const val ACTION_UPDATE_FLUSH_POLICY = "com.sleepcare.watch.action.UPDATE_FLUSH_POLICY"
    const val ACTION_BACKFILL_REQUEST = "com.sleepcare.watch.action.BACKFILL_REQUEST"
    const val ACTION_ALERT = "com.sleepcare.watch.action.ALERT"
    const val ACTION_DISMISS_ALERT = "com.sleepcare.watch.action.DISMISS_ALERT"

    const val EXTRA_RAW_MESSAGE = "extra_raw_message"
    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_LEVEL = "extra_level"
    const val EXTRA_PATTERN = "extra_pattern"
    const val EXTRA_REASON = "extra_reason"
    const val EXTRA_FROM_SAMPLE_SEQ = "extra_from_sample_seq"
    const val EXTRA_FLUSH_NORMAL_SEC = "extra_flush_normal_sec"
    const val EXTRA_FLUSH_SUSPECT_SEC = "extra_flush_suspect_sec"
    const val EXTRA_FLUSH_ALERT_SEC = "extra_flush_alert_sec"
    const val EXTRA_STUDY_MODE = "extra_study_mode"
    const val EXTRA_HR_REQUIRED = "extra_hr_required"
    const val EXTRA_WATCH_VIBRATION_ENABLED = "extra_watch_vibration_enabled"

    fun startSession(context: Context, config: WatchSessionConfig): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            // 같은 명령을 raw JSON 없이도 로컬 UI에서 만들 수 있게 주요 값을 extra로 넣습니다.
            action = ACTION_START_SESSION
            putExtra(EXTRA_SESSION_ID, config.sessionId)
            putExtra(EXTRA_STUDY_MODE, config.studyMode)
            putExtra(EXTRA_FLUSH_NORMAL_SEC, config.flushPolicy.normalSec)
            putExtra(EXTRA_FLUSH_SUSPECT_SEC, config.flushPolicy.suspectSec)
            putExtra(EXTRA_FLUSH_ALERT_SEC, config.flushPolicy.alertSec)
            putExtra(EXTRA_HR_REQUIRED, config.hrRequired)
            putExtra(EXTRA_WATCH_VIBRATION_ENABLED, config.watchVibrationEnabled)
        }

    fun startSessionFromPhone(context: Context, rawMessage: ByteArray): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            // 휴대폰에서 온 Data Layer payload는 원본 byte 배열 그대로 서비스로 넘깁니다.
            action = ACTION_START_SESSION
            putExtra(EXTRA_RAW_MESSAGE, rawMessage)
        }

    fun stopSession(context: Context, sessionId: String? = null, reason: String? = null): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_STOP_SESSION
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            reason?.let { putExtra(EXTRA_REASON, it) }
        }

    fun stopSessionFromPhone(context: Context, rawMessage: ByteArray): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_STOP_SESSION
            putExtra(EXTRA_RAW_MESSAGE, rawMessage)
        }

    fun updateFlushPolicy(
        context: Context,
        sessionId: String,
        flushPolicy: WatchFlushPolicy,
    ): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_UPDATE_FLUSH_POLICY
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_FLUSH_NORMAL_SEC, flushPolicy.normalSec)
            putExtra(EXTRA_FLUSH_SUSPECT_SEC, flushPolicy.suspectSec)
            putExtra(EXTRA_FLUSH_ALERT_SEC, flushPolicy.alertSec)
        }

    fun updateFlushPolicyFromPhone(context: Context, rawMessage: ByteArray): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_UPDATE_FLUSH_POLICY
            putExtra(EXTRA_RAW_MESSAGE, rawMessage)
        }

    fun requestBackfill(context: Context, sessionId: String, fromSampleSeq: Long): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_BACKFILL_REQUEST
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_FROM_SAMPLE_SEQ, fromSampleSeq)
        }

    fun requestBackfillFromPhone(context: Context, rawMessage: ByteArray): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_BACKFILL_REQUEST
            putExtra(EXTRA_RAW_MESSAGE, rawMessage)
        }

    fun triggerAlert(
        context: Context,
        sessionId: String,
        level: Int,
        pattern: String,
        reason: String,
    ): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_ALERT
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_LEVEL, level)
            putExtra(EXTRA_PATTERN, pattern)
            putExtra(EXTRA_REASON, reason)
        }

    fun triggerAlertFromPhone(context: Context, rawMessage: ByteArray): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_ALERT
            putExtra(EXTRA_RAW_MESSAGE, rawMessage)
        }

    fun dismissAlert(context: Context): Intent =
        Intent(context, WatchSensorTrackingService::class.java).apply {
            action = ACTION_DISMISS_ALERT
        }

    fun decodeEnvelope(intent: Intent): WatchEnvelope? =
        intent.getByteArrayExtra(EXTRA_RAW_MESSAGE)?.let(WatchProtocolCodec::decodeEnvelope)

    // 아래 decode/build 함수들은 서비스가 WatchProtocolCodec을 직접 흩어 쓰지 않도록 모아 둔 어댑터입니다.
    fun decodeConfig(intent: Intent): WatchSessionConfig? =
        intent.getByteArrayExtra(EXTRA_RAW_MESSAGE)?.let(WatchProtocolCodec::decodeSessionConfig)

    fun decodeFlushPolicy(intent: Intent): WatchFlushPolicy? =
        intent.getByteArrayExtra(EXTRA_RAW_MESSAGE)?.let(WatchProtocolCodec::decodeFlushPolicy)

    fun decodeBackfillFrom(intent: Intent): Long? =
        intent.getByteArrayExtra(EXTRA_RAW_MESSAGE)?.let(WatchProtocolCodec::decodeBackfillRequest)

    fun decodeSessionId(intent: Intent): String? =
        intent.getStringExtra(EXTRA_SESSION_ID)

    fun startForegroundService(context: Context, intent: Intent) {
        // Android O 이상에서도 동일하게 동작하도록 ContextCompat 경로를 사용합니다.
        ContextCompat.startForegroundService(context, intent)
    }

    fun buildSessionReadyPayload(
        sessionId: String,
        sensorBackend: String = "placeholder",
    ): ByteArray =
        WatchProtocolCodec.encodeSessionReady(
            sessionId = sessionId,
            sensorBackend = sensorBackend,
        )

    fun buildSessionErrorPayload(
        sessionId: String,
        code: String,
        message: String,
        recoverable: Boolean,
    ): ByteArray = WatchProtocolCodec.encodeSessionError(sessionId, code, message, recoverable)

    fun buildSessionClosedPayload(
        sessionId: String,
        reason: String,
        finalSampleSeq: Long? = null,
    ): ByteArray = WatchProtocolCodec.encodeSessionClosed(sessionId, reason, finalSampleSeq)

    fun buildLiveSamplePayload(sample: com.sleepcare.watch.contracts.WatchHeartRateSample): ByteArray =
        WatchProtocolCodec.encodeHeartRateSample(sample)

    fun buildBatchPayload(batch: com.sleepcare.watch.contracts.WatchHeartRateBatch): ByteArray =
        WatchProtocolCodec.encodeHeartRateBatch(batch)

    fun buildVibrationPayload(
        sessionId: String,
        level: Int,
        pattern: String,
    ): ByteArray = WatchProtocolCodec.encodeVibrationAlert(sessionId, level, pattern)

    fun buildAckPayload(cursor: com.sleepcare.watch.contracts.WatchCursor): ByteArray =
        WatchProtocolCodec.encodeHeartRateAck(cursor)

    fun buildStartPayload(config: WatchSessionConfig): ByteArray =
        WatchProtocolCodec.encodeSessionStart(config)

    fun buildStopPayload(sessionId: String): ByteArray =
        WatchProtocolCodec.encodeSessionStop(sessionId)

    fun buildFlushPayload(sessionId: String, flushPolicy: WatchFlushPolicy): ByteArray =
        WatchProtocolCodec.encodeFlushPolicyUpdate(sessionId, flushPolicy)

    fun buildBackfillPayload(sessionId: String, fromSampleSeq: Long): ByteArray =
        WatchProtocolCodec.encodeBackfillRequest(sessionId, fromSampleSeq)
}
