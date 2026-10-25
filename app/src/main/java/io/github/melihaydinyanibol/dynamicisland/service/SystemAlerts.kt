package io.github.melihaydinyanibol.dynamicisland.service

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.model.ActivityType
import io.github.melihaydinyanibol.dynamicisland.model.BatteryPolicy
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.MediaInfo
import io.github.melihaydinyanibol.dynamicisland.util.appIcon

/** Builders for the island content the app generates itself (battery, sound mode, demos). */
object SystemAlerts {

    const val KEY_BATTERY = "system:battery"
    const val KEY_RINGER = "system:ringer"
    const val KEY_TEST_NOTIFICATION = "test:notification"
    const val KEY_TEST_TIMER = "test:timer"
    const val KEY_DEMO_MEDIA = "demo:media"

    fun charging(context: Context, level: Int) = IslandActivity(
        key = KEY_BATTERY,
        type = ActivityType.CHARGING,
        packageName = context.packageName,
        title = context.getString(R.string.island_charging),
        appName = context.getString(R.string.island_charging),
        icon = context.getDrawable(R.drawable.ic_bolt),
        tintIcon = true,
        accentColor = BatteryPolicy.levelColor(level, charging = true),
        compactText = context.getString(R.string.island_percent, level),
        expandOnAlert = false,
        timestamp = System.currentTimeMillis(),
    )

    fun lowBattery(context: Context, level: Int) = IslandActivity(
        key = KEY_BATTERY,
        type = ActivityType.CHARGING,
        packageName = context.packageName,
        title = context.getString(R.string.island_battery_low),
        icon = context.getDrawable(R.drawable.ic_battery_alert),
        tintIcon = true,
        accentColor = BatteryPolicy.levelColor(level, charging = false),
        compactText = context.getString(R.string.island_percent, level),
        expandOnAlert = false,
        timestamp = System.currentTimeMillis(),
    )

    fun batteryFull(context: Context) = IslandActivity(
        key = KEY_BATTERY,
        type = ActivityType.CHARGING,
        packageName = context.packageName,
        title = context.getString(R.string.island_battery_full),
        icon = context.getDrawable(R.drawable.ic_battery_full),
        tintIcon = true,
        accentColor = BatteryPolicy.GREEN,
        compactText = context.getString(R.string.island_percent, 100),
        expandOnAlert = false,
        timestamp = System.currentTimeMillis(),
    )

    fun ringer(context: Context, mode: Int): IslandActivity? {
        val (icon, label, color) = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> Triple(R.drawable.ic_bell_off, R.string.island_ringer_silent, BatteryPolicy.ORANGE)
            AudioManager.RINGER_MODE_VIBRATE -> Triple(R.drawable.ic_vibrate, R.string.island_ringer_vibrate, IslandActivity.DEFAULT_ACCENT)
            AudioManager.RINGER_MODE_NORMAL -> Triple(R.drawable.ic_volume, R.string.island_ringer_normal, IslandActivity.DEFAULT_ACCENT)
            else -> return null
        }
        return IslandActivity(
            key = KEY_RINGER,
            type = ActivityType.SYSTEM,
            packageName = context.packageName,
            title = context.getString(label),
            icon = context.getDrawable(icon),
            tintIcon = true,
            accentColor = color,
            compactText = context.getString(label),
            expandOnAlert = false,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun testNotification(context: Context) = IslandActivity(
        key = KEY_TEST_NOTIFICATION,
        type = ActivityType.NOTIFICATION,
        packageName = context.packageName,
        title = context.getString(R.string.test_notification_title),
        subtitle = context.getString(R.string.test_notification_text),
        appName = context.getString(R.string.app_name),
        icon = context.appIcon(context.packageName),
        timestamp = System.currentTimeMillis(),
    )

    fun testTimer(context: Context) = IslandActivity(
        key = KEY_TEST_TIMER,
        type = ActivityType.TIMER,
        packageName = context.packageName,
        title = context.getString(R.string.test_timer_title),
        appName = context.getString(R.string.app_name),
        icon = context.getDrawable(R.drawable.ic_timer),
        tintIcon = true,
        accentColor = BatteryPolicy.ORANGE,
        chronometerWhen = System.currentTimeMillis() + 5 * 60_000L,
        countDown = true,
        timestamp = System.currentTimeMillis(),
    )

    /** Sample "now playing" card for the settings preview. */
    fun demoMedia(context: Context) = IslandActivity(
        key = KEY_DEMO_MEDIA,
        type = ActivityType.MEDIA,
        packageName = context.packageName,
        title = context.getString(R.string.demo_track_title),
        subtitle = context.getString(R.string.demo_track_artist),
        appName = context.getString(R.string.app_name),
        icon = context.getDrawable(R.drawable.ic_music),
        tintIcon = true,
        accentColor = DEMO_ACCENT,
        media = MediaInfo(
            isPlaying = true,
            durationMs = 214_000L,
            positionMs = 71_000L,
            positionSampledAt = SystemClock.elapsedRealtime(),
            speed = 1f,
            controls = null,
        ),
        timestamp = System.currentTimeMillis(),
    )

    private const val DEMO_ACCENT = 0xFFFF6FAE.toInt()
}
