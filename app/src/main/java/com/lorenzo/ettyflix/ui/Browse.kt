package com.lorenzo.ettyflix.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lorenzo.ettyflix.EttyFlixApp
import com.lorenzo.ettyflix.data.MediaItem
import com.lorenzo.ettyflix.data.SourceDef
import com.lorenzo.ettyflix.data.TMDB
import com.lorenzo.ettyflix.data.TMDBImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// MARK: - Liste di "Sfoglia" in Cerca (uguali a Mac, Apple TV e iPhone)

class BrowseList(val title: String, val subtitle: String, val icon: ImageVector, val colors: List<Color>,
                 val source: SourceDef, val cover: SourceDef? = null)

object BrowseLists {
    private fun d(name: String, type: String, p: Map<String, String>) = SourceDef("discover", name, type, p)
    private val indigo = Color(0xFF5856D6); private val purple = Color(0xFFAF52DE); private val teal = Color(0xFF30B0C7)
    private val blue = Color(0xFF007AFF); private val mint = Color(0xFF00C7BE); private val gray = Color(0xFF8E8E93)
    private val orange = Color(0xFFFF9500); private val red = Color(0xFFFF3B30); private val green = Color(0xFF34C759)
    private val pink = Color(0xFFFF2D55); private val yellow = Color(0xFFFFCC00); private val cyan = Color(0xFF32ADE6)
    private val brown = Color(0xFFA2845E); private val black = Color(0xFF000000)

    val all: List<BrowseList> = listOf(
        BrowseList("Gemme nascoste", "Film belli che in pochi hanno visto", Icons.Filled.Diamond, listOf(indigo, purple),
            d("Gemme nascoste", "movie", mapOf("vote_average.gte" to "7.3", "vote_count.gte" to "300", "vote_count.lte" to "2500",
                "primary_release_date.gte" to "2000-01-01", "sort_by" to "vote_average.desc"))),
        BrowseList("Miniserie", "Da finire in un weekend", Icons.Filled.CalendarMonth, listOf(teal, blue),
            d("Miniserie", "tv", mapOf("with_type" to "2", "vote_average.gte" to "7.3", "vote_count.gte" to "150", "sort_by" to "popularity.desc"))),
        BrowseList("Serie concluse", "Da recuperare dall'inizio alla fine", Icons.Filled.Verified, listOf(mint, teal),
            d("Serie concluse", "tv", mapOf("with_status" to "3", "vote_average.gte" to "7.8", "vote_count.gte" to "500", "sort_by" to "popularity.desc"))),
        BrowseList("Indagini e misteri", "Serie crime e gialli", Icons.Filled.Search, listOf(gray, indigo),
            d("Indagini e misteri", "tv", mapOf("with_genres" to "80,9648", "vote_average.gte" to "7", "vote_count.gte" to "200", "sort_by" to "popularity.desc"))),
        BrowseList("Sotto i 100 minuti", "Film corti e ben fatti", Icons.Filled.Timer, listOf(orange, red),
            d("Sotto i 100 minuti", "movie", mapOf("with_runtime.gte" to "70", "with_runtime.lte" to "100", "vote_average.gte" to "7",
                "vote_count.gte" to "1000", "sort_by" to "popularity.desc"))),
        BrowseList("Cinema italiano", "Film italiani dal 2010", Icons.Filled.Movie, listOf(green, red),
            d("Cinema italiano", "movie", mapOf("with_original_language" to "it", "vote_average.gte" to "6.8", "vote_count.gte" to "200",
                "primary_release_date.gte" to "2010-01-01", "sort_by" to "popularity.desc")),
            cover = d("Cinema italiano", "movie", mapOf("with_original_language" to "it", "vote_count.gte" to "800",
                "primary_release_date.gte" to "2010-01-01", "sort_by" to "vote_count.desc"))),
        BrowseList("Serie coreane", "Le più amate", Icons.Filled.Favorite, listOf(pink, purple),
            d("Serie coreane", "tv", mapOf("with_original_language" to "ko", "vote_average.gte" to "7.5", "vote_count.gte" to "100", "sort_by" to "popularity.desc"))),
        BrowseList("Serie spagnole", "Dalla Spagna, le migliori", Icons.Filled.WbSunny, listOf(yellow, red),
            d("Serie spagnole", "tv", mapOf("with_original_language" to "es", "with_origin_country" to "ES", "vote_average.gte" to "7",
                "vote_count.gte" to "150", "sort_by" to "popularity.desc"))),
        BrowseList("Thriller ad alta tensione", "Dal 2010", Icons.Filled.Bolt, listOf(red, black),
            d("Thriller ad alta tensione", "movie", mapOf("with_genres" to "53", "vote_average.gte" to "7", "vote_count.gte" to "1500",
                "primary_release_date.gte" to "2010-01-01", "sort_by" to "popularity.desc"))),
        BrowseList("Fantascienza da pensare", "Idee grandi, non solo effetti", Icons.Filled.AutoAwesome, listOf(cyan, indigo),
            d("Fantascienza da pensare", "movie", mapOf("with_genres" to "878", "without_genres" to "28", "vote_average.gte" to "7.2",
                "vote_count.gte" to "1000", "sort_by" to "popularity.desc"))),
        BrowseList("Commedie per staccare", "Dal 2015, per ridere", Icons.Filled.EmojiEmotions, listOf(yellow, orange),
            d("Commedie per staccare", "movie", mapOf("with_genres" to "35", "without_genres" to "16,27", "vote_average.gte" to "6.8",
                "vote_count.gte" to "800", "primary_release_date.gte" to "2015-01-01", "sort_by" to "popularity.desc"))),
        BrowseList("Documentari", "I più apprezzati", Icons.Filled.Public, listOf(brown, orange),
            d("Documentari", "movie", mapOf("with_genres" to "99", "vote_average.gte" to "7.2", "vote_count.gte" to "150", "sort_by" to "popularity.desc"))),
        BrowseList("Anime", "Le serie giapponesi più amate", Icons.Filled.Whatshot, listOf(orange, pink),
            d("Anime", "tv", mapOf("with_genres" to "16", "with_original_language" to "ja", "vote_average.gte" to "7.8", "vote_count.gte" to "300",
                "sort_by" to "popularity.desc"))),
        BrowseList("Animazione per tutti", "Da vedere a qualsiasi età", Icons.Filled.AutoFixHigh, listOf(blue, mint),
            d("Animazione per tutti", "movie", mapOf("with_genres" to "16", "vote_average.gte" to "7.5", "vote_count.gte" to "2000", "sort_by" to "popularity.desc"))),
        BrowseList("Serie britanniche", "Dal Regno Unito", Icons.Filled.EmojiEvents, listOf(indigo, red),
            d("Serie britanniche", "tv", mapOf("with_origin_country" to "GB", "vote_average.gte" to "7.5", "vote_count.gte" to "300", "sort_by" to "popularity.desc"))),
        BrowseList("Serie fantasy", "Mondi da esplorare", Icons.Filled.AutoFixHigh, listOf(purple, teal),
            d("Serie fantasy", "tv", mapOf("with_genres" to "10765", "vote_average.gte" to "7.8", "vote_count.gte" to "500", "sort_by" to "popularity.desc"))),
        BrowseList("Serie comiche", "Puntate brevi da ridere", Icons.Filled.TheaterComedy, listOf(yellow, pink),
            d("Serie comiche", "tv", mapOf("with_genres" to "35", "without_genres" to "16", "vote_average.gte" to "7.5", "vote_count.gte" to "300",
                "sort_by" to "popularity.desc"))),
        BrowseList("Horror che fanno paura", "Dal 2010, solo i migliori", Icons.Filled.Visibility, listOf(black, red),
            d("Horror che fanno paura", "movie", mapOf("with_genres" to "27", "vote_average.gte" to "6.8", "vote_count.gte" to "1500",
                "primary_release_date.gte" to "2010-01-01", "sort_by" to "popularity.desc"))),
        BrowseList("Storie di guerra", "I grandi film di guerra", Icons.Filled.Shield, listOf(brown, gray),
            d("Storie di guerra", "movie", mapOf("with_genres" to "10752", "vote_average.gte" to "7.2", "vote_count.gte" to "800", "sort_by" to "popularity.desc"))),
        BrowseList("Musica sul grande schermo", "Musical e storie di musicisti", Icons.Filled.MusicNote, listOf(pink, orange),
            d("Musica sul grande schermo", "movie", mapOf("with_genres" to "10402", "vote_average.gte" to "7", "vote_count.gte" to "800", "sort_by" to "popularity.desc")))
    )

    /** Locandine delle copertine: tre per lista, e ogni titolo compare in una sola copertina. */
    suspend fun loadCovers(): Map<String, List<MediaItem>> = coroutineScope {
        val jobs = all.map { b -> async { runCatching { TMDB.items(b.cover ?: b.source, 1) }.getOrDefault(emptyList()) } }
        val used = HashSet<String>()
        val out = HashMap<String, List<MediaItem>>()
        all.zip(jobs).forEach { (b, j) ->
            val pick = j.await().filter { it.posterPath != null && it.id !in used }.take(3)
            pick.forEach { used.add(it.id) }
            out[b.title] = pick
        }
        out
    }
}

/** Copertina di una lista: sfondo sfocato del primo titolo nel colore della lista, tre locandine a ventaglio, nome in basso. */
@Composable
fun BrowseCover(list: BrowseList, posters: List<MediaItem>, focused: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(list.colors))) {
        posters.firstOrNull()?.backdropPath?.let { b ->
            Img(TMDBImage.url(b, "w780"), Modifier.fillMaxSize().blur(14.dp))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(list.colors.map { it.copy(alpha = 0.6f) })))
        }
        // riflesso di vetro e base più scura per leggere il nome
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0x2EFFFFFF), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x8C000000))))
        // tre locandine a ventaglio
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 26.dp).width(150.dp)) {
            posters.forEachIndexed { i, m ->
                Img(TMDBImage.url(m.posterPath, "w342"), Modifier
                    .align(Alignment.Center)
                    .offset(x = ((i - 1) * 32).dp, y = if (i == 1) (-6).dp else 4.dp)
                    .zIndex(if (i == 1) 3f else (2 - i).toFloat())
                    .rotate((i - 1) * 9f)
                    .size(64.dp, 96.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(6.dp)))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp).widthIn(max = 170.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0x40FFFFFF)), contentAlignment = Alignment.Center) {
                Icon(list.icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Text(list.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(list.subtitle, color = Color(0xD9FFFFFF), fontSize = 11.sp, maxLines = 2)
        }
    }
}

// MARK: - Colore principale del logo di un canale

object LogoTint {
    val colors = mutableStateMapOf<String, Color>()
    private val loading = HashSet<String>()

    suspend fun load(url: String) {
        if (colors.containsKey(url) || !loading.add(url)) return
        try {
            val ctx = EttyFlixApp.ctx
            val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).size(48).build()
            val res = ImageLoader(ctx).execute(req)
            val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return
            withContext(Dispatchers.Default) { average(bmp) }?.let { colors[url] = it }
        } finally { loading.remove(url) }
    }

    /** media dei pixel colorati (senza trasparenze, bianchi, neri e grigi) */
    private fun average(b: Bitmap): Color? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var r = 0.0; var g = 0.0; var bl = 0.0; var n = 0
        for (c in px) {
            val a = (c ushr 24) and 0xFF
            if (a < 150) continue
            val pr = ((c shr 16) and 0xFF) / 255.0; val pg = ((c shr 8) and 0xFF) / 255.0; val pb = (c and 0xFF) / 255.0
            val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
            if (mx - mn <= 0.18 || mx <= 0.2) continue
            r += pr; g += pg; bl += pb; n++
        }
        if (n < 20) return null
        return Color((r / n).toFloat(), (g / n).toFloat(), (bl / n).toFloat())
    }
}
