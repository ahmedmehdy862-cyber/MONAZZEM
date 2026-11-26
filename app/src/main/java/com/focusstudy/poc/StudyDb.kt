package com.focusstudy.poc

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SessionRow(
    val id: Long,
    val taskId: Long,
    val startedAt: Long,
    val endsAt: Long,
    val blockedPackages: Set<String>,
    val taskTitle: String
)

/**
 * Local persistence for tasks, subtasks and focus sessions.
 *
 * Implemented on SQLiteOpenHelper so the project builds without annotation processors.
 * Schema history:
 *   v1 — tasks(id, title, planned_minutes, done_minutes, is_done, blocked_packages, created_at)
 *        sessions(id, task_id, started_at, ends_at, blocked_packages, state)
 *   v2 — rich task fields (description, category, priority, status, dates, notes, recurrence,
 *        subject, sort_order, completed_at) and the subtasks table.
 *   v14 — notes(id, title, body, category, pinned, created_at, updated_at) for the private «نوتة».
 *   v15 — life tracking: habits, habit_logs, wird_settings, wird_logs, health_logs,
 *        transactions, mood_logs.
 *   v16 — fitness: fitness_profile, calorie_logs, exercise_logs.
 *   v17 — user_profile (single-row display name, migrated once from preferences)
 *        and support_messages (local copy of every support/suggestion message with
 *        its send status and the private copies of its attached images).
 *
 * Every upgrade is additive: no column or table is dropped, so existing data survives.
 */
class StudyDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext: Context = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              planned_minutes INTEGER NOT NULL,
              done_minutes INTEGER NOT NULL DEFAULT 0,
              is_done INTEGER NOT NULL DEFAULT 0,
              blocked_packages TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sessions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id INTEGER NOT NULL,
              started_at INTEGER NOT NULL,
              ends_at INTEGER NOT NULL,
              blocked_packages TEXT NOT NULL DEFAULT '',
              state TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sessions_state ON sessions(state)")
        db.execSQL("CREATE INDEX idx_tasks_created ON tasks(created_at)")
        migrateToV2(db)
        migrateToV3(db)
        migrateToV4(db)
        migrateToV5(db)
        migrateToV6(db)
        migrateToV7(db)
        migrateToV8(db)
        migrateToV9(db)
        migrateToV10(db)
        migrateToV11(db)
        migrateToV12(db)
        migrateToV13(db)
        migrateToV14(db)
        migrateToV15(db)
        migrateToV16(db)
        migrateToV17(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
        if (oldVersion < 3) migrateToV3(db)
        if (oldVersion < 4) migrateToV4(db)
        if (oldVersion < 5) migrateToV5(db)
        if (oldVersion < 6) migrateToV6(db)
        if (oldVersion < 7) migrateToV7(db)
        if (oldVersion < 8) migrateToV8(db)
        if (oldVersion < 9) migrateToV9(db)
        if (oldVersion < 10) migrateToV10(db)
        if (oldVersion < 11) migrateToV11(db)
        if (oldVersion < 12) migrateToV12(db)
        if (oldVersion < 13) migrateToV13(db)
        if (oldVersion < 14) migrateToV14(db)
        if (oldVersion < 15) migrateToV15(db)
        if (oldVersion < 16) migrateToV16(db)
        if (oldVersion < 17) migrateToV17(db)
    }

    private fun migrateToV4(db: SQLiteDatabase) {
        addColumnIfMissing(db, "tasks", "original_due_at", "INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(due_at, status)")
    }

    private fun migrateToV5(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              kind TEXT NOT NULL DEFAULT 'study',
              subject_id INTEGER,
              task_id INTEGER,
              starts_at INTEGER NOT NULL,
              ends_at INTEGER NOT NULL,
              notes TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_range ON events(starts_at, ends_at)")
    }

    private fun migrateToV6(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE tasks
            SET status = CASE WHEN is_done = 1 OR status = 'done' THEN 'done' ELSE status END,
                is_done = CASE WHEN is_done = 1 OR status = 'done' THEN 1 ELSE 0 END
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_subject ON tasks(subject_id)")
    }

    /** v7 - user-editable task types, additive: existing tasks keep working unchanged. */
    private fun migrateToV7(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS task_types(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              sort_order INTEGER NOT NULL DEFAULT 0,
              archived INTEGER NOT NULL DEFAULT 0,
              built_in INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        addColumnIfMissing(db, "tasks", "type_id", "TEXT")
        seedTaskTypes(db)
        db.execSQL(
            """
            UPDATE tasks SET type_id = CASE category
                WHEN 'study' THEN 'study'
                WHEN 'university' THEN 'study'
                ELSE 'life' END
            WHERE type_id IS NULL
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_type ON tasks(type_id)")
    }

    private fun seedTaskTypes(db: SQLiteDatabase) {
        val builtIns = listOf(
            "religious" to "ديني",
            "study" to "دراسة",
            "life" to "حياة",
            "volunteering" to "تطوّع"
        )
        builtIns.forEachIndexed { index, (id, name) ->
            db.execSQL(
                "INSERT OR IGNORE INTO task_types(id, name, sort_order, archived, built_in) VALUES(?, ?, ?, 0, 1)",
                arrayOf<Any?>(id, name, index)
            )
        }
    }

    // -------------------------------------------------------------- task types

    fun goals(): List<Goal> = readableDatabase.rawQuery(
        "SELECT id, title, target, current, unit, category, due_at, status, created_at " +
            "FROM goals ORDER BY (status = 'done'), COALESCE(due_at, 9223372036854775807), id DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Goal(
                        id = cursor.getLong(0),
                        title = cursor.getString(1).orEmpty(),
                        target = cursor.getInt(2),
                        current = cursor.getInt(3),
                        unit = cursor.getString(4).orEmpty(),
                        category = cursor.getString(5).orEmpty(),
                        dueAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                        status = cursor.getString(7).orEmpty(),
                        createdAt = cursor.getLong(8)
                    )
                )
            }
        }
    }

    fun addGoal(title: String, target: Int, unit: String, dueAt: Long?, category: String): Long {
        require(title.isNotBlank()) { "Goal title cannot be blank" }
        require(target > 0) { "Goal target must be positive" }
        val values = ContentValues().apply {
            put("title", title.trim())
            put("target", target)
            put("current", 0)
            put("unit", unit.trim().ifBlank { "خطوة" })
            put("category", category.trim())
            if (dueAt != null) put("due_at", dueAt) else putNull("due_at")
            put("status", "active")
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("goals", null, values)
    }

    fun setGoalProgress(goalId: Long, current: Int) {
        val database = writableDatabase
        val target = database.rawQuery("SELECT target FROM goals WHERE id = ?", arrayOf(goalId.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val clamped = current.coerceIn(0, if (target > 0) target else current.coerceAtLeast(0))
        database.execSQL(
            "UPDATE goals SET current = ?, status = CASE WHEN ? >= target THEN 'done' ELSE status END WHERE id = ?",
            arrayOf<Any>(clamped, clamped, goalId)
        )
    }

    fun addGoalProgress(goalId: Long, delta: Int) {
        val database = writableDatabase
        database.rawQuery("SELECT current, target FROM goals WHERE id = ?", arrayOf(goalId.toString()))
            .use { cursor ->
                if (cursor.moveToFirst()) setGoalProgress(goalId, cursor.getInt(0) + delta)
            }
    }

    fun setGoalStatus(goalId: Long, status: String) {
        writableDatabase.execSQL(
            "UPDATE goals SET status = ? WHERE id = ?",
            arrayOf<Any>(status, goalId)
        )
    }

    fun deleteGoal(goalId: Long) {
        writableDatabase.delete("goals", "id = ?", arrayOf(goalId.toString()))
        writableDatabase.delete("goal_links", "goal_id = ?", arrayOf(goalId.toString()))
    }

    /** Edit what a goal measures, its deadline and its label without losing progress. */
    fun updateGoal(goalId: Long, title: String, target: Int, unit: String, category: String, dueAt: Long?) {
        require(title.isNotBlank()) { "Goal title cannot be blank" }
        require(target > 0) { "Goal target must be positive" }
        val values = ContentValues().apply {
            put("title", title.trim())
            put("target", target)
            put("unit", unit.trim().ifBlank { "خطوة" })
            put("category", category.trim())
            if (dueAt != null) put("due_at", dueAt) else putNull("due_at")
        }
        writableDatabase.update("goals", values, "id = ?", arrayOf(goalId.toString()))
    }

    /** Links a task or a skill to a goal; a task that is already done counts immediately. */
    fun linkGoal(goalId: Long, kind: String, refId: Long) {
        if (kind != "task" && kind != "skill") return
        val done = kind == "task" && (task(refId)?.let { it.isDone || it.status == TaskStatus.DONE } ?: false)
        writableDatabase.insertWithOnConflict(
            "goal_links",
            null,
            ContentValues().apply {
                put("goal_id", goalId)
                put("kind", kind)
                put("ref_id", refId)
                put("awarded", 0)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (done) awardGoalLinksForTask(refId)
    }

    fun unlinkGoal(goalId: Long, kind: String, refId: Long) {
        writableDatabase.delete(
            "goal_links",
            "goal_id = ? AND kind = ? AND ref_id = ?",
            arrayOf(goalId.toString(), kind, refId.toString())
        )
    }

    fun goalLinks(goalId: Long): List<GoalLink> = readableDatabase.rawQuery(
        "SELECT kind, ref_id FROM goal_links WHERE goal_id = ? ORDER BY created_at ASC",
        arrayOf(goalId.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(GoalLink(cursor.getString(0).orEmpty(), cursor.getLong(1)))
        }
    }

    /** One step per newly linked, completed task — called on every transition to done. */
    fun awardGoalLinksForTask(taskId: Long) {
        val db = writableDatabase
        val goalIds = db.rawQuery(
            "SELECT goal_id FROM goal_links WHERE kind = 'task' AND ref_id = ? AND awarded = 0",
            arrayOf(taskId.toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        goalIds.forEach { goalId ->
            addGoalProgress(goalId, 1)
            db.execSQL(
                "UPDATE goal_links SET awarded = 1 WHERE goal_id = ? AND kind = 'task' AND ref_id = ?",
                arrayOf<Any>(goalId, taskId)
            )
        }
    }

    fun skills(): List<Skill> = readableDatabase.rawQuery(
        "SELECT id, name, category, level, target_level, minutes, last_practiced_at, created_at " +
            "FROM skills ORDER BY name COLLATE NOCASE",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Skill(
                        id = cursor.getLong(0),
                        name = cursor.getString(1).orEmpty(),
                        category = cursor.getString(2).orEmpty(),
                        level = cursor.getInt(3),
                        targetLevel = cursor.getInt(4),
                        minutes = cursor.getInt(5),
                        lastPracticedAt = cursor.getLong(6),
                        createdAt = cursor.getLong(7)
                    )
                )
            }
        }
    }

    fun addSkill(name: String, category: String = "", targetLevel: Int = 3): Long {
        require(name.isNotBlank()) { "Skill name cannot be blank" }
        val values = ContentValues().apply {
            put("name", name.trim())
            put("category", category.trim())
            put("level", 1)
            put("target_level", targetLevel.coerceIn(1, 5))
            put("minutes", 0)
            put("last_practiced_at", 0L)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("skills", null, values)
    }

    fun logSkillPractice(skillId: Long, minutes: Int) {
        if (minutes <= 0) return
        writableDatabase.execSQL(
            "UPDATE skills SET minutes = minutes + ?, last_practiced_at = ? WHERE id = ?",
            arrayOf<Any>(minutes, System.currentTimeMillis(), skillId)
        )
    }

    fun setSkillLevel(skillId: Long, level: Int) {
        writableDatabase.execSQL(
            "UPDATE skills SET level = ? WHERE id = ?",
            arrayOf<Any>(level.coerceIn(1, 5), skillId)
        )
    }

    fun deleteSkill(skillId: Long) {
        writableDatabase.delete("skills", "id = ?", arrayOf(skillId.toString()))
    }

    // --- Notebook «نوتة» ---------------------------------------------------------------
    // Notes are stored only here, in the app's private SQLite file. No method in this class
    // sends them anywhere, and the notebook repository performs no network calls.

    fun notes(): List<Note> = readableDatabase.rawQuery(
        "SELECT id, title, body, category, pinned, created_at, updated_at FROM notes " +
            "ORDER BY pinned DESC, updated_at DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Note(
                        id = cursor.getLong(0),
                        title = cursor.getString(1).orEmpty(),
                        body = cursor.getString(2).orEmpty(),
                        category = cursor.getString(3).orEmpty(),
                        pinned = cursor.getInt(4) == 1,
                        createdAt = cursor.getLong(5),
                        updatedAt = cursor.getLong(6)
                    )
                )
            }
        }
    }

    fun addNote(title: String, body: String, category: String): Long {
        require(body.isNotBlank() || title.isNotBlank()) { "الملاحظة فارغة" }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", NotesText.title(title))
            put("body", NotesText.body(body))
            put("category", NotesText.category(category))
            put("pinned", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insert("notes", null, values)
    }

    fun updateNote(id: Long, title: String, body: String, category: String) {
        require(body.isNotBlank() || title.isNotBlank()) { "الملاحظة فارغة" }
        val values = ContentValues().apply {
            put("title", NotesText.title(title))
            put("body", NotesText.body(body))
            put("category", NotesText.category(category))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("notes", values, "id = ?", arrayOf(id.toString()))
    }

    fun setNotePinned(id: Long, pinned: Boolean) {
        val values = ContentValues().apply {
            put("pinned", if (pinned) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("notes", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    fun taskTypes(includeArchived: Boolean = false): List<TaskType> {
        val rows = ArrayList<TaskType>()
        val where = if (includeArchived) "" else "WHERE archived = 0"
        readableDatabase.rawQuery(
            "SELECT id, name, sort_order, archived, built_in FROM task_types $where " +
                "ORDER BY sort_order ASC, name COLLATE NOCASE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    TaskType(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        sortOrder = cursor.getInt(2),
                        archived = cursor.getInt(3) == 1,
                        builtIn = cursor.getInt(4) == 1
                    )
                )
            }
        }
        return rows
    }

    fun addTaskType(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "اسم النوع مطلوب" }
        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM task_types WHERE name = ? COLLATE NOCASE LIMIT 1",
            arrayOf(clean)
        ).use { it.moveToFirst() }
        require(!exists) { "هذا النوع موجود بالفعل" }
        val id = "c_" + System.currentTimeMillis().toString(36)
        val order = readableDatabase
            .rawQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM task_types", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 1 }
        writableDatabase.insertOrThrow("task_types", null, ContentValues().apply {
            put("id", id)
            put("name", clean)
            put("sort_order", order)
            put("archived", 0)
            put("built_in", 0)
        })
        return id
    }

    fun renameTaskType(id: String, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "اسم النوع مطلوب" }
        writableDatabase.update(
            "task_types",
            ContentValues().apply { put("name", clean) },
            "id = ?",
            arrayOf(id)
        )
    }

    fun setTaskTypeArchived(id: String, archived: Boolean) {
        writableDatabase.update(
            "task_types",
            ContentValues().apply { put("archived", if (archived) 1 else 0) },
            "id = ?",
            arrayOf(id)
        )
    }

    /** Swaps the type with its neighbour and renumbers the list so the order stays dense. */
    fun moveTaskType(id: String, up: Boolean) {
        val ordered = taskTypes(includeArchived = true)
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return
        val swapWith = if (up) index - 1 else index + 1
        if (swapWith !in ordered.indices) return
        val reordered = ordered.toMutableList().apply {
            val moved = removeAt(index)
            add(swapWith, moved)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            reordered.forEachIndexed { position, type ->
                db.execSQL(
                    "UPDATE task_types SET sort_order = ? WHERE id = ?",
                    arrayOf<Any?>(position, type.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes a custom type; its tasks stay, they simply lose the label. */
    fun deleteTaskType(id: String) {
        val builtIn = readableDatabase.rawQuery(
            "SELECT built_in FROM task_types WHERE id = ? LIMIT 1",
            arrayOf(id)
        ).use { if (it.moveToFirst()) it.getInt(0) == 1 else false }
        require(!builtIn) { "لا يمكن حذف نوع أساسي" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE tasks SET type_id = NULL WHERE type_id = ?", arrayOf<Any?>(id))
            db.delete("task_types", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * v8 - reminder engine state, additive.
     *
     * `reminders` keeps the alarm schedule per task so it can be rebuilt after a reboot;
     * `notification_log` records delivery and the user's action for the adaptive policy.
     */
    /** v12 — long-term goals with a numeric measure and a deadline. */
    private fun migrateToV12(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS goals(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              target INTEGER NOT NULL,
              current INTEGER NOT NULL DEFAULT 0,
              unit TEXT NOT NULL DEFAULT 'خطوة',
              category TEXT NOT NULL DEFAULT '',
              due_at INTEGER,
              status TEXT NOT NULL DEFAULT 'active',
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * v13 — the goal path: which tasks and which skills serve a goal.
     *
     * `awarded` makes the link advance the goal exactly once, so reopening and completing a task
     * again never double-counts. Links cascade away with the goal, the task or the skill.
     */
    private fun migrateToV13(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS goal_links(
              goal_id INTEGER NOT NULL,
              kind TEXT NOT NULL,
              ref_id INTEGER NOT NULL,
              awarded INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              PRIMARY KEY(goal_id, kind, ref_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_links_goal ON goal_links(goal_id, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_links_ref ON goal_links(kind, ref_id)")
    }

    /** v14 — the private notebook «نوتة». Additive: every existing table is left untouched. */
    private fun migrateToV14(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL DEFAULT '',
              body TEXT NOT NULL DEFAULT '',
              category TEXT NOT NULL DEFAULT '',
              pinned INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_order ON notes(pinned DESC, updated_at DESC)")
    }

    /** v11 — skills the person is developing, independent of tasks and sessions. */
    private fun migrateToV11(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skills(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              category TEXT NOT NULL DEFAULT '',
              level INTEGER NOT NULL DEFAULT 1,
              target_level INTEGER NOT NULL DEFAULT 3,
              minutes INTEGER NOT NULL DEFAULT 0,
              last_practiced_at INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun migrateToV8(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reminders(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id INTEGER NOT NULL,
              kind TEXT NOT NULL,
              fire_at INTEGER NOT NULL,
              state TEXT NOT NULL DEFAULT 'pending',
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_log(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              kind TEXT NOT NULL,
              task_id INTEGER,
              scheduled_at INTEGER NOT NULL,
              delivered_at INTEGER,
              action TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reminders_fire ON reminders(state, fire_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notification_log_time ON notification_log(delivered_at)")
    }

    private fun migrateToV3(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semesters(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              university TEXT NOT NULL DEFAULT '',
              year_label TEXT NOT NULL DEFAULT '',
              starts_on INTEGER,
              ends_on INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subjects(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              semester_id INTEGER,
              name TEXT NOT NULL,
              color INTEGER NOT NULL DEFAULT 4281558682,
              icon TEXT NOT NULL DEFAULT 'book',
              instructor TEXT NOT NULL DEFAULT '',
              goal_minutes INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exams(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              subject_id INTEGER NOT NULL,
              title TEXT NOT NULL,
              at INTEGER NOT NULL,
              kind TEXT NOT NULL DEFAULT 'exam'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_subjects_semester ON subjects(semester_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exams_subject_at ON exams(subject_id, at)")
    }

    private fun migrateToV2(db: SQLiteDatabase) {
        addColumnIfMissing(db, "tasks", "description", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(db, "tasks", "category", "TEXT NOT NULL DEFAULT 'study'")
        addColumnIfMissing(db, "tasks", "priority", "INTEGER NOT NULL DEFAULT 1")
        addColumnIfMissing(db, "tasks", "status", "TEXT NOT NULL DEFAULT 'todo'")
        addColumnIfMissing(db, "tasks", "start_at", "INTEGER")
        addColumnIfMissing(db, "tasks", "due_at", "INTEGER")
        addColumnIfMissing(db, "tasks", "subject_id", "INTEGER")
        addColumnIfMissing(db, "tasks", "notes", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(db, "tasks", "recurrence", "TEXT NOT NULL DEFAULT 'none'")
        addColumnIfMissing(db, "tasks", "completed_at", "INTEGER")
        addColumnIfMissing(db, "tasks", "sort_order", "INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subtasks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id INTEGER NOT NULL,
              title TEXT NOT NULL,
              is_done INTEGER NOT NULL DEFAULT 0,
              sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_subtasks_task ON subtasks(task_id)")
    }

    /**
     * v9 - local consent ledger, additive.
     *
     * Android keeps its own permission grants across updates, but the app cannot tell
     * "لم يُمنح قط" from "كان ممنوحًا وأوقفه النظام" without remembering it. This table
     * lives only on this phone; nothing here is uploaded.
     */
    private fun migrateToV9(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS consents(
              key TEXT PRIMARY KEY,
              granted INTEGER NOT NULL,
              version INTEGER NOT NULL,
              at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Kept after revocation: without it the app cannot tell "لم يُمنح قط" from "أوقفه النظام".
        addColumnIfMissing(db, "consents", "last_granted_at", "INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * v10 - the consents table learned to remember that a permission was granted once.
     *
     * 0.16.3 shipped v9 without `last_granted_at`, so installs already on v9 need this
     * separate step; folding the column into migrateToV9 alone would never run for them.
     */
    private fun migrateToV10(db: SQLiteDatabase) {
        addColumnIfMissing(db, "consents", "last_granted_at", "INTEGER NOT NULL DEFAULT 0")
    }

    data class StoredConsent(
        val key: String,
        val granted: Boolean,
        val version: Int,
        val at: Long,
        val lastGrantedAt: Long
    )

    /** Upserts one permission state; the row keeps the last time it was seen in that state. */
    fun saveConsent(key: String, granted: Boolean, version: Int, at: Long, lastGrantedAt: Long) {
        writableDatabase.execSQL(
            "INSERT INTO consents(key, granted, version, at, last_granted_at) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(key) DO UPDATE SET granted=excluded.granted, version=excluded.version, " +
                "at=excluded.at, last_granted_at=excluded.last_granted_at",
            arrayOf(key, if (granted) 1 else 0, version, at, lastGrantedAt)
        )
    }

    fun consents(): List<StoredConsent> {
        val out = mutableListOf<StoredConsent>()
        readableDatabase.rawQuery(
            "SELECT key, granted, version, at, last_granted_at FROM consents",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += StoredConsent(
                    key = cursor.getString(0),
                    granted = cursor.getInt(1) == 1,
                    version = cursor.getInt(2),
                    at = cursor.getLong(3),
                    lastGrantedAt = cursor.getLong(4)
                )
            }
        }
        return out
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        var exists = false
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    // ---------------------------------------------------------------- tasks

    // ------------------------------------------------------------- reminders

    /**
     * Replaces the computed schedule of a task but keeps persisted deferrals. A deferral lives in
     * the same table with an encoded kind, so it survives a task refresh, reboot and force-stop.
     */
    fun replaceReminders(taskId: Long, schedule: List<Pair<String, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("reminders", "task_id = ? AND kind NOT LIKE 'deferred|%'", arrayOf(taskId.toString()))
            val now = System.currentTimeMillis()
            for ((kind, fireAt) in schedule) {
                db.execSQL(
                    "INSERT INTO reminders(task_id, kind, fire_at, state, created_at) VALUES(?, ?, ?, 'pending', ?)",
                    arrayOf<Any?>(taskId, kind, fireAt, now)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Inserts one persisted deferral/retry and returns its row id for a unique alarm code. */
    fun insertReminder(taskId: Long, encodedKind: String, fireAt: Long): Long {
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("kind", encodedKind)
            put("fire_at", fireAt)
            put("state", "pending")
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("reminders", null, values)
    }

    /** Replaces only the old deferral of the same reminder kind, leaving other deferrals alone. */
    fun clearDeferrals(taskId: Long, sourceKind: String) {
        writableDatabase.delete(
            "reminders",
            "task_id = ? AND kind LIKE ?",
            arrayOf(taskId.toString(), "deferred|%|$sourceKind")
        )
    }

    fun pendingReminders(limit: Int = 64): List<StoredReminder> {
        val rows = ArrayList<StoredReminder>()
        readableDatabase.rawQuery(
            "SELECT id, task_id, kind, fire_at FROM reminders WHERE state = 'pending' ORDER BY fire_at ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += StoredReminder(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getLong(3))
            }
        }
        return rows
    }

    /** True only while the row is still pending; used to ignore alarms left over from older schedules. */
    fun reminderIsPending(reminderId: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM reminders WHERE id = ? AND state = 'pending' LIMIT 1",
            arrayOf(reminderId.toString())
        ).use { it.moveToFirst() }

    fun markReminderDone(reminderId: Long, state: String = "fired") {
        writableDatabase.execSQL("UPDATE reminders SET state = ? WHERE id = ?", arrayOf<Any?>(state, reminderId))
    }

    fun dropRemindersForTask(taskId: Long) {
        writableDatabase.delete("reminders", "task_id = ?", arrayOf(taskId.toString()))
    }

    fun logNotification(kind: String, taskId: Long?, scheduledAt: Long, deliveredAt: Long?, action: String) {
        writableDatabase.execSQL(
            "INSERT INTO notification_log(kind, task_id, scheduled_at, delivered_at, action) VALUES(?, ?, ?, ?, ?)",
            arrayOf<Any?>(kind, taskId, scheduledAt, deliveredAt, action)
        )
    }

    fun logAction(notificationId: Long, action: String) {
        writableDatabase.execSQL("UPDATE notification_log SET action = ? WHERE id = ?", arrayOf<Any?>(action, notificationId))
    }

    fun deliveredCountSince(from: Long, until: Long = System.currentTimeMillis()): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM notification_log WHERE delivered_at IS NOT NULL AND delivered_at >= ? AND delivered_at <= ?",
            arrayOf(from.toString(), until.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun lastNonCriticalDelivery(): Long? =
        readableDatabase.rawQuery(
            "SELECT MAX(delivered_at) FROM notification_log WHERE delivered_at IS NOT NULL AND kind IN ('task_due','task_overdue','companion','pledge','dhikr','daily','wird','skill')",
            null
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /**
     * Consecutive ignored notifications, newest first. A blank action means "delivered, no answer
     * recorded" and must never be treated as an ignore — that false streak used to silence the app.
     */
    fun ignoredStreak(): Int {
        val actions = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT action FROM notification_log WHERE delivered_at IS NOT NULL ORDER BY delivered_at DESC LIMIT 12",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) actions += cursor.getString(0).orEmpty()
        }
        return NotificationPolicy.ignoredStreakFrom(actions)
    }

    /** Marks the most recent delivered row of a kind as explicitly ignored by the person. */
    fun markIgnored(kind: String, taskId: Long?, at: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            """
            UPDATE notification_log SET action = ? WHERE id = (
              SELECT id FROM notification_log
              WHERE kind = ? AND task_id IS ? AND delivered_at IS NOT NULL
              ORDER BY delivered_at DESC LIMIT 1
            )
            """.trimIndent(),
            arrayOf<Any?>(ACTION_IGNORED, kind, taskId)
        )
    }

    /** Local-only diagnostic rows; delivered notifications stay out of this list. */
    fun notificationDiagnosticsSince(from: Long): List<NotificationRecord> {
        val rows = ArrayList<NotificationRecord>()
        readableDatabase.rawQuery(
            "SELECT kind, scheduled_at, delivered_at, action FROM notification_log WHERE delivered_at IS NULL AND scheduled_at >= ? ORDER BY scheduled_at DESC LIMIT 20",
            arrayOf(from.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += NotificationRecord(
                    kind = cursor.getString(0),
                    scheduledAt = cursor.getLong(1),
                    deliveredAt = cursor.nullableLong(2),
                    action = cursor.getString(3).orEmpty()
                )
            }
        }
        return rows
    }

    fun notificationsSince(from: Long): List<NotificationRecord> {
        val rows = ArrayList<NotificationRecord>()
        readableDatabase.rawQuery(
            "SELECT kind, scheduled_at, delivered_at, action FROM notification_log WHERE delivered_at >= ? ORDER BY delivered_at DESC",
            arrayOf(from.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += NotificationRecord(
                    kind = cursor.getString(0),
                    scheduledAt = cursor.getLong(1),
                    deliveredAt = cursor.nullableLong(2),
                    action = cursor.getString(3).orEmpty()
                )
            }
        }
        return rows
    }

    fun insertTask(
        title: String,
        plannedMinutes: Int,
        blockedPackages: Set<String>,
        description: String = "",
        category: TaskCategory = TaskCategory.STUDY,
        priority: TaskPriority = TaskPriority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO,
        startAt: Long? = null,
        dueAt: Long? = null,
        subjectId: Long? = null,
        notes: String = "",
        recurrence: Recurrence = Recurrence.NONE,
        typeId: String? = null
    ): Long {
        require(title.isNotBlank()) { "Task title cannot be blank" }
        require(plannedMinutes in 1..720) { "Task duration must be between 1 and 720 minutes" }
        require(subjectId == null || subjectExists(subjectId)) { "Subject does not exist" }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title)
            put("planned_minutes", plannedMinutes)
            put("done_minutes", 0)
            put("is_done", 0)
            put("blocked_packages", encodePackages(blockedPackages))
            put("created_at", now)
            put("description", description)
            put("category", category.key)
            put("priority", priority.level)
            put("status", status.key)
            if (startAt != null) put("start_at", startAt) else putNull("start_at")
            if (dueAt != null) put("due_at", dueAt) else putNull("due_at")
            if (subjectId != null) put("subject_id", subjectId) else putNull("subject_id")
            put("notes", notes)
            put("recurrence", recurrence.key)
            if (typeId != null) put("type_id", typeId) else putNull("type_id")
            put("completed_at", if (status == TaskStatus.DONE) now else null)
            put("sort_order", 0)
        }
        return writableDatabase.insert("tasks", null, values)
    }

    fun updateTask(task: StudyTask) {
        require(task.title.isNotBlank()) { "Task title cannot be blank" }
        require(task.plannedMinutes in 1..720) { "Task duration must be between 1 and 720 minutes" }
        require(task.subjectId == null || subjectExists(task.subjectId)) { "Subject does not exist" }
        val completed = task.status == TaskStatus.DONE || task.isDone
        val values = ContentValues().apply {
            put("title", task.title)
            put("planned_minutes", task.plannedMinutes)
            put("description", task.description)
            put("category", task.category.key)
            put("priority", task.priority.level)
            put("status", if (completed) TaskStatus.DONE.key else task.status.key)
            put("is_done", if (completed) 1 else 0)
            put("blocked_packages", encodePackages(task.blockedPackages))
            if (task.startAt != null) put("start_at", task.startAt) else putNull("start_at")
            if (task.dueAt != null) put("due_at", task.dueAt) else putNull("due_at")
            if (task.subjectId != null) put("subject_id", task.subjectId) else putNull("subject_id")
            put("notes", task.notes)
            put("recurrence", task.recurrence.key)
            if (task.typeId != null) put("type_id", task.typeId) else putNull("type_id")
            if (completed) put("completed_at", task.completedAt ?: System.currentTimeMillis()) else putNull("completed_at")
            put("sort_order", task.sortOrder)
            if (task.originalDueAt != null) put("original_due_at", task.originalDueAt) else putNull("original_due_at")
        }
        writableDatabase.update("tasks", values, "id = ?", arrayOf(task.id.toString()))
    }

    fun setTaskStatus(taskId: Long, status: TaskStatus) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = task(taskId) ?: return
            val completedAt = if (status == TaskStatus.DONE) System.currentTimeMillis() else null
            db.execSQL(
                "UPDATE tasks SET status = ?, is_done = ?, completed_at = ? WHERE id = ?",
                arrayOf<Any?>(status.key, if (status == TaskStatus.DONE) 1 else 0, completedAt, taskId)
            )
            if (status == TaskStatus.DONE && previous.status != TaskStatus.DONE) {
                createNextRecurringTask(db, previous)
                awardGoalLinksForTask(taskId)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rescheduleTask(taskId: Long, dueAt: Long?) {
        writableDatabase.execSQL(
            "UPDATE tasks SET original_due_at = COALESCE(original_due_at, due_at), due_at = ? WHERE id = ?",
            arrayOf<Any?>(dueAt, taskId)
        )
    }

    fun updateTaskBlocked(taskId: Long, blockedPackages: Set<String>) {
        val values = ContentValues().apply {
            put("blocked_packages", encodePackages(blockedPackages))
        }
        writableDatabase.update("tasks", values, "id = ?", arrayOf(taskId.toString()))
    }

    /** Deletes the task together with its subtasks; focus sessions stay for history. */
    fun deleteTask(taskId: Long) {
        writableDatabase.delete("subtasks", "task_id = ?", arrayOf(taskId.toString()))
        writableDatabase.delete("tasks", "id = ?", arrayOf(taskId.toString()))
    }

    /** Copies a task (and its subtasks) with a clean progress state. */
    fun duplicateTask(taskId: Long): Long {
        val original = task(taskId) ?: return -1L
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", "${original.title} (نسخة)")
            put("planned_minutes", original.plannedMinutes)
            put("done_minutes", 0)
            put("is_done", 0)
            put("blocked_packages", encodePackages(original.blockedPackages))
            put("created_at", now)
            put("description", original.description)
            put("category", original.category.key)
            if (original.typeId != null) put("type_id", original.typeId) else putNull("type_id")
            put("priority", original.priority.level)
            put("status", TaskStatus.TODO.key)
            put("start_at", original.startAt)
            put("due_at", original.dueAt)
            put("subject_id", original.subjectId)
            put("notes", original.notes)
            put("recurrence", original.recurrence.key)
            putNull("completed_at")
            put("sort_order", original.sortOrder)
            put("original_due_at", original.originalDueAt)
        }
        val newId = writableDatabase.insert("tasks", null, values)
        subtasks(taskId).forEach { subtask ->
            val copy = ContentValues().apply {
                put("task_id", newId)
                put("title", subtask.title)
                put("is_done", 0)
                put("sort_order", subtask.sortOrder)
            }
            writableDatabase.insert("subtasks", null, copy)
        }
        return newId
    }

    fun task(taskId: Long): StudyTask? =
        queryTasks("WHERE id = ?", arrayOf(taskId.toString())).firstOrNull()

    fun tasks(): List<StudyTask> = queryTasks(null, null)

    // ----------------------------------------------------------- academics

    fun subjects(): List<StudySubject> {
        val result = ArrayList<StudySubject>()
        readableDatabase.rawQuery(
            """
            SELECT s.id, s.semester_id, s.name, s.color, s.icon, s.instructor,
                   s.goal_minutes, s.created_at, MIN(e.at)
            FROM subjects s
            LEFT JOIN exams e ON e.subject_id = s.id AND e.at >= ?
            GROUP BY s.id
            ORDER BY COALESCE(MIN(e.at), 9223372036854775807), s.name COLLATE NOCASE
            """.trimIndent(),
            arrayOf(System.currentTimeMillis().toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    StudySubject(
                        id = cursor.getLong(0),
                        semesterId = cursor.nullableLong(1),
                        name = cursor.getString(2),
                        color = cursor.getLong(3).toInt(),
                        icon = cursor.getString(4),
                        instructor = cursor.getString(5),
                        goalMinutes = cursor.getInt(6),
                        createdAt = cursor.getLong(7),
                        nextExamAt = cursor.nullableLong(8)
                    )
                )
            }
        }
        return result
    }

    fun eventsBetween(startAt: Long, endAt: Long): List<StudyEvent> {
        val result = ArrayList<StudyEvent>()
        readableDatabase.rawQuery(
            "SELECT id, title, kind, starts_at, ends_at, notes FROM events WHERE starts_at < ? AND ends_at > ? ORDER BY starts_at",
            arrayOf(endAt.toString(), startAt.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(
                StudyEvent(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4), cursor.getString(5))
            )
        }
        return result
    }

    fun insertEvent(title: String, kind: String, startsAt: Long, endsAt: Long, notes: String = ""): Long {
        require(title.isNotBlank()) { "Event title cannot be blank" }
        require(endsAt > startsAt)
        val values = ContentValues().apply {
            put("title", title.trim())
            put("kind", kind)
            putNull("subject_id")
            putNull("task_id")
            put("starts_at", startsAt)
            put("ends_at", endsAt)
            put("notes", notes)
        }
        return writableDatabase.insertOrThrow("events", null, values)
    }

    fun deleteEvent(eventId: Long) { writableDatabase.delete("events", "id = ?", arrayOf(eventId.toString())) }

    fun hasEventConflict(startsAt: Long, endsAt: Long): Boolean =
        ScheduleConflictDetector.overlaps(startsAt, endsAt, eventsBetween(startsAt, endsAt))

    fun nextAvailableEventStart(startAt: Long, durationMillis: Long, dayEnd: Long): Long? {
        return ScheduleConflictDetector.nextAvailableStart(
            startAt, durationMillis, dayEnd, eventsBetween(startAt, dayEnd)
        )
    }

    fun insertSubject(
        name: String,
        instructor: String,
        goalMinutes: Int,
        examAt: Long? = null,
        color: Int = 0xFF3B82F6.toInt()
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val subjectValues = ContentValues().apply {
                put("name", name.trim())
                put("instructor", instructor.trim())
                put("goal_minutes", goalMinutes.coerceAtLeast(0))
                put("color", color)
                put("created_at", System.currentTimeMillis())
            }
            val subjectId = db.insertOrThrow("subjects", null, subjectValues)
            if (examAt != null) {
                val examValues = ContentValues().apply {
                    put("subject_id", subjectId)
                    put("title", "امتحان ${name.trim()}")
                    put("at", examAt)
                    put("kind", "exam")
                }
                db.insertOrThrow("exams", null, examValues)
            }
            db.setTransactionSuccessful()
            return subjectId
        } finally {
            db.endTransaction()
        }
    }

    private fun queryTasks(where: String?, args: Array<String>?): List<StudyTask> {
        val sql = """
            SELECT id, title, planned_minutes, done_minutes, is_done, blocked_packages, created_at,
                   description, category, priority, status, start_at, due_at, subject_id, notes,
                   recurrence, completed_at, sort_order, original_due_at, type_id
            FROM tasks
            ${where.orEmpty()}
            ORDER BY is_done ASC, COALESCE(due_at, 9223372036854775807) ASC, created_at DESC
        """.trimIndent()
        val result = ArrayList<StudyTask>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    StudyTask(
                        id = cursor.getLong(0),
                        title = cursor.getString(1),
                        plannedMinutes = cursor.getInt(2),
                        doneMinutes = cursor.getInt(3),
                        isDone = cursor.getInt(4) == 1,
                        blockedPackages = decodePackages(cursor.getString(5)),
                        createdAt = cursor.getLong(6),
                        description = cursor.getString(7).orEmpty(),
                        category = TaskCategory.from(cursor.getString(8)),
                        priority = TaskPriority.from(cursor.getInt(9)),
                        status = TaskStatus.from(cursor.getString(10)),
                        startAt = cursor.nullableLong(11),
                        dueAt = cursor.nullableLong(12),
                        subjectId = cursor.nullableLong(13),
                        notes = cursor.getString(14).orEmpty(),
                        recurrence = Recurrence.from(cursor.getString(15)),
                        completedAt = cursor.nullableLong(16),
                        sortOrder = cursor.getInt(17),
                        originalDueAt = cursor.nullableLong(18),
                        typeId = cursor.getString(19)
                    )
                )
            }
        }
        return result
    }

    private fun subjectExists(subjectId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM subjects WHERE id = ? LIMIT 1",
        arrayOf(subjectId.toString())
    ).use { it.moveToFirst() }

    fun addFocusMinutes(taskId: Long, minutes: Int) {
        if (minutes <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            creditFocusMinutes(db, taskId, minutes)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun creditFocusMinutes(db: SQLiteDatabase, taskId: Long, minutes: Int) {
        if (minutes <= 0) return
        val previous = task(taskId)
        db.execSQL(
            "UPDATE tasks SET done_minutes = done_minutes + ? WHERE id = ?",
            arrayOf<Any>(minutes, taskId)
        )
        db.execSQL(
            """
            UPDATE tasks
            SET status = CASE WHEN status = 'done' OR done_minutes >= planned_minutes THEN 'done' ELSE status END,
                is_done = CASE WHEN status = 'done' OR done_minutes >= planned_minutes THEN 1 ELSE 0 END,
                completed_at = CASE WHEN done_minutes >= planned_minutes THEN COALESCE(completed_at, ?) ELSE completed_at END
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any?>(System.currentTimeMillis(), taskId)
        )
        val updated = task(taskId)
        if (previous != null && updated?.status == TaskStatus.DONE && previous.status != TaskStatus.DONE) {
            createNextRecurringTask(db, previous)
            awardGoalLinksForTask(taskId)
        }
    }

    /**
     * Finishes an active session and credits its minutes in one transaction. Returns true only when
     * this call was the one that moved the row from active to finished, so a racing alarm and ticker
     * can never double-count the same session.
     */
    fun finishSessionOnce(sessionId: Long, taskId: Long, minutes: Int): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = db.update(
                "sessions",
                ContentValues().apply { put("state", "finished") },
                "id = ? AND state = ?",
                arrayOf(sessionId.toString(), STATE_ACTIVE)
            )
            if (changed > 0) creditFocusMinutes(db, taskId, minutes)
            db.setTransactionSuccessful()
            return changed > 0
        } finally {
            db.endTransaction()
        }
    }

    private fun createNextRecurringTask(db: SQLiteDatabase, original: StudyTask) {
        if (original.recurrence == Recurrence.NONE) return
        val zone = AppTimeZone.zone(appContext)
        // The engine keeps the original clock time and skips occurrences already past.
        val nextDueAt = RecurrenceEngine.next(
            dueAt = original.dueAt,
            recurrence = original.recurrence,
            completedAt = original.completedAt ?: System.currentTimeMillis(),
            zone = zone
        ) ?: return
        val dueDate = original.dueAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val nextDueDate = java.time.Instant.ofEpochMilli(nextDueAt).atZone(zone).toLocalDate()
        val nextStartAt = original.startAt?.let { start ->
            val startDate = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val daysBeforeDue = dueDate?.let { java.time.temporal.ChronoUnit.DAYS.between(startDate, it) } ?: 0L
            nextDueDate.minusDays(daysBeforeDue).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val values = ContentValues().apply {
            put("title", original.title)
            put("planned_minutes", original.plannedMinutes)
            put("done_minutes", 0)
            put("is_done", 0)
            put("blocked_packages", encodePackages(original.blockedPackages))
            put("created_at", System.currentTimeMillis())
            put("description", original.description)
            put("category", original.category.key)
            if (original.typeId != null) put("type_id", original.typeId) else putNull("type_id")
            put("priority", original.priority.level)
            put("status", TaskStatus.TODO.key)
            if (nextStartAt == null) putNull("start_at") else put("start_at", nextStartAt)
            put("due_at", nextDueAt)
            if (original.subjectId == null) putNull("subject_id") else put("subject_id", original.subjectId)
            put("notes", original.notes)
            put("recurrence", original.recurrence.key)
            putNull("completed_at")
            put("sort_order", original.sortOrder)
            putNull("original_due_at")
        }
        val nextId = db.insertOrThrow("tasks", null, values)
        subtasks(original.id).forEach { subtask ->
            db.insertOrThrow("subtasks", null, ContentValues().apply {
                put("task_id", nextId)
                put("title", subtask.title)
                put("is_done", 0)
                put("sort_order", subtask.sortOrder)
            })
        }
    }

    // ------------------------------------------------------------- subtasks

    fun subtasks(taskId: Long): List<Subtask> {
        val result = ArrayList<Subtask>()
        readableDatabase.rawQuery(
            "SELECT id, task_id, title, is_done, sort_order FROM subtasks WHERE task_id = ? ORDER BY sort_order ASC, id ASC",
            arrayOf(taskId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Subtask(
                        id = cursor.getLong(0),
                        taskId = cursor.getLong(1),
                        title = cursor.getString(2),
                        isDone = cursor.getInt(3) == 1,
                        sortOrder = cursor.getInt(4)
                    )
                )
            }
        }
        return result
    }

    fun addSubtask(taskId: Long, title: String): Long {
        val nextOrder = readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM subtasks WHERE task_id = ?",
            arrayOf(taskId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("title", title)
            put("is_done", 0)
            put("sort_order", nextOrder)
        }
        return writableDatabase.insert("subtasks", null, values)
    }

    fun setSubtaskDone(subtaskId: Long, done: Boolean) {
        writableDatabase.execSQL(
            "UPDATE subtasks SET is_done = ? WHERE id = ?",
            arrayOf<Any>(if (done) 1 else 0, subtaskId)
        )
    }

    fun deleteSubtask(subtaskId: Long) {
        writableDatabase.delete("subtasks", "id = ?", arrayOf(subtaskId.toString()))
    }

    // ------------------------------------------------------------- sessions

    fun startSession(
        taskId: Long,
        startedAt: Long,
        endsAt: Long,
        blockedPackages: Set<String>
    ): Long {
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("started_at", startedAt)
            put("ends_at", endsAt)
            put("blocked_packages", encodePackages(blockedPackages))
            put("state", STATE_ACTIVE)
        }
        return writableDatabase.insert("sessions", null, values)
    }

    fun finishSession(sessionId: Long) {
        writableDatabase.execSQL(
            "UPDATE sessions SET state = 'finished' WHERE id = ?",
            arrayOf<Any>(sessionId)
        )
    }

    fun activeSession(): SessionRow? {
        readableDatabase.rawQuery(
            """
            SELECT s.id, s.task_id, s.started_at, s.ends_at, s.blocked_packages, t.title
            FROM sessions s JOIN tasks t ON t.id = s.task_id
            WHERE s.state = '$STATE_ACTIVE'
            ORDER BY s.id DESC LIMIT 1
            """.trimIndent(),
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return SessionRow(
                id = cursor.getLong(0),
                taskId = cursor.getLong(1),
                startedAt = cursor.getLong(2),
                endsAt = cursor.getLong(3),
                blockedPackages = decodePackages(cursor.getString(4)),
                taskTitle = cursor.getString(5)
            )
        }
    }

    fun totalFocusedMinutes(): Int {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(done_minutes), 0) FROM tasks", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun completedTasksBetween(startAt: Long, endAt: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tasks WHERE status = 'done' AND completed_at >= ? AND completed_at < ?",
            arrayOf(startAt.toString(), endAt.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun overdueTaskCount(now: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tasks WHERE status NOT IN ('done','cancelled') AND due_at IS NOT NULL AND due_at < ?",
            arrayOf(now.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun finishedSessionsBetween(startAt: Long, endAt: Long): List<FocusSessionMetric> {
        val result = ArrayList<FocusSessionMetric>()
        readableDatabase.rawQuery(
            "SELECT started_at, ends_at, task_id FROM sessions WHERE state = 'finished' AND started_at >= ? AND started_at < ? ORDER BY started_at",
            arrayOf(startAt.toString(), endAt.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += FocusSessionMetric(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }
        return result
    }

    private fun android.database.Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun encodePackages(packages: Set<String>): String = packages.joinToString(SEPARATOR)

    private fun decodePackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    /**
     * v15 — تتبع الحياة «حياتي»: عادات، ورد قرآني، صحة، مال، مزاج.
     *
     * جداول جديدة فقط؛ لا يتغير أي جدول قائم، فبيانات المستخدمين الحاليين تبقى كما هي.
     * مفاتيح الأيام نصوص ISO (yyyy-MM-dd) في منطقة الجهاز، فيطابق مفتاح اليوم ما تعرضه
     * الواجهة دون حسابات مناطق زمنية داخل قاعدة البيانات.
     */
    private fun migrateToV15(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS habits(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS habit_logs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              habit_id INTEGER NOT NULL,
              day TEXT NOT NULL,
              done INTEGER NOT NULL DEFAULT 1,
              UNIQUE(habit_id, day)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_habit_logs_habit_day ON habit_logs(habit_id, day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wird_settings(
              id INTEGER PRIMARY KEY,
              surah TEXT NOT NULL DEFAULT 'البقرة',
              start_page INTEGER NOT NULL DEFAULT 2,
              pages_per_day INTEGER NOT NULL DEFAULT 2
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wird_logs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              day TEXT NOT NULL,
              pages INTEGER NOT NULL,
              surah TEXT NOT NULL DEFAULT 'البقرة',
              logged_at INTEGER NOT NULL,
              UNIQUE(day)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS health_logs(
              day TEXT PRIMARY KEY,
              weight REAL,
              sleep_hours REAL,
              water_cups INTEGER NOT NULL DEFAULT 0,
              exercise_minutes INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transactions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              kind TEXT NOT NULL,
              amount REAL NOT NULL,
              category TEXT NOT NULL DEFAULT '',
              note TEXT NOT NULL DEFAULT '',
              day TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_day ON transactions(day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mood_logs(
              day TEXT PRIMARY KEY,
              mood INTEGER NOT NULL,
              logged_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * v16 — اللياقة: ملف الجسم، سجل السعرات، سجل إتمام التمارين.
     *
     * جداول جديدة فقط؛ لا يتغير أي جدول قائم، فبيانات المستخدمين الحاليين تبقى كما هي.
     */
    private fun migrateToV16(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fitness_profile(
              id INTEGER PRIMARY KEY,
              gender TEXT NOT NULL DEFAULT 'male',
              age INTEGER NOT NULL DEFAULT 30,
              height_cm REAL NOT NULL DEFAULT 170,
              weight_kg REAL NOT NULL DEFAULT 70,
              activity TEXT NOT NULL DEFAULT 'moderate',
              goal TEXT NOT NULL DEFAULT 'maintain'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calorie_logs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              day TEXT NOT NULL,
              name TEXT NOT NULL DEFAULT '',
              calories INTEGER NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calorie_logs_day ON calorie_logs(day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exercise_logs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              day TEXT NOT NULL,
              exercise_id TEXT NOT NULL,
              done INTEGER NOT NULL DEFAULT 1,
              UNIQUE(day, exercise_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exercise_logs_day ON exercise_logs(day)")
    }

    /**
     * v17 — additive only: two new tables, no DROP and no ALTER of any existing table.
     * user_profile keeps the display name as a single row (id = 1); support_messages
     * keeps a local copy of every support/suggestion message so none is lost when the
     * network is down — it is saved as «sending» before the POST and its status is
     * updated to «sent»/«failed» from the result. Attached images are copied to
     * filesDir/support_images and only their paths are stored here, never BLOBs.
     */
    private fun migrateToV17(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_profile(
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL DEFAULT '',
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS support_messages(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              kind TEXT NOT NULL,
              user_name TEXT NOT NULL DEFAULT '',
              sender_name TEXT NOT NULL DEFAULT '',
              contact TEXT NOT NULL DEFAULT '',
              body TEXT NOT NULL DEFAULT '',
              image_paths TEXT NOT NULL DEFAULT '',
              status TEXT NOT NULL DEFAULT 'sending',
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_support_messages_created ON support_messages(created_at)")
    }

    // ------------------------------------------------------- life tracking

    fun habits(): List<Habit> = readableDatabase.rawQuery(
        "SELECT id, name, created_at FROM habits WHERE archived = 0 ORDER BY created_at ASC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Habit(id = cursor.getLong(0), name = cursor.getString(1).orEmpty(), createdAt = cursor.getLong(2)))
            }
        }
    }

    fun addHabit(name: String): Long {
        require(name.isNotBlank()) { "اسم العادة مطلوب" }
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
            put("archived", 0)
        }
        return writableDatabase.insert("habits", null, values)
    }

    fun deleteHabit(habitId: Long) {
        writableDatabase.delete("habit_logs", "habit_id = ?", arrayOf(habitId.toString()))
        writableDatabase.delete("habits", "id = ?", arrayOf(habitId.toString()))
    }

    /** أيام الإنجاز لعادة واحدة منذ يوم معين؛ تُبنى منها شبكة الأيام والسلسلة. */
    fun habitDoneDays(habitId: Long, sinceDay: String): Set<String> =
        readableDatabase.rawQuery(
            "SELECT day FROM habit_logs WHERE habit_id = ? AND done = 1 AND day >= ? ORDER BY day DESC",
            arrayOf(habitId.toString(), sinceDay)
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0).orEmpty()) }
        }

    fun setHabitDone(habitId: Long, day: String, done: Boolean) {
        if (done) {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO habit_logs(habit_id, day, done) VALUES(?, ?, 1)",
                arrayOf<Any?>(habitId, day)
            )
        } else {
            writableDatabase.delete(
                "habit_logs",
                "habit_id = ? AND day = ?",
                arrayOf(habitId.toString(), day)
            )
        }
    }

    /** إعدادات الورد؛ الافتراضي صفحتان يوميًا من سورة البقرة بداية من صفحة 2. */
    fun wirdSettings(): WirdSettings =
        readableDatabase.rawQuery(
            "SELECT surah, start_page, pages_per_day FROM wird_settings WHERE id = 1 LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                WirdSettings(
                    surah = cursor.getString(0).orEmpty().ifBlank { "البقرة" },
                    startPage = cursor.getInt(1).coerceAtLeast(1),
                    pagesPerDay = cursor.getInt(2).coerceAtLeast(1)
                )
            } else {
                WirdSettings()
            }
        }

    fun saveWirdSettings(settings: WirdSettings) {
        require(settings.surah.isNotBlank()) { "اسم السورة مطلوب" }
        require(settings.startPage >= 1) { "صفحة البداية يجب أن تكون 1 أو أكثر" }
        require(settings.pagesPerDay in 1..30) { "عدد صفحات اليوم بين 1 و 30" }
        // تغيير السورة أو صفحة البداية يعني وردًا جديدًا: العدّاد العام عابر للسور
        // كان يستمر من إجمالي السورة السابقة، فيبدأ العد من صفحة البداية الجديدة.
        // تغيير عدد صفحات اليوم وحده لا يصفّر شيئًا — إنه إيقاع فقط، لا بداية جديدة.
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = wirdSettings()
            if (old.surah != settings.surah.trim() || old.startPage != settings.startPage) {
                db.delete("wird_logs", null, null)
            }
            db.execSQL(
                "INSERT INTO wird_settings(id, surah, start_page, pages_per_day) VALUES(1, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET surah=excluded.surah, start_page=excluded.start_page, " +
                    "pages_per_day=excluded.pages_per_day",
                arrayOf<Any?>(settings.surah.trim(), settings.startPage, settings.pagesPerDay)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** يصفّر عدّاد الورد (حذف سجل الصفحات) لبدء ختمة جديدة؛ الإعدادات نفسها لا تتغير. */
    fun resetWirdLogs() {
        writableDatabase.delete("wird_logs", null, null)
    }

    /** يسجّل ورد يوم واحد فقط؛ الإرجاع false إن كان اليوم مسجّلًا بالفعل فلا يتضاعف العد. */
    fun logWird(day: String, pages: Int, surah: String): Boolean {
        if (pages <= 0) return false
        val values = ContentValues().apply {
            put("day", day)
            put("pages", pages)
            put("surah", surah)
            put("logged_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "wird_logs", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun wirdLoggedToday(day: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM wird_logs WHERE day = ? LIMIT 1",
            arrayOf(day)
        ).use { it.moveToFirst() }

    fun wirdTotalPages(): Int =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(pages), 0) FROM wird_logs", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun healthLog(day: String): HealthLog? =
        readableDatabase.rawQuery(
            "SELECT day, weight, sleep_hours, water_cups, exercise_minutes FROM health_logs WHERE day = ? LIMIT 1",
            arrayOf(day)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            HealthLog(
                day = cursor.getString(0).orEmpty(),
                weight = if (cursor.isNull(1)) null else cursor.getDouble(1),
                sleepHours = if (cursor.isNull(2)) null else cursor.getDouble(2),
                waterCups = cursor.getInt(3),
                exerciseMinutes = cursor.getInt(4)
            )
        }

    fun saveHealthLog(log: HealthLog) {
        val values = ContentValues().apply {
            put("day", log.day)
            if (log.weight != null) put("weight", log.weight) else putNull("weight")
            if (log.sleepHours != null) put("sleep_hours", log.sleepHours) else putNull("sleep_hours")
            put("water_cups", log.waterCups.coerceAtLeast(0))
            put("exercise_minutes", log.exerciseMinutes.coerceAtLeast(0))
        }
        writableDatabase.insertWithOnConflict(
            "health_logs", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun addTransaction(kind: MoneyKind, amount: Double, category: String, note: String, day: String): Long {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        val values = ContentValues().apply {
            put("kind", kind.key)
            put("amount", amount)
            put("category", category.trim())
            put("note", note.trim())
            put("day", day)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("transactions", null, values)
    }

    fun deleteTransaction(transactionId: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(transactionId.toString()))
    }

    /** معاملات شهر كامل؛ `monthPrefix` بصيغة yyyy-MM فيطابق مفتاح اليوم مباشرة. */
    fun transactionsForMonth(monthPrefix: String): List<MoneyTransaction> =
        readableDatabase.rawQuery(
            "SELECT id, kind, amount, category, note, day, created_at FROM transactions " +
                "WHERE day LIKE ? ORDER BY day DESC, created_at DESC",
            arrayOf("$monthPrefix%")
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MoneyTransaction(
                            id = cursor.getLong(0),
                            kind = MoneyKind.from(cursor.getString(1)),
                            amount = cursor.getDouble(2),
                            category = cursor.getString(3).orEmpty(),
                            note = cursor.getString(4).orEmpty(),
                            day = cursor.getString(5).orEmpty(),
                            createdAt = cursor.getLong(6)
                        )
                    )
                }
            }
        }

    fun logMood(day: String, mood: Int) {
        val values = ContentValues().apply {
            put("day", day)
            put("mood", mood.coerceIn(1, 5))
            put("logged_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "mood_logs", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun moodFor(day: String): Int? =
        readableDatabase.rawQuery(
            "SELECT mood FROM mood_logs WHERE day = ? LIMIT 1",
            arrayOf(day)
        ).use { if (it.moveToFirst()) it.getInt(0) else null }

    fun moodLogsSince(sinceDay: String): List<MoodLog> =
        readableDatabase.rawQuery(
            "SELECT day, mood FROM mood_logs WHERE day >= ? ORDER BY day ASC",
            arrayOf(sinceDay)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(MoodLog(day = cursor.getString(0).orEmpty(), mood = cursor.getInt(1)))
            }
        }

    // ------------------------------------------------------- fitness (v16)

    /** ملف الجسم؛ الافتراضي ذكر، 30 سنة، 170 سم، 70 كجم، نشاط متوسط، حفاظ. */
    fun fitnessProfile(): FitnessProfile =
        readableDatabase.rawQuery(
            "SELECT gender, age, height_cm, weight_kg, activity, goal FROM fitness_profile WHERE id = 1 LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                FitnessProfile(
                    gender = FitnessGender.from(cursor.getString(0)),
                    age = cursor.getInt(1).coerceIn(10, 100),
                    heightCm = cursor.getDouble(2).coerceIn(100.0, 250.0),
                    weightKg = cursor.getDouble(3).coerceIn(30.0, 300.0),
                    activity = ActivityLevel.from(cursor.getString(4)),
                    goal = FitnessGoal.from(cursor.getString(5))
                )
            } else {
                FitnessProfile()
            }
        }

    fun saveFitnessProfile(profile: FitnessProfile) {
        require(profile.age in 10..100) { "العمر بين 10 و 100" }
        require(profile.heightCm in 100.0..250.0) { "الطول بين 100 و 250 سم" }
        require(profile.weightKg in 30.0..300.0) { "الوزن بين 30 و 300 كجم" }
        val values = ContentValues().apply {
            put("id", 1)
            put("gender", profile.gender.key)
            put("age", profile.age)
            put("height_cm", profile.heightCm)
            put("weight_kg", profile.weightKg)
            put("activity", profile.activity.key)
            put("goal", profile.goal.key)
        }
        writableDatabase.insertWithOnConflict(
            "fitness_profile", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** وجبات يوم واحد؛ الأحدث أولًا. */
    fun calorieEntries(day: String): List<CalorieEntry> =
        readableDatabase.rawQuery(
            "SELECT id, day, name, calories, created_at FROM calorie_logs WHERE day = ? ORDER BY created_at DESC",
            arrayOf(day)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalorieEntry(
                            id = cursor.getLong(0),
                            day = cursor.getString(1).orEmpty(),
                            name = cursor.getString(2).orEmpty(),
                            calories = cursor.getInt(3),
                            createdAt = cursor.getLong(4)
                        )
                    )
                }
            }
        }

    fun addCalorieEntry(name: String, calories: Int, day: String): Long {
        require(calories in 1..10000) { "السعرات بين 1 و 10000" }
        val values = ContentValues().apply {
            put("day", day)
            put("name", name.trim().take(48))
            put("calories", calories)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("calorie_logs", null, values)
    }

    fun deleteCalorieEntry(entryId: Long) {
        writableDatabase.delete("calorie_logs", "id = ?", arrayOf(entryId.toString()))
    }

    /** كل تسجيلات إتمام التمارين منذ يوم معين؛ تُبنى منها علامات الإنجاز. */
    fun exerciseLogsSince(sinceDay: String): List<ExerciseDone> =
        readableDatabase.rawQuery(
            "SELECT day, exercise_id FROM exercise_logs WHERE done = 1 AND day >= ? ORDER BY day DESC",
            arrayOf(sinceDay)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ExerciseDone(day = cursor.getString(0).orEmpty(), exerciseId = cursor.getString(1).orEmpty()))
                }
            }
        }

    fun setExerciseDone(exerciseId: String, day: String, done: Boolean) {
        if (done) {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO exercise_logs(day, exercise_id, done) VALUES(?, ?, 1)",
                arrayOf<Any?>(day, exerciseId)
            )
        } else {
            writableDatabase.delete(
                "exercise_logs",
                "day = ? AND exercise_id = ?",
                arrayOf(day, exerciseId)
            )
        }
    }

    // ------------------------------------------------------- user profile (v17)

    /** Display name from the single-row user_profile table, or null when never saved. */
    fun userName(): String? = readableDatabase.rawQuery(
        "SELECT name FROM user_profile WHERE id = 1 LIMIT 1", null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else null }

    /** Single-row upsert (id = 1); the preferences mirror in FocusPreferences stays in sync. */
    fun saveUserName(name: String) {
        val values = ContentValues().apply {
            put("id", 1)
            put("name", name.trim().take(60))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "user_profile", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ------------------------------------------------------- support messages (v17)

    /**
     * Saves a support/suggestion message BEFORE it is sent, with status «sending».
     * [userName] is the current profile name, attached to every message; [senderName] is
     * the name typed in the form (used as the POST payload's name); [body] is the raw
     * text as typed (the [اقتراح] tag and the image-count note are rebuilt on retry);
     * [imagePaths] are the private filesDir copies, not the picked content URIs.
     */
    fun saveSupportMessage(
        kind: SupportMessageKind,
        userName: String,
        senderName: String,
        contact: String,
        body: String,
        imagePaths: List<String>
    ): Long {
        require(body.isNotBlank()) { "نص الرسالة مطلوب" }
        val values = ContentValues().apply {
            put("kind", kind.key)
            put("user_name", userName.trim().take(60))
            put("sender_name", senderName.trim().take(60))
            put("contact", contact.trim().take(120))
            put("body", body.trim())
            put("image_paths", joinSupportImagePaths(imagePaths))
            put("status", SupportMessageStatus.SENDING.key)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("support_messages", null, values)
    }

    fun updateSupportMessageStatus(id: Long, status: SupportMessageStatus) {
        val values = ContentValues().apply { put("status", status.key) }
        writableDatabase.update("support_messages", values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * يحوّل رسائل الدعم العالقة بحالة «قيد الإرسال» إلى «فاشلة» (م11): رسالة تبقى
     * عالقة إن قُتل التطبيق أثناء الإرسال، فلا تظهر أبدًا كفاشلة قابلة لإعادة
     * الإرسال. تُستدعى مرة واحدة عند فتح شاشة الدعم — لا أثناء إرسال جارٍ، لأن
     * الإرسال الجاري يحدّث حالته بنفسه فور انتهائه. يعيد عدد الرسائل التي سُوّيت.
     */
    fun failStuckSendingSupportMessages(): Int {
        val values = ContentValues().apply { put("status", SupportMessageStatus.FAILED.key) }
        return writableDatabase.update(
            "support_messages", values, "status = ?",
            arrayOf(SupportMessageStatus.SENDING.key)
        )
    }

    /** Saved messages, newest first; the support screen's «رسائلي» section reads this. */
    fun supportMessages(limit: Int = 50): List<SupportMessage> =
        readableDatabase.rawQuery(
            "SELECT id, kind, user_name, sender_name, contact, body, image_paths, status, created_at " +
                "FROM support_messages ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SupportMessage(
                            id = cursor.getLong(0),
                            kind = SupportMessageKind.from(cursor.getString(1)),
                            userName = cursor.getString(2).orEmpty(),
                            senderName = cursor.getString(3).orEmpty(),
                            contact = cursor.getString(4).orEmpty(),
                            body = cursor.getString(5).orEmpty(),
                            imagePaths = splitSupportImagePaths(cursor.getString(6).orEmpty()),
                            status = SupportMessageStatus.from(cursor.getString(7)),
                            createdAt = cursor.getLong(8)
                        )
                    )
                }
            }
        }

    companion object {
        private const val DB_NAME = "focus_study.db"
        private const val DB_VERSION = 17
        private const val STATE_ACTIVE = "active"
        private const val SEPARATOR = "\n"

        @Volatile
        private var instance: StudyDb? = null

        fun get(context: Context): StudyDb =
            instance ?: synchronized(this) {
                instance ?: StudyDb(context).also { instance = it }
            }
    }
}
