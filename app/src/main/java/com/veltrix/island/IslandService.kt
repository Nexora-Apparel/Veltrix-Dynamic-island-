package com.veltrix.island

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class IslandService : Service() {
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        ?.takeIf { it.isNotBlank() }
                    IslandState.showCall(IslandCall(CallState.RINGING, number))
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    val current = IslandState.flow.value.call
                    IslandState.showCall(
                        if (current != null) current.copy(state = CallState.ACTIVE, startedAtMillis = if (current.startedAtMillis == 0L) System.currentTimeMillis() else current.startedAtMillis)
                        else IslandCall(CallState.ACTIVE, startedAtMillis = System.currentTimeMillis())
                    )
                }
                TelephonyManager.EXTRA_STATE_IDLE -> IslandState.clearCall()
            }
        }
    }
    private var phoneReceiverRegistered = false

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
        registerPhoneReceiverIfPermitted()
    }

    private fun registerPhoneReceiverIfPermitted() {
        if (phoneReceiverRegistered || checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            ContextCompat.registerReceiver(this, phoneStateReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            phoneReceiverRegistered = true
        } catch (_: SecurityException) { }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() {
        if (phoneReceiverRegistered) {
            try { unregisterReceiver(phoneStateReceiver) } catch (_: IllegalArgumentException) { }
            phoneReceiverRegistered = false
        }
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