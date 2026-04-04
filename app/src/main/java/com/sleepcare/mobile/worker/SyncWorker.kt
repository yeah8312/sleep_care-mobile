package com.sleepcare.mobile.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.SleepRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sleepRepository: SleepRepository,
    private val recommendationRepository: RecommendationRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        sleepRepository.refreshFromSource()
        recommendationRepository.refreshRecommendations()
        return Result.success()
    }
}

