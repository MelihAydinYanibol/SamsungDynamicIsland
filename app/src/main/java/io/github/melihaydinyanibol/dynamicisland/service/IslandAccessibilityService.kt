package io.github.melihaydinyanibol.dynamicisland.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.telecom.TelecomManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import io.github.melihaydinyanibol.dynamicisland.IslandPrefs
import io.github.melihaydinyanibol.dynamicisland.IslandRepository
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.device.CutoutSpec
import io.github.melihaydinyanibol.dynamicisland.device.DeviceProfile
import io.github.melihaydinyanibol.dynamicisland.model.ActionRole
import io.github.melihaydinyanibol.dynamicisland.model.ActivityType
import io.github.melihaydinyanibol.dynamicisland.model.BatteryPolicy
import io.github.melihaydinyanibol.dynamicisland.model.IslandAction
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.IslandController
import io.github.melihaydinyanibol.dynamicisland.model.IslandMode
import io.github.melihaydinyanibol.dynamicisland.ui.IslandGeometry
import io.github.melihaydinyanibol.dynamicisland.ui.IslandRootView
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
 * permission. The service only looks at the size and type of system windows (to get out of the
 * way of the notification panel); it never reads what is on screen.
 */
class IslandAccessibilityService :
    AccessibilityService(),
    IslandView.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: IslandPrefs
    private lateinit var windowManager: WindowManager
    private var root: IslandRootView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var receiverRegistered = false

    private val controller = IslandController()
    private val handler = Handler(Looper.getMainLooper())
    private var latestLive: Map<String, IslandActivity> = emptyMap()
    private var lastBatteryLevel: Int? = null

    private var shadeOpen = false
    private var statusBarHidden = false
    private val camerasInUse = HashSet<String>()
    private var micInUse = false

    private val alertTimeout = Runnable {
        controller.dismissAlert()
        restartAlertTimer()
        render()
    }

    private val autoCollapse = Runnable {
        controller.collapse()
        render()
    }

    private val checkWindows = Runnable { updateShadeState() }

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
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> render()
            }
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (camerasInUse.remove(cameraId)) updatePrivacy()
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (camerasInUse.add(cameraId)) updatePrivacy()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            // Hotword detection ("Hi Bixby", "Hey Google") keeps the mic open; it is not shown.
            val active = configs.any { it.clientAudioSource != AUDIO_SOURCE_HOTWORD && !it.isClientSilenced }
            if (active != micInUse) {
                micInUse = active
                updatePrivacy()
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
        registerPrivacyCallbacks()

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
        applySystemPopups()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized || !prefs.hideWithShade) return
        // Window changes come in bursts while the panel is dragged; check once things settle.
        handler.removeCallbacks(checkWindows)
        handler.postDelayed(checkWindows, WINDOW_CHECK_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root == null) return
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
            getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cameraCallback)
            getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordingCallback)
            receiverRegistered = false
        }
        if (::prefs.isInitialized) {
            prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
            // Never leave the user without notification pop-ups once the island is gone.
            SystemPopups.apply(this, prefs, suppress = false)
        }
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        IslandRepository.setOverlayRunning(false)
    }

    // region Overlay window

    private fun attachOverlay() {
        val themed = ContextThemeWrapper(this, R.style.Theme_DynamicIsland_Overlay)
        val view = IslandRootView(themed).apply {
            island.callbacks = this@IslandAccessibilityService
            island.hapticsEnabled = prefs.haptics
            setOnApplyWindowInsetsListener { _, insets ->
                val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
                if (hidden != statusBarHidden) {
                    statusBarHidden = hidden
                    handler.post { render() }
                }
                insets
            }
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
        root = view
        layoutParams = params
        updateGeometry()
        render()
    }

    private fun updateGeometry() {
        val view = root ?: return
        val params = layoutParams ?: return
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val profile = DeviceProfile.detect(Build.MODEL)
        val cutout = metrics.windowInsets.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        val spec = cutout?.let { CutoutSpec(it.centerX(), it.top, it.width(), it.height()) }
            ?: profile.fallbackCutout(bounds.width())

        val scale = prefs.scale.coerceIn(50, 200) / 100f
        val widthScale = prefs.widthScale.coerceIn(50, 200) / 100f
        val hole = max(spec.height, dpInt(20))
        val idleHeight = ((hole + 2 * dp(profile.idlePaddingDp.toFloat())) * scale).roundToInt()
        val idleWidth = max((idleHeight * IDLE_ASPECT * widthScale).roundToInt(), spec.width + dpInt(12))
        val compactWidth = idleWidth + (dp(COMPACT_EXTRA_DP) * scale * widthScale).roundToInt()
        val expandedWidth = min(bounds.width() - dpInt(16), dpInt(440))

        view.setGeometry(
            IslandGeometry(
                idleWidth = idleWidth,
                idleHeight = idleHeight,
                compactWidth = min(compactWidth, expandedWidth),
                expandedWidth = expandedWidth,
                expandedCorner = dp(EXPANDED_CORNER_DP) * scale,
                holeWidth = min(spec.width, idleHeight),
                holeHeight = min(spec.height, idleHeight),
                vanishWhenIdle = !prefs.keepIdlePill,
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

    private fun isAlertEnabled(activity: IslandActivity): Boolean =
        activity.key.startsWith("test:") || isLiveEnabled(activity)

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
        val view = root ?: return
        controller.showIdle = true
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Full-screen video and games keep the camera area clear, but alerts still come through.
        val fullscreen = prefs.hideInFullscreen && statusBarHidden && !locked && controller.currentAlert == null
        controller.hidden = !prefs.enabled ||
            (prefs.hideInLandscape && landscape) ||
            (prefs.hideWithShade && shadeOpen && !locked) ||
            fullscreen
        val state = controller.state()
        view.render(state)
        scheduleAutoCollapse(state.mode == IslandMode.EXPANDED && !state.isAlert)
    }

    private fun scheduleAutoCollapse(expanded: Boolean) {
        handler.removeCallbacks(autoCollapse)
        if (expanded) handler.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
    }

    /** The notification panel / quick settings is a full-height system window. */
    private fun updateShadeState() {
        val list = runCatching { windows }.getOrNull() ?: return
        val screen = windowManager.currentWindowMetrics.bounds
        val rect = Rect()
        val open = list.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_SYSTEM && run {
                window.getBoundsInScreen(rect)
                rect.height() > screen.height() * 0.6f && rect.width() > screen.width() * 0.9f
            }
        }
        if (open != shadeOpen) {
            shadeOpen = open
            if (open) controller.collapse()
            render()
        }
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

    override fun onSecondaryTap(key: String) {
        if (controller.select(key)) render()
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

    override fun onAction(action: IslandAction) {
        val done = when (action.role) {
            ActionRole.HANG_UP -> endCallViaTelecom() || action.intent?.sendFromOverlay(this) == true
            ActionRole.ANSWER -> answerViaTelecom() || action.intent?.sendFromOverlay(this) == true
            ActionRole.NEUTRAL -> action.intent?.sendFromOverlay(this) == true
        }
        if (!done) Log.w(TAG, "Action '${action.title}' could not be delivered")
        if (controller.currentAlert != null) {
            controller.dismissAlert()
            restartAlertTimer()
        } else if (action.role == ActionRole.HANG_UP) {
            controller.collapse()
        }
        render()
    }

    private fun open(activity: IslandActivity) {
        if (activity.packageName == packageName) return
        val sent = activity.contentIntent?.sendFromOverlay(this) ?: false
        if (!sent) launchPackage(activity.packageName)
    }

    // endregion

    // region Calls

    /** Ends the call through Telecom, which works even when the dialer's own intent is protected. */
    @Suppress("DEPRECATION")
    private fun endCallViaTelecom(): Boolean {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            getSystemService(TelecomManager::class.java).endCall()
        } catch (e: SecurityException) {
            Log.w(TAG, "endCall refused", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun answerViaTelecom(): Boolean {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            getSystemService(TelecomManager::class.java).acceptRingingCall()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "acceptRingingCall refused", e)
            false
        }
    }

    // endregion

    // region System events

    private fun registerSystemReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun registerPrivacyCallbacks() {
        getSystemService(CameraManager::class.java).registerAvailabilityCallback(cameraCallback, handler)
        getSystemService(AudioManager::class.java).registerAudioRecordingCallback(recordingCallback, handler)
    }

    private fun updatePrivacy() {
        val color = when {
            !prefs.privacyIndicator -> null
            camerasInUse.isNotEmpty() -> BatteryPolicy.GREEN
            micInUse -> BatteryPolicy.ORANGE
            else -> null
        }
        root?.island?.setPrivacyIndicator(color)
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
        if (notificationAccessGranted()) {
            runCatching { NotificationListenerService.requestRebind(IslandNotificationListener.component(this)) }
        }
    }

    private fun notificationAccessGranted(): Boolean =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(IslandNotificationListener.component(this))

    /** Hide One UI's pop-ups only while the island can actually show notifications instead. */
    private fun applySystemPopups() {
        val suppress = prefs.enabled && prefs.notifications && prefs.replacePopups && notificationAccessGranted()
        SystemPopups.apply(this, prefs, suppress)
    }

    // endregion

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (root == null || key in IslandPrefs.INTERNAL_KEYS) return
        if (key == null || key in IslandPrefs.GEOMETRY_KEYS) updateGeometry()
        root?.island?.hapticsEnabled = prefs.haptics
        if (key == IslandPrefs.KEY_HIDE_SHADE && !prefs.hideWithShade) shadeOpen = false
        updatePrivacy()
        applySystemPopups()
        syncLive()
    }

    private companion object {
        const val TAG = "IslandOverlay"
        const val IDLE_ASPECT = 2.9f
        const val COMPACT_EXTRA_DP = 140f
        const val EXPANDED_CORNER_DP = 40f
        const val COMPACT_ALERT_MS = 2_500L
        const val AUTO_COLLAPSE_MS = 10_000L
        const val WINDOW_CHECK_DELAY_MS = 120L

        /** `MediaRecorder.AudioSource.HOTWORD`, which is a hidden constant. */
        const val AUDIO_SOURCE_HOTWORD = 1999
    }
}
