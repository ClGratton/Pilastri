package xyz.grtlabs.pilastri.sync.auto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LoginDriver] over a WebView: the activities' visible WebView (with a [Surface] that shows or covers it), or an offscreen one
 * ([offscreen]) for the background sync.
 *
 * Integration in an activity that owns its WebView and WebViewClient:
 *  - construct it after configuring the WebView and call [install] BEFORE the first `loadUrl`;
 *  - forward from the WebViewClient: `onPageStarted` -> [onPageStarted], `onPageFinished` -> [onPageFinished],
 *    `doUpdateVisitedHistory` -> [onUrlChanged], main-frame `onReceivedError` -> [onLoadFailed], `onRenderProcessGone` -> [onBroken];
 *  - Back / "Annulla" -> [onClosed]; `onDestroy` -> [dispose].
 * Or use [client], which does all of that and allows only https pages of the provider in the main frame.
 *
 * Messages are accepted only from the main frame of an exact login origin ([ProviderSite.loginOrigins]); credentials are posted only
 * through the reply proxy of the frame that reported the form. All WebView calls happen on the main thread.
 */
class WebLoginDriver(
    private val web: WebView,
    val site: ProviderSite,
    private val surface: Surface? = null,
    rules: LoginRules = LoginRulesTable.forProvider(site.provider),
    private val loadTimeoutMs: Long = 30_000,
    private val evaluateTimeoutMs: Long = 10_000,
) : LoginDriver {
    /** The activity's cover: [reveal] shows the provider page with the one-line reason, [cover] hides it behind progress. */
    interface Surface {
        fun reveal(reason: AttentionReason)
        fun cover()
    }

    private val main = Handler(Looper.getMainLooper())
    private val events = Channel<LoginEvent>(Channel.UNLIMITED)
    private val script = LoginAutofill.script(rules)
    @Volatile private var lastUrl: String? = null
    @Volatile private var disposed = false
    private var reply: JavaScriptReplyProxy? = null
    private var capture = false
    private var startScript: ScriptHandler? = null
    private var injectOnFinish = false
    private var pendingLoad: CompletableDeferred<String?>? = null

    override val canReveal: Boolean get() = surface != null

    /**
     * Installs the message bridge and the document-start script for the exact login origins. False when this WebView is too old for
     * web message listeners: then nothing is installed and the page can still be used by hand.
     */
    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(web, LoginAutofill.BRIDGE, site.loginOrigins, listener)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            startScript = WebViewCompat.addDocumentStartJavaScript(web, script, site.loginOrigins)
        } else {
            injectOnFinish = true
        }
        return true
    }

    // ---- forwarded from the WebViewClient (main thread) ----

    fun onPageStarted(url: String?) { lastUrl = url }

    fun onPageFinished(url: String?) {
        lastUrl = url
        if (injectOnFinish && site.isOnLoginOrigin(url)) web.evaluateJavascript(script, null)
        pendingLoad?.complete(url)
        pendingLoad = null
        if (url != null) events.trySend(LoginEvent.Page(url))
    }

    fun onUrlChanged(url: String?) {
        if (url == null || url == lastUrl) return
        lastUrl = url
        events.trySend(LoginEvent.Page(url))
    }

    fun onLoadFailed() {
        pendingLoad?.complete(null)
        pendingLoad = null
    }

    fun onClosed() { events.trySend(LoginEvent.Closed) }

    fun onBroken(message: String = "Il componente web del telefono si è interrotto: riprova.") {
        pendingLoad?.complete(null)
        events.trySend(LoginEvent.Broken(message))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        events.close()
        runCatching { startScript?.remove() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) runCatching { WebViewCompat.removeWebMessageListener(web, LoginAutofill.BRIDGE) }
        reply = null
    }

    /** A client that forwards everything above and refuses main-frame pages that are not https pages of the provider. */
    fun client(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && !site.isProviderUrl(request.url.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = this@WebLoginDriver.onPageStarted(url)
        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
            this@WebLoginDriver.onPageFinished(url)
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onUrlChanged(url)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onLoadFailed()
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onBroken()
            return true
        }
    }

    private val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
        if (disposed || !isMainFrame || !site.isLoginOrigin(sourceOrigin.toString())) return@WebMessageListener
        val parsed = LoginAutofill.parse(message.data) ?: return@WebMessageListener
        reply = replyProxy
        val event: LoginEvent? = when (parsed) {
            LoginAutofill.PageMessage.Hello -> { if (capture) replyProxy.postMessage(LoginAutofill.captureMessage(true)); null }
            is LoginAutofill.PageMessage.Form -> LoginEvent.Form(parsed.step)
            is LoginAutofill.PageMessage.Sca -> LoginEvent.Sca(parsed.kind)
            LoginAutofill.PageMessage.Challenge -> LoginEvent.Challenge
            LoginAutofill.PageMessage.Error -> LoginEvent.LoginError
            is LoginAutofill.PageMessage.Captured -> if (capture) LoginEvent.Captured(parsed.username, parsed.password) else null
            LoginAutofill.PageMessage.Nothing, LoginAutofill.PageMessage.Stale, is LoginAutofill.PageMessage.Submitted -> LoginEvent.Quiet
        }
        if (event != null) events.trySend(event)
    }

    // ---- LoginDriver ----

    override fun currentUrl(): String? = lastUrl

    override fun open(url: String) {
        require(site.isProviderUrl(url)) { "Pagina fuori dal sito del fornitore." }
        main.post { if (!disposed) web.loadUrl(url) }
    }

    override suspend fun load(url: String): String? {
        require(site.isProviderUrl(url)) { "Pagina fuori dal sito del fornitore." }
        val done = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            pendingLoad?.complete(null)
            pendingLoad = done
            web.loadUrl(url)
        }
        return withTimeoutOrNull(loadTimeoutMs) { done.await() }
    }

    override suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        if (disposed || !site.isProviderUrl(web.url)) return@withContext null
        withTimeoutOrNull(evaluateTimeoutMs) {
            suspendCancellableCoroutine { cont -> web.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result) } }
        }
    }

    override suspend fun nextEvent(): LoginEvent = try {
        events.receive()
    } catch (_: ClosedReceiveChannelException) {
        LoginEvent.Closed
    }

    override fun fill(step: LoginStep, login: SavedLogin) {
        val message = LoginAutofill.fillMessage(step, login)
        main.post { if (!disposed) reply?.postMessage(message) }
    }

    override fun setCapture(on: Boolean) {
        main.post {
            capture = on
            if (!disposed) reply?.postMessage(LoginAutofill.captureMessage(on))
        }
    }

    override fun reveal(reason: AttentionReason) { main.post { surface?.reveal(reason) } }
    override fun cover() { main.post { surface?.cover() } }

    companion object {
        /**
         * A WebView that is never attached to a window, for the background sync: laid out at a phone size so pages render and
         * the autofill script can see its fields. Main thread only. It shares the app's cookie jar with the visible pages.
         */
        @SuppressLint("SetJavaScriptEnabled")
        fun offscreenWebView(context: Context, desktop: Boolean = false): WebView {
            val web = xyz.grtlabs.pilastri.sync.WebViewDebug.create(context.applicationContext)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                userAgentString = if (desktop) desktopAgent(userAgentString) else mobileAgent(userAgentString)
                if (desktop) { useWideViewPort = true; loadWithOverviewMode = true }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val density = context.resources.displayMetrics.density
            val w = (412 * density).toInt()
            val h = (915 * density).toInt()
            web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, w, h)
            return web
        }

        /** The same agent the PayPal and isybank pages use: the regular mobile browser, without the WebView tells. */
        fun mobileAgent(original: String): String = original.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)*\\s?"), "")

        fun desktopAgent(original: String): String {
            val version = Regex("Chrome/([0-9.]+)").find(original)?.groupValues?.get(1)?.substringBefore('.') ?: "130"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version.0.0.0 Safari/537.36"
        }
    }
}
