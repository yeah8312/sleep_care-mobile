package com.sleepcare.mobile.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreCalculatorTest {
    @Test
    fun `sleep quality stays within valid range`() {
        val score = ScoreCalculator.sleepQuality(
            totalMinutes = 405,
            consistencyScore = 78,
            latencyMinutes = 15,
            awakeMinutes = 12,
        )

        assertTrue(score in 35..100)
    }

    @Test
    fun `focus score rewards better sleep`() {
        val events = listOf(
            DrowsinessEvent(
                id = "event-1",
                timestamp = java.time.LocalDateTime.now(),
                severity = 2,
                durationMinutes = 5,
                label = "오후 피로",
                deviceId = "pi",
            )
        )

        val lowSleepScore = ScoreCalculator.focusScore(events, averageSleepMinutes = 340)
        val healthySleepScore = ScoreCalculator.focusScore(events, averageSleepMinutes = 450)

        assertTrue(healthySleepScore > lowSleepScore)
    }
}

