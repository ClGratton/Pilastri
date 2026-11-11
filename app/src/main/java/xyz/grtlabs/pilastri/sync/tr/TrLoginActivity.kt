package xyz.grtlabs.pilastri.sync.tr

import xyz.grtlabs.pilastri.sync.usePageTransitions
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.sync.ImportHandoff
import xyz.grtlabs.pilastri.sync.WebChrome
import xyz.grtlabs.pilastri.sync.WebChromeScaffold
import xyz.grtlabs.pilastri.sync.WebChromeTheme
import xyz.grtlabs.pilastri.sync.WebHelpPoint
import xyz.grtlabs.pilastri.sync.WebIcons
import xyz.grtlabs.pilastri.sync.applyWebWindow
import xyz.grtlabs.pilastri.ui.Ic
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Meter
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.fadeThrough

/**
 * Hosts Trade Republic's own web app in a WebView. Two modes (see [TrConnector]):
 *
 *  - LOGIN: the login page is shown; the user types phone number, PIN and the 2FA code into Trade Republic's page. When the
 *    page has left the login routes for a while the activity returns LOGGED_IN. Back cancels.
 *  - SYNC: a native progress screen covers a WebView that loads app.traderepublic.com. Once the page has settled (and has not
 *    redirected to its login) the relay script is injected and [TrSync] reads the account. A 401 switches to the visible
 *    login, once, and then the sync restarts.
 *
 * Nothing is injected into the login page and the app never touches its form fields; credentials and cookies never reach
 * Kotlin code. If the system recreates this activity (rotation, Fold unfolding) the work restarts from the beginning; the
 * manifest entry would need `android:configChanges` like MainActivity's to avoid that.
 */
class TrLoginActivity : ComponentActivity() {
    private enum class Screen { Login, Sync }

    private lateinit var web: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mode = MODE_SYNC
    private var sinceDays = 400
    private var screen by mutableStateOf(Screen.Sync)
    private val chrome = WebChrome(
        provider = "Trade Republic",
        isProviderHost = TrUrls::isTrHost,
        foreignWarning = "Non è un sito Trade Republic: non inserire i tuoi dati",
        help = listOf(
            WebHelpPoint(Ic.shield, "Accesso", "Numero, PIN e codice si scrivono nella pagina di Trade Republic: Pilastri non li legge né li salva."),
            WebHelpPoint(WebIcons.readOnly, "Sola lettura", "Pilastri legge movimenti, posizioni e saldo. Non invia ordini e non modifica il conto."),
            WebHelpPoint(WebIcons.preview, "Anteprima", "Prima di salvare qualsiasi dato vedi cosa verrà importato."),
            WebHelpPoint(WebIcons.lock, "Indirizzo", "Sotto il nome vedi il sito aperto: deve finire con traderepublic.com."),
        ),
    )
    /** WebChromeClient progress; the bar draws it. */
    private var pageProgress: Int
        get() = chrome.progress
        set(value) { chrome.progress = value }
    private var progress by mutableStateOf(TrProgress("Apertura di Trade Republic…"))

    private var bridge: TrWebBridge? = null
    private var bridgeToken = ""
    private var syncJob: Job? = null
    private var watcher: Job? = null
    private var awaitingPage = false
    private var sawLogin = false
    private var loginUsed = false
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usePageTransitions()
        applyWebWindow()
        mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_LOGIN) MODE_LOGIN else MODE_SYNC
        sinceDays = intent.getIntExtra(EXTRA_SINCE_DAYS, 400).coerceIn(1, 3660)
        web = xyz.grtlabs.pilastri.sync.WebViewDebug.create(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            // Trade Republic's web app refuses phones ("Scarica la nostra app…", no login form, seen on the Fold 2026-10-31), so the
            // page asks for the desktop site, as a browser's "Versione desktop" does: a desktop Chrome user agent with the same engine
            // version, no "wv" marker, no X-Requested-With app header, and client hints that say "not mobile". The user still signs in
            // on Trade Republic's own page; nothing here touches its login or any bot check.
            settings.userAgentString = desktopUserAgent(settings.userAgentString)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val current = WebSettingsCompat.getUserAgentMetadata(settings)
                WebSettingsCompat.setUserAgentMetadata(settings, androidx.webkit.UserAgentMetadata.Builder(current)
                    .setMobile(false).setPlatform("Linux").setPlatformVersion("").setModel("").build())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = pageClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { pageProgress = newProgress }
            }
        }
        setContent { WebChromeTheme { Content() } }
        if (mode == MODE_LOGIN) beginLogin() else beginSync()
    }

    /** A desktop Chrome user agent carrying the WebView's own Chrome version. */
    private fun desktopUserAgent(webViewAgent: String): String {
        val version = Regex("Chrome/([0-9.]+)").find(webViewAgent)?.groupValues?.get(1)?.substringBefore('.') ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version.0.0.0 Safari/537.36"
    }

    // ---- flow ------------------------------------------------------------------------------------------------------

    private fun stopWork() {
        syncJob?.cancel(); syncJob = null
        watcher?.cancel(); watcher = null
        bridge?.shutdown(); bridge = null
        awaitingPage = false
    }

    private fun beginLogin() {
        stopWork()
        // The relay is never present on the login page: remove it before the page loads and inject nothing here.
        web.removeJavascriptInterface(TrBridgeScript.INTERFACE)
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        screen = Screen.Login
        chrome.closeCancels = false
        chrome.closeLabel = "Torna a Pilastri"
        sawLogin = false
        web.loadUrl(TrUrls.LOGIN_URL)
        watcher = scope.launch {
            val startedAt = System.nanoTime()
            var stableSince = 0L
            while (true) {
                delay(500)
                val now = System.nanoTime()
                // The WebView may still report the previous page right after loadUrl: wait to have seen the login (or 4 s).
                val settled = sawLogin || (now - startedAt) > 4_000_000_000L
                val url = web.url
                val out = settled && pageProgress == 100 && TrUrls.looksLoggedIn(url, TrUrls.hasSessionCookie(TrConnector.cookieNames()))
                if (!out) { stableSince = 0L; continue }
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= 1_500_000_000L) break
            }
            CookieManager.getInstance().flush()
            if (mode == MODE_LOGIN) finishWith(true, TrResult.LOGGED_IN) else beginSync()
        }
    }

    private fun beginSync() {
        stopWork()
        screen = Screen.Sync
        chrome.closeCancels = true
        chrome.closeLabel = "Annulla la sincronizzazione"
        progress = TrProgress("Apertura di Trade Republic…")
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        bridgeToken = UUID.randomUUID().toString()
        val b = TrWebBridge(web, bridgeToken)
        bridge = b
        web.removeJavascriptInterface(TrBridgeScript.INTERFACE)
        web.addJavascriptInterface(b, TrBridgeScript.INTERFACE)
        awaitingPage = true
        web.loadUrl(TrUrls.APP_URL)
    }

    /** Called when a main-frame load finishes. In SYNC the first page that is not a login route starts the work. */
    private fun onPageFinished(url: String?) {
        if (finished || screen != Screen.Sync || !awaitingPage) return
        when (TrUrls.classify(url)) {
            TrUrls.Page.LOGIN, TrUrls.Page.PRE_LOGIN -> { awaitingPage = false; needLogin() }
            TrUrls.Page.ROOT, TrUrls.Page.APP -> { awaitingPage = false; startSync() }
            TrUrls.Page.OTHER -> Unit
        }
    }

    private fun needLogin() {
        if (loginUsed) { finishWith(true, TrResult.EXPIRED); return }
        loginUsed = true
        beginLogin()
    }

    private fun startSync() {
        val relay = bridge ?: return
        val token = bridgeToken
        syncJob = scope.launch {
            // Let the page's own scripts finish (WAF token, redirect to the login when signed out) before injecting.
            delay(SETTLE_MS)
            val page = TrUrls.classify(web.url)
            if (page == TrUrls.Page.LOGIN || page == TrUrls.Page.PRE_LOGIN) { needLogin(); return@launch }
            web.evaluateJavascript(TrBridgeScript.source(token), null)
            try {
                val batch = withContext(Dispatchers.Default) {
                    TrSync(relay, TrConfig(sinceDays = sinceDays)).run { progress = it }
                }
                finishWith(true, TrResult.IMPORTED, token = ImportHandoff.put(batch))
            } catch (e: CancellationException) {
                throw e
            } catch (_: TrError.SessionExpired) {
                needLogin()
            } catch (e: TrError) {
                finishWith(true, TrResult.FAILED, message = e.message)
            } catch (_: Exception) {
                finishWith(true, TrResult.FAILED, message = "Sincronizzazione con Trade Republic non riuscita.")
            }
        }
    }

    private fun finishWith(ok: Boolean, kind: String? = null, token: String? = null, message: String? = null) {
        if (finished) return
        finished = true
        stopWork()
        CookieManager.getInstance().flush()
        if (ok) {
            setResult(RESULT_OK, Intent().putExtra(TrResult.KIND, kind).putExtra(TrResult.TOKEN, token).putExtra(TrResult.MESSAGE, message))
        } else setResult(RESULT_CANCELED)
        finish()
    }

    // ---- web view --------------------------------------------------------------------------------------------------

    private fun pageClient() = object : WebViewClient() {
        // Only https pages on traderepublic.com: the login page of a bank is not a place to wander off.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !TrUrls.isAllowedNavigation(request.url.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            chrome.pageStarted(url)
            if (screen == Screen.Login && TrUrls.classify(url) == TrUrls.Page.LOGIN) sawLogin = true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            chrome.showUrl(url)
            if (screen == Screen.Login && TrUrls.classify(url) == TrUrls.Page.LOGIN) sawLogin = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            chrome.showUrl(url)
            CookieManager.getInstance().flush()
            this@TrLoginActivity.onPageFinished(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && screen == Screen.Sync && awaitingPage) {
                finishWith(true, TrResult.FAILED, message = "Impossibile raggiungere Trade Republic. Controlla la connessione.")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            finishWith(true, TrResult.FAILED, message = "Il componente web del telefono si è interrotto: riprova.")
            return true
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        bridge?.shutdown()
        web.removeJavascriptInterface(TrBridgeScript.INTERFACE)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---- UI --------------------------------------------------------------------------------------------------------

    /**
     * One bar for both phases (back arrow while signing in, a cross while the sync runs; "?" holds the explanations). Below
     * it the WebView stays mounted in both phases, because the sync runs inside the page; the sync screen crossfades over it.
     */
    @Composable
    private fun Content() {
        val reduce = LocalReduceMotion.current
        BackHandler { finishWith(false) }
        WebChromeScaffold(chrome, onClose = { finishWith(false) }, showProgress = screen == Screen.Login) {
            key("tr-web") { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
            AnimatedVisibility(
                visible = screen == Screen.Sync,
                enter = fadeIn(tween(if (reduce) 120 else 220)), exit = fadeOut(tween(if (reduce) 80 else 180)),
                label = "sync-cover",
            ) { SyncCover() }
        }
    }

    /** The sync phase: the current step is the one primary line, a meter under it, and one caption with the count. */
    @Composable
    private fun SyncCover() {
        val pal = LocalPal.current
        val reduce = LocalReduceMotion.current
        val p = progress
        val step = p.label.removeSuffix("…").removeSuffix("...")
        val caption = when {
            p.total > 0 -> "${p.done} di ${p.total}"
            p.done > 0 -> "${p.done} movimenti letti"
            else -> "Sola lettura"
        }
        // Surface swallows touches, so the covered page cannot be tapped by accident.
        Surface(Modifier.fillMaxSize(), color = pal.bg) {
            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(step, transitionSpec = fadeThrough(reduce), label = "step") { s ->
                        Text(s, style = Type.title, color = pal.ink, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Spacer(Modifier.height(20.dp))
                    Crossfade(p.total > 0, label = "meter") { counted ->
                        if (counted) Meter(p.done.toFloat() / p.total, track = pal.track, fill = pal.fill, height = 6.dp)
                        else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = pal.fill, trackColor = pal.track,
                            strokeCap = StrokeCap.Round, gapSize = 0.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(caption, style = Type.body.copy(fontFeatureSettings = "tnum"), color = pal.ink2, textAlign = TextAlign.Center)
                }
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "xyz.grtlabs.pilastri.sync.tr.MODE"
        const val EXTRA_SINCE_DAYS = "xyz.grtlabs.pilastri.sync.tr.SINCE_DAYS"
        const val MODE_LOGIN = "login"
        const val MODE_SYNC = "sync"
        private const val SETTLE_MS = 1_500L
    }
}
