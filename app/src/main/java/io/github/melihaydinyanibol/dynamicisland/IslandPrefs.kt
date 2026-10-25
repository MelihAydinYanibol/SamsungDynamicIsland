package io.github.melihaydinyanibol.dynamicisland

import android.content.Context
import android.content.SharedPreferences

/** Typed access to the user's island settings. */
class IslandPrefs(context: Context) {

    val raw: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled by bool(KEY_ENABLED, true)
    var showIdle by bool(KEY_SHOW_IDLE, true)
    var notifications by bool(KEY_NOTIFICATIONS, true)
    var media by bool(KEY_MEDIA, true)
    var liveActivities by bool(KEY_LIVE, true)
    var charging by bool(KEY_CHARGING, true)
    var ringer by bool(KEY_RINGER, true)
    var hideInLandscape by bool(KEY_HIDE_LANDSCAPE, true)
    var haptics by bool(KEY_HAPTICS, true)

    /** dp, negative = left. */
    var offsetX by int(KEY_OFFSET_X, 0)

    /** dp, negative = up. */
    var offsetY by int(KEY_OFFSET_Y, 0)

    /** Percent. */
    var scale by int(KEY_SCALE, 100)
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
        const val KEY_SHOW_IDLE = "show_idle"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_MEDIA = "media"
        const val KEY_LIVE = "live_activities"
        const val KEY_CHARGING = "charging"
        const val KEY_RINGER = "ringer"
        const val KEY_HIDE_LANDSCAPE = "hide_landscape"
        const val KEY_HAPTICS = "haptics"
        const val KEY_OFFSET_X = "offset_x"
        const val KEY_OFFSET_Y = "offset_y"
        const val KEY_SCALE = "scale"
        const val KEY_ALERT_SECONDS = "alert_seconds"

        /** Keys that change the island's geometry rather than its content. */
        val GEOMETRY_KEYS = setOf(KEY_OFFSET_X, KEY_OFFSET_Y, KEY_SCALE, KEY_HIDE_LANDSCAPE)
    }
}
