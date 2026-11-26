package com.focusstudy.poc

/**
 * Pure payload builder for Android → web sync. It never sees [StudyTask.notes], a money
 * transaction note or a note body: only the fields listed in the backend whitelist
 * (windows-sync contract §3) are copied into `data`.
 *
 * [tracked] holds the last fingerprint we sent for each record, so repeated pushes keep the same
 * `updatedAt` until the record actually changes. A local record that disappeared becomes a
 * tombstone instead of silently vanishing from the other device.
 */
internal data class SyncStamp(
    val fingerprint: String,
    val updatedAt: Long,
    val deleted: Boolean = false
)

internal data class SyncBuilt(
    val data: Map<String, List<Map<String, Any>>>,
    val stamps: Map<String, SyncStamp>
)

/** The «حياتي» half of a sync snapshot; empty by default so old callers keep working. */
internal data class LifeSyncSnapshot(
    val habits: List<Habit> = emptyList(),
    val habitLogs: List<HabitLogRow> = emptyList(),
    val wirdLogs: List<WirdLog> = emptyList(),
    val healthLogs: List<HealthLog> = emptyList(),
    val transactions: List<MoneyTransaction> = emptyList(),
    val moodLogs: List<MoodLog> = emptyList(),
    val calorieLogs: List<CalorieEntry> = emptyList()
)

internal object SyncPayload {

    /** All synced collection keys, in wire order; matches the server COLLECTIONS. */
    val collections: List<String> = listOf(
        "tasks", "subjects", "taskTypes",
        "habits", "habitLogs", "wirdLogs", "healthLogs", "transactions", "moodLogs", "calorieLogs"
    )

    /**
     * Canonical content fingerprint: the record's fields except `id`/`updatedAt`/`deleted`,
     * sorted by key, with numbers normalized so `30`, `30L` and `30.0` hash alike. The same
     * function fingerprints both locally built records and records received on pull
     * ([SyncApply]), so a record applied from the server keeps a stamp the next push
     * recognises as unchanged instead of re-stamping it. The format changed in windows-sync
     * (it used to be per-kind ad-hoc strings), so the first push after this upgrade
     * re-stamps every record once with fresh `updatedAt`; content is unchanged.
     */
    fun contentFingerprint(content: Map<String, Any>): String =
        content.entries
            .filter { (key, _) -> key != "id" && key != "updatedAt" && key != "deleted" }
            .sortedBy { (key, _) -> key }
            .joinToString("\u001f") { (key, value) -> "$key=${canonicalValue(value)}" }

    private fun canonicalValue(value: Any): String = when (value) {
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble.isFinite() && asDouble == kotlin.math.floor(asDouble) &&
                asDouble >= Long.MIN_VALUE.toDouble() && asDouble <= Long.MAX_VALUE.toDouble()
            ) {
                asDouble.toLong().toString()
            } else {
                value.toString()
            }
        }
        else -> value.toString()
    }

    fun build(
        tasks: List<StudyTask>,
        subjects: List<StudySubject>,
        types: List<TaskType>,
        tracked: Map<String, SyncStamp>,
        now: Long
    ): SyncBuilt = build(tasks, subjects, types, tracked, now, LifeSyncSnapshot())

    fun build(
        tasks: List<StudyTask>,
        subjects: List<StudySubject>,
        types: List<TaskType>,
        tracked: Map<String, SyncStamp>,
        now: Long,
        life: LifeSyncSnapshot
    ): SyncBuilt {
        val stamps = LinkedHashMap<String, SyncStamp>()
        val lists = LinkedHashMap<String, MutableList<Map<String, Any>>>()
        for (kind in collections) lists[kind] = ArrayList()

        /** Builds one live record: id + fields + a stamp kept from [tracked] when unchanged. */
        fun record(kind: String, id: String, fields: Map<String, Any>): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            out["id"] = id
            out.putAll(fields)
            out["updatedAt"] = stamp(tracked, "$kind:$id", contentFingerprint(out), now, stamps)
            return out
        }

        for (task in tasks) {
            val fields = LinkedHashMap<String, Any>()
            fields["title"] = task.title
            fields["minutes"] = task.plannedMinutes
            fields["category"] = task.category.key
            fields["priority"] = task.priority.level
            fields["status"] = task.status.key
            fields["recurrence"] = task.recurrence.key
            task.dueAt?.let { fields["dueAt"] = it }
            task.subjectId?.let { fields["subjectId"] = it.toString() }
            task.typeId?.let { fields["typeId"] = it }
            lists.getValue("tasks").add(record("tasks", task.id.toString(), fields))
        }

        for (subject in subjects) {
            lists.getValue("subjects").add(
                record("subjects", subject.id.toString(), mapOf("name" to subject.name))
            )
        }

        for (type in types) {
            val fields = LinkedHashMap<String, Any>()
            fields["name"] = type.name
            if (type.archived) fields["archived"] = true
            lists.getValue("taskTypes").add(record("taskTypes", type.id, fields))
        }

        for (habit in life.habits) {
            val fields = LinkedHashMap<String, Any>()
            fields["name"] = habit.name
            if (habit.createdAt > 0) fields["createdAt"] = habit.createdAt
            if (habit.archived) fields["archived"] = true
            lists.getValue("habits").add(record("habits", habit.id.toString(), fields))
        }

        for (log in life.habitLogs) {
            lists.getValue("habitLogs").add(
                record(
                    "habitLogs", "${log.habitId}:${log.day}",
                    mapOf("habitId" to log.habitId.toString(), "day" to log.day)
                )
            )
        }

        for (log in life.wirdLogs) {
            lists.getValue("wirdLogs").add(
                record(
                    "wirdLogs", log.day,
                    mapOf("day" to log.day, "pages" to log.pages, "surah" to log.surah)
                )
            )
        }

        for (log in life.healthLogs) {
            val fields = LinkedHashMap<String, Any>()
            fields["day"] = log.day
            log.weight?.let { fields["weight"] = it }
            log.sleepHours?.let { fields["sleepHours"] = it }
            fields["waterCups"] = log.waterCups
            fields["exerciseMinutes"] = log.exerciseMinutes
            lists.getValue("healthLogs").add(record("healthLogs", log.day, fields))
        }

        // A money note is never copied: it stays on its device, like task notes.
        for (transaction in life.transactions) {
            val fields = LinkedHashMap<String, Any>()
            fields["kind"] = transaction.kind.key
            fields["amount"] = transaction.amount
            if (transaction.category.isNotBlank()) fields["category"] = transaction.category
            fields["day"] = transaction.day
            if (transaction.createdAt > 0) fields["createdAt"] = transaction.createdAt
            lists.getValue("transactions")
                .add(record("transactions", transaction.id.toString(), fields))
        }

        for (log in life.moodLogs) {
            lists.getValue("moodLogs").add(
                record("moodLogs", log.day, mapOf("day" to log.day, "mood" to log.mood))
            )
        }

        for (entry in life.calorieLogs) {
            val fields = LinkedHashMap<String, Any>()
            fields["day"] = entry.day
            fields["name"] = entry.name
            fields["calories"] = entry.calories
            if (entry.createdAt > 0) fields["createdAt"] = entry.createdAt
            lists.getValue("calorieLogs").add(record("calorieLogs", entry.id.toString(), fields))
        }

        // Emit tombstones for anything we tracked before but can no longer see locally.
        val live = stamps.keys.toSet()
        for ((key, previous) in tracked) {
            if (key in live || previous.deleted) continue
            val kind = key.substringBefore(':')
            val list = lists[kind] ?: continue
            val tombstone = LinkedHashMap<String, Any>()
            tombstone["id"] = key.substringAfter(':')
            tombstone["deleted"] = true
            tombstone["updatedAt"] = now
            list.add(tombstone)
            stamps[key] = SyncStamp(previous.fingerprint, now, deleted = true)
        }

        // Keep tombstones on later pushes so a device that was offline still learns about them.
        for ((key, previous) in tracked) {
            if (!previous.deleted || key in stamps) continue
            val kind = key.substringBefore(':')
            val list = lists[kind] ?: continue
            val tombstone = LinkedHashMap<String, Any>()
            tombstone["id"] = key.substringAfter(':')
            tombstone["deleted"] = true
            tombstone["updatedAt"] = previous.updatedAt
            list.add(tombstone)
            stamps[key] = previous
        }

        return SyncBuilt(data = lists, stamps = stamps)
    }

    private fun stamp(
        tracked: Map<String, SyncStamp>,
        key: String,
        fingerprint: String,
        now: Long,
        out: MutableMap<String, SyncStamp>
    ): Long {
        val previous = tracked[key]
        val updatedAt = if (previous?.fingerprint == fingerprint && !previous.deleted) previous.updatedAt else now
        out[key] = SyncStamp(fingerprint, updatedAt)
        return updatedAt
    }
}
