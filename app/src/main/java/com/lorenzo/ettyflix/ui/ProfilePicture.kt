package com.lorenzo.ettyflix.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.lorenzo.ettyflix.data.Profile

/** Immagini dei profili scelte sul Mac: stesse immagini predefinite (disegni originali) o la foto. */
private class Preset(val icon: ImageVector, val from: Long, val to: Long)

private val presets: Map<String, Preset> = mapOf(
    "popcorn" to Preset(Icons.Filled.Fastfood, 0xFFFF5A4E, 0xFFB3122E),
    "film" to Preset(Icons.Filled.Movie, 0xFF5B6CFF, 0xFF1F2A8C),
    "tv" to Preset(Icons.Filled.Tv, 0xFF00C2A8, 0xFF00695C),
    "teatro" to Preset(Icons.Filled.TheaterComedy, 0xFFC86BFA, 0xFF5B1A8E),
    "detective" to Preset(Icons.Filled.Search, 0xFF8D99AE, 0xFF2B2D42),
    "notte" to Preset(Icons.Filled.NightsStay, 0xFF3A47D5, 0xFF0B0F3B),
    "magia" to Preset(Icons.Filled.AutoAwesome, 0xFFFF7EDB, 0xFF7B2FF7),
    "fulmine" to Preset(Icons.Filled.Bolt, 0xFFFFD23F, 0xFFF77F00),
    "fuoco" to Preset(Icons.Filled.LocalFireDepartment, 0xFFFF9F1C, 0xFFD62828),
    "corona" to Preset(Icons.Filled.EmojiEvents, 0xFFFFE066, 0xFFC9A227),
    "zampa" to Preset(Icons.Filled.Pets, 0xFFC08552, 0xFF5E3023),
    "cane" to Preset(Icons.Filled.Pets, 0xFFE0A96D, 0xFF8C5523),
    "gatto" to Preset(Icons.Filled.Pets, 0xFFF4A261, 0xFF9C4A1A),
    "coniglio" to Preset(Icons.Filled.CrueltyFree, 0xFFF7B2BD, 0xFFC0587E),
    "tartaruga" to Preset(Icons.Filled.Spa, 0xFF80ED99, 0xFF2D6A4F),
    "uccello" to Preset(Icons.Filled.FlutterDash, 0xFF4CC9F0, 0xFF3A0CA3),
    "pesce" to Preset(Icons.Filled.SetMeal, 0xFF48CAE4, 0xFF0077B6),
    "foglia" to Preset(Icons.Filled.Eco, 0xFFA7C957, 0xFF386641),
    "musica" to Preset(Icons.Filled.MusicNote, 0xFFFF4D6D, 0xFF590D22),
    "gioco" to Preset(Icons.Filled.SportsEsports, 0xFF52B788, 0xFF1B4332),
    "cuore" to Preset(Icons.Filled.Favorite, 0xFFFF758F, 0xFFC9184A),
    "stella" to Preset(Icons.Filled.Star, 0xFFFFC300, 0xFFFF5733),
    "montagna" to Preset(Icons.Filled.Terrain, 0xFF90E0EF, 0xFF355070),
    "neve" to Preset(Icons.Filled.AcUnit, 0xFFCAF0F8, 0xFF48A9C5)
)

private val photoCache = HashMap<Int, ImageBitmap?>()

private fun decode(b64: String): ImageBitmap? = photoCache.getOrPut(b64.hashCode()) {
    runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private val glowCache = HashMap<Int, Pair<Color, Color>?>()

/** Colori della luce intorno: media della metà alta e della metà bassa dell'immagine, un po' più vivaci. */
fun profileGlow(p: Profile): Pair<Color, Color>? {
    val photo = p.photo
    if (!photo.isNullOrEmpty()) {
        return glowCache.getOrPut(photo.hashCode()) {
            runCatching {
                val bytes = Base64.decode(photo, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, 1, 2, true)
                fun vivid(c: Int): Color {
                    var r = android.graphics.Color.red(c) / 255f
                    var g = android.graphics.Color.green(c) / 255f
                    var b = android.graphics.Color.blue(c) / 255f
                    val gray = (r + g + b) / 3
                    r = gray + (r - gray) * 1.7f; g = gray + (g - gray) * 1.7f; b = gray + (b - gray) * 1.7f
                    val m = maxOf(r, g, b, 0.001f)
                    if (m < 0.6f) { r *= 0.6f / m; g *= 0.6f / m; b *= 0.6f / m }
                    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
                }
                vivid(small.getPixel(0, 0)) to vivid(small.getPixel(0, 1))
            }.getOrNull()
        }
    }
    val pr = presets[p.avatar] ?: return null
    return Color(pr.from) to Color(pr.to)
}

fun hasProfilePicture(p: Profile): Boolean = !p.photo.isNullOrEmpty() || presets.containsKey(p.avatar)

/** Foto o immagine predefinita (riempie il riquadro: la forma la decide chi la usa). */
@Composable
fun ProfilePicture(p: Profile, size: Dp) {
    val photo = p.photo
    val bmp = remember(photo) { photo?.takeIf { it.isNotEmpty() }?.let { decode(it) } }
    if (bmp != null) {
        Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        return
    }
    val pr = presets[p.avatar] ?: return
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(Color(pr.from), Color(pr.to)))),
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x47FFFFFF), Color.Transparent))))
        Icon(pr.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.46f))
    }
}
