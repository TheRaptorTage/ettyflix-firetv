package com.lorenzo.ettyflix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.AI
import com.lorenzo.ettyflix.data.ContinueTarget
import com.lorenzo.ettyflix.data.LiveTV
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.PlayRequest
import com.lorenzo.ettyflix.data.Shelf
import com.lorenzo.ettyflix.data.SourceDef
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.TMDB
import com.lorenzo.ettyflix.data.TMDBImage
import com.lorenzo.ettyflix.data.WatchProgress
import com.lorenzo.ettyflix.data.arr
import com.lorenzo.ettyflix.data.resolveContinue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Loghi dei titoli (immagine con il nome), caricati una volta. */
object LogoStore {
    val logos = mutableStateMapOf<String, String?>()
    suspend fun load(m: MediaItem) {
        if (logos.containsKey(m.id)) return
        val j = TMDB.get("${m.mediaType}/${m.tmdbID}/images", mapOf("include_image_language" to "it,en,null", "language" to "it"))
        val list = j?.arr("logos") ?: emptyList()
        fun pick(lang: String) = list.firstOrNull { (it.optString("iso_639_1", "")) == lang }?.optString("file_path")
        logos[m.id] = pick("it") ?: pick("en") ?: list.firstOrNull()?.optString("file_path")
    }
}

private val recommendedCover = mapOf(
    "movie" to "https://raw.githubusercontent.com/TheRaptorTage/copertine-it/main/recommended-movies-cover.png",
    "tv" to "https://raw.githubusercontent.com/TheRaptorTage/copertine-it/main/recommended-series-cover.png"
)

@Composable
fun HomeScreen() {
    var hero by remember { mutableStateOf(listOf<MediaItem>()) }
    var topTV by remember { mutableStateOf(listOf<MediaItem>()) }
    var topMovies by remember { mutableStateOf(listOf<MediaItem>()) }
    val listState = rememberLazyListState()

    // ogni 3 ore il banner in alto cambia titoli
    var heroSlot by remember { mutableStateOf(TMDB.heroSlot()) }
    LaunchedEffect(Unit) { while (true) { delay(300_000); TMDB.heroSlot().let { if (it != heroSlot) heroSlot = it } } }
    LaunchedEffect(Store.reloadToken, Store.hasKey, heroSlot) {
        if (!Store.hasKey) return@LaunchedEffect
        coroutineScope {
            val h = async { TMDB.heroWithNewSeasons() }
            val tv = async { TMDB.items(SourceDef.endpoint("Top serie", "trending/tv/week", "tv"), 1) }
            val mv = async { TMDB.items(SourceDef.endpoint("Top film", "trending/movie/week", "movie"), 1) }
            hero = h.await()
            topTV = tv.await().take(10)
            topMovies = mv.await().take(10)
        }
        runCatching { AI.refreshTyped("movie") }
        runCatching { AI.refreshTyped("tv") }
    }

    val shelves = Store.shelves.filter { it.enabled && (it.kind == "group" || it.kind == "liveTVFavorites") }.sortedBy { it.order }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth()) {
                if (Store.hasKey && hero.isNotEmpty()) HeroSection(hero)
                else Column(Modifier.padding(C.pad).padding(top = 40.dp)) { SetupHint() }
            }
        }
        item(key = "continue") { ContinueRow() }
        shelves.firstOrNull { it.kind == "liveTVFavorites" }?.let { fav -> item(key = "fav-ch") { FavoriteChannelsRow(fav.title) } }
        if (Store.hasKey) {
            item(key = "ai-movie") { AIRow("movie") }
            if (topTV.isNotEmpty()) item(key = "top-tv") { Top10Row("Top 10 serie della settimana", topTV) }
            item(key = "ai-tv") { AIRow("tv") }
            if (topMovies.isNotEmpty()) item(key = "top-mv") { Top10Row("Top 10 film della settimana", topMovies) }
        }
        items(shelves.filter { it.kind != "liveTVFavorites" }, key = { "sh-" + it.id }) { sh -> TileShelf(sh) }
    }
}

@Composable
fun SetupHint() {
    Column(Modifier.widthIn(max = 640.dp).background(Color(0x1AFFFFFF), RoundedCornerShape(16.dp)).padding(24.dp)) {
        Text("Benvenuto in EttyFlix", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Per iniziare invia la configurazione dal Mac: su EttyFlix per Mac apri Impostazioni › Condividi configurazione " +
            "e scrivi il codice che trovi qui in Impostazioni › Ricevi configurazione.", color = C.dim, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        PillButton("Apri Impostazioni", primary = true) { Nav.selectTab(Screen.Settings) }
    }
}

// MARK: Banner in alto

@Composable
private fun HeroSection(items: List<MediaItem>) {
    var index by remember { mutableIntStateOf(0) }
    var lastManual by remember { mutableStateOf(0L) }
    val item = items[index % items.size]
    val play = remember { FocusRequester() }
    LaunchedEffect(items) { items.forEach { LogoStore.load(it) } }
    LaunchedEffect(items.size) {
        while (true) {
            delay(10_000)
            if (items.size > 1 && System.currentTimeMillis() - lastManual > 12_000) index = (index + 1) % items.size
        }
    }
    LaunchedEffect(Unit) { delay(300); runCatching { play.requestFocus() } }

    Box(Modifier.fillMaxWidth().height(430.dp)) {
        AnimatedContent(item, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hero") { m ->
            Backdrop(TMDBImage.url(m.backdropPath, "w1280"), Modifier.fillMaxSize())
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = C.pad, bottom = 26.dp).widthIn(max = 520.dp)) {
            Text(TMDB.heroLabel(item), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0x80000000), RoundedCornerShape(50)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.height(10.dp))
            TitleArt(item, maxWidth = 340, maxHeight = 110, fontSize = 40)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parts = listOf(if (item.mediaType == "movie") "Film" else "Serie TV") +
                    item.genreIDs.mapNotNull { Store.genreNames[it] }.take(2) + listOfNotNull(item.year.ifEmpty { null })
                Text(parts.joinToString(" · "), color = C.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (item.rating > 0) {
                    Text("  ·  ", color = C.dim, fontSize = 14.sp)
                    Icon(Icons.Filled.Star, null, tint = C.dim, modifier = Modifier.size(14.dp))
                    Text(String.format(java.util.Locale.ITALIAN, " %.1f", item.rating), color = C.dim, fontSize = 14.sp)
                }
            }
            if (item.overview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(item.overview, color = C.dim, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Guarda ora", Icons.Filled.PlayArrow, Modifier.focusRequester(play), primary = true) { Nav.push(Screen.Detail(item)) }
                PillButton(icon = if (Store.inWatchlist(item)) Icons.Filled.Check else Icons.Filled.Add) { Store.toggleWatchlist(item) }
                PillButton(icon = Icons.Filled.Info) { Nav.push(Screen.Detail(item)) }
                if (items.size > 1) PillButton(icon = Icons.Filled.ChevronRight) {
                    lastManual = System.currentTimeMillis(); index = (index + 1) % items.size
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.indices.forEach { k ->
                Box(Modifier.height(6.dp).width(if (k == index % items.size) 16.dp else 6.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (k == index % items.size) 1f else 0.4f)))
            }
        }
    }
}

/** Logo del titolo (immagine con il nome) o, se manca, il titolo scritto. */
@Composable
fun TitleArt(item: MediaItem, maxWidth: Int, maxHeight: Int, fontSize: Int, logoPath: String? = null) {
    LaunchedEffect(item.id) { if (logoPath == null) LogoStore.load(item) }
    val logo = logoPath ?: LogoStore.logos[item.id]
    if (logo != null) {
        Img(TMDBImage.url(logo, "w500"), Modifier.widthIn(max = maxWidth.dp).heightIn(max = maxHeight.dp), ContentScale.Fit)
    } else {
        Text(item.title, color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Black, maxLines = 2,
            lineHeight = (fontSize * 1.05).sp, overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = Shadow(Color(0x99000000), blurRadius = 12f)))
    }
}

// MARK: Continua a guardare

@Composable
private fun ContinueRow() {
    var targets by remember { mutableStateOf(listOf<ContinueTarget>()) }
    LaunchedEffect(WatchProgress.version) {
        val list = WatchProgress.continueWatching.take(15)
        targets = coroutineScope { list.map { async { runCatching { resolveContinue(it) }.getOrNull() } }.awaitAll().filterNotNull() }
    }
    if (targets.isEmpty()) return
    MediaRow("Continua a guardare") {
        items(targets, key = { it.entry.id }) { t -> ContinueCard(t) }
    }
}

@Composable
fun ContinueCard(t: ContinueTarget) {
    val e = t.entry
    Column(Modifier.width(250.dp)) {
        FocusBox(
            onClick = {
                Nav.play(PlayRequest(e.media, t.season, t.episode, t.episodeTitle, t.stillPath, e.logoPath, null, t.startAt))
            },
            onMenu = {
                Nav.menu = MenuReq(e.title, e.media, listOf("Togli da Continua a guardare" to { WatchProgress.removeFromContinue(e) }))
            },
            modifier = Modifier.width(250.dp).height(141.dp)
        ) {
            Box(Modifier.fillMaxSize().background(C.panel)) {
                Img(TMDBImage.url(t.stillPath ?: e.backdropPath, "w780"), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    val sub = if (t.season != null && t.episode != null)
                        "S${t.season}, E${t.episode}" + if (t.isNext) " · Prossimo" else " · ${e.remainingMinutes} min rimanenti"
                    else "${e.remainingMinutes} min rimanenti"
                    Text(sub, color = C.dim, fontSize = 11.sp, maxLines = 1)
                    if (!t.isNext && e.fraction > 0) {
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0x55FFFFFF))) {
                            Box(Modifier.fillMaxWidth(e.fraction.toFloat()).height(3.dp).background(Color.White))
                        }
                    }
                }
            }
        }
    }
}

// MARK: Consigli dell'IA

@Composable
private fun AIRow(type: String) {
    val picks = AI.typedPicks(type)
    val title = "Scelti per te · " + if (type == "movie") "Film" else "Serie TV"
    if (!AI.hasKey) return
    if (picks.isEmpty()) {
        val msg = if (type in AI.loadingTypes) "L'IA sta scegliendo i titoli per te…" else AI.typeStatus[type] ?: return
        Column(Modifier.padding(top = 18.dp)) {
            SectionTitle(title, icon = Icons.Filled.AutoAwesome)
            Text(msg, color = C.faint, fontSize = 14.sp, modifier = Modifier.padding(start = C.pad))
        }
        return
    }
    MediaRow(title, Icons.Filled.AutoAwesome) {
        items(picks, key = { it.id }) { p -> PosterCard(p.item, 130.dp) }
    }
}

// MARK: Top 10

@Composable
private fun Top10Row(title: String, items: List<MediaItem>) {
    MediaRow(title) {
        itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
            FocusBox(onClick = { Nav.push(Screen.Detail(m)) }, onMenu = { Nav.menu = MenuReq(m) }, modifier = Modifier.width(130.dp).height(195.dp)) {
                Box(Modifier.fillMaxSize().background(C.panel)) {
                    Img(TMDBImage.url(m.posterPath, "w342"), Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x59000000), 0.25f to Color.Transparent,
                        0.6f to Color.Transparent, 1f to Color(0xD9000000))))
                    Text("${i + 1}", color = Color.White.copy(alpha = 0.92f), fontSize = 64.sp, fontWeight = FontWeight.Black,
                        style = TextStyle(shadow = Shadow(Color(0xAA000000), blurRadius = 10f)),
                        modifier = Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = (-8).dp))
                    Text(Store.genreLabel(m), color = C.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
            }
        }
    }
}

// MARK: Cartelle del file .strand

@Composable
private fun TileShelf(sh: Shelf) {
    Column(Modifier.padding(top = 14.dp)) {
        if (!sh.hideTitle) SectionTitle(sh.title)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = C.pad, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (sh.title.contains("scopri", ignoreCase = true) && AI.hasKey) {
                items(listOf("movie", "tv")) { t ->
                    TileCard(recommendedCover[t], if (t == "movie") "Consigliati · Film" else "Consigliati · Serie TV", true) {
                        Nav.push(Screen.Recommended(t))
                    }
                }
            }
            items(sh.tiles, key = { it.id }) { tile ->
                TileCard(tile.cover, tile.title, tile.showsSourceRows || !tile.hideTitle) { Nav.push(Screen.TilePage(tile)) }
            }
        }
    }
}

@Composable
private fun TileCard(cover: String?, title: String, showTitle: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(220.dp)) {
        FocusBox(onClick = onClick, modifier = Modifier.width(220.dp).height(124.dp)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B2F7A), Color(0xFF6B2C8F)))),
                contentAlignment = Alignment.Center) {
                if (cover != null) Img(cover, Modifier.fillMaxSize()) else Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showTitle) Text(title, color = C.dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FavoriteChannelsRow(title: String) {
    val favs = LiveTV.favoriteChannels
    if (favs.isEmpty()) return
    MediaRow(title) {
        items(favs, key = { it.id }) { c ->
            Column(Modifier.width(170.dp)) {
                FocusBox(onClick = { Nav.push(Screen.LivePlayer(c)) }, modifier = Modifier.width(170.dp).height(96.dp)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF20242C)).padding(14.dp), contentAlignment = Alignment.Center) {
                        if (c.logo != null) Img(c.logo, Modifier.fillMaxSize(), ContentScale.Fit)
                        else Text(c.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(LiveTV.current(c)?.title ?: c.name, color = C.dim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
