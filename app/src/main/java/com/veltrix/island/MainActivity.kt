package com.veltrix.island

import android.Manifest
import android.app.DownloadManager
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
import kotlinx.coroutines.launch

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
    val phoneOk = remember(tick) { androidx.core.content.PermissionChecker.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == androidx.core.content.PermissionChecker.PERMISSION_GRANTED }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val a11yOk = remember(tick) {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${ctx.packageName}/${IslandAccessibilityService::class.java.name}") == true
    }
    var cfg by remember { mutableStateOf(Prefs.readConfig(ctx)) }
    var allowed by remember { mutableStateOf(Prefs.allowed(ctx)) }
    var notificationDuration by remember { mutableIntStateOf(Prefs.notificationDuration(ctx)) }
    LaunchedEffect(notificationDuration) { Prefs.setNotificationDuration(ctx, notificationDuration) }
    var askDialog by remember { mutableStateOf(!Prefs.asked(ctx) && !listenerOk) }
    val screenW = LocalConfiguration.current.screenWidthDp

    val postPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Settings.canDrawOverlays(ctx)) ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java))
        tick++
    }
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
        item { UpdaterCard() }
        item { Step("1. Draw over other apps (required)", overlayOk, "Grant") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) } }
        item { Step("2. Notification access", listenerOk, "Grant") {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        item { Step("3. Phone call detection", phoneOk, "Allow") {
            if (!phoneOk) phonePerm.launch(Manifest.permission.READ_PHONE_STATE)
        } }
        item { Step("4. Lock-screen mode (Accessibility)", a11yOk, "Enable") {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        item { Step("5. Battery optimization", batteryOk, "Set unrestricted") {
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

        item { Text("Notification display duration", style = MaterialTheme.typography.titleMedium) }
        item { Text("Choose how long each notification stays expanded before the island returns to normal.") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 60).forEach { seconds ->
                    if (notificationDuration == seconds) Button(onClick = { notificationDuration = seconds }, modifier = Modifier.weight(1f)) { Text("${seconds}s") }
                    else OutlinedButton(onClick = { notificationDuration = seconds }, modifier = Modifier.weight(1f)) { Text("${seconds}s") }
                }
            }
        }

        item { Text("Apps shown in the island", style = MaterialTheme.typography.titleMedium) }
        item { Text("Turn on every app whose notifications or app icon you want to see here. Music playback is detected separately when the app exposes an Android media session.") }
        items(apps, key = { it.first }) { (pkg, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Switch(pkg in allowed, { on -> Prefs.setAllowed(ctx, pkg, on); allowed = Prefs.allowed(ctx) })
            }
        }
    }
}


@Composable
private fun UpdaterCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Check GitHub for the latest version.") }
    var update by remember { mutableStateOf<IslandUpdateInfo?>(null) }
    var currentVersion by remember { mutableIntStateOf(1) }
    var downloadStatus by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        currentVersion = try {
            if (Build.VERSION.SDK_INT >= 28) ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
            else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (_: Exception) { 1 }
        downloadStatus = IslandUpdater.downloadStatus(ctx) ?: -1
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleLarge)
            Text("Installed version code: $currentVersion")
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = "Checking GitHub Releases…"
                    scope.launch {
                        try {
                            val found = IslandUpdater.check()
                            update = found
                            message = when {
                                found == null -> "No published update found yet. The release updater needs a stable signing key configured."
                                found.versionCode <= currentVersion -> "You're up to date."
                                else -> "Update available: " + found.versionName + " (code " + found.versionCode + ")"
                            }
                        } catch (_: Exception) {
                            message = "Could not check for updates. Check your internet connection and try again."
                        } finally { checking = false }
                    }
                }
            ) { Text(if (checking) "Checking…" else "Check for updates") }

            val available = update?.let { it.versionCode > currentVersion } == true
            if (available) {
                Button(onClick = {
                    val found = update ?: return@Button
                    try {
                        IslandUpdater.download(ctx, found)
                        downloadStatus = DownloadManager.STATUS_PENDING
                        message = "Download started. Android will show download progress in notifications."
                    } catch (_: Exception) {
                        message = "Could not start the download. Please try again."
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Download & Install") }
            }

            if (downloadStatus == DownloadManager.STATUS_PENDING ||
                downloadStatus == DownloadManager.STATUS_RUNNING ||
                downloadStatus == DownloadManager.STATUS_PAUSED) {
                OutlinedButton(onClick = {
                    downloadStatus = IslandUpdater.downloadStatus(ctx) ?: -1
                    message = if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) "Download complete. Tap Install update." else "Download is still in progress."
                }, modifier = Modifier.fillMaxWidth()) { Text("Check download status") }
            }
            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                Button(onClick = {
                    val opened = IslandUpdater.installDownloaded(ctx)
                    if (opened) message = "Opening Android installer…"
                    else message = "Allow Veltrix Island to install unknown apps in Settings, then return and tap Install update again."
                }, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
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