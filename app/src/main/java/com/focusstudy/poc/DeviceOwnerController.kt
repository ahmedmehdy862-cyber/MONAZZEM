package com.focusstudy.poc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Optional Device Owner support.
 *
 * When the app is provisioned as Device Owner, the selected apps can be suspended at the OS level
 * with [DevicePolicyManager.setPackagesSuspended]: their launcher icons are disabled (greyed out),
 * they disappear from Recents, they cannot start activities, show notifications, play audio or
 * vibrate, and tapping them shows the system "app suspended" dialog. Suspension is lifted when the
 * session ends.
 *
 * Without Device Owner the app still works exactly as before: the accessibility overlay hides the
 * app and the VPN drops its traffic. Nothing here is required for a normal install.
 */
object DeviceOwnerController {

    private const val TAG = "FocusOwner"
    private const val PREFS = "focus_device_owner"
    private const val KEY_SUSPENDED = "suspended_packages"

    fun isDeviceOwner(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching { manager.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    /**
     * Suspends [packages] and returns how many were actually suspended. Packages the system refuses
     * (system apps, the launcher, packages with an active admin) are reported by the platform and
     * are simply skipped; the accessibility layer still covers them.
     */
    fun suspend(context: Context, packages: Set<String>): Int {
        if (packages.isEmpty() || !isDeviceOwner(context)) return 0
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return 0
        val targets = packages.filter { it != context.packageName }.toSet()
        if (targets.isEmpty()) return 0

        // Persist synchronously BEFORE suspending: a process kill between the system call and an
        // asynchronous commit would otherwise leave apps suspended with no way to lift it later.
        remember(context, targets)
        return runCatching {
            val failed = manager
                .setPackagesSuspended(admin(context), targets.toTypedArray(), true)
                ?.toSet()
                .orEmpty()
            val done = targets - failed
            if (failed.isNotEmpty()) {
                Log.w(TAG, "could not suspend: $failed")
                forget(context, failed)
            }
            done.size
        }.onFailure { Log.w(TAG, "setPackagesSuspended(true) failed", it) }.getOrDefault(0)
    }

    /** Lifts every suspension this app applied. Safe to call when nothing is suspended. */
    fun unsuspendAll(context: Context) {
        val stored = stored(context)
        if (stored.isEmpty()) return
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        if (manager == null || !isDeviceOwner(context)) return
        val lifted = runCatching {
            manager.setPackagesSuspended(admin(context), stored.toTypedArray(), false)
            true
        }.onFailure { Log.w(TAG, "setPackagesSuspended(false) failed", it) }.getOrDefault(false)
        // Keep the record when lifting failed so a later restore/boot can retry instead of leaving
        // apps suspended forever.
        if (lifted) forget(context)
    }

    fun hasSuspended(context: Context): Boolean = stored(context).isNotEmpty()

    private fun admin(context: Context): ComponentName =
        ComponentName(context, FocusDeviceAdminReceiver::class.java)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stored(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SUSPENDED, emptySet()).orEmpty().toSet()

    private fun remember(context: Context, packages: Set<String>) {
        if (packages.isEmpty()) return
        prefs(context).edit()
            .putStringSet(KEY_SUSPENDED, stored(context) + packages)
            .commit()
    }

    private fun forget(context: Context, packages: Set<String>? = null) {
        val editor = prefs(context).edit()
        if (packages == null) editor.remove(KEY_SUSPENDED)
        else {
            val remaining = stored(context) - packages
            if (remaining.isEmpty()) editor.remove(KEY_SUSPENDED)
            else editor.putStringSet(KEY_SUSPENDED, remaining)
        }
        editor.commit()
    }
}
