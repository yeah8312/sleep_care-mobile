package com.sleepcare.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.sleepcare.mobile.domain.DrowsinessEvent
import com.sleepcare.mobile.domain.ExamSchedule
import com.sleepcare.mobile.domain.LastSyncState
import com.sleepcare.mobile.domain.NotificationPreferences
import com.sleepcare.mobile.domain.OnboardingState
import com.sleepcare.mobile.domain.PiSessionSummary
import com.sleepcare.mobile.domain.RecommendationSnapshot
import com.sleepcare.mobile.domain.RecommendationTip
import com.sleepcare.mobile.domain.SleepSession
import com.sleepcare.mobile.domain.StudySessionState
import com.sleepcare.mobile.domain.StudyPlan
import com.sleepcare.mobile.domain.UserGoals
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sleep_care_preferences")

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey val id: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val totalMinutes: Int,
    val sleepScore: Int,
    val consistencyScore: Int,
    val latencyMinutes: Int,
    val awakeMinutes: Int,
    val source: String,
)

@Entity(tableName = "drowsiness_events")
data class DrowsinessEventEntity(
    @PrimaryKey val id: String,
    val timestamp: LocalDateTime,
    val severity: Int,
    val durationMinutes: Int,
    val label: String,
    val deviceId: String,
    val sessionId: String?,
)

@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey val id: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val phase: String,
    val latestRiskState: String?,
    val latestFusedScore: Double?,
    val alertCount: Int,
    val summaryMode: String?,
    val summaryReason: String?,
    val peakFusedScore: Double?,
)

@Entity(tableName = "study_plan")
data class StudyPlanEntity(
    @PrimaryKey val id: Int = StudyPlan.DEFAULT_ID,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val focusHours: Int,
    val daysSerialized: String,
    val breakPreferenceMinutes: Int,
    val autoBreakEnabled: Boolean,
)

@Entity(tableName = "exam_schedule")
data class ExamScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val location: String,
    val priority: Int,
    val syncEnabled: Boolean,
)

@Entity(tableName = "recommendation_snapshot")
data class RecommendationSnapshotEntity(
    @PrimaryKey val id: Long = 1L,
    val recommendedBedtime: LocalTime,
    val recommendedWakeTime: LocalTime,
    val targetSleepMinutes: Int,
    val reason: String,
    val routineShiftMinutes: Int,
    val tipsSerialized: String,
    val generatedAt: LocalDateTime,
)

class RoomConverters {
    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localTimeToString(value: LocalTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)
}

@Dao
interface SleepSessionDao {
    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC")
    fun observeAll(): Flow<List<SleepSessionEntity>>

    @Query("SELECT COUNT(*) FROM sleep_sessions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SleepSessionEntity>)

    @Query("DELETE FROM sleep_sessions")
    suspend fun clear()
}

@Dao
interface DrowsinessEventDao {
    @Query("SELECT * FROM drowsiness_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DrowsinessEventEntity>>

    @Query("SELECT COUNT(*) FROM drowsiness_events")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DrowsinessEventEntity>)

    @Query("DELETE FROM drowsiness_events")
    suspend fun clear()
}

@Dao
interface StudySessionDao {
    @Query("SELECT * FROM study_sessions ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<StudySessionEntity?>

    @Query("SELECT * FROM study_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StudySessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StudySessionEntity)

    @Query("DELETE FROM study_sessions")
    suspend fun clear()
}

@Dao
interface StudyPlanDao {
    @Query("SELECT * FROM study_plan WHERE id = :id")
    fun observeById(id: Int = StudyPlan.DEFAULT_ID): Flow<StudyPlanEntity?>

    @Query("SELECT COUNT(*) FROM study_plan")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StudyPlanEntity)

    @Query("DELETE FROM study_plan")
    suspend fun clear()
}

@Dao
interface ExamScheduleDao {
    @Query("SELECT * FROM exam_schedule ORDER BY date ASC, startTime ASC")
    fun observeAll(): Flow<List<ExamScheduleEntity>>

    @Query("SELECT COUNT(*) FROM exam_schedule")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExamScheduleEntity)

    @Query("DELETE FROM exam_schedule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM exam_schedule")
    suspend fun clear()
}

@Dao
interface RecommendationSnapshotDao {
    @Query("SELECT * FROM recommendation_snapshot WHERE id = 1")
    fun observeLatest(): Flow<RecommendationSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecommendationSnapshotEntity)

    @Query("DELETE FROM recommendation_snapshot")
    suspend fun clear()
}

@Database(
    entities = [
        SleepSessionEntity::class,
        DrowsinessEventEntity::class,
        StudySessionEntity::class,
        StudyPlanEntity::class,
        ExamScheduleEntity::class,
        RecommendationSnapshotEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class SleepCareDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun drowsinessEventDao(): DrowsinessEventDao
    abstract fun studySessionDao(): StudySessionDao
    abstract fun studyPlanDao(): StudyPlanDao
    abstract fun examScheduleDao(): ExamScheduleDao
    abstract fun recommendationSnapshotDao(): RecommendationSnapshotDao
}

class PreferencesStore(private val context: Context) {
    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val drowsinessAlerts = booleanPreferencesKey("drowsiness_alerts")
        val sleepReminders = booleanPreferencesKey("sleep_reminders")
        val targetWakeTime = stringPreferencesKey("target_wake_time")
        val preferredBedtime = stringPreferencesKey("preferred_bedtime")
        val sleepSyncedAt = stringPreferencesKey("sleep_synced_at")
        val drowsinessSyncedAt = stringPreferencesKey("drowsiness_synced_at")
    }

    val onboardingState: Flow<OnboardingState> = context.dataStore.safeData()
        .map { OnboardingState(completed = it[Keys.onboardingCompleted] ?: false) }

    val notificationPreferences: Flow<NotificationPreferences> = context.dataStore.safeData()
        .map {
            NotificationPreferences(
                drowsinessAlertsEnabled = it[Keys.drowsinessAlerts] ?: true,
                sleepRemindersEnabled = it[Keys.sleepReminders] ?: true,
            )
        }

    val userGoals: Flow<UserGoals> = context.dataStore.safeData()
        .map {
            UserGoals(
                targetWakeTime = it[Keys.targetWakeTime]?.let(LocalTime::parse),
                preferredBedtime = it[Keys.preferredBedtime]?.let(LocalTime::parse),
            )
        }

    val lastSyncState: Flow<LastSyncState> = context.dataStore.safeData()
        .map {
            LastSyncState(
                sleepSyncedAt = it[Keys.sleepSyncedAt]?.let(LocalDateTime::parse),
                drowsinessSyncedAt = it[Keys.drowsinessSyncedAt]?.let(LocalDateTime::parse),
            )
        }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    suspend fun updateNotificationPreferences(preferences: NotificationPreferences) {
        context.dataStore.edit {
            it[Keys.drowsinessAlerts] = preferences.drowsinessAlertsEnabled
            it[Keys.sleepReminders] = preferences.sleepRemindersEnabled
        }
    }

    suspend fun updateUserGoals(goals: UserGoals) {
        context.dataStore.edit {
            if (goals.targetWakeTime == null) {
                it.remove(Keys.targetWakeTime)
            } else {
                it[Keys.targetWakeTime] = goals.targetWakeTime.toString()
            }
            if (goals.preferredBedtime == null) {
                it.remove(Keys.preferredBedtime)
            } else {
                it[Keys.preferredBedtime] = goals.preferredBedtime.toString()
            }
        }
    }

    suspend fun updateLastSyncState(state: LastSyncState) {
        context.dataStore.edit {
            if (state.sleepSyncedAt == null) {
                it.remove(Keys.sleepSyncedAt)
            } else {
                it[Keys.sleepSyncedAt] = state.sleepSyncedAt.toString()
            }
            if (state.drowsinessSyncedAt == null) {
                it.remove(Keys.drowsinessSyncedAt)
            } else {
                it[Keys.drowsinessSyncedAt] = state.drowsinessSyncedAt.toString()
            }
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

private fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { exception ->
    if (exception is IOException) emit(emptyPreferences()) else throw exception
}

fun SleepSessionEntity.toDomain(): SleepSession = SleepSession(
    id = id,
    startTime = startTime,
    endTime = endTime,
    totalMinutes = totalMinutes,
    sleepScore = sleepScore,
    consistencyScore = consistencyScore,
    latencyMinutes = latencyMinutes,
    awakeMinutes = awakeMinutes,
    source = source,
)

fun SleepSession.toEntity(): SleepSessionEntity = SleepSessionEntity(
    id = id,
    startTime = startTime,
    endTime = endTime,
    totalMinutes = totalMinutes,
    sleepScore = sleepScore,
    consistencyScore = consistencyScore,
    latencyMinutes = latencyMinutes,
    awakeMinutes = awakeMinutes,
    source = source,
)

fun DrowsinessEventEntity.toDomain(): DrowsinessEvent = DrowsinessEvent(
    id = id,
    timestamp = timestamp,
    severity = severity,
    durationMinutes = durationMinutes,
    label = label,
    deviceId = deviceId,
    sessionId = sessionId,
)

fun DrowsinessEvent.toEntity(): DrowsinessEventEntity = DrowsinessEventEntity(
    id = id,
    timestamp = timestamp,
    severity = severity,
    durationMinutes = durationMinutes,
    label = label,
    deviceId = deviceId,
    sessionId = sessionId,
)

fun StudySessionState.toEntity(): StudySessionEntity {
    val summary = latestSummary
    return StudySessionEntity(
        id = sessionId ?: error("sessionId is required to persist study session state"),
        startedAt = startedAt ?: LocalDateTime.now(),
        endedAt = summary?.receivedAt,
        phase = phase.name,
        latestRiskState = latestRisk?.state,
        latestFusedScore = latestRisk?.fusedScore,
        alertCount = summary?.totalAlerts ?: if (latestAlert != null) 1 else 0,
        summaryMode = summary?.mode,
        summaryReason = summary?.summaryReason,
        peakFusedScore = summary?.peakFusedScore,
    )
}

fun StudySessionEntity.toSummary(): PiSessionSummary? {
    if (endedAt == null) return null
    return PiSessionSummary(
        sessionId = id,
        sequence = -1L,
        finalState = phase,
        totalAlerts = alertCount,
        peakFusedScore = peakFusedScore,
        mode = summaryMode,
        summaryReason = summaryReason,
        receivedAt = endedAt,
    )
}

fun StudyPlanEntity.toDomain(): StudyPlan = StudyPlan(
    id = id,
    startTime = startTime,
    endTime = endTime,
    focusHours = focusHours,
    days = daysSerialized.split(",").filter { it.isNotBlank() }.map(DayOfWeek::valueOf).toSet(),
    breakPreferenceMinutes = breakPreferenceMinutes,
    autoBreakEnabled = autoBreakEnabled,
)

fun StudyPlan.toEntity(): StudyPlanEntity = StudyPlanEntity(
    id = id,
    startTime = startTime,
    endTime = endTime,
    focusHours = focusHours,
    daysSerialized = days.joinToString(",") { it.name },
    breakPreferenceMinutes = breakPreferenceMinutes,
    autoBreakEnabled = autoBreakEnabled,
)

fun ExamScheduleEntity.toDomain(): ExamSchedule = ExamSchedule(
    id = id,
    name = name,
    date = date,
    startTime = startTime,
    endTime = endTime,
    location = location,
    priority = priority,
    syncEnabled = syncEnabled,
)

fun ExamSchedule.toEntity(): ExamScheduleEntity = ExamScheduleEntity(
    id = id,
    name = name,
    date = date,
    startTime = startTime,
    endTime = endTime,
    location = location,
    priority = priority,
    syncEnabled = syncEnabled,
)

fun RecommendationSnapshotEntity.toDomain(): RecommendationSnapshot = RecommendationSnapshot(
    id = id,
    recommendedBedtime = recommendedBedtime,
    recommendedWakeTime = recommendedWakeTime,
    targetSleepMinutes = targetSleepMinutes,
    reason = reason,
    routineShiftMinutes = routineShiftMinutes,
    tips = tipsSerialized.split("||").filter { it.isNotBlank() }.map { encoded ->
        val parts = encoded.split("::")
        RecommendationTip(
            title = parts.getOrElse(0) { "" },
            body = parts.getOrElse(1) { "" },
            iconKey = parts.getOrElse(2) { "insights" },
        )
    },
    generatedAt = generatedAt,
)

fun RecommendationSnapshot.toEntity(): RecommendationSnapshotEntity = RecommendationSnapshotEntity(
    id = id,
    recommendedBedtime = recommendedBedtime,
    recommendedWakeTime = recommendedWakeTime,
    targetSleepMinutes = targetSleepMinutes,
    reason = reason,
    routineShiftMinutes = routineShiftMinutes,
    tipsSerialized = tips.joinToString("||") { "${it.title}::${it.body}::${it.iconKey}" },
    generatedAt = generatedAt,
)
