package io.github.melihaydinyanibol.dynamicisland.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.melihaydinyanibol.dynamicisland.IslandPrefs
import io.github.melihaydinyanibol.dynamicisland.IslandRepository
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.device.CutoutSpec
import io.github.melihaydinyanibol.dynamicisland.device.DeviceProfile
import io.github.melihaydinyanibol.dynamicisland.model.ActivityType
import io.github.melihaydinyanibol.dynamicisland.model.BatteryPolicy
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.IslandController
import io.github.melihaydinyanibol.dynamicisland.model.IslandMode
import io.github.melihaydinyanibol.dynamicisland.ui.IslandGeometry
import io.github.melihaydinyanibol.dynamicisland.ui.IslandView
import io.github.melihaydinyanibol.dynamicisland.util.dp
import io.github.melihaydinyanibol.dynamicisland.util.dpInt
import io.github.melihaydinyanibol.dynamicisland.util.launchPackage
import io.github.melihaydinyanibol.dynamicisland.util.sendFromOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hosts the island overlay. An accessibility overlay can sit on top of the status bar and the
 * camera cutout, survives One UI's background restrictions well, and needs no "draw over apps"
 * permission. The service never inspects screen content.
 */
class IslandAccessibilityService :
    AccessibilityService(),
    IslandView.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: IslandPrefs
    private lateinit var windowManager: WindowManager
    private var islandView: IslandView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var receiverRegistered = false

    private val controller = IslandController()
    private val handler = Handler(Looper.getMainLooper())
    private var latestLive: Map<String, IslandActivity> = emptyMap()
    private var lastBatteryLevel: Int? = null

    private val alertTimeout = Runnable {
        controller.dismissAlert()
        restartAlertTimer()
        render()
    }

    private val autoCollapse = Runnable {
        controller.collapse()
        render()
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    IslandRepository.postAlert(SystemAlerts.charging(context, batteryLevel()))
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent, isInitialStickyBroadcast)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (!isInitialStickyBroadcast) {
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                    SystemAlerts.ringer(context, mode)?.let(IslandRepository::postAlert)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = IslandPrefs(this)
        windowManager = getSystemService(WindowManager::class.java)
        attachOverlay()
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        registerSystemReceiver()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                IslandRepository.live.collect {
                    latestLive = it
                    syncLive()
                }
            }
            scope.launch { IslandRepository.alerts.collect { onAlert(it) } }
            scope.launch {
                IslandRepository.dismissed.collect { key ->
                    if (controller.removeAlert(key)) restartAlertTimer()
                    render()
                }
            }
        }
        IslandRepository.setOverlayRunning(true)
        rebindNotificationListener()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (islandView == null) return
        updateGeometry()
        render()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        scope?.cancel()
        scope = null
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            unregisterReceiver(systemReceiver)
            receiverRegistered = false
        }
        if (::prefs.isInitialized) prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        islandView?.let { view -> runCatching { windowManager.removeView(view) } }
        islandView = null
        IslandRepository.setOverlayRunning(false)
    }

    // region Overlay window

    private fun attachOverlay() {
        val themed = ContextThemeWrapper(this, R.style.Theme_DynamicIsland_Overlay)
        val view = IslandView(themed).apply {
            callbacks = this@IslandAccessibilityService
            hapticsEnabled = prefs.haptics
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "DynamicIsland"
        }
        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not add the island overlay", e)
            return
        }
        islandView = view
        layoutParams = params
        updateGeometry()
        render()
    }

    private fun updateGeometry() {
        val view = islandView ?: return
        val params = layoutParams ?: return
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val profile = DeviceProfile.detect(Build.MODEL)
        val cutout = metrics.windowInsets.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        val spec = cutout?.let { CutoutSpec(it.centerX(), it.top, it.width(), it.height()) }
            ?: profile.fallbackCutout(bounds.width())

        val scale = prefs.scale.coerceIn(50, 200) / 100f
        val hole = max(spec.height, dpInt(20))
        val idleHeight = ((hole + 2 * dp(profile.idlePaddingDp.toFloat())) * scale).roundToInt()
        val idleWidth = max((idleHeight * IDLE_ASPECT).roundToInt(), spec.width + dpInt(12))
        val compactWidth = idleWidth + (dp(COMPACT_EXTRA_DP) * scale).roundToInt()
        val expandedWidth = min(bounds.width() - dpInt(16), dpInt(440))

        view.setGeometry(
            IslandGeometry(
                idleWidth = idleWidth,
                idleHeight = idleHeight,
                compactWidth = min(compactWidth, expandedWidth),
                expandedWidth = expandedWidth,
                expandedCorner = dp(EXPANDED_CORNER_DP) * scale,
            ),
        )

        params.x = spec.centerX - bounds.width() / 2 + dpInt(prefs.offsetX)
        params.y = max(0, spec.top + spec.height / 2 - idleHeight / 2 + dpInt(prefs.offsetY))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // endregion

    // region State

    private fun syncLive() {
        controller.setLive(latestLive.values.filter(::isLiveEnabled))
        render()
    }

    private fun isLiveEnabled(activity: IslandActivity): Boolean = when (activity.type) {
        ActivityType.MEDIA -> prefs.media
        ActivityType.CALL, ActivityType.TIMER, ActivityType.NAVIGATION, ActivityType.PROGRESS -> prefs.liveActivities
        ActivityType.CHARGING -> prefs.charging
        ActivityType.NOTIFICATION -> prefs.notifications
        ActivityType.SYSTEM -> prefs.ringer
    }

    private fun isAlertEnabled(activity: IslandActivity): Boolean = when {
        activity.key.startsWith("test:") -> true
        else -> isLiveEnabled(activity)
    }

    private fun onAlert(alert: IslandActivity) {
        if (!prefs.enabled || !isAlertEnabled(alert)) return
        if (controller.enqueueAlert(alert)) restartAlertTimer()
        render()
    }

    private fun restartAlertTimer() {
        handler.removeCallbacks(alertTimeout)
        val alert = controller.currentAlert ?: return
        val duration = if (alert.expandOnAlert) prefs.alertSeconds.coerceIn(1, 30) * 1000L else COMPACT_ALERT_MS
        handler.postDelayed(alertTimeout, duration)
    }

    private fun render() {
        val view = islandView ?: return
        controller.showIdle = prefs.showIdle
        controller.hidden = !prefs.enabled ||
            (prefs.hideInLandscape && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val state = controller.state()
        view.render(state)
        scheduleAutoCollapse(state.mode == IslandMode.EXPANDED && !state.isAlert)
    }

    private fun scheduleAutoCollapse(expanded: Boolean) {
        handler.removeCallbacks(autoCollapse)
        if (expanded) handler.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
    }

    // endregion

    // region Gestures

    override fun onTap() {
        val state = controller.state()
        when {
            state.isAlert -> {
                state.activity?.let(::open)
                controller.dismissAlert()
                restartAlertTimer()
            }
            state.mode == IslandMode.COMPACT -> controller.expand()
            state.mode == IslandMode.EXPANDED -> {
                state.activity?.let(::open)
                controller.collapse()
            }
            else -> Unit
        }
        render()
    }

    /** Long-press jumps straight into the app behind whatever the island shows. */
    override fun onLongPress() {
        val state = controller.state()
        val activity = state.activity ?: return
        open(activity)
        if (state.isAlert) {
            controller.dismissAlert()
            restartAlertTimer()
        } else {
            controller.collapse()
        }
        render()
    }

    override fun onSwipe(direction: IslandView.Swipe) {
        val state = controller.state()
        when (direction) {
            IslandView.Swipe.UP -> if (state.isAlert) {
                controller.dismissAlert()
                restartAlertTimer()
            } else {
                controller.collapse()
            }
            IslandView.Swipe.DOWN -> if (!state.isAlert) controller.expand()
            IslandView.Swipe.LEFT -> if (!state.isAlert) controller.cycle(forward = true)
            IslandView.Swipe.RIGHT -> if (!state.isAlert) controller.cycle(forward = false)
        }
        render()
    }

    override fun onOutsideTouch() {
        if (controller.isExpanded()) {
            controller.collapse()
            render()
        }
    }

    override fun onUserInteraction() {
        val state = controller.state()
        if (state.isAlert) restartAlertTimer()
        if (state.mode == IslandMode.EXPANDED && !state.isAlert) scheduleAutoCollapse(true)
    }

    override fun onSendIntent(intent: PendingIntent) {
        intent.sendFromOverlay(this)
        if (controller.currentAlert != null) {
            controller.dismissAlert()
            restartAlertTimer()
            render()
        }
    }

    private fun open(activity: IslandActivity) {
        if (activity.packageName == packageName) return
        val sent = activity.contentIntent?.sendFromOverlay(this) ?: false
        if (!sent) launchPackage(activity.packageName)
    }

    // endregion

    // region System events

    private fun registerSystemReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun batteryLevel(): Int =
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun onBatteryChanged(intent: Intent, initial: Boolean) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return
        val level = raw * 100 / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val previous = lastBatteryLevel
        lastBatteryLevel = level
        if (initial) return
        when {
            BatteryPolicy.crossedLowThreshold(previous, level, charging) != null ->
                IslandRepository.postAlert(SystemAlerts.lowBattery(this, level))
            BatteryPolicy.becameFull(previous, level, charging) ->
                IslandRepository.postAlert(SystemAlerts.batteryFull(this))
        }
    }

    private fun rebindNotificationListener() {
        if (IslandNotificationListener.connected) return
        val manager = getSystemService(NotificationManager::class.java)
        val component = IslandNotificationListener.component(this)
        if (manager.isNotificationListenerAccessGranted(component)) {
            runCatching { NotificationListenerService.requestRebind(component) }
        }
    }

    // endregion

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (islandView == null) return
        if (key == null || key in IslandPrefs.GEOMETRY_KEYS) updateGeometry()
        islandView?.hapticsEnabled = prefs.haptics
        syncLive()
    }

    private companion object {
        const val TAG = "IslandOverlay"
        const val IDLE_ASPECT = 2.9f
        const val COMPACT_EXTRA_DP = 140f
        const val EXPANDED_CORNER_DP = 40f
        const val COMPACT_ALERT_MS = 2_500L
        const val AUTO_COLLAPSE_MS = 10_000L
    }
}
