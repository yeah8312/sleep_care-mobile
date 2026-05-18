package com.sleepcare.watch.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import com.sleepcare.watch.contracts.WatchCursor
import com.sleepcare.watch.contracts.WatchFlushPolicy
import com.sleepcare.watch.contracts.WatchHeartRateBatch
import com.sleepcare.watch.contracts.WatchHeartRateSample
import com.sleepcare.watch.contracts.WatchPaths
import com.sleepcare.watch.contracts.WatchProtocolCodec
import com.sleepcare.watch.contracts.WatchSessionConfig
import com.sleepcare.watch.runtime.WatchSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 워치에서 전면 서비스로 센서 추적을 수행하는 핵심 런타임입니다.
// 휴대폰 명령 Intent를 받아 세션을 시작/종료하고, 심박 샘플을 휴대폰으로 보냅니다.
class WatchSensorTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val buffer = WatchSampleBuffer()
    private val backend by lazy { WatchSensorBackendFactory.create(this) }
    private val messenger by lazy { WatchPhoneMessengerFactory.create(this) }

    private var currentConfig: WatchSessionConfig? = null
    private var trackingStarted = false

    override fun onCreate() {
        super.onCreate()
        WatchNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // WearableListenerService나 워치 UI에서 보낸 action을 세션 동작으로 분기합니다.
        Log.d(TAG, "service onStartCommand action=${intent?.action ?: "null"}, sid=${intent?.let { resolveSessionId(it) } ?: "none"}")
        when (intent?.action) {
            WatchSessionIntents.ACTION_START_SESSION -> handleStart(intent)
            WatchSessionIntents.ACTION_STOP_SESSION -> handleStop(intent)
            WatchSessionIntents.ACTION_UPDATE_FLUSH_POLICY -> handleFlushPolicy(intent)
            WatchSessionIntents.ACTION_BACKFILL_REQUEST -> handleBackfill(intent)
            WatchSessionIntents.ACTION_ALERT -> handleAlert(intent)
            WatchSessionIntents.ACTION_DISMISS_ALERT -> handleDismissAlert()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (trackingStarted || currentConfig != null) {
            serviceScope.launch {
                stopSessionInternal(reason = "service_destroyed", notifyPhone = false)
            }
        }
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val config = resolveConfig(intent)
        if (config == null) {
            val fallbackSessionId = intent.getStringExtra(WatchSessionIntents.EXTRA_SESSION_ID) ?: "unknown-session"
            WatchSessionStore.recordCommandError("start invalid_config", fallbackSessionId, "payload parse failed")
            Log.d(TAG, "start invalid_config sid=$fallbackSessionId")
            serviceScope.launch {
                sendSessionError(
                    sessionId = fallbackSessionId,
                    code = "invalid_config",
                    message = "Unable to parse session start payload.",
                    recoverable = false,
                )
            }
            stopSelf()
            return
        }

        val missingPermissions = requiredRuntimePermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            WatchSessionStore.recordCommandError(
                label = "start permission_required",
                sessionId = config.sessionId,
                detail = missingPermissions.toReadableNames(),
            )
            Log.d(TAG, "start permission_required sid=${config.sessionId}, missing=${missingPermissions.toReadableNames()}")
            serviceScope.launch {
                // 워치 앱을 먼저 열어 권한을 승인해야 하는 상황을 모바일에 명확히 알려 줍니다.
                sendSessionError(
                    sessionId = config.sessionId,
                    code = "permission_required",
                    message = "Watch permissions are required: ${missingPermissions.toReadableNames()}",
                    recoverable = true,
                )
            }
            WatchSessionStore.updatePermissions(false)
            stopSelf()
            return
        }

        // 센서 수집은 백그라운드 제한을 피하기 위해 foreground service로 유지합니다.
        WatchSessionStore.recordCommandHandled("start config parsed", config.sessionId)
        Log.d(TAG, "start config parsed sid=${config.sessionId}, mode=${config.studyMode}")
        val foregroundStarted = runCatching {
            startForeground(
                WatchNotification.NOTIFICATION_ID,
                WatchNotification.build(
                    this,
                    title = "SleepCare tracking",
                    content = "Preparing watch sensor session for ${config.sessionId}",
                ),
            )
        }
        foregroundStarted.onFailure { throwable ->
            val detail = throwable.message ?: throwable::class.java.simpleName
            WatchSessionStore.recordCommandError("foreground service failed", config.sessionId, detail)
            Log.d(TAG, "foreground service failed sid=${config.sessionId}", throwable)
            serviceScope.launch {
                // health foreground service 조건이 맞지 않으면 ready 타임아웃 대신 원인을 회신합니다.
                sendSessionError(
                    sessionId = config.sessionId,
                    code = "foreground_service_failed",
                    message = detail,
                    recoverable = true,
                )
            }
            WatchSessionStore.stopTracking("Foreground service failed")
            stopSelf()
            return
        }

        serviceScope.launch {
            WatchSessionStore.recordCommandHandled("foreground started", config.sessionId)
            startSessionInternal(config)
        }
    }

    private fun handleStop(intent: Intent) {
        val sessionId = resolveSessionId(intent) ?: currentConfig?.sessionId
        WatchSessionStore.recordCommandHandled("stop command", sessionId)
        Log.d(TAG, "stop command sid=${sessionId ?: "none"}")
        serviceScope.launch {
            stopSessionInternal(
                reason = intent.getStringExtra(WatchSessionIntents.EXTRA_REASON) ?: "phone_stop",
                notifyPhone = true,
            )
        }
    }

    private fun handleFlushPolicy(intent: Intent) {
        val sessionId = resolveSessionId(intent) ?: currentConfig?.sessionId
        if (sessionId == null) {
            WatchSessionStore.recordCommandError("flush missing session", detail = "sid not found")
            Log.d(TAG, "flush missing session")
            return
        }
        val policy = resolveFlushPolicy(intent)
        if (policy == null) {
            WatchSessionStore.recordCommandError("flush invalid payload", sessionId, "policy parse failed")
            Log.d(TAG, "flush invalid payload sid=$sessionId")
            return
        }
        currentConfig = currentConfig?.copy(sessionId = sessionId, flushPolicy = policy) ?: WatchSessionConfig(
            sessionId = sessionId,
            flushPolicy = policy,
        )
        WatchSessionStore.updateFlushPolicy(policy)
        WatchSessionStore.recordCommandHandled(
            label = "flush ${policy.normalSec}/${policy.suspectSec}/${policy.alertSec}",
            sessionId = sessionId,
        )
        Log.d(TAG, "flush policy updated sid=$sessionId, policy=$policy")
        serviceScope.launch {
            backend.updateFlushPolicy(policy)
        }
    }

    private fun handleBackfill(intent: Intent) {
        val sessionId = resolveSessionId(intent) ?: currentConfig?.sessionId
        if (sessionId == null) {
            WatchSessionStore.recordCommandError("backfill missing session", detail = "sid not found")
            Log.d(TAG, "backfill missing session")
            return
        }
        val fromSampleSeq = resolveBackfillFrom(intent)
        if (fromSampleSeq == null) {
            WatchSessionStore.recordCommandError("backfill invalid payload", sessionId, "fromSampleSeq missing")
            Log.d(TAG, "backfill invalid payload sid=$sessionId")
            return
        }
        WatchSessionStore.recordCommandHandled("backfill from=$fromSampleSeq", sessionId)
        Log.d(TAG, "backfill requested sid=$sessionId, from=$fromSampleSeq")
        serviceScope.launch {
            // 휴대폰이 누락을 감지한 sampleSeq부터 버퍼에 남은 샘플을 배치로 다시 보냅니다.
            val samples = buffer.fromSequence(sessionId, fromSampleSeq)
            if (samples.isEmpty()) {
                WatchSessionStore.recordCommandHandled("backfill empty", sessionId)
                Log.d(TAG, "backfill empty sid=$sessionId, from=$fromSampleSeq")
                return@launch
            }
            val batch = WatchHeartRateBatch(
                sessionId = sessionId,
                messageSequence = samples.maxOf { it.messageSequence },
                deliveryMode = "backfill",
                samples = samples,
            )
            val sent = messenger.send(WatchPaths.HrBatch, WatchSessionIntents.buildBatchPayload(batch))
            if (sent) {
                WatchSessionStore.recordCommandHandled("backfill sent ${samples.size}", sessionId)
            } else {
                WatchSessionStore.recordCommandError("backfill send failed", sessionId)
            }
            Log.d(TAG, "backfill send result sid=$sessionId, count=${samples.size}, sent=$sent")
        }
    }

    private fun handleAlert(intent: Intent) {
        val sessionId = resolveSessionId(intent) ?: currentConfig?.sessionId
        if (sessionId == null) {
            WatchSessionStore.recordCommandError("vibrate missing session", detail = "sid not found")
            Log.d(TAG, "vibrate missing session")
            return
        }
        val level = intent.getIntExtra(WatchSessionIntents.EXTRA_LEVEL, 3)
        val pattern = intent.getStringExtra(WatchSessionIntents.EXTRA_PATTERN) ?: "pulse"
        val reason = intent.getStringExtra(WatchSessionIntents.EXTRA_REASON) ?: "Drowsiness risk"
        WatchSessionStore.recordCommandHandled("vibrate level=$level", sessionId)
        Log.d(TAG, "vibrate command sid=$sessionId, level=$level, pattern=$pattern")
        WatchSessionStore.markAlerting(
            badge = "${level * 30}% vigilance drop",
            body = reason,
        )
        vibrate(level, pattern)
    }

    private fun handleDismissAlert() {
        WatchSessionStore.recordCommandHandled("dismiss alert")
        Log.d(TAG, "dismiss alert")
        WatchSessionStore.dismissAlert()
    }

    private suspend fun startSessionInternal(config: WatchSessionConfig) {
        currentConfig = config
        trackingStarted = true
        WatchSessionStore.recordCommandHandled("backend start requested", config.sessionId)
        Log.d(TAG, "backend start requested sid=${config.sessionId}")
        WatchSessionStore.startTrackingSession(config.sessionId)
        WatchSessionStore.updateFlushPolicy(config.flushPolicy)

        val startResult = backend.start(
            config = config,
            onSample = { sample ->
                serviceScope.launch {
                    onSample(sample)
                }
            },
            onRuntimeError = { error ->
                serviceScope.launch {
                    handleBackendRuntimeError(config.sessionId, error)
                }
            },
        )

        // 센서 백엔드가 시작되지 못하면 즉시 휴대폰에 recoverable 오류를 알립니다.
        if (!startResult.started) {
            WatchSessionStore.recordCommandError("backend_start_failed", config.sessionId, startResult.message)
            Log.d(TAG, "backend start failed sid=${config.sessionId}, message=${startResult.message}")
            sendSessionError(
                sessionId = config.sessionId,
                code = "backend_start_failed",
                message = startResult.message,
                recoverable = true,
            )
            currentConfig = null
            trackingStarted = false
            WatchSessionStore.stopTracking(startResult.message)
            stopSelf()
            return
        }

        WatchSessionStore.recordCommandHandled("backend started", config.sessionId)
        val readySent = messenger.send(
            WatchPaths.SessionReady,
            WatchSessionIntents.buildSessionReadyPayload(
                sessionId = config.sessionId,
                sensorBackend = startResult.sensorBackend,
            ),
        )
        if (readySent) {
            WatchSessionStore.recordCommandHandled("session.ready sent", config.sessionId)
        } else {
            WatchSessionStore.recordCommandError("session.ready send failed", config.sessionId)
        }
        Log.d(TAG, "session.ready send result sid=${config.sessionId}, sent=$readySent")
    }

    private suspend fun handleBackendRuntimeError(
        sessionId: String,
        error: WatchBackendRuntimeError,
    ) {
        if (currentConfig?.sessionId != sessionId || !trackingStarted) return
        WatchSessionStore.recordCommandError(error.code, sessionId, error.message)
        Log.d(TAG, "backend runtime error sid=$sessionId, code=${error.code}, message=${error.message}")
        // SDK가 시작 이후 끊긴 경우에도 모바일이 타임아웃으로 오해하지 않도록 기존 session.error 계약으로 회신합니다.
        sendSessionError(
            sessionId = sessionId,
            code = error.code,
            message = error.message,
            recoverable = error.recoverable,
        )
        stopSessionInternal(reason = error.message, notifyPhone = false)
    }

    private suspend fun stopSessionInternal(
        reason: String,
        notifyPhone: Boolean,
    ) {
        val sessionId = currentConfig?.sessionId
        if (!trackingStarted && sessionId == null) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        backend.stop()
        if (sessionId != null) {
            if (notifyPhone) {
                // 정상 종료일 때는 마지막 sampleSeq를 함께 보내 모바일 커서 정리를 돕습니다.
                val closedSent = messenger.send(
                    WatchPaths.SessionClosed,
                    WatchSessionIntents.buildSessionClosedPayload(
                        sessionId = sessionId,
                        reason = reason,
                        finalSampleSeq = buffer.snapshot(sessionId).maxOfOrNull { it.sampleSeq },
                    ),
                )
                if (closedSent) {
                    WatchSessionStore.recordCommandHandled("session.closed sent", sessionId)
                } else {
                    WatchSessionStore.recordCommandError("session.closed send failed", sessionId)
                }
                Log.d(TAG, "session.closed send result sid=$sessionId, sent=$closedSent")
            }
            buffer.clear(sessionId)
        }
        currentConfig = null
        trackingStarted = false
        WatchSessionStore.stopTracking(reason)
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun onSample(sample: WatchHeartRateSample) {
        buffer.append(sample)
        WatchSessionStore.updateHeartRate(sample)
        // MVP에서는 실시간 live 샘플을 우선 전송하고, 누락 시 backfill로 보완합니다.
        val sent = messenger.send(WatchPaths.HrLive, WatchSessionIntents.buildLiveSamplePayload(sample))
        Log.d(TAG, "hr live send result sid=${sample.sessionId}, seq=${sample.sampleSeq}, sent=$sent")
    }

    private suspend fun sendSessionError(
        sessionId: String,
        code: String,
        message: String,
        recoverable: Boolean,
    ) {
        val sent = messenger.send(
            WatchPaths.SessionError,
            WatchSessionIntents.buildSessionErrorPayload(sessionId, code, message, recoverable),
        )
        if (sent) {
            WatchSessionStore.recordCommandHandled("session.error sent $code", sessionId)
        } else {
            WatchSessionStore.recordCommandError("session.error send failed $code", sessionId)
        }
        Log.d(TAG, "session.error send result sid=$sessionId, code=$code, sent=$sent")
    }

    private fun resolveConfig(intent: Intent): WatchSessionConfig? {
        // 휴대폰에서 온 raw JSON payload를 우선 해석하고, 없으면 로컬 extra 값으로 구성합니다.
        WatchSessionIntents.decodeConfig(intent)?.let { return it }
        val sessionId = intent.getStringExtra(WatchSessionIntents.EXTRA_SESSION_ID) ?: return null
        val flushPolicy = resolveFlushPolicy(intent) ?: WatchFlushPolicy()
        return WatchSessionConfig(
            sessionId = sessionId,
            studyMode = intent.getStringExtra(WatchSessionIntents.EXTRA_STUDY_MODE) ?: "focus",
            flushPolicy = flushPolicy,
            hrRequired = intent.getBooleanExtra(WatchSessionIntents.EXTRA_HR_REQUIRED, true),
            watchVibrationEnabled = intent.getBooleanExtra(WatchSessionIntents.EXTRA_WATCH_VIBRATION_ENABLED, true),
        )
    }

    private fun resolveSessionId(intent: Intent): String? =
        WatchSessionIntents.decodeEnvelope(intent)?.sessionId
            ?: intent.getStringExtra(WatchSessionIntents.EXTRA_SESSION_ID)

    private fun resolveFlushPolicy(intent: Intent): WatchFlushPolicy? {
        WatchSessionIntents.decodeFlushPolicy(intent)?.let { return it }
        if (!intent.hasExtra(WatchSessionIntents.EXTRA_FLUSH_NORMAL_SEC)) return null
        return WatchFlushPolicy(
            normalSec = intent.getIntExtra(WatchSessionIntents.EXTRA_FLUSH_NORMAL_SEC, 15),
            suspectSec = intent.getIntExtra(WatchSessionIntents.EXTRA_FLUSH_SUSPECT_SEC, 5),
            alertSec = intent.getIntExtra(WatchSessionIntents.EXTRA_FLUSH_ALERT_SEC, 2),
        )
    }

    private fun resolveBackfillFrom(intent: Intent): Long? =
        WatchSessionIntents.decodeBackfillFrom(intent)
            ?: if (intent.hasExtra(WatchSessionIntents.EXTRA_FROM_SAMPLE_SEQ)) {
                intent.getLongExtra(WatchSessionIntents.EXTRA_FROM_SAMPLE_SEQ, -1L).takeIf { it >= 0L }
            } else {
                null
            }

    private fun vibrate(level: Int, pattern: String) {
        val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        // Pi 위험도 level과 pattern을 단순 진동 길이로 매핑합니다.
        val durationMs = when (pattern) {
            "pulse" -> 500L
            "urgent" -> 900L
            else -> 350L + (level.coerceIn(1, 5) * 100L)
        }
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                durationMs,
                VibrationEffect.DEFAULT_AMPLITUDE,
            )
        )
    }

    private fun stopForegroundCompat() {
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private val requiredRuntimePermissions = listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )

        // 아래 헬퍼들은 외부 클래스가 action/extra를 직접 알지 않고 서비스를 시작하게 해 줍니다.
        fun start(context: Context, config: WatchSessionConfig) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.startSession(context, config))
        }

        fun startFromPhone(context: Context, rawMessage: ByteArray) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.startSessionFromPhone(context, rawMessage))
        }

        fun stop(context: Context, sessionId: String? = null, reason: String? = null) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.stopSession(context, sessionId, reason))
        }

        fun stopFromPhone(context: Context, rawMessage: ByteArray) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.stopSessionFromPhone(context, rawMessage))
        }

        fun updateFlushPolicy(context: Context, sessionId: String, flushPolicy: WatchFlushPolicy) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.updateFlushPolicy(context, sessionId, flushPolicy))
        }

        fun updateFlushPolicyFromPhone(context: Context, rawMessage: ByteArray) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.updateFlushPolicyFromPhone(context, rawMessage))
        }

        fun requestBackfill(context: Context, sessionId: String, fromSampleSeq: Long) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.requestBackfill(context, sessionId, fromSampleSeq))
        }

        fun requestBackfillFromPhone(context: Context, rawMessage: ByteArray) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.requestBackfillFromPhone(context, rawMessage))
        }

        fun triggerAlert(context: Context, sessionId: String, level: Int, pattern: String, reason: String) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.triggerAlert(context, sessionId, level, pattern, reason))
        }

        fun triggerAlertFromPhone(context: Context, rawMessage: ByteArray) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.triggerAlertFromPhone(context, rawMessage))
        }

        fun dismissAlert(context: Context) {
            WatchSessionIntents.startForegroundService(context, WatchSessionIntents.dismissAlert(context))
        }

        private const val TAG = "SleepCareWatch"
    }
}

private fun List<String>.toReadableNames(): String = joinToString { permission ->
    when (permission) {
        Manifest.permission.BODY_SENSORS -> "BODY_SENSORS"
        Manifest.permission.ACTIVITY_RECOGNITION -> "ACTIVITY_RECOGNITION"
        else -> permission.substringAfterLast('.')
    }
}
