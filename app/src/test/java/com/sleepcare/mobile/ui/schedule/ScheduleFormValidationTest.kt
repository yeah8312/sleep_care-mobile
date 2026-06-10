package com.sleepcare.mobile.ui.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleFormValidationTest {
    @Test
    fun `blank user goals are saved as unset goals`() {
        val result = parseUserGoalsForm("", "")

        assertNull(result.error)
        assertNull(result.value?.targetWakeTime)
        assertNull(result.value?.preferredBedtime)
    }

    @Test
    fun `study plan rejects end time before start time`() {
        val result = parseStudyPlanForm(
            startText = "22:00",
            endText = "08:00",
            autoBreakEnabled = true,
            selectedDays = setOf(DayOfWeek.MONDAY),
        )

        assertNotNull(result.error)
    }

    @Test
    fun `study plan keeps only available time and auto break preference`() {
        val result = parseStudyPlanForm(
            startText = "08:00",
            endText = "22:00",
            autoBreakEnabled = false,
            selectedDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        )

        assertNull(result.error)
        assertEquals(LocalTime.of(8, 0), result.value?.startTime)
        assertEquals(LocalTime.of(22, 0), result.value?.endTime)
        assertEquals(0, result.value?.focusHours)
        assertEquals(0, result.value?.breakPreferenceMinutes)
        assertEquals(false, result.value?.autoBreakEnabled)
    }

    @Test
    fun `exam form preserves id when editing`() {
        val result = parseExamForm(
            id = 42L,
            name = "중간고사",
            date = "2026-04-10",
            startTime = "14:00",
            endTime = "16:00",
            location = "본관",
            priority = "2",
            syncEnabled = true,
        )

        assertNull(result.error)
        assertEquals(42L, result.value?.id)
        assertEquals(LocalDate.of(2026, 4, 10), result.value?.date)
    }

    @Test
    fun `exam form rejects invalid time range`() {
        val result = parseExamForm(
            id = 0L,
            name = "시험",
            date = "2026-04-10",
            startTime = "16:00",
            endTime = "14:00",
            location = "본관",
            priority = "1",
            syncEnabled = true,
        )

        assertNotNull(result.error)
    }
}
