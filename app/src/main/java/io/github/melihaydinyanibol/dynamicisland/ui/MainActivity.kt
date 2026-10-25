package io.github.melihaydinyanibol.dynamicisland.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.melihaydinyanibol.dynamicisland.BuildConfig
import io.github.melihaydinyanibol.dynamicisland.IslandPrefs
import io.github.melihaydinyanibol.dynamicisland.IslandRepository
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.device.DeviceProfile
import io.github.melihaydinyanibol.dynamicisland.service.IslandAccessibilityService
import io.github.melihaydinyanibol.dynamicisland.service.IslandNotificationListener
import io.github.melihaydinyanibol.dynamicisland.service.SystemAlerts
import kotlin.reflect.KMutableProperty0

/** Setup and settings screen. */
class MainActivity : Activity() {

    private lateinit var prefs: IslandPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = IslandPrefs(this)

        applySystemBarInsets()
        bindDeviceLabel()
        bindPermissions()
        bindSwitches()
        bindSeekBars()
        bindTests()
        findViewById<TextView>(R.id.version_label).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
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

    // region Permissions

    private fun bindPermissions() {
        findViewById<Button>(R.id.perm_accessibility_button).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.perm_notifications_button).setOnClickListener { openNotificationAccess() }
        findViewById<Button>(R.id.perm_battery_button).setOnClickListener { openAppDetails() }
    }

    private fun refreshPermissions() {
        setStatus(R.id.perm_accessibility_status, isAccessibilityEnabled(), required = true)
        setStatus(R.id.perm_notifications_status, isNotificationAccessGranted(), required = true)
        setStatus(R.id.perm_battery_status, isIgnoringBatteryOptimizations(), required = false)
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

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val component = ComponentName(this, IslandAccessibilityService::class.java)
            val detail = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
            if (tryStart(detail)) return
        }
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

    // region Settings

    private fun bindSwitches() {
        bindSwitch(R.id.switch_enabled, prefs::enabled)
        bindSwitch(R.id.switch_show_idle, prefs::showIdle)
        bindSwitch(R.id.switch_notifications, prefs::notifications)
        bindSwitch(R.id.switch_media, prefs::media)
        bindSwitch(R.id.switch_live, prefs::liveActivities)
        bindSwitch(R.id.switch_charging, prefs::charging)
        bindSwitch(R.id.switch_ringer, prefs::ringer)
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

    // region Demo

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
}
