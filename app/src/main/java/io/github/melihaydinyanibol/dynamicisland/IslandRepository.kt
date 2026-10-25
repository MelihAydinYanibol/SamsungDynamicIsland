package io.github.melihaydinyanibol.dynamicisland

import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process hub between the producers (notification listener, media tracker, settings screen)
 * and the overlay service that renders the island. Everything runs in the app's single process.
 */
object IslandRepository {

    private val _live = MutableStateFlow<Map<String, IslandActivity>>(emptyMap())

    /** Ongoing activities keyed by [IslandActivity.key]. */
    val live: StateFlow<Map<String, IslandActivity>> = _live.asStateFlow()

    private val _alerts = MutableSharedFlow<IslandActivity>(extraBufferCapacity = 16)
    val alerts: SharedFlow<IslandActivity> = _alerts.asSharedFlow()

    private val _dismissed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Keys of alerts whose source went away (e.g. the notification was cleared). */
    val dismissed: SharedFlow<String> = _dismissed.asSharedFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    fun putLive(activity: IslandActivity) = _live.update { it + (activity.key to activity) }

    fun removeLive(key: String) = _live.update { if (key in it) it - key else it }

    fun removeLiveWhere(predicate: (IslandActivity) -> Boolean) =
        _live.update { map -> map.filterValues { !predicate(it) } }

    fun postAlert(activity: IslandActivity) {
        _alerts.tryEmit(activity)
    }

    fun dismissAlert(key: String) {
        _dismissed.tryEmit(key)
    }

    internal fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }
}
