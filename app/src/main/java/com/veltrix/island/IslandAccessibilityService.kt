package com.veltrix.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Hosts a lock-screen-capable overlay and does not inspect screen content. */
class IslandAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        IslandHost.accessibilityActive = true
        IslandHost.start(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        IslandHost.accessibilityActive = false
        IslandHost.stop()
        if (IslandHost.fgActive) IslandHost.start(applicationContext, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        return super.onUnbind(intent)
    }
}