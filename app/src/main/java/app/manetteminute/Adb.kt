package app.manetteminute

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * L'appli se connecte au débogage ADB du Fire TV lui-même (127.0.0.1:5555).
 * Ça lui donne les droits pour lancer et fermer les applis de jeu.
 * À la première connexion, le Fire TV affiche « Autoriser le débogage ? » :
 * cocher « Toujours autoriser » puis OK.
 */
object Adb {
    @Volatile private var conn: Dadb? = null

    private fun keys(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    @Volatile var lastError: String = ""

    /** Adresses à essayer : le Fire TV lui-même, puis son adresse sur le Wi-Fi/câble. */
    @Suppress("DEPRECATION")
    private fun hosts(ctx: Context): List<String> {
        val l = mutableListOf("127.0.0.1")
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) l += "${ip and 255}.${ip shr 8 and 255}.${ip shr 16 and 255}.${ip shr 24 and 255}"
        } catch (_: Exception) {}
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress }.forEach { a -> a.hostAddress?.let { if (it !in l) l += it } }
            }
        } catch (_: Exception) {}
        return l
    }

    @Synchronized
    private fun get(ctx: Context): Dadb {
        conn?.let { return it }
        val errs = mutableListOf<String>()
        for (h in hosts(ctx)) {
            // Vérifie d'abord que le port ADB répond, sinon on passe à l'adresse suivante
            try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, 5555), 2000) } }
            catch (e: Exception) { errs += "$h : port fermé"; continue }
            val d = Dadb.create(h, 5555, keys(ctx))
            try {
                d.shell("echo mm-ok")
                conn = d; lastError = ""
                return d
            } catch (e: Exception) {
                try { d.close() } catch (_: Exception) {}
                errs += "$h : " + (e.message ?: e.javaClass.simpleName).take(80)
            }
        }
        lastError = errs.joinToString(" · ")
        throw IllegalStateException(lastError)
    }

    @Synchronized
    fun reset() {
        try { conn?.close() } catch (_: Exception) {}
        conn = null
    }

    /** Lance une commande shell et renvoie sa sortie. Réessaie une fois si la connexion a sauté. */
    fun shell(ctx: Context, cmd: String): String {
        return try {
            get(ctx).shell(cmd).output
        } catch (e: Exception) {
            reset()
            get(ctx).shell(cmd).output
        }
    }

    /** Copie un fichier de l'appli vers le Fire TV (dossier temporaire accessible au système). */
    fun push(ctx: Context, file: File, remote: String) {
        try { get(ctx).push(file, remote) } catch (e: Exception) { reset(); get(ctx).push(file, remote) }
    }

    /** Lance une commande qui continue même si l'appli est arrêtée entre-temps (mise à jour, redémarrage). */
    fun detached(ctx: Context, cmd: String) {
        val esc = cmd.replace("\"", "\\\"")
        shell(ctx, "nohup sh -c \"$esc\" > /dev/null 2>&1 &")
    }

    fun ok(ctx: Context): Boolean = try { shell(ctx, "echo mm-ok").contains("mm-ok") } catch (e: Exception) { false }
}
