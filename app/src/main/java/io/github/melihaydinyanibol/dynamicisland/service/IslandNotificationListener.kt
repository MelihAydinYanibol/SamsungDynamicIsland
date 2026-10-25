package io.github.melihaydinyanibol.dynamicisland.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.melihaydinyanibol.dynamicisland.IslandRepository

/** Receives every notification and forwards the interesting ones to [IslandRepository]. */
class IslandNotificationListener : NotificationListenerService() {

    private lateinit var mapper: NotificationMapper
    private var mediaTracker: MediaSessionTracker? = null

    /** Keys of live activities produced by notifications, so they can be cleared on disconnect. */
    private val liveKeys = HashSet<String>()

    /** Keys already alerted once, for notifications that ask to alert only once. */
    private val alertedOnce = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        mapper = NotificationMapper(this)
    }

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, allowAlert = false) }
        mediaTracker = MediaSessionTracker(this, component(this)).also { it.start() }
    }

    override fun onListenerDisconnected() {
        connected = false
        mediaTracker?.stop()
        mediaTracker = null
        liveKeys.forEach(IslandRepository::removeLive)
        liveKeys.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        handle(sbn, allowAlert = true, rankingMap = rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (liveKeys.remove(sbn.key)) IslandRepository.removeLive(sbn.key)
        alertedOnce.remove(sbn.key)
        IslandRepository.dismissAlert(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification, allowAlert: Boolean, rankingMap: RankingMap? = null) {
        val ranking = rankingMap?.let { map -> Ranking().takeIf { map.getRanking(sbn.key, it) } }
        val onlyOnce = (sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        val canAlert = allowAlert && !(onlyOnce && sbn.key in alertedOnce)

        when (val result = mapper.map(sbn, ranking, canAlert)) {
            is NotificationMapper.Result.Live -> {
                liveKeys += sbn.key
                IslandRepository.putLive(result.activity)
            }
            is NotificationMapper.Result.Alert -> {
                rememberAlerted(sbn.key)
                IslandRepository.postAlert(result.activity)
            }
            NotificationMapper.Result.Ignore -> {
                // A notification can stop being ongoing (e.g. a finished download).
                if (liveKeys.remove(sbn.key)) IslandRepository.removeLive(sbn.key)
            }
        }
    }

    private fun rememberAlerted(key: String) {
        alertedOnce += key
        if (alertedOnce.size > 200) alertedOnce.remove(alertedOnce.first())
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        fun component(context: Context) = ComponentName(context, IslandNotificationListener::class.java)
    }
}
