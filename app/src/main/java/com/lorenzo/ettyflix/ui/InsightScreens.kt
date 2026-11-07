package com.lorenzo.ettyflix.ui

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
            Row(Modifier.fillMaxWidth().padding(horizontal = C.pad), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Panel("Ore negli ultimi 12 mesi", Modifier.weight(1.2f)) {
                    val maxV = (s.monthly.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1.0)
                    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom) {
                        s.monthly.forEach { m ->
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom) {
                                Box(Modifier.fillMaxWidth().height((120 * (m.value / maxV)).coerceAtLeast(2.0).dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Color(0xD9FFFFFF)))
                                Text(m.label, color = C.dim, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                Panel("Generi preferiti", Modifier.weight(1f)) {
                    if (s.genres.isEmpty()) Text("Guarda qualcosa per vedere i tuoi generi.", color = C.dim, fontSize = 13.sp)
                    else {
                        val maxV = s.genres.maxOf { it.value }.coerceAtLeast(0.01)
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            s.genres.forEach { g ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, modifier = Modifier.width(120.dp))
                                    Box(Modifier.weight(1f)) {
                                        Box(Modifier.fillMaxWidth((g.value / maxV).toFloat().coerceIn(0.03f, 1f)).height(9.dp)
                                            .clip(RoundedCornerShape(50)).background(Color(0xCCFFFFFF)))
                                    }
                                }
                            }
                        }
                    }
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
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
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
            androidx.compose.animation.Crossfade(m, androidx.compose.animation.core.tween(1600), label = "c") { item ->
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
