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

    fun ok(ctx: Context): Boolean = try { shell(ctx, "echo mm-ok").contains("mm-ok") } catch (e: Exception) { false }
}
