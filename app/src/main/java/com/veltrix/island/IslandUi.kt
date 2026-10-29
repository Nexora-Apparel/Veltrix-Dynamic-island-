package com.veltrix.island

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF30D158)

@Composable
fun IslandRoot() {
    val s by IslandState.flow.collectAsState()
    if (!s.screenOn) return
    val cfg = s.config
    val note = s.notification
    val call = s.call
    val media = s.media
    var expanded by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var callClockTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(call?.state) {
        while (call?.state == CallState.ACTIVE) {
            delay(1000)
            callClockTick++
        }
    }
    LaunchedEffect(note?.key, call?.state, s.notificationDurationSeconds) {
        if (note != null && call == null) {
            expanded = true
            delay((s.notificationDurationSeconds * 1000L).coerceIn(10_000L, 60_000L))
            expanded = false
            delay(450)
            IslandState.clearNotification(note.key)
        }
    }
    LaunchedEffect(media?.packageName, media?.elapsedSeconds, media?.playing) {
        elapsed = media?.elapsedSeconds ?: 0L
        while (media?.playing == true) {
            delay(1000)
            elapsed++
        }
    }
    // Keep paused media visible briefly, then clear it after 10 minutes of inactivity.
    LaunchedEffect(media?.packageName, media?.playing) {
        val inactivePackage = media?.packageName
        if (media != null && !media.playing && inactivePackage != null) {
            delay(10 * 60 * 1000L)
            val current = IslandState.flow.value.media
            if (current?.packageName == inactivePackage && current?.playing == false) {
                IslandState.showMedia(null)
            }
        }
    }
    val liquid = spring<Dp>(dampingRatio = 0.58f, stiffness = Spring.StiffnessLow)
    val widthTarget = if (expanded) 320.dp else cfg.widthDp.dp
    val heightTarget = if (expanded) when {
        call != null -> 92.dp
        media != null -> 106.dp
        note != null -> 78.dp
        else -> 48.dp
    } else cfg.heightDp.dp
    val w by animateDpAsState(widthTarget, liquid, label = "w")
    val h by animateDpAsState(heightTarget, liquid, label = "h")
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(s.unlockTick) {
        if (s.unlockTick > 0) {
            bounce.animateTo(1.2f, tween(100, easing = FastOutSlowInEasing))
            bounce.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 320f))
        }
    }
    val dot = when {
        s.level in 0..39 -> Red
        s.level in 40..100 -> Green
        else -> null
    }
    Box(Modifier.padding(10.dp)) {
        Box(Modifier.size(w, h)
            .graphicsLayer { scaleX = bounce.value; scaleY = 1f + (bounce.value - 1f) * 0.6f }
            .clip(RoundedCornerShape(minOf(h / 2, 34.dp)))
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
        ) {
            when {
                expanded && media != null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(media.packageName, 28.dp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(media.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(media.artist.ifBlank { appLabel(media.packageName) }, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatTime(elapsed), color = Green, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(7.dp))
                            if (dot != null) PulseDot(dot)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { MediaActions.previous?.invoke() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("⏮", color = Color.White, fontSize = 19.sp) }
                            TextButton(onClick = { MediaActions.playPause?.invoke() }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(if (media.playing) "Ⅱ" else "▶", color = Green, fontSize = 20.sp) }
                            TextButton(onClick = { MediaActions.next?.invoke() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("⏭", color = Color.White, fontSize = 19.sp) }
                        }
                    }
                }
                expanded && call != null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(call.packageName ?: "com.android.phone", 28.dp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (call.state == CallState.RINGING) "INCOMING CALL" else "CALL IN PROGRESS", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(call.number ?: if (call.packageName == "com.whatsapp") "WhatsApp call" else "Phone call", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (call.state == CallState.ACTIVE) Text(formatTime(((System.currentTimeMillis() - call.startedAtMillis).coerceAtLeast(0L) / 1000L) + callClockTick * 0L), color = Green, fontSize = 12.sp)
                            Spacer(Modifier.width(7.dp))
                            if (dot != null) PulseDot(dot)
                        }
                        Spacer(Modifier.height(5.dp))
                        if (call.packageName == "com.whatsapp" && CallActions.endCall != null) {
                            TextButton(onClick = { CallActions.endCall?.invoke() }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (call.state == CallState.RINGING) "Decline call" else "End call", color = Color(0xFFFF453A), fontSize = 12.sp)
                            }
                        } else {
                            Text(if (call.state == CallState.RINGING) "Use the phone screen to answer or decline" else "Use the phone screen to end the call", color = Color.LightGray, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }
                expanded && note != null -> {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(note.packageName, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(note.app, color = Color(0xFF8E8E93), fontSize = 11.sp, maxLines = 1)
                            Text(note.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (note.text.isNotEmpty()) Text(note.text, color = Color(0xFFD1D1D6), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                !expanded && media != null -> {
                    Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(media.packageName, 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { GreenWave() }
                        Spacer(Modifier.width(6.dp))
                        Text(formatTime(elapsed), color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        if (dot != null) PulseDot(dot)
                    }
                }
                !expanded && call != null -> {
                    Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(call.packageName ?: "com.android.phone", 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { GreenWave() }
                        Spacer(Modifier.width(6.dp))
                        Text(if (call.state == CallState.ACTIVE) formatTime((System.currentTimeMillis() - call.startedAtMillis).coerceAtLeast(0L) / 1000L + callClockTick * 0L) else "CALL", color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        if (dot != null) PulseDot(dot)
                    }
                }
                !expanded && note != null -> {
                    Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(note.packageName, 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(note.title, Modifier.weight(1f), color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (dot != null) PulseDot(dot)
                    }
                }
                expanded -> Text("${s.level}%${if (s.charging) "  ⚡" else ""}", Modifier.align(Alignment.Center), color = Color.White, fontSize = 15.sp)
                else -> if (dot != null) Box(Modifier.fillMaxSize().padding(end = 12.dp), Alignment.CenterEnd) { PulseDot(dot) }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String, size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, 64, 64)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (_: Exception) { null }
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(7.dp)))
    else Box(Modifier.size(size).background(Color.DarkGray, CircleShape))
}

@Composable
private fun appLabel(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) {
        try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
        catch (_: Exception) { packageName }
    }
}

@Composable
private fun GreenWave() {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(13) { index ->
            val height by transition.animateFloat(
                initialValue = 4f,
                targetValue = (7 + (index * 7 % 15)).toFloat(),
                animationSpec = infiniteRepeatable(tween(330 + index * 25, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar$index"
            )
            Box(Modifier.width(2.dp).height(height.dp).clip(CircleShape).background(Green))
        }
    }
}

private fun formatTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return "%d:%02d".format(safe / 60, safe % 60)
}

@Composable
private fun PulseDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(6.dp).graphicsLayer { alpha = a.value; val k = 0.75f + 0.25f * a.value; scaleX = k; scaleY = k }.background(color, CircleShape))
}