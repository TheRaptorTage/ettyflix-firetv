package com.lorenzo.ettyflix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lorenzo.ettyflix.EttyFlixApp
import com.lorenzo.ettyflix.R
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// MARK: - Decorazioni del periodo e vetro (come su Mac, Apple TV e iPhone)

enum class SeasonalTheme(val id: String, val title: String) {
    OFF("off", "Spente"), AUTO("auto", "Automatiche (in base al periodo)"),
    NATALE("natale", "Natale"), HALLOWEEN("halloween", "Halloween"), PRIMAVERA("primavera", "Primavera"),
    ESTATE("estate", "Estate"), AUTUNNO("autunno", "Autunno");

    /** quello da mostrare adesso */
    val active: SeasonalTheme?
        get() = when (this) { OFF -> null; AUTO -> forDate(); else -> this }

    /** disegno in fondo al menu laterale */
    val bottomImage: Int?
        get() = when (this) {
            NATALE -> R.drawable.season_gifts; HALLOWEEN -> R.drawable.season_pumpkins
            AUTUNNO -> R.drawable.season_leafpile; PRIMAVERA -> R.drawable.season_flowers
            ESTATE -> R.drawable.season_beach; else -> null
        }

    companion object {
        /** Natale 15–30 dicembre · Halloween 24–31 ottobre · stagioni: primi 7 giorni */
        fun forDate(): SeasonalTheme? {
            val c = Calendar.getInstance()
            val m = c.get(Calendar.MONTH) + 1; val d = c.get(Calendar.DAY_OF_MONTH)
            return when {
                m == 12 && d in 15..30 -> NATALE
                m == 10 && d in 24..31 -> HALLOWEEN
                m == 3 && d in 20..26 -> PRIMAVERA
                m == 6 && d in 21..27 -> ESTATE
                m == 9 && d in 23..29 -> AUTUNNO
                else -> null
            }
        }
    }
}

/** Scelte di aspetto e decorazioni (salvate, stessi nomi delle app Apple). */
object Decor {
    private val prefs get() = EttyFlixApp.prefs
    var seasonal by mutableStateOf(SeasonalTheme.entries.firstOrNull { it.id == prefs.getString("seasonal_theme", null) } ?: SeasonalTheme.AUTO)
        private set
    var snowStyle by mutableStateOf(prefs.getString("xmas_menu_snow", "neve") ?: "neve")
        private set
    var snowLevel by mutableIntStateOf(prefs.getInt("xmas_snow_level", 3))
        private set
    var snowAmount by mutableIntStateOf(prefs.getInt("xmas_snow_amount", 3))
        private set
    /** "off" (classico) o "lastre" (righe della Home su lastre di vetro) */
    var homeGlass by mutableStateOf(prefs.getString("mac_home_glass", "off") ?: "off")
        private set
    /** colori dentro le lastre: grigio (nessuno), aurora1, aurora2, aurora3 */
    var slabBG by mutableStateOf(prefs.getString("mac_home_bg", "grigio") ?: "grigio")
        private set
    /** sfondo delle pagine: "grigio" o "vetro" (vetro scuro con colori sfocati) */
    var pageBG by mutableStateOf(prefs.getString("mac_home_page", "grigio") ?: "grigio")
        private set
    /** colori dello sfondo delle pagine: "uguale" all'apertura, oppure un tema */
    var pageTheme by mutableStateOf(prefs.getString("home_aurora_theme", "uguale") ?: "uguale")
        private set

    fun setSeasonal(v: SeasonalTheme) { seasonal = v; prefs.edit().putString("seasonal_theme", v.id).apply() }
    fun setSnowStyle(v: String) { snowStyle = v; prefs.edit().putString("xmas_menu_snow", v).apply() }
    fun setSnowLevel(v: Int) { snowLevel = v; prefs.edit().putInt("xmas_snow_level", v).apply() }
    fun setSnowAmount(v: Int) { snowAmount = v; prefs.edit().putInt("xmas_snow_amount", v).apply() }
    fun setHomeGlass(v: String) { homeGlass = v; prefs.edit().putString("mac_home_glass", v).apply() }
    fun setSlabBG(v: String) { slabBG = v; prefs.edit().putString("mac_home_bg", v).apply() }
    fun setPageBG(v: String) { pageBG = v; prefs.edit().putString("mac_home_page", v).apply() }
    fun setPageTheme(v: String) { pageTheme = v; prefs.edit().putString("home_aurora_theme", v).apply() }

    /** tema dei colori delle pagine e delle lastre */
    val pageColors: AuroraTheme
        get() = AuroraTheme.entries.firstOrNull { it.id == pageTheme } ?: Look.theme
}

/** numeri pseudo-casuali stabili (stessa particella, stesso comportamento a ogni fotogramma) */
private fun rnd(i: Int, salt: Int): Float {
    var x = (i.toLong() * 73856093L xor salt.toLong() * 19349663L) + -0x61c8864680b583ebL
    x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
    x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
    x = x xor (x ushr 31)
    return ((x ushr 1) % 10_000L).toFloat() / 10_000f
}

/** tempo che scorre (fermo durante la visione): ~30 fotogrammi al secondo, o meno se richiesto */
@Composable
private fun rememberClock(fps: Int = 30): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fps) {
        val start = System.nanoTime()
        while (true) {
            if (!Nav.top.fullScreen) t = (System.nanoTime() - start) / 1e9f
            delay((1000 / fps).toLong())
        }
    }
    return t
}

/** Effetto del periodo che si muove dietro le voci del menu laterale. */
@Composable
fun SeasonFX(modifier: Modifier = Modifier) {
    when (Decor.seasonal.active) {
        SeasonalTheme.NATALE -> Snow(modifier)
        SeasonalTheme.HALLOWEEN -> Bats(modifier)
        SeasonalTheme.AUTUNNO -> Falling(modifier, listOf(R.drawable.sprite_leaf_red, R.drawable.sprite_oak_orange, R.drawable.sprite_leaf_orange,
            R.drawable.sprite_oval_yellow, R.drawable.sprite_leaf_yellow, R.drawable.sprite_oak_brown, R.drawable.sprite_oval_red),
            count = 13, size = 14f..24f, speed = 16f..30f, sway = 22f, spin = 0.9f, alpha = 0.9f)
        SeasonalTheme.PRIMAVERA -> Falling(modifier, listOf(R.drawable.sprite_petal_pink, R.drawable.sprite_petal_white, R.drawable.sprite_petal_pink),
            count = 14, size = 8f..13f, speed = 10f..20f, sway = 30f, spin = 0.6f, alpha = 0.85f)
        SeasonalTheme.ESTATE -> SunRays(modifier)
        else -> {}
    }
}

/** Disegno in fondo al menu laterale (regali, zucche, foglie, fiori, spiaggia), largo quanto il menu. */
@Composable
fun SeasonBottom(modifier: Modifier = Modifier) {
    val res = Decor.seasonal.active?.bottomImage ?: return
    Image(painterResource(res), null, contentScale = ContentScale.FillWidth, modifier = modifier.fillMaxWidth())
}

@Composable
private fun Snow(modifier: Modifier) {
    val t = rememberClock()
    val alphaK = listOf(0.45f, 0.7f, 1f, 1.45f, 2f)[(Decor.snowLevel - 1).coerceIn(0, 4)]
    val countK = listOf(1f, 1.6f, 2.4f, 3.4f, 4.6f)[(Decor.snowAmount - 1).coerceIn(0, 4)]
    val style = Decor.snowStyle
    Canvas(modifier.fillMaxSize()) {
        val k = density   // dimensioni in dp
        fun dots(share: Float) {
            val h = size.height + 10 * k
            val n = (max(8, min(55, (size.width / k * size.height / k / 5000).toInt())) * countK * share).toInt()
            for (i in 0 until n) {
                val depth = rnd(i, 61)
                val r = (0.8f + 1.3f * depth) * k
                val sp = (7 + 11 * depth) * k
                val y = (t * sp + rnd(i, 62) * h) % h - 5 * k
                val x = rnd(i, 63) * size.width + sin(t * (0.4f + rnd(i, 64) * 0.5f) + i) * 6 * k
                drawCircle(Color.White.copy(alpha = min(0.9f, (0.14f + 0.24f * depth) * alphaK)), r, Offset(x, y))
            }
        }
        fun flakes(share: Float) {
            val h = size.height + 20 * k
            val n = (max(4, min(24, (size.width / k * size.height / k / 12500).toInt())) * countK * share).toInt()
            for (i in 0 until n) {
                val depth = rnd(i, 71)
                val s = (3.5f + 3.5f * depth) * k
                val sp = (6 + 8 * depth) * k
                val y = (t * sp + rnd(i, 72) * h) % h - 10 * k
                val x = rnd(i, 73) * size.width + sin(t * (0.3f + rnd(i, 74) * 0.4f) + i) * 8 * k
                val col = Color.White.copy(alpha = min(0.9f, (0.12f + 0.2f * depth) * alphaK))
                val p = Path()
                for (a in 0 until 6) {
                    val ang = a * Math.PI.toFloat() / 3
                    val dx = cos(ang); val dy = sin(ang)
                    p.moveTo(0f, 0f); p.lineTo(dx * s, dy * s)
                    val bx = dx * s * 0.6f; val by = dy * s * 0.6f
                    for (side in listOf(-1f, 1f)) {
                        val b = ang + side * Math.PI.toFloat() / 4
                        p.moveTo(bx, by); p.lineTo(bx + cos(b) * s * 0.35f, by + sin(b) * s * 0.35f)
                    }
                }
                withTransform({ translate(x, y); rotate(t * (0.2f + rnd(i, 75) * 0.3f) * (if (i % 2 == 0) 1 else -1) * 57.3f, Offset.Zero) }) {
                    drawPath(p, col, style = Stroke(width = 0.9f * k))
                }
            }
        }
        when (style) {
            "fiocchi" -> flakes(1f)
            "misto" -> { dots(0.65f); flakes(0.55f) }
            else -> dots(1f)
        }
    }
}

@Composable
private fun Bats(modifier: Modifier) {
    val t = rememberClock()
    val up = ImageBitmap.imageResource(R.drawable.sprite_bat_up)
    val down = ImageBitmap.imageResource(R.drawable.sprite_bat_down)
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; val k = density
        for (i in 0 until 5) {
            val a = 0.11f + 0.09f * rnd(i, 11); val b = 0.07f + 0.08f * rnd(i, 12)
            val p = rnd(i, 13) * 6.28f; val q = rnd(i, 14) * 6.28f
            val x = w * (0.5f + 0.42f * sin(t * a + p) * cos(t * a * 0.37f + q))
            val y = h * (0.5f + 0.44f * sin(t * b + q) * cos(t * b * 0.53f + p))
            val s = (16 + 10 * rnd(i, 15)) * k
            val flap = sin(t * (11 + 4 * rnd(i, 17)) + i) > 0
            withTransform({ translate(x, y); rotate(cos(t * a + p) * 12, Offset.Zero) }) {
                drawSprite(if (flap) up else down, s, 0.55f + 0.35f * rnd(i, 16))
            }
        }
    }
}

@Composable
private fun Falling(modifier: Modifier, images: List<Int>, count: Int, size: ClosedFloatingPointRange<Float>,
                    speed: ClosedFloatingPointRange<Float>, sway: Float, spin: Float, alpha: Float) {
    val t = rememberClock()
    val imgs = images.map { ImageBitmap.imageResource(it) }
    Canvas(modifier.fillMaxSize()) {
        val k = density
        val w = this.size.width; val h = this.size.height + 40 * k
        for (i in 0 until count) {
            val s = (size.start + (size.endInclusive - size.start) * rnd(i, 21)) * k
            val v = (speed.start + (speed.endInclusive - speed.start) * rnd(i, 22)) * k
            val y = (t * v + rnd(i, 23) * h) % h - 20 * k
            val x = rnd(i, 24) * w + sin(t * (0.5f + 0.5f * rnd(i, 25)) + i * 1.7f) * sway * k
            val flutter = max(0.25f, abs(cos(t * (1.2f + rnd(i, 28)) + i)))
            withTransform({
                translate(x, y)
                rotate((t * spin * (rnd(i, 26) - 0.5f) * 2 + rnd(i, 27) * 6.28f) * 57.3f, Offset.Zero)
                scale(flutter, 1f, Offset.Zero)
            }) { drawSprite(imgs[i % imgs.size], s, alpha) }
        }
    }
}

private fun DrawScope.drawSprite(img: ImageBitmap, s: Float, alpha: Float) {
    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset((-s / 2).toInt(), (-s / 2).toInt()), dstSize = IntSize(s.toInt(), s.toInt()), alpha = alpha)
}

@Composable
private fun SunRays(modifier: Modifier) {
    val t = rememberClock(20)
    val warm = Color(1f, 0.9f, 0.62f)
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; val k = density
        drawRect(Brush.radialGradient(listOf(warm.copy(alpha = 0.18f), warm.copy(alpha = 0.04f), Color.Transparent), Offset.Zero, max(1f, w * 0.7f)))
        val len = sqrt(w * w + h * h) * 1.3f
        for (i in 0 until 2) {
            val width = (if (i == 0) 38f else 22f) * k
            val ang = 27 + i * 13 + sin(t * 0.12f + i) * 1.5f
            val op = 0.05f + 0.04f * (0.5f + 0.5f * sin(t * 0.45f + i * 1.3f))
            withTransform({ translate(w * (0.03f + 0.08f * i), -14 * k); rotate(-ang, Offset.Zero) }) {
                drawRect(Brush.verticalGradient(listOf(warm.copy(alpha = op * 1.7f), warm.copy(alpha = op), Color.Transparent), 0f, len),
                    Offset(-width / 2, 0f), Size(width, len))
            }
        }
        for (i in 0 until 6) {
            val x = w * (0.1f + 0.6f * rnd(i, 41)) + sin(t * 0.2f + i) * 10 * k
            val y = h * (0.08f + 0.6f * rnd(i, 42)) + cos(t * 0.15f + i * 2) * 12 * k
            drawCircle(warm.copy(alpha = 0.25f + 0.35f * max(0f, sin(t * (0.6f + rnd(i, 43)) + i))), 1.2f * k, Offset(x, y))
        }
    }
}

// MARK: - Vetro

/** Colori del tema che si muovono piano (sfondo delle pagine "vetro scuro" e dentro le lastre). */
@Composable
private fun SlowColors(theme: AuroraTheme, strength: Float, modifier: Modifier = Modifier) {
    val t = rememberClock(15) * 0.35f
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun blob(x: Float, y: Float, col: Color, r: Float) {
            val c = Offset(w * x, h * y); val rad = max(w, h) * r
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = strength), col.copy(alpha = strength * 0.35f), Color.Transparent), c, rad), rad, c)
        }
        blob(0.5f + 0.2f * sin(t * 0.7f), 0f, theme.top, 0.45f)
        blob(0f, 0.5f + 0.15f * cos(t * 0.9f), theme.left, 0.5f)
        blob(1f, 0.5f + 0.15f * sin(t), theme.right, 0.5f)
        blob(0.5f + 0.2f * cos(t * 0.5f), 1f, theme.bottom, 0.45f)
        blob(0.5f + 0.18f * cos(t * 0.6f), 0.5f + 0.18f * sin(t * 0.8f), theme.center, 0.42f)
    }
}

/** Sfondo delle pagine: grigio di sempre, oppure "vetro scuro" con colori sfocati e un riflesso che passa. */
@Composable
fun PageBackground(modifier: Modifier = Modifier) {
    if (Decor.pageBG != "vetro") { Box(modifier.fillMaxSize().background(C.bg)); return }
    val t = rememberClock(15)
    Box(modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
        SlowColors(Decor.pageColors, 0.3f)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(Brush.verticalGradient(listOf(Color(0x40000000), Color(0x80000000))))
            // riflesso di luce che attraversa la lastra ogni ~25 secondi
            val p = (t % 25f) / 25f
            withTransform({ translate(-w * 0.4f + p * w * 1.8f, h / 2); rotate(22f, Offset.Zero) }) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x08FFFFFF), Color(0x12FFFFFF), Color(0x08FFFFFF), Color.Transparent),
                    -w * 0.225f, w * 0.225f), Offset(-w * 0.225f, -h), Size(w * 0.45f, h * 2))
            }
            drawRect(Brush.verticalGradient(0f to Color(0x0FFFFFFF), 0.3f to Color.Transparent))
        }
    }
}

/** "Lastra di vetro" dietro una riga della Home (solo se scelta nelle Impostazioni).
 *  Se la riga è vuota (niente da mostrare) la lastra non compare. */
@Composable
fun Slab(content: @Composable () -> Unit) {
    if (Decor.homeGlass != "lastre") { content(); return }
    val shape = RoundedCornerShape(26.dp)
    val strength = when (Decor.slabBG) { "aurora1" -> 0.22f; "aurora2" -> 0.35f; "aurora3" -> 0.5f; else -> 0f }
    var h by remember { mutableIntStateOf(0) }
    val show = h > 40
    Box(Modifier.fillMaxWidth()
        .padding(horizontal = if (show) 24.dp else 0.dp, vertical = if (show) 6.dp else 0.dp)
        .then(if (show) Modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x12FFFFFF))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF))), shape) else Modifier)) {
        if (show && strength > 0f) SlowColors(Decor.pageColors, strength, Modifier.matchParentSize())
        Box(Modifier.onSizeChanged { h = it.height }.padding(bottom = if (show) 8.dp else 0.dp)) { content() }
    }
}

/** come Slab: usata per le righe che a volte sono vuote */
@Composable
fun SlabIfAny(content: @Composable () -> Unit) = Slab(content)
