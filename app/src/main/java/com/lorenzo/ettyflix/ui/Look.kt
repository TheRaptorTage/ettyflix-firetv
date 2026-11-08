package com.lorenzo.ettyflix.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lorenzo.ettyflix.EttyFlixApp
import com.lorenzo.ettyflix.data.Profile
import com.lorenzo.ettyflix.data.Profiles
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Colori dello sfondo aurora (uguali a Mac, Apple TV e iPhone). */
enum class AuroraTheme(val id: String, val title: String,
                       val top: Color, val left: Color, val center: Color, val right: Color, val bottom: Color,
                       val avatars: List<Color>) {
    RUBINO("rubino", "Rubino",
        c(0.35, 0.05, 0.15), c(0.1, 0.05, 0.3), c(0.92, 0.2, 0.3), c(0.05, 0.2, 0.35), c(0.4, 0.1, 0.3),
        listOf(c(0.95, 0.30, 0.42), c(0.62, 0.20, 0.55), c(1.0, 0.50, 0.45), c(0.40, 0.25, 0.70), c(0.85, 0.20, 0.30), c(0.95, 0.55, 0.70))),
    OCEANO("oceano", "Oceano",
        c(0.02, 0.18, 0.32), c(0.02, 0.08, 0.3), c(0.05, 0.55, 0.75), c(0.0, 0.3, 0.35), c(0.08, 0.15, 0.45),
        listOf(c(0.20, 0.65, 0.95), c(0.10, 0.80, 0.80), c(0.30, 0.40, 0.95), c(0.45, 0.80, 1.0), c(0.15, 0.50, 0.70), c(0.55, 0.50, 0.95))),
    SMERALDO("smeraldo", "Smeraldo",
        c(0.02, 0.25, 0.2), c(0.02, 0.15, 0.25), c(0.1, 0.65, 0.45), c(0.15, 0.3, 0.1), c(0.0, 0.25, 0.3),
        listOf(c(0.15, 0.75, 0.55), c(0.20, 0.70, 0.75), c(0.50, 0.85, 0.45), c(0.10, 0.55, 0.45), c(0.45, 0.90, 0.75), c(0.30, 0.60, 0.85))),
    AMETISTA("ametista", "Ametista",
        c(0.25, 0.05, 0.35), c(0.08, 0.05, 0.35), c(0.55, 0.25, 0.85), c(0.35, 0.05, 0.3), c(0.15, 0.1, 0.45),
        listOf(c(0.65, 0.40, 0.95), c(0.90, 0.40, 0.80), c(0.40, 0.35, 0.95), c(0.80, 0.60, 1.0), c(0.55, 0.25, 0.70), c(0.95, 0.55, 0.90))),
    TRAMONTO("tramonto", "Tramonto",
        c(0.4, 0.12, 0.05), c(0.35, 0.05, 0.2), c(0.95, 0.45, 0.2), c(0.45, 0.1, 0.25), c(0.3, 0.08, 0.3),
        listOf(c(1.0, 0.55, 0.25), c(0.95, 0.35, 0.45), c(1.0, 0.75, 0.40), c(0.85, 0.30, 0.25), c(1.0, 0.60, 0.60), c(0.75, 0.30, 0.50)));

    /** colore del cerchio di un profilo, in armonia con lo sfondo (ogni profilo ne ha uno diverso) */
    fun avatarTint(p: Profile): Color {
        val i = Profiles.list.indexOfFirst { it.id == p.id }.let { if (it < 0) p.color else it }
        return avatars[i % avatars.size]
    }
}

private fun c(r: Double, g: Double, b: Double) = Color(r.toFloat(), g.toFloat(), b.toFloat())

enum class SplashShine(val id: String, val title: String) { RESPIRO("respiro", "Respiro"), BORDO("bordo", "Bordo luminoso") }

/** Scelte dell'aspetto (salvate) e il comando per rivedere l'animazione d'apertura. */
object Look {
    private val prefs get() = EttyFlixApp.prefs
    var theme by mutableStateOf(AuroraTheme.entries.firstOrNull { it.id == prefs.getString("aurora_theme", null) } ?: AuroraTheme.RUBINO)
        private set
    var shine by mutableStateOf(SplashShine.entries.firstOrNull { it.id == prefs.getString("splash_shine", null) } ?: SplashShine.RESPIRO)
        private set
    var sound by mutableStateOf(StartupSoundStyle.entries.firstOrNull { it.id == prefs.getString("startup_sound_v4", null) } ?: StartupSoundStyle.PROFONDO)
        private set
    /** cambia ogni volta che si vuole rivedere l'animazione */
    var replay by mutableIntStateOf(0)

    fun chooseTheme(t: AuroraTheme) { theme = t; prefs.edit().putString("aurora_theme", t.id).apply(); replay++ }
    fun chooseShine(s: SplashShine) { shine = s; prefs.edit().putString("splash_shine", s.id).apply(); replay++ }
    fun chooseSound(s: StartupSoundStyle) { sound = s; prefs.edit().putString("startup_sound_v4", s.id).apply(); replay++ }
}

/** Aurora: colori che si muovono lentamente (sfondo dell'apertura e della scelta del profilo). */
@Composable
fun EttyAurora(modifier: Modifier = Modifier.fillMaxSize()) {
    val k = Look.theme
    val tr = rememberInfiniteTransition(label = "aurora")
    // ciclo di 20π secondi: tutte le frequenze tornano al punto di partenza, quindi nessuno scatto
    val t by tr.animateFloat(0f, (20 * Math.PI).toFloat(), infiniteRepeatable(tween((20 * Math.PI * 1000).toInt(), easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Color.Black)
        fun blob(x: Float, y: Float, col: Color, r: Float, a: Float = 1f) {
            val ctr = Offset(w * x, h * y)
            val rad = max(w, h) * r
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = a * 0.35f), Color.Transparent), ctr, rad), rad, ctr)
        }
        // stessi punti in movimento della versione Apple (griglia 3×3)
        blob(0.5f + 0.2f * sin(t * 0.7f), 0f, k.top, 0.45f, 0.9f)
        blob(0f, 0.5f + 0.15f * cos(t * 0.9f), k.left, 0.5f, 0.9f)
        blob(1f, 0.5f + 0.15f * sin(t), k.right, 0.5f, 0.9f)
        blob(0.5f + 0.2f * cos(t * 0.5f), 1f, k.bottom, 0.45f, 0.9f)
        blob(0.5f + 0.18f * cos(t * 0.6f), 0.5f + 0.18f * sin(t * 0.8f), k.center, 0.42f, 0.95f)
    }
}

/** Aurora del colore scelto, più scura al centro dove ci sono i profili. */
@Composable
fun EttyProfileBackground() {
    EttyAurora()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(listOf(Color(0x8C000000), Color(0x40000000)), Offset(size.width / 2, size.height / 2),
            max(size.width, size.height) * 0.55f))
    }
}
