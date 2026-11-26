package com.lorenzo.ettyflix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Apri EttyFlix da sola quando accendi la TV (la Fire Stick si risveglia) o dopo un riavvio.
 *
 * Fire OS non permette di scegliere l'app d'avvio: un servizio sempre attivo ascolta il risveglio dello schermo
 * e apre EttyFlix. Per aprire un'app "da dietro" Android vuole il permesso "mostra sopra le altre app"
 * (SYSTEM_ALERT_WINDOW), che sulla Fire Stick si dà solo dal Mac, una volta, con:
 *   adb shell appops set com.lorenzo.ettyflix SYSTEM_ALERT_WINDOW allow
 * Si spegne da Impostazioni › "Apri EttyFlix quando accendi la TV".
 */
object AutoStart {
    private const val KEY = "auto_start"

    var enabled: Boolean
        get() = EttyFlixApp.prefs.getBoolean(KEY, true)
        set(v) {
            EttyFlixApp.prefs.edit().putBoolean(KEY, v).apply()
            if (v) start(EttyFlixApp.ctx) else EttyFlixApp.ctx.stopService(Intent(EttyFlixApp.ctx, AutoStartService::class.java))
        }

    /** EttyFlix è davanti adesso? (lo aggiorna MainActivity) */
    @Volatile var inFront = false

    /** il permesso "mostra sopra le altre app" è stato dato (dal Mac, con adb)? */
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun start(ctx: Context) {
        if (!enabled) return
        val i = Intent(ctx, AutoStartService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    fun openApp(ctx: Context) {
        if (!enabled || inFront) return
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { ctx.startActivity(i) }
    }
}

/** Dopo un riavvio (o un aggiornamento dell'app) fa ripartire il servizio. */
class AutoStartBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                AutoStart.start(ctx)
                // dopo il riavvio lascio caricare la home della Fire Stick, poi apro EttyFlix
                Handler(Looper.getMainLooper()).postDelayed({ AutoStart.openApp(ctx) }, 6000)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> AutoStart.start(ctx)
        }
    }
}

/** Servizio sempre attivo che ascolta il risveglio della Fire Stick (TV accesa). */
class AutoStartService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // un attimo di pausa: la Fire Stick prima mostra la sua home, poi apro EttyFlix sopra
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ AutoStart.openApp(applicationContext) }, 2500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = "ettyflix_avvio"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(NotificationChannel(channel, "Avvio automatico", NotificationManager.IMPORTANCE_MIN))
        }
        val n = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("EttyFlix")
            .setContentText("Si apre da sola quando accendi la TV")
            .setSmallIcon(R.mipmap.ic_launcher_tramonto)
            .build()
        runCatching { startForeground(7, n) }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenOn, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AutoStart.enabled) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
