package com.lorenzo.ettyflix.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.Store

/** Pulsante tondo con la stella (come il cuore/+): vuota se non hai votato, piena col tuo voto. */
@Composable
fun RatingPill(item: MediaItem, modifier: Modifier = Modifier) {
    val r = Store.rating(item)
    if (r > 0) PillButton("$r", Icons.Filled.Star, modifier) { Nav.rate = item }
    else PillButton(icon = Icons.Filled.StarBorder, modifier = modifier) { Nav.rate = item }
}

/** Finestra "Il tuo voto": 1–10 (l'IA lo usa per capire i tuoi gusti), Togli il voto, Non mi piace. */
@Composable
fun RatingDialog(item: MediaItem) {
    val current = Store.rating(item)
    val first = remember { FocusRequester() }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    fun close() { Nav.rate = null }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000))
        .onPreviewKeyEvent {
            val n = it.nativeKeyEvent
            val ok = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            ok && n.eventTime - openedAt < 500
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(C.panel, RoundedCornerShape(20.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Il tuo voto", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(item.title, color = C.dim, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(16.dp))
            for (row in listOf(1..5, 6..10)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    for (n in row) {
                        val m = if (n == (current.takeIf { it > 0 } ?: 1)) Modifier.focusRequester(first) else Modifier
                        RateCircle(n, n == current, m) { Store.setRating(item, n); close() }
                    }
                }
            }
            Text("1 = pessimo · 10 = capolavoro. L'IA usa i tuoi voti per scegliere i consigli.",
                color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current > 0) PillButton("Togli il voto") { Store.setRating(item, 0); close() }
                PillButton(if (Store.isDisliked(item)) "Togli \"Non mi piace\"" else "Non mi piace", Icons.Filled.ThumbDown) {
                    Store.toggleDisliked(item); close()
                }
                PillButton("Chiudi") { close() }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}

@Composable
private fun RateCircle(n: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(modifier.scale(if (focused) 1.12f else 1f).size(56.dp).clip(CircleShape)
        .background(if (focused) Color.White else if (selected) C.yellow.copy(alpha = 0.35f) else Color(0x26FFFFFF))
        .border(if (selected && !focused) 2.dp else 1.dp, if (selected && !focused) C.yellow else Color(0x33FFFFFF), CircleShape)
        .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text("$n", color = if (focused) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
