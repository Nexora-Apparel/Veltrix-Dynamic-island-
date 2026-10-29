package com.veltrix.island

import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.update

class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val ssrc = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry
    fun create() { ssrc.performAttach(); ssrc.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun pause() { registry.currentState = Lifecycle.State.CREATED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
object IslandHost {
    private var rt: IslandRuntime? = null
    @Volatile var accessibilityActive = false
    @Volatile var fgActive = false
    fun start(ctx: Context, type: Int) { rt?.stop(); rt = IslandRuntime(ctx, type).also { it.start() } }
    fun stop() { rt?.stop(); rt = null }
}
class IslandRuntime(private val ctx: Context, private val type: Int) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = OverlayOwner()
    private val density = ctx.resources.displayMetrics.density
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var batteryOn = false
    private val prefsListener = OnSharedPreferenceChangeListener { _, _ -> applyConfig() }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readBattery(i)
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> onScreen(true)
                Intent.ACTION_SCREEN_OFF -> onScreen(false)
                Intent.ACTION_USER_PRESENT -> IslandState.flow.update { it.copy(unlockTick = it.unlockTick + 1) }
            }
        }
    }
    @Suppress("DEPRECATION")
    fun start() {
        owner.create()
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { IslandRoot() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp = params; view = v
        try { wm.addView(v, params) } catch (e: Exception) { stop(); return }
        applyConfig()
        Prefs.sp(ctx).registerOnSharedPreferenceChangeListener(prefsListener)
        ContextCompat.registerReceiver(ctx, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        onScreen(ctx.getSystemService(PowerManager::class.java).isInteractive)
    }
    private fun applyConfig() {
        val cfg = Prefs.readConfig(ctx)
        IslandState.flow.update { it.copy(config = cfg) }
        val p = lp ?: return
        p.x = (cfg.offsetXdp * density).toInt()
        p.y = ((cfg.offsetYdp - 10) * density).toInt()
        view?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, p) }
    }
    private fun onScreen(on: Boolean) {
        IslandState.flow.update { it.copy(screenOn = on) }
        if (on) { owner.resume(); registerBattery(); view?.visibility = android.view.View.VISIBLE }
        else { unregisterBattery(); owner.pause(); view?.visibility = android.view.View.GONE }
    }
    private fun registerBattery() {
        if (batteryOn) return
        batteryOn = true
        val sticky = ContextCompat.registerReceiver(ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        readBattery(sticky)
    }
    private fun unregisterBattery() {
        if (!batteryOn) return
        batteryOn = false
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) {}
    }
    private fun readBattery(i: Intent?) {
        i ?: return
        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (raw < 0) return
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        IslandState.flow.update { it.copy(level = raw * 100 / scale, charging = plugged) }
    }
    fun stop() {
        unregisterBattery()
        try { ctx.unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        Prefs.sp(ctx).unregisterOnSharedPreferenceChangeListener(prefsListener)
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        owner.destroy()
    }
}