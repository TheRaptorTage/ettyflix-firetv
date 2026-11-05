package com.lorenzo.ettyflix.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.QualityBadges
import com.lorenzo.ettyflix.data.TMDBImage

object C {
    val bg = Color(0xFF0E1014)
    val panel = Color(0xFF1A1D24)
    val glass = Color(0x33FFFFFF)
    val glassStrong = Color(0x55FFFFFF)
    val text = Color(0xFFF2F2F2)
    val dim = Color(0xB3FFFFFF)
    val faint = Color(0x80FFFFFF)
    val accent = Color(0xFFE9A23B)
    val yellow = Color(0xFFF5C518)
    val pad = 48.dp
}

/** Tasti che aprono subito il menu dell'elemento (utili con il telecomando della TV via HDMI-CEC,
 *  che spesso non manda la pressione prolungata di OK). */
private val menuKeys = setOf(
    android.view.KeyEvent.KEYCODE_MENU, android.view.KeyEvent.KEYCODE_INFO,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
    android.view.KeyEvent.KEYCODE_PROG_RED, android.view.KeyEvent.KEYCODE_PROG_GREEN,
    android.view.KeyEvent.KEYCODE_PROG_YELLOW, android.view.KeyEvent.KEYCODE_PROG_BLUE,
    android.view.KeyEvent.KEYCODE_GUIDE, android.view.KeyEvent.KEYCODE_SETTINGS
)

/** Il tasto arriva dal telecomando della TV (HDMI-CEC) e non da quello della Fire TV?
 *  I tasti CEC vengono "iniettati" dal sistema: non hanno un dispositivo fisico vero. */
private fun fromTvRemote(n: android.view.KeyEvent): Boolean {
    val d = n.device ?: return true
    if (d.isVirtual || n.deviceId <= 0) return true
    val name = d.name.lowercase()
    return "cec" in name || "hdmi" in name
}

/** OK tenuto premuto (o tasto ☰ / Play-Pausa / Info / tasti colorati): apre il menu dell'elemento.
 *  - Telecomando Fire TV: la pressione lunga si riconosce dalle ripetizioni del tasto.
 *  - Telecomandi delle TV (CEC): non mandano la pressione lunga in modo affidabile (spesso mandano tanti
 *    clic veloci). Se [onClick] è indicato, il clic parte dopo un attimo: se nel frattempo arriva un altro OK
 *    (tasto tenuto premuto o doppio clic) si apre il menu invece della pagina. */
fun Modifier.onMenuKey(onClick: (() -> Unit)? = null, action: () -> Unit): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var longPressed by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var second by remember { mutableStateOf(false) }
    onPreviewKeyEvent {
        val n = it.nativeKeyEvent
        val ok = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                || n.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        val down = it.type == KeyEventType.KeyDown
        val up = it.type == KeyEventType.KeyUp
        when {
            n.keyCode in menuKeys -> { if (down && n.repeatCount == 0) { pending?.cancel(); pending = null; action() }; true }
            !ok -> false
            down && n.repeatCount == 0 -> {
                if (pending?.isActive == true) { pending?.cancel(); pending = null; second = true }
                else { second = false }
                downAt = n.eventTime; longPressed = false
                onClick != null         // gestisco io il clic (altrimenti lo fa clickable)
            }
            down -> {
                if (!longPressed && !second && n.repeatCount >= 2) { longPressed = true; action() }
                true   // finché tengo premuto non parte il clic normale
            }
            up && longPressed -> { longPressed = false; true }
            up && second -> { second = false; action(); true }   // secondo OK subito dopo il primo: menu
            up && downAt > 0 && n.eventTime - downAt >= 600 -> { downAt = 0; action(); true }
            up && onClick != null -> {
                downAt = 0
                if (fromTvRemote(n)) {
                    pending = scope.launch { kotlinx.coroutines.delay(450); pending = null; onClick() }
                } else onClick()
                true
            }
            else -> false
        }
    }
}

/** Elemento selezionabile col telecomando: si ingrandisce e ha il bordo bianco quando è selezionato. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focusScale: Float = 1.07f,
    onMenu: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) onFocus?.invoke() }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(160), label = "s")
    Box(
        modifier
            .scale(scale)
            .shadow(if (focused) 18.dp else 0.dp, shape)
            .clip(shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .let { m -> if (onMenu != null) m.onMenuKey(onClick, onMenu) else m }
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** Pulsante a capsula (bianco quando selezionato, vetro scuro altrimenti). */
@Composable
fun PillButton(
    text: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val bg by animateColorAsState(if (focused) Color.White else if (primary) Color(0x40FFFFFF) else Color(0x26FFFFFF), label = "b")
    val fg = if (focused) Color.Black else Color.White
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "s")
    Row(
        modifier
            .scale(scale)
            .clip(if (text == null) CircleShape else RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, Color(0x33FFFFFF), if (text == null) CircleShape else RoundedCornerShape(50))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (text == null) 10.dp else 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        if (text != null) Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.padding(start = C.pad, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Riga orizzontale con titolo. */
@Composable
fun MediaRow(title: String, icon: ImageVector? = null, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        SectionTitle(title, icon = icon)
        LazyRow(
            contentPadding = PaddingValues(horizontal = C.pad, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
fun Img(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    AsyncImage(model = url, contentDescription = null, contentScale = scale, modifier = modifier)
}

/** Locandina verticale. */
@Composable
fun PosterCard(item: MediaItem, width: Dp = 130.dp, showTitle: Boolean = false, onFocus: (() -> Unit)? = null) {
    val watched = com.lorenzo.ettyflix.data.WatchProgress.isWatched(item.tmdbID, item.mediaType)
    Column(Modifier.width(width)) {
        FocusBox(
            onClick = { Nav.push(Screen.Detail(item)) },
            onMenu = { Nav.menu = MenuReq(item) },
            onFocus = onFocus,
            modifier = Modifier.width(width).height(width * 1.5f)
        ) {
            Box(Modifier.fillMaxSize().background(C.panel)) {
                Img(TMDBImage.url(item.posterPath, "w342"), Modifier.fillMaxSize())
                if (item.posterPath == null) Text(item.title, color = C.dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                if (watched) CheckBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        if (showTitle) Text(item.title, color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun CheckBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(Color(0x99000000)).border(1.dp, Color(0x66FFFFFF), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Bollini di qualità (4K, Dolby Vision, HDR, Atmos, 5.1). */
@Composable
fun BadgesRow(b: QualityBadges, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun chip(t: String) = Text(t, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(C.glass).border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp))
        if (b.noSources) chip("Nessuna fonte")
        if (b.uhd) chip("4K") else if (b.fullHD) chip("1080p") else if (b.hd) chip("720p")
        if (b.dolbyVision) chip("Dolby Vision")
        if (b.hdr) chip("HDR")
        if (b.atmos) chip("Dolby Atmos")
        if (b.surround) chip("5.1")
    }
}

/** Sfondo con sfumature verso il colore dell'app. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Box(modifier) {
        Img(url, Modifier.fillMaxSize().let { if (alpha < 1f) it.background(Color.Black) else it })
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xD9000000), 0.45f to Color(0x66000000), 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 0.85f to C.bg.copy(alpha = 0.85f), 1f to C.bg)))
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp), content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(shape).background(Color(0x73151820)).border(1.dp, Color(0x2EFFFFFF), shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}
