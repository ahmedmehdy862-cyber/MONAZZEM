package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Two ids live side by side, with different jobs:
 *
 * - [id] is the anonymous installation id created on first launch. It is never regenerated, so an
 *   app update does not break the old message cache or the dashboard's existing records.
 * - [profileId] is issued by the server and kept after registration. It is the stable address for
 *   one person, which can outlive a single installation in later phases.
 *
 * Neither one is an account and neither carries personal data. The team still looks up the short
 * [code] in the registry; the server resolves that code to the profile id before storing a message.
 */
object UserIdentity {
    internal const val PREFS = "focus_team_messages"
    private const val KEY = "user_id"
    private const val KEY_PROFILE = "profile_id"
    private val PROFILE_ID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns the one install id for this app installation. Synchronised because the message check
     * and the registration effect can start together on a cold launch; a check-then-act with
     * `apply()` could otherwise let both see "no id" and save two different ids. `commit()` makes
     * the id durable before it is sent to the server or shown to the person.
     */
    @Synchronized
    fun id(context: Context): String {
        // P-04: معرّف التثبيت في تفضيلات مشفّرة (مع ترحيل آمن من العادي).
        val prefs = SecurePrefs.prefs(context, PREFS)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).commit()
        return created
    }

    /** Empty until the first successful registration; never invented on the device. */
    @Synchronized
    fun profileId(context: Context): String =
        SecurePrefs.prefs(context, PREFS)
            .getString(KEY_PROFILE, null).orEmpty().trim()

    /** P-02: يمسح معرّف الحساب المحفوظ (بعد حذف الحساب من الخادم). */
    @Synchronized
    internal fun clearProfileId(context: Context) {
        SecurePrefs.prefs(context, PREFS)
            .edit().remove(KEY_PROFILE).remove(UserRegistry.KEY_SIGNATURE).commit()
    }

    /** Keeps only a server-issued id, so a bad response cannot become a delivery address. */
    @Synchronized
    internal fun storeProfileId(context: Context, value: String) {
        val clean = value.trim().lowercase()
        if (!isValidProfileId(clean)) return
        SecurePrefs.prefs(context, PREFS)
            .edit().putString(KEY_PROFILE, clean).commit()
    }

    internal fun isValidProfileId(value: String): Boolean = PROFILE_ID_PATTERN.matches(value)

    /**
     * A code the person can read over the phone: eight characters of the install id, grouped.
     *
     * The dashboard looks this code up in the user registry and resolves it to the profile id
     * before storing a message, so only the addressed person can read it — the code alone is not a
     * delivery address.
     */
    fun code(context: Context): String = codeOf(id(context))

    internal fun codeOf(rawId: String): String {
        val clean = rawId.lowercase().filter { it.isLetterOrDigit() }.take(8).uppercase()
        return if (clean.length == 8) clean.chunked(4).joinToString("-") else clean
    }
}

/**
 * Quietly introduces this install to the team dashboard.
 *
 * There is no sign-up form and no extra tap: the app sends the anonymous install id, the profile id
 * the server issued on an earlier attempt (if any), the name the person already typed in «ملفك
 * الشخصي», and the current version. Nothing else leaves the device. The server returns the profile
 * id, which is saved locally and sent on later attempts; the dashboard still lists the person under
 * the short code shown in «رسائل الفريق», so the team never asks for a phone number or email address.
 *
 * H2: the profile id sent here is ownership proof, not a suggestion - the server never adopts a
 * client-suggested id, and it returns an existing profile id only to a caller that presents the
 * stored id (or the sync passphrase). A 409 (already registered without proof) simply leaves the
 * local id untouched and the throttled retry applies.
 *
 * The request is fire-and-forget. A failed attempt is remembered as "not registered" and retried on
 * the next app open or profile change, with a short throttle so an offline device never loops.
 */
internal object UserRegistry {
    internal const val KEY_SIGNATURE = "registered_signature"
    private const val KEY_LAST_ATTEMPT = "register_last_attempt_ms"
    private const val RETRY_DELAY_MS = 60_000L

    private val mutex = Mutex()

    private fun signature(
        uid: String,
        hasProfileId: Boolean,
        prefs: FocusPreferences,
        versionCode: Int
    ): String = listOf(
        uid,
        hasProfileId.toString(),
        prefs.studentName,
        prefs.profile.key,
        versionCode.toString()
    ).joinToString("\u001f")

    /**
     * P-02: يحذف صف المستخدم ورسائل دعمه من الخادم (إثبات الملكية = معرّف
     * الحساب المحفوظ). يعيد false عند أي فشل؛ والمتصل لا يمسح الهوية
     * المحلية إلا بعد نجاح حقيقي.
     */
    suspend fun deleteAccount(context: Context): Boolean = withContext(Dispatchers.IO) {
        val uid = UserIdentity.id(context)
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext false
        val payload = JSONObject()
            .put("action", "delete")
            .put("uid", uid)
            .put("profileId", profileId)
        val response = runCatching {
            TeamMessages.request("${TeamMessages.BASE}/api/messages", "POST", payload.toString())
        }.getOrNull() ?: return@withContext false
        runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
    }

    /** [force] bypasses the retry throttle when the person's name or profile just changed. */
    suspend fun register(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = FocusPreferences.load(context)
            val uid = UserIdentity.id(context)
            val profileId = UserIdentity.profileId(context)
            val versionCode = TeamMessages.versionCode(context)
            // The extra profile flag makes an install registered by 0.29.0 introduce itself once
            // more after the update, so the server can issue the new id without a data migration.
            val signature = signature(uid, profileId.isNotBlank(), prefs, versionCode)

            val store = SecurePrefs.prefs(context, UserIdentity.PREFS)
            if (store.getString(KEY_SIGNATURE, null) == signature) return@withLock
            val now = System.currentTimeMillis()
            if (!force && now - store.getLong(KEY_LAST_ATTEMPT, 0L) < RETRY_DELAY_MS) return@withLock

            val payload = JSONObject()
                .put("action", "register")
                .put("uid", uid)
                .put("name", prefs.studentName.ifBlank { "مستخدم مُنظِّم" })
                .put("profile", prefs.profile.key)
                .put("versionName", TeamMessages.versionName(context))
                .put("versionCode", versionCode)
            if (profileId.isNotBlank()) payload.put("profileId", profileId)

            val result = runCatching {
                TeamMessages.request("${TeamMessages.BASE}/api/messages", "POST", payload.toString())
            }
            if (result.isSuccess) {
                val issued = runCatching {
                    JSONObject(result.getOrNull().orEmpty()).optString("profileId")
                }.getOrDefault("")
                UserIdentity.storeProfileId(context, issued)
            }

            store.edit()
                .putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
                .apply()
            if (result.isSuccess) {
                // Compute the saved signature after storing the issued id: the next launch then has
                // nothing to repeat, while a response without a valid id keeps the retry pending.
                val saved = signature(
                    uid,
                    UserIdentity.profileId(context).isNotBlank(),
                    prefs,
                    versionCode
                )
                store.edit().putString(KEY_SIGNATURE, saved).apply()
            }
        }
    }
}
