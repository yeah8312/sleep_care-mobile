package com.sleepcare.mobile.di

import android.content.Context
import androidx.room.Room
import com.sleepcare.mobile.data.local.DrowsinessEventDao
import com.sleepcare.mobile.data.local.ExamScheduleDao
import com.sleepcare.mobile.data.local.PreferencesStore
import com.sleepcare.mobile.data.local.RecommendationSnapshotDao
import com.sleepcare.mobile.data.local.SleepCareDatabase
import com.sleepcare.mobile.data.local.SleepSessionDao
import com.sleepcare.mobile.data.local.StudyPlanDao
import com.sleepcare.mobile.data.repository.DeviceConnectionRepositoryImpl
import com.sleepcare.mobile.data.repository.DrowsinessRepositoryImpl
import com.sleepcare.mobile.data.repository.ExamScheduleRepositoryImpl
import com.sleepcare.mobile.data.repository.RecommendationRepositoryImpl
import com.sleepcare.mobile.data.repository.SettingsRepositoryImpl
import com.sleepcare.mobile.data.repository.SleepCareRecommendationEngine
import com.sleepcare.mobile.data.repository.SleepRepositoryImpl
import com.sleepcare.mobile.data.repository.StudyPlanRepositoryImpl
import com.sleepcare.mobile.data.source.FakePiBleDataSource
import com.sleepcare.mobile.data.source.FakeWatchSleepDataSource
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.PiBleDataSource
import com.sleepcare.mobile.domain.RecommendationEngine
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.domain.StudyPlanRepository
import com.sleepcare.mobile.domain.WatchSleepDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SleepCareDatabase =
        Room.databaseBuilder(context, SleepCareDatabase::class.java, "sleep-care.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSleepSessionDao(database: SleepCareDatabase): SleepSessionDao = database.sleepSessionDao()

    @Provides
    fun provideDrowsinessEventDao(database: SleepCareDatabase): DrowsinessEventDao = database.drowsinessEventDao()

    @Provides
    fun provideStudyPlanDao(database: SleepCareDatabase): StudyPlanDao = database.studyPlanDao()

    @Provides
    fun provideExamScheduleDao(database: SleepCareDatabase): ExamScheduleDao = database.examScheduleDao()

    @Provides
    fun provideRecommendationSnapshotDao(database: SleepCareDatabase): RecommendationSnapshotDao =
        database.recommendationSnapshotDao()

    @Provides
    @Singleton
    fun providePreferencesStore(@ApplicationContext context: Context): PreferencesStore = PreferencesStore(context)

    @Provides
    @Singleton
    fun provideWatchSleepDataSource(): WatchSleepDataSource = FakeWatchSleepDataSource()

    @Provides
    @Singleton
    fun providePiBleDataSource(): PiBleDataSource = FakePiBleDataSource()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {
    @Binds
    abstract fun bindSleepRepository(impl: SleepRepositoryImpl): SleepRepository

    @Binds
    abstract fun bindDrowsinessRepository(impl: DrowsinessRepositoryImpl): DrowsinessRepository

    @Binds
    abstract fun bindStudyPlanRepository(impl: StudyPlanRepositoryImpl): StudyPlanRepository

    @Binds
    abstract fun bindExamScheduleRepository(impl: ExamScheduleRepositoryImpl): ExamScheduleRepository

    @Binds
    abstract fun bindRecommendationRepository(impl: RecommendationRepositoryImpl): RecommendationRepository

    @Binds
    abstract fun bindDeviceConnectionRepository(impl: DeviceConnectionRepositoryImpl): DeviceConnectionRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindRecommendationEngine(impl: SleepCareRecommendationEngine): RecommendationEngine
}
