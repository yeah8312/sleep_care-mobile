package com.sleepcare.mobile.data.repository

import com.sleepcare.mobile.domain.SleepDaySummary
import com.sleepcare.mobile.domain.SleepSession
import java.time.LocalDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepMetricsTest {
    @Test
    fun `regularity score rewards consistent bedtime and wake time`() {
        val consistentDays = listOf(
            sleepDay("2026-04-14T02:00", "2026-04-14T09:00"),
            sleepDay("2026-04-15T02:10", "2026-04-15T09:05"),
            sleepDay("2026-04-16T01:55", "2026-04-16T08:58"),
            sleepDay("2026-04-17T02:05", "2026-04-17T09:02"),
        )
        val irregularDays = listOf(
            sleepDay("2026-04-14T00:30", "2026-04-14T06:10"),
            sleepDay("2026-04-15T03:40", "2026-04-15T11:20"),
            sleepDay("2026-04-16T01:10", "2026-04-16T07:00"),
            sleepDay("2026-04-17T05:20", "2026-04-17T13:40"),
        )

        val consistentScore = calculateSleepRegularityScore(consistentDays)
        val irregularScore = calculateSleepRegularityScore(irregularDays)

        assertTrue(consistentScore > irregularScore)
    }

    @Test
    fun `weekly sleep analysis uses merged day totals`() {
        val snapshot = buildSleepAnalysisSnapshot(
            listOf(
                session("a", "2026-04-20T01:30", "2026-04-20T05:00", 210),
                session("b", "2026-04-20T06:30", "2026-04-20T08:00", 90),
                session("c", "2026-04-21T02:00", "2026-04-21T09:00", 420),
            )
        )

        assertTrue(snapshot.averageMinutes >= 360)
        assertTrue(snapshot.consistency in 35..100)
        assertTrue(snapshot.score in 35..100)
    }

    private fun sleepDay(start: String, end: String): SleepDaySummary {
        val session = session("session-$start", start, end, java.time.Duration.between(
            LocalDateTime.parse(start),
            LocalDateTime.parse(end)
        ).toMinutes().toInt())
        return SleepDaySummary(
            date = session.endTime.toLocalDate(),
            primarySession = session,
            totalMinutes = session.totalMinutes,
            extraSleepMinutes = 0,
        )
    }

    private fun session(id: String, start: String, end: String, totalMinutes: Int): SleepSession =
        SleepSession(
            id = id,
            startTime = LocalDateTime.parse(start),
            endTime = LocalDateTime.parse(end),
            totalMinutes = totalMinutes,
            sleepScore = 80,
            consistencyScore = 80,
            latencyMinutes = 0,
            awakeMinutes = 12,
        )
}
