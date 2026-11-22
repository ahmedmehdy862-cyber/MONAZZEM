package com.focusstudy.poc

/**
 * Pure payload builder for Android → web sync. It never sees [StudyTask.notes] or a note body:
 * only the fields listed in the backend whitelist are copied into `data`.
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

internal object SyncPayload {

    fun build(
        tasks: List<StudyTask>,
        subjects: List<StudySubject>,
        types: List<TaskType>,
        tracked: Map<String, SyncStamp>,
        now: Long
    ): SyncBuilt {
        val stamps = LinkedHashMap<String, SyncStamp>()
        val tasksOut = ArrayList<Map<String, Any>>()
        for (task in tasks) {
            val id = task.id.toString()
            val fingerprint = listOf(
                task.title, task.plannedMinutes.toString(), task.category.key,
                task.priority.level.toString(), task.status.key, task.dueAt?.toString().orEmpty(),
                task.recurrence.key, task.subjectId?.toString().orEmpty(), task.typeId.orEmpty()
            ).joinToString("\u001f")
            val record = LinkedHashMap<String, Any>()
            record["id"] = id
            record["title"] = task.title
            record["minutes"] = task.plannedMinutes
            record["category"] = task.category.key
            record["priority"] = task.priority.level
            record["status"] = task.status.key
            record["recurrence"] = task.recurrence.key
            task.dueAt?.let { record["dueAt"] = it }
            task.subjectId?.let { record["subjectId"] = it.toString() }
            task.typeId?.let { record["typeId"] = it }
            record["updatedAt"] = stamp(tracked, "tasks:$id", fingerprint, now, stamps)
            tasksOut.add(record)
        }

        val subjectsOut = ArrayList<Map<String, Any>>()
        for (subject in subjects) {
            val id = subject.id.toString()
            val record = LinkedHashMap<String, Any>()
            record["id"] = id
            record["name"] = subject.name
            record["updatedAt"] = stamp(tracked, "subjects:$id", subject.name, now, stamps)
            subjectsOut.add(record)
        }

        val typesOut = ArrayList<Map<String, Any>>()
        for (type in types) {
            val fingerprint = type.name + "\u001f" + type.archived
            val record = LinkedHashMap<String, Any>()
            record["id"] = type.id
            record["name"] = type.name
            record["archived"] = type.archived
            record["updatedAt"] = stamp(tracked, "taskTypes:${type.id}", fingerprint, now, stamps)
            typesOut.add(record)
        }

        // Emit tombstones for anything we tracked before but can no longer see locally.
        val live = stamps.keys.toSet()
        for ((key, previous) in tracked) {
            if (key in live || previous.deleted) continue
            val id = key.substringAfter(':')
            val kind = key.substringBefore(':')
            val tombstone = LinkedHashMap<String, Any>()
            tombstone["id"] = id
            tombstone["deleted"] = true
            tombstone["updatedAt"] = now
            when (kind) {
                "tasks" -> tasksOut.add(tombstone)
                "subjects" -> subjectsOut.add(tombstone)
                "taskTypes" -> typesOut.add(tombstone)
            }
            stamps[key] = SyncStamp(previous.fingerprint, now, deleted = true)
        }

        // Keep tombstones on later pushes so a device that was offline still learns about them.
        for ((key, previous) in tracked) {
            if (!previous.deleted || key in stamps) continue
            stamps[key] = previous
            val id = key.substringAfter(':')
            val tombstone = LinkedHashMap<String, Any>()
            tombstone["id"] = id
            tombstone["deleted"] = true
            tombstone["updatedAt"] = previous.updatedAt
            when {
                key.startsWith("tasks:") -> tasksOut.add(tombstone)
                key.startsWith("subjects:") -> subjectsOut.add(tombstone)
                key.startsWith("taskTypes:") -> typesOut.add(tombstone)
            }
        }

        return SyncBuilt(
            data = mapOf("tasks" to tasksOut, "subjects" to subjectsOut, "taskTypes" to typesOut),
            stamps = stamps
        )
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
