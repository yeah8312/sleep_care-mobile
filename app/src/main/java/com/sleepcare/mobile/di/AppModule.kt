package com.sleepcare.mobile.di

import android.content.Context
import androidx.room.Room
import com.sleepcare.mobile.data.local.DrowsinessEventDao
import com.sleepcare.mobile.data.local.ExamScheduleDao
import com.sleepcare.mobile.data.local.MIGRATION_3_4
import com.sleepcare.mobile.data.local.PreferencesStore
import com.sleepcare.mobile.data.local.RecommendationSnapshotDao
import com.sleepcare.mobile.data.local.SleepCareDatabase
import com.sleepcare.mobile.data.local.SleepSessionDao
import com.sleepcare.mobile.data.local.StudyPlanDao
import com.sleepcare.mobile.data.local.StudySessionDao
import com.sleepcare.mobile.data.repository.DeviceConnectionRepositoryImpl
import com.sleepcare.mobile.data.repository.DrowsinessRepositoryImpl
import com.sleepcare.mobile.data.repository.ExamScheduleRepositoryImpl
import com.sleepcare.mobile.data.repository.PiDebugRepositoryImpl
import com.sleepcare.mobile.data.repository.PiDebugTrustedPiStore
import com.sleepcare.mobile.data.repository.PreferencesPiDebugTrustedPiStore
import com.sleepcare.mobile.data.repository.RecommendationRepositoryImpl
import com.sleepcare.mobile.data.repository.SettingsRepositoryImpl
import com.sleepcare.mobile.data.repository.SleepCareRecommendationEngine
import com.sleepcare.mobile.data.repository.SleepRepositoryImpl
import com.sleepcare.mobile.data.repository.StudyPlanRepositoryImpl
import com.sleepcare.mobile.data.repository.StudySessionRepositoryImpl
import com.sleepcare.mobile.data.repository.WatchDebugRepositoryImpl
import com.sleepcare.mobile.data.source.GalaxyWatchSessionDataSource
import com.sleepcare.mobile.data.source.HealthConnectSleepDataSource
import com.sleepcare.mobile.data.source.PiDebugClient
import com.sleepcare.mobile.data.source.PiDebugNetworkClient
import com.sleepcare.mobile.data.source.PiNetworkDataSourceImpl
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.PiDebugRepository
import com.sleepcare.mobile.domain.PiNetworkDataSource
import com.sleepcare.mobile.domain.RecommendationEngine
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.domain.StudyPlanRepository
import com.sleepcare.mobile.domain.StudySessionRepository
import com.sleepcare.mobile.domain.WatchDebugRepository
import com.sleepcare.mobile.domain.WatchSessionDataSource
import com.sleepcare.mobile.domain.WatchSleepDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Hilt가 앱 전체에서 쓸 싱글턴 객체와 구현체 바인딩을 만드는 곳입니다.
// 화면/저장소는 생성 방법을 몰라도 생성자 주입으로 필요한 의존성을 받을 수 있습니다.

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {
    // Room 데이터베이스는 앱 생명주기 전체에서 하나만 유지합니다.
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SleepCareDatabase =
        Room.databaseBuilder(context, SleepCareDatabase::class.java, "sleep-care.db")
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSleepSessionDao(database: SleepCareDatabase): SleepSessionDao = database.sleepSessionDao()

    @Provides
    fun provideDrowsinessEventDao(database: SleepCareDatabase): DrowsinessEventDao = database.drowsinessEventDao()

    @Provides
    fun provideStudyPlanDao(database: SleepCareDatabase): StudyPlanDao = database.studyPlanDao()

    @Provides
    fun provideStudySessionDao(database: SleepCareDatabase): StudySessionDao = database.studySessionDao()

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
    fun provideWatchSleepDataSource(
        impl: HealthConnectSleepDataSource,
    ): WatchSleepDataSource = impl

    @Provides
    @Singleton
    fun provideWatchSessionDataSource(
        @ApplicationContext context: Context,
    ): WatchSessionDataSource = GalaxyWatchSessionDataSource(context)

    // Pi 통신은 Android NSD와 WSS 연결을 직접 다루므로 Context를 받아 생성합니다.
    @Provides
    @Singleton
    fun providePiNetworkDataSource(
        @ApplicationContext context: Context,
        preferencesStore: PreferencesStore,
    ): PiNetworkDataSource = PiNetworkDataSourceImpl(context, preferencesStore)
}

// 인터페이스를 실제 구현체에 연결하는 바인딩 모듈입니다.
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

    @Binds
    abstract fun bindStudySessionRepository(impl: StudySessionRepositoryImpl): StudySessionRepository

    @Binds
    abstract fun bindWatchDebugRepository(impl: WatchDebugRepositoryImpl): WatchDebugRepository

    @Binds
    abstract fun bindPiDebugRepository(impl: PiDebugRepositoryImpl): PiDebugRepository

    @Binds
    abstract fun bindPiDebugClient(impl: PiDebugNetworkClient): PiDebugClient

    @Binds
    abstract fun bindPiDebugTrustedPiStore(impl: PreferencesPiDebugTrustedPiStore): PiDebugTrustedPiStore
}
