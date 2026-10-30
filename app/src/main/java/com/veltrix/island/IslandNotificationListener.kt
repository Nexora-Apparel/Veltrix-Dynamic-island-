package com.veltrix.island

import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/** Bound by Android only when notification access is granted. */
class IslandNotificationListener : NotificationListenerService() {
    private val dismissedByIsland = ConcurrentHashMap.newKeySet<String>()
    private val controllers = ConcurrentHashMap<String, MediaController>()
    private val callbacks = ConcurrentHashMap<String, MediaController.Callback>()
    private val mediaTimeoutHandler = Handler(Looper.getMainLooper())
    private var mediaTimeoutPackage: String? = null
    private val mediaInactivityTimeout = Runnable {
        val pkg = mediaTimeoutPackage ?: return@Runnable
        if (IslandState.flow.value.media?.let { it.packageName == pkg && !it.playing } == true) {
            IslandState.showMedia(null)
        }
        mediaTimeoutPackage = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach {
            handleMediaNotification(it)
            handleNavigationNotification(it)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (dismissedByIsland.remove(sbn.key)) return
        IslandState.clearNotification(sbn.key)
        LiveActivities.remove("navigation:" + sbn.key)
        if (sbn.packageName == "com.whatsapp" && IslandState.flow.value.call?.packageName == "com.whatsapp") {
            IslandState.clearCall()
            CallActions.endCall = null
        }
        if (controllers.containsKey(sbn.packageName)) refreshMedia(sbn.packageName)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleMediaNotification(sbn)
        handleNavigationNotification(sbn)
        if (!IslandState.flow.value.screenOn) return
        val n = sbn.notification
        if (sbn.packageName == "com.whatsapp" &&
            (n.category == Notification.CATEGORY_CALL ||
                (sbn.isOngoing && ((n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() + " " +
                    n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).contains("call", ignoreCase = true))))) {
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val incoming = (title + " " + text).contains("incoming", ignoreCase = true) ||
                (title + " " + text).contains("calling", ignoreCase = true)
            IslandState.showCall(IslandCall(if (incoming) CallState.RINGING else CallState.ACTIVE,
                number = title.ifBlank { "WhatsApp call" },
                startedAtMillis = if (incoming) 0L else System.currentTimeMillis(),
                packageName = "com.whatsapp"))
            n.actions?.firstOrNull { action ->
                val label = action.title?.toString().orEmpty()
                label.contains("end", true) || label.contains("decline", true) || label.contains("reject", true)
            }?.let { action ->
                CallActions.endCall = { try { action.actionIntent.send() } catch (_: Exception) {} }
            }
            return
        }
        if (sbn.isOngoing || sbn.packageName !in Prefs.allowed(this)) return
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

    private fun handleNavigationNotification(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: ""
        ).trim()
        val combined = "$title $text"
        val mapsNavigation = sbn.packageName == "com.google.android.apps.maps" &&
            sbn.isOngoing &&
            Regex(
                "(navigate|navigation|turn left|turn right|continue straight|\bdestination\b|\barrive\b|\bmeters?\b|\bkm\b|\bmiles?\b|\bexit\b)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(combined)
        if (n.category != Notification.CATEGORY_NAVIGATION && !mapsNavigation) return

        val routeTitle = title.ifBlank { "Navigation" }.take(90)
        val routeDetail = text.ifBlank { "Navigation in progress" }.take(140)
        LiveActivities.upsert(
            LiveActivity(
                id = "navigation:" + sbn.key,
                kind = ActivityKind.NAVIGATION,
                title = routeTitle,
                subtitle = routeDetail,
                status = "active",
                priority = 70
            )
        )
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
            val incoming = MediaController(this, token)
            val existing = controllers[sbn.packageName]
            val controller = if (existing != null && existing.sessionToken == incoming.sessionToken) {
                existing
            } else {
                callbacks.remove(sbn.packageName)?.let { cb ->
                    try { existing?.unregisterCallback(cb) } catch (_: Exception) {}
                }
                controllers[sbn.packageName] = incoming
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) { publishMedia(sbn.packageName) }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { publishMedia(sbn.packageName) }
                    override fun onSessionDestroyed() {
                        callbacks.remove(sbn.packageName)?.let { oldCallback ->
                            try { controllers.remove(sbn.packageName)?.unregisterCallback(oldCallback) } catch (_: Exception) {}
                        }
                        refreshMedia(sbn.packageName)
                    }
                }
                callbacks[sbn.packageName] = cb
                incoming.registerCallback(cb)
                incoming
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
            clearMediaTimeout()
            IslandState.showMedia(null)
            return
        }
        publishMedia(pkg)
    }

    private fun clearMediaTimeout() {
        mediaTimeoutHandler.removeCallbacks(mediaInactivityTimeout)
        mediaTimeoutPackage = null
    }

    private fun publishMedia(pkg: String) {
        val c = controllers[pkg] ?: return
        val state = c.playbackState
        val metadata = c.metadata
        val playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING ||
            state?.state == PlaybackState.STATE_CONNECTING
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: c.packageName
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val elapsed = (state?.position ?: 0L).coerceAtLeast(0L) / 1000L
        if (playing || state?.state == PlaybackState.STATE_PAUSED) {
            IslandState.showMedia(IslandMedia(pkg, title, artist, playing, elapsed))
            if (playing) {
                clearMediaTimeout()
            } else {
                mediaTimeoutHandler.removeCallbacks(mediaInactivityTimeout)
                mediaTimeoutPackage = pkg
                mediaTimeoutHandler.postDelayed(mediaInactivityTimeout, 10 * 60 * 1000L)
            }
        } else {
            clearMediaTimeout()
            IslandState.showMedia(null)
        }
    }

    override fun onDestroy() {
        clearMediaTimeout()
        callbacks.forEach { (pkg, cb) -> try { controllers[pkg]?.unregisterCallback(cb) } catch (_: Exception) {} }
        callbacks.clear()
        controllers.clear()
        MediaActions.playPause = null
        MediaActions.next = null
        MediaActions.previous = null
        super.onDestroy()
    }
}