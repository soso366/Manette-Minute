package app.manetteminute

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * Menu de réglage de la borne (5 appuis sur ☰ depuis l'accueil, puis code PIN).
 * - adresse du serveur, identifiant de la borne, code PIN
 * - connexion au débogage du Fire TV
 * - pour chaque jeu : choisir l'appli (GeForce NOW / Luna) et enregistrer le lancement
 */
class AdminActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 60) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F0B1F")); addView(root) })
        if (Store.pin(this).isEmpty()) { unlocked = true; build() } else askPin()
    }

    override fun onResume() { super.onResume(); Store.resume() }

    // ---------- petits outils d'interface ----------
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 26f; setTextColor(Color.parseColor("#C6FF3D")); setPadding(0, 30, 0, 10)
    }.also { root.addView(it) }

    private fun label(t: String, into: LinearLayout = root) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.parseColor("#CFCBE3")); setPadding(0, 12, 0, 4)
    }.also { into.addView(it) }

    private fun field(value: String, hint: String, into: LinearLayout = root, number: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 18f
        setSingleLine(true)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }.also { into.addView(it) }

    private fun button(t: String, into: LinearLayout = root, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 17f; setOnClickListener { onClick() }
    }.also { into.addView(it) }

    private fun bg(work: () -> String) {
        status.text = "Patiente…"
        Thread {
            val msg = try { work() } catch (e: Exception) { "❌ " + (e.message ?: e.toString()) }
            ui.post { status.text = msg }
        }.start()
    }

    // ---------- écrans ----------
    private fun askPin() {
        title("Manette Minute · Réglages")
        label("Code PIN")
        val pin = field("", "Code PIN", number = true)
        button("Déverrouiller") {
            if (pin.text.toString() == Store.pin(this)) { unlocked = true; root.removeAllViews(); build() }
            else pin.error = "Code faux"
        }
        pin.requestFocus()
    }

    private fun build() {
        title("Manette Minute · Réglages")
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 10, 0, 10) }
        root.addView(status)
        if (KioskService.lastError.isNotEmpty()) status.text = "Dernière erreur : ${KioskService.lastError}"

        label("Adresse du serveur (ex. https://manette-minute.ton-nom.workers.dev)")
        val server = field(Store.server(this).ifEmpty { BuildConfig.DEFAULT_SERVER }, "https://…workers.dev")
        label("Code d'activation de la borne (affiché sur ta page de gestion)")
        val code = field(Store.code(this), "ex. CST7LUDF")
        label("Code PIN pour ce menu (4 chiffres minimum, obligatoire)")
        val pin = field(Store.pin(this), "ex. 4821", number = true)
        if (Store.borne(this).isNotEmpty()) label("Borne actuelle : ${Store.borne(this)}")
        button("✅ Activer la borne") {
            val sv = server.text.toString().trim(); val cd = code.text.toString().trim().uppercase(); val pn = pin.text.toString().trim()
            if (!sv.startsWith("https://")) { status.text = "❌ L'adresse du serveur doit commencer par https://"; return@button }
            if (pn.length < 4) { status.text = "❌ Choisis un code PIN d'au moins 4 chiffres"; return@button }
            bg {
                val r = Api.activate(sv, cd)
                if (!r.optBoolean("ok")) return@bg "❌ " + r.optString("error", "Code refusé")
                Store.save(this, sv, r.optString("borne"), cd, pn)
                val d = Api.device(this)
                if (!d.optBoolean("ok")) "❌ " + d.optString("error")
                else "✅ Borne « ${r.optString("name")} » activée sur ce Fire TV"
            }
        }

        title("1 · Connexion au Fire TV")
        label("Le débogage ADB doit être activé dans Paramètres > Mon Fire TV > Options pour les développeurs. " +
            "À la première connexion, coche « Toujours autoriser » puis OK.")
        button("🔌 Tester la connexion") {
            bg { Adb.reset(); if (Adb.ok(this)) "✅ Connexion au Fire TV OK" else "❌ Pas de connexion. Détail : " + Adb.lastError.ifEmpty { "inconnu" } + " — si une fenêtre « Autoriser » apparaît, coche « Toujours autoriser » puis réessaie" }
        }

        title("2 · Jeux de la borne")
        val apps = Launcher.installedGameApps(this)
        label(if (apps.isEmpty()) "⚠️ Aucune appli GeForce NOW ou Luna trouvée sur ce Fire TV. Installe-les d'abord."
              else "Applis trouvées : " + apps.joinToString { "${it.second} (${it.first})" })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        button("🔄 Charger les jeux depuis le serveur") {
            bg {
                val d = Api.device(this)
                if (!d.optBoolean("ok")) return@bg "❌ " + d.optString("error", "Borne non activée")
                val games = d.optJSONArray("games") ?: JSONArray()
                ui.post { list.removeAllViews(); for (i in 0 until games.length()) gameRow(list, games.getString(i), apps) }
                "✅ ${games.length()} jeux chargés"
            }
        }
        root.addView(list)

        title("3 · Revenir à la borne")
        button("▶️ Quitter les réglages") { finish() }
    }

    private fun gameRow(into: LinearLayout, game: String, apps: List<Pair<String, String>>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 24)
            setBackgroundColor(Color.parseColor("#1B1533"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 }
        }
        into.addView(box)
        val r = Store.recipe(this, game)
        TextView(this).apply {
            text = "🎮 $game" + (if (r != null) "  ·  ✅ lancement enregistré (${r.optJSONArray("steps")?.length() ?: 0} touches)" else "  ·  ⚠️ pas encore enregistré")
            textSize = 20f; setTextColor(Color.WHITE)
        }.also { box.addView(it) }

        label("Appli du jeu (nom du paquet)", box)
        val pkg = field(r?.optString("pkg") ?: "", "ex. com.nvidia.geforcenow", box)
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(quick)
        apps.forEach { (p, name) -> button(name, quick) { pkg.setText(p) } }

        label("Lien direct (facultatif, laisser vide en général)", box)
        val url = field(r?.optString("url") ?: "", "https://…", box)
        label("Secondes avant que le temps de jeu démarre (chargement du jeu)", box)
        val ready = field((r?.optInt("ready", 40) ?: 40).toString(), "40", box, number = true)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        box.addView(row)
        button("⏺️ Enregistrer le lancement", row) {
            val p = pkg.text.toString().trim()
            if (p.isEmpty()) { status.text = "❌ Choisis d'abord l'appli du jeu"; return@button }
            status.text = "L'appli va s'ouvrir. Attends 2 secondes, puis va jusqu'au jeu avec la télécommande et lance-le. " +
                "Quand la partie démarre, appuie sur ☰ (Menu)."
            Store.pause(6)
            val u = url.text.toString().trim(); val rd = ready.text.toString().toIntOrNull() ?: 40
            Thread {
                val msg = try {
                    Thread.sleep(3500)
                    val steps = Launcher.record(this, p, u)
                    Store.saveRecipe(this, game, Launcher.recipeOf(p, u, rd, steps))
                    "✅ « $game » enregistré : ${steps.length()} touches. Teste-le pour vérifier."
                } catch (e: Exception) { "❌ " + (e.message ?: e.toString()) }
                try { Adb.shell(this, "am start -n $packageName/.AdminActivity") } catch (_: Exception) {}
                ui.post { status.text = msg; rebuild() }
            }.start()
        }
        button("▶️ Tester", row) {
            val p = pkg.text.toString().trim()
            val old = Store.recipe(this, game)
            Store.saveRecipe(this, game, Launcher.recipeOf(p, url.text.toString().trim(),
                ready.text.toString().toIntOrNull() ?: 40, old?.optJSONArray("steps") ?: JSONArray()))
            Store.pause(3)
            Thread {
                val msg = try { Launcher.launch(this, game); "✅ Test lancé : la partie doit démarrer toute seule." }
                          catch (e: Exception) { "❌ " + (e.message ?: e.toString()) }
                ui.post { status.text = msg + "  (Reviens ici avec 5 × ☰ depuis l'accueil.)" }
            }.start()
        }
        button("💾", row) {
            val old = Store.recipe(this, game)
            Store.saveRecipe(this, game, Launcher.recipeOf(pkg.text.toString().trim(), url.text.toString().trim(),
                ready.text.toString().toIntOrNull() ?: 40, old?.optJSONArray("steps") ?: JSONArray()))
            status.text = "✅ Réglages de « $game » enregistrés"
        }
    }

    private fun rebuild() { root.removeAllViews(); build() }

    override fun onPause() {
        super.onPause()
        if (isFinishing) Store.resume()
    }

    @Suppress("unused") private fun hide(v: View) { v.visibility = View.GONE }
}
