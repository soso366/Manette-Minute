package app.manetteminute

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/** Écran d'accueil de la borne : la page « Prêt à jouer ? » du serveur, en plein écran. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var info: TextView
    private var loadedUrl = ""
    private var menuCount = 0
    private var menuFirst = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.parseColor("#0F0B1F"))
            webViewClient = WebViewClient()
            isFocusable = false
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            text = "Manette Minute\n\nAppuie 5 fois sur ☰ (Menu) pour configurer la borne."
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply { addView(web); addView(info) })
        KioskService.start(this)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        if (!Store.isConfigured(this)) {
            info.visibility = View.VISIBLE
            return
        }
        info.visibility = View.GONE
        val url = "${Store.server(this)}/tv/${Store.borne(this)}?app=1"
        if (url != loadedUrl) { loadedUrl = url; web.loadUrl(url) }
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val now = System.currentTimeMillis()
            if (now - menuFirst > 4000) { menuFirst = now; menuCount = 0 }
            menuCount++
            if (menuCount >= 5) { menuCount = 0; startActivity(Intent(this, AdminActivity::class.java)) }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true // on ne quitte pas la borne
        return super.onKeyDown(keyCode, event)
    }
}
