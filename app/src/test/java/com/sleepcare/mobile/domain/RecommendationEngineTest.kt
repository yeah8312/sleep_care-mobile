package com.sleepcare.mobile.domain

import com.sleepcare.mobile.data.repository.SleepCareRecommendationEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val engine = SleepCareRecommendationEngine()
    private val now = LocalDateTime.of(2026, 4, 8, 18, 0)

    @Test
    fun `empty personal data returns setup status instead of demo recommendation`() {
        val recommendation = engine.generate(baseInput())

        assertEquals(RecommendationStatus.NeedsSetup, recommendation.status)
        assertTrue(recommendation.reason.contains("설정"))
    }

    @Test
    fun `afternoon exam keeps user wake time and creates exam prep block`() {
        val recommendation = engine.generate(
            baseInput(
                userGoals = UserGoals(targetWakeTime = LocalTime.of(7, 0)),
                exams = listOf(exam(start = LocalTime.of(14, 0), date = LocalDate.of(2026, 4, 10))),
            )
        )

        assertEquals(LocalTime.of(7, 0), recommendation.recommendedWakeTime)
        assertTrue(recommendation.targetSleepMinutes >= 480)
        assertTrue(recommendation.actionBlocks.any { it.type == RecommendationActionBlockType.ExamPrep })
        assertTrue(recommendation.reason.contains("복습"))
    }

    @Test
    fun `morning exam only pulls wake time when normal wake would miss deadline`() {
        val recommendation = engine.generate(
            baseInput(
                userGoals = UserGoals(targetWakeTime = LocalTime.of(8, 30)),
                exams = listOf(exam(start = LocalTime.of(9, 0), date = LocalDate.of(2026, 4, 10))),
            )
        )

        assertEquals(LocalTime.of(7, 0), recommendation.recommendedWakeTime)
        assertTrue(recommendation.factors.any { it.type == RecommendationFactorType.AcademicSchedule })
    }

    @Test
    fun `irregular sleep rhythm moves bedtime and wake time gradually`() {
        val recommendation = engine.generate(
            baseInput(
                sleepSessions = listOf(
                    sleep("a", "2026-04-05T23:00:00", "2026-04-06T06:00:00", 420),
                    sleep("b", "2026-04-06T02:00:00", "2026-04-06T08:00:00", 360),
                    sleep("c", "2026-04-07T04:00:00", "2026-04-07T09:00:00", 300),
                ),
                userGoals = UserGoals(targetWakeTime = LocalTime.of(6, 30)),
            )
        )

        assertEquals(LocalTime.of(7, 15), recommendation.recommendedWakeTime)
        assertEquals(LocalTime.of(1, 30), recommendation.recommendedBedtime)
        assertTrue(recommendation.factors.any { it.value == "기상 고정 우선" })
    }

    @Test
    fun `old drowsiness events outside fourteen days do not affect copy or sleep target`() {
        val oldEvents = (1..5).map { index ->
            drowsiness("old-$index", now.minusDays(20).withHour(14).withMinute(index))
        }

        val recommendation = engine.generate(
            baseInput(
                drowsinessEvents = oldEvents,
                userGoals = UserGoals(targetWakeTime = LocalTime.of(7, 0)),
            )
        )

        assertEquals(450, recommendation.targetSleepMinutes)
        assertFalse(recommendation.reason.contains("졸음"))
        assertFalse(recommendation.tips.any { it.title.contains("졸음") || it.body.contains("졸음") })
        assertTrue(recommendation.factors.any { it.type == RecommendationFactorType.DrowsinessPattern && it.value == "최근 14일 0회" })
    }

    @Test
    fun `recent afternoon drowsiness creates recovery block when auto break is enabled`() {
        val events = (1..3).map { index ->
            drowsiness("recent-$index", now.minusDays(index.toLong()).withHour(14).withMinute(10 + index))
        }

        val recommendation = engine.generate(
            baseInput(
                drowsinessEvents = events,
                studyPlan = studyPlan(autoBreakEnabled = true),
                userGoals = UserGoals(targetWakeTime = LocalTime.of(7, 0)),
            )
        )

        assertTrue(recommendation.targetSleepMinutes >= 465)
        assertTrue(recommendation.actionBlocks.any { it.type == RecommendationActionBlockType.Recovery })
    }

    private fun baseInput(
        sleepSessions: List<SleepSession> = emptyList(),
        drowsinessEvents: List<DrowsinessEvent> = emptyList(),
        studyPlan: StudyPlan? = null,
        exams: List<ExamSchedule> = emptyList(),
        userGoals: UserGoals = UserGoals(),
    ): RecommendationInput = RecommendationInput(
        sleepSessions = sleepSessions,
        drowsinessEvents = drowsinessEvents,
        studyPlan = studyPlan,
        exams = exams,
        userGoals = userGoals,
        generatedAt = now,
    )

    private fun sleep(id: String, start: String, end: String, totalMinutes: Int): SleepSession = SleepSession(
        id = id,
        startTime = LocalDateTime.parse(start),
        endTime = LocalDateTime.parse(end),
        totalMinutes = totalMinutes,
        sleepScore = 70,
        consistencyScore = 50,
        latencyMinutes = 20,
        awakeMinutes = 10,
    )

    private fun drowsiness(id: String, timestamp: LocalDateTime): DrowsinessEvent = DrowsinessEvent(
        id = id,
        timestamp = timestamp,
        severity = 3,
        durationMinutes = 8,
        label = "졸음",
        deviceId = "pi",
    )

    private fun exam(start: LocalTime, date: LocalDate): ExamSchedule = ExamSchedule(
        name = "시험",
        date = date,
        startTime = start,
        endTime = start.plusHours(2),
        location = "강의실",
        priority = 1,
        syncEnabled = true,
    )

    private fun studyPlan(autoBreakEnabled: Boolean): StudyPlan = StudyPlan(
        startTime = LocalTime.of(9, 0),
        endTime = LocalTime.of(21, 0),
        focusHours = 0,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        breakPreferenceMinutes = 0,
        autoBreakEnabled = autoBreakEnabled,
    )
}
