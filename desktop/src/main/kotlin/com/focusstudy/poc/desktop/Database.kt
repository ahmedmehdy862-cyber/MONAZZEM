package com.focusstudy.poc.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.sqlite.driver.JdbcSqliteDriver
import com.focusstudy.poc.desktop.db.MonazzemDatabase
import java.io.File

/**
 * معرّفات ويندوز (عقد المزامنة §7): معرّف رقمي ينشئه ويندوز يجب أن يكون
 * سالبًا (`-epochMillis`) حتى لا يصطدم أبدًا بمعرّف AUTOINCREMENT موجب
 * ينشئه أندرويد. سالب متناقص باطراد: `-now` يصغر مع مرور الزمن، وحارس
 * `lastIssued` يمنع التكرار داخل نفس المللي ثانية وعبر إعادة التشغيل.
 * معرّفات الأنواع النصية الجديدة: `w_<millis-base36>`.
 */
object WindowsIds {
    private var lastIssued = 0L

    @Synchronized
    fun next(): Long {
        val candidate = -System.currentTimeMillis()
        lastIssued = minOf(candidate, lastIssued - 1)
        return lastIssued
    }

    fun taskTypeId(): String = "w_" + System.currentTimeMillis().toString(36)
}

/**
 * فتح قاعدة البيانات المحلية وإنشاؤها عند أول تشغيل.
 *
 * الملف: %APPDATA%\Monazzem\monazzem.db على ويندوز (أو ~/.monazzem/monazzem.db
 * على غيره). المخطط الكامل من Monazzem.sq يُنشأ عبر Schema.create — كل
 * الجداول CREATE TABLE IF NOT EXISTS فالاستدعاء آمن عند كل تشغيل.
 */
object DesktopDatabase {
    fun open(dataDir: File): MonazzemDatabase {
        dataDir.mkdirs()
        val driver: SqlDriver =
            JdbcSqliteDriver("jdbc:sqlite:${File(dataDir, "monazzem.db").absolutePath}")
        MonazzemDatabase.Schema.create(driver)
        return MonazzemDatabase(driver)
    }
}

/**
 * مستودع رفيع فوق استعلامات SQLDelight المولّدة. يحاكي دوال StudyDb في :app
 * للعمليات التي تستخدمها واجهة ويندوز، بنفس القواعد (مثال: تسجيل الورد مرة
 * واحدة في اليوم، والمزاج 1..5، والصفحة بين 1 و604).
 *
 * ملاحظة عن الأرقام: أعمدة INTEGER في SQLite تصل كـ Long من SQLDelight،
 * لذا التحويل Long<->Int يتم هنا فقط، لا في الواجهة.
 */
class DesktopRepository(private val db: MonazzemDatabase) {
    private val q get() = db.monazzemQueries

    // ------------------------------------------------------------ مهام
    fun tasks(): List<DesktopTask> = q.selectTasks().executeAsList().map { row ->
        DesktopTask(
            id = row.id,
            title = row.title,
            plannedMinutes = row.planned_minutes.toInt(),
            doneMinutes = row.done_minutes.toInt(),
            isDone = row.is_done == 1L,
            createdAt = row.created_at,
            category = row.category,
            typeId = row.type_id,
            priority = row.priority.toInt(),
            status = row.status,
            dueAt = row.due_at,
            subjectId = row.subject_id,
            recurrence = row.recurrence,
            completedAt = row.completed_at,
        )
    }

    fun task(id: Long): DesktopTask? =
        q.selectTaskById(id).executeAsOneOrNull()?.let { row ->
            DesktopTask(
                id = row.id, title = row.title, plannedMinutes = row.planned_minutes.toInt(),
                doneMinutes = row.done_minutes.toInt(), isDone = row.is_done == 1L,
                createdAt = row.created_at, category = row.category, typeId = row.type_id,
                priority = row.priority.toInt(), status = row.status, dueAt = row.due_at,
                subjectId = row.subject_id, recurrence = row.recurrence,
                completedAt = row.completed_at,
            )
        }

    fun addTask(title: String, plannedMinutes: Int, category: String = "study"): Long {
        require(title.isNotBlank()) { "عنوان المهمة مطلوب" }
        require(plannedMinutes in 1..720) { "المدة بين 1 و 720 دقيقة" }
        val id = WindowsIds.next()
        q.insertTaskWithId(
            id = id,
            title = title.trim(),
            plannedMinutes = plannedMinutes.toLong(),
            createdAt = System.currentTimeMillis(),
            category = category,
            typeId = null,
            priority = 1L,
            dueAt = null,
            subjectId = null,
            recurrence = "none",
        )
        return id
    }

    fun setTaskDone(id: Long, done: Boolean) {
        q.setTaskDone(
            status = if (done) "done" else "todo",
            isDone = if (done) 1L else 0L,
            completedAt = if (done) System.currentTimeMillis() else null,
            id = id,
        )
    }

    fun deleteTask(id: Long) {
        q.deleteSubtasksForTask(id)
        q.deleteTask(id)
    }

    // ------------------------------------------- مواد وأنواع (للمزامنة)
    fun subjects(): List<DesktopSubject> =
        q.selectSubjects().executeAsList().map { DesktopSubject(it.id, it.name) }

    fun taskTypes(): List<DesktopTaskType> =
        q.selectTaskTypes().executeAsList().map { DesktopTaskType(it.id, it.name, it.archived == 1L) }

    /** كتابة سجل قادم من الخادم؛ الأعمدة المحلية فقط (الوصف والملاحظات) لا تُمس. */
    fun insertTaskFromSync(
        id: Long, title: String, minutes: Int, category: String, priority: Int,
        status: String, dueAt: Long?, recurrence: String, subjectId: Long?,
        typeId: String?, isDone: Boolean, createdAt: Long, completedAt: Long?,
    ) {
        q.insertTaskFromSync(id, title, minutes.toLong(), if (isDone) 1L else 0L, createdAt,
            category, typeId, priority.toLong(), status, dueAt, subjectId, recurrence, completedAt)
    }

    fun updateTaskFromSync(
        id: Long, title: String, minutes: Int, category: String, priority: Int,
        status: String, dueAt: Long?, recurrence: String, subjectId: Long?,
        typeId: String?, isDone: Boolean, completedAt: Long?,
    ) {
        q.updateTaskFromSync(title, minutes.toLong(), category, priority.toLong(), status,
            dueAt, recurrence, subjectId, typeId, if (isDone) 1L else 0L, completedAt, id)
    }

    fun insertSubjectFromSync(id: Long, name: String, createdAt: Long) {
        q.insertSubjectFromSync(id, name, createdAt)
    }

    fun updateSubjectFromSync(id: Long, name: String) = q.updateSubjectFromSync(name, id)

    fun deleteSubject(id: Long) = q.deleteSubject(id)

    fun insertTaskTypeFromSync(id: String, name: String, archived: Boolean) {
        q.insertTaskTypeFromSync(id, name, if (archived) 1L else 0L)
    }

    fun updateTaskTypeFromSync(id: String, name: String, archived: Boolean) {
        q.updateTaskTypeFromSync(name, if (archived) 1L else 0L, id)
    }

    fun deleteTaskType(id: String) = q.deleteTaskType(id)

    // ------------------------------------------------------------ عادات
    fun habits(): List<DesktopHabit> =
        q.selectHabits().executeAsList().map { DesktopHabit(it.id, it.name, it.created_at) }

    /** كل العادات بما فيها المؤرشفة — للمزامنة فقط؛ الواجهة تستخدم habits(). */
    fun allHabits(): List<DesktopHabit> =
        q.selectAllHabits().executeAsList().map {
            DesktopHabit(it.id, it.name, it.created_at, it.archived == 1L)
        }

    fun allHabitLogs(): List<HabitLogRow> =
        q.selectAllHabitLogs().executeAsList().map { HabitLogRow(it.habit_id, it.day) }

    fun allWirdLogs(): List<WirdLogRow> =
        q.selectAllWirdLogs().executeAsList().map { WirdLogRow(it.day, it.pages.toInt(), it.surah, it.logged_at) }

    fun allHealthLogs(): List<HealthLog> =
        q.selectAllHealthLogs().executeAsList().map {
            HealthLog(it.day, it.weight, it.sleep_hours, it.water_cups.toInt(), it.exercise_minutes.toInt())
        }

    fun allTransactions(): List<MoneyTransaction> =
        q.selectAllTransactions().executeAsList().map {
            MoneyTransaction(it.id, it.kind, it.amount, it.category, it.note, it.day, it.created_at)
        }

    fun allMoodLogs(): List<MoodLog> =
        q.selectAllMoodLogs().executeAsList().map { MoodLog(it.day, it.mood.toInt()) }

    fun allCalorieLogs(): List<CalorieEntry> =
        q.selectAllCalorieLogs().executeAsList().map {
            CalorieEntry(it.id, it.day, it.name, it.calories.toInt(), it.created_at)
        }

    fun transactionById(id: Long): MoneyTransaction? =
        q.selectTransactionById(id).executeAsOneOrNull()?.let {
            MoneyTransaction(it.id, it.kind, it.amount, it.category, it.note, it.day, it.created_at)
        }

    fun calorieEntryById(id: Long): CalorieEntry? =
        q.selectCalorieEntryById(id).executeAsOneOrNull()?.let {
            CalorieEntry(it.id, it.day, it.name, it.calories.toInt(), it.created_at)
        }

    fun addHabit(name: String): Long {
        require(name.isNotBlank()) { "اسم العادة مطلوب" }
        val id = WindowsIds.next()
        q.insertHabitWithId(id, name.trim(), System.currentTimeMillis())
        return id
    }

    fun deleteHabit(id: Long) {
        q.deleteHabitLogs(id)
        q.deleteHabit(id)
    }

    fun habitDoneDays(habitId: Long, sinceDay: String): Set<String> =
        q.habitDoneDays(habitId, sinceDay).executeAsList().toSet()

    fun setHabitDone(habitId: Long, day: String, done: Boolean) {
        if (done) q.setHabitDone(habitId, day) else q.clearHabitDone(habitId, day)
    }

    // -------------------------------------------------------------- ورد
    fun wirdSettings(): WirdSettings =
        q.selectWirdSettings().executeAsOneOrNull()?.let {
            WirdSettings(
                surah = it.surah.ifBlank { "البقرة" },
                startPage = it.start_page.toInt().coerceAtLeast(1),
                pagesPerDay = it.pages_per_day.toInt().coerceIn(1, 30),
            )
        } ?: WirdSettings()

    fun saveWirdSettings(settings: WirdSettings) {
        require(settings.surah.isNotBlank()) { "اسم السورة مطلوب" }
        require(settings.startPage >= 1) { "صفحة البداية يجب أن تكون 1 أو أكثر" }
        require(settings.pagesPerDay in 1..30) { "عدد صفحات اليوم بين 1 و 30" }
        // تغيير الإعدادات وحده لا يمسح سجل الورد أبدًا — نفس قاعدة أندرويد (M1).
        q.saveWirdSettings(settings.surah.trim(), settings.startPage.toLong(), settings.pagesPerDay.toLong())
    }

    /** false إن كان اليوم مسجّلًا بالفعل فلا يتضاعف العد — نفس قاعدة أندرويد. */
    fun logWird(day: String, pages: Int, surah: String): Boolean {
        if (pages <= 0) return false
        if (wirdLogged(day)) return false
        q.logWird(day, pages.toLong(), surah, System.currentTimeMillis())
        return true
    }

    fun wirdLogged(day: String): Boolean = q.countWirdForDay(day).executeAsOne() > 0L

    fun wirdTotalPages(): Int = q.wirdTotalPages().executeAsOne().toInt()

    // -------------------------------------------------------------- صحة
    fun healthLog(day: String): HealthLog? =
        q.selectHealthLog(day).executeAsOneOrNull()?.let {
            HealthLog(
                day = it.day, weight = it.weight, sleepHours = it.sleep_hours,
                waterCups = it.water_cups.toInt(), exerciseMinutes = it.exercise_minutes.toInt(),
            )
        }

    fun saveHealthLog(log: HealthLog) {
        q.saveHealthLog(log.day, log.weight, log.sleepHours, log.waterCups.toLong(), log.exerciseMinutes.toLong())
    }

    fun calorieEntries(day: String): List<CalorieEntry> =
        q.selectCalorieEntries(day).executeAsList().map {
            CalorieEntry(it.id, it.day, it.name, it.calories.toInt(), it.created_at)
        }

    fun addCalorieEntry(name: String, calories: Int, day: String): Long {
        require(calories > 0) { "السعرات يجب أن تكون أكبر من صفر" }
        val id = WindowsIds.next()
        q.insertCalorieEntryWithId(id, day, name.trim(), calories.toLong(), System.currentTimeMillis())
        return id
    }

    fun deleteCalorieEntry(id: Long) = q.deleteCalorieEntry(id)

    // -------------------------------------------------------------- مال
    fun addTransaction(kind: String, amount: Double, category: String, note: String, day: String): Long {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        require(kind == "income" || kind == "expense") { "نوع المعاملة غير معروف" }
        val id = WindowsIds.next()
        q.insertTransactionWithId(id, kind, amount, category.trim(), note.trim(), day, System.currentTimeMillis())
        return id
    }

    fun deleteTransaction(id: Long) = q.deleteTransaction(id)

    fun transactionsForMonth(month: String): List<MoneyTransaction> =
        q.selectTransactionsForMonth("$month%").executeAsList().map {
            MoneyTransaction(it.id, it.kind, it.amount, it.category, it.note, it.day, it.created_at)
        }

    // -------------------------------------------------------------- مزاج
    fun logMood(day: String, mood: Int) {
        q.logMood(day, mood.coerceIn(1, 5).toLong(), System.currentTimeMillis())
    }

    fun moodFor(day: String): Int? = q.moodForDay(day).executeAsOneOrNull()?.toInt()

    fun moodLogsSince(sinceDay: String): List<MoodLog> =
        q.moodLogsSince(sinceDay).executeAsList().map { MoodLog(it.day, it.mood.toInt()) }

    // ------------------------------------------------- جلسات تركيز محلية
    /** taskId = 0 لجلسة بلا مهمة مرتبطة. state: "completed" أو "cancelled". */
    fun logFocusSession(taskId: Long, startedAt: Long, endsAt: Long, state: String) {
        q.insertSession(taskId, startedAt, endsAt, state)
    }

    // --------------------------------------------------------- sync_meta
    fun syncMeta(key: String): String? = q.selectSyncMeta(key).executeAsOneOrNull()

    fun putSyncMeta(key: String, value: String) = q.putSyncMeta(key, value)

    // ------------------------------------------- تطبيق سجلات واردة (pull)
    fun insertHabitFromSync(id: Long, name: String, createdAt: Long, archived: Boolean) {
        q.insertHabitFromSync(id, name, createdAt, if (archived) 1L else 0L)
    }

    fun updateHabitFromSync(id: Long, name: String, archived: Boolean) {
        q.updateHabitFromSync(name, if (archived) 1L else 0L, id)
    }

    fun insertHabitLogFromSync(habitId: Long, day: String) = q.insertHabitLogFromSync(habitId, day)

    fun deleteHabitLog(habitId: Long, day: String) = q.deleteHabitLog(habitId, day)

    fun upsertWirdFromSync(day: String, pages: Int, surah: String) {
        q.upsertWirdFromSync(day, pages.toLong(), surah, System.currentTimeMillis())
    }

    fun deleteWirdLog(day: String) = q.deleteWirdLog(day)

    fun upsertHealthFromSync(log: HealthLog) {
        q.upsertHealthFromSync(log.day, log.weight, log.sleepHours, log.waterCups.toLong(), log.exerciseMinutes.toLong())
    }

    fun deleteHealthLog(day: String) = q.deleteHealthLog(day)

    fun insertTransactionFromSync(tx: MoneyTransaction) {
        q.insertTransactionWithId(tx.id, tx.kind, tx.amount, tx.category, tx.note, tx.day, tx.createdAt)
    }

    fun upsertMoodFromSync(day: String, mood: Int) {
        q.upsertMoodFromSync(day, mood.toLong(), System.currentTimeMillis())
    }

    fun deleteMoodLog(day: String) = q.deleteMoodLog(day)

    fun insertCalorieFromSync(entry: CalorieEntry) {
        q.insertCalorieEntryWithId(entry.id, entry.day, entry.name, entry.calories.toLong(), entry.createdAt)
    }
}
