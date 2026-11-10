package app.manetteminute

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mesure la connexion internet de la borne : débit, latence (ping), type (wifi ou câble),
 * force du signal wifi. Le résultat est envoyé à ta page de gestion.
 */
object NetTest {

    private fun auth(ctx: Context) = JSONObject().put("borne", Store.borne(ctx)).put("code", Store.code(ctx)).put("device", Store.deviceId(ctx))

    /** Infos de connexion sans rien télécharger. */
    @Suppress("DEPRECATION")
    fun link(ctx: Context): JSONObject {
        val o = JSONObject()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "aucune"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "autre"
        }
        o.put("type", type)
        if (type == "wifi") {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                o.put("rssi", info.rssi).put("link", info.linkSpeed).put("freq", info.frequency)
            } catch (_: Exception) {}
        }
        return o
    }

    /** Latence vers le serveur : médiane de 5 allers-retours, et variation (jitter). */
    private fun ping(ctx: Context): Pair<Double, Double> {
        val times = mutableListOf<Double>()
        repeat(5) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val c = URL("${Store.server(ctx)}/api/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            try { c.inputStream.use { it.readBytes() }; times += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 }
            catch (_: Exception) {} finally { c.disconnect() }
        }
        if (times.isEmpty()) return 0.0 to 0.0
        val sorted = times.sorted()
        val med = sorted[sorted.size / 2]
        return med to (sorted.last() - sorted.first())
    }

    /** Débit descendant en Mbit/s : téléchargement de 8 Mo, meilleur de 2 essais. */
    private fun download(ctx: Context): Double {
        var best = 0.0
        repeat(2) {
            val c = URL("${Store.server(ctx)}/api/speed").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            try {
                c.outputStream.use { it.write(auth(ctx).put("size", 8).toString().toByteArray()) }
                val inp = c.inputStream
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val t0 = SystemClock.elapsedRealtimeNanos()
                while (true) { val n = inp.read(buf); if (n < 0) break; total += n }
                val secs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
                if (secs > 0 && total > 1_000_000) best = maxOf(best, total * 8 / secs / 1e6)
            } catch (_: Exception) {} finally { c.disconnect() }
        }
        return best
    }

    /**
     * Lance un test et l'envoie au serveur.
     * light = true pendant une partie : pas de téléchargement, pour ne pas gêner le joueur.
     */
    fun runAndReport(ctx: Context, light: Boolean) {
        val o = link(ctx)
        val (p, j) = ping(ctx)
        o.put("ping", p).put("jitter", j).put("light", light).put("ver", BuildConfig.VERSION_CODE)
        if (!light) o.put("down", download(ctx))
        val body = auth(ctx)
        o.keys().forEach { k -> body.put(k, o.get(k)) }
        Api.postForJson("${Store.server(ctx)}/api/net", body)
    }
}
