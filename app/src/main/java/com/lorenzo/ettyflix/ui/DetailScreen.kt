package com.lorenzo.ettyflix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.CastMember
import com.lorenzo.ettyflix.data.Episode
import com.lorenzo.ettyflix.data.Jellyfin
import com.lorenzo.ettyflix.data.MediaDetail
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.PlayRequest
import com.lorenzo.ettyflix.data.QualityBadges
import com.lorenzo.ettyflix.data.Ratings
import com.lorenzo.ettyflix.data.SourceCache
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.TMDB
import com.lorenzo.ettyflix.data.TMDBImage
import com.lorenzo.ettyflix.data.WatchProgress
import com.lorenzo.ettyflix.data.shortDate
import com.lorenzo.ettyflix.data.todayString
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Episodio da cui ripartire: quello a metà, oppure il successivo all'ultimo visto. */
private data class Target(val season: Int, val episode: Int, val startAt: Double, val label: String)

private suspend fun seriesTarget(item: MediaItem, seasons: List<Int>): Target {
    val last = WatchProgress.latest(item.tmdbID)
    if (last?.season != null && last.episode != null) {
        val t = com.lorenzo.ettyflix.data.resolveContinue(last)
        if (t?.season != null && t.episode != null) {
            return Target(t.season, t.episode, t.startAt,
                (if (t.isNext || t.startAt < 60) "Guarda" else "Riprendi") + " S${t.season}, E${t.episode}")
        }
    }
    val first = seasons.firstOrNull { it > 0 } ?: 1
    return Target(first, 1, 0.0, "Guarda S$first, E1")
}

@Composable
fun DetailScreen(start: MediaItem) {
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var season by remember { mutableIntStateOf(-1) }
    var episodes by remember { mutableStateOf(listOf<Episode>()) }
    var imdb by remember { mutableStateOf<Double?>(null) }
    var badges by remember { mutableStateOf<QualityBadges?>(null) }
    var searching by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf(listOf<MediaItem>()) }
    var showRatings by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val item = detail?.item ?: start
    val isTV = item.mediaType == "tv"
    @Suppress("UNUSED_VARIABLE") val v = WatchProgress.version  // ricompone quando cambiano i progressi

    LaunchedEffect(start.id) {
        detail = TMDB.detail(start.mediaType, start.tmdbID)
        detail?.collectionID?.let { collection = TMDB.collection(it).filter { m -> m.tmdbID != start.tmdbID } }
    }
    LaunchedEffect(start.id) { imdb = Ratings.imdbRating(start) }

    val seasonNumbers = detail?.seasons?.map { it.number } ?: emptyList()
    var target by remember { mutableStateOf<Target?>(null) }
    LaunchedEffect(detail?.item?.id, WatchProgress.version) {
        if (!isTV || detail == null) return@LaunchedEffect
        val t = seriesTarget(item, seasonNumbers)
        target = t
        if (season < 0) season = t.season
    }
    LaunchedEffect(start.id, target != null) { delay(250); runCatching { playFocus.requestFocus() } }
    LaunchedEffect(season) { if (isTV && season >= 0) episodes = TMDB.episodes(start.tmdbID, season) }

    // Bollini: cerco le fonti reali in sottofondo (film, oppure l'episodio da guardare)
    LaunchedEffect(start.id, target?.season, target?.episode) {
        if (Store.server == null || (isTV && target == null)) return@LaunchedEffect
        val s = target?.season; val e = target?.episode
        SourceCache.get(start, s, e)?.let { badges = QualityBadges.from(it); return@LaunchedEffect }
        searching = true
        val list = withTimeoutOrNull(30_000) {
            Jellyfin.allSources(start, s, e) { partial -> badges = QualityBadges.from(partial, complete = false) }
        }
        searching = false
        if (list != null) { SourceCache.put(start, s, e, list); badges = QualityBadges.from(list) }
        else if (badges == null) badges = QualityBadges(noSources = true)
    }

    fun playMovie() {
        val pos = WatchProgress.resumePosition(item.tmdbID, "movie") ?: 0.0
        Nav.play(PlayRequest(item, logoPath = detail?.logoPath, runtime = detail?.runtime, startAt = pos))
    }
    fun playEpisode(s: Int, ep: Int, title: String, still: String?, runtime: Int?, at: Double) {
        Nav.play(PlayRequest(item, s, ep, title, still, detail?.logoPath, runtime ?: detail?.runtime, at))
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Backdrop(TMDBImage.url(item.backdropPath, "w1280"), Modifier.fillMaxWidth().height(470.dp))
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
            item(key = "top") {
                Column(Modifier.padding(start = C.pad, top = 80.dp, end = C.pad).widthIn(max = 600.dp)) {
                    TitleArt(item, 360, 120, 40, detail?.logoPath)
                    Spacer(Modifier.height(12.dp))
                    val meta = mutableListOf<String>()
                    if (item.year.isNotEmpty()) meta += item.year
                    detail?.runtime?.takeIf { it > 0 }?.let { r -> meta += if (r >= 60) "${r / 60} h ${r % 60} min" else "$r min" }
                    detail?.certification?.takeIf { it.isNotEmpty() }?.let { meta += it }
                    detail?.genres?.take(3)?.let { meta += it }
                    Text(meta.joinToString(" · "), color = C.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val value = imdb ?: item.rating.takeIf { it > 0 }
                        if (value != null) {
                            Text(if (imdb != null) "IMDb" else "TMDB", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.background(if (imdb != null) C.yellow else Color.White, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp))
                            Text(String.format(Locale.ITALIAN, "%.1f", value), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        badges?.let { if (!it.isEmpty) BadgesRow(it) }
                        if (searching && (badges == null || badges!!.isEmpty)) Text("Cerco le fonti…", color = C.faint, fontSize = 12.sp)
                    }
                    val overview = detail?.overview?.ifEmpty { null } ?: item.overview
                    if (overview.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(overview, color = C.dim, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val t = target
                        if (isTV && t != null) {
                            val ep = episodes.firstOrNull { it.season == t.season && it.number == t.episode }
                            PillButton(t.label, Icons.Filled.PlayArrow, Modifier.focusRequester(playFocus), primary = true) {
                                playEpisode(t.season, t.episode, ep?.name ?: "", ep?.stillPath, ep?.runtime, t.startAt)
                            }
                        } else if (!isTV) {
                            val pos = WatchProgress.resumePosition(item.tmdbID, "movie")
                            PillButton(if (pos != null) "Riprendi" else "Riproduci", Icons.Filled.PlayArrow, Modifier.focusRequester(playFocus), primary = true) { playMovie() }
                        }
                        PillButton(icon = if (Store.inWatchlist(item)) Icons.Filled.Check else Icons.Filled.Add) { Store.toggleWatchlist(item) }
                        detail?.trailerKey?.let { key ->
                            PillButton("Trailer", Icons.Filled.Movie) { openTrailer(ctx, key) }
                        }
                        if (!isTV) {
                            val watched = WatchProgress.isWatched(item.tmdbID, "movie")
                            PillButton(icon = if (watched) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) {
                                WatchProgress.setWatched(item, null, null, !watched)
                            }
                        } else {
                            PillButton("Voti per stagione", Icons.Filled.Star) { showRatings = true }
                        }
                    }
                }
            }

            if (isTV && detail != null) {
                item(key = "seasons") {
                    LazyRow(contentPadding = PaddingValues(horizontal = C.pad, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 24.dp)) {
                        items(detail!!.seasons.filter { it.episodeCount > 0 }, key = { it.id }) { s ->
                            SeasonPill(s.name, s.number == season) { season = s.number }
                        }
                    }
                }
                item(key = "episodes") {
                    val today = todayString()
                    LazyRow(contentPadding = PaddingValues(horizontal = C.pad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(episodes, key = { it.id }) { ep ->
                            EpisodeCard(item, ep, ep.airDate.isNotEmpty() && ep.airDate > today) {
                                val pos = WatchProgress.resumePosition(item.tmdbID, "tv", ep.season, ep.number) ?: 0.0
                                playEpisode(ep.season, ep.number, ep.name, ep.stillPath, ep.runtime, pos)
                            }
                        }
                    }
                }
                item(key = "season-actions") {
                    Row(Modifier.padding(start = C.pad, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val allWatched = episodes.isNotEmpty() && episodes.all { WatchProgress.isWatched(item.tmdbID, "tv", it.season, it.number) }
                        PillButton(if (allWatched) "Segna stagione come non vista" else "Segna stagione come vista", Icons.Filled.DoneAll) {
                            WatchProgress.setSeason(item, episodes, !allWatched)
                        }
                    }
                }
            }

            detail?.let { d ->
                val people = d.directors + d.cast
                if (people.isNotEmpty()) item(key = "cast") {
                    MediaRow("Cast e troupe") { items(people, key = { "p" + it.id + it.character }) { CastCard(it) } }
                }
                if (collection.isNotEmpty()) item(key = "coll") {
                    MediaRow(d.collectionName.ifEmpty { "Saga" }) { items(collection, key = { it.id }) { PosterCard(it, 120.dp) } }
                }
                if (d.recommendations.isNotEmpty()) item(key = "rec") {
                    MediaRow("Ti potrebbe piacere") { items(d.recommendations.take(20), key = { it.id }) { PosterCard(it, 120.dp) } }
                }
                if (d.similar.isNotEmpty()) item(key = "sim") {
                    MediaRow("Titoli simili") { items(d.similar.take(20), key = { it.id }) { PosterCard(it, 120.dp) } }
                }
                if (d.info.isNotEmpty()) item(key = "info") { InfoGrid(d) }
            }
        }
        if (showRatings) SeasonRatingsDialog(item) { showRatings = false }
    }
}

@Composable
private fun SeasonPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Text(text, color = if (focused) Color.Black else Color.White, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.scale(if (focused) 1.06f else 1f).clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else if (selected) Color(0x40FFFFFF) else Color(0x1AFFFFFF))
            .border(1.dp, if (selected && !focused) Color(0xB3FFFFFF) else Color.Transparent, RoundedCornerShape(50))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp))
}

@Composable
private fun EpisodeCard(show: MediaItem, ep: Episode, future: Boolean, onClick: () -> Unit) {
    val entry = WatchProgress.entry(show.tmdbID, "tv", ep.season, ep.number)
    val watched = entry?.finished == true
    Column(Modifier.width(250.dp)) {
        FocusBox(
            onClick = onClick,
            onMenu = {
                Nav.menu = MenuReq("E${ep.number} · ${ep.name}", null, listOf(
                    "Riproduci" to onClick,
                    (if (watched) "Segna come non visto" else "Segna come visto") to { WatchProgress.setWatched(show, ep.season, ep, !watched) }
                ))
            },
            modifier = Modifier.width(250.dp).height(141.dp)
        ) {
            Box(Modifier.fillMaxSize().background(C.panel)) {
                // gli episodi non ancora visti sono sfocati (niente anticipazioni)
                Img(TMDBImage.url(ep.stillPath ?: show.backdropPath, "w780"),
                    Modifier.fillMaxSize().let { if (!watched) it.blur(10.dp) else it }.alpha(if (future) 0.4f else 1f))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC000000))))
                if (watched) CheckBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
                Text("E${ep.number}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                if (entry != null && !watched && entry.fraction > 0.02) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(entry.fraction.toFloat()).height(3.dp).background(Color.White))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(ep.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(ep.runtime?.let { "$it min" }, ep.airDate.ifEmpty { null }?.let { shortDate(it) }).joinToString(" · ")
        Text(sub, color = C.faint, fontSize = 11.sp, maxLines = 1)
        if (ep.overview.isNotEmpty()) Text(ep.overview, color = C.faint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CastCard(c: CastMember) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Column(Modifier.width(96.dp).clickable(interactionSource = src, indication = null) { Nav.push(Screen.Person(c.id, c.name)) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.scale(if (focused) 1.1f else 1f).size(84.dp).clip(CircleShape).background(C.panel)
            .border(if (focused) 3.dp else 0.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (c.profilePath != null) Img(TMDBImage.url(c.profilePath, "w185"), Modifier.fillMaxSize())
            else Text(c.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), color = Color.White, fontSize = 22.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(c.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(c.character, color = C.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoGrid(d: MediaDetail) {
    Column(Modifier.padding(start = C.pad, end = C.pad, top = 24.dp)) {
        Text("Informazioni", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        d.info.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { r ->
                    Column(Modifier.weight(1f)) {
                        Text(r.label, color = C.faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(r.value, color = C.dim, fontSize = 13.sp, maxLines = 3)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SeasonRatingsDialog(item: MediaItem, onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<Pair<Int, Double>>?>(null) }
    var source by remember { mutableStateOf("") }
    val close = remember { FocusRequester() }
    LaunchedEffect(item.id) { val (r, s) = Ratings.seasonRatings(item); rows = r; source = s }
    LaunchedEffect(Unit) { delay(100); runCatching { close.requestFocus() } }
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).background(C.panel, RoundedCornerShape(20.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)).padding(24.dp)) {
            Text("Voti per stagione", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (source.isNotEmpty()) Text("Fonte: $source", color = C.faint, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            val r = rows
            if (r == null) Text("Carico i voti…", color = C.dim, fontSize = 15.sp)
            else if (r.isEmpty()) Text("Nessun voto disponibile.", color = C.dim, fontSize = 15.sp)
            else r.forEach { (s, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stagione $s", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(110.dp))
                    Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(Color(0x26FFFFFF))) {
                        Box(Modifier.fillMaxWidth((v / 10).toFloat().coerceIn(0f, 1f)).height(8.dp).background(C.yellow))
                    }
                    Text(String.format(Locale.ITALIAN, "  %.1f", v), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            PillButton("Chiudi", modifier = Modifier.focusRequester(close)) { onClose() }
        }
    }
}


/** Apre il trailer nell'app YouTube (come su Apple TV); se non c'è, nel browser. */
fun openTrailer(ctx: android.content.Context, key: String) {
    val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$key"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$key"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(app) } catch (e: Exception) {
        try { ctx.startActivity(web) } catch (_: Exception) {}
    }
}
