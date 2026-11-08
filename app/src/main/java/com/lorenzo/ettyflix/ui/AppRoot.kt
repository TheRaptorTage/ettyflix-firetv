package com.lorenzo.ettyflix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.LiveTV
import com.lorenzo.ettyflix.data.Profiles
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.Trakt
import com.lorenzo.ettyflix.data.WatchProgress
import com.lorenzo.ettyflix.player.LivePlayerScreen
import com.lorenzo.ettyflix.player.PlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stato del salvaschermo: ultimo tasto premuto e se è visibile. */
object Idle {
    var lastKey = android.os.SystemClock.uptimeMillis()
    var saver by mutableStateOf(false)
}

@Composable
fun AppRoot(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.bg)
        // ogni tasto conta come attività; se il salvaschermo è aperto lo chiude e basta
        .onPreviewKeyEvent {
            Idle.lastKey = android.os.SystemClock.uptimeMillis()
            if (Idle.saver) { if (it.type == KeyEventType.KeyUp) Idle.saver = false; true } else false
        }) {
        if (!Profiles.chosen) {
            ProfileScreen()
            return@Box
        }
        LaunchedEffect(Store.reloadToken) {
            runCatching { Store.bootstrap() }
            runCatching { LiveTV.loadIfNeeded() }
        }
        // Ogni minuto controlla se su Trakt è cambiato qualcosa (es. hai visto qualcosa sul Mac)
        LaunchedEffect(Profiles.currentID) {
            if (Trakt.isLoggedIn) runCatching { Trakt.quickSync() }   // completa solo se su Trakt è cambiato qualcosa
            runCatching { com.lorenzo.ettyflix.data.CompletedModel.refresh() }   // spunte delle serie finite
            while (true) {
                delay(15_000)   // ogni 15 secondi (una richiesta leggera)
                if (!Nav.top.fullScreen) runCatching { Trakt.quickSync() }
            }
        }

        val holder = rememberSaveableStateHolder()
        val top = Nav.top
        // Salvaschermo interno: dopo qualche minuto senza tasti (non mentre guardi qualcosa)
        LaunchedEffect(Unit) {
            while (true) {
                delay(10_000)
                val p = com.lorenzo.ettyflix.EttyFlixApp.prefs
                val on = p.getBoolean("screensaver_on", true)
                val minutes = p.getInt("screensaver_minutes", 5)
                if (on && !Idle.saver && !Nav.top.fullScreen && Nav.menu == null &&
                    android.os.SystemClock.uptimeMillis() - Idle.lastKey > minutes * 60_000L &&
                    (Store.watchlist.isNotEmpty() || WatchProgress.history.isNotEmpty())) Idle.saver = true
            }
        }
        BackHandler {
            when {
                Nav.menu != null -> Nav.menu = null
                Nav.pop() -> {}
                Nav.tab != Screen.Home -> Nav.selectTab(Screen.Home)
                else -> onExit()
            }
        }
        // Quando si apre una pagina nuova (o si chiude un menu) il cursore va sulla pagina,
        // non sul menu laterale: se la pagina non lo prende da sola, lo porto io sul primo elemento.
        val contentFocus = remember { FocusRequester() }
        var contentHasFocus by remember { mutableStateOf(false) }
        LaunchedEffect(top, Nav.menu == null) {
            if (Nav.menu != null) return@LaunchedEffect
            repeat(12) {
                delay(90)
                if (contentHasFocus) return@LaunchedEffect
                runCatching { contentFocus.requestFocus() }
            }
        }
        val contentModifier = Modifier.fillMaxSize().focusRequester(contentFocus).onFocusChanged { contentHasFocus = it.hasFocus }.focusGroup()
        if (top.fullScreen) {
            Box(contentModifier) {
                holder.SaveableStateProvider(Nav.stack.size.toString() + top.toString().take(80)) { ScreenContent(top) }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Sidebar()
                Box(contentModifier) {
                    holder.SaveableStateProvider(Nav.stack.size.toString() + top.toString().take(80)) { ScreenContent(top) }
                }
            }
        }
        Nav.menu?.let { MediaMenu(it) }
        if (Idle.saver) ScreensaverScreen()
    }
}

@Composable
private fun ScreenContent(s: Screen) {
    when (s) {
        Screen.Home -> HomeScreen()
        Screen.Search -> SearchScreen()
        Screen.Library -> LibraryScreen()
        Screen.Live -> LiveScreen()
        Screen.Settings -> SettingsScreen()
        Screen.Calendar -> CalendarScreen()
        Screen.Stats -> StatsScreen()
        is Screen.Detail -> DetailScreen(s.item)
        is Screen.TilePage -> TilePageScreen(s.tile)
        is Screen.Grid -> GridScreen(s.title, s.sources, s.items)
        is Screen.Person -> PersonScreen(s.id, s.name)
        is Screen.Recommended -> RecommendedScreen(s.type)
        is Screen.Sources -> SourcesScreen(s.req)
        is Screen.Player -> PlayerScreen(s.req, s.sources, s.selected)
        is Screen.LivePlayer -> LivePlayerScreen(s.channel)
    }
}

// MARK: Menu laterale

@Composable
private fun Sidebar() {
    var expanded by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    // Quando si apre una pagina il cursore può passare un attimo dal menu laterale prima di arrivare
    // sulla pagina: in quel momento non lo apro, così non "lampeggia".
    val navAt by androidx.compose.runtime.rememberUpdatedState(
        remember(Nav.stack.size, Nav.top, Nav.menu == null) { android.os.SystemClock.uptimeMillis() })
    LaunchedEffect(hasFocus) {
        if (hasFocus) {
            val wait = 700 - (android.os.SystemClock.uptimeMillis() - navAt)
            if (wait > 0) delay(wait)
        }
        expanded = hasFocus
    }
    val p = Profiles.current
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) 210.dp else 64.dp)
            .animateContentSize()
            .background(
                if (expanded) Brush.horizontalGradient(listOf(Color(0xF0101318), Color(0xD9101318)))
                else Brush.horizontalGradient(listOf(Color(0xCC0B0D11), Color(0x990B0D11)))
            )
            .onFocusChanged { hasFocus = it.hasFocus }
            .padding(vertical = 22.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SideItem(null, p.name, expanded, avatar = p.color, initial = p.name.take(1)) {
            Profiles.chosen = false
        }
        Spacer(Modifier.height(10.dp))
        SideItem(Icons.Filled.Search, "Cerca", expanded, selected = Nav.tab == Screen.Search) { Nav.selectTab(Screen.Search) }
        SideItem(Icons.Filled.Home, "Home", expanded, selected = Nav.tab == Screen.Home) { Nav.selectTab(Screen.Home) }
        SideItem(Icons.Filled.VideoLibrary, "Libreria", expanded, selected = Nav.tab == Screen.Library) { Nav.selectTab(Screen.Library) }
        SideItem(Icons.Filled.CalendarMonth, "Calendario", expanded, selected = Nav.tab == Screen.Calendar) { Nav.selectTab(Screen.Calendar) }
        SideItem(Icons.Filled.BarChart, "Statistiche", expanded, selected = Nav.tab == Screen.Stats) { Nav.selectTab(Screen.Stats) }
        SideItem(Icons.Filled.LiveTv, "TV in diretta", expanded, selected = Nav.tab == Screen.Live) { Nav.selectTab(Screen.Live) }
        SideItem(Icons.Filled.Settings, "Impostazioni", expanded, selected = Nav.tab == Screen.Settings) { Nav.selectTab(Screen.Settings) }
        Spacer(Modifier.weight(1f))
        if (expanded) Clock(Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SideItem(icon: ImageVector?, label: String, expanded: Boolean, selected: Boolean = false,
                     avatar: Int? = null, initial: String = "", onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else if (selected) Color(0x26FFFFFF) else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (avatar != null) {
            val (a, b) = Profiles.palette[avatar.coerceIn(0, Profiles.palette.lastIndex)]
            Box(Modifier.size(26.dp).background(Brush.linearGradient(listOf(Color(a), Color(b))), CircleShape)
                .border(1.dp, Color(0x55FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Text(initial, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else if (icon != null) {
            Icon(icon, null, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            Text(label, color = if (focused) Color.Black else Color.White, fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(start = 14.dp), maxLines = 1)
        }
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier, size: Int = 15) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    Column(modifier) {
        Text(SimpleDateFormat("HH:mm", Locale.ITALIAN).format(now), color = Color.White, fontSize = (size + 5).sp, fontWeight = FontWeight.SemiBold)
        Text(SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(now).replaceFirstChar { it.uppercase() },
            color = C.dim, fontSize = size.sp)
    }
}

// MARK: Menu di un elemento (OK tenuto premuto)

@Composable
private fun MediaMenu(req: MenuReq) {
    val first = remember { FocusRequester() }
    val item = req.item
    fun close() { Nav.menu = null }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000))
        // Subito dopo l'apertura ignoro OK: con il telecomando della TV arrivano ancora i clic
        // del tasto tenuto premuto e sceglierebbero da soli la prima voce.
        .onPreviewKeyEvent {
            val n = it.nativeKeyEvent
            val ok = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || n.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            ok && n.eventTime - openedAt < 800
        }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(380.dp).background(C.panel, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(req.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            var firstUsed = false
            fun m(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            req.extra.forEach { (label, action) -> PillButton(label, modifier = m()) { close(); action() } }
            if (item != null) {
                if (req.showOpen) PillButton("Apri", modifier = m()) { close(); Nav.push(Screen.Detail(item)) }
                PillButton(if (Store.inWatchlist(item)) "Togli dalla watchlist" else "Aggiungi alla watchlist", modifier = m()) {
                    Store.toggleWatchlist(item); close()
                }
                if (item.mediaType == "tv") {
                    PillButton("Segna serie come vista", modifier = m()) {
                        close(); com.lorenzo.ettyflix.data.Trakt.scope.launch { WatchProgress.setSeries(item, true) }
                    }
                    if (WatchProgress.entries.values.any { it.tmdbID == item.tmdbID }) {
                        PillButton("Segna serie come non vista", modifier = m()) {
                            close(); com.lorenzo.ettyflix.data.Trakt.scope.launch { WatchProgress.setSeries(item, false) }
                        }
                    }
                }
                if (item.mediaType == "movie") {
                    val watched = WatchProgress.isWatched(item.tmdbID, "movie")
                    PillButton(if (watched) "Segna come non visto" else "Segna come visto", modifier = m()) {
                        WatchProgress.setWatched(item, null, null, !watched); close()
                    }
                }
            }
            PillButton("Chiudi", modifier = m()) { close() }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}
