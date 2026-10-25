# Samsung Dynamic Island

[![Android CI](https://github.com/MelihAydinYanibol/SamsungDynamicIsland/actions/workflows/android.yml/badge.svg)](https://github.com/MelihAydinYanibol/SamsungDynamicIsland/actions/workflows/android.yml)

An iPhone‑style **Dynamic Island** for the **Galaxy S25 FE** and **Galaxy A56**, written in Kotlin.
A black pill wraps the punch‑hole camera and morphs to show what's happening on your phone.

| Feature | What you get |
| --- | --- |
| 🔔 Notification alerts | The island drops open with the app, sender, message and up to three action buttons (Reply-less actions like *Mark as read*, *Archive*). |
| 🎵 Now playing | Album art + animated equaliser in the pill; expand for title, artist, seek bar and ⏮ ⏯ ⏭ controls. Works with any app that uses a MediaSession (Spotify, YouTube Music, Samsung Music…). |
| 📞 Calls | Incoming/ongoing calls take over the island with a live call timer and Answer / Decline / Hang up buttons. |
| ⏱ Timers & stopwatches | Ongoing chronometer notifications (Samsung Clock, etc.) count up or down live. |
| 🧭 Navigation | Turn-by-turn text from navigation notifications (Google Maps, Waze). |
| ⬇️ Progress | Downloads/uploads show a progress ring and bar. |
| 🔋 Battery | Charging bubble with level, low-battery (20/10/5 %) and fully charged alerts. |
| 🔕 Sound mode | Silent / Vibrate / Sound on changes. |

| 🔴 Privacy dots | Green dot while any app uses the camera, orange while the microphone records. |
| 🌐 Languages | English and Türkçe (follows the phone language, or set it per app in Android 13+). |

When nothing is happening the island **sinks back into the camera hole** and disappears; it springs
out of the camera again when something arrives (turn on *Keep pill when idle* if you prefer a
resting pill). Two live activities at once show as a pill plus a small bubble, like on iOS.

**Gestures:** tap to expand · tap again to open the app · long-press to jump straight into the app ·
swipe up to collapse/dismiss · swipe sideways (or tap the bubble) to switch live activities · touch outside to collapse.

## Getting Samsung's own UI out of the way

| One UI element | What the island does |
| --- | --- |
| Notification pop-ups (heads-up, "Brief"/"Detailed") | With a one-time ADB permission, the app turns the system pop-ups off while the island is running and restores your original setting when it stops. |
| Notification panel / Quick settings | The island hides while the panel is pulled down. |
| Full-screen video & games | The island hides when the status bar is hidden; alerts still come through. |
| Landscape | The island hides (optional). |

To let the island replace One UI's pop-ups, connect the phone to a computer with USB debugging and
run once:

```bash
adb shell pm grant io.github.melihaydinyanibol.dynamicisland android.permission.WRITE_SECURE_SETTINGS
```

then enable **Replace system notification pop-ups** in the app. If you ever uninstall the app while
it is running and pop-ups don't come back, restore them with
`adb shell settings put global heads_up_notifications_enabled 1`.

Things Android does not let a normal app change without root: the status-bar call/timer chips and
Samsung's Now Bar on the lock screen. They keep showing alongside the island.

### Call buttons

Calls use the *Answer / Decline / Hang up* actions from Android 12+ call notifications. For the
most reliable hang-up, also grant **Call buttons** in the app (`ANSWER_PHONE_CALLS`); the island then
ends or answers calls through Android's telephony service directly.

## Supported devices

| Device | Models | Status |
| --- | --- | --- |
| Galaxy S25 FE | `SM-S731*` | Tuned profile |
| Galaxy A56 5G | `SM-A566*` | Tuned profile |
| Other Android 11+ phones with a centred punch hole | — | Works with the generic profile |

The island reads the real camera position from Android's `DisplayCutout` API; the per-device profiles
in [`DeviceProfile.kt`](app/src/main/java/io/github/melihaydinyanibol/dynamicisland/device/DeviceProfile.kt)
are a fallback. Fine-tune position and size in the app if needed.

## Install

1. Download the APK from **Releases** (or the `DynamicIsland-debug` artifact of the latest
   [Android CI run](https://github.com/MelihAydinYanibol/SamsungDynamicIsland/actions)).
2. Open the app and grant:
   - **Island overlay (Accessibility)** — draws the island above the status bar. If it's greyed out,
     go to *Settings → Apps → Dynamic Island → ⋮ → Allow restricted settings* first (Android 13+
     sideloading rule), then enable it.
   - **Notification access** — needed for notifications, calls, timers and media.
   - **Call buttons** (optional) — lets Answer / Hang up control the call directly.
   - **Unrestricted battery** (recommended) — *App info → Battery → Unrestricted*, and add the app to
     *Settings → Battery → Background usage limits → Never sleeping apps* so One UI doesn't kill it.
3. Use **Try it** buttons in the app to preview a notification, the charging bubble or a live timer.

> Privacy: the accessibility service draws the overlay and looks only at the *size and type* of
> system windows, so it can hide under the notification panel. It never reads text or other screen
> content, and nothing leaves your device — the app has no internet permission.

## Build

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## CI / CD

- **`.github/workflows/android.yml`** — on every push and pull request: unit tests, lint, debug APK
  build; the APK and reports are uploaded as artifacts.
- **`.github/workflows/release.yml`** — push a tag like `v1.0.0` (or run it manually) to build a
  minified release APK and publish it as a GitHub Release. To sign with your own key, add the
  secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
  `SIGNING_KEY_PASSWORD`; otherwise the debug key is used.

## How it works

```
NotificationListenerService ──► NotificationMapper ─┐
MediaSessionManager ─────────► MediaSessionTracker ─┼──► IslandRepository (StateFlow / SharedFlow)
Battery & ringer broadcasts ──► SystemAlerts ───────┘                │
                                                                     ▼
                         AccessibilityService overlay ◄── IslandController (priority, alerts, gestures)
                                    │
                                    └──► IslandView (idle ↔ compact ↔ expanded spring animation)
```

- `IslandController` is plain Kotlin and fully unit-tested: it ranks live activities
  (call > timer > navigation > media > progress), queues alerts, and tracks expand/selection state.
- `IslandView` is a custom `ViewGroup` drawn in a `TYPE_ACCESSIBILITY_OVERLAY` window with
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, so it can sit right on the camera hole.

## License

GPL-3.0 — see [LICENSE](LICENSE).
