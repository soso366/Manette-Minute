package app.manetteminute

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Réglages enregistrés sur le Fire TV. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("mm", Context.MODE_PRIVATE)

    fun server(c: Context): String = (p(c).getString("server", "") ?: "").trim().trimEnd('/')
    fun borne(c: Context): String = (p(c).getString("borne", "") ?: "").trim()
    fun pin(c: Context): String = p(c).getString("pin", "") ?: ""
    fun isConfigured(c: Context) = server(c).startsWith("http") && borne(c).isNotEmpty()

    fun save(c: Context, server: String, borne: String, pin: String) {
        p(c).edit().putString("server", server.trim()).putString("borne", borne.trim().lowercase())
            .putString("pin", pin.trim()).apply()
    }

    /** Pause du kiosque (pendant l'enregistrement d'un lancement ou un test), avec fin automatique. */
    @Volatile var pausedUntil: Long = 0L
    fun pause(minutes: Int) { pausedUntil = System.currentTimeMillis() + minutes * 60_000L }
    fun resume() { pausedUntil = 0L }
    fun isPaused() = System.currentTimeMillis() < pausedUntil

    /** Recettes de lancement : nom du jeu -> {pkg, url, ready, steps:[[délai ms, code touche], ...]} */
    fun recipes(c: Context): JSONObject =
        try { JSONObject(p(c).getString("recipes", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun recipe(c: Context, game: String): JSONObject? = recipes(c).optJSONObject(game)

    fun saveRecipe(c: Context, game: String, r: JSONObject) {
        val all = recipes(c); all.put(game, r)
        p(c).edit().putString("recipes", all.toString()).apply()
    }

    fun gamePackages(c: Context): Set<String> {
        val all = recipes(c); val out = mutableSetOf<String>()
        all.keys().forEach { k -> all.optJSONObject(k)?.optString("pkg")?.takeIf { it.isNotBlank() }?.let(out::add) }
        return out
    }
}

/** Appels au serveur Manette Minute (Cloudflare). */
object Api {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        try {
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    fun postJson(url: String, body: JSONObject): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            return c.responseCode
        } finally { c.disconnect() }
    }

    fun state(ctx: Context): JSONObject =
        getJson("${Store.server(ctx)}/api/state?borne=${enc(Store.borne(ctx))}")

    fun config(ctx: Context): JSONObject =
        getJson("${Store.server(ctx)}/api/config?borne=${enc(Store.borne(ctx))}")

    fun ready(ctx: Context) {
        postJson("${Store.server(ctx)}/api/ready", JSONObject().put("borne", Store.borne(ctx)))
    }
}
