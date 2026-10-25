package io.github.melihaydinyanibol.dynamicisland.model

enum class IslandMode { HIDDEN, IDLE, COMPACT, EXPANDED }

data class IslandUiState(
    val mode: IslandMode,
    val activity: IslandActivity? = null,
    val isAlert: Boolean = false,
    /** Number of live activities, so the UI can hint that more can be swiped to. */
    val liveCount: Int = 0,
    /** Next live activity, shown as a small bubble beside the compact pill. */
    val secondary: IslandActivity? = null,
)

/**
 * Decides what the island shows. It holds the live activities (ongoing things such as calls,
 * media and timers), a queue of transient alerts, and the user's expand/selection state.
 *
 * Pure Kotlin with no Android dependencies so it can be unit tested on the JVM.
 */
class IslandController(private val maxQueuedAlerts: Int = 5) {

    private var live: List<IslandActivity> = emptyList()
    private val alertQueue = ArrayDeque<IslandActivity>()
    private var expanded = false
    private var selectedKey: String? = null

    var currentAlert: IslandActivity? = null
        private set

    /** Show a resting pill around the camera when nothing is active. */
    var showIdle: Boolean = true

    /** Force the island off, e.g. in landscape or when disabled. */
    var hidden: Boolean = false

    val liveActivities: List<IslandActivity> get() = live

    fun setLive(activities: Collection<IslandActivity>) {
        val previous = currentLive()
        val previousKeys = live.mapTo(HashSet()) { it.key }
        live = order(activities)

        // A newly started activity that outranks the one on screen takes over the island.
        // Calls open straight into the expanded card so they can be answered from the island.
        val newcomer = live.firstOrNull { it.key !in previousKeys }
        if (newcomer != null && (previous == null || newcomer.type.priority > previous.type.priority)) {
            selectedKey = null
            expanded = newcomer.type == ActivityType.CALL
        }
        if (selectedKey != null && live.none { it.key == selectedKey }) {
            selectedKey = null
            expanded = false
        }
        if (live.isEmpty()) expanded = false
    }

    /** Queues an alert. Returns true when it is now on screen (so the caller should start its timer). */
    fun enqueueAlert(alert: IslandActivity): Boolean {
        val current = currentAlert
        if (current == null || current.key == alert.key) {
            currentAlert = alert
            return true
        }
        alertQueue.removeAll { it.key == alert.key }
        alertQueue.addLast(alert)
        while (alertQueue.size > maxQueuedAlerts) alertQueue.removeFirst()
        return false
    }

    /** Drops the alert on screen and promotes the next queued one, which is returned. */
    fun dismissAlert(): IslandActivity? {
        currentAlert = alertQueue.removeFirstOrNull()
        return currentAlert
    }

    /** Removes an alert wherever it is. Returns true if the one on screen changed. */
    fun removeAlert(key: String): Boolean {
        if (currentAlert?.key == key) {
            dismissAlert()
            return true
        }
        alertQueue.removeAll { it.key == key }
        return false
    }

    fun currentLive(): IslandActivity? =
        live.firstOrNull { it.key == selectedKey } ?: live.firstOrNull()

    fun isExpanded(): Boolean = expanded && currentAlert == null && currentLive() != null

    fun expand(): Boolean {
        if (currentLive() == null) return false
        expanded = true
        return true
    }

    fun collapse() {
        expanded = false
    }

    /** Brings a specific live activity to the front. */
    fun select(key: String): Boolean {
        if (live.none { it.key == key }) return false
        selectedKey = key
        return true
    }

    /** Switches to the next/previous live activity. Returns true if the selection changed. */
    fun cycle(forward: Boolean): Boolean {
        if (live.size < 2) return false
        val index = live.indexOf(currentLive()).coerceAtLeast(0)
        val step = if (forward) 1 else -1
        selectedKey = live[(index + step + live.size) % live.size].key
        return true
    }

    fun state(): IslandUiState {
        if (hidden) return IslandUiState(IslandMode.HIDDEN, liveCount = live.size)
        currentAlert?.let { alert ->
            val mode = if (alert.expandOnAlert) IslandMode.EXPANDED else IslandMode.COMPACT
            return IslandUiState(mode, alert, isAlert = true, liveCount = live.size)
        }
        val activity = currentLive()
            ?: return IslandUiState(if (showIdle) IslandMode.IDLE else IslandMode.HIDDEN)
        val mode = if (expanded) IslandMode.EXPANDED else IslandMode.COMPACT
        val secondary = if (mode == IslandMode.COMPACT) live.firstOrNull { it.key != activity.key } else null
        return IslandUiState(mode, activity, isAlert = false, liveCount = live.size, secondary = secondary)
    }

    companion object {
        fun order(activities: Collection<IslandActivity>): List<IslandActivity> =
            activities.sortedWith(
                compareByDescending<IslandActivity> { it.type.priority }
                    .thenByDescending { it.timestamp }
                    .thenBy { it.key },
            )
    }
}
