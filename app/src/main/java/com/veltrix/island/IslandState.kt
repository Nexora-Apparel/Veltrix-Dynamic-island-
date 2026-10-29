package com.veltrix.island

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class IslandConfig(
    val offsetXdp: Int = 0,
    val offsetYdp: Int = 6,
    val widthDp: Int = 110,
    val heightDp: Int = 32
)
data class IslandNotification(val key: String, val app: String, val packageName: String, val title: String, val text: String)
data class IslandCall(val state: CallState, val number: String? = null, val startedAtMillis: Long = 0L, val packageName: String? = null)
data class IslandMedia(val packageName: String, val title: String, val artist: String, val playing: Boolean, val elapsedSeconds: Long = 0L)
enum class CallState { RINGING, ACTIVE }
data class IslandUiState(
    val level: Int = 100,
    val charging: Boolean = false,
    val screenOn: Boolean = true,
    val unlockTick: Int = 0,
    val notification: IslandNotification? = null,
    val call: IslandCall? = null,
    val media: IslandMedia? = null,
    val notificationDurationSeconds: Int = 10,
    val config: IslandConfig = IslandConfig()
)
object IslandState {
    val flow = MutableStateFlow(IslandUiState())
    fun showNotification(n: IslandNotification) = flow.update { it.copy(notification = n) }
    fun clearNotification(key: String) =
        flow.update { if (it.notification?.key == key) it.copy(notification = null) else it }
    fun showCall(call: IslandCall) = flow.update { it.copy(call = call, notification = null) }
    fun clearCall() = flow.update { it.copy(call = null) }
    fun showMedia(media: IslandMedia?) = flow.update { it.copy(media = media) }
}
object CallActions { @Volatile var endCall: (() -> Unit)? = null }
object MediaActions {
    @Volatile var playPause: (() -> Unit)? = null
    @Volatile var next: (() -> Unit)? = null
    @Volatile var previous: (() -> Unit)? = null
}
object Prefs {
    fun sp(c: Context) = c.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun readConfig(c: Context) = sp(c).let {
        IslandConfig(it.getInt("x", 0), it.getInt("y", 6), it.getInt("w", 110), it.getInt("h", 32))
    }
    fun writeConfig(c: Context, cfg: IslandConfig) = sp(c).edit()
        .putInt("x", cfg.offsetXdp).putInt("y", cfg.offsetYdp)
        .putInt("w", cfg.widthDp).putInt("h", cfg.heightDp).apply()
    fun allowed(c: Context): Set<String> = HashSet(sp(c).getStringSet("allowed", emptySet()) ?: emptySet())
    fun setAllowed(c: Context, pkg: String, on: Boolean) {
        val s = allowed(c).toMutableSet(); if (on) s.add(pkg) else s.remove(pkg)
        sp(c).edit().putStringSet("allowed", s).apply()
    }
    fun notificationDuration(c: Context): Int = sp(c).getInt("notification_duration", 10).coerceIn(10, 60)
    fun setNotificationDuration(c: Context, seconds: Int) {
        sp(c).edit().putInt("notification_duration", seconds.coerceIn(10, 60)).apply()
        IslandState.flow.update { it.copy(notificationDurationSeconds = seconds.coerceIn(10, 60)) }
    }
    fun asked(c: Context) = sp(c).getBoolean("asked", false)
    fun markAsked(c: Context) = sp(c).edit().putBoolean("asked", true).apply()
}