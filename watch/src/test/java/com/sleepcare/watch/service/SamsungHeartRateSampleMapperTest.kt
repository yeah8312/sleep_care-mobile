package com.sleepcare.watch.service

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungHeartRateSampleMapperTest {
    @Test
    fun mapsSamsungReadingToSharedWatchSample() {
        val fixedNow = LocalDateTime.of(2026, 5, 18, 22, 10)
        val mapper = SamsungHeartRateSampleMapper(now = { fixedNow })

        val sample = mapper.toSample(
            sessionId = "session-1",
            reading = SamsungHeartRateReading(
                sensorTimestampMs = 1234L,
                bpm = 72,
                hrStatus = 1,
                ibiMs = listOf(830, 840),
                ibiStatus = listOf(0, 0),
            ),
            messageSequence = 3L,
            sampleSeq = 2L,
        )

        assertEquals("session-1", sample.sessionId)
        assertEquals(3L, sample.messageSequence)
        assertEquals(2L, sample.sampleSeq)
        assertEquals(1234L, sample.sensorTimestampMs)
        assertEquals(72, sample.bpm)
        assertEquals(1, sample.hrStatus)
        assertEquals(listOf(830, 840), sample.ibiMs)
        assertEquals(listOf(0, 0), sample.ibiStatus)
        assertEquals("live", sample.deliveryMode)
        assertEquals(fixedNow, sample.receivedAt)
    }

    @Test
    fun keepsEmptyIbiListsWhenSamsungDoesNotProvideIbiForEveryBatchPoint() {
        val mapper = SamsungHeartRateSampleMapper(now = { LocalDateTime.of(2026, 5, 18, 22, 11) })

        val sample = mapper.toSample(
            sessionId = "session-2",
            reading = SamsungHeartRateReading(
                sensorTimestampMs = 2000L,
                bpm = 68,
                hrStatus = 1,
                ibiMs = emptyList(),
                ibiStatus = emptyList(),
            ),
            messageSequence = 1L,
            sampleSeq = 1L,
        )

        assertEquals(emptyList<Int>(), sample.ibiMs)
        assertEquals(emptyList<Int>(), sample.ibiStatus)
    }
}
