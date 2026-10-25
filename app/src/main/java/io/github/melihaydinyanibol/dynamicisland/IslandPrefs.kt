package io.github.melihaydinyanibol.dynamicisland

import android.content.Context
import android.content.SharedPreferences

/** Typed access to the user's island settings. */
class IslandPrefs(context: Context) {

    val raw: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled by bool(KEY_ENABLED, true)
    /** Keep a resting pill around the camera instead of sinking into it when idle. */
    var keepIdlePill by bool(KEY_KEEP_IDLE_PILL, false)
    var notifications by bool(KEY_NOTIFICATIONS, true)
    var media by bool(KEY_MEDIA, true)
    var liveActivities by bool(KEY_LIVE, true)
    var charging by bool(KEY_CHARGING, true)
    var ringer by bool(KEY_RINGER, true)
    var hideInLandscape by bool(KEY_HIDE_LANDSCAPE, true)
    var haptics by bool(KEY_HAPTICS, true)

    /** Replace One UI's notification pop-ups with the island (needs the ADB grant). */
    var replacePopups by bool(KEY_REPLACE_POPUPS, true)
    var hideInFullscreen by bool(KEY_HIDE_FULLSCREEN, true)
    var hideWithShade by bool(KEY_HIDE_SHADE, true)
    var privacyIndicator by bool(KEY_PRIVACY, true)

    /** Bookkeeping so the user's own pop-up setting can be restored. */
    var popupsSuppressed by bool(KEY_POPUPS_SUPPRESSED, false)
    var savedHeadsUpValue by int(KEY_SAVED_HEADS_UP, 1)

    /** dp, negative = left. */
    var offsetX by int(KEY_OFFSET_X, 0)

    /** dp, negative = up. */
    var offsetY by int(KEY_OFFSET_Y, 0)

    /** Percent. */
    var scale by int(KEY_SCALE, 100)

    /** Percent, horizontal only: makes the pill wider or narrower without changing its height. */
    var widthScale by int(KEY_WIDTH_SCALE, 100)
    var alertSeconds by int(KEY_ALERT_SECONDS, 5)

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = raw.getBoolean(key, default)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            raw.edit().putBoolean(key, value).apply()
        }
    }

    private fun int(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = raw.getInt(key, default)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) {
            raw.edit().putInt(key, value).apply()
        }
    }

    companion object {
        const val FILE = "island_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_KEEP_IDLE_PILL = "keep_idle_pill"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_MEDIA = "media"
        const val KEY_LIVE = "live_activities"
        const val KEY_CHARGING = "charging"
        const val KEY_RINGER = "ringer"
        const val KEY_HIDE_LANDSCAPE = "hide_landscape"
        const val KEY_HAPTICS = "haptics"
        const val KEY_REPLACE_POPUPS = "replace_popups"
        const val KEY_HIDE_FULLSCREEN = "hide_fullscreen"
        const val KEY_HIDE_SHADE = "hide_shade"
        const val KEY_PRIVACY = "privacy_indicator"
        const val KEY_POPUPS_SUPPRESSED = "popups_suppressed"
        const val KEY_SAVED_HEADS_UP = "saved_heads_up"
        const val KEY_OFFSET_X = "offset_x"
        const val KEY_OFFSET_Y = "offset_y"
        const val KEY_SCALE = "scale"
        const val KEY_WIDTH_SCALE = "width_scale"
        const val KEY_ALERT_SECONDS = "alert_seconds"

        /** Keys that change the island's geometry rather than its content. */
        val GEOMETRY_KEYS = setOf(KEY_OFFSET_X, KEY_OFFSET_Y, KEY_SCALE, KEY_WIDTH_SCALE, KEY_HIDE_LANDSCAPE, KEY_KEEP_IDLE_PILL)

        /** Keys the app writes for itself; changes to them need no re-render. */
        val INTERNAL_KEYS = setOf(KEY_POPUPS_SUPPRESSED, KEY_SAVED_HEADS_UP)
    }
}
