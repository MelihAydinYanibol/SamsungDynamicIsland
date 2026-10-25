package io.github.melihaydinyanibol.dynamicisland.model

import android.app.PendingIntent
import android.graphics.drawable.Drawable

/** What kind of content an island activity carries. Higher [priority] wins the island. */
enum class ActivityType(val priority: Int) {
    CALL(100),
    TIMER(70),
    NAVIGATION(60),
    MEDIA(50),
    PROGRESS(40),
    CHARGING(30),
    NOTIFICATION(20),
    SYSTEM(10),
}

/** Playback controls for a media session, kept free of framework types so the model stays testable. */
interface MediaControls {
    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
}

data class MediaInfo(
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** `SystemClock.elapsedRealtime()` at which [positionMs] was sampled. */
    val positionSampledAt: Long,
    val speed: Float,
    val controls: MediaControls?,
) {
    fun positionAt(elapsedRealtime: Long): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0)
        val advanced = positionMs + ((elapsedRealtime - positionSampledAt) * speed).toLong()
        return if (durationMs > 0) advanced.coerceIn(0, durationMs) else advanced.coerceAtLeast(0)
    }
}

data class IslandAction(
    val title: CharSequence,
    val intent: PendingIntent?,
)

/**
 * One thing the island can show: a live activity (call, timer, media, …) or a transient alert.
 */
data class IslandActivity(
    val key: String,
    val type: ActivityType,
    val packageName: String,
    val title: CharSequence,
    val subtitle: CharSequence? = null,
    val appName: CharSequence? = null,
    /** Small icon (app icon or a glyph). Tinted with [accentColor] when [tintIcon] is set. */
    val icon: Drawable? = null,
    val tintIcon: Boolean = false,
    /** Large picture: album art, contact photo, … */
    val image: Drawable? = null,
    val accentColor: Int = DEFAULT_ACCENT,
    /** Short text shown on the trailing side of the compact pill, e.g. "85%" or "300 m". */
    val compactText: CharSequence? = null,
    /** Wall-clock time a chronometer counts from (or down to, when [countDown]). */
    val chronometerWhen: Long? = null,
    val countDown: Boolean = false,
    val progress: Int = 0,
    val progressMax: Int = 0,
    val progressIndeterminate: Boolean = false,
    val media: MediaInfo? = null,
    val contentIntent: PendingIntent? = null,
    val actions: List<IslandAction> = emptyList(),
    /** For alerts: open straight into the expanded card instead of the compact pill. */
    val expandOnAlert: Boolean = true,
    val timestamp: Long = 0L,
) {
    val hasProgress: Boolean get() = progressIndeterminate || progressMax > 0

    val progressFraction: Float
        get() = if (progressMax > 0) (progress.toFloat() / progressMax).coerceIn(0f, 1f) else 0f

    companion object {
        const val DEFAULT_ACCENT: Int = 0xFFFFFFFF.toInt()
    }
}
