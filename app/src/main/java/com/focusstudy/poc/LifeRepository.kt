package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entry point for the life tracker «حياتي»; screens never touch [StudyDb] directly. */
class LifeRepository private constructor(private val db: StudyDb) {

    suspend fun habits(): List<Habit> = io { db.habits() }

    suspend fun addHabit(name: String): Long = io { db.addHabit(name) }

    suspend fun deleteHabit(habitId: Long) = io { db.deleteHabit(habitId) }

    suspend fun habitDoneDays(habitId: Long, sinceDay: String): Set<String> =
        io { db.habitDoneDays(habitId, sinceDay) }

    suspend fun setHabitDone(habitId: Long, day: String, done: Boolean) =
        io { db.setHabitDone(habitId, day, done) }

    suspend fun wirdSettings(): WirdSettings = io { db.wirdSettings() }

    suspend fun saveWirdSettings(settings: WirdSettings) = io { db.saveWirdSettings(settings) }

    /** يصفّر عدّاد الورد لبدء ختمة جديدة (انظر [StudyDb.resetWirdLogs]). */
    suspend fun resetWird() = io { db.resetWirdLogs() }

    suspend fun logWird(day: String, pages: Int, surah: String): Boolean =
        io { db.logWird(day, pages, surah) }

    suspend fun wirdLoggedToday(day: String): Boolean = io { db.wirdLoggedToday(day) }

    suspend fun wirdTotalPages(): Int = io { db.wirdTotalPages() }

    suspend fun healthLog(day: String): HealthLog? = io { db.healthLog(day) }

    suspend fun saveHealthLog(log: HealthLog) = io { db.saveHealthLog(log) }

    suspend fun addTransaction(
        kind: MoneyKind,
        amount: Double,
        category: String,
        note: String,
        day: String
    ): Long = io { db.addTransaction(kind, amount, category, note, day) }

    suspend fun deleteTransaction(transactionId: Long) = io { db.deleteTransaction(transactionId) }

    suspend fun transactionsForMonth(monthPrefix: String): List<MoneyTransaction> =
        io { db.transactionsForMonth(monthPrefix) }

    suspend fun logMood(day: String, mood: Int) = io { db.logMood(day, mood) }

    suspend fun moodFor(day: String): Int? = io { db.moodFor(day) }

    suspend fun moodLogsSince(sinceDay: String): List<MoodLog> = io { db.moodLogsSince(sinceDay) }

    suspend fun fitnessProfile(): FitnessProfile = io { db.fitnessProfile() }

    suspend fun saveFitnessProfile(profile: FitnessProfile) = io { db.saveFitnessProfile(profile) }

    suspend fun calorieEntries(day: String): List<CalorieEntry> = io { db.calorieEntries(day) }

    suspend fun addCalorieEntry(name: String, calories: Int, day: String): Long =
        io { db.addCalorieEntry(name, calories, day) }

    suspend fun deleteCalorieEntry(entryId: Long) = io { db.deleteCalorieEntry(entryId) }

    suspend fun exerciseLogsSince(sinceDay: String): List<ExerciseDone> =
        io { db.exerciseLogsSince(sinceDay) }

    suspend fun setExerciseDone(exerciseId: String, day: String, done: Boolean) =
        io { db.setExerciseDone(exerciseId, day, done) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        @Volatile
        private var instance: LifeRepository? = null

        fun get(context: Context): LifeRepository =
            instance ?: synchronized(this) {
                instance ?: LifeRepository(StudyDb.get(context)).also { instance = it }
            }
    }
}
