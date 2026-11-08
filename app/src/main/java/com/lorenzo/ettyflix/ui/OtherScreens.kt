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
            items(tile.sources.filter { it.kind != "traktList" || Trakt.isConfigured }, key = { it.key }) { src -> SourceRow(src) }
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
        rows = AI.recentSeeds(type, 3).mapNotNull { seed ->
            val recs = TMDB.recommendations(type, seed.tmdbID).filter { it.id !in seen && used.add(it.id) }.take(20)
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
            MediaRow("Perché hai visto ${seed.title}") { items(list, key = { it.id }) { PosterCard(it, 118.dp) } }
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
        0 -> Store.watchlist
        2 -> com.lorenzo.ettyflix.data.CompletedModel.items
        else -> WatchProgress.history.map { it.media }
    }
    PosterGrid("Libreria", items) {
        Row(Modifier.padding(start = C.pad, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Da guardare", tab == 0) { tab = 0 }
            Chip("Visti di recente", tab == 1) { tab = 1 }
            Chip("Completati", tab == 2) { tab = 2 }
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

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var serverStatus by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ConfigReceiver.start() }
    LazyColumn(contentPadding = PaddingValues(horizontal = C.pad, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Impostazioni", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Section("Ricevi configurazione dal Mac") {
                Text("Sul Mac apri EttyFlix › Impostazioni › Condividi configurazione e scrivi questo codice. Mac e Fire TV devono essere sulla stessa rete.",
                    color = C.dim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text(ConfigReceiver.code, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text(ConfigReceiver.address, color = C.faint, fontSize = 13.sp)
                if (ConfigReceiver.message.isNotEmpty()) Text(ConfigReceiver.message, color = C.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            Section("Trakt (profilo ${Profiles.current.name})") {
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
        }
        item {
            Section("Stato") {
                Line("TMDB", if (TMDB.hasKey) "Chiave presente" else "Chiave mancante: inviala dal Mac")
                Line("IA (Gemini)", if (AI.hasKey) "Chiave presente" else "Nessuna chiave")
                Line("OMDb", if (EttyFlixApp.prefs.getString("omdb_api_key", "").isNullOrEmpty()) "Nessuna chiave" else "Chiave presente")
                Line("Media server", Store.server?.let { "${it.name} · ${it.url.substringAfter("://").substringBefore("/")}" } ?: "Nessun server")
                Line("TV in diretta", LiveTV.provider?.let { "${it.name} · ${LiveTV.channels.size} canali · guida per ${LiveTV.guide.size} canali" } ?: "Nessuna playlist")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (Store.server != null) PillButton("Verifica server") { scope.launch { serverStatus = "Verifico…"; serverStatus = Jellyfin.check() } }
                    if (LiveTV.provider != null) PillButton(if (LiveTV.loading) "Aggiorno…" else "Aggiorna canali e guida") { scope.launch { LiveTV.refresh() } }
                }
                if (serverStatus.isNotEmpty()) Text(serverStatus, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                if (LiveTV.status.isNotEmpty()) Text(LiveTV.status, color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            Section("Profili") {
                Text("Profilo attivo: ${Profiles.current.name}", color = C.dim, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Cambia profilo") { Profiles.chosen = false }
                    PillButton("Aggiungi profilo") { renaming = true }
                }
            }
        }
        item {
            Text("EttyFlix per Fire TV · versione " + runCatching {
                EttyFlixApp.ctx.packageManager.getPackageInfo(EttyFlixApp.ctx.packageName, 0).versionName
            }.getOrDefault(""), color = C.faint, fontSize = 12.sp)
        }
    }
    if (renaming) NameDialog("Nuovo profilo", "") { n -> if (n != null) Profiles.add(n); renaming = false }
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
private fun Line(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = C.faint, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        Text(value, color = C.dim, fontSize = 14.sp)
    }
}
