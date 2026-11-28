package com.focusstudy.poc.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * عميل المزامنة — نسخة ويندوز من SyncAccount/SyncPayload/SyncApply في :app.
 *
 * مصدر الحقيقة: ~/workspace/windows-sync-contract.md (المجموعات العشر،
 * أشكال السجلات، قاعدة الدمج §5، معرّفات ويندوز §7).
 *
 * نفس النقطة (POST /api/sync)، ونفس الأفعال الثلاثة (register/pull/push)،
 * ونفس القواعد:
 *  - الخادم: update-site/api/sync.js + lib/sync-store.js.
 *  - كلمة المرور تُرسل في جسم الطلب عبر HTTPS فقط، وتُحفظ على الجهاز
 *    في Windows Credential Manager حصرًا (لا ملف ولا قاعدة بيانات) —
 *    انظر WindowsCredentialStore؛ فتستعيدها المزامنة التلقائية عند الفتح.
 *  - الدمج last-write-wins لكل سجل؛ والطابع الزمني المتساوي يُبقي الصف
 *    المحلي، فلا يعتمد الترتيب على من يتكلم أخيرًا.
 *  - البصمات تُحفظ فقط بعد أن يقبل الخادم الـ push، فلا يبدو تغيير محلي
 *    مزامَنًا بينما فشل إرساله.
 *  - الملاحظات («نوتة») وملاحظات المهام وملاحظة المعاملة المالية لا تعبر
 *    السلك أبدًا: البنّاء ينسخ فقط الحقول التي يقبلها الخادم (العقد §3).
 *  - `wird_settings` و`fitness_profile` و`exercise_logs` والجلسات
 *    والأحداث والتذكيرات محلية فقط في هذا الإصدار من العقد (§6).
 *
 * لا اعتماديات HTTP خارجية: java.net.http.HttpClient من JDK فقط.
 */
object SyncProtocol {
    const val BASE_URL = "https://update-site-sigma.vercel.app"
    const val SYNC_PATH = "/api/sync"

    /** المجموعات العشر، بنفس ترتيب الخادم (COLLECTIONS) وأندرويد. */
    val COLLECTIONS: List<String> = listOf(
        "tasks", "subjects", "taskTypes",
        "habits", "habitLogs", "wirdLogs", "healthLogs", "transactions", "moodLogs", "calorieLogs",
    )
}

/**
 * سجل مزامنة واحد. الحقول كلها اختيارية إلا `id`، لأن كل مجموعة تستخدم
 * subset مختلفًا (العقد §3)؛ والخادم يبيّض ما لا يعرفه ويسقطه بصمت.
 * `day` هو مفتاح السجلات اليومية (wird/health/mood) وجزء من معرّف habitLogs.
 */
@Serializable
data class SyncRecord(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val minutes: Int? = null,
    val category: String? = null,
    val priority: Int? = null,
    val status: String? = null,
    val dueAt: Long? = null,
    val recurrence: String? = null,
    val subjectId: String? = null,
    val typeId: String? = null,
    val archived: Boolean? = null,
    val createdAt: Long? = null,
    val habitId: String? = null,
    val day: String? = null,
    val pages: Int? = null,
    val surah: String? = null,
    val weight: Double? = null,
    val sleepHours: Double? = null,
    val waterCups: Int? = null,
    val exerciseMinutes: Int? = null,
    val kind: String? = null,
    val amount: Double? = null,
    val mood: Int? = null,
    val calories: Int? = null,
    val updatedAt: Long = 0,
    val deleted: Boolean? = null,
)

@Serializable
data class SyncData(
    val tasks: List<SyncRecord> = emptyList(),
    val subjects: List<SyncRecord> = emptyList(),
    val taskTypes: List<SyncRecord> = emptyList(),
    val habits: List<SyncRecord> = emptyList(),
    val habitLogs: List<SyncRecord> = emptyList(),
    val wirdLogs: List<SyncRecord> = emptyList(),
    val healthLogs: List<SyncRecord> = emptyList(),
    val transactions: List<SyncRecord> = emptyList(),
    val moodLogs: List<SyncRecord> = emptyList(),
    val calorieLogs: List<SyncRecord> = emptyList(),
) {
    fun byKind(kind: String): List<SyncRecord> = when (kind) {
        "tasks" -> tasks
        "subjects" -> subjects
        "taskTypes" -> taskTypes
        "habits" -> habits
        "habitLogs" -> habitLogs
        "wirdLogs" -> wirdLogs
        "healthLogs" -> healthLogs
        "transactions" -> transactions
        "moodLogs" -> moodLogs
        "calorieLogs" -> calorieLogs
        else -> emptyList()
    }
}

@Serializable
data class SyncSnapshot(
    val revision: Long = 0,
    val updatedAt: Long = 0,
    val data: SyncData = SyncData(),
)

@Serializable
data class SyncRequest(
    val action: String,
    val profileId: String,
    val passphrase: String,
    val data: SyncData? = null,
    val currentPassphrase: String? = null,
    val recoveryCode: String? = null,
    /** Re-seal guard (design §2.2, Option A): refuse unless the server revision still matches. */
    val baseRevision: Long? = null,
)

@Serializable
data class RegisterResponse(val ok: Boolean = false, val created: Boolean = false)

/** The outcome of a link/activation, plus whether the account was created by this call. */
data class LinkResult(val outcome: SyncOutcome, val created: Boolean)

@Serializable
data class SyncStamp(val fingerprint: String, val updatedAt: Long, val deleted: Boolean = false)

@Serializable
data class LastSync(val at: Long, val revision: Long, val ok: Boolean, val message: String)

sealed interface SyncOutcome {
    data class Ok(val snapshot: SyncSnapshot?) : SyncOutcome
    data class Error(val message: String, val status: Int = 0) : SyncOutcome
}

class SyncClient(
    private val baseUrl: String = SyncProtocol.BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * الربط بحساب موجود — بكلمة مروره الحالية فقط، أبدًا بكلمة مختلفة.
     *
     * إصلاح جذري لمسار الربط: الخادم (`update-site/api/sync.js`) يرفض
     * `register` لحساب موجود بلا `currentPassphrase` صحيح (401) — وأندرويد
     * لا يقع فيه لأنه يسجّل مرة واحدة عند إنشاء الحساب، بينما ويندوز
     * يستدعي `register` عند كل ربط. فربط ويندوز الأول لحساب سُجّل من
     * الهاتف كان يفشل بكلمة المرور الصحيحة نفسها. لذلك: عند 401 تُعاد
     * المحاولة مرة واحدة بـ currentPassphrase = passphrase نفسها — أي
     * «اربط بالحساب الموجود بكلمة مروره الحالية»؛ وتثبيت نفس القيمة عبر
     * setAccount لا يغيّرها. كلمة مرور خاطئة تفشل في المحاولتين معًا،
     * وحساب جديد ينجح من الأولى.
     *
     * تغيير كلمة المرور له مسار مخصص ([changePassphrase] + إعادة ختم) —
     * لا يمر من هنا أبدًا: إدخال كلمة جديدة في شاشة الدخول كان يغيّر
     * تجزئة المصادقة بلا إعادة ختم، فيكسر قراءة النسخة متى صارت مشفّرة
     * (v2، التصميم §2.3). لذلك لا يقبل `register` كلمة حالية مختلفة.
     */
    fun register(profileId: String, passphrase: String): LinkResult {
        val first = registerAttempt(profileId, passphrase, null)
        if (first is SyncOutcome.Error && first.status == 401) {
            return registerAttempt(profileId, passphrase, passphrase)
        }
        return first
    }

    private fun registerAttempt(profileId: String, passphrase: String, currentPassphrase: String?): LinkResult {
        val body = json.encodeToString(
            SyncRequest("register", profileId, passphrase, currentPassphrase = currentPassphrase)
        )
        return when (val result = post(body)) {
            is PostResult.Ok -> {
                val response = runCatching {
                    json.decodeFromString<RegisterResponse>(result.body)
                }.getOrDefault(RegisterResponse(false))
                if (response.ok) LinkResult(SyncOutcome.Ok(null), response.created)
                else LinkResult(SyncOutcome.Error("تعذّر ربط الحساب"), false)
            }
            is PostResult.Failed -> LinkResult(SyncOutcome.Error(result.message, result.status), false)
        }
    }

    /**
     * تغيير كلمة المرور (التصميم §2.2، الخيار أ): يبدّل تجزئة المصادقة على
     * الخادم فقط. المتصل يحدّث الخزنة بعد النجاح — لا قبله أبدًا — ثم يعيد
     * الختم عبر [SyncService.reseal]. كلمة جديدة بلا الحالية تُرفض بـ 401.
     */
    fun changePassphrase(profileId: String, currentPassphrase: String, newPassphrase: String): SyncOutcome {
        val body = json.encodeToString(
            SyncRequest(
                "changePassphrase", profileId, newPassphrase,
                currentPassphrase = currentPassphrase,
            )
        )
        return when (val result = post(body)) {
            is PostResult.Ok -> {
                val ok = runCatching {
                    json.decodeFromString<RegisterResponse>(result.body).ok
                }.getOrDefault(false)
                if (ok) SyncOutcome.Ok(null) else SyncOutcome.Error("تعذّر تغيير كلمة المرور")
            }
            is PostResult.Failed -> SyncOutcome.Error(result.message, result.status)
        }
    }

    /** يخزّن تجزئة scrypt لكود استرداد على الخادم؛ الكود نفسه لا يُخزَّن أبدًا. */
    fun setRecoveryCode(profileId: String, passphrase: String, code: String): SyncOutcome {
        val normalized = SyncRecoveryCode.normalize(code)
        if (normalized.isEmpty()) return SyncOutcome.Error("كود الاسترداد غير صحيح الصيغة")
        val body = json.encodeToString(
            SyncRequest("setRecoveryCode", profileId, passphrase, recoveryCode = normalized)
        )
        return when (val result = post(body)) {
            is PostResult.Ok -> {
                val ok = runCatching {
                    json.decodeFromString<RegisterResponse>(result.body).ok
                }.getOrDefault(false)
                if (ok) SyncOutcome.Ok(null) else SyncOutcome.Error("تعذّر حفظ كود الاسترداد")
            }
            is PostResult.Failed -> SyncOutcome.Error(result.message, result.status)
        }
    }

    /**
     * استرداد بكود لمرة واحدة (التصميم §3.2): عند النجاح حذف الخادم الحساب
     * ونسخته، فيعيد المتصل التسجيل بكلمة جديدة ويرفع بيانات هذا الجهاز.
     */
    fun resetWithRecoveryCode(profileId: String, code: String): SyncOutcome {
        val normalized = SyncRecoveryCode.normalize(code)
        if (normalized.isEmpty()) return SyncOutcome.Error("كود الاسترداد غير صحيح الصيغة")
        val body = json.encodeToString(
            SyncRequest("reset", profileId, "", recoveryCode = normalized)
        )
        return when (val result = post(body)) {
            is PostResult.Ok -> {
                val ok = runCatching {
                    json.decodeFromString<RegisterResponse>(result.body).ok
                }.getOrDefault(false)
                if (ok) SyncOutcome.Ok(null) else SyncOutcome.Error("تعذّر الاسترداد")
            }
            is PostResult.Failed -> SyncOutcome.Error(result.message, result.status)
        }
    }

    fun pull(profileId: String, passphrase: String): SyncOutcome {
        val body = json.encodeToString(SyncRequest("pull", profileId, passphrase))
        return when (val result = post(body)) {
            is PostResult.Ok -> runCatching {
                SyncOutcome.Ok(json.decodeFromString<SyncSnapshot>(result.body))
            }.getOrElse { SyncOutcome.Error("رد غير مفهوم من الخادم") }

            is PostResult.Failed -> SyncOutcome.Error(result.message, result.status)
        }
    }

    /**
     * نجاح الـ push يرجع اللقطة المدموجة (مع revision) لا علامة ok.
     * `baseRevision` حارس إعادة الختم (التصميم §2.2): يرفض الخادم بـ 409
     * إن تغيّرت المراجعة منذ أن قرأها العميل، فيعيد العميل السحب والمحاولة.
     */
    fun push(profileId: String, passphrase: String, data: SyncData, baseRevision: Long? = null): SyncOutcome {
        val body = json.encodeToString(
            SyncRequest("push", profileId, passphrase, data = data, baseRevision = baseRevision)
        )
        return when (val result = post(body)) {
            is PostResult.Ok -> runCatching {
                SyncOutcome.Ok(json.decodeFromString<SyncSnapshot>(result.body))
            }.getOrElse { SyncOutcome.Error("رد غير مفهوم من الخادم") }

            is PostResult.Failed -> SyncOutcome.Error(result.message, result.status)
        }
    }

    private sealed interface PostResult {
        data class Ok(val body: String) : PostResult
        data class Failed(val message: String, val status: Int = 0) : PostResult
    }

    private fun post(body: String): PostResult {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + SyncProtocol.SYNC_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PostResult.Failed("تعذّر الوصول إلى خادم المزامنة — تحقق من الاتصال")
        }
        if (response.statusCode() in 200..299) return PostResult.Ok(response.body())
        val error = response.body()
        return PostResult.Failed(
            when {
                response.statusCode() == 401 -> "بيانات الدخول غير صحيحة"
                response.statusCode() == 409 -> "تعارض في المراجعة — تغيّرت النسخة على الخادم؛ أعد المحاولة"
                response.statusCode() == 429 -> "محاولات كثيرة — أعد المحاولة بعد قليل"
                response.statusCode() == 503 -> "خدمة المزامنة غير متاحة الآن"
                error.contains("profile_unknown") -> "هذا الحساب غير معروف على الخادم"
                error.contains("invalid_passphrase") -> "كلمة المرور بين 10 و 200 حرف"
                error.contains("invalid_profile") -> "معرّف الحساب غير صالح"
                else -> "فشلت المزامنة (رمز ${response.statusCode()})"
            },
            status = response.statusCode(),
        )
    }
}

/**
 * بنّاء الحمولة — مرآة SyncPayload.kt في :app، حرفيًا في اصطلاح البصمة:
 * بصمة المحتوى القانونية = حقول السجل عدا `id`/`updatedAt`/`deleted`،
 * مرتبة بالمفتاح، والأرقام تُطبَّع (30 و30L و30.0 بصمة واحدة). نفس الدالة
 * تبصم السجل المحلي والسجل الوارد، فيعرف الدفع التالي أن سجلًا طُبّق من
 * الخادم لم يتغير، فلا يعيد ختمه بطابع جديد.
 *
 * حضور الحقول الاختيارية مطابق لأندرويد: createdAt فقط إن كان > 0،
 * وarchived فقط إن كان true، وdueAt/subjectId/typeId فقط عند وجودها،
 * وcategory في المعاملة فقط إن كانت غير فارغة. ملاحظة المعاملة لا تُنسخ
 * أبدًا (العقد §3.8 و§6).
 */
object SyncPayload {
    data class Built(val data: SyncData, val stamps: Map<String, SyncStamp>)

    /** بصمة قانونية — نفس SyncPayload.contentFingerprint في :app. */
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

    /** خريطة حقول سجل وارد، كما وصلت من الخادم بعد التبييض — للتبصيم. */
    fun recordFields(record: SyncRecord): Map<String, Any> {
        val fields = LinkedHashMap<String, Any>()
        record.title?.let { fields["title"] = it }
        record.name?.let { fields["name"] = it }
        record.minutes?.let { fields["minutes"] = it }
        record.category?.let { fields["category"] = it }
        record.priority?.let { fields["priority"] = it }
        record.status?.let { fields["status"] = it }
        record.dueAt?.let { fields["dueAt"] = it }
        record.recurrence?.let { fields["recurrence"] = it }
        record.subjectId?.let { fields["subjectId"] = it }
        record.typeId?.let { fields["typeId"] = it }
        if (record.archived == true) fields["archived"] = true
        record.createdAt?.let { fields["createdAt"] = it }
        record.habitId?.let { fields["habitId"] = it }
        record.day?.let { fields["day"] = it }
        record.pages?.let { fields["pages"] = it }
        record.surah?.let { fields["surah"] = it }
        record.weight?.let { fields["weight"] = it }
        record.sleepHours?.let { fields["sleepHours"] = it }
        record.waterCups?.let { fields["waterCups"] = it }
        record.exerciseMinutes?.let { fields["exerciseMinutes"] = it }
        record.kind?.let { fields["kind"] = it }
        record.amount?.let { fields["amount"] = it }
        record.mood?.let { fields["mood"] = it }
        record.calories?.let { fields["calories"] = it }
        return fields
    }

    /**
     * مع `forceRefresh` يُعاد ختم كل سجل حي بـ `now` حتى لو لم يتغير محتواه — خطوة
     * إعادة الختم عند تغيير كلمة المرور (التصميم §2.2، الخيار أ). على v1 السجلات
     * نص صريح فالرفع يعيد تأكيدها فقط، لكن قاعدة الدمج في الخادم تُبقي الصف
     * المخزَّن عند تساوي الطابع الزمني، فبلا الرفع يُسقَط إعادة الختم بصمت.
     */
    fun build(
        repo: DesktopRepository,
        tracked: Map<String, SyncStamp>,
        nowInput: Long,
        forceRefresh: Boolean = false,
    ): Built {
        val now = maxOf(nowInput, (tracked.values.maxOfOrNull { it.updatedAt } ?: 0L) + 1L)
        val stamps = LinkedHashMap<String, SyncStamp>()
        val lists = LinkedHashMap<String, MutableList<SyncRecord>>()
        for (kind in SyncProtocol.COLLECTIONS) lists[kind] = ArrayList()

        /** يبني سجلًا حيًا: id + حقول + ختم يُحفظ من tracked ما دام لم يتغير. */
        fun record(kind: String, id: String, fields: Map<String, Any>): SyncRecord {
            val updatedAt = stamp(tracked, "$kind:$id", contentFingerprint(fields), now, stamps, forceRefresh)
            return toRecord(id, fields, updatedAt)
        }

        for (task in repo.tasks()) {
            val fields = LinkedHashMap<String, Any>()
            fields["title"] = task.title
            fields["minutes"] = task.plannedMinutes
            fields["category"] = task.category
            fields["priority"] = task.priority
            fields["status"] = task.status
            fields["recurrence"] = task.recurrence
            task.dueAt?.let { fields["dueAt"] = it }
            task.subjectId?.let { fields["subjectId"] = it.toString() }
            task.typeId?.let { fields["typeId"] = it }
            lists.getValue("tasks").add(record("tasks", task.id.toString(), fields))
        }
        for (subject in repo.subjects()) {
            lists.getValue("subjects").add(
                record("subjects", subject.id.toString(), mapOf("name" to subject.name))
            )
        }
        for (type in repo.taskTypes()) {
            val fields = LinkedHashMap<String, Any>()
            fields["name"] = type.name
            if (type.archived) fields["archived"] = true
            lists.getValue("taskTypes").add(record("taskTypes", type.id, fields))
        }
        for (habit in repo.allHabits()) {
            val fields = LinkedHashMap<String, Any>()
            fields["name"] = habit.name
            if (habit.createdAt > 0) fields["createdAt"] = habit.createdAt
            if (habit.archived) fields["archived"] = true
            lists.getValue("habits").add(record("habits", habit.id.toString(), fields))
        }
        for (log in repo.allHabitLogs()) {
            lists.getValue("habitLogs").add(
                record(
                    "habitLogs", "${log.habitId}:${log.day}",
                    mapOf("habitId" to log.habitId.toString(), "day" to log.day),
                )
            )
        }
        for (log in repo.allWirdLogs()) {
            lists.getValue("wirdLogs").add(
                record(
                    "wirdLogs", log.day,
                    mapOf("day" to log.day, "pages" to log.pages, "surah" to log.surah),
                )
            )
        }
        for (log in repo.allHealthLogs()) {
            val fields = LinkedHashMap<String, Any>()
            fields["day"] = log.day
            log.weight?.let { fields["weight"] = it }
            log.sleepHours?.let { fields["sleepHours"] = it }
            fields["waterCups"] = log.waterCups
            fields["exerciseMinutes"] = log.exerciseMinutes
            lists.getValue("healthLogs").add(record("healthLogs", log.day, fields))
        }
        // ملاحظة المعاملة لا تُنسخ أبدًا — تبقى على جهازها، مثل ملاحظات المهام.
        for (tx in repo.allTransactions()) {
            val fields = LinkedHashMap<String, Any>()
            fields["kind"] = tx.kind
            fields["amount"] = tx.amount
            if (tx.category.isNotBlank()) fields["category"] = tx.category
            fields["day"] = tx.day
            if (tx.createdAt > 0) fields["createdAt"] = tx.createdAt
            lists.getValue("transactions").add(record("transactions", tx.id.toString(), fields))
        }
        for (log in repo.allMoodLogs()) {
            lists.getValue("moodLogs").add(
                record("moodLogs", log.day, mapOf("day" to log.day, "mood" to log.mood))
            )
        }
        for (entry in repo.allCalorieLogs()) {
            val fields = LinkedHashMap<String, Any>()
            fields["day"] = entry.day
            fields["name"] = entry.name
            fields["calories"] = entry.calories
            if (entry.createdAt > 0) fields["createdAt"] = entry.createdAt
            lists.getValue("calorieLogs").add(record("calorieLogs", entry.id.toString(), fields))
        }

        // شواهد حذف للسجلات التي كانت متعقَّبة واختفت محليًا.
        val live = stamps.keys.toSet()
        for ((key, previous) in tracked) {
            if (key in live || previous.deleted) continue
            val kind = key.substringBefore(':')
            val list = lists[kind] ?: continue
            list.add(SyncRecord(id = key.substringAfter(':'), deleted = true, updatedAt = now))
            stamps[key] = SyncStamp(previous.fingerprint, now, deleted = true)
        }
        // أبقِ الشواهد القديمة في الحمولات التالية حتى يعرف عنها جهاز كان غائبًا.
        for ((key, previous) in tracked) {
            if (!previous.deleted || key in stamps) continue
            val kind = key.substringBefore(':')
            val list = lists[kind] ?: continue
            list.add(SyncRecord(id = key.substringAfter(':'), deleted = true, updatedAt = previous.updatedAt))
            stamps[key] = previous
        }

        return Built(
            data = SyncData(
                tasks = lists.getValue("tasks"),
                subjects = lists.getValue("subjects"),
                taskTypes = lists.getValue("taskTypes"),
                habits = lists.getValue("habits"),
                habitLogs = lists.getValue("habitLogs"),
                wirdLogs = lists.getValue("wirdLogs"),
                healthLogs = lists.getValue("healthLogs"),
                transactions = lists.getValue("transactions"),
                moodLogs = lists.getValue("moodLogs"),
                calorieLogs = lists.getValue("calorieLogs"),
            ),
            stamps = stamps,
        )
    }

    private fun stamp(
        tracked: Map<String, SyncStamp>, key: String, fingerprint: String, now: Long,
        out: MutableMap<String, SyncStamp>, forceRefresh: Boolean = false,
    ): Long {
        val previous = tracked[key]
        val updatedAt = if (forceRefresh) now
            else if (previous?.fingerprint == fingerprint && !previous.deleted) previous.updatedAt
            else now
        out[key] = SyncStamp(fingerprint, updatedAt)
        return updatedAt
    }

    /** يحوّل خريطة حقول إلى SyncRecord للسلك. */
    private fun toRecord(id: String, fields: Map<String, Any>, updatedAt: Long): SyncRecord =
        SyncRecord(
            id = id,
            title = fields["title"] as? String,
            name = fields["name"] as? String,
            minutes = (fields["minutes"] as? Number)?.toInt(),
            category = fields["category"] as? String,
            priority = (fields["priority"] as? Number)?.toInt(),
            status = fields["status"] as? String,
            dueAt = (fields["dueAt"] as? Number)?.toLong(),
            recurrence = fields["recurrence"] as? String,
            subjectId = fields["subjectId"] as? String,
            typeId = fields["typeId"] as? String,
            archived = fields["archived"] as? Boolean,
            createdAt = (fields["createdAt"] as? Number)?.toLong(),
            habitId = fields["habitId"] as? String,
            day = fields["day"] as? String,
            pages = (fields["pages"] as? Number)?.toInt(),
            surah = fields["surah"] as? String,
            weight = (fields["weight"] as? Number)?.toDouble(),
            sleepHours = (fields["sleepHours"] as? Number)?.toDouble(),
            waterCups = (fields["waterCups"] as? Number)?.toInt(),
            exerciseMinutes = (fields["exerciseMinutes"] as? Number)?.toInt(),
            kind = fields["kind"] as? String,
            amount = (fields["amount"] as? Number)?.toDouble(),
            mood = (fields["mood"] as? Number)?.toInt(),
            calories = (fields["calories"] as? Number)?.toInt(),
            updatedAt = updatedAt,
        )
}

/**
 * مخطط تطبيق pull — مرآة SyncApply.plan في :app (العقد §5):
 * - الوارد أحدث من ختمنا لذلك المفتاح يفوز: upsert، أو حذف عند التومبستون.
 * - عند تساوي updatedAt يفوز المحلي؛ والدفع التالي يعيد تأكيده.
 * - سجل بلا ختم وبلا صف محلي يُطبَّق ويُعتمد ختمه كما هو.
 * - سجل بلا ختم وله صف محلي بمحتوى مطابق يعتمد الختم فقط (بلا كتابة)؛
 *   وإن اختلف المحتوى يُبقي المحلي — الدفع التالي يختمه بطابع جديد فيفوز
 *   على الخادم. بيانات محلية لم تُزامَن لا يُكتب فوقها pull أبدًا.
 * - أختام السجلات المطبَّقة تؤخذ من السجل الوارد (بصمته وupdatedAt)، ولا
 *   يعاد ختمها بـ now: هذا ما يمنع عودة صف حذفه تومبستون في الدفع التالي.
 */
object SyncApply {
    data class Plan(
        val upserts: Map<String, List<SyncRecord>>,
        val deletes: Map<String, List<String>>,
        val stamps: Map<String, SyncStamp>,
    )

    fun plan(
        repo: DesktopRepository,
        tracked: Map<String, SyncStamp>,
        incoming: SyncData,
    ): Plan {
        val upserts = LinkedHashMap<String, MutableList<SyncRecord>>()
        val deletes = LinkedHashMap<String, MutableList<String>>()
        val stamps = LinkedHashMap<String, SyncStamp>(tracked)
        val local = LocalContentMaps(repo)

        for (kind in SyncProtocol.COLLECTIONS) {
            for (record in incoming.byKind(kind)) {
                val id = record.id
                if (id.isEmpty()) continue
                val key = "$kind:$id"
                val updatedAt = record.updatedAt
                val deleted = record.deleted == true
                val fingerprint = SyncPayload.contentFingerprint(SyncPayload.recordFields(record))
                val stamp = tracked[key]
                val localFields = local.fields(kind, id)

                val apply = when {
                    stamp != null -> updatedAt > stamp.updatedAt
                    localFields == null -> true
                    SyncPayload.contentFingerprint(localFields) == fingerprint -> {
                        // نفس المحتوى، وأول مرة نرى ختمه: نعتمد الختم بلا كتابة.
                        stamps[key] = SyncStamp(fingerprint, updatedAt)
                        false
                    }
                    else -> false // تغيير محلي غير مزامَن يفوز؛ الدفع التالي يختمه.
                }
                if (!apply) continue
                if (deleted) {
                    deletes.getOrPut(kind) { ArrayList() }.add(id)
                    stamps[key] = SyncStamp(fingerprint, updatedAt, deleted = true)
                } else {
                    upserts.getOrPut(kind) { ArrayList() }.add(record)
                    stamps[key] = SyncStamp(fingerprint, updatedAt)
                }
            }
        }
        return Plan(upserts = upserts, deletes = deletes, stamps = stamps)
    }

    /** خرائط محتوى السجلات المحلية (id + حقول، بلا updatedAt) للمقارنة. */
    private class LocalContentMaps(repo: DesktopRepository) {
        private val maps: MutableMap<String, MutableMap<String, Map<String, Any>>> =
            mutableMapOf<String, MutableMap<String, Map<String, Any>>>().apply {
            for (kind in SyncProtocol.COLLECTIONS) {
                put(kind, LinkedHashMap<String, Map<String, Any>>())
            }
            for (task in repo.tasks()) {
                val fields = LinkedHashMap<String, Any>()
                fields["title"] = task.title
                fields["minutes"] = task.plannedMinutes
                fields["category"] = task.category
                fields["priority"] = task.priority
                fields["status"] = task.status
                fields["recurrence"] = task.recurrence
                task.dueAt?.let { fields["dueAt"] = it }
                task.subjectId?.let { fields["subjectId"] = it.toString() }
                task.typeId?.let { fields["typeId"] = it }
                getValue("tasks")[task.id.toString()] = fields
            }
            for (subject in repo.subjects()) {
                getValue("subjects")[subject.id.toString()] = mapOf("name" to subject.name)
            }
            for (type in repo.taskTypes()) {
                val fields = LinkedHashMap<String, Any>()
                fields["name"] = type.name
                if (type.archived) fields["archived"] = true
                getValue("taskTypes")[type.id] = fields
            }
            for (habit in repo.allHabits()) {
                val fields = LinkedHashMap<String, Any>()
                fields["name"] = habit.name
                if (habit.createdAt > 0) fields["createdAt"] = habit.createdAt
                if (habit.archived) fields["archived"] = true
                getValue("habits")[habit.id.toString()] = fields
            }
            for (log in repo.allHabitLogs()) {
                getValue("habitLogs")["${log.habitId}:${log.day}"] =
                    mapOf("habitId" to log.habitId.toString(), "day" to log.day)
            }
            for (log in repo.allWirdLogs()) {
                getValue("wirdLogs")[log.day] =
                    mapOf("day" to log.day, "pages" to log.pages, "surah" to log.surah)
            }
            for (log in repo.allHealthLogs()) {
                val fields = LinkedHashMap<String, Any>()
                fields["day"] = log.day
                log.weight?.let { fields["weight"] = it }
                log.sleepHours?.let { fields["sleepHours"] = it }
                fields["waterCups"] = log.waterCups
                fields["exerciseMinutes"] = log.exerciseMinutes
                getValue("healthLogs")[log.day] = fields
            }
            for (tx in repo.allTransactions()) {
                val fields = LinkedHashMap<String, Any>()
                fields["kind"] = tx.kind
                fields["amount"] = tx.amount
                if (tx.category.isNotBlank()) fields["category"] = tx.category
                fields["day"] = tx.day
                if (tx.createdAt > 0) fields["createdAt"] = tx.createdAt
                getValue("transactions")[tx.id.toString()] = fields
            }
            for (log in repo.allMoodLogs()) {
                getValue("moodLogs")[log.day] = mapOf("day" to log.day, "mood" to log.mood)
            }
            for (entry in repo.allCalorieLogs()) {
                val fields = LinkedHashMap<String, Any>()
                fields["day"] = entry.day
                fields["name"] = entry.name
                fields["calories"] = entry.calories
                if (entry.createdAt > 0) fields["createdAt"] = entry.createdAt
                getValue("calorieLogs")[entry.id.toString()] = fields
            }
        }

        fun fields(kind: String, id: String): Map<String, Any>? = maps[kind]?.get(id)
    }

    /** تنفيذ المخطط على قاعدة البيانات المحلية. */
    fun execute(repo: DesktopRepository, plan: Plan) {
        for ((kind, ids) in plan.deletes) {
            for (id in ids) deleteLocal(repo, kind, id)
        }
        for ((kind, records) in plan.upserts) {
            for (record in records) upsertLocal(repo, kind, record)
        }
    }

    private fun deleteLocal(repo: DesktopRepository, kind: String, id: String) {
        when (kind) {
            "tasks" -> id.toLongOrNull()?.let { repo.deleteTask(it) }
            "subjects" -> id.toLongOrNull()?.let { repo.deleteSubject(it) }
            "taskTypes" -> repo.deleteTaskType(id)
            "habits" -> id.toLongOrNull()?.let { repo.deleteHabit(it) }
            "habitLogs" -> {
                val habitId = id.substringBefore(':').toLongOrNull() ?: return
                val day = id.substringAfter(':', "")
                if (day.isNotEmpty()) repo.deleteHabitLog(habitId, day)
            }
            "wirdLogs" -> repo.deleteWirdLog(id)
            "healthLogs" -> repo.deleteHealthLog(id)
            "transactions" -> id.toLongOrNull()?.let { repo.deleteTransaction(it) }
            "moodLogs" -> repo.deleteMoodLog(id)
            "calorieLogs" -> id.toLongOrNull()?.let { repo.deleteCalorieEntry(it) }
        }
    }

    private fun upsertLocal(repo: DesktopRepository, kind: String, record: SyncRecord) {
        when (kind) {
            "tasks" -> {
                val id = record.id.toLongOrNull() ?: return
                val local = repo.task(id)
                val done = record.status == "done"
                val completedAt = if (done) local?.completedAt ?: record.updatedAt else null
                if (local == null) {
                    repo.insertTaskFromSync(
                        id = id, title = record.title.orEmpty(),
                        minutes = (record.minutes ?: 25).coerceIn(1, 720),
                        category = record.category ?: "study",
                        priority = (record.priority ?: 1).coerceIn(0, 3),
                        status = record.status ?: "todo",
                        dueAt = record.dueAt, recurrence = record.recurrence ?: "none",
                        subjectId = record.subjectId?.toLongOrNull(),
                        typeId = record.typeId, isDone = done,
                        createdAt = record.updatedAt, completedAt = completedAt,
                    )
                } else {
                    repo.updateTaskFromSync(
                        id = id, title = record.title.orEmpty(),
                        minutes = (record.minutes ?: local.plannedMinutes).coerceIn(1, 720),
                        category = record.category ?: local.category,
                        priority = (record.priority ?: local.priority).coerceIn(0, 3),
                        status = record.status ?: local.status,
                        dueAt = record.dueAt, recurrence = record.recurrence ?: local.recurrence,
                        subjectId = record.subjectId?.toLongOrNull(),
                        typeId = record.typeId, isDone = done, completedAt = completedAt,
                    )
                }
            }
            "subjects" -> {
                val id = record.id.toLongOrNull() ?: return
                val name = record.name.orEmpty()
                if (name.isBlank()) return
                if (repo.subjects().none { it.id == id }) {
                    repo.insertSubjectFromSync(id, name, record.updatedAt)
                } else {
                    repo.updateSubjectFromSync(id, name)
                }
            }
            "taskTypes" -> {
                val name = record.name.orEmpty()
                if (name.isBlank()) return
                if (repo.taskTypes().none { it.id == record.id }) {
                    repo.insertTaskTypeFromSync(record.id, name, record.archived == true)
                } else {
                    repo.updateTaskTypeFromSync(record.id, name, record.archived == true)
                }
            }
            "habits" -> {
                val id = record.id.toLongOrNull() ?: return
                val name = record.name.orEmpty()
                if (name.isBlank()) return
                if (repo.allHabits().none { it.id == id }) {
                    repo.insertHabitFromSync(id, name, record.createdAt ?: record.updatedAt, record.archived == true)
                } else {
                    repo.updateHabitFromSync(id, name, record.archived == true)
                }
            }
            "habitLogs" -> {
                // id مشتق "habitId:day"؛ وإن غاب يُشتق من الحقلين (كما يفعل الخادم).
                val habitId = record.habitId?.toLongOrNull()
                    ?: record.id.substringBefore(':').toLongOrNull() ?: return
                val day = record.day ?: record.id.substringAfter(':', "")
                if (day.isEmpty()) return
                repo.insertHabitLogFromSync(habitId, day)
            }
            "wirdLogs" -> {
                val day = record.day ?: record.id
                val pages = (record.pages ?: 0).coerceIn(1, 604)
                repo.upsertWirdFromSync(day, pages, record.surah.orEmpty())
            }
            "healthLogs" -> {
                val day = record.day ?: record.id
                repo.upsertHealthFromSync(
                    HealthLog(
                        day = day,
                        weight = record.weight?.takeIf { it in 20.0..500.0 },
                        sleepHours = record.sleepHours?.takeIf { it in 0.0..24.0 },
                        waterCups = (record.waterCups ?: 0).coerceIn(0, 100),
                        exerciseMinutes = (record.exerciseMinutes ?: 0).coerceIn(0, 1440),
                    )
                )
            }
            "transactions" -> {
                val id = record.id.toLongOrNull() ?: return
                val kind = record.kind ?: return
                if (kind != "income" && kind != "expense") return
                val amount = record.amount ?: return
                if (amount <= 0 || amount > 1e12) return
                val day = record.day ?: return
                val existing = repo.transactionById(id)
                if (existing == null) {
                    // الملاحظة لا تعبر السلك أبدًا: الوارد بلا note، والمحلي
                    // غير موجود أصلًا — تُحفظ فارغة.
                    repo.insertTransactionFromSync(
                        MoneyTransaction(
                            id = id, kind = kind, amount = amount,
                            category = record.category.orEmpty(), note = "",
                            day = day, createdAt = record.createdAt ?: record.updatedAt,
                        )
                    )
                } else {
                    // تحديث الحقول المتزامنة فقط؛ note المحلية لا تُمس.
                    repo.deleteTransaction(id)
                    repo.insertTransactionFromSync(
                        existing.copy(
                            kind = kind, amount = amount,
                            category = record.category.orEmpty(), day = day,
                        )
                    )
                }
            }
            "moodLogs" -> {
                val day = record.day ?: record.id
                val mood = (record.mood ?: 0).coerceIn(1, 5)
                repo.upsertMoodFromSync(day, mood)
            }
            "calorieLogs" -> {
                val id = record.id.toLongOrNull() ?: return
                val day = record.day ?: return
                val name = record.name.orEmpty()
                if (name.isBlank()) return
                val calories = (record.calories ?: 0).coerceIn(1, 10000)
                val existing = repo.calorieEntryById(id)
                if (existing == null) {
                    repo.insertCalorieFromSync(
                        CalorieEntry(id, day, name, calories, record.createdAt ?: record.updatedAt)
                    )
                } else {
                    repo.deleteCalorieEntry(id)
                    repo.insertCalorieFromSync(
                        CalorieEntry(id, day, name, calories, existing.createdAt)
                    )
                }
            }
        }
    }
}

/** دورة «مزامنة الآن»: pull ← تخطيط وتطبيق ← push، مع حفظ البصمات بعد القبول فقط. */
class SyncService(
    private val repo: DesktopRepository,
    private val client: SyncClient = SyncClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadStamps(): Map<String, SyncStamp> =
        repo.syncMeta("stamps")?.let {
            runCatching { json.decodeFromString<Map<String, SyncStamp>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()

    fun lastSync(): LastSync? =
        repo.syncMeta("last_sync")?.let {
            runCatching { json.decodeFromString<LastSync>(it) }.getOrNull()
        }

    private fun saveLastSync(value: LastSync) {
        repo.putSyncMeta("last_sync", json.encodeToString(value))
    }

    /**
     * إعادة الختم بعد تغيير كلمة المرور (التصميم §2.2، الخيار أ): سحب لقراءة أحدث
     * مراجعة، ثم دفع كل السجلات المحلية بختم +1ms ومع `baseRevision`؛ عند 409
     * يعيد السحب والمحاولة حتى ثلاث مرات. تُستدعى بعد أن حدّث المتصل الخزنة
     * بكلمة المرور الجديدة — لا قبلها أبدًا.
     */
    fun reseal(session: SyncSession): SyncOutcome {
        val profileId = session.profileId ?: return SyncOutcome.Error("الحساب غير مرتبط")
        val passphrase = session.passphrase()
        if (passphrase.isBlank()) return SyncOutcome.Error("أدخل كلمة مرور المزامنة")
        repeat(3) { attempt ->
            val pulled = client.pull(profileId, passphrase)
            val snapshot = when (pulled) {
                is SyncOutcome.Error -> return pulled
                is SyncOutcome.Ok -> pulled.snapshot ?: SyncSnapshot()
            }
            val plan = SyncApply.plan(repo, loadStamps(), snapshot.data)
            SyncApply.execute(repo, plan)
            val built = SyncPayload.build(repo, plan.stamps, System.currentTimeMillis(), forceRefresh = true)
            val pushed = client.push(profileId, passphrase, built.data, baseRevision = snapshot.revision)
            when (pushed) {
                is SyncOutcome.Ok -> {
                    repo.putSyncMeta("stamps", json.encodeToString(built.stamps))
                    val revision = pushed.snapshot?.revision ?: snapshot.revision
                    saveLastSync(LastSync(System.currentTimeMillis(), revision, true, "تمت المزامنة"))
                    return pushed
                }
                is SyncOutcome.Error -> {
                    if (pushed.status == 409 && attempt < 2) {
                        // كاتب متزامن سبقنا: نعيد السحب والختم من المراجعة الجديدة.
                    } else {
                        saveLastSync(LastSync(System.currentTimeMillis(), snapshot.revision, false, pushed.message))
                        return pushed
                    }
                }
            }
        }
        return SyncOutcome.Error("تعذّرت إعادة الرفع — ستكتمل مع المزامنة التالية")
    }

    fun syncNow(session: SyncSession): SyncOutcome {
        val profileId = session.profileId ?: return SyncOutcome.Error("الحساب غير مرتبط")
        val passphrase = session.passphrase()
        if (passphrase.isBlank()) return SyncOutcome.Error("أدخل كلمة مرور المزامنة")
        // التزامن متسلسل لكل حساب (العقد §5): لا push وpull متوازيين أبدًا.
        val pulled = client.pull(profileId, passphrase)
        val snapshot = when (pulled) {
            is SyncOutcome.Error -> {
                saveLastSync(LastSync(System.currentTimeMillis(), 0, false, pulled.message))
                return pulled
            }
            is SyncOutcome.Ok -> pulled.snapshot ?: SyncSnapshot()
        }
        val plan = SyncApply.plan(repo, loadStamps(), snapshot.data)
        SyncApply.execute(repo, plan)
        val built = SyncPayload.build(repo, plan.stamps, System.currentTimeMillis())
        val pushed = client.push(profileId, passphrase, built.data)
        return when (pushed) {
            is SyncOutcome.Error -> {
                saveLastSync(LastSync(System.currentTimeMillis(), snapshot.revision, false, pushed.message))
                pushed
            }
            is SyncOutcome.Ok -> {
                // حُفظ البصمات بعد قبول الخادم فقط (قاعدة أندرويد نفسها).
                repo.putSyncMeta("stamps", json.encodeToString(built.stamps))
                val revision = pushed.snapshot?.revision ?: snapshot.revision
                saveLastSync(LastSync(System.currentTimeMillis(), revision, true, "تمت المزامنة"))
                SyncOutcome.Ok(pushed.snapshot)
            }
        }
    }
}
