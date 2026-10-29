package com.veltrix.island

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Setup() } } }
    }
}

@Composable
private fun Setup() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val listenerOk = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val a11yOk = remember(tick) {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${ctx.packageName}/${IslandAccessibilityService::class.java.name}") == true
    }
    var cfg by remember { mutableStateOf(Prefs.readConfig(ctx)) }
    var allowed by remember { mutableStateOf(Prefs.allowed(ctx)) }
    var askDialog by remember { mutableStateOf(!Prefs.asked(ctx) && !listenerOk) }
    val screenW = LocalConfiguration.current.screenWidthDp

    val postPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) postPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(overlayOk) {
        if (overlayOk) ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java))
    }
    fun save(c: IslandConfig) { cfg = c; Prefs.writeConfig(ctx, c) }

    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }

    if (askDialog) AlertDialog(
        onDismissRequest = { askDialog = false; Prefs.markAsked(ctx) },
        title = { Text("Allow notification access?") },
        text = { Text("The island needs notification access to show alerts from the apps you pick. Only apps you enable below are ever shown, and nothing leaves your phone.") },
        confirmButton = { TextButton({
            askDialog = false; Prefs.markAsked(ctx)
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text("Continue") } },
        dismissButton = { TextButton({ askDialog = false; Prefs.markAsked(ctx) }) { Text("Not now") } }
    )

    LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Veltrix Island", style = MaterialTheme.typography.headlineMedium) }
        item { Step("1. Draw over other apps (required)", overlayOk, "Grant") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) } }
        item { Step("2. Notification access", listenerOk, "Grant") {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        item { Step("3. Lock-screen mode (Accessibility)", a11yOk, "Enable") {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        item { Step("4. Battery optimization", batteryOk, "Set unrestricted") {
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } }

        item { Text("Camera alignment", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val edge = screenW / 2 - 44
                OutlinedButton({ save(cfg.copy(offsetXdp = -edge)) }) { Text("Left") }
                OutlinedButton({ save(cfg.copy(offsetXdp = 0)) }) { Text("Center") }
                OutlinedButton({ save(cfg.copy(offsetXdp = edge)) }) { Text("Right") }
            }
        }
        item { Slide("X offset ${cfg.offsetXdp}dp", cfg.offsetXdp, -(screenW / 2)..(screenW / 2)) { save(cfg.copy(offsetXdp = it)) } }
        item { Slide("Y offset ${cfg.offsetYdp}dp", cfg.offsetYdp, 0..60) { save(cfg.copy(offsetYdp = it)) } }
        item { Slide("Width ${cfg.widthDp}dp", cfg.widthDp, 20..220) { save(cfg.copy(widthDp = it)) } }
        item { Slide("Height ${cfg.heightDp}dp", cfg.heightDp, 16..60) { save(cfg.copy(heightDp = it)) } }

        item { Text("Apps shown in the island", style = MaterialTheme.typography.titleMedium) }
        items(apps, key = { it.first }) { (pkg, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Switch(pkg in allowed, { on -> Prefs.setAllowed(ctx, pkg, on); allowed = Prefs.allowed(ctx) })
            }
        }
    }
}

@Composable
private fun Step(title: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (done) "✅ " else "⬜ ") + title, Modifier.weight(1f))
        if (!done) Button(onClick) { Text(action) }
    }
}
@Composable
private fun Slide(label: String, v: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(v.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}