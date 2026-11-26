package com.lorenzo.ettyflix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import com.lorenzo.ettyflix.EttyFlixApp
import com.lorenzo.ettyflix.data.AI
import com.lorenzo.ettyflix.data.ForYouRow
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.Profiles
import com.lorenzo.ettyflix.data.RatingPrompt
import com.lorenzo.ettyflix.data.Store
import com.lorenzo.ettyflix.data.TasteFeatureStore
import com.lorenzo.ettyflix.data.TasteProfile
import com.lorenzo.ettyflix.data.WatchProgress
import kotlinx.coroutines.delay

/** verde tenue: si nota ma non ruba l'occhio alla copertina */
val TasteGood = Color(0xFF4DF26B)   // verde acceso: sulla TV si vede da lontano
/** stellina del tuo voto: giallo tenue */
val TasteMine = Color(0xFFFFD133)

/** In alto sulla copertina: "Tuo voto 8" se l'hai votato, altrimenti "92%" (da 65% in su). */
@Composable
fun MatchBadge(item: MediaItem, modifier: Modifier = Modifier, size: Int = 11) {
    @Suppress("UNUSED_VARIABLE") val v = TasteProfile.version
    val mine = Store.ratings[item.id]
    if (mine != null) {
        Row(modifier.clip(RoundedCornerShape(50)).background(Color(0xB8000000)).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("★ ", color = TasteMine, fontSize = size.sp, fontWeight = FontWeight.Bold)
            Text("Tuo voto $mine", color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    if (!EttyFlixApp.prefs.getBoolean("match_badge", true)) return
    // sempre visibile, anche bassa: così si capisce subito cosa non è nei tuoi gusti
    val p = TasteProfile.percent(item) ?: return
    Text("$p%", color = if (p >= 85) TasteGood else if (p < 50) Color(0xD1FFFFFF) else Color.White, fontSize = size.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color(0xB8000000)).padding(horizontal = 7.dp, vertical = 2.dp))
}

/** Scheda del titolo: "91% per te · Simile a «…», che hai votato 9" (+ il motivo dell'IA). */
@Composable
fun WhyForYouLine(item: MediaItem) {
    @Suppress("UNUSED_VARIABLE") val v = TasteProfile.version
    LaunchedEffect(item.id) {
        if (TasteFeatureStore.get(item.id) == null) {
            runCatching { TasteFeatureStore.fetch(listOf(item.mediaType to item.tmdbID)) }
            TasteProfile.featuresChanged()
        }
    }
    val seen = item.tmdbID in WatchProgress.startedIDs || Store.ratings.containsKey(item.id)
    if (seen) return
    val pct = TasteProfile.percent(item)
    val why = TasteProfile.reason(item)
    val aiReason = (AI.moviePicks + AI.tvPicks).firstOrNull { it.id == item.id }?.reason?.ifEmpty { null }
    if (pct == null && why == null && aiReason == null) return
    Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x26FFFFFF))
        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            if (pct != null) {
                Text("$pct% per te", color = if (pct >= 85) TasteGood else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (why != null) Text("  ·  ", color = C.dim, fontSize = 13.sp)
            }
            if (why != null) Text(why, color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (aiReason != null) Text("L'IA: $aiReason", color = C.faint, fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Riga "per te" della Home: locandine con la scritta sotto (es. "Esce il 12 dicembre"). */
@Composable
fun ForYouRowView(r: ForYouRow) {
    MediaRow(r.title, Icons.Filled.AutoAwesome) {
        items(r.items, key = { it.id }) { m ->
            Column(Modifier.width(130.dp)) {
                PosterCard(m, 130.dp)
                r.captions[m.id]?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = C.dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** Ricalcola i gusti quando cambiano voti, liste, progressi o profilo (con una piccola attesa). */
@Composable
fun TasteWatcher() {
    val finished = WatchProgress.entries.values.count { it.finished }
    LaunchedEffect(Profiles.currentID, Store.ratings, Store.disliked.size, Store.abandoned.size, finished,
        TasteProfile.rejections.size, Store.hasKey) {
        if (!Store.hasKey) return@LaunchedEffect
        delay(1500)
        runCatching { TasteProfile.rebuild() }
    }
}

/** "Ti è piaciuto?" quando chiudi il player dopo un film o l'ultimo episodio di una stagione. */
@Composable
fun RatingPromptDialog() {
    val ask = RatingPrompt.current ?: return
    val first = remember { FocusRequester() }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000))
        .onPreviewKeyEvent {
            val n = it.nativeKeyEvent
            val ok = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            ok && n.eventTime - openedAt < 600
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(C.panel, RoundedCornerShape(22.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp)).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ask.question, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ask.subtitle + " · il voto aiuta i consigli", color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Tantissimo", modifier = Modifier.focusRequester(first)) { RatingPrompt.rate(ask, 9) }
                PillButton("Sì") { RatingPrompt.rate(ask, 7) }
                PillButton("No") { RatingPrompt.rate(ask, 3) }
                if (ask.season == null) PillButton("Voto preciso") { RatingPrompt.rate(ask, null); Nav.rate = ask.item }
                PillButton("Non ora") { RatingPrompt.rate(ask, null) }
            }
        }
    }
    LaunchedEffect(ask) { delay(80); runCatching { first.requestFocus() } }
}

/** Statistiche: "I tuoi voti". */
@Composable
fun MyRatingsPanel() {
    LaunchedEffect(Store.ratings.size) { runCatching { Store.fillRatedItems() } }
    val votes = Store.ratings.values.toList()
    val rated = Store.ratings.mapNotNull { (k, v) -> Store.ratedItems[k]?.let { it to v } }
        .sortedWith(compareByDescending<Pair<MediaItem, Int>> { it.second }.thenBy { it.first.title })
    Column(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = C.pad, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("I tuoi voti", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(if (votes.size == 1) "1 titolo votato" else "${votes.size} titoli votati", color = C.dim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (votes.isEmpty()) {
            Text("Dai un voto ai film e alle serie (la stella nella scheda, o «Ti è piaciuto?» quando finisci): qui vedrai le tue classifiche.",
                color = C.dim, fontSize = 14.sp)
            return@Column
        }
        val avg = votes.average()
        val movies = Store.ratings.keys.count { it.startsWith("movie-") }
        val shows = Store.ratings.keys.count { it.startsWith("tv-") }
        val liked = votes.count { it >= 7 }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(String.format(java.util.Locale.ITALIAN, "%.1f", avg) to "voto medio", "$movies" to "film votati",
                "$shows" to "serie votate", "${Math.round(liked * 100.0 / votes.size)}%" to "ti sono piaciuti").forEach { (v, l) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("★", color = TasteMine, fontSize = 16.sp)
                    Text(v, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(l, color = C.dim, fontSize = 13.sp)
                }
            }
        }
        // come voti: quanti titoli per ogni voto da 1 a 10
        val counts = (1..10).map { n -> votes.count { it == n } }
        val top = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text("Come voti", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                counts.forEachIndexed { i, c ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (c > 0) "$c" else " ", color = C.dim, fontSize = 11.sp)
                        Box(Modifier.fillMaxWidth().height((3 + 90f * c / top).dp).clip(RoundedCornerShape(4.dp))
                            .background(if (i + 1 >= 8) TasteGood.copy(alpha = 0.8f) else if (i + 1 <= 4) Color(0x40FFFFFF) else Color(0x80FFFFFF)))
                        Text("${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // i generi che voti meglio (almeno 2 titoli)
        val total = HashMap<Int, Int>(); val count = HashMap<Int, Int>()
        for ((m, v) in rated) for (g in m.genreIDs) { total[g] = (total[g] ?: 0) + v; count[g] = (count[g] ?: 0) + 1 }
        val genres = count.filter { it.value >= 2 }.mapNotNull { (g, n) -> Store.genreNames[g]?.let { Triple(it, (total[g] ?: 0).toDouble() / n, n) } }
            .sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.first }).take(6)
        if (genres.isNotEmpty()) {
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x1FFFFFFF))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text("I generi che voti meglio", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                genres.forEach { (name, a, n) ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.width(170.dp))
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF))) {
                            Box(Modifier.fillMaxWidth((a / 10).toFloat()).height(10.dp).clip(RoundedCornerShape(50))
                                .background(if (a >= 8) TasteGood.copy(alpha = 0.8f) else Color(0x8CFFFFFF)))
                        }
                        Text(String.format(java.util.Locale.ITALIAN, "  %.1f", a), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("  ($n)", color = C.dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
        val best = rated.filter { it.second >= 7 }.take(15)
        if (best.isNotEmpty()) MediaRow("I più amati") { items(best, key = { it.first.id }) { (m, _) -> PosterCard(m, 120.dp, showTitle = true) } }
        val low = rated.filter { it.second <= 5 }.sortedBy { it.second }.take(10)
        if (low.isNotEmpty()) MediaRow("Le delusioni") { items(low, key = { it.first.id }) { (m, _) -> PosterCard(m, 120.dp, showTitle = true) } }
    }
}
