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
enum class RingerDisplay { NORMAL, SILENT, VIBRATE }
enum class ActivityKind { MEDIA, CALL, TIMER, NAVIGATION, DELIVERY, OTHER }
data class LiveActivity(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val subtitle: String = "",
    val startedAtMillis: Long = System.currentTimeMillis(),
    val progress: Float? = null,
    val status: String = "active",
    val priority: Int = 0
)
data class IslandUiState(
    val level: Int = 100,
    val charging: Boolean = false,
    val screenOn: Boolean = true,
    val deviceLocked: Boolean = false,
    val ringerDisplay: RingerDisplay = RingerDisplay.NORMAL,
    val unlockTick: Int = 0,
    val notification: IslandNotification? = null,
    val call: IslandCall? = null,
    val media: IslandMedia? = null,
    val liveActivities: List<LiveActivity> = emptyList(),
    val notificationDurationSeconds: Int = 10,
    val config: IslandConfig = IslandConfig()
)
object IslandState {
    val flow = MutableStateFlow(IslandUiState())
    fun showNotification(n: IslandNotification) {
        flow.update { it.copy(notification = n) }
        LiveActivities.upsert(LiveActivity("notification:" + n.key, ActivityKind.OTHER, n.title, n.text, priority = 10))
    }
    fun clearNotification(key: String) {
        flow.update { if (it.notification?.key == key) it.copy(notification = null) else it }
        LiveActivities.remove("notification:" + key)
    }
    fun showCall(call: IslandCall) {
        flow.update { it.copy(call = call, notification = null) }
        LiveActivities.upsert(LiveActivity("call:" + (call.packageName ?: "phone"), ActivityKind.CALL,
            call.number ?: "Phone call", status = call.state.name.lowercase(), startedAtMillis = call.startedAtMillis, priority = 100))
    }
    fun clearCall() {
        flow.update { it.copy(call = null) }
        LiveActivities.removeByPrefix("call:")
    }
    fun showMedia(media: IslandMedia?) {
        flow.update { it.copy(media = media) }
        if (media == null) LiveActivities.removeByPrefix("media:")
        else LiveActivities.upsert(LiveActivity("media:" + media.packageName, ActivityKind.MEDIA,
            media.title, media.artist, status = if (media.playing) "playing" else "paused", priority = 50))
    }
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
    fun setAllAllowed(c: Context, packages: Set<String>, on: Boolean) {
        val selected = if (on) packages else emptySet()
        sp(c).edit().putStringSet("allowed", selected).apply()
    }
    fun notificationDuration(c: Context): Int = sp(c).getInt("notification_duration", 10).coerceIn(10, 60)
    fun setNotificationDuration(c: Context, seconds: Int) {
        sp(c).edit().putInt("notification_duration", seconds.coerceIn(10, 60)).apply()
        IslandState.flow.update { it.copy(notificationDurationSeconds = seconds.coerceIn(10, 60)) }
    }
    fun asked(c: Context) = sp(c).getBoolean("asked", false)
    fun markAsked(c: Context) = sp(c).edit().putBoolean("asked", true).apply()
}
/** In-memory normalized registry shared by media, calls, timers and navigation producers. */
object LiveActivities {
    private val entries = LinkedHashMap<String, LiveActivity>()
    @Synchronized fun upsert(activity: LiveActivity) {
        entries[activity.id] = activity
        publish()
    }
    @Synchronized fun remove(id: String) {
        entries.remove(id)
        publish()
    }
    @Synchronized fun removeByPrefix(prefix: String) {
        entries.keys.filter { it.startsWith(prefix) }.forEach(entries::remove)
        publish()
    }
    @Synchronized fun clear() {
        entries.clear()
        publish()
    }
    @Synchronized fun snapshot(): List<LiveActivity> = entries.values.sortedWith(
        compareByDescending<LiveActivity> { it.priority }.thenBy { it.startedAtMillis }
    )
    private fun publish() {
        val ordered = snapshot()
        IslandState.flow.update { it.copy(liveActivities = ordered) }
    }
}


/** Local countdown producer for the unified Live Activities surface. */
object IslandTimer {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var endAtMillis: Long = 0L
    @Volatile private var durationMillis: Long = 0L
    @Volatile private var label: String = "Timer"
    private val activityId = "timer:local"
    private val ticker = object : Runnable {
        override fun run() {
            val remaining = (endAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val progress = if (durationMillis <= 0L) 0f else 1f - remaining.toFloat() / durationMillis.toFloat()
            if (remaining <= 0L) {
                LiveActivities.upsert(LiveActivity(activityId, ActivityKind.TIMER, "$label complete", "Finished", progress = 1f, status = "completed", priority = 80))
                endAtMillis = 0L
                return
            }
            val seconds = (remaining + 999L) / 1000L
            LiveActivities.upsert(LiveActivity(activityId, ActivityKind.TIMER, label, "%d:%02d remaining".format(seconds / 60L, seconds % 60L), progress = progress.coerceIn(0f, 1f), priority = 80))
            handler.postDelayed(this, 1000L)
        }
    }

    @Synchronized fun start(durationSeconds: Long, title: String = "Timer") {
        require(durationSeconds in 1L..86400L) { "Timer duration must be between 1 second and 24 hours." }
        handler.removeCallbacks(ticker)
        durationMillis = durationSeconds * 1000L
        endAtMillis = System.currentTimeMillis() + durationMillis
        label = title.take(40).ifBlank { "Timer" }
        ticker.run()
    }

    @Synchronized fun cancel() {
        handler.removeCallbacks(ticker)
        endAtMillis = 0L
        LiveActivities.remove(activityId)
    }

    fun isRunning(): Boolean = endAtMillis > System.currentTimeMillis()
}
