package com.sleepcare.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInsightCopyTest {
    @Test
    fun `fatigue timeline waits honestly when there are no drowsiness events`() {
        val timeline = buildHomeFatigueTimeline(0)

        assertTrue(timeline.message.contains("기록된 Pi 졸음 이벤트가 없습니다"))
        assertEquals(listOf("기록 대기"), timeline.segments.map { it.label })
    }

    @Test
    fun `fatigue timeline marks repeated events without inventing a day pattern`() {
        val timeline = buildHomeFatigueTimeline(4)

        assertTrue(timeline.message.contains("4회 반복"))
        assertTrue(timeline.segments.any { it.label == "반복 졸음" })
        assertTrue(timeline.segments.none { it.label == "안정 집중" })
    }
}
