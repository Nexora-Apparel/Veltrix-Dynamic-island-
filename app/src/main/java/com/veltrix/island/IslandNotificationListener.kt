package com.veltrix.island

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Bound by Android only when notification access is granted. */
class IslandNotificationListener : NotificationListenerService() {
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandState.clearNotification(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!IslandState.flow.value.screenOn) return
        if (sbn.isOngoing || sbn.packageName !in Prefs.allowed(this)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        IslandState.showNotification(IslandNotification(sbn.key, label, title, text))
    }
}