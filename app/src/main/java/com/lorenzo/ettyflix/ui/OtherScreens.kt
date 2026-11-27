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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.EttyFlixApp
import com.lorenzo.ettyflix.data.AI
import com.lorenzo.ettyflix.data.AIPick
import com.lorenzo.ettyflix.data.ConfigReceiver
import com.lorenzo.ettyflix.data.Jellyfin
import com.lorenzo.ettyflix.data.LiveChannel
import com.lorenzo.ettyflix.data.LiveTV
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.PersonDetail
import com.lorenzo.ettyflix.data.PersonInfo
import com.lorenzo.ettyflix.data.Profiles
import com.lorenzo.ettyflix.data.SourceDef
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.TMDB
import com.lorenzo.ettyflix.data.TMDBImage
import com.lorenzo.ettyflix.data.Tile
import com.lorenzo.ettyflix.data.Trakt
import com.lorenzo.ettyflix.data.WatchProgress
import com.lorenzo.ettyflix.data.prettyDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MARK: Griglia di titoli

@Composable
fun PosterGrid(title: String, items: List<MediaItem>, loading: Boolean = false, header: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = C.pad, top = 28.dp))
        header?.invoke()
        if (loading && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        } else if (items.isEmpty()) {
            Text("Nessun titolo.", color = C.dim, fontSize = 15.sp, modifier = Modifier.padding(C.pad))
        } else {
            LazyVerticalGrid(GridCells.Adaptive(126.dp), contentPadding = PaddingValues(horizontal = C.pad, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(items, key = { it.id }) { PosterCard(it, 126.dp, showTitle = true) }
            }
        }
    }
}

@Composable
fun GridScreen(title: String, sources: List<SourceDef>, fixed: List<MediaItem>?) {
    var items by remember { mutableStateOf(fixed ?: emptyList()) }
    var loading by remember { mutableStateOf(fixed == null) }
    var sort by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(sources) {
        if (fixed != null) return@LaunchedEffect
        val lists = sources.filter { it.kind != "traktList" || Trakt.isConfigured }.map { TMDB.items(it, 5) }
        val flat = lists.flatten()
        val mixed = flat.map { it.mediaType }.toSet().size > 1
        val ordered = if (lists.size > 1 && mixed) flat.sortedByDescending { it.popularity } else flat
        val seen = HashSet<String>()
        items = ordered.filter { seen.add(it.id) }
        loading = false
    }
    val sorts = listOf("Originale", "Più popolari", "Più votati", "Più recenti", "A–Z")
    val shown = when (sort) {
        1 -> items.sortedByDescending { it.popularity }
        2 -> items.sortedByDescending { it.rating }
        3 -> items.sortedByDescending { it.year }
        4 -> items.sortedBy { it.title.lowercase() }
        else -> items
    }
    PosterGrid(title, shown, loading) {
        Row(Modifier.padding(start = C.pad, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sorts.forEachIndexed { i, s -> Chip(s, i == sort) { sort = i } }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Text(text, color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.scale(if (focused) 1.06f else 1f).clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else if (selected) Color(0x40FFFFFF) else Color(0x1AFFFFFF))
            .border(1.dp, if (selected && !focused) Color(0xCCFFFFFF) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp))
}

// MARK: Cartella (tile)

@Composable
fun TilePageScreen(tile: Tile) {
    if (!tile.showsSourceRows) { GridScreen(tile.title, tile.sources, null); return }
    Box(Modifier.fillMaxSize()) {
        Img(tile.background ?: tile.cover, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)))
        LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = 40.dp)) {
            item { Text(tile.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = C.pad)) }
            items(tile.sources.filter { it.kind != "traktList" || Trakt.isConfigured }, key = { it.key }) { src -> SlabIfAny { SourceRow(src) } }
        }
    }
}

@Composable
private fun SourceRow(src: SourceDef) {
    var items by remember { mutableStateOf(listOf<MediaItem>()) }
    LaunchedEffect(src.key) { items = TMDB.items(src, 1).take(20) }
    if (items.isEmpty()) { Spacer(Modifier.height(1.dp)); return }
    MediaRow(src.name) { items(items, key = { it.id }) { PosterCard(it, 118.dp) } }
}

// MARK: Consigliati (Perché hai visto…)

@Composable
fun RecommendedScreen(type: String) {
    var rows by remember { mutableStateOf<List<Pair<MediaItem, List<MediaItem>>>?>(null) }
    LaunchedEffect(type) {
        AI.updateQualifiedShows()
        val seen = WatchProgress.entries.values.map { "${it.mediaType}-${it.tmdbID}" }.toSet()
        val used = HashSet<String>()
        val skip = Store.notInterestedIDs   // niente titoli "Non mi piace"/"Abbandonato" nei consigli
        rows = AI.recentSeeds(type, 3).mapNotNull { seed ->
            val recs = TMDB.recommendations(type, seed.tmdbID).filter { it.id !in seen && it.id !in skip && used.add(it.id) }.take(20)
            if (recs.isEmpty()) null else seed to recs
        }
    }
    LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = 40.dp)) {
        item {
            Text("Consigliati · " + if (type == "movie") "Film" else "Serie TV", color = Color.White, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = C.pad))
        }
        val r = rows
        if (r == null) item { CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(C.pad)) }
        else if (r.isEmpty()) item {
            Column(Modifier.padding(C.pad)) {
                Text(if (type == "movie") "Guarda almeno il 75% di un film e qui troverai i consigli."
                     else "Guarda almeno il 75% di una serie (contando tutte le stagioni) e qui troverai i consigli.", color = C.dim, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                PillButton("Torna indietro") { Nav.pop() }
            }
        }
        else items(r, key = { it.first.id }) { (seed, list) ->
            Slab { MediaRow("Perché hai visto ${seed.title}") { items(list, key = { it.id }) { PosterCard(it, 118.dp) } } }
        }
    }
}

// MARK: Persona

@Composable
fun PersonScreen(id: Int, name: String) {
    var p by remember { mutableStateOf<PersonDetail?>(null) }
    var bio by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(id) {
        val d = TMDB.person(id)
        p = d
        bio = d?.biography ?: ""
        if (d != null && d.biographyInEnglish && AI.hasKey) AI.translateToItalian(d.biography, "person-$id")?.let { bio = it }
    }
    val person = p
    val credits = person?.credits?.filter { when (filter) { 1 -> it.mediaType == "movie"; 2 -> it.mediaType == "tv"; else -> true } } ?: emptyList()
    PosterGrid(name, credits, person == null) {
        if (person != null) Row(Modifier.padding(start = C.pad, top = 14.dp, end = C.pad)) {
            Box(Modifier.size(110.dp, 150.dp).clip(RoundedCornerShape(12.dp)).background(C.panel)) {
                Img(TMDBImage.url(person.profilePath, "w342"), Modifier.fillMaxSize())
            }
            Column(Modifier.padding(start = 18.dp)) {
                val sub = listOfNotNull(
                    person.department.takeIf { it.isNotEmpty() }?.let { if (it == "Acting") "Attore/attrice" else if (it == "Directing") "Regia" else it },
                    person.birthday.takeIf { it.isNotEmpty() }?.let { "Nato/a il " + prettyDate(it) },
                    person.placeOfBirth.takeIf { it.isNotEmpty() })
                Text(sub.joinToString(" · "), color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(bio.ifEmpty { "Biografia non disponibile." }, color = C.dim, fontSize = 13.sp, maxLines = 6,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tutti", "Film", "Serie TV").forEachIndexed { i, s -> Chip(s, filter == i) { filter = i } }
                }
            }
        }
    }
}

// MARK: Cerca

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var movies by remember { mutableStateOf(listOf<MediaItem>()) }
    var shows by remember { mutableStateOf(listOf<MediaItem>()) }
    var people by remember { mutableStateOf(listOf<PersonInfo>()) }
    var ai by remember { mutableStateOf(listOf<AIPick>()) }
    var aiBusy by remember { mutableStateOf(false) }
    var covers by remember { mutableStateOf(mapOf<String, List<MediaItem>>()) }
    LaunchedEffect(Unit) { if (covers.isEmpty()) covers = runCatching { BrowseLists.loadCovers() }.getOrDefault(emptyMap()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { movies = emptyList(); shows = emptyList(); people = emptyList(); return@LaunchedEffect }
        delay(450)
        val (m, t, p) = TMDB.search(q)
        movies = m; shows = t; people = p
    }
    LazyColumn(contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.padding(horizontal = C.pad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(26.dp))
                TvTextField(query, { query = it }, "Film, serie e persone", Modifier.weight(1f), onSubmit = { Store.addRecent(query) })
                if (AI.hasKey && query.trim().length > 3) PillButton(if (aiBusy) "L'IA cerca…" else "Chiedi all'IA", Icons.Filled.AutoAwesome) {
                    if (!aiBusy) scope.launch { aiBusy = true; Store.addRecent(query); ai = AI.searchByDescription(query.trim()); aiBusy = false }
                }
            }
        }
        if (query.isBlank() && Store.recents.isNotEmpty()) item {
            Column(Modifier.padding(start = C.pad, top = 24.dp)) {
                Text("Ricerche recenti", color = C.dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Store.recents.take(6).forEach { r -> Chip(r, false) { query = r } } }
            }
        }
        // Sfoglia: le stesse liste con copertina di Mac, Apple TV e iPhone
        if (query.isBlank()) {
            item { Text("Sfoglia", color = C.dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = C.pad, top = 24.dp, bottom = 6.dp)) }
            items(BrowseLists.all.chunked(3), key = { "b-" + it.first().title }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = C.pad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { b ->
                        FocusBox(onClick = { Nav.push(Screen.Grid(b.title, listOf(b.source))) },
                            modifier = Modifier.weight(1f).height(150.dp), shape = RoundedCornerShape(16.dp), focusScale = 1.04f) { f ->
                            BrowseCover(b, covers[b.title] ?: emptyList(), f)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (ai.isNotEmpty()) item { MediaRow("Proposte dell'IA", Icons.Filled.AutoAwesome) { items(ai, key = { "ai" + it.id }) { PosterCard(it.item, 118.dp) } } }
        if (movies.isNotEmpty()) item { MediaRow("Film") { items(movies, key = { it.id }) { PosterCard(it, 118.dp, showTitle = true) } } }
        if (shows.isNotEmpty()) item { MediaRow("Serie TV") { items(shows, key = { it.id }) { PosterCard(it, 118.dp, showTitle = true) } } }
        if (people.isNotEmpty()) item {
            MediaRow("Persone") {
                items(people, key = { "p" + it.id }) { p ->
                    val src = remember { MutableInteractionSource() }
                    val f by src.collectIsFocusedAsState()
                    Column(Modifier.width(96.dp).clickable(interactionSource = src, indication = null) { Nav.push(Screen.Person(p.id, p.name)) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.scale(if (f) 1.1f else 1f).size(84.dp).clip(CircleShape).background(C.panel)
                            .border(if (f) 3.dp else 0.dp, Color.White, CircleShape)) {
                            Img(TMDBImage.url(p.profilePath, "w185"), Modifier.fillMaxSize())
                        }
                        Text(p.name, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

// MARK: Libreria

@Composable
fun LibraryScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val v = WatchProgress.version
    LaunchedEffect(Profiles.currentID, v) { runCatching { com.lorenzo.ettyflix.data.CompletedModel.refresh() } }
    val items = when (tab) {
        0 -> com.lorenzo.ettyflix.data.TasteProfile.sortedForYou(Store.toWatch)   // prima quelli che ti piaceranno di più
        2 -> com.lorenzo.ettyflix.data.CompletedModel.items
        3 -> Store.notInterested
        else -> WatchProgress.history.map { it.media }
    }
    PosterGrid("Libreria", items) {
        Row(Modifier.padding(start = C.pad, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Da guardare", tab == 0) { tab = 0 }
            Chip("Visti di recente", tab == 1) { tab = 1 }
            Chip("Completati", tab == 2) { tab = 2 }
            Chip("Non mi piace / Abbandonato", tab == 3) { tab = 3 }
        }
    }
}

// MARK: TV in diretta

@Composable
fun LiveScreen() {
    var group by rememberSaveable { mutableStateOf("★ Preferiti") }
    LaunchedEffect(Unit) { runCatching { LiveTV.loadIfNeeded() } }
    if (LiveTV.provider == null) {
        Column(Modifier.padding(C.pad)) {
            Text("TV in diretta", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Nessuna playlist: inviala dal Mac con \"Condividi configurazione\".", color = C.dim, fontSize = 15.sp)
        }
        return
    }
    val groups = listOf("★ Preferiti") + LiveTV.groups
    val list: List<LiveChannel> = if (group == "★ Preferiti") LiveTV.favoriteChannels else LiveTV.visibleChannels.filter { it.group == group }
    Row(Modifier.fillMaxSize().padding(top = 24.dp)) {
        LazyColumn(Modifier.width(230.dp).fillMaxHeight(), contentPadding = PaddingValues(start = 24.dp, end = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("TV in diretta", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp)) }
            items(groups) { g -> GroupItem(g, g == group) { group = g } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 10.dp, end = 30.dp, top = 40.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item {
                Text(if (LiveTV.loading) LiveTV.status else if (group == "★ Preferiti") "Nessun canale preferito: tieni premuto OK su un canale per aggiungerlo."
                     else "Nessun canale.", color = C.dim, fontSize = 14.sp)
            }
            items(list, key = { it.id }) { c -> ChannelRow(c) }
        }
    }
}

@Composable
private fun GroupItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    Text(text, color = if (f) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (f) Color.White else if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun ChannelRow(c: LiveChannel) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    val now = LiveTV.current(c)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (f) Color(0xFF3A3F4B) else Color(0xFF1C2028))
        .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
        .onMenuKey(onClick = { Nav.push(Screen.LivePlayer(c)) }) {
            Nav.menu = MenuReq(c.name, null, listOf(
                "Guarda" to { Nav.push(Screen.LivePlayer(c)) },
                (if (LiveTV.isFavorite(c)) "Togli dai preferiti" else "Aggiungi ai preferiti") to { LiveTV.toggleFavorite(c) }
            ))
        }
        .clickable(interactionSource = src, indication = null) { Nav.push(Screen.LivePlayer(c)) }
        .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2F3A)).padding(4.dp), contentAlignment = Alignment.Center) {
            if (c.logo != null) Img(c.logo, Modifier.fillMaxSize(), ContentScale.Fit)
            else Text(c.name.take(3), color = Color.White, fontSize = 12.sp)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (now != null) {
                Text("${now.timeRange} · ${now.title}", color = C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 4.dp).width(200.dp).height(3.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(now.progress()).height(3.dp).background(Color.White))
                }
            }
        }
        Icon(if (LiveTV.isFavorite(c)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null,
            tint = if (LiveTV.isFavorite(c)) C.accent else Color(0x66FFFFFF), modifier = Modifier.size(20.dp))
    }
}

// MARK: Impostazioni

/** Categorie delle Impostazioni (a sinistra): spostandoti con il telecomando a destra compare la loro pagina. */
private data class SettingsCat(val id: String, val title: String, val subtitle: () -> String, val group: String)

private val settingsCats = listOf(
    SettingsCat("profili", "Profili", { "Profilo attivo: ${Profiles.current.name}" }, "Generale"),
    SettingsCat("apertura", "Apertura", { "Colori, effetto di luce, suono d'avvio" }, "Aspetto"),
    SettingsCat("home", "Home e pagine", { "Righe di vetro e sfondi" }, "Aspetto"),
    SettingsCat("decorazioni", "Decorazioni del periodo", { Decor.seasonal.title }, "Aspetto"),
    SettingsCat("consigli", "Consigli e voti", { "«Per te» e «Ti è piaciuto?»" }, "Visione"),
    SettingsCat("trakt", "Trakt", { if (Trakt.isLoggedIn) "Collegato" else "Non collegato" }, "Account e fonti"),
    SettingsCat("fonti", "Fonti e server", { if (TMDB.hasKey) "Chiavi e media server" else "Chiave TMDB mancante" }, "Account e fonti"),
    SettingsCat("tv", "TV in diretta", { LiveTV.provider?.let { "${LiveTV.channels.size} canali" } ?: "Nessuna playlist" }, "Account e fonti"),
    SettingsCat("ricevi", "Ricevi configurazione", { "Codice da scrivere sul Mac" }, "Dispositivo e privacy"),
    SettingsCat("avvio", "Avvio automatico", { if (com.lorenzo.ettyflix.AutoStart.enabled) "Attivo" else "Disattivato" }, "Dispositivo e privacy"),
    SettingsCat("privacy", "Privacy", { if (PrivacyBlur.on) "Nomi delle fonti sfocati" else "Sfocatura disattivata" }, "Dispositivo e privacy"),
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen() {
    var page by rememberSaveable { mutableStateOf("profili") }
    LaunchedEffect(Unit) { ConfigReceiver.start() }
    Row(Modifier.fillMaxSize().padding(horizontal = C.pad, vertical = 28.dp)) {
        // elenco delle categorie
        // tornando a sinistra il cursore va sulla categoria aperta, non su quella alla stessa altezza
        LazyColumn(Modifier.width(320.dp).fillMaxHeight().focusRestorer(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("Impostazioni", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            // versione: per capire subito se l'app è aggiornata
            item { Text(appVersionLabel(), color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) }
            settingsCats.forEachIndexed { i, c ->
                if (i == 0 || settingsCats[i - 1].group != c.group) item {
                    Text(c.group.uppercase(), color = C.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
                }
                item(key = c.id) { CategoryRow(c.title, c.subtitle(), page == c.id) { page = c.id } }
            }
        }
        Spacer(Modifier.width(28.dp))
        // pagina della categoria scelta
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp)) {
            item(key = "titolo-$page") {
                Text(settingsCats.firstOrNull { it.id == page }?.title ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            item(key = "pagina-$page") { SettingsPage(page) }
        }
    }
}

@Composable
private fun CategoryRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val f by src.collectIsFocusedAsState()
    LaunchedEffect(f) { if (f) onSelect() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(when { f -> Color.White; selected -> Color(0x33FFFFFF); else -> Color.Transparent })
        .clickable(interactionSource = src, indication = null) { onSelect() }
        .padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = if (f) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(subtitle, color = if (f) Color(0xFF444444) else C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Contenuto di una categoria. */
@Composable
private fun SettingsPage(page: String) {
    val scope = rememberCoroutineScope()
    var serverStatus by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        when (page) {
            "profili" -> Section("Profili") {
                Text("Profilo attivo: ${Profiles.current.name}", color = C.dim, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Cambia profilo") { Profiles.chosen = false }
                    PillButton("Aggiungi profilo") { renaming = true }
                }
                Text("Nomi e immagini dei profili arrivano dal Mac.", color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
            "apertura" -> Section("Apertura e scelta del profilo") {
                Choices("Colori dello sfondo", AuroraTheme.entries.map { it.id to it.title }, Look.theme.id) { id ->
                    AuroraTheme.entries.firstOrNull { it.id == id }?.let { Look.chooseTheme(it) }
                }
                Choices("Effetto di luce dell'apertura", SplashShine.entries.map { it.name to it.title }, Look.shine.name) { id ->
                    SplashShine.entries.firstOrNull { it.name == id }?.let { Look.chooseShine(it) }
                }
                Choices("Suono d'avvio", StartupSoundStyle.entries.map { it.name to it.title }, Look.sound.name) { id ->
                    StartupSoundStyle.entries.firstOrNull { it.name == id }?.let { Look.chooseSound(it) }
                }
                PillButton("Guarda di nuovo l'animazione") { Look.replay++ }
            }
            "home" -> {
                Section("Righe della Home") {
                    Choices("Aspetto delle righe", listOf("off" to "Classico", "lastre" to "Lastre di vetro"), Decor.homeGlass) { Decor.chooseHomeGlass(it) }
                    Choices("Sfondo dentro le lastre", listOf("grigio" to "Nessuno", "aurora1" to "Colori appena percettibili",
                        "aurora2" to "Colori tenui", "aurora3" to "Colori un po' più visibili"), Decor.slabBG) { Decor.chooseSlabBG(it) }
                }
                Section("Sfondo delle pagine") {
                    Choices("Sfondo", listOf("grigio" to "Grigio", "vetro" to "Vetro scuro"), Decor.pageBG) { Decor.choosePageBG(it) }
                    Choices("Colori", listOf("uguale" to "Uguale all'apertura") + AuroraTheme.entries.map { it.id to it.title },
                        Decor.pageTheme) { Decor.choosePageTheme(it) }
                }
            }
            "decorazioni" -> {
                Section("Decorazioni del periodo (nel menu laterale)") {
                    Choices("Decorazioni", SeasonalTheme.entries.map { it.id to it.title }, Decor.seasonal.id) { id ->
                        SeasonalTheme.entries.firstOrNull { it.id == id }?.let { Decor.chooseSeasonal(it) }
                    }
                }
                Section("Natale") {
                    Choices("Tipo di neve", listOf("neve" to "Neve", "fiocchi" to "Fiocchi", "misto" to "Misto"), Decor.snowStyle) { Decor.chooseSnowStyle(it) }
                    Choices("Quanto si vede", (1..5).map { "$it" to "Livello $it" }, "${Decor.snowLevel}") { Decor.chooseSnowLevel(it.toInt()) }
                    Choices("Quantità", (1..5).map { "$it" to "Livello $it" }, "${Decor.snowAmount}") { Decor.chooseSnowAmount(it.toInt()) }
                }
            }
            "consigli" -> Section("Consigli in base ai tuoi gusti") {
                val p = EttyFlixApp.prefs
                var badge by remember { mutableStateOf(p.getBoolean("match_badge", true)) }
                var prompt by remember { mutableStateOf(p.getBoolean("rating_prompt", true)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton((if (badge) "✓ " else "") + "Percentuale «per te» sulle copertine", primary = badge) {
                        badge = !badge; p.edit().putBoolean("match_badge", badge).apply(); com.lorenzo.ettyflix.data.TasteProfile.featuresChanged()
                    }
                    PillButton((if (prompt) "✓ " else "") + "Chiedi «Ti è piaciuto?» a fine visione", primary = prompt) {
                        prompt = !prompt; p.edit().putBoolean("rating_prompt", prompt).apply()
                    }
                }
            }
            "trakt" -> Section("Trakt (profilo ${Profiles.current.name})") {
                when {
                    Trakt.isLoggedIn -> {
                        Text("Collegato" + (Trakt.username?.let { " come $it" } ?: "") + ".", color = C.dim, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(if (Trakt.busy) "Sincronizzo…" else "Sincronizza ora") { scope.launch { runCatching { Trakt.syncAll() } } }
                            PillButton("Disconnetti") { Trakt.logout() }
                        }
                    }
                    Trakt.userCode != null -> {
                        Text("Vai su ${Trakt.verificationURL} dal telefono o dal computer e scrivi il codice:", color = C.dim, fontSize = 14.sp)
                        Text(Trakt.userCode ?: "", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        PillButton("Annulla") { Trakt.cancelLogin() }
                    }
                    else -> PillButton("Collega Trakt", primary = true) { Trakt.startLogin() }
                }
                if (Trakt.status.isNotEmpty()) Text(Trakt.status, color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            "fonti" -> {
                Section("Chiavi") {
                    Line("TMDB", if (TMDB.hasKey) "Chiave presente" else "Chiave mancante: inviala dal Mac")
                    Line("IA (Gemini)", if (AI.hasKey) "Chiave presente" else "Nessuna chiave")
                    Line("OMDb", if (EttyFlixApp.prefs.getString("omdb_api_key", "").isNullOrEmpty()) "Nessuna chiave" else "Chiave presente")
                }
                Section("Media server") {
                    Line("Server", Store.server?.let { "${it.name} · ${it.url.substringAfter("://").substringBefore("/")}" } ?: "Nessun server", blur = true)
                    if (Store.server != null) {
                        Spacer(Modifier.height(10.dp))
                        PillButton("Verifica server") { scope.launch { serverStatus = "Verifico…"; serverStatus = Jellyfin.check() } }
                    }
                    if (serverStatus.isNotEmpty()) Text(serverStatus, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).privacyBlur())
                }
            }
            "tv" -> Section("TV in diretta") {
                Line("Playlist", LiveTV.provider?.let { "${it.name} · ${LiveTV.channels.size} canali · guida per ${LiveTV.guide.size} canali" } ?: "Nessuna playlist")
                if (LiveTV.provider != null) {
                    Spacer(Modifier.height(10.dp))
                    PillButton(if (LiveTV.loading) "Aggiorno…" else "Aggiorna canali e guida") { scope.launch { LiveTV.refresh() } }
                }
                if (LiveTV.status.isNotEmpty()) Text(LiveTV.status, color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            "ricevi" -> Section("Ricevi configurazione dal Mac") {
                Text("Sul Mac apri EttyFlix › Impostazioni › Invia ad altri dispositivi e scrivi questo codice. Mac e Fire TV devono essere sulla stessa rete.",
                    color = C.dim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text(ConfigReceiver.code, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text(ConfigReceiver.address, color = C.faint, fontSize = 13.sp)
                if (ConfigReceiver.message.isNotEmpty()) Text(ConfigReceiver.message, color = C.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
            "avvio" -> Section("Avvio automatico") {
                var auto by remember { mutableStateOf(com.lorenzo.ettyflix.AutoStart.enabled) }
                val ok = com.lorenzo.ettyflix.AutoStart.permitted(EttyFlixApp.ctx)
                PillButton((if (auto) "✓ " else "") + "Apri EttyFlix quando accendi la TV", primary = auto) {
                    auto = !auto; com.lorenzo.ettyflix.AutoStart.enabled = auto
                }
                Text(
                    if (ok) "Permesso attivo: EttyFlix si apre da sola quando la Fire Stick si accende o si riavvia."
                    else "Manca il permesso \"mostra sopra le altre app\": si dà una volta dal Mac (vedi le istruzioni che ti ho dato). " +
                        "Senza, EttyFlix si apre da sola solo dopo un riavvio, e su alcune versioni nemmeno quello.",
                    color = if (ok) C.dim else C.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
            "privacy" -> Section("Modalità privacy") {
                PillButton((if (PrivacyBlur.on) "✓ " else "") + "Sfoca i nomi delle fonti e degli addon", primary = PrivacyBlur.on) {
                    PrivacyBlur.set(!PrivacyBlur.on)
                }
                Text("Per fare foto o video da mandare ad altri: i nomi restano al loro posto ma non si leggono.",
                    color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (renaming) NameDialog("Nuovo profilo", "") { n -> if (n != null) Profiles.add(n); renaming = false }
}

/** Riga di scelte a pillole (scorre di lato se sono tante). */
@Composable
private fun Choices(label: String, list: List<Pair<String, String>>, current: String, pick: (String) -> Unit) {
    Text(label, color = C.dim, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)) {
        items(list.size) { i ->
            val (id, title) = list[i]
            PillButton((if (current == id) "✓ " else "") + title, primary = current == id) { pick(id) }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(16.dp)).padding(20.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Line(label: String, value: String, blur: Boolean = false) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = C.faint, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        Text(value, color = C.dim, fontSize = 14.sp, modifier = if (blur) Modifier.privacyBlur() else Modifier)
    }
}


/** "Versione 1.42 · installata il 27 novembre 2026 alle 16:40" (numero uguale su tutti i dispositivi). */
fun appVersionLabel(): String = runCatching {
    val ctx = com.lorenzo.ettyflix.EttyFlixApp.ctx
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val f = java.text.SimpleDateFormat("d MMMM yyyy 'alle' HH:mm", java.util.Locale.ITALIAN)
    "Versione ${pi.versionName} · installata il ${f.format(java.util.Date(pi.lastUpdateTime))}"
}.getOrDefault("Versione sconosciuta")
