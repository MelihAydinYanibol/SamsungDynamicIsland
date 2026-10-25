package io.github.melihaydinyanibol.dynamicisland.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.melihaydinyanibol.dynamicisland.IslandRepository
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.model.ActivityType
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.MediaControls
import io.github.melihaydinyanibol.dynamicisland.model.MediaInfo
import io.github.melihaydinyanibol.dynamicisland.util.appIcon
import io.github.melihaydinyanibol.dynamicisland.util.appLabel
import io.github.melihaydinyanibol.dynamicisland.util.vibrantAverageColor

/**
 * Follows active media sessions (requires notification access) and publishes the most relevant
 * one as the "now playing" live activity.
 */
class MediaSessionTracker(
    private val context: Context,
    private val listenerComponent: ComponentName,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val tracked = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    /** Session that was paused long enough to be hidden; it comes back once it plays again. */
    private var hiddenPausedToken: MediaSession.Token? = null
    private var pausedToken: MediaSession.Token? = null

    private var cachedArt: Bitmap? = null
    private var cachedArtColor: Int = IslandActivity.DEFAULT_ACCENT

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessionsChanged(controllers.orEmpty())
    }

    private val hidePaused = Runnable {
        hiddenPausedToken = pausedToken
        publish()
    }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            onSessionsChanged(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access missing, media tracking disabled", e)
        }
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        handler.removeCallbacks(hidePaused)
        tracked.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        tracked.clear()
        IslandRepository.removeLive(KEY)
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        val tokens = controllers.mapTo(HashSet()) { it.sessionToken }
        tracked.keys.filter { it !in tokens }.forEach { token ->
            tracked.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        controllers.forEach { controller ->
            if (controller.sessionToken !in tracked) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                    override fun onSessionDestroyed() = publish()
                }
                controller.registerCallback(callback, handler)
                tracked[controller.sessionToken] = controller to callback
            }
        }
        publish()
    }

    private fun publish() {
        val controllers = tracked.values.map { it.first }
        val playing = controllers.firstOrNull { it.playbackState?.isPlayingOrBuffering() == true && it.metadata != null }
        val paused = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata != null
        }

        if (playing != null) {
            hiddenPausedToken = null
            pausedToken = null
            handler.removeCallbacks(hidePaused)
        } else if (paused != null && paused.sessionToken != pausedToken) {
            pausedToken = paused.sessionToken
            handler.removeCallbacks(hidePaused)
            handler.postDelayed(hidePaused, PAUSED_TIMEOUT_MS)
        }

        val controller = playing ?: paused?.takeIf { it.sessionToken != hiddenPausedToken }
        if (controller == null) {
            IslandRepository.removeLive(KEY)
            return
        }
        IslandRepository.putLive(toActivity(controller))
    }

    private fun toActivity(controller: MediaController): IslandActivity {
        val metadata = controller.metadata
        val state = controller.playbackState
        val pkg = controller.packageName
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art !== cachedArt) {
            cachedArt = art
            cachedArtColor = art?.vibrantAverageColor() ?: IslandActivity.DEFAULT_ACCENT
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: context.getString(R.string.island_unknown_track)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

        val transport = controller.transportControls
        val controls = object : MediaControls {
            override fun playPause() {
                if (controller.playbackState?.isPlayingOrBuffering() == true) transport.pause() else transport.play()
            }
            override fun next() = transport.skipToNext()
            override fun previous() = transport.skipToPrevious()
            override fun seekTo(positionMs: Long) = transport.seekTo(positionMs)
        }

        val media = MediaInfo(
            isPlaying = state?.isPlayingOrBuffering() == true,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0L,
            positionMs = state?.position ?: 0L,
            positionSampledAt = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            controls = controls,
        )

        return IslandActivity(
            key = KEY,
            type = ActivityType.MEDIA,
            packageName = pkg,
            title = title,
            subtitle = artist,
            appName = context.appLabel(pkg),
            icon = context.appIcon(pkg),
            image = art?.let { BitmapDrawable(context.resources, it) },
            accentColor = cachedArtColor,
            media = media,
            contentIntent = controller.sessionActivity,
            timestamp = 0L,
        )
    }

    private fun PlaybackState.isPlayingOrBuffering(): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    companion object {
        const val KEY = "media"
        private const val TAG = "IslandMedia"
        private const val PAUSED_TIMEOUT_MS = 60_000L
    }
}
