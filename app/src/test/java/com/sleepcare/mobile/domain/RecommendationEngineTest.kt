package com.sleepcare.mobile.domain

import com.sleepcare.mobile.data.repository.SleepCareRecommendationEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val engine = SleepCareRecommendationEngine()

    @Test
    fun `exam in two weeks pulls wake time earlier`() {
        val input = RecommendationInput(
            sleepSessions = listOf(
                SleepSession(
                    id = "sleep-1",
                    startTime = LocalDateTime.of(2026, 4, 3, 0, 20),
                    endTime = LocalDateTime.of(2026, 4, 3, 6, 20),
                    totalMinutes = 360,
                    sleepScore = 72,
                    consistencyScore = 65,
                    latencyMinutes = 20,
                    awakeMinutes = 20,
                )
            ),
            drowsinessEvents = listOf(
                DrowsinessEvent(
                    id = "d1",
                    timestamp = LocalDateTime.of(2026, 4, 3, 14, 30),
                    severity = 3,
                    durationMinutes = 10,
                    label = "오후 졸음",
                    deviceId = "pi",
                )
            ),
            studyPlan = StudyPlan(
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(22, 0),
                focusHours = 8,
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                breakPreferenceMinutes = 15,
                autoBreakEnabled = true,
            ),
            exams = listOf(
                ExamSchedule(
                    name = "모의고사",
                    date = LocalDate.of(2026, 4, 10),
                    startTime = LocalTime.of(7, 0),
                    endTime = LocalTime.of(11, 0),
                    location = "본관",
                    priority = 1,
                    syncEnabled = true,
                )
            ),
            userGoals = UserGoals(targetWakeTime = LocalTime.of(8, 30)),
            generatedAt = LocalDateTime.of(2026, 4, 4, 18, 0),
        )

        val recommendation = engine.generate(input)

        assertEquals(LocalTime.of(5, 30), recommendation.recommendedWakeTime)
        assertTrue(recommendation.targetSleepMinutes >= 450)
        assertTrue(recommendation.tips.isNotEmpty())
    }
}
