package com.veltrix.island

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class IslandService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Island service", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Island running")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        IslandHost.fgActive = true
        if (!IslandHost.accessibilityActive) IslandHost.start(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() {
        IslandHost.fgActive = false
        if (!IslandHost.accessibilityActive) IslandHost.stop()
        super.onDestroy()
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Settings.canDrawOverlays(c))
            ContextCompat.startForegroundService(c, Intent(c, IslandService::class.java))
    }
}