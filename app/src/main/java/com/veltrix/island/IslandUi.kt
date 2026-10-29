package com.veltrix.island

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(note?.key) {
        if (note != null) { expanded = true; delay(4500); expanded = false; delay(450); IslandState.clearNotification(note.key) }
    }
    LaunchedEffect(expanded) { if (expanded && note == null) { delay(3000); expanded = false } }
    val liquid = spring<Dp>(dampingRatio = 0.58f, stiffness = Spring.StiffnessLow)
    val w by animateDpAsState(if (expanded) 320.dp else cfg.widthDp.dp, liquid, label = "w")
    val h by animateDpAsState(if (expanded) (if (note != null) 78.dp else 48.dp) else cfg.heightDp.dp, liquid, label = "h")
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(s.unlockTick) {
        if (s.unlockTick > 0) {
            bounce.animateTo(1.2f, tween(100, easing = FastOutSlowInEasing))
            bounce.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 320f))
        }
    }
    val dot = when {
        s.level in 0..30 -> Red
        s.level in 40..100 -> Green
        else -> null
    }
    Box(Modifier.padding(10.dp)) {
        Box(Modifier.size(w, h)
            .graphicsLayer { scaleX = bounce.value; scaleY = 1f + (bounce.value - 1f) * 0.6f }
            .clip(RoundedCornerShape(minOf(h / 2, 34.dp)))
            .background(Color(0xFF000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
        ) {
            if (expanded) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (note != null) {
                            Text(note.app, color = Color(0xFF8E8E93), fontSize = 11.sp, maxLines = 1)
                            Text(note.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (note.text.isNotEmpty()) Text(note.text, color = Color(0xFFD1D1D6), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text("${s.level}%" + if (s.charging) "  ⚡" else "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (dot != null) { Spacer(Modifier.width(8.dp)); PulseDot(dot) }
                }
            } else if (dot != null) {
                Box(Modifier.fillMaxSize().padding(end = 12.dp), Alignment.CenterEnd) { PulseDot(dot) }
            }
        }
    }
}
@Composable
private fun PulseDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(6.dp).graphicsLayer { alpha = a.value; val k = 0.75f + 0.25f * a.value; scaleX = k; scaleY = k }.background(color, CircleShape))
}