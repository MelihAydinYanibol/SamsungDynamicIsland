package io.github.melihaydinyanibol.dynamicisland.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.melihaydinyanibol.dynamicisland.BuildConfig
import io.github.melihaydinyanibol.dynamicisland.IslandPrefs
import io.github.melihaydinyanibol.dynamicisland.IslandRepository
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.device.DeviceProfile
import io.github.melihaydinyanibol.dynamicisland.model.IslandAction
import io.github.melihaydinyanibol.dynamicisland.model.IslandController
import io.github.melihaydinyanibol.dynamicisland.service.IslandAccessibilityService
import io.github.melihaydinyanibol.dynamicisland.service.IslandNotificationListener
import io.github.melihaydinyanibol.dynamicisland.service.SystemAlerts
import io.github.melihaydinyanibol.dynamicisland.service.SystemPopups
import io.github.melihaydinyanibol.dynamicisland.util.dp
import io.github.melihaydinyanibol.dynamicisland.util.dpInt
import kotlin.math.roundToInt
import kotlin.reflect.KMutableProperty0

/** Setup and settings screen, with a live preview of the island at the top. */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: IslandPrefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var previewFrame: FrameLayout
    private lateinit var preview: IslandRootView
    private val previewController = IslandController()
    private var demoStep = 0

    private val demoTick = object : Runnable {
        override fun run() {
            advanceDemo()
            handler.postDelayed(this, DEMO_STEP_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = IslandPrefs(this)

        applySystemBarInsets()
        bindDeviceLabel()
        setupPreview()
        bindPermissions()
        bindPopups()
        bindSwitches()
        bindSeekBars()
        bindTests()
        findViewById<TextView>(R.id.version_label).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        refreshPermissions()
        restartDemo()
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(demoTick)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || key in IslandPrefs.GEOMETRY_KEYS) updatePreviewGeometry()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    private fun applySystemBarInsets() {
        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(view.paddingLeft, top + bars.top, view.paddingRight, bottom + bars.bottom)
            insets
        }
    }

    private fun bindDeviceLabel() {
        val profile = DeviceProfile.detect(Build.MODEL)
        findViewById<TextView>(R.id.device_label).text = if (profile.isTuned) {
            getString(R.string.device_supported, profile.displayName, Build.MODEL)
        } else {
            getString(R.string.device_generic, Build.MODEL)
        }
    }

    // region Preview

    private fun setupPreview() {
        previewFrame = findViewById(R.id.preview_frame)
        preview = IslandRootView(ContextThemeWrapper(this, R.style.Theme_DynamicIsland_Overlay))
        preview.island.hapticsEnabled = false
        preview.island.callbacks = object : IslandView.Callbacks {
            override fun onTap() = restartDemo()
            override fun onLongPress() = Unit
            override fun onSwipe(direction: IslandView.Swipe) = Unit
            override fun onOutsideTouch() = Unit
            override fun onUserInteraction() = Unit
            override fun onAction(action: IslandAction) = Unit
            override fun onSecondaryTap(key: String) {
                previewController.select(key)
                renderPreview()
            }
        }
        previewFrame.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        previewFrame.setOnClickListener { restartDemo() }
        previewFrame.post { updatePreviewGeometry() }
    }

    /** Mirrors the overlay's sizing so the size and width sliders show up in the preview. */
    private fun updatePreviewGeometry() {
        if (previewFrame.width == 0) return
        val scale = prefs.scale / 100f
        val widthScale = prefs.widthScale / 100f
        val hole = dpInt(PREVIEW_HOLE_DP)
        val idleHeight = ((hole + dp(12f)) * scale).roundToInt()
        val idleWidth = (idleHeight * 2.9f * widthScale).roundToInt()
        val expandedWidth = previewFrame.width - dpInt(16)
        preview.setGeometry(
            IslandGeometry(
                idleWidth = idleWidth,
                idleHeight = idleHeight,
                compactWidth = minOf(idleWidth + (dp(140f) * scale * widthScale).roundToInt(), expandedWidth),
                expandedWidth = expandedWidth,
                expandedCorner = dp(40f) * scale,
                holeWidth = hole,
                holeHeight = hole,
                vanishWhenIdle = !prefs.keepIdlePill,
            ),
        )
        val holeCenter = dpInt(PREVIEW_HOLE_TOP_DP) + hole / 2
        (preview.layoutParams as FrameLayout.LayoutParams).topMargin = holeCenter - idleHeight / 2
        preview.requestLayout()
        renderPreview()
    }

    private fun restartDemo() {
        handler.removeCallbacks(demoTick)
        demoStep = 0
        previewController.setLive(emptyList())
        while (previewController.dismissAlert() != null) Unit
        previewController.collapse()
        renderPreview()
        handler.postDelayed(demoTick, DEMO_FIRST_STEP_MS)
    }

    /** A short story: notification → music → two activities → charging → back into the camera. */
    private fun advanceDemo() {
        val media = SystemAlerts.demoMedia(this)
        val timer = SystemAlerts.testTimer(this)
        when (demoStep % DEMO_STEPS) {
            0 -> previewController.enqueueAlert(SystemAlerts.testNotification(this))
            1 -> {
                previewController.dismissAlert()
                previewController.setLive(listOf(media))
            }
            2 -> previewController.expand()
            3 -> {
                previewController.collapse()
                previewController.setLive(listOf(media, timer))
                previewController.select(media.key)
            }
            4 -> previewController.enqueueAlert(SystemAlerts.charging(this, 82))
            5 -> previewController.dismissAlert()
            6 -> previewController.setLive(emptyList())
        }
        demoStep++
        renderPreview()
    }

    private fun renderPreview() {
        previewController.showIdle = true
        preview.render(previewController.state())
    }

    // endregion

    // region Permissions

    private fun bindPermissions() {
        findViewById<Button>(R.id.perm_accessibility_button).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.perm_notifications_button).setOnClickListener { openNotificationAccess() }
        findViewById<Button>(R.id.perm_calls_button).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), REQUEST_CALLS)
        }
        findViewById<Button>(R.id.perm_battery_button).setOnClickListener { openAppDetails() }
    }

    private fun refreshPermissions() {
        setStatus(R.id.perm_accessibility_status, isAccessibilityEnabled(), required = true)
        setStatus(R.id.perm_notifications_status, isNotificationAccessGranted(), required = true)
        setStatus(R.id.perm_calls_status, hasCallControl(), required = false)
        setStatus(R.id.perm_battery_status, isIgnoringBatteryOptimizations(), required = false)
        findViewById<Button>(R.id.perm_calls_button).visibility = if (hasCallControl()) View.INVISIBLE else View.VISIBLE
        refreshPopups()
    }

    private fun setStatus(viewId: Int, granted: Boolean, required: Boolean) {
        val view = findViewById<TextView>(viewId)
        view.setText(
            when {
                granted -> R.string.perm_granted
                required -> R.string.perm_missing
                else -> R.string.perm_optional
            },
        )
        view.setTextColor(
            getColor(
                when {
                    granted -> R.color.island_green
                    required -> R.color.island_red
                    else -> R.color.island_orange
                },
            ),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == packageName && info.name == IslandAccessibilityService::class.java.name
        }
    }

    private fun isNotificationAccessGranted(): Boolean =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(IslandNotificationListener.component(this))

    private fun hasCallControl(): Boolean =
        checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openAccessibilitySettings() {
        // On One UI the service is listed under Accessibility → Installed apps.
        tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationAccess() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                IslandNotificationListener.component(this).flattenToString(),
            )
        if (!tryStart(detail)) tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** App info hosts both "Battery → Unrestricted" and "Allow restricted settings" on One UI. */
    private fun openAppDetails() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    // endregion

    // region System pop-ups

    private fun bindPopups() {
        val command = SystemPopups.adbGrantCommand(this)
        findViewById<TextView>(R.id.popups_command).text = command
        findViewById<Button>(R.id.popups_copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), command))
            // Android 13+ shows its own clipboard confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.popups_copied, Toast.LENGTH_SHORT).show()
            }
        }
        bindSwitch(R.id.switch_replace_popups, prefs::replacePopups)
    }

    private fun refreshPopups() {
        val granted = SystemPopups.canControl(this)
        findViewById<TextView>(R.id.popups_status).apply {
            setText(if (granted) R.string.popups_ready else R.string.popups_needs_grant)
            setTextColor(getColor(if (granted) R.color.island_green else R.color.island_orange))
        }
        findViewById<Switch>(R.id.switch_replace_popups).isEnabled = granted
    }

    // endregion

    // region Settings

    private fun bindSwitches() {
        bindSwitch(R.id.switch_enabled, prefs::enabled)
        bindSwitch(R.id.switch_notifications, prefs::notifications)
        bindSwitch(R.id.switch_media, prefs::media)
        bindSwitch(R.id.switch_live, prefs::liveActivities)
        bindSwitch(R.id.switch_charging, prefs::charging)
        bindSwitch(R.id.switch_ringer, prefs::ringer)
        bindSwitch(R.id.switch_privacy, prefs::privacyIndicator)
        bindSwitch(R.id.switch_keep_idle_pill, prefs::keepIdlePill)
        bindSwitch(R.id.switch_hide_shade, prefs::hideWithShade)
        bindSwitch(R.id.switch_hide_fullscreen, prefs::hideInFullscreen)
        bindSwitch(R.id.switch_hide_landscape, prefs::hideInLandscape)
        bindSwitch(R.id.switch_haptics, prefs::haptics)
    }

    private fun bindSwitch(id: Int, property: KMutableProperty0<Boolean>) {
        findViewById<Switch>(id).apply {
            isChecked = property.get()
            setOnCheckedChangeListener { _, checked -> property.set(checked) }
        }
    }

    private fun bindSeekBars() {
        bindSeek(R.id.offset_x_seek, R.id.offset_x_value, prefs::offsetX, -80, 80) { getString(R.string.value_dp, it) }
        bindSeek(R.id.offset_y_seek, R.id.offset_y_value, prefs::offsetY, -40, 40) { getString(R.string.value_dp, it) }
        bindSeek(R.id.scale_seek, R.id.scale_value, prefs::scale, 70, 150) { getString(R.string.value_percent, it) }
        bindSeek(R.id.width_scale_seek, R.id.width_scale_value, prefs::widthScale, 60, 180) {
            getString(R.string.value_percent, it)
        }
        bindSeek(R.id.alert_seconds_seek, R.id.alert_seconds_value, prefs::alertSeconds, 2, 12) {
            getString(R.string.value_seconds, it)
        }
    }

    private fun bindSeek(
        seekId: Int,
        valueId: Int,
        property: KMutableProperty0<Int>,
        min: Int,
        max: Int,
        format: (Int) -> String,
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(valueId)
        seek.min = min
        seek.max = max
        seek.progress = property.get().coerceIn(min, max)
        label.text = format(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = format(progress)
                if (fromUser) property.set(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    // endregion

    // region Tests on the real island

    private fun bindTests() {
        findViewById<Button>(R.id.test_notification).setOnClickListener {
            whenOverlayRunning { IslandRepository.postAlert(SystemAlerts.testNotification(this)) }
        }
        findViewById<Button>(R.id.test_charging).setOnClickListener {
            whenOverlayRunning {
                val level = getSystemService(BatteryManager::class.java)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                IslandRepository.postAlert(SystemAlerts.charging(this, level))
            }
        }
        findViewById<Button>(R.id.test_timer).setOnClickListener {
            whenOverlayRunning { IslandRepository.putLive(SystemAlerts.testTimer(this)) }
        }
        findViewById<Button>(R.id.test_stop).setOnClickListener {
            IslandRepository.removeLive(SystemAlerts.KEY_TEST_TIMER)
            IslandRepository.dismissAlert(SystemAlerts.KEY_TEST_NOTIFICATION)
        }
    }

    private inline fun whenOverlayRunning(block: () -> Unit) {
        if (IslandRepository.overlayRunning.value) {
            block()
        } else {
            Toast.makeText(this, R.string.enable_overlay_first, Toast.LENGTH_SHORT).show()
            openAccessibilitySettings()
        }
    }

    // endregion

    private companion object {
        const val REQUEST_CALLS = 1
        const val DEMO_STEPS = 7
        const val DEMO_STEP_MS = 2_400L
        const val DEMO_FIRST_STEP_MS = 900L
        const val PREVIEW_HOLE_DP = 14
        const val PREVIEW_HOLE_TOP_DP = 12
    }
}
