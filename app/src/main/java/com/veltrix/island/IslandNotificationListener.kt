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
    private val mediaTimeouts = ConcurrentHashMap<String, Runnable>()
    private val mediaLastUpdated = ConcurrentHashMap<String, Long>()
    private val mediaInactivityTimeoutMs = 10 * 60 * 1000L

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
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                        if (controllers[sbn.packageName] === incoming) publishMedia(sbn.packageName)
                    }
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (controllers[sbn.packageName] === incoming) publishMedia(sbn.packageName)
                    }
                    override fun onSessionDestroyed() {
                        // A delayed callback from an old session must never remove its replacement.
                        if (controllers.remove(sbn.packageName, incoming)) {
                            callbacks.remove(sbn.packageName, this)
                            cancelMediaTimeout(sbn.packageName)
                            mediaLastUpdated.remove(sbn.packageName)
                            try { incoming.unregisterCallback(this) } catch (_: Exception) {}
                            selectMediaSession()
                        }
                    }
                }
                callbacks[sbn.packageName] = cb
                incoming.registerCallback(cb)
                incoming
            }
            publishMedia(sbn.packageName)
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
            cancelMediaTimeout(pkg)
            mediaLastUpdated.remove(pkg)
        } else {
            publishMedia(pkg)
        }
        selectMediaSession()
    }

    private fun cancelMediaTimeout(pkg: String) {
        mediaTimeouts.remove(pkg)?.let { mediaTimeoutHandler.removeCallbacks(it) }
    }

    private fun scheduleMediaTimeout(pkg: String) {
        cancelMediaTimeout(pkg)
        val timeout = Runnable {
            mediaTimeouts.remove(pkg)
            val current = controllers[pkg]
            if (current?.playbackState?.state == PlaybackState.STATE_PAUSED) {
                controllers.remove(pkg, current)
                callbacks.remove(pkg)?.let { cb ->
                    try { current.unregisterCallback(cb) } catch (_: Exception) {}
                }
                mediaLastUpdated.remove(pkg)
                selectMediaSession()
            }
        }
        mediaTimeouts[pkg] = timeout
        mediaTimeoutHandler.postDelayed(timeout, mediaInactivityTimeoutMs)
    }

    private fun selectMediaSession() {
        val candidates = controllers.keys.mapNotNull { pkg ->
            val controller = controllers[pkg] ?: return@mapNotNull null
            val state = controller.playbackState?.state ?: return@mapNotNull null
            val active = state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING
            val paused = state == PlaybackState.STATE_PAUSED
            if (!active && !paused) return@mapNotNull null
            Triple(pkg, controller, active)
        }
        val selected = candidates.sortedWith(
            compareByDescending<Triple<String, MediaController, Boolean>> { it.third }
                .thenByDescending { mediaLastUpdated[it.first] ?: 0L }
        ).firstOrNull()
        val current = selected?.second
        if (current == null) {
            IslandState.showMedia(null)
            MediaActions.playPause = null
            MediaActions.next = null
            MediaActions.previous = null
            return
        }
        val pkg = selected.first
        val state = current.playbackState
        val metadata = current.metadata
        val playing = selected.third
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: current.packageName
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val elapsed = (state?.position ?: 0L).coerceAtLeast(0L) / 1000L
        IslandState.showMedia(IslandMedia(pkg, title, artist, playing, elapsed))
        MediaActions.playPause = { togglePlayback(pkg) }
        MediaActions.next = { controllers[pkg]?.transportControls?.skipToNext() }
        MediaActions.previous = { controllers[pkg]?.transportControls?.skipToPrevious() }
    }

    private fun publishMedia(pkg: String) {
        val controller = controllers[pkg] ?: return
        mediaLastUpdated[pkg] = System.currentTimeMillis()
        val state = controller.playbackState?.state
        when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING ->
                cancelMediaTimeout(pkg)
            PlaybackState.STATE_PAUSED -> scheduleMediaTimeout(pkg)
            else -> {
                cancelMediaTimeout(pkg)
                controllers.remove(pkg, controller)
                callbacks.remove(pkg)?.let { cb ->
                    try { controller.unregisterCallback(cb) } catch (_: Exception) {}
                }
                mediaLastUpdated.remove(pkg)
            }
        }
        selectMediaSession()
    }

    override fun onDestroy() {
        mediaTimeouts.keys.toList().forEach(::cancelMediaTimeout)
        callbacks.forEach { (pkg, cb) -> try { controllers[pkg]?.unregisterCallback(cb) } catch (_: Exception) {} }
        callbacks.clear()
        controllers.clear()
        mediaLastUpdated.clear()
        IslandState.showMedia(null)
        MediaActions.playPause = null
        MediaActions.next = null
        MediaActions.previous = null
        super.onDestroy()
    }
}