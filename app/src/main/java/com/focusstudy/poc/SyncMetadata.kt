package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences

/**
 * Last sent fingerprint per record, kept outside the app database so it never forces a `DB_VERSION` bump.
 * Nothing here stores a passphrase, a device id or note content.
 */
internal class SyncMetadata(private val prefs: SharedPreferences) {

    fun tracked(): Map<String, SyncStamp> {
        val out = HashMap<String, SyncStamp>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(PREFIX) || value !is String) continue
            val parts = value.split(SEPARATOR)
            if (parts.size != 3) continue
            val updatedAt = parts[1].toLongOrNull() ?: continue
            out[key.removePrefix(PREFIX)] = SyncStamp(parts[0], updatedAt, parts[2] == "1")
        }
        return out
    }

    /** P-02: يمسح كل الأختام المحلية (بعد حذف حساب المزامنة من الخادم). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    fun save(stamps: Map<String, SyncStamp>) {
        val editor = prefs.edit().clear()
        for ((key, stamp) in stamps) {
            editor.putString(
                PREFIX + key,
                listOf(stamp.fingerprint, stamp.updatedAt.toString(), if (stamp.deleted) "1" else "0")
                    .joinToString(SEPARATOR)
            )
        }
        editor.apply()
    }

    companion object {
        private const val PREFIX = "stamp_"
        private const val SEPARATOR = "\u001e" // fingerprints already use U+001F internally

        fun get(context: Context): SyncMetadata = SyncMetadata(
            context.getSharedPreferences("focus_sync_meta", Context.MODE_PRIVATE)
        )
    }
}
