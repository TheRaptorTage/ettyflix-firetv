package com.lorenzo.ettyflix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animazione d'apertura "Isola di vetro" (come su Mac, Apple TV e iPhone): un punto di vetro si allarga
 * in una pillola, dentro compare il nome, poi l'effetto di luce scelto e tutto sfuma. Il suono parte a tempo.
 */
@Composable
fun EttySplash(onDone: () -> Unit) {
    val shine = Look.shine
    val appear = remember { Animatable(0f) }    // punto di vetro + aurora
    val grow = remember { Animatable(0f) }      // 0 = punto, 1 = pillola
    val word = remember { Animatable(0f) }      // nome
    val light = remember { Animatable(0f) }     // effetto di luce
    val out = remember { Animatable(0f) }       // sfumatura finale

    LaunchedEffect(Look.replay) {
        appear.snapTo(0f); grow.snapTo(0f); word.snapTo(0f); light.snapTo(0f); out.snapTo(0f)
        // il suono si prepara prima, così parte nello stesso istante del punto di vetro
        val sound = StartupSound.prepare(Look.sound, shine == SplashShine.BORDO)
        delay(150)
        StartupSound.start(sound)                                                  // 0,00 s  punto di vetro
        coroutineScope {
            launch { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 110f)) }
            delay(550)
            launch { grow.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 70f)) }   // 0,55 s  pillola
            delay(500)
            launch { word.animateTo(1f, tween(700, easing = LinearOutSlowInEasing)) }      // 1,05 s  nome
            delay(800)                                                                   // 1,85 s  luce
            when (shine) {
                SplashShine.BORDO -> launch { light.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
                SplashShine.RESPIRO -> {
                    light.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
                    delay(50)
                    launch { light.animateTo(0f, tween(800, easing = FastOutSlowInEasing)) }
                }
            }
            delay(1450)
            out.animateTo(1f, tween(550))
        }
        delay(50)
        onDone()
    }

    val k = 0.85f
    val pw = 64f + (520f - 64f) * grow.value
    val ph = 64f + (140f - 64f) * grow.value
    val breathe = if (shine == SplashShine.RESPIRO) light.value else 0f

    Box(Modifier.fillMaxSize().alpha(1f - out.value).scale(1f + 0.06f * out.value).background(Color.Black),
        contentAlignment = Alignment.Center) {
        EttyAurora(Modifier.fillMaxSize().alpha(appear.value.coerceIn(0f, 1f)))
        // pillola di vetro
        Canvas(Modifier.size((pw * k).dp, (ph * k).dp)
            .scale((0.2f + 0.8f * appear.value) * (1f + 0.035f * breathe))
            .alpha(appear.value.coerceIn(0f, 1f))) {
            val r = CornerRadius(size.height / 2)
            // bagliore del "Respiro"
            if (breathe > 0f) drawRoundRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.25f * breathe), Color.Transparent),
                center, size.width * 0.8f), Offset(-size.width * 0.3f, -size.height * 0.6f),
                Size(size.width * 1.6f, size.height * 2.2f), CornerRadius(size.height))
            // vetro: velo chiaro, riflesso in alto e bordo luminoso
            drawRoundRect(Color.White.copy(alpha = 0.10f + 0.12f * breathe), cornerRadius = r)
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent, Color.White.copy(alpha = 0.08f))),
                cornerRadius = r)
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.45f)),
                Offset.Zero, Offset(size.width, size.height)), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
            // "Bordo luminoso": un tratto di luce che corre lungo tutto il bordo
            if (shine == SplashShine.BORDO && light.value > 0f && light.value < 1f) {
                val inset = 1.5.dp.toPx()
                val path = Path().apply {
                    addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(size.height / 2 - inset)))
                }
                val pm = PathMeasure().apply { setPath(path, false) }
                val len = pm.length
                val seg = 0.18f
                val start = light.value * (1 + seg) - seg
                val from = (start.coerceIn(0f, 1f)) * len
                val to = ((start + seg).coerceIn(0f, 1f)) * len
                if (to > from) {
                    val dst = Path()
                    pm.getSegment(from, to, dst, true)
                    drawPath(dst, Color.White.copy(alpha = 0.35f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(dst, Color.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        // il nome
        Text("EttyFlix",
            style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFCCCCCC))),
                fontSize = (78 * k).sp, fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.alpha(word.value).scale(0.94f + 0.06f * word.value).blur(((1 - word.value) * 10).dp))
    }
}
