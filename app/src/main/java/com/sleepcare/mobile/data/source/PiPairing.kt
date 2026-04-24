package com.sleepcare.mobile.data.source

import com.sleepcare.mobile.domain.PiPairingPayload
import com.sleepcare.mobile.domain.TrustedPiDevice
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

class PiPairingException(message: String) : IllegalArgumentException(message)

object PiPairingCodec {
    const val PROTO = "sleepcare-pair-v1"
    const val SERVICE_TYPE = "_sleepcare._tcp"

    fun parse(rawPayload: String): PiPairingPayload {
        val root = try {
            JSONObject(rawPayload)
        } catch (_: JSONException) {
            throw PiPairingException("QR payload is not valid JSON.")
        }

        val proto = root.requiredString("proto")
        if (proto != PROTO) throw PiPairingException("Unsupported pairing proto: $proto")

        val service = root.requiredString("service")
        if (service != SERVICE_TYPE) throw PiPairingException("Unsupported service type: $service")

        val tls = root.optInt("tls", 0)
        if (tls != 1) throw PiPairingException("Pairing requires tls=1.")

        val spkiSha256 = root.requiredString("spki_sha256")
        validateSpkiPin(spkiSha256)

        return PiPairingPayload(
            proto = proto,
            deviceId = root.requiredString("device_id"),
            displayName = root.optionalString("display_name"),
            service = service,
            ws = root.requiredString("ws"),
            tls = tls,
            spkiSha256 = spkiSha256,
            issuedAtMs = root.optionalLong("issued_at_ms"),
            keyId = root.optionalString("key_id"),
            pinHint = root.optionalString("pin_hint"),
        )
    }

    fun toTrustedDevice(payload: PiPairingPayload, registeredAtMs: Long = System.currentTimeMillis()): TrustedPiDevice =
        TrustedPiDevice(
            deviceId = payload.deviceId,
            displayName = payload.displayName ?: payload.deviceId,
            serviceType = payload.service,
            wsPath = payload.ws,
            spkiSha256 = payload.spkiSha256,
            registeredAtMs = registeredAtMs,
        )

    fun parseTrustedDevice(raw: String): TrustedPiDevice? = runCatching {
        val root = JSONObject(raw)
        TrustedPiDevice(
            deviceId = root.requiredString("device_id"),
            displayName = root.requiredString("display_name"),
            serviceType = root.requiredString("service_type"),
            wsPath = root.requiredString("ws_path"),
            spkiSha256 = root.requiredString("spki_sha256"),
            registeredAtMs = root.optLong("registered_at_ms", 0L),
        )
    }.getOrNull()

    fun TrustedPiDevice.toJson(): String = JSONObject()
        .put("device_id", deviceId)
        .put("display_name", displayName)
        .put("service_type", serviceType)
        .put("ws_path", wsPath)
        .put("spki_sha256", spkiSha256)
        .put("registered_at_ms", registeredAtMs)
        .toString()

    fun certificateSpkiSha256(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        )

    fun validateSpkiPin(spkiSha256: String) {
        val decoded = try {
            Base64.getDecoder().decode(spkiSha256)
        } catch (_: IllegalArgumentException) {
            throw PiPairingException("spki_sha256 must be Base64-encoded SHA-256 bytes.")
        }
        if (decoded.size != 32) {
            throw PiPairingException("spki_sha256 must decode to 32 bytes.")
        }
    }

    fun shortPin(spkiSha256: String): String =
        spkiSha256.take(10) + "..." + spkiSha256.takeLast(6)
}

private fun JSONObject.requiredString(key: String): String {
    val value = optString(key).trim()
    if (value.isBlank()) throw PiPairingException("Missing required field: $key")
    return value
}

private fun JSONObject.optionalString(key: String): String? =
    optString(key).trim().takeIf { it.isNotBlank() }

private fun JSONObject.optionalLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
