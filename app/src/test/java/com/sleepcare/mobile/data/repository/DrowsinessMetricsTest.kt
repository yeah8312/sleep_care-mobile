package com.sleepcare.mobile.data.repository

import com.sleepcare.mobile.domain.DrowsinessEvent
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DrowsinessMetricsTest {
    @Test
    fun `drowsiness snapshot keeps total count separate from recent display list`() {
        val events = (0 until 10).map { index ->
            DrowsinessEvent(
                id = "event-$index",
                timestamp = LocalDateTime.of(2026, 5, 25, 14, index),
                severity = 2,
                durationMinutes = 5,
                label = "졸음",
                deviceId = "pi",
            )
        }

        val snapshot = buildDrowsinessAnalysisSnapshot(events = events, sessions = emptyList())

        assertEquals(10, snapshot.totalCount)
        assertEquals(8, snapshot.recentEvents.size)
        assertEquals("14:00 - 14:59", snapshot.peakWindowLabel)
    }
}
