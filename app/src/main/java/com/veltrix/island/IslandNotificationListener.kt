package com.veltrix.island

import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/** Bound by Android only when notification access is granted. */
class IslandNotificationListener : NotificationListenerService() {
    private val dismissedByIsland = ConcurrentHashMap.newKeySet<String>()
    private val controllers = ConcurrentHashMap<String, MediaController>()
    private val callbacks = ConcurrentHashMap<String, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach { handleMediaNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (dismissedByIsland.remove(sbn.key)) return
        IslandState.clearNotification(sbn.key)
        if (controllers.containsKey(sbn.packageName)) refreshMedia(sbn.packageName)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleMediaNotification(sbn)
        if (!IslandState.flow.value.screenOn) return
        if (sbn.isOngoing || sbn.packageName !in Prefs.allowed(this)) return
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }

        IslandState.showNotification(IslandNotification(sbn.key, label, sbn.packageName, title, text))
        dismissedByIsland.add(sbn.key)
        try {
            cancelNotification(sbn.key)
        } catch (_: SecurityException) {
            dismissedByIsland.remove(sbn.key)
        } catch (_: IllegalArgumentException) {
            dismissedByIsland.remove(sbn.key)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleMediaNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val token: MediaSession.Token? = try {
            if (Build.VERSION.SDK_INT >= 33)
                notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            else notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        } catch (_: Exception) { null }
        if (token == null) {
            if (controllers.containsKey(sbn.packageName)) refreshMedia(sbn.packageName)
            return
        }
        try {
            val controller = MediaController(this, token)
            val old = controllers.put(sbn.packageName, controller)
            if (old == null || old.sessionToken != controller.sessionToken) {
                callbacks.remove(sbn.packageName)?.let { cb -> try { old?.unregisterCallback(cb) } catch (_: Exception) {} }
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) { publishMedia(sbn.packageName) }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { publishMedia(sbn.packageName) }
                    override fun onSessionDestroyed() { refreshMedia(sbn.packageName) }
                }
                callbacks[sbn.packageName] = cb
                controller.registerCallback(cb)
            }
            publishMedia(sbn.packageName)
            MediaActions.playPause = { togglePlayback(sbn.packageName) }
            MediaActions.next = { controllers[sbn.packageName]?.transportControls?.skipToNext() }
            MediaActions.previous = { controllers[sbn.packageName]?.transportControls?.skipToPrevious() }
        } catch (_: SecurityException) { }
        catch (_: IllegalArgumentException) { }
    }

    private fun togglePlayback(pkg: String) {
        val controller = controllers[pkg] ?: return
        try {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                controller.transportControls.pause()
            else controller.transportControls.play()
        } catch (_: SecurityException) { }
    }

    private fun refreshMedia(pkg: String) {
        val controller = controllers[pkg]
        if (controller == null) {
            IslandState.showMedia(null)
            return
        }
        publishMedia(pkg)
    }

    private fun publishMedia(pkg: String) {
        val c = controllers[pkg] ?: return
        val state = c.playbackState
        val metadata = c.metadata
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING ||
            state?.state == PlaybackState.STATE_CONNECTING
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: c.packageName
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val elapsed = (state?.position ?: 0L).coerceAtLeast(0L) / 1000L
        if (playing || state?.state == PlaybackState.STATE_PAUSED) {
            IslandState.showMedia(IslandMedia(pkg, title, artist, playing, elapsed))
        } else {
            IslandState.showMedia(null)
        }
    }
}