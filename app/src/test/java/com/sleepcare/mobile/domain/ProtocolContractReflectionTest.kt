package com.sleepcare.mobile.domain

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProtocolContractReflectionTest {
    @Test
    fun `pi envelope contract keeps the documented core fields when present`() {
        val envelopeClass = findVisibleClass("PiEnvelope")
        assumeTrue("PiEnvelope is not visible yet", envelopeClass != null)

        val fieldNames = envelopeClass!!.declaredFields.map { it.name }.toSet()
        assertTrue(
            fieldNames.containsAll(
                setOf("version", "type", "sessionId", "sequence", "source", "sentAtMs", "ackRequired", "body")
            )
        )
    }

    @Test
    fun `pi payload contracts keep the documented fields when present`() {
        val riskUpdateClass = findVisibleClass("PiRiskUpdate")
        val alertFireClass = findVisibleClass("PiAlertFire")

        assumeTrue("PiRiskUpdate and PiAlertFire are not visible yet", riskUpdateClass != null && alertFireClass != null)

        val riskFieldNames = riskUpdateClass!!.declaredFields.map { it.name }.toSet()
        assertTrue(riskFieldNames.containsAll(setOf("mode", "eyeScore", "hrScore", "fusedScore", "state", "recommendedFlushSec")))

        val alertFieldNames = alertFireClass!!.declaredFields.map { it.name }.toSet()
        assertTrue(alertFieldNames.containsAll(setOf("level", "reason", "durationMs")))
    }

    @Test
    fun `study session state contract exposes the lifecycle states when present`() {
        val phaseClass = findVisibleClass("StudySessionPhase")
        val stateClass = findVisibleClass("StudySessionState")
        assumeTrue("StudySessionState and StudySessionPhase are not visible yet", stateClass != null && phaseClass != null)

        val stateFieldNames = stateClass!!.declaredFields.map { it.name }.toSet()
        assertTrue(stateFieldNames.containsAll(setOf("sessionId", "phase", "startedAt", "latestRisk", "latestAlert", "latestSummary", "message")))

        assertTrue(phaseClass!!.isEnum)
        val names = phaseClass.enumConstants!!.map { (it as Enum<*>).name }.toSet()
        assertTrue(names.containsAll(setOf("Idle", "DiscoveringPi", "ConnectingPi", "OpeningSession", "Running", "Alerting", "Stopping", "Error")))
    }

    private fun findVisibleClass(simpleName: String): Class<*>? {
        val candidates = listOf(
            "com.sleepcare.mobile.domain.$simpleName",
            "com.sleepcare.mobile.protocol.$simpleName",
            "com.sleepcare.mobile.data.protocol.$simpleName",
            "com.sleepcare.mobile.data.source.$simpleName",
        )
        return candidates.asSequence()
            .mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
            .firstOrNull()
    }
}
