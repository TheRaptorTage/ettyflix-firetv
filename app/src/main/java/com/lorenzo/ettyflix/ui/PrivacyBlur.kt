package com.lorenzo.ettyflix.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lorenzo.ettyflix.EttyFlixApp

/** Modalità privacy: nomi di fonti, addon e server sfocati (per foto e video da mandare ad altri). */
object PrivacyBlur {
    private const val KEY = "privacy_blur"
    var on by mutableStateOf(EttyFlixApp.prefs.getBoolean(KEY, false))
        private set
    fun set(v: Boolean) { on = v; EttyFlixApp.prefs.edit().putBoolean(KEY, v).apply() }
}

/** Sfoca questo elemento quando la modalità privacy è attiva. Sulle Fire Stick con Android vecchio
 *  (niente sfocatura del sistema) il testo viene coperto da una macchia sfumata: resta al suo posto ma non si legge. */
fun Modifier.privacyBlur(): Modifier =
    if (!PrivacyBlur.on) this
    else if (Build.VERSION.SDK_INT >= 31) this.blur(7.dp)
    else this.drawWithContent {
        drawContent()
        drawRect(Color(0xF02A2A2E))
    }
