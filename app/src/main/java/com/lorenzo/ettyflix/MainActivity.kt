package com.lorenzo.ettyflix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.lorenzo.ettyflix.data.ConfigReceiver
import com.lorenzo.ettyflix.data.Profiles
import com.lorenzo.ettyflix.data.Trakt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.lorenzo.ettyflix.ui.AppRoot
import com.lorenzo.ettyflix.ui.EttySplash
import com.lorenzo.ettyflix.ui.Look
import com.lorenzo.ettyflix.ui.Nav
import com.lorenzo.ettyflix.ui.Screen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigReceiver.start()
        setContent {
            var splash by remember { mutableStateOf(true) }
            var first by remember { mutableStateOf(true) }
            // "Guarda di nuovo l'animazione" e i cambi di colore/luce/suono la fanno ripartire
            LaunchedEffect(Look.replay) { if (first) first = false else splash = true }
            // durante l'animazione d'apertura i tasti del telecomando non fanno nulla sotto
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { splash }) {
                AppRoot(onExit = { finish() })
                if (splash) EttySplash { splash = false }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        leftAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        ConfigReceiver.start()
        // Dopo 15 minuti lontano dall'app torna a "Chi vuole guardare?"
        if (leftAt > 0 && System.currentTimeMillis() - leftAt > 15 * 60_000 && Nav.top !is Screen.Player) {
            Profiles.chosen = false
            Nav.selectTab(Screen.Home)
        }
        leftAt = 0
        // riaprendo l'app: subito le posizioni aggiornate dagli altri dispositivi
        if (Profiles.chosen && Trakt.isLoggedIn) lifecycleScope.launch { runCatching { Trakt.quickSync() } }
    }
}
