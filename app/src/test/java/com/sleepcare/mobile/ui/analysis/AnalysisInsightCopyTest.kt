package com.sleepcare.mobile.ui.analysis

import com.sleepcare.mobile.domain.DrowsinessAnalysisSnapshot
import com.sleepcare.mobile.domain.SleepAnalysisSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisInsightCopyTest {
    @Test
    fun `analysis insight does not invent a combined pattern without data`() {
        val message = buildAnalysisInsight(
            AnalysisUiState(
                sleepAvailable = false,
                sleepEmptyReason = "Health Connect 수면 데이터가 아직 없습니다.",
                drowsiness = DrowsinessAnalysisSnapshot(
                    totalCount = 0,
                    peakWindowLabel = "실시간 연결 대기",
                    focusScore = 0,
                    recentEvents = emptyList(),
                ),
            )
        )

        assertTrue(message.contains("통합 인사이트는 대기 중"))
        assertFalse(message.contains("오후 2시대"))
    }

    @Test
    fun `analysis insight uses available sleep and drowsiness metrics`() {
        val message = buildAnalysisInsight(
            AnalysisUiState(
                sleep = SleepAnalysisSnapshot(
                    score = 78,
                    averageMinutes = 420,
                    consistency = 82,
                    latencyMinutes = 15,
                    awakeMinutes = 10,
                    weeklyDurations = emptyList(),
                ),
                drowsiness = DrowsinessAnalysisSnapshot(
                    totalCount = 3,
                    peakWindowLabel = "14:00 - 14:59",
                    focusScore = 72,
                    recentEvents = emptyList(),
                ),
                sleepAvailable = true,
            )
        )

        assertTrue(message.contains("평균 수면 7시간"))
        assertTrue(message.contains("14:00 - 14:59"))
        assertTrue(message.contains("72점"))
    }

    @Test
    fun `drowsiness detail waits for real alert events before warning about a peak window`() {
        val message = buildDrowsinessDetailInsight(
            DrowsinessAnalysisSnapshot(
                totalCount = 0,
                peakWindowLabel = "실시간 연결 대기",
                focusScore = 0,
                recentEvents = emptyList(),
            )
        )

        assertTrue(message.contains("주의 시간대를 계산하지 않았습니다"))
    }
}
