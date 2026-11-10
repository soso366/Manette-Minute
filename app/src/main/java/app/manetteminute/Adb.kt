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

    @Synchronized
    private fun get(ctx: Context): Dadb {
        conn?.let { return it }
        val d = Dadb.create("127.0.0.1", 5555, keys(ctx))
        conn = d
        return d
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
