// Adapted from the user's own Cattedra app (registro_elettronico/nextapp/.../AiWebActivity.kt, sign-in mode): the provider's
// page in a WebView, sign-in popups kept inside, the session noticed from the cookie NAME only. Same author; Cattedra's
// repository is CC BY-NC-SA 4.0 and its next-app licence is unresolved (see COUNCIL.md). Rewritten for Pilastri.
package xyz.grtlabs.pilastri.ai

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import xyz.grtlabs.pilastri.sync.WebChrome
import xyz.grtlabs.pilastri.sync.WebChromeScaffold
import xyz.grtlabs.pilastri.sync.WebChromeTheme
import xyz.grtlabs.pilastri.sync.WebHelpPoint
import xyz.grtlabs.pilastri.sync.WebIcons
import xyz.grtlabs.pilastri.sync.applyWebWindow
import xyz.grtlabs.pilastri.ui.Ic
import xyz.grtlabs.pilastri.ui.LocalPal

/**
 * Sign-in to the user's Claude account, on the provider's own site, under the app's shared provider-page bar
 * ([WebChromeScaffold]: back button, provider name, lock and host line, "?" sheet, progress line). The user types credentials, 2FA codes and
 * any CAPTCHA or bot check on the provider's page; the app injects nothing here, never reads a field and never solves a
 * challenge. It watches only whether the session cookie NAME exists and, once present for 1.5 s, finishes with RESULT_OK.
 */
class AiWebActivity : ComponentActivity() {
    private lateinit var account: WebAccount
    private var web: WebView? = null
    private var popup by mutableStateOf<WebView?>(null)
    private lateinit var chrome: WebChrome

    private fun chromeFor(account: WebAccount) = WebChrome(
        provider = account.label,
        // Sign-in may pass through Google, Apple or Microsoft: those hosts are expected here, anything else is flagged.
        isProviderHost = { host -> host != null && account.matches(host, account.signInHosts) },
        foreignWarning = "Non è una pagina di accesso di ${account.label}: non inserire i tuoi dati",
        help = listOf(
            WebHelpPoint(Ic.shield, "Accesso", "Email, password e codici si scrivono nella pagina di ${account.label}: Pilastri non li legge né li salva."),
            WebHelpPoint(WebIcons.lock, "Indirizzo", "Sotto il nome vedi il sito aperto: ${account.hosts.first()}, o il servizio con cui accedi (Google, Apple, Microsoft)."),
            WebHelpPoint(Ic.warning, "Verifiche", "Se compare un CAPTCHA o una verifica, la completi tu: l'app non la tocca."),
            WebHelpPoint(Ic.check, "Fine", "Pilastri nota solo che l'accesso è fatto, dal nome del cookie di sessione, e chiude questa pagina da sola."),
        ),
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        // The phone's password manager may fill the provider's sign-in fields; the app never sees them.
        w.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
        // Google sign-in refuses embedded views; the user's own account in an ordinary-browser view (as in Cattedra).
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
        }
        w.settings.setSupportMultipleWindows(true)
        w.settings.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
    }

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme != "https" && url.scheme != "http") return true
            // Sign-in can pass through identity and bot-check providers: they stay here. Anything else opens outside.
            if (!account.matches(url.host.orEmpty(), account.signInHosts)) {
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: ActivityNotFoundException) { }
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { chrome.pageStarted(url) }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { chrome.showUrl(url) }
        override fun onPageFinished(view: WebView, url: String?) { chrome.showUrl(url) }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { finish(); return true }
    }

    private fun chromeClient() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { chrome.progress = newProgress }

        // Google, Apple or Microsoft sign-in opens a window: it stays inside, on top.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val pw = xyz.grtlabs.pilastri.sync.WebViewDebug.create(this@AiWebActivity)
            configure(pw)
            pw.webViewClient = client()
            pw.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) { if (popup === window) popup = null; window.destroy() }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = pw
            resultMsg.sendToTarget()
            val before = popup
            popup = pw
            if (before != null && before !== pw) before.destroy()
            return true
        }
    }

    private fun sessionOk() = WebChatSession.hasSession(account)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = WebAccount.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ACCOUNT) } ?.takeIf { it.webSignIn } ?: run { finish(); return }
        chrome = chromeFor(account)
        applyWebWindow()
        setContent {
            WebChromeTheme {
                BackHandler { if (popup != null) { popup?.destroy(); popup = null } else finish() }
                LaunchedEffect(Unit) {
                    var since = 0L
                    while (true) {
                        delay(500)
                        if (sessionOk()) {
                            if (since == 0L) since = SystemClock.elapsedRealtime()
                            if (SystemClock.elapsedRealtime() - since >= 1_500L) {
                                CookieManager.getInstance().flush()
                                setResult(RESULT_OK)
                                finish()
                                return@LaunchedEffect
                            }
                        } else since = 0L
                    }
                }
                WebChromeScaffold(chrome, onClose = { finish() }) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            xyz.grtlabs.pilastri.sync.WebViewDebug.create(ctx).also { w ->
                                web = w
                                w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                configure(w)
                                w.webViewClient = client()
                                w.webChromeClient = chromeClient()
                                w.loadUrl(account.siteUrl)
                            }
                        },
                    )
                    popup?.let { pw -> AndroidView(modifier = Modifier.fillMaxSize().background(LocalPal.current.bg), factory = { pw }) }
                }
            }
        }
    }

    // Session cookies reach the disk when the screen leaves, so the sign-in survives.
    override fun onPause() { CookieManager.getInstance().flush(); super.onPause() }

    override fun onDestroy() { popup?.destroy(); popup = null; web?.destroy(); web = null; super.onDestroy() }

    companion object {
        private const val EXTRA_ACCOUNT = "pilastri.ai.account"

        fun loginIntent(context: Context, account: WebAccount): Intent =
            Intent(context, AiWebActivity::class.java).putExtra(EXTRA_ACCOUNT, account.name)
    }
}
