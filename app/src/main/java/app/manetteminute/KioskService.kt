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
    private var lastOk = System.currentTimeMillis()
    private var lastCmdId = ""
    private var updateTriedAt = 0L

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
                if (!Store.isConfigured(this)) Store.lockReason = "Borne pas encore activée"
                else if (!Store.isPaused()) { tick(); lastOk = System.currentTimeMillis() }
                lastError = ""
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                Log.w("MM", "tick", e)
                // Plus de contact avec le serveur depuis 10 min : on bloque la borne
                if (System.currentTimeMillis() - lastOk > 10 * 60_000L) {
                    lock("Pas de connexion au serveur")
                }
            }
            Thread.sleep(2000)
        }
    }

    private fun lock(reason: String) {
        Store.lockReason = reason
        try {
            if (lastStatus != "locked") { Launcher.stopGames(this); lastLaunchAt = 0; currentGame = null; readyAt = 0 }
            if (Launcher.foreground(this) != packageName) Launcher.bringHome(this)
        } catch (_: Exception) {}
        lastStatus = "locked"
    }

    private fun tick() {
        val st = Api.device(this)
        if (!st.optBoolean("ok", false)) { lock(st.optString("error", "Borne non autorisée")); return }
        st.optJSONObject("cmd")?.let { runCommand(it.optString("id"), it.optString("action")) }
        if (st.optString("status") == "suspended") { lock("Borne momentanément indisponible"); return }
        Store.lockReason = ""
        if (lastStatus == "locked") lastStatus = "idle"
        val status = st.optString("status", "idle")
        if (status == "idle") maybeUpdate(st.optInt("apkVersion", 0))
        val game = st.optString("game", "").ifBlank { null }
        val now = System.currentTimeMillis()

        when (status) {
            "launching" -> {
                val la = st.optLong("launchAt", 0L)
                if (la != lastLaunchAt && game != null) {
                    lastLaunchAt = la
                    currentGame = game
                    stopOthers(game)
                    val ready = launchWithRetry(game)
                    readyAt = if (ready < 0) 0 else System.currentTimeMillis() + ready * 1000L
                } else if (readyAt in 1..now) {
                    readyAt = 0
                    Api.ready(this)
                }
            }
            "failed" -> {
                // le client choisit sur son téléphone ; en attendant on ferme le jeu et on affiche l'accueil
                if (lastStatus != "failed") { Launcher.stopGames(this); lastLaunchAt = 0; readyAt = 0 }
                if (Launcher.foreground(this) != packageName) Launcher.bringHome(this)
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

    /**
     * Lance le jeu, vérifie que l'appli du jeu est bien à l'écran, réessaie une fois,
     * puis prévient le serveur en cas d'échec. Renvoie le délai « jeu prêt » ou -1 si échec.
     */
    private fun launchWithRetry(game: String): Int {
        var reason = ""
        for (attempt in 1..2) {
            try {
                val ready = Launcher.launch(this, game)
                Thread.sleep(5000)
                val pkg = Launcher.pkgFor(this, game)
                if (pkg != null && Launcher.foreground(this) == pkg) return ready
                reason = "l'appli du jeu n'est pas à l'écran après le lancement (essai $attempt)"
            } catch (e: Exception) {
                reason = e.message ?: e.toString()
            }
        }
        lastError = reason
        try { Api.launchFailed(this, reason) } catch (_: Exception) {}
        return -1
    }

    /** Ordres envoyés depuis ta page de gestion. Exécutés une seule fois chacun. */
    private fun runCommand(id: String, action: String) {
        if (id.isEmpty() || id == lastCmdId) return
        lastCmdId = id
        try { Api.cmdDone(this, id) } catch (_: Exception) {}
        when (action) {
            "closegame" -> { Launcher.stopGames(this); Launcher.bringHome(this) }
            "restartapp" -> Adb.detached(this, "sleep 1; am force-stop $packageName; sleep 2; am start -n $packageName/.MainActivity")
            "reboot" -> Adb.detached(this, "sleep 2; reboot")
        }
    }

    /** Mise à jour automatique : seulement quand la borne est libre, au plus une tentative par 30 min. */
    private fun maybeUpdate(serverVersion: Int) {
        if (serverVersion <= BuildConfig.VERSION_CODE) return
        val now = System.currentTimeMillis()
        if (now - updateTriedAt < 30 * 60_000L) return
        updateTriedAt = now
        try {
            val f = java.io.File(cacheDir, "mm-update.apk")
            if (!Api.downloadApk(this, f)) { lastError = "Mise à jour : téléchargement impossible"; return }
            Adb.push(this, f, "/data/local/tmp/mm-update.apk")
            Adb.detached(this, "sleep 1; pm install -r /data/local/tmp/mm-update.apk; sleep 2; am start -n $packageName/.MainActivity")
        } catch (e: Exception) { lastError = "Mise à jour : " + (e.message ?: e.toString()) }
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
