package com.lorenzo.ettyflix.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.Profile
import com.lorenzo.ettyflix.data.Profiles
import kotlin.math.cos
import kotlin.math.sin

/** Sfondo "petrolio" che respira lentamente (come sull'Apple TV). */
@Composable
fun PetrolBackground() {
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "p")
    val breathe by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(6_000), RepeatMode.Reverse), label = "b")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF041014))
        val w = size.width; val h = size.height
        val blobs = listOf(
            Triple(Offset(w * (0.25f + 0.12f * cos(phase)), h * (0.3f + 0.1f * sin(phase))), Color(0xFF0E5C63), 0.75f),
            Triple(Offset(w * (0.75f + 0.1f * sin(phase * 1.3f)), h * (0.65f + 0.12f * cos(phase))), Color(0xFF0B3F55), 0.7f),
            Triple(Offset(w * (0.55f + 0.15f * cos(phase * 0.7f)), h * (0.15f + 0.1f * cos(phase * 1.1f))), Color(0xFF145A52), 0.55f)
        )
        for ((c, col, r) in blobs) {
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.55f * breathe), Color.Transparent), c, w * r), w * r, c)
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
    }
}

@Composable
fun ProfileScreen() {
    val first = remember { FocusRequester() }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        EttyProfileBackground()   // stesso sfondo dell'animazione d'apertura (colore scelto nelle Impostazioni)
        ProfileClock(Modifier.align(Alignment.TopEnd).padding(36.dp))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Chi vuole guardare?", style = TextStyle(
                brush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.82f))),
                shadow = Shadow(Color(0x73000000), Offset(0f, 3f), 8f)),
                fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Profiles.list.forEachIndexed { i, p ->
                    ProfileAvatar(p, if (i == Profiles.list.indexOfFirst { it.id == Profiles.currentID }.coerceAtLeast(0))
                        Modifier.focusRequester(first) else Modifier) { Profiles.select(p) }
                }
                if (Profiles.list.size < 6) AddProfileButton { adding = true }
            }
        }
        if (adding) NameDialog("Nuovo profilo", "", onDone = { n -> if (n != null) Profiles.add(n); adding = false })
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ProfileAvatar(p: Profile, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tween(220), label = "s")
    val tint = Look.theme.avatarTint(p)   // colore in armonia con lo sfondo scelto
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier
        .clickable(interactionSource = src, indication = null, onClick = onClick)) {
        Box(
            Modifier.scale(scale).size(118.dp).clip(CircleShape)
                // cerchio "di vetro" colorato
                .background(Brush.linearGradient(listOf(tint.copy(alpha = if (focused) 0.75f else 0.55f), tint.copy(alpha = if (focused) 0.5f else 0.32f))))
                .border(if (focused) 3.dp else 1.dp,
                    if (focused) Brush.linearGradient(listOf(Color.White, Color.White)) else
                        Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x1AFFFFFF), Color(0x66FFFFFF))), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(118.dp).background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color.Transparent, Color(0x14FFFFFF)))))
            Text(p.name.take(1).uppercase(), color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        // nome dentro una pillola di vetro (il testo resta normale)
        Text(p.name, color = Color.White, fontSize = 18.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = if (focused) 0.28f else 0.14f))
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF))), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 5.dp))
    }
}

/** Ora e data in alto a destra: lettere chiare con un leggero riflesso e ombra (come sulle altre app). */
@Composable
private fun ProfileClock(modifier: Modifier) {
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) { while (true) { now = java.util.Date(); kotlinx.coroutines.delay(15_000) } }
    val glassText = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.82f))),
        shadow = Shadow(Color(0x73000000), Offset(0f, 3f), 8f))
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALIAN).format(now), style = glassText,
            fontSize = 54.sp, fontWeight = FontWeight.Medium)
        Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.ITALIAN).format(now).replaceFirstChar { it.uppercase() },
            style = glassText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddProfileButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(interactionSource = src, indication = null, onClick = onClick).alpha(if (focused) 1f else 0.6f)) {
        Box(Modifier.size(118.dp).clip(CircleShape).background(Color(0x1AFFFFFF))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Aggiungi", color = C.dim, fontSize = 18.sp)
    }
}
