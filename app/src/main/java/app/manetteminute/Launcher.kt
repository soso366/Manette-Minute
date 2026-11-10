package app.manetteminute

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Lance, enregistre et ferme les jeux (GeForce NOW, Luna, ...). */
object Launcher {

    /** Applis de jeu connues, pour les retrouver sur le Fire TV. */
    val KNOWN_HINTS = listOf("geforce", "nvidia", "luna")

    /** Codes Linux (getevent) -> codes Android (input keyevent). */
    private val KEYMAP = mapOf(
        "KEY_UP" to 19, "KEY_DOWN" to 20, "KEY_LEFT" to 21, "KEY_RIGHT" to 22,
        "KEY_ENTER" to 23, "KEY_SELECT" to 23, "KEY_OK" to 23, "KEY_KPENTER" to 23,
        "KEY_BACK" to 4, "KEY_ESC" to 4,
        "KEY_PLAYPAUSE" to 85, "KEY_PLAY" to 126, "KEY_PAUSE" to 127,
        "KEY_FASTFORWARD" to 90, "KEY_REWIND" to 89,
        "BTN_A" to 96, "BTN_SOUTH" to 96, "BTN_GAMEPAD" to 96,
        "BTN_B" to 97, "BTN_EAST" to 97,
        "BTN_X" to 99, "BTN_WEST" to 99, "BTN_NORTH" to 100, "BTN_Y" to 100,
        "BTN_START" to 108, "BTN_SELECT" to 109,
        "BTN_TL" to 102, "BTN_TR" to 103
    )

    /** Marge de sécurité : on rejoue les touches un peu plus lentement que l'enregistrement. */
    private const val SLOWDOWN = 1.25

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun component(ctx: Context, pkg: String): String? {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        return i?.component?.flattenToShortString()
    }

    fun installedGameApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0)
            .filter { a -> KNOWN_HINTS.any { a.packageName.lowercase().contains(it) } }
            .map { a -> a.packageName to pm.getApplicationLabel(a).toString() }
            .sortedBy { it.second }
    }

    fun startPackage(ctx: Context, pkg: String, url: String = "") {
        if (url.isNotBlank()) {
            Adb.shell(ctx, "am start -a android.intent.action.VIEW -d ${q(url)} $pkg")
        } else {
            val c = component(ctx, pkg) ?: throw IllegalStateException("Appli introuvable : $pkg")
            Adb.shell(ctx, "am start -n $c")
        }
    }

    fun bringHome(ctx: Context) {
        Adb.shell(ctx, "am start -n ${ctx.packageName}/.MainActivity")
    }

    fun stopGames(ctx: Context) {
        val pkgs = Store.gamePackages(ctx) + installedGameApps(ctx).map { it.first }
        pkgs.forEach { Adb.shell(ctx, "am force-stop $it") }
    }

    /** Paquet de l'appli affichée à l'écran. */
    fun foreground(ctx: Context): String? {
        val out = Adb.shell(ctx, "dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity'")
        return Regex("""u0 ([A-Za-z0-9_.]+)/""").find(out)?.groupValues?.get(1)
    }

    fun pkgFor(ctx: Context, game: String?): String? =
        game?.let { Store.recipe(ctx, it)?.optString("pkg")?.takeIf { p -> p.isNotBlank() } }

    /** Lance un jeu tout seul : ouvre l'appli puis rejoue les touches enregistrées. */
    fun launch(ctx: Context, game: String): Int {
        val r = Store.recipe(ctx, game) ?: throw IllegalStateException("Aucun lancement enregistré pour « $game »")
        val pkg = r.optString("pkg")
        Adb.shell(ctx, "am force-stop $pkg")
        Thread.sleep(800)
        startPackage(ctx, pkg, r.optString("url"))
        val steps = r.optJSONArray("steps") ?: JSONArray()
        val t0 = SystemClock.elapsedRealtime()
        var target = 0L
        for (i in 0 until steps.length()) {
            val s = steps.getJSONArray(i)
            target += (s.getLong(0) * SLOWDOWN).toLong()
            val wait = target - (SystemClock.elapsedRealtime() - t0)
            if (wait > 0) Thread.sleep(wait)
            Adb.shell(ctx, "input keyevent ${s.getInt(1)}")
        }
        return r.optInt("ready", 40)
    }

    /**
     * Enregistre un lancement : ouvre l'appli, puis note chaque touche de la télécommande
     * (et le temps entre chaque touche) jusqu'à l'appui sur le bouton Menu (☰).
     */
    fun record(ctx: Context, pkg: String, url: String): JSONArray {
        val up0 = Adb.shell(ctx, "cat /proc/uptime").trim().split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0
        Adb.shell(ctx, "am force-stop $pkg")
        Thread.sleep(800)
        startPackage(ctx, pkg, url)
        val out = Adb.shell(ctx, "timeout 300 getevent -lt | sed '/KEY_MENU *DOWN/q'")
        val re = Regex("""\[\s*([0-9.]+)]\s+\S+:\s+EV_KEY\s+(\S+)\s+DOWN""")
        val steps = JSONArray()
        var prev = up0 + 0.8
        for (line in out.lines()) {
            val m = re.find(line) ?: continue
            val ts = m.groupValues[1].toDoubleOrNull() ?: continue
            val label = m.groupValues[2]
            if (label == "KEY_MENU") break
            val code = KEYMAP[label] ?: continue
            val delay = ((ts - prev) * 1000).toLong().coerceIn(150L, 60000L)
            steps.put(JSONArray().put(delay).put(code))
            prev = ts
        }
        return steps
    }

    fun recipeOf(pkg: String, url: String, ready: Int, steps: JSONArray): JSONObject =
        JSONObject().put("pkg", pkg).put("url", url).put("ready", ready).put("steps", steps)
}
