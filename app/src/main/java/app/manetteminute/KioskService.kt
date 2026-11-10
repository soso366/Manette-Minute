package app.manetteminute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Tourne en permanence. Toutes les 2 secondes :
 *  - demande au serveur l'état de la borne (libre / lancement / en jeu)
 *  - lance le jeu payé, signale « jeu prêt », ferme le jeu à la fin du temps
 *  - ramène l'écran d'accueil si quelqu'un sort de la borne sans payer
 */
class KioskService : Service() {
    @Volatile private var running = true
    private var lastLaunchAt = 0L
    private var currentGame: String? = null
    private var lastStatus = "idle"
    private var readyAt = 0L

    companion object {
        @Volatile var lastError: String = ""
        fun start(ctx: Context) {
            val i = Intent(ctx, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        Thread { loop() }.apply { isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("mm", "Manette Minute", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "mm")
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Manette Minute").setContentText("Borne active")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
    }

    private fun loop() {
        while (running) {
            try {
                if (!Store.isPaused() && Store.isConfigured(this)) tick()
                lastError = ""
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                Log.w("MM", "tick", e)
            }
            Thread.sleep(2000)
        }
    }

    private fun tick() {
        val st = Api.state(this)
        val status = st.optString("status", "idle")
        val game = st.optString("game", "").ifBlank { null }
        val now = System.currentTimeMillis()

        when (status) {
            "launching" -> {
                val la = st.optLong("launchAt", 0L)
                if (la != lastLaunchAt && game != null) {
                    lastLaunchAt = la
                    currentGame = game
                    stopOthers(game)
                    val ready = try { Launcher.launch(this, game) } catch (e: Exception) { lastError = e.message ?: ""; 40 }
                    readyAt = System.currentTimeMillis() + ready * 1000L
                } else if (readyAt in 1..now) {
                    readyAt = 0
                    Api.ready(this)
                }
            }
            "playing" -> {
                currentGame = game ?: currentGame
                val pkg = Launcher.pkgFor(this, currentGame)
                if (pkg != null) {
                    val fg = Launcher.foreground(this)
                    if (fg != null && fg != pkg) Launcher.startPackage(this, pkg) // ramène le jeu
                }
            }
            else -> {
                if (lastStatus != "idle") {
                    Launcher.stopGames(this) // temps écoulé : on ferme le jeu
                    lastLaunchAt = 0; currentGame = null; readyAt = 0
                }
                if (Launcher.foreground(this) != packageName) Launcher.bringHome(this)
            }
        }
        lastStatus = status
    }

    private fun stopOthers(game: String) {
        val keep = Launcher.pkgFor(this, game)
        (Store.gamePackages(this) - setOfNotNull(keep)).forEach { Adb.shell(this, "am force-stop $it") }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KioskService.start(context)
    }
}
