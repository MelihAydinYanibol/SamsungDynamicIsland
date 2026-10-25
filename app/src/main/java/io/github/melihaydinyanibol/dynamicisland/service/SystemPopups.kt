package io.github.melihaydinyanibol.dynamicisland.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import io.github.melihaydinyanibol.dynamicisland.IslandPrefs

/**
 * Turns the system's own heads-up pop-ups (One UI's "Brief"/"Detailed" notification pop-ups) off
 * while the island is showing notifications, and restores the user's original setting afterwards.
 *
 * Writing the global `heads_up_notifications_enabled` setting needs WRITE_SECURE_SETTINGS, which a
 * regular app can only receive once through ADB:
 * `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`.
 */
object SystemPopups {

    private const val TAG = "IslandPopups"
    private const val HEADS_UP_KEY = "heads_up_notifications_enabled"

    fun canControl(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    fun systemPopupsEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, HEADS_UP_KEY, 1) != 0

    /**
     * Suppresses system pop-ups when [suppress] is true, otherwise puts back whatever the user had.
     * Safe to call repeatedly; does nothing without the permission.
     */
    fun apply(context: Context, prefs: IslandPrefs, suppress: Boolean) {
        if (!canControl(context)) return
        val resolver = context.contentResolver
        try {
            if (suppress) {
                if (!prefs.popupsSuppressed) {
                    prefs.savedHeadsUpValue = Settings.Global.getInt(resolver, HEADS_UP_KEY, 1)
                    prefs.popupsSuppressed = true
                }
                Settings.Global.putInt(resolver, HEADS_UP_KEY, 0)
            } else if (prefs.popupsSuppressed) {
                Settings.Global.putInt(resolver, HEADS_UP_KEY, prefs.savedHeadsUpValue)
                prefs.popupsSuppressed = false
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot change heads-up setting", e)
        }
    }
}
