package dev.techo5.cast.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import dev.techo5.cast.extract.YtDlpExtractor

/**
 * Signs in to Google on Google's own page, in a WebView, and keeps only the session cookies, written as
 * the cookies.txt yt-dlp reads. The app never sees the password. YouTube answers "sign in to confirm
 * you're not a bot" to addresses it has rate-limited; yt-dlp passes these cookies to get past that.
 */
class SignInActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        // A fresh start, so a second sign-in can be another account rather than the one already in the WebView.
        cookies.removeAllCookies(null)
        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Google refuses sign-in in embedded browsers it recognises by "; wv" and "Version/4.0".
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val host = url.toUri().host.orEmpty()
                    if (host == "www.youtube.com" || host == "m.youtube.com") finishIfSignedIn()
                }
            }
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F")
    }

    private fun finishIfSignedIn() {
        val cookies = CookieManager.getInstance()
        cookies.flush()
        val youtube = pairs(cookies.getCookie("https://www.youtube.com"))
        if (youtube.none { it.first == "SAPISID" || it.first == "__Secure-3PSID" }) return
        val google = pairs(cookies.getCookie("https://accounts.google.com")) + pairs(cookies.getCookie("https://www.google.com"))
        // CookieManager hands over names and values only; give every cookie a year and the whole site.
        val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        fun lines(domain: String, list: List<Pair<String, String>>) =
            list.distinctBy { it.first }.map { "$domain\tTRUE\t/\tTRUE\t$expires\t${it.first}\t${it.second}" }
        val text = (listOf("# Netscape HTTP Cookie File") + lines(".youtube.com", youtube) + lines(".google.com", google)).joinToString("\n")
        val ok = YtDlpExtractor.saveCookies(this, text)
        Toast.makeText(this, if (ok) "Signed in" else "Sign-in did not give any cookies", Toast.LENGTH_LONG).show()
        if (ok) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun pairs(header: String?): List<Pair<String, String>> =
        header.orEmpty().split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }

    override fun onDestroy() {
        web.destroy()
        // The cookies live in yt-dlp's file now; the WebView keeps no second copy of the session.
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }
}
