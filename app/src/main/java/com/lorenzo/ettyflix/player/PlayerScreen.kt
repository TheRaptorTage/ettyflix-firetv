package com.lorenzo.ettyflix.player

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lorenzo.ettyflix.data.Episode
import com.lorenzo.ettyflix.data.IntroService
import com.lorenzo.ettyflix.data.Jellyfin
import com.lorenzo.ettyflix.data.LiveChannel
import com.lorenzo.ettyflix.data.LiveTV
import com.lorenzo.ettyflix.data.PlayRequest
import com.lorenzo.ettyflix.data.PlayerSource
import com.lorenzo.ettyflix.data.SkipSegment
import com.lorenzo.ettyflix.data.SourceCache
import com.lorenzo.ettyflix.data.SourceMatcher
import com.lorenzo.ettyflix.data.TMDB
import com.lorenzo.ettyflix.data.TMDBImage
import com.lorenzo.ettyflix.data.Trakt
import com.lorenzo.ettyflix.data.WatchEntry
import com.lorenzo.ettyflix.data.WatchProgress
import com.lorenzo.ettyflix.data.formatTime
import com.lorenzo.ettyflix.data.todayString
import com.lorenzo.ettyflix.ui.Img
import com.lorenzo.ettyflix.ui.LogoStore
import com.lorenzo.ettyflix.ui.Nav
import com.lorenzo.ettyflix.ui.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private val yellow = Color(0xFFF5C518)

@OptIn(UnstableApi::class)
fun buildPlayer(ctx: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("EttyFlix/1.0 (Fire TV)")
        .setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage("it")
                .setPreferredTextLanguage("it")
                .setSelectUndeterminedTextLanguage(false)
                .build()
            playWhenReady = true
        }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayer) {
    AndroidView(factory = { c ->
        PlayerView(c).apply {
            useController = false
            keepScreenOn = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            this.player = player
        }
    }, modifier = Modifier.fillMaxSize())
}

private data class TrackItem(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private fun langName(code: String?): String {
    if (code.isNullOrEmpty() || code == "und") return ""
    val n = Locale(code).getDisplayLanguage(Locale.ITALIAN)
    return n.replaceFirstChar { it.uppercase() }
}

@OptIn(UnstableApi::class)
private fun tracksOf(p: ExoPlayer, type: Int): List<TrackItem> {
    val out = mutableListOf<TrackItem>()
    for (g in p.currentTracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val parts = listOfNotNull(
                langName(f.language).ifEmpty { null },
                f.label,
                f.sampleMimeType?.substringAfter("/")?.uppercase()?.takeIf { type == MC.TRACK_TYPE_AUDIO },
                when (f.channelCount) { 6 -> "5.1"; 8 -> "7.1"; 2 -> "2.0"; else -> null }.takeIf { type == MC.TRACK_TYPE_AUDIO }
            ).distinct()
            out += TrackItem(g, i, parts.joinToString(" · ").ifEmpty { "Traccia ${out.size + 1}" }, g.isTrackSelected(i))
        }
    }
    return out
}

@Composable
fun PlayerScreen(req: PlayRequest, sources: List<PlayerSource>, selected: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { buildPlayer(ctx) }
    var srcList by remember { mutableStateOf(sources) }
    var index by remember { mutableIntStateOf(selected.coerceIn(0, (sources.size - 1).coerceAtLeast(0))) }
    var position by remember { mutableDoubleStateOf(req.startAt) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    /** pannello aperto: "audio", "subs" o "sources" (null = chiuso) */
    var panelKind by remember { mutableStateOf<String?>(null) }
    val panel = panelKind != null
    /** sei sceso sui pulsanti: sinistra/destra spostano tra i pulsanti, OK li preme */
    var buttons by remember { mutableStateOf(false) }
    var btnIndex by remember { mutableIntStateOf(1) }
    var seekOffset by remember { mutableDoubleStateOf(0.0) }
    var note by remember { mutableStateOf("") }
    var segments by remember { mutableStateOf(listOf<SkipSegment>()) }
    var failed by remember { mutableStateOf(setOf<Int>()) }
    var expiredTried by remember { mutableStateOf(false) }
    var nextReady by remember { mutableStateOf<Pair<PlayRequest, List<PlayerSource>>?>(null) }
    var upNextDismissed by remember { mutableStateOf(false) }
    var trackTick by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val isTV = req.media.mediaType == "tv" && req.season != null && req.episode != null

    fun wake() { showControls = true; lastActivity = System.currentTimeMillis() }

    fun load(i: Int, at: Double) {
        val s = srcList.getOrNull(i) ?: return
        index = i
        started = false
        player.setMediaItem(ExoItem.fromUri(s.url), (at * 1000).toLong())
        player.prepare()
        player.play()
    }

    /** Durata molto più corta del film: è la pagina d'errore del server (link scaduto). */
    fun looksExpired(): Boolean {
        val expected = (req.runtime ?: 0) * 60.0
        return duration > 1 && duration < 300 && (expected >= 15 * 60 || (expected == 0.0 && duration < 90))
    }

    fun save() {
        if (!started || looksExpired()) return
        WatchProgress.update(req, position, duration)
    }

    fun tryNext(reason: String) {
        failed = failed + index
        val next = srcList.indices.firstOrNull { it !in failed }
        if (next == null) { note = "Nessuna fonte funziona per questo titolo."; return }
        note = reason
        load(next, position.coerceAtLeast(req.startAt))
    }

    fun close() {
        save()
        WatchProgress.entry(req.media.tmdbID, req.media.mediaType, req.season, req.episode)?.let { Trakt.pushProgressNow(it) }
        Nav.pop()
    }

    fun playNextEpisode() {
        val n = nextReady ?: return
        save()
        Nav.replaceTop(Screen.Player(n.first, n.second, 0))
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tryNext("La fonte non funziona, provo la successiva…")
            }
            override fun onTracksChanged(tracks: Tracks) { trackTick++ }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !looksExpired()) {
                    save()
                    if (isTV && nextReady != null && !upNextDismissed) playNextEpisode() else close()
                }
            }
        }
        player.addListener(l)
        load(index, req.startAt)
        onDispose { player.removeListener(l); player.release() }
    }

    // Tasto Home del telecomando: l'app va in background, il video va in pausa (niente audio di sottofondo)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { if (player.isPlaying) save(); player.pause() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Aggiornamento posizione + salvataggio ogni 10 secondi
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            position = player.currentPosition / 1000.0
            val d = player.duration
            if (d > 0 && d != MC.TIME_UNSET) duration = d / 1000.0
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            if (player.isPlaying && !started) { started = true; note = "" }
            if (showControls && playing && !panel && System.currentTimeMillis() - lastActivity > (if (buttons) 8000 else 4000)) {
                showControls = false; buttons = false
            }
            tick++
            if (tick % 20 == 0 && playing) save()
        }
    }

    // Link scaduto: prima cerco di nuovo le fonti, poi passo a una fonte di un altro servizio
    LaunchedEffect(duration) {
        if (!looksExpired()) return@LaunchedEffect
        if (!expiredTried) {
            expiredTried = true
            note = "Il link della fonte è scaduto: cerco di nuovo le fonti…"
            SourceCache.clear(req.media, req.season, req.episode)
            val found = withTimeoutOrNull(40_000) { Jellyfin.allSources(req.media, req.season, req.episode, fresh = true) }
            val sorted = Jellyfin.sorted(found ?: emptyList()).filter { !it.isError && it.url != null }
            if (sorted.isNotEmpty()) {
                val pick = SourceMatcher.pickIndex(sorted, srcList.getOrNull(index))
                srcList = sorted.map { it.toPlayer() }
                failed = emptySet()
                load(pick, req.startAt)
                return@LaunchedEffect
            }
        }
        // stesso link vecchio: provo un'altra fonte, preferendo un servizio diverso
        val cur = srcList.getOrNull(index)
        failed = failed + index
        val curProv = cur?.let { SourceMatcher.provider((it.title ?: "") + " " + it.fileName) }
        val candidates = srcList.indices.filter { it !in failed }
        val other = candidates.firstOrNull { SourceMatcher.provider((srcList[it].title ?: "") + " " + srcList[it].fileName) != curProv }
        val next = other ?: candidates.firstOrNull()
        if (next == null || failed.size > 8) { note = "Tutte le fonti provate hanno il link scaduto."; return@LaunchedEffect }
        note = "Link scaduto: provo un'altra fonte…"
        load(next, req.startAt)
    }

    // Salta intro / riassunto / titoli di coda (serie e film, da tre archivi)
    LaunchedEffect(req) {
        val movie = req.media.mediaType == "movie"
        if (isTV || movie) segments = IntroService.segments(req.media.tmdbID,
            if (movie) null else req.season, if (movie) null else req.episode)
    }

    // Episodio successivo: lo cerco 20 secondi dopo l'inizio, con la stessa fonte
    LaunchedEffect(req) {
        if (!isTV) return@LaunchedEffect
        delay(20_000)
        val s = req.season!!; val e = req.episode!!
        var ep: Episode? = TMDB.episodes(req.media.tmdbID, s).firstOrNull { it.number == e + 1 }
        if (ep == null) ep = TMDB.episodes(req.media.tmdbID, s + 1).firstOrNull()
        val n = ep ?: return@LaunchedEffect
        if (n.airDate.isNotEmpty() && n.airDate > todayString()) return@LaunchedEffect
        val found = withTimeoutOrNull(45_000) { Jellyfin.allSources(req.media, n.season, n.number) } ?: return@LaunchedEffect
        val sorted = Jellyfin.sorted(found).filter { !it.isError && it.url != null }
        if (sorted.isEmpty()) return@LaunchedEffect
        SourceCache.put(req.media, n.season, n.number, found)
        val pick = SourceMatcher.pickIndex(sorted, srcList.getOrNull(index))
        val ordered = listOf(sorted[pick]) + sorted.filterIndexed { i, _ -> i != pick }
        nextReady = req.copy(season = n.season, episode = n.number, episodeTitle = n.name, stillPath = n.stillPath,
            runtime = n.runtime ?: req.runtime, startAt = 0.0) to ordered.map { it.toPlayer() }
    }

    LaunchedEffect(Unit) { delay(100); runCatching { rootFocus.requestFocus() } }

    // Pulsanti in basso (come su Apple TV e Mac)
    val btnIds = buildList {
        add("back10"); add("play"); add("fwd10")
        if (isTV) add("next")
        if (srcList.size > 1) add("sources")
        add("audio"); add("subs")
    }
    fun pressButton(id: String) {
        wake()
        when (id) {
            "back10" -> player.seekTo(((position - 10).coerceAtLeast(0.0) * 1000).toLong())
            "fwd10" -> player.seekTo(((position + 10).coerceAtMost((duration - 2).coerceAtLeast(0.0)) * 1000).toLong())
            "play" -> if (player.isPlaying) { player.pause(); save() } else player.play()
            "next" -> scope.launch {
                // se la ricerca anticipata non è finita, aspetto un po' che arrivi
                var waited = 0
                while (nextReady == null && waited < 40) { delay(500); waited++ }
                playNextEpisode()
            }
            "sources", "audio", "subs" -> panelKind = id
        }
    }

    BackHandler {
        when {
            panel -> { panelKind = null; runCatching { rootFocus.requestFocus() } }
            buttons -> buttons = false
            showControls && playing -> showControls = false
            else -> close()
        }
    }

    val activeSkip = segments.firstOrNull { it.kind != "outro" && position >= it.start && position < minOf(it.end, duration) - 1 }
    val remaining = duration - position
    val showUpNext = isTV && nextReady != null && !upNextDismissed && started && duration > 300 && remaining in 0.5..30.0

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { ev ->
                if (panel) return@onPreviewKeyEvent false
                val native = ev.nativeKeyEvent
                val down = native.action == KeyEvent.ACTION_DOWN
                if (buttons) {
                    // sui pulsanti: sinistra/destra si spostano (senza uscire), OK preme, su/giù non fanno nulla
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) { wake(); btnIndex = (btnIndex - 1).coerceAtLeast(0) }; return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) { wake(); btnIndex = (btnIndex + 1).coerceAtMost(btnIds.size - 1) }; return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (down && native.repeatCount == 0) btnIds.getOrNull(btnIndex)?.let { pressButton(it) }
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) wake(); return@onPreviewKeyEvent true }
                        else -> {}
                    }
                }
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (down && native.repeatCount == 0) {
                            when {
                                showUpNext && native.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playNextEpisode()
                                activeSkip != null && !showControls && native.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                                    player.seekTo((minOf(activeSkip.end, duration - 1) * 1000).toLong())
                                else -> { if (player.isPlaying) { player.pause(); save() } else player.play(); wake() }
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val dir = if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -1 else 1
                        if (down) {
                            wake()
                            // tenendo premuto si va sempre più veloce (fino a 2 minuti per scatto)
                            val step = if (native.repeatCount == 0) 10.0 else minOf(10.0 + native.repeatCount * 3.0, 120.0)
                            seekOffset += dir * step
                        } else if (seekOffset != 0.0) {
                            val target = (position + seekOffset).coerceIn(0.0, (duration - 2).coerceAtLeast(0.0))
                            player.seekTo((target * 1000).toLong())
                            position = target
                            seekOffset = 0.0
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // giù: si scende sui pulsanti, sia in pausa che durante il film
                        if (down && native.repeatCount == 0) {
                            wake(); buttons = true
                            btnIndex = btnIds.indexOf("play").coerceAtLeast(0)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> {
                        if (down) { if (native.keyCode == KeyEvent.KEYCODE_MENU) panelKind = "audio" else wake() }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        VideoSurface(player)

        if (!started || buffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!started) {
                    Img(TMDBImage.url(req.stillPath ?: req.media.backdropPath, "w1280"), Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    if (!started) {
                        Spacer(Modifier.height(14.dp))
                        Text(req.media.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        srcList.getOrNull(index)?.let { s ->
                            Text(listOf(s.resolution, s.size).filter { it.isNotEmpty() }.joinToString(" · "), color = Color(0xB3FFFFFF), fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(showControls || !playing || seekOffset != 0.0, enter = fadeIn(), exit = fadeOut()) {
            Controls(req, position, duration, seekOffset, playing, segments, srcList.getOrNull(index),
                btnIds, if (buttons) btnIndex else -1)
        }

        if (note.isNotEmpty()) {
            Text(note, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp).background(Color(0xCC1A1D24), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp))
        }

        if (activeSkip != null && !showUpNext && !panel) {
            Text("${activeSkip.label}  ·  premi OK", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (showControls) 130.dp else 48.dp)
                    .background(Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp))
        }

        if (showUpNext) nextReady?.let { (n, _) ->
            Row(Modifier.align(Alignment.BottomEnd).padding(48.dp).width(380.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xE61A1D24)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(140.dp).height(79.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF20242C))) {
                    Img(TMDBImage.url(n.stillPath ?: n.media.backdropPath, "w300"), Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Prossimo episodio tra ${remaining.toInt()} s", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                    Text("S${n.season}, E${n.episode} · ${n.episodeTitle}", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Premi OK per guardarlo ora", color = Color(0x80FFFFFF), fontSize = 11.sp)
                }
            }
        }

        panelKind?.let { kind ->
            OptionsPanel(player, kind, srcList, index, trackTick,
                onSource = { i -> panelKind = null; buttons = false; failed = emptySet(); load(i, position); runCatching { rootFocus.requestFocus() } },
                onClose = { panelKind = null; runCatching { rootFocus.requestFocus() } })
        }
    }
}

@Composable
private fun Controls(req: PlayRequest, position: Double, duration: Double, seekOffset: Double, playing: Boolean,
                     segments: List<SkipSegment>, src: PlayerSource?, btnIds: List<String>, focusedBtn: Int) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xA6000000), Color.Transparent)))
            .padding(horizontal = 48.dp, vertical = 30.dp)) {
            val logo = LogoStore.logos[req.media.id] ?: req.logoPath
            if (logo != null) Img(TMDBImage.url(logo, "w300"), Modifier.widthIn(max = 260.dp).height(70.dp),
                androidx.compose.ui.layout.ContentScale.Fit)
            else Text(req.media.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (req.season != null && req.episode != null) {
                Text("S${req.season}, E${req.episode}" + if (req.episodeTitle.isNotEmpty()) " · ${req.episodeTitle}" else "",
                    color = Color(0xD9FFFFFF), fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))).padding(horizontal = 48.dp, vertical = 28.dp)) {
            val total = duration.coerceAtLeast(1.0)
            val target = (position + seekOffset).coerceIn(0.0, total)
            Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0x40FFFFFF)))
                for (s in segments) {
                    val x0 = (s.start / total).toFloat().coerceIn(0f, 1f)
                    val x1 = (minOf(s.end, total) / total).toFloat().coerceIn(0f, 1f)
                    if (x1 > x0) Box(Modifier.fillMaxWidth(x1).height(5.dp)) {
                        Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(((x1 - x0) / x1).coerceIn(0f, 1f)).height(5.dp).background(yellow.copy(alpha = 0.7f)))
                    }
                }
                Box(Modifier.fillMaxWidth((position / total).toFloat().coerceIn(0f, 1f)).height(5.dp).clip(CircleShape).background(Color.White))
                if (seekOffset != 0.0) {
                    Box(Modifier.fillMaxWidth((target / total).toFloat().coerceIn(0.002f, 1f)).height(22.dp)) {
                        Box(Modifier.align(Alignment.CenterEnd).width(4.dp).height(22.dp).background(yellow))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(if (seekOffset != 0.0) target else position), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (seekOffset != 0.0) Text("  (" + (if (seekOffset >= 0) "+" else "-") + formatTime(kotlin.math.abs(seekOffset)) + ")",
                    color = yellow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(if (focusedBtn >= 0) "◀ ▶ scegli un pulsante · OK per premerlo · Indietro torna alla barra"
                     else if (playing) "◀ ▶ avanti/indietro (tieni premuto per andare veloce) · ▼ pulsanti"
                     else "In pausa · OK per riprendere · ▼ pulsanti",
                    color = Color(0x99FFFFFF), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("-" + formatTime((duration - position).coerceAtLeast(0.0)), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (src != null) Text(listOf(src.resolution, src.codec, src.size).filter { it.isNotEmpty() }.joinToString(" · "),
                color = Color(0x80FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            // pulsanti: a sinistra riproduzione, a destra fonti/audio/sottotitoli
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val left = btnIds.filter { it in listOf("back10", "play", "fwd10", "next") }
                val right = btnIds.filter { it !in left }
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (id in left) PlayerButton(id, playing, btnIds.indexOf(id) == focusedBtn)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (id in right) PlayerButton(id, playing, btnIds.indexOf(id) == focusedBtn)
                }
            }
        }
    }
}

@Composable
private fun PlayerButton(id: String, playing: Boolean, focused: Boolean) {
    val icon = when (id) {
        "back10" -> Icons.Filled.Replay10
        "fwd10" -> Icons.Filled.Forward10
        "play" -> if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
        "next" -> Icons.Filled.SkipNext
        "sources" -> Icons.AutoMirrored.Filled.List
        "audio" -> Icons.AutoMirrored.Filled.VolumeUp
        else -> Icons.Filled.ClosedCaption
    }
    Box(Modifier.size(46.dp).clip(CircleShape).background(if (focused) Color.White else Color.Transparent),
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, contentDescription = id, tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(if (id == "play") 30.dp else 26.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun OptionsPanel(player: ExoPlayer, kind: String, sources: List<PlayerSource>, current: Int, tick: Int,
                         onSource: (Int) -> Unit, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val t = tick
    var refresh by remember { mutableIntStateOf(0) }
    val audio = remember(tick, refresh) { tracksOf(player, MC.TRACK_TYPE_AUDIO) }
    val subs = remember(tick, refresh) { tracksOf(player, MC.TRACK_TYPE_TEXT) }
    val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT) || subs.none { it.selected }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(330.dp).background(Color(0xF0151820))
            .padding(horizontal = 40.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (kind == "audio") PanelColumn("Audio", Modifier.weight(1f)) {
                itemsIndexed(audio) { i, a ->
                    PanelButton(a.label, a.selected, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(a.group.mediaTrackGroup, a.index)).build()
                        refresh++
                        onClose()
                    }
                }
                if (audio.isEmpty()) item { Text("Nessuna traccia", color = Color(0x80FFFFFF), fontSize = 13.sp,
                    modifier = Modifier.focusRequester(first).focusable()) }
            }
            if (kind == "subs") PanelColumn("Sottotitoli", Modifier.weight(1f)) {
                item {
                    PanelButton("Disattivati", subsOff, Modifier.focusRequester(first)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
                        refresh++
                        onClose()
                    }
                }
                itemsIndexed(subs) { _, s ->
                    PanelButton(s.label, s.selected && !subsOff) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(s.group.mediaTrackGroup, s.index)).build()
                        refresh++
                        onClose()
                    }
                }
            }
            if (kind == "sources") PanelColumn("Sorgente", Modifier.weight(1.4f)) {
                itemsIndexed(sources) { i, s ->
                    PanelButton(listOf(s.resolution, s.codec, s.size).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Sorgente ${i + 1}" } +
                        "\n" + (s.title ?: s.fileName).replace("\n", " ").take(80), i == current,
                        if (i == 0) Modifier.focusRequester(first) else Modifier) { onSource(i) }
                }
            }
        }
    }
}

@Composable
private fun PanelColumn(title: String, modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(modifier.fillMaxHeight()) {
        Text(title, color = Color(0xB3FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun PanelButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (focused) Color.White else if (selected) Color(0x33FFFFFF) else Color.Transparent)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "✓" else " ", color = if (focused) Color.Black else Color.White, fontSize = 14.sp, modifier = Modifier.width(20.dp))
        Text(text, color = if (focused) Color.Black else Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: TV in diretta

@Composable
fun LivePlayerScreen(start: LiveChannel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { buildPlayer(ctx) }
    var channel by remember { mutableStateOf(start) }
    var urlIndex by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var buffering by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    /** menu laterale dei canali (1° Indietro) */
    var menuOpen by remember { mutableStateOf(false) }
    // ricollegamento automatico quando il link del canale scade e l'immagine si ferma
    var reconnecting by remember { mutableStateOf(false) }
    var reconnectStarted by remember { mutableLongStateOf(0L) }
    var reconnectTries by remember { mutableIntStateOf(0) }
    var stuckSince by remember { mutableLongStateOf(0L) }
    var lastPos by remember { mutableLongStateOf(-1L) }
    var wasPlayingBeforeStop by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val list = LiveTV.favoriteChannels + LiveTV.visibleChannels.filter { it.id !in LiveTV.favorites }

    fun load() {
        val u = channel.urls.getOrNull(urlIndex) ?: return
        player.setMediaItem(ExoItem.fromUri(u))
        player.prepare(); player.play()
        lastPos = -1L; stuckSince = 0L
    }

    fun reconnect() {
        reconnectTries++
        // dopo qualche tentativo, se il canale ha più flussi, provo il successivo
        if (reconnectTries > 3 && channel.urls.size > 1) { urlIndex = (urlIndex + 1) % channel.urls.size; reconnectTries = 1 }
        reconnecting = true
        reconnectStarted = System.currentTimeMillis()
        load()                     // l'indirizzo della lista dà un link nuovo, con un permesso nuovo
    }

    fun switchTo(c: LiveChannel) {
        menuOpen = false
        runCatching { focus.requestFocus() }
        if (c.id == channel.id) return
        channel = c; urlIndex = 0; reconnecting = false; reconnectTries = 0
        showInfo = true; lastActivity = System.currentTimeMillis()
        load()
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (reconnectTries < 3) { reconnect(); return }
                if (urlIndex + 1 < channel.urls.size) { urlIndex++; reconnectTries = 0; note = "Provo un altro flusso…"; load() }
                else { reconnecting = false; note = "Il canale non è disponibile al momento." }
            }
        }
        player.addListener(l)
        load()
        onDispose { player.removeListener(l); player.release() }
    }

    // Tasto Home: niente audio in sottofondo; al ritorno si riparte dalla diretta di adesso
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { wasPlayingBeforeStop = player.playWhenReady; player.stop() }
                Lifecycle.Event.ON_START -> if (wasPlayingBeforeStop) { wasPlayingBeforeStop = false; load() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            if (player.isPlaying) note = ""
            if (showInfo && !menuOpen && now - lastActivity > 5000) showInfo = false
            // controllo: se l'immagine resta ferma 3 secondi, rinnovo il link del canale
            if (!player.playWhenReady || menuOpen) { stuckSince = 0L; continue }
            val pos = player.currentPosition
            val moving = player.isPlaying && pos != lastPos
            lastPos = pos
            if (moving) {
                stuckSince = 0L
                if (reconnecting && now - reconnectStarted > 1000) { reconnecting = false; reconnectTries = 0 }
                continue
            }
            if (stuckSince == 0L) stuckSince = now
            val ended = player.playbackState == Player.STATE_ENDED
            if (!reconnecting && (now - stuckSince >= 3000 || ended)) reconnect()
            else if (reconnecting && now - reconnectStarted >= 7000) reconnect()
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }

    // 1° Indietro: menu dei canali · 2° Indietro (con il menu aperto): esco dalla TV e torno alla Home
    BackHandler {
        if (menuOpen) { player.stop(); Nav.selectTab(Screen.Home) }
        else { menuOpen = true; showInfo = true; lastActivity = System.currentTimeMillis() }
    }

    fun zap(d: Int) {
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == channel.id }
        switchTo(list[((if (i < 0) 0 else i) + d + list.size) % list.size])
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().focusRequester(focus)
            .onPreviewKeyEvent { ev ->
                if (menuOpen) return@onPreviewKeyEvent false
                val n = ev.nativeKeyEvent
                if (n.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent n.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)
                when (n.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showInfo = !showInfo; lastActivity = System.currentTimeMillis(); true }
                    else -> false
                }
            }.focusable()) {
            VideoSurface(player)
        }
        if (buffering && !reconnecting) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        if (reconnecting) {
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(22.dp)).background(Color(0xD91A1D24))
                .padding(horizontal = 36.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Ricollego il canale…", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Il link del canale è scaduto e lo sto rinnovando.\nNon è un problema della tua connessione.",
                    color = Color(0xBFFFFFFF), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (note.isNotEmpty()) Text(note, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(30.dp)
            .background(Color(0xCC1A1D24), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp))
        AnimatedVisibility(showInfo && !menuOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            val now = LiveTV.current(channel)
            val next = LiveTV.next(channel)
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                .padding(horizontal = 48.dp, vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(90.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF20242C)).padding(6.dp)) {
                    if (channel.logo != null) Img(channel.logo, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
                }
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Text(channel.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (now != null) {
                        Text("${now.timeRange} · ${now.title}", color = Color(0xD9FFFFFF), fontSize = 15.sp, maxLines = 1)
                        Box(Modifier.padding(top = 6.dp).width(320.dp).height(4.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                            Box(Modifier.fillMaxWidth(now.progress()).height(4.dp).background(Color.White))
                        }
                    }
                    if (next != null) Text("Dopo: ${next.timeRange} · ${next.title}", color = Color(0x99FFFFFF), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                }
                Text("▲▼ cambia canale · Indietro: elenco canali", color = Color(0x80FFFFFF), fontSize = 13.sp)
            }
        }
        if (menuOpen) ChannelMenu(list, channel, onPick = { switchTo(it) },
            onClose = { menuOpen = false; runCatching { focus.requestFocus() } })
    }
}

/** Menu laterale con i canali: su/giù scorrono, OK apre, destra chiude il menu. */
@Composable
private fun ChannelMenu(list: List<LiveChannel>, current: LiveChannel, onPick: (LiveChannel) -> Unit, onClose: () -> Unit) {
    val state = rememberLazyListState()
    val startIndex = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val requesters = remember(list.size) { List(list.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        state.scrollToItem((startIndex - 3).coerceAtLeast(0))
        delay(120)
        runCatching { requesters.getOrNull(startIndex)?.requestFocus() }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxHeight().width(380.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xF0000000), Color(0xC0000000))))
            .padding(horizontal = 20.dp, vertical = 26.dp)
            .onPreviewKeyEvent { ev ->
                val n = ev.nativeKeyEvent
                when (n.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (n.action == KeyEvent.ACTION_DOWN) onClose(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true          // a sinistra non succede nulla
                    else -> false
                }
            }) {
            Text("Canali", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            LazyColumn(state = state, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(list) { i, c -> ChannelRow(c, c.id == current.id, requesters[i]) { onPick(c) } }
            }
            Text("Indietro di nuovo per tornare alla Home", color = Color(0x80FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ChannelRow(c: LiveChannel, isCurrent: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val p = LiveTV.current(c)
    Row(Modifier.fillMaxWidth().focusRequester(requester).clip(RoundedCornerShape(12.dp))
        .background(if (focused) Color.White else Color(0x10FFFFFF))
        .clickable(interactionSource = src, indication = null, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF)).padding(4.dp)) {
            if (c.logo != null) Img(c.logo, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = if (focused) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (LiveTV.isFavorite(c)) Text("  ★", color = if (focused) Color(0xFFB8860B) else yellow, fontSize = 13.sp)
                if (isCurrent) Text("  🔊", fontSize = 12.sp)
            }
            if (p != null) {
                Text(p.title, color = if (focused) Color(0xB3000000) else Color(0xB3FFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.timeRange, color = if (focused) Color(0x80000000) else Color(0x80FFFFFF), fontSize = 11.sp)
            }
        }
    }
}
