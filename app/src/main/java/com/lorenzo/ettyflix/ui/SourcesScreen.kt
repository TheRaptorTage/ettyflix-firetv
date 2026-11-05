package com.lorenzo.ettyflix.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lorenzo.ettyflix.data.Jellyfin
import com.lorenzo.ettyflix.data.PlayRequest
import com.lorenzo.ettyflix.data.SourceCache
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.StreamSource
import com.lorenzo.ettyflix.data.TMDBImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SourcesScreen(req: PlayRequest) {
    var sources by remember { mutableStateOf(listOf<StreamSource>()) }
    var searching by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var run by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    LaunchedEffect(run) {
        if (Store.server == null) { searching = false; message = "Nessun media server: invia la configurazione dal Mac."; return@LaunchedEffect }
        if (run == 0) SourceCache.get(req.media, req.season, req.episode)?.let {
            sources = Jellyfin.sorted(it); searching = false; return@LaunchedEffect
        }
        searching = true
        val list = withTimeoutOrNull(45_000) {
            Jellyfin.allSources(req.media, req.season, req.episode, fresh = run > 0) { partial -> sources = Jellyfin.sorted(partial) }
        } ?: sources
        sources = Jellyfin.sorted(list)
        SourceCache.put(req.media, req.season, req.episode, list)
        searching = false
        if (list.isEmpty()) message = "Nessuna fonte trovata per questo titolo."
    }
    LaunchedEffect(sources.isNotEmpty()) {
        delay(150)
        runCatching { if (sources.isNotEmpty()) firstFocus.requestFocus() else backFocus.requestFocus() }
    }

    fun choose(i: Int) {
        val usable = sources.filter { !it.isError && it.url != null }
        val chosen = sources[i]
        val idx = usable.indexOf(chosen).coerceAtLeast(0)
        Nav.replaceTop(Screen.Player(req, usable.map { it.toPlayer() }, idx))
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF20242E)).padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("Indietro", Icons.Filled.ArrowBack, Modifier.focusRequester(backFocus)) { Nav.pop() }
                Text("Seleziona sorgente", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (sources.isNotEmpty()) Text("${sources.size} stream", color = C.dim, fontSize = 14.sp)
                PillButton(if (searching && run > 0) "Cerco…" else "Cerca di nuovo", Icons.Filled.Refresh) {
                    if (!searching) { SourceCache.clear(req.media, req.season, req.episode); sources = emptyList(); message = ""; run++ }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (sources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (searching) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("Ricerca degli stream…", color = C.dim, fontSize = 15.sp)
                    } else Text(message, color = C.dim, fontSize = 15.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp)) {
                    itemsIndexed(sources, key = { i, s -> s.id + i }) { i, s ->
                        SourceRow(s, req.runtime, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) { if (!s.isError) choose(i) }
                    }
                    if (searching) item {
                        Text("Cerco altre sorgenti…", color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
        // Colonna informazioni
        Column(Modifier.width(260.dp).padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(180.dp).height(270.dp).clip(RoundedCornerShape(12.dp)).background(C.panel)) {
                Img(TMDBImage.url(req.media.posterPath, "w342"), Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Text(req.media.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (req.season != null && req.episode != null) {
                Text("S${req.season}, E${req.episode}" + if (req.episodeTitle.isNotEmpty()) " · ${req.episodeTitle}" else "",
                    color = C.dim, fontSize = 14.sp, maxLines = 2)
            }
            if (req.startAt > 60) Text("Riprende da " + com.lorenzo.ettyflix.data.formatTime(req.startAt), color = C.faint, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(s: StreamSource, runtime: Int?, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().scale(if (focused) 1.015f else 1f).clip(shape)
            .background(if (focused) Color(0x40FFFFFF) else Color(0x1FFFFFFF))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x26FFFFFF), shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.isError) "Errore della fonte" else s.addonName, color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                if (s.isReady) Text("  ⚡", color = Color(0xFFFFD60A), fontSize = 14.sp)
                if (s.isBackup) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.height(20.dp).clip(RoundedCornerShape(50)).background(Color(0x55FF9F0A))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(50)).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text("Riserva", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // le etichette vanno a capo invece di rimpicciolirsi
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.resolutionLabel.isNotEmpty()) Tag(s.resolutionLabel)
                if (s.isDolbyVision) DolbyLogo("VISION") else if (s.isHDR) Tag("HDR")
                if (s.isAtmos) DolbyLogo("ATMOS") else s.audioBadge?.let { Tag(it) }
                if (s.channelsLabel.isNotEmpty()) Tag(s.channelsLabel)
                s.videoBadge?.let { Tag(it) }
                if (s.releaseRank == 0) Tag("Remux")
                if (s.hasItalianAudio) Tag("Italiano")
                if (s.hasItalianSubs) Tag("Sottotitolato")
                if (s.isPack) Tag("Pacchetto stagione")
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (s.sizeLabel.isNotEmpty()) Text(s.sizeLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            s.neededMbps(runtime)?.let { Text("$it Mbps", color = C.dim, fontSize = 12.sp) }
        }
    }
}

/** Etichetta in vetro chiaro, come accanto al titolo nella scheda del film. */
@Composable
private fun Tag(t: String) {
    Box(
        Modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x2EFFFFFF))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(t, color = Color(0xEBFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** Logo Dolby (doppia D) con la scritta Vision / Atmos. */
@Composable
private fun DolbyLogo(sub: String) {
    Column(Modifier.height(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.width(13.dp).height(11.dp)) {
                val r = size.height / 2
                val w = r                  // ogni D è larga metà della sua altezza
                val gap = size.width - 2 * w
                drawArc(Color.White, -90f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(-r, 0f),
                    size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                drawArc(Color.White, 90f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(w + gap + w - r, 0f),
                    size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
            }
            Spacer(Modifier.width(3.dp))
            Text("Dolby", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp)
        }
        Text(sub, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, lineHeight = 8.sp)
    }
}
