package com.sleepcare.mobile.data.repository

import com.sleepcare.mobile.data.source.PiDebugClient
import com.sleepcare.mobile.data.source.PiPairingCodec
import com.sleepcare.mobile.domain.PiAlertFire
import com.sleepcare.mobile.domain.PiDebugEndpoint
import com.sleepcare.mobile.domain.PiDebugNsdCandidate
import com.sleepcare.mobile.domain.PiDebugPacketLogEntry
import com.sleepcare.mobile.domain.PiDebugSessionMode
import com.sleepcare.mobile.domain.PiDebugState
import com.sleepcare.mobile.domain.PiHelloAck
import com.sleepcare.mobile.domain.PiRiskUpdate
import com.sleepcare.mobile.domain.PiSessionSummary
import com.sleepcare.mobile.domain.TrustedPiDevice
import com.sleepcare.mobile.domain.WatchHeartRateSample
import java.time.LocalDateTime
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiDebugRepositoryTest {
    @Test
    fun `generated pairing json passes existing pairing codec`() {
        val json = PiDebugPayloadFactory.buildPairingJson(
            endpoint = PiDebugEndpoint(
                host = "192.168.0.45",
                port = "8765",
                wsPath = "ws",
                deviceId = "deskpi-a1",
                displayName = "Desk Pi",
            ),
            spkiSha256 = validSpki(),
            issuedAtMs = 123L,
        )

        val payload = PiPairingCodec.parse(json)

        assertEquals(PiPairingCodec.PROTO, payload.proto)
        assertEquals("deskpi-a1", payload.deviceId)
        assertEquals("Desk Pi", payload.displayName)
        assertEquals("/ws", payload.ws)
        assertEquals(1, payload.tls)
        assertEquals(123L, payload.issuedAtMs)
    }

    @Test
    fun `direct endpoint hello does not auto save trusted pi`() = runTest {
        val fakeClient = FakePiDebugClient()
        val fakeStore = FakePiDebugTrustedPiStore()
        val repository = PiDebugRepositoryImpl(fakeClient, fakeStore)

        repository.updateEndpoint(PiDebugEndpoint(host = "192.168.0.45"))
        repository.readServerSpki()
        repository.sendHello()

        assertEquals(1, fakeClient.connectDirectCalls)
        assertEquals(0, fakeStore.saveCalls)
        assertNotNull(repository.currentState().lastHelloSummary)
    }

    @Test
    fun `nsd discovery keeps raw txt records and does not register automatically`() = runTest {
        val fakeClient = FakePiDebugClient().apply {
            nsdCandidates = listOf(
                PiDebugNsdCandidate(
                    serviceName = "SleepCare Pi",
                    serviceType = PiPairingCodec.SERVICE_TYPE,
                    host = "192.168.0.45",
                    port = 8765,
                    attributes = mapOf("proto" to "v0", "tls" to "0", "device_id" to "wrong", "ws" to "/wrong"),
                )
            )
        }
        val fakeStore = FakePiDebugTrustedPiStore()
        val repository = PiDebugRepositoryImpl(fakeClient, fakeStore)

        repository.discoverNsdCandidates()

        val candidate = repository.currentState().nsdCandidates.single()
        assertEquals("v0", candidate.attributes["proto"])
        assertEquals("0", candidate.attributes["tls"])
        assertEquals(0, fakeStore.saveCalls)
    }

    @Test
    fun `json registration saves only after generated json passes codec`() = runTest {
        val fakeClient = FakePiDebugClient()
        val fakeStore = FakePiDebugTrustedPiStore()
        val repository = PiDebugRepositoryImpl(fakeClient, fakeStore)

        repository.updateEndpoint(PiDebugEndpoint(host = "192.168.0.45", deviceId = "deskpi-a1"))
        repository.readServerSpki()
        repository.generatePairingJson()
        repository.registerGeneratedPairingJson()

        assertEquals(1, fakeStore.saveCalls)
        assertEquals("deskpi-a1", fakeStore.trustedPi.value?.deviceId)
    }

    @Test
    fun `eye only start sends only session open with eye only mode`() = runTest {
        val fakeClient = FakePiDebugClient()
        val fakeStore = FakePiDebugTrustedPiStore()
        val repository = PiDebugRepositoryImpl(fakeClient, fakeStore)

        repository.updateEndpoint(PiDebugEndpoint(host = "192.168.0.45"))
        repository.readServerSpki()
        repository.startEyeOnlySession()

        assertEquals(PiDebugSessionMode.EyeOnly, fakeClient.startedModes.single())
        assertTrue(fakeClient.startedSessionIds.single().startsWith("pi-debug-"))
        assertEquals(0, fakeClient.sentSamples.size)
        assertEquals(0, fakeStore.saveCalls)
    }

    @Test
    fun `eye with synthetic hr sends three hr ingest samples for current session`() = runTest {
        val fakeClient = FakePiDebugClient()
        val repository = PiDebugRepositoryImpl(fakeClient, FakePiDebugTrustedPiStore())

        repository.updateEndpoint(PiDebugEndpoint(host = "192.168.0.45"))
        repository.readServerSpki()
        repository.startEyeWithSyntheticHrSession()
        val sessionId = repository.currentState().sessionId!!
        repository.sendSyntheticHeartRate()

        assertEquals(PiDebugSessionMode.EyeWithSyntheticHr, fakeClient.startedModes.single())
        assertEquals(3, fakeClient.sentSamples.size)
        assertTrue(fakeClient.sentSamples.all { it.sessionId == sessionId })
        assertEquals(listOf(1L, 2L, 3L), fakeClient.sentSamples.map { it.sampleSeq })
    }

    @Test
    fun `incoming pi events from different session are ignored`() = runTest {
        val fakeClient = FakePiDebugClient()
        val repository = PiDebugRepositoryImpl(fakeClient, FakePiDebugTrustedPiStore())

        repository.updateEndpoint(PiDebugEndpoint(host = "192.168.0.45"))
        repository.readServerSpki()
        repository.startEyeOnlySession()
        val sessionId = repository.currentState().sessionId!!

        fakeClient.riskUpdates.emit(risk("other-session"))
        delay(50)
        assertNull(repository.currentState().lastRiskSummary)

        fakeClient.riskUpdates.emit(risk(sessionId))
        eventually {
            assertNotNull(repository.currentState().lastRiskSummary)
        }
    }

    @Test
    fun `packet logs keep latest eighty entries in newest first order`() = runTest {
        val fakeClient = FakePiDebugClient()
        val repository = PiDebugRepositoryImpl(fakeClient, FakePiDebugTrustedPiStore())

        repeat(85) { index ->
            fakeClient.packetLogs.emit(packetLog(sequence = index.toLong()))
        }

        eventually {
            val logs = repository.currentState().packetLogs
            assertEquals(80, logs.size)
            assertEquals(84L, logs.first().sequence)
            assertEquals(5L, logs.last().sequence)
        }
    }

    @Test
    fun `clear packet logs empties in memory debug log`() = runTest {
        val fakeClient = FakePiDebugClient()
        val repository = PiDebugRepositoryImpl(fakeClient, FakePiDebugTrustedPiStore())

        fakeClient.packetLogs.emit(packetLog(sequence = 1L))
        eventually {
            assertTrue(repository.currentState().packetLogs.isNotEmpty())
        }

        repository.clearPacketLogs()

        assertTrue(repository.currentState().packetLogs.isEmpty())
    }

    private fun risk(sessionId: String) = PiRiskUpdate(
        sessionId = sessionId,
        sequence = 1L,
        mode = "eye-only",
        eyeScore = 0.2,
        hrScore = null,
        fusedScore = 0.2,
        state = "BASELINE",
        recommendedFlushSec = null,
        receivedAt = LocalDateTime.now(),
    )

    private fun packetLog(sequence: Long) = PiDebugPacketLogEntry(
        receivedAt = LocalDateTime.now(),
        rawJson = """{"v":1,"t":"risk.update","seq":$sequence}""",
        parsedSuccessfully = true,
        type = "risk.update",
        sessionId = "pi-debug-test",
        sequence = sequence,
        ackRequired = false,
    )

    private fun validSpki(): String = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    private suspend fun eventually(assertion: () -> Unit) {
        withTimeout(2_000) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    delay(20)
                }
            }
        }
    }
}

private fun PiDebugRepositoryImpl.currentState(): PiDebugState =
    (observeDebugState() as StateFlow<PiDebugState>).value

private class FakePiDebugTrustedPiStore(
    initial: TrustedPiDevice? = null,
) : PiDebugTrustedPiStore {
    val trustedPi = MutableStateFlow(initial)
    var saveCalls = 0

    override fun observeTrustedPi(): Flow<TrustedPiDevice?> = trustedPi

    override suspend fun saveTrustedPi(device: TrustedPiDevice) {
        saveCalls += 1
        trustedPi.value = device
    }
}

private class FakePiDebugClient : PiDebugClient {
    val riskUpdates = MutableSharedFlow<PiRiskUpdate>(replay = 1)
    val alerts = MutableSharedFlow<PiAlertFire>(replay = 1)
    val summaries = MutableSharedFlow<PiSessionSummary>(replay = 1)
    val packetLogs = MutableSharedFlow<PiDebugPacketLogEntry>(extraBufferCapacity = 100)
    var nsdCandidates = emptyList<PiDebugNsdCandidate>()
    val startedModes = mutableListOf<PiDebugSessionMode>()
    val startedSessionIds = mutableListOf<String>()
    val sentSamples = mutableListOf<WatchHeartRateSample>()
    var connectDirectCalls = 0
    private val spki = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    override fun observeRiskUpdates(): Flow<PiRiskUpdate> = riskUpdates

    override fun observeAlerts(): Flow<PiAlertFire> = alerts

    override fun observeSessionSummaries(): Flow<PiSessionSummary> = summaries

    override fun observePacketLogs(): Flow<PiDebugPacketLogEntry> = packetLogs

    override suspend fun readServerSpki(endpoint: PiDebugEndpoint): String = spki

    override suspend fun discoverNsdCandidates(timeoutMs: Long): List<PiDebugNsdCandidate> = nsdCandidates

    override suspend fun connectDirect(endpoint: PiDebugEndpoint, expectedSpkiSha256: String): PiHelloAck {
        connectDirectCalls += 1
        return PiHelloAck(deviceId = endpoint.deviceId, mode = "debug", protocol = "v1")
    }

    override suspend fun connectRegistered(trustedPi: TrustedPiDevice): PiHelloAck =
        PiHelloAck(deviceId = trustedPi.deviceId, mode = "debug", protocol = "v1")

    override suspend fun startSession(sessionId: String, mode: PiDebugSessionMode): Boolean {
        startedSessionIds += sessionId
        startedModes += mode
        return true
    }

    override suspend fun sendHeartRateSamples(samples: List<WatchHeartRateSample>): Set<Long> {
        sentSamples += samples
        return samples.map { it.sampleSeq }.toSet()
    }

    override suspend fun stopSession(sessionId: String): PiSessionSummary? = null

    override suspend fun disconnect() = Unit
}
