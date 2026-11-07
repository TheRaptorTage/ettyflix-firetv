package com.lorenzo.ettyflix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.lorenzo.ettyflix.data.ConfigReceiver
import com.lorenzo.ettyflix.data.Profiles
import com.lorenzo.ettyflix.data.Trakt
import com.lorenzo.ettyflix.ui.AppRoot
import com.lorenzo.ettyflix.ui.Nav
import com.lorenzo.ettyflix.ui.Screen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigReceiver.start()
        setContent { AppRoot(onExit = { finish() }) }
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
