package com.veltrix.island

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Color as AndroidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF30D158)

@Composable
fun IslandRoot() {
    val s by IslandState.flow.collectAsState()
    if (!s.screenOn) return
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val cfg = s.config
    val note = s.notification
    val call = s.call
    val media = s.media
    val activity = s.liveActivities.firstOrNull { it.kind == ActivityKind.TIMER || it.kind == ActivityKind.NAVIGATION || it.kind == ActivityKind.DELIVERY }
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
    var unlockReveal by remember { mutableStateOf(false) }
    val liquid = spring<Dp>(dampingRatio = 0.58f, stiffness = Spring.StiffnessLow)
    // Unlock briefly morphs the compact island into a wider capsule, then returns it to rest.
    val widthTarget = when {
        unlockReveal -> 132.dp
        expanded -> 320.dp
        else -> cfg.widthDp.dp
    }
    val heightTarget = if (unlockReveal) 132.dp else if (expanded) when {
        call != null -> 92.dp
        media != null -> 106.dp
        note != null -> 78.dp
        activity != null -> 82.dp
        else -> 48.dp
    } else if (unlockReveal) 54.dp else cfg.heightDp.dp
    val w by animateDpAsState(widthTarget, spring(dampingRatio = 0.62f, stiffness = 460f), label = "w")
    val h by animateDpAsState(heightTarget, liquid, label = "h")
    val bounce = remember { Animatable(1f) }
    val unlockLift = remember { Animatable(0f) }
    val unlockScaleX = remember { Animatable(1f) }
    val unlockScaleY = remember { Animatable(1f) }
    val unlockFlash = remember { Animatable(0f) }
    var unlockGlow by remember { mutableStateOf(false) }
    val chargePulse = remember { Animatable(1f) }
    var chargeGlow by remember { mutableStateOf(false) }
    LaunchedEffect(s.charging) {
        if (s.charging) {
            chargeGlow = true
            chargePulse.snapTo(0.72f)
            chargePulse.animateTo(1.22f, spring(dampingRatio = 0.42f, stiffness = 420f))
            chargePulse.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 360f))
            delay(700L)
            chargeGlow = false
        } else {
            chargeGlow = false
            chargePulse.snapTo(1f)
        }
    }
    val islandBorder by animateColorAsState(
        if (unlockGlow || chargeGlow) Color(0xFF30D158) else Color(0xFF242424),
        tween(260), label = "unlockBorder"
    )
    LaunchedEffect(s.unlockTick) {
        if (s.unlockTick > 0) {
            unlockGlow = true
            unlockReveal = true
            bounce.snapTo(0.97f)
            unlockLift.snapTo(-5f)
            unlockScaleX.snapTo(0.82f)
            unlockScaleY.snapTo(1.16f)
            unlockFlash.snapTo(0.72f)
            // iPhone-inspired unlock: compress, stretch, rebound and settle.
            coroutineScope {
                launch { unlockScaleX.animateTo(1.08f, spring(dampingRatio = 0.48f, stiffness = 620f)) }
                launch { unlockScaleY.animateTo(0.94f, spring(dampingRatio = 0.48f, stiffness = 620f)) }
                launch { unlockFlash.animateTo(0f, tween(620, easing = FastOutSlowInEasing)) }
                launch { bounce.animateTo(1.045f, spring(dampingRatio = 0.52f, stiffness = 560f)) }
                launch { unlockLift.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 500f)) }
            }
            unlockScaleX.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
            unlockScaleY.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
            bounce.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
            delay(1_200L)
            unlockReveal = false
            delay(220L)
            unlockGlow = false
        }
    }
    val dot = when {
        s.level in 0..39 -> Red
        s.level in 40..100 -> Green
        else -> null
    }
    Box(Modifier.padding(10.dp)) {
        // Short-lived halo gives the unlock transition a soft luminous edge.
        Box(
            Modifier.size(w + 12.dp, h + 12.dp)
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha = unlockFlash.value
                    scaleX = 0.94f + unlockFlash.value * 0.08f
                    scaleY = 0.94f + unlockFlash.value * 0.08f
                }
                .clip(RoundedCornerShape(50))
                .background(Green.copy(alpha = 0.32f))
        )
        Box(Modifier.size(w, h)
            .graphicsLayer {
                scaleX = bounce.value * unlockScaleX.value
                scaleY = bounce.value * unlockScaleY.value
                translationY = unlockLift.value * density
            }
             .shadow(if (unlockGlow || chargeGlow) 24.dp else 18.dp, RoundedCornerShape(50), clip = false)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF050505))
            .border(if (unlockGlow) 1.1.dp else 0.7.dp, islandBorder, RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
        ) {
            if (unlockReveal) {
                UnlockChainCircle(Modifier.fillMaxSize())
            } else {
            StatusIndicators(s.deviceLocked, s.ringerDisplay, s.charging, chargePulse.value, Modifier.align(Alignment.CenterStart).padding(start = 9.dp))
            when {
                expanded && media != null && call == null && note == null -> {
                    Column(Modifier.fillMaxSize().padding(start = 42.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.Center) {
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
                expanded && activity != null && note == null -> {
                    Column(Modifier.fillMaxSize().padding(start = 42.dp, end = 14.dp, top = 9.dp, bottom = 9.dp), verticalArrangement = Arrangement.Center) {
                        Text(activity.kind.name, color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(activity.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (activity.subtitle.isNotBlank()) Text(activity.subtitle, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        activity.progress?.let { progress ->
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0xFF333333))) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight().then(Modifier).graphicsLayer { scaleX = progress.coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(Green, CircleShape))
                            }
                        }
                    }
                }
                expanded && note != null -> {
                    Row(Modifier.fillMaxSize().padding(start = 42.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(note.packageName, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(note.app, color = Color(0xFF8E8E93), fontSize = 11.sp, maxLines = 1)
                            Text(note.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (note.text.isNotEmpty()) Text(note.text, color = Color(0xFFD1D1D6), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                !expanded && media != null && call == null && note == null -> {
                    Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                !expanded && note == null && activity != null -> {
                    Row(Modifier.fillMaxSize().padding(start = 42.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (activity.kind) { ActivityKind.TIMER -> "◷"; ActivityKind.NAVIGATION -> "➤"; else -> "•" }, color = Green, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(activity.title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (activity.subtitle.isNotBlank()) Text(activity.subtitle, color = Color.LightGray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
}

@Composable
private fun UnlockChainCircle(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "unlock-chain")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
        label = "unlock-chain-rotation"
    )
    androidx.compose.foundation.Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.355f
        val linkW = size.minDimension * 0.105f
        val linkH = size.minDimension * 0.205f
        rotate(angle, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            repeat(12) { index ->
                val theta = Math.toRadians(index * 30.0)
                val x = cx + kotlin.math.cos(theta).toFloat() * radius
                val y = cy + kotlin.math.sin(theta).toFloat() * radius
                rotate(index * 30f + 90f, pivot = androidx.compose.ui.geometry.Offset(x, y)) {
                    drawOval(
                        color = Color.White,
                        topLeft = androidx.compose.ui.geometry.Offset(x - linkW / 2f, y - linkH / 2f),
                        size = androidx.compose.ui.geometry.Size(linkW, linkH),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.018f)
                    )
                }
            }
        }
        val textPath = AndroidPath().apply {
            addCircle(cx, cy, size.minDimension * 0.265f, AndroidPath.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = size.minDimension * 0.082f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(angle, cx, cy)
        drawContext.canvas.nativeCanvas.drawTextOnPath("UNLOCKED", textPath, 0f, 0f, paint)
        drawContext.canvas.nativeCanvas.restore()
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
@Composable
private fun StatusIndicators(locked: Boolean, mode: RingerDisplay, charging: Boolean, chargeScale: Float, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (locked) LockIndicator()
        if (charging) Text("⚡", fontSize = 12.sp, color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.graphicsLayer { scaleX = chargeScale; scaleY = chargeScale })
        when (mode) {
            RingerDisplay.SILENT -> SolidBellIndicator(Red)
            RingerDisplay.VIBRATE -> Text("〰", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
            RingerDisplay.NORMAL -> SolidBellIndicator(Color.White)
        }
    }
}

@Composable
private fun LockIndicator() {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp, 14.dp)) {
        val w = size.width
        val h = size.height
        drawArc(
            color = Color.White,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.04f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.62f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx())
        )
        drawRoundRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.43f),
            size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.53f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )
        drawCircle(Color(0xFF050505), radius = 0.8.dp.toPx(), center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.66f))
    }
}

@Composable
private fun SolidBellIndicator(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val bell = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            cubicTo(w * 0.25f, h * 0.06f, w * 0.2f, h * 0.29f, w * 0.2f, h * 0.48f)
            cubicTo(w * 0.2f, h * 0.67f, w * 0.12f, h * 0.75f, w * 0.08f, h * 0.82f)
            lineTo(w * 0.92f, h * 0.82f)
            cubicTo(w * 0.88f, h * 0.75f, w * 0.8f, h * 0.67f, w * 0.8f, h * 0.48f)
            cubicTo(w * 0.8f, h * 0.29f, w * 0.75f, h * 0.06f, w * 0.5f, h * 0.06f)
            close()
        }
        drawPath(bell, color)
        drawCircle(color, radius = w * 0.1f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.94f))
    }
}
