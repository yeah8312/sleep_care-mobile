package com.sleepcare.mobile.data.source

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PiPairingCodecTest {
    private val validPin = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `valid qr json maps to trusted device`() {
        val payload = PiPairingCodec.parse(
            """
            {
              "proto": "sleepcare-pair-v1",
              "device_id": "deskpi-a1",
              "display_name": "Desk Pi",
              "service": "_sleepcare._tcp",
              "ws": "/ws",
              "tls": 1,
              "spki_sha256": "$validPin",
              "issued_at_ms": 1775578000000,
              "key_id": "k1"
            }
            """.trimIndent()
        )

        val trusted = PiPairingCodec.toTrustedDevice(payload, registeredAtMs = 42L)

        assertEquals("deskpi-a1", trusted.deviceId)
        assertEquals("Desk Pi", trusted.displayName)
        assertEquals("_sleepcare._tcp", trusted.serviceType)
        assertEquals("/ws", trusted.wsPath)
        assertEquals(validPin, trusted.spkiSha256)
        assertEquals(42L, trusted.registeredAtMs)
    }

    @Test
    fun `invalid qr json is rejected with clear validation`() {
        assertThrows(PiPairingException::class.java) {
            PiPairingCodec.parse("{")
        }
        assertThrows(PiPairingException::class.java) {
            PiPairingCodec.parse(validPayload(proto = "other"))
        }
        assertThrows(PiPairingException::class.java) {
            PiPairingCodec.parse(validPayload(tls = 0))
        }
        assertThrows(PiPairingException::class.java) {
            PiPairingCodec.parse(validPayload(spkiSha256 = "not-base64"))
        }
    }

    @Test
    fun `certificate spki sha256 uses public key encoded bytes`() {
        val certificateFile = File("src/main/res/raw/sleepcare_pi_dev_cert.pem")
        val certificate = certificateFile.inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        )

        assertEquals(expected, PiPairingCodec.certificateSpkiSha256(certificate))
        assertNotEquals(validPin, PiPairingCodec.certificateSpkiSha256(certificate))
    }

    private fun validPayload(
        proto: String = "sleepcare-pair-v1",
        tls: Int = 1,
        spkiSha256: String = validPin,
    ): String =
        """
        {
          "proto": "$proto",
          "device_id": "deskpi-a1",
          "service": "_sleepcare._tcp",
          "ws": "/ws",
          "tls": $tls,
          "spki_sha256": "$spkiSha256"
        }
        """.trimIndent()
}
