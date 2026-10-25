package io.github.melihaydinyanibol.dynamicisland.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.model.ActionRole
import io.github.melihaydinyanibol.dynamicisland.model.ActivityType
import io.github.melihaydinyanibol.dynamicisland.model.IslandAction
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.util.appIcon
import io.github.melihaydinyanibol.dynamicisland.util.appLabel

/** Turns status-bar notifications into island activities. */
class NotificationMapper(private val context: Context) {

    sealed interface Result {
        /** An ongoing notification that becomes a live activity. */
        data class Live(val activity: IslandActivity) : Result

        /** A fresh notification that pops the island open for a moment. */
        data class Alert(val activity: IslandActivity) : Result

        data object Ignore : Result
    }

    fun map(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?,
        allowAlert: Boolean,
    ): Result {
        val notification = sbn.notification ?: return Result.Ignore
        if (sbn.packageName == context.packageName) return Result.Ignore
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return Result.Ignore
        // Media notifications are handled through their MediaSession instead.
        if (notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return Result.Ignore

        liveType(sbn, notification)?.let { type ->
            return Result.Live(build(sbn, notification, type))
        }

        if (!allowAlert || sbn.isOngoing) return Result.Ignore
        if (ranking != null) {
            if (!ranking.matchesInterruptionFilter()) return Result.Ignore
            if (ranking.isSuspended) return Result.Ignore
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return Result.Ignore
        }
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return Result.Ignore
        return Result.Alert(build(sbn, notification, ActivityType.NOTIFICATION))
    }

    private fun liveType(sbn: StatusBarNotification, n: Notification): ActivityType? {
        val extras = n.extras
        val category = n.category
        val isCall = category == Notification.CATEGORY_CALL &&
            (sbn.isOngoing || n.fullScreenIntent != null)
        if (isCall) return ActivityType.CALL
        if (!sbn.isOngoing) return null
        return when {
            category == Notification.CATEGORY_NAVIGATION -> ActivityType.NAVIGATION
            category == CATEGORY_STOPWATCH -> ActivityType.TIMER
            extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) -> ActivityType.TIMER
            extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) -> ActivityType.PROGRESS
            else -> null
        }
    }

    private fun build(sbn: StatusBarNotification, n: Notification, type: ActivityType): IslandActivity {
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: context.appLabel(sbn.packageName)
            ?: sbn.packageName
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val appIcon = context.appIcon(sbn.packageName)
            ?: n.smallIcon?.loadDrawable(context)
        val largeIcon = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()

        val showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
            (type == ActivityType.CALL && sbn.isOngoing && n.`when` > 0)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

        val accent = when (type) {
            ActivityType.CALL -> CALL_GREEN
            ActivityType.TIMER -> TIMER_ORANGE
            else -> n.color.takeIf { it != Notification.COLOR_DEFAULT && it != 0 }?.let(::liftColor)
                ?: IslandActivity.DEFAULT_ACCENT
        }

        val compactText = when (type) {
            ActivityType.NAVIGATION -> title
            ActivityType.PROGRESS ->
                if (progressMax > 0) context.getString(R.string.island_percent, progress * 100 / progressMax) else null
            else -> null
        }

        val actions = if (type == ActivityType.CALL) callActions(n) else plainActions(n)

        return IslandActivity(
            key = sbn.key,
            type = type,
            packageName = sbn.packageName,
            title = title,
            subtitle = text,
            appName = context.appLabel(sbn.packageName),
            icon = appIcon,
            image = largeIcon,
            accentColor = accent,
            compactText = compactText,
            chronometerWhen = if (showChronometer) n.`when` else null,
            countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            progress = progress,
            progressMax = progressMax,
            progressIndeterminate = indeterminate,
            contentIntent = n.contentIntent ?: n.fullScreenIntent,
            actions = actions,
            expandOnAlert = true,
            timestamp = sbn.postTime,
        )
    }

    private fun plainActions(n: Notification): List<IslandAction> =
        n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(MAX_ACTIONS)
            .map { IslandAction(it.title, it.actionIntent) }

    /**
     * Calls posted with `Notification.CallStyle` (Android 12+, used by One UI's dialer, Google
     * Phone, WhatsApp, …) keep Answer / Decline / Hang up in extras rather than in [Notification.actions].
     * Older call notifications are classified by their button labels.
     */
    private fun callActions(n: Notification): List<IslandAction> {
        val extras = n.extras
        val answer = extras.pendingIntent(EXTRA_ANSWER_INTENT)
        val decline = extras.pendingIntent(EXTRA_DECLINE_INTENT)
        val hangUp = extras.pendingIntent(EXTRA_HANG_UP_INTENT)

        val result = mutableListOf<IslandAction>()
        when {
            decline != null -> result += IslandAction(context.getString(R.string.call_decline), decline, ActionRole.HANG_UP)
            hangUp != null -> result += IslandAction(context.getString(R.string.call_hang_up), hangUp, ActionRole.HANG_UP)
        }
        if (answer != null) result += IslandAction(context.getString(R.string.call_answer), answer, ActionRole.ANSWER)

        val known = setOf(answer, decline, hangUp)
        n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() && it.actionIntent !in known }
            .forEach { action ->
                val role = callRole(action.title)
                if (role != ActionRole.NEUTRAL && result.any { it.role == role }) return@forEach
                result += IslandAction(action.title, action.actionIntent, role)
            }

        if (result.none { it.role == ActionRole.HANG_UP }) {
            // Still offer a hang-up button; the service ends the call through Telecom if it can.
            result += IslandAction(context.getString(R.string.call_hang_up), null, ActionRole.HANG_UP)
        }
        // Red on the left, green on the right, extras in between — like the iOS call card.
        val required = result.filter { it.role != ActionRole.NEUTRAL }
        val neutral = result.filter { it.role == ActionRole.NEUTRAL }.take((MAX_ACTIONS - required.size).coerceAtLeast(0))
        return (required + neutral).sortedBy {
            when (it.role) {
                ActionRole.HANG_UP -> 0
                ActionRole.NEUTRAL -> 1
                ActionRole.ANSWER -> 2
            }
        }
    }

    private fun callRole(title: CharSequence): ActionRole {
        val text = title.toString().lowercase()
        return when {
            HANG_UP_WORDS.any { it in text } -> ActionRole.HANG_UP
            ANSWER_WORDS.any { it in text } -> ActionRole.ANSWER
            else -> ActionRole.NEUTRAL
        }
    }

    @Suppress("DEPRECATION")
    private fun android.os.Bundle.pendingIntent(key: String): PendingIntent? = get(key) as? PendingIntent

    /** Make dark brand colours visible on the black island. */
    private fun liftColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2].coerceAtLeast(0.8f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    companion object {
        /** `Notification.CATEGORY_STOPWATCH`, added in API 31; spelled out so minSdk 30 is fine. */
        private const val CATEGORY_STOPWATCH = "stopwatch"

        /** `Notification.EXTRA_*_INTENT` of CallStyle, added in API 31. */
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"

        private val HANG_UP_WORDS = listOf(
            "hang up", "end call", "decline", "reject", "dismiss call",
            "kapat", "sonlandır", "reddet", "bitir",
        )
        private val ANSWER_WORDS = listOf("answer", "accept", "cevapla", "yanıtla", "kabul")
        private const val MAX_ACTIONS = 3
        private const val CALL_GREEN = 0xFF34C759.toInt()
        private const val TIMER_ORANGE = 0xFFFF9F0A.toInt()
    }
}
