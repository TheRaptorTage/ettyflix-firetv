package com.lorenzo.ettyflix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import com.lorenzo.ettyflix.data.StatCount
import com.lorenzo.ettyflix.data.statExact
import com.lorenzo.ettyflix.data.statShort
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.CalendarModel
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.Profiles
import com.lorenzo.ettyflix.data.StatTop
import com.lorenzo.ettyflix.data.StatsModel
import com.lorenzo.ettyflix.data.StatsPeriod
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.TMDBImage
import com.lorenzo.ettyflix.data.UpcomingShow
import com.lorenzo.ettyflix.data.WatchProgress
import kotlinx.coroutines.delay

// MARK: Calendario

@Composable
fun CalendarScreen() {
    val v = WatchProgress.version
    LaunchedEffect(Profiles.currentID, Store.watchlist.size, v) { runCatching { CalendarModel.load() } }
    val groups = CalendarModel.groups()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.padding(start = C.pad, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendario", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (CalendarModel.loading) {
                    Spacer(Modifier.width(14.dp))
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (!CalendarModel.loading && groups.isEmpty()) item {
            Text("Nessuna uscita in programma per le serie della watchlist e di quelle che hai guardato.",
                color = C.dim, fontSize = 15.sp, modifier = Modifier.padding(start = C.pad, top = 10.dp))
        }
        groups.forEach { (title, list) ->
            item(key = title) {
                MediaRow(title) { items(list, key = { it.show.tmdbID }) { u -> CalendarCard(u) } }
            }
        }
    }
}

@Composable
private fun CalendarCard(u: UpcomingShow) {
    val e = u.next
    Column(Modifier.width(300.dp)) {
        FocusBox(onClick = { Nav.push(Screen.Detail(u.show)) }, onMenu = { Nav.menu = MenuReq(u.show) },
            modifier = Modifier.width(300.dp).height(169.dp)) {
            Box(Modifier.fillMaxSize().background(C.panel)) {
                Img(TMDBImage.url(e?.stillPath ?: u.show.backdropPath, "w780"), Modifier.fillMaxSize())
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (e == null) Tag("In arrivo") else {
                        if (e.isPremiere) Tag(if (e.season == 1) "Nuova serie" else "Nuova stagione")
                        if (e.isFinale) Tag("Finale di stagione")
                        if (WatchProgress.isWatched(e.show.tmdbID, "tv", e.season, e.episode)) Tag("Visto")
                    }
                }
            }
        }
        Text(u.show.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(if (e != null) e.code + (if (e.name.isNotEmpty()) " · " + e.name else "") else "Data da confermare",
            color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tag(t: String) {
    Text(t, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000))
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp))
}

// MARK: Statistiche

@Composable
fun StatsScreen() {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.MONTH) }
    val v = WatchProgress.version
    LaunchedEffect(Profiles.currentID, v) { runCatching { StatsModel.loadMeta() } }
    @Suppress("UNUSED_VARIABLE") val mv = StatsModel.version
    val s = StatsModel.summary(period)
    var selMonth by remember { mutableStateOf<Int?>(null) }
    var selDay by remember { mutableStateOf<Int?>(null) }
    var showGenres by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.padding(horizontal = C.pad), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Statistiche", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Profilo di ${Profiles.current.name}", color = C.dim, fontSize = 14.sp)
                }
                if (StatsModel.loading) {
                    Spacer(Modifier.width(14.dp))
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsPeriod.values().forEach { p -> Chip(p.title, period == p) { period = p } }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = C.pad, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(s.hoursText, s.hoursUnit, Icons.Filled.Schedule, Color(0xFFFF9F0A), Modifier.weight(1f))
                StatTile("${s.movies}", "film", Icons.Filled.Movie, Color(0xFFFF375F), Modifier.weight(1f))
                StatTile("${s.episodes}", "episodi", Icons.Filled.Tv, Color(0xFF0A84FF), Modifier.weight(1f))
                StatTile("${s.series}", "serie", Icons.Filled.ViewCarousel, Color(0xFFBF5AF2), Modifier.weight(1f))
                StatTile("${s.streak}", "giorni di fila", Icons.Filled.LocalFireDepartment, Color(0xFFFF453A), Modifier.weight(1f))
            }
        }
        item {
            Panel("Ore negli ultimi 12 mesi", Modifier.fillMaxWidth().padding(horizontal = C.pad)) {
                SelLine(s.monthly, selMonth, "Scorri sui mesi per vedere le ore")
                BarChart(s.monthly, 170) { selMonth = it }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = C.pad, vertical = 14.dp).height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val gSrc = remember { MutableInteractionSource() }
                val gFocused by gSrc.collectIsFocusedAsState()
                Panel("Generi preferiti", Modifier.weight(1f).fillMaxHeight()
                    .scale(if (gFocused) 1.02f else 1f)
                    .border(2.dp, if (gFocused) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = gSrc, indication = null, enabled = s.allGenres.isNotEmpty()) { showGenres = true }) {
                    if (s.allGenres.isEmpty()) Text("Guarda qualcosa per vedere i tuoi generi.", color = C.dim, fontSize = 13.sp)
                    else {
                        GenreBars(s.allGenres.take(8))
                        Spacer(Modifier.weight(1f))
                        Text(if (s.allGenres.size > 8) "Premi per vedere tutti i ${s.allGenres.size} generi" else "Premi per aprire",
                            color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Panel("Quando guardi", Modifier.weight(1f).fillMaxHeight()) {
                    SelLine(s.weekdays, selDay, "Scorri sui giorni per vedere le ore")
                    BarChart(s.weekdays, 170) { selDay = it }
                }
            }
        }
        if (s.topSeries.isNotEmpty()) item {
            MediaRow("Serie più guardate") { items(s.topSeries, key = { it.id }) { t -> StatPoster(t, "tv", if (t.count == 1) "1 episodio" else "${t.count} episodi") } }
        }
        if (s.recentMovies.isNotEmpty()) item {
            MediaRow("Film visti") { items(s.recentMovies, key = { it.id }) { t -> StatPoster(t, "movie", "") } }
        }
    }
    if (showGenres) AllGenresOverlay(s.allGenres, period.title) { showGenres = false }
    }
}

/** riga sopra il grafico: il mese (o il giorno) selezionato con le ore esatte */
@Composable
private fun SelLine(data: List<StatCount>, sel: Int?, hint: String) {
    val c = sel?.let { data.getOrNull(it) }
    Text(
        if (c == null) hint else "${c.detail.ifEmpty { c.label }}: ${statExact(c.value)}",
        color = if (c == null) C.faint else Color.White, fontSize = 13.sp,
        fontWeight = if (c == null) FontWeight.Normal else FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

/** grafico a barre che si scorre col telecomando: la barra selezionata si illumina e mostra le ore */
@Composable
private fun BarChart(data: List<StatCount>, height: Int, onSelect: (Int?) -> Unit) {
    val maxV = (data.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(0.5)
    var focusedIdx by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().height(height.dp).onFocusChanged { if (!it.hasFocus) { focusedIdx = null; onSelect(null) } },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        data.forEachIndexed { i, c ->
            val src = remember { MutableInteractionSource() }
            val focused by src.collectIsFocusedAsState()
            LaunchedEffect(focused) { if (focused) { focusedIdx = i; onSelect(i) } }
            val dim = focusedIdx != null && !focused
            Column(Modifier.weight(1f).fillMaxHeight()
                .clickable(interactionSource = src, indication = null) { },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (focused) Text(statShort(c.value), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(50)).background(Color.White)
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                Box(Modifier.fillMaxWidth().height(((height - 50) * (c.value / maxV)).coerceAtLeast(2.0).dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (focused) Color.White else if (dim) Color(0x4DFFFFFF) else Color(0xD9FFFFFF)))
                Text(c.label, color = if (focused) Color.White else C.dim, fontSize = 10.sp, maxLines = 1,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** barre dei generi con le ore a destra */
@Composable
private fun GenreBars(list: List<StatCount>, numbered: Boolean = false) {
    val maxV = (list.maxOfOrNull { it.value } ?: 0.01).coerceAtLeast(0.01)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        list.forEachIndexed { i, g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (numbered) Text("${i + 1}", color = C.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(26.dp))
                Text(g.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
                Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth((g.value / maxV).toFloat().coerceIn(0.03f, 1f)).height(9.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xCCFFFFFF)))
                }
                Text(statShort(g.value), color = C.dim, fontSize = 12.sp, maxLines = 1,
                    textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
            }
        }
    }
}

/** tutti i generi del periodo, con le ore */
@Composable
private fun AllGenresOverlay(genres: List<StatCount>, periodTitle: String, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    BackHandler { onClose() }
    val maxV = (genres.maxOfOrNull { it.value } ?: 0.01).coerceAtLeast(0.01)
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).fillMaxHeight(0.86f).background(C.panel, RoundedCornerShape(22.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp)).padding(24.dp)) {
            Text("Tutti i generi", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(periodTitle, color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(genres) { i, g ->
                    val src = remember { MutableInteractionSource() }
                    val focused by src.collectIsFocusedAsState()
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (focused) Color(0x33FFFFFF) else Color.Transparent)
                        .let { if (i == 0) it.focusRequester(first) else it }
                        .clickable(interactionSource = src, indication = null) { }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = C.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                        Text(g.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                        Box(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth((g.value / maxV).toFloat().coerceIn(0.03f, 1f)).height(10.dp)
                                .clip(RoundedCornerShape(50)).background(Color(0xCCFFFFFF)))
                        }
                        Text(statShort(g.value), color = C.dim, fontSize = 13.sp, textAlign = TextAlign.End,
                            modifier = Modifier.width(90.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp))
        .padding(16.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text(label, color = C.dim, fontSize = 13.sp)
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
        .padding(18.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable
private fun StatPoster(t: StatTop, type: String, detail: String) {
    val m = MediaItem(t.id, type, t.title, posterPath = t.posterPath)
    Column(Modifier.width(120.dp)) {
        PosterCard(m, width = 120.dp)
        Text(t.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (detail.isNotEmpty()) Text(detail, color = C.dim, fontSize = 11.sp)
    }
}

// MARK: Salvaschermo e caricamento

/** Salvaschermo: le immagini della watchlist e dei titoli guardati scorrono lentamente, con l'orologio. */
@Composable
fun ScreensaverScreen() {
    val items = remember0 {
        val seen = HashSet<String>()
        (Store.watchlist + WatchProgress.history.take(30).map { it.media })
            .filter { it.backdropPath != null && seen.add(it.id) }.shuffled()
    }
    var index by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(items) { while (items.isNotEmpty()) { delay(10_000); index++ } }
    val m = items.getOrNull(if (items.isEmpty()) 0 else index % items.size)
    val t = rememberInfiniteTransition(label = "z")
    val zoom by t.animateFloat(1f, 1.12f, infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Reverse), label = "z")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (m != null) {
            androidx.compose.animation.Crossfade(targetState = m, animationSpec = tween(1600), label = "c") { item ->
                Img(TMDBImage.url(item.backdropPath, "original"), Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(48.dp)) {
                Text(m.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                Text(listOfNotNull(if (m.mediaType == "tv") "Serie TV" else "Film", m.year.takeIf { it.isNotEmpty() },
                    if (m.rating > 0) "★ " + String.format(java.util.Locale.US, "%.1f", m.rating) else null).joinToString(" · "),
                    color = C.dim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Clock(Modifier.align(Alignment.TopEnd).padding(36.dp))
    }
}

@Composable
private fun <T> remember0(calc: () -> T): T = androidx.compose.runtime.remember { calc() }

/** Sfondo della schermata di caricamento: immagine del titolo scura con un lento zoom. */
@Composable
fun LoadingBackdrop(url: String?) {
    val t = rememberInfiniteTransition(label = "lb")
    val zoom by t.animateFloat(1.05f, 1.15f, infiniteRepeatable(tween(14_000), RepeatMode.Reverse), label = "lb")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url != null) Img(url, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; alpha = 0.45f })
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC000000)), radius = 1400f)))
    }
}

/** Trailer muto in sottofondo (link diretto da iTunes), al massimo 1080p, in ciclo; compare in dissolvenza. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun BackgroundVideo(url: String, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ready by androidx.compose.runtime.remember(url) { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (ready) 1f else 0f, tween(1400), label = "bv")
    val player = androidx.compose.runtime.remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() { ready = true }
            })
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    // si vede qualche secondo dopo l'apertura della pagina
    var show by androidx.compose.runtime.remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) { delay(2000); show = true }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            androidx.media3.ui.PlayerView(c).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        modifier = modifier.graphicsLayer { this.alpha = if (show) alpha else 0f }
    )
}
