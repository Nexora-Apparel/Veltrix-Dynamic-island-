package com.veltrix.island

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/** Bound by Android only when notification access is granted. */
class IslandNotificationListener : NotificationListenerService() {
    // Keep the island visible when Android reports removal of a notification
    // that this service dismissed after successfully capturing it.
    private val dismissedByIsland = ConcurrentHashMap.newKeySet<String>()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (dismissedByIsland.remove(sbn.key)) return
        IslandState.clearNotification(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!IslandState.flow.value.screenOn) return
        if (sbn.isOngoing || sbn.packageName !in Prefs.allowed(this)) return
        val n = sbn.notification
        // Never dismiss call notifications: Android's native incoming-call UI
        // and call actions must remain available while the island mirrors calls.
        if (n.category == Notification.CATEGORY_CALL) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        IslandState.showNotification(IslandNotification(sbn.key, label, title, text))
        dismissedByIsland.add(sbn.key)
        try {
            cancelNotification(sbn.key)
        } catch (e: SecurityException) {
            dismissedByIsland.remove(sbn.key)
        } catch (e: IllegalArgumentException) {
            dismissedByIsland.remove(sbn.key)
        }
    }
}