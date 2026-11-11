package xyz.grtlabs.pilastri.sync.paypal

import xyz.grtlabs.pilastri.sync.usePageTransitions
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.ImportHandoff
import xyz.grtlabs.pilastri.sync.ProviderSyncPage
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.auto.InteractiveOutcome
import xyz.grtlabs.pilastri.sync.WebChrome
import xyz.grtlabs.pilastri.sync.WebHelpPoint
import xyz.grtlabs.pilastri.sync.WebIcons
import xyz.grtlabs.pilastri.sync.WebStatus
import xyz.grtlabs.pilastri.sync.WebToggle
import xyz.grtlabs.pilastri.sync.applyWebWindow
import xyz.grtlabs.pilastri.sync.installWebInsets
import xyz.grtlabs.pilastri.sync.webChromeView
import xyz.grtlabs.pilastri.ui.Ic

/**
 * PayPal's own pages, ONLY for signing in: the first link and the steps the automatic sync needs from the person (sign-in, a 2FA
 * code, a check). [ProviderSyncPage] keeps the page behind a progress cover while it opens PayPal's sign-in page and fills a saved
 * sign-in ("Ricorda l'accesso"), and shows it only when the person must act. As soon as the session is signed in the page closes
 * with its page-out slide ([RESULT_SIGNED_IN]) and the activity list is read in the background, offscreen
 * ([AutoSyncKit] / `WebRunner`): the person never watches PayPal pages navigate, never opens the statements page and never
 * downloads anything.
 *
 * The app does not try to get past a CAPTCHA or bot check (those are for the person to solve). Top-level navigation is
 * limited to https pages on paypal.com.
 *
 * Fallback: a download the person starts on the page is still captured through [WebView.setDownloadListener]: an https file
 * is fetched once with OkHttp, sending the WebView's cookies for PayPal hosts only and following redirects by hand so cookies
 * never leave paypal.com; a blob: file is read by a one-off script run in the page (a fetch of the blob handed back through a
 * nonce-checked bridge). The file is capped at 10 MB, parsed by [PaypalParser] and handed back as an [ImportHandoff] token
 * (the preview).
 *
 * UNVERIFIED against the live site in this form: no sign-in was made during development.
 *
 * Result: [RESULT_SIGNED_IN] (the background read is running: the provider state shows its progress), [RESULT_APPLIED],
 * RESULT_OK + [PaypalConnector.EXTRA_TOKEN], [PaypalConnector.RESULT_FAILED] + EXTRA_ERROR, [PaypalConnector.RESULT_SESSION_EXPIRED],
 * or RESULT_CANCELED (Back or the bar's button). Decode the last four with [PaypalConnector.parseResult] (it reads
 * [RESULT_SIGNED_IN] as Cancelled: nothing to preview).
 *
 * The top bar is the shared [WebChrome]: provider name, the host actually loaded, a "?" sheet with the explanations, the
 * desktop-site toggle, the load line and one state line.
 */
class PaypalWebActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var page: ProviderSyncPage
    private val chromeBar = WebChrome(
        provider = "PayPal",
        isProviderHost = PaypalConnector::isPaypalHost,
        foreignWarning = "Non è un sito PayPal: non inserire credenziali",
        help = listOf(
            WebHelpPoint(Ic.refresh, "Aggiornamento", "Accedi una volta: Pilastri legge da solo l'attività di PayPal, ora e ogni volta che apri l'app."),
            WebHelpPoint(Ic.shield, "Accesso", "Password e codici si inseriscono qui, sulla pagina di PayPal. Pilastri li conserva, cifrati nel telefono, solo con \"Ricorda l'accesso\" attivo in Collegamenti."),
            WebHelpPoint(WebIcons.lock, "Indirizzo", "Sotto il nome vedi il sito aperto. Si aprono solo pagine https di paypal.com."),
            WebHelpPoint(WebIcons.download, "File", "Se scarichi un report Attività da qui, Pilastri lo legge e ti mostra cosa importare."),
        ),
    )

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .build()

    private var mobileAgent = ""
    private var desktopAgent = ""
    private var desktopMode = false
    private val lock = Any()
    private var pendingBlob: PendingBlob? = null

    private class PendingBlob(val nonce: String, val fileName: String)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usePageTransitions()
        val surface = applyWebWindow()
        // The page shows account data: keep it out of screenshots and the recents thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // The person opened the page: the background run stops, so two pages never share the session.
        AutoSyncKit.coordinator(this).cancel(ImportProvider.PAYPAL)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        installWebInsets(root, chromeBar)

        webView = xyz.grtlabs.pilastri.sync.WebViewDebug.create(this)
        page = ProviderSyncPage(this, ImportProvider.PAYPAL, chromeBar, webView,
            manualHint = WebStatus.hint("Accedi e scarica il report Attività in CSV"), onDone = ::onSyncEnded)
        configureWebView()
        chromeBar.toggle = desktopToggle()

        val bar = webChromeView(this, chromeBar) { close() }
        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(page.coverView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        // The driver goes in before anything loads; the sign-in then opens PayPal's sign-in page and nothing else.
        page.install()
        page.start()
    }

    /** Back or the bar's button: a running sync ends as Cancelled, and the page closes at once. */
    private fun close() {
        page.close()
        finishWith(RESULT_CANCELED, null, null)
    }

    /**
     * Signed in: close at once (the read runs in the background). Saved: close. Preview: the token as before. Cancelled: close.
     * NeedsUser and Failed stay on the page (shown by [page]).
     */
    private fun onSyncEnded(outcome: InteractiveOutcome) {
        when (outcome) {
            is InteractiveOutcome.SignedIn -> finishWith(RESULT_SIGNED_IN, null, null)
            InteractiveOutcome.Applied -> finishWith(RESULT_APPLIED, null, null)
            is InteractiveOutcome.Preview -> finishWith(RESULT_OK, outcome.token, null)
            InteractiveOutcome.Cancelled -> finishWith(RESULT_CANCELED, null, null)
            is InteractiveOutcome.NeedsUser, is InteractiveOutcome.Failed -> Unit
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setGeolocationEnabled(false)

        // Present as the regular mobile browser, not as an embedded WebView ("; wv" and "Version/x.y" are the tells).
        val original = settings.userAgentString
        mobileAgent = original.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)*\\s?"), "")
        val chrome = Regex("Chrome/[\\d.]+").find(original)?.value ?: "Chrome/120.0.0.0"
        desktopAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        settings.userAgentString = mobileAgent

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(Bridge(this), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Top-level pages must be https on paypal.com; http, intent:, paypal:, tel:, file: and other sites are refused. A frame inside a
                // PayPal page may be https from elsewhere, so that a security challenge the person has to solve can still load.
                val https = request.url.scheme == "https"
                if (https && (!request.isForMainFrame || PaypalConnector.isPaypalHost(request.url.host))) return false
                if (request.isForMainFrame) {
                    chromeBar.status = WebStatus.blocked(
                        if (!https) "Collegamento non sicuro bloccato"
                        else "Pagina fuori da PayPal bloccata · ${request.url.host.orEmpty().take(60)}"
                    )
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                showHost(url, started = true)
                page.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                showHost(url, started = false)
                CookieManager.getInstance().flush()
                page.onPageFinished(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                chromeBar.showUrl(url)
                page.onUrlChanged(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) page.onLoadFailed()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                page.onBroken()
                finishWith(PaypalConnector.RESULT_FAILED, null, "La pagina di PayPal si è chiusa in modo imprevisto. Riprova.")
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // The load line follows the page only while the person sees it, not the hidden loads behind the cover.
            override fun onProgressChanged(view: WebView, newProgress: Int) { chromeBar.progress = if (page.covered) 100 else newProgress }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            onDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }
    }

    override fun onDestroy() {
        page.dispose()
        worker.shutdownNow()
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.destroy()
        super.onDestroy()
    }

    // ---- page state ----------------------------------------------------------------------------------------

    /** The bar's host line follows the page; a host outside PayPal turns it into a warning (see [WebChrome.showUrl]). */
    private fun showHost(url: String?, started: Boolean) {
        val host = url?.let { Uri.parse(it).host }
        if (!host.isNullOrEmpty() && PaypalConnector.isPaypalHost(host)) PaypalConnector.visitedHosts += host.lowercase()
        if (started) chromeBar.pageStarted(url) else chromeBar.showUrl(url)
    }

    private fun desktopToggle() = WebToggle("Versione desktop", WebIcons.desktop, desktopMode) { toggleDesktop() }

    private fun toggleDesktop() {
        desktopMode = !desktopMode
        webView.settings.userAgentString = if (desktopMode) desktopAgent else mobileAgent
        webView.settings.useWideViewPort = desktopMode
        webView.settings.loadWithOverviewMode = desktopMode
        chromeBar.toggle = desktopToggle()
        webView.reload()
    }

    // ---- download capture ----------------------------------------------------------------------------------

    private fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        if (!busy.compareAndSet(false, true)) return
        // The person chose a file: it replaces the automatic read of this visit.
        page.stop()
        if (contentLength > MAX_BYTES) return fail("Il file è troppo grande (oltre 10 MB): non sembra il report Attività.")
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val pageUrl = webView.url
        chromeBar.status = WebStatus.busy("Lettura del file")
        when {
            url.startsWith("blob:") -> readBlob(url, fileName, pageUrl)
            url.startsWith("https://") -> {
                val agent = userAgent?.takeIf { it.isNotBlank() } ?: webView.settings.userAgentString
                worker.execute { downloadHttps(url, agent, pageUrl, fileName) }
            }
            else -> fail("Questo tipo di download non è supportato. Scarica il report in formato CSV oppure scegli il file dall'app.")
        }
    }

    /** One-off script in the page: fetch the blob, read it as base64, and hand it to [Bridge] with the matching nonce. */
    private fun readBlob(url: String, fileName: String, pageUrl: String?) {
        if (!BLOB_URL.matches(url) || !PaypalConnector.isPaypalHost(pageUrl?.let { Uri.parse(it).host })) {
            return fail("Download bloccato: non proviene da una pagina PayPal.")
        }
        val nonce = UUID.randomUUID().toString()
        synchronized(lock) { pendingBlob = PendingBlob(nonce, fileName) }
        // url matches BLOB_URL (no quotes, backslashes or newlines); the nonce is a UUID.
        val script = "(function(u,n,m){var b=window.$BRIDGE_NAME;try{fetch(u).then(function(r){return r.blob()}).then(function(x){" +
            "if(x.size>m){b.fail(n,'size');return}var f=new FileReader();f.onloadend=function(){var s=String(f.result);b.deliver(n,s.substring(s.indexOf(',')+1))};" +
            "f.onerror=function(){b.fail(n,'read')};f.readAsDataURL(x)}).catch(function(){b.fail(n,'fetch')})}catch(e){b.fail(n,'js')}})('$url','$nonce',$MAX_BYTES);"
        webView.evaluateJavascript(script, null)
    }

    /** Receives a blob read by [readBlob]. Methods run on a WebView thread; a call without the live nonce is ignored. */
    class Bridge(private val activity: PaypalWebActivity) {
        @JavascriptInterface
        fun deliver(nonce: String?, base64: String?) {
            val pending = activity.takePending(nonce) ?: return
            val data = base64 ?: return activity.runOnUiThread { activity.fail("Impossibile leggere il file scaricato.") }
            if (data.length > MAX_BYTES / 3 * 4 + 16) return activity.runOnUiThread { activity.fail("Il file è troppo grande (oltre 10 MB): non sembra il report Attività.") }
            activity.worker.execute {
                val bytes = try { Base64.decode(data, Base64.DEFAULT) } catch (e: IllegalArgumentException) { null }
                if (bytes == null) activity.runOnUiThread { activity.fail("Impossibile leggere il file scaricato.") }
                else activity.deliverBytes(bytes, pending.fileName)
            }
        }

        @JavascriptInterface
        fun fail(nonce: String?, reason: String?) {
            activity.takePending(nonce) ?: return
            val message = if (reason == "size") "Il file è troppo grande (oltre 10 MB): non sembra il report Attività."
            else "Impossibile leggere il file scaricato da PayPal."
            activity.runOnUiThread { activity.fail(message) }
        }
    }

    private fun takePending(nonce: String?): PendingBlob? = synchronized(lock) {
        pendingBlob?.takeIf { it.nonce == nonce }?.also { pendingBlob = null }
    }

    private sealed interface Fetched {
        class Bytes(val data: ByteArray) : Fetched
        data object LoggedOut : Fetched
        class Error(val message: String) : Fetched
    }

    /** Worker thread. Cookies are attached per hop, only to PayPal hosts, and redirects are followed by hand. */
    private fun downloadHttps(url: String, agent: String, referer: String?, fileName: String) {
        val fetched = try { fetch(url, agent, referer) } catch (e: IOException) { Fetched.Error("Impossibile scaricare il file da PayPal. Controlla la connessione e riprova.") }
        when (fetched) {
            is Fetched.Bytes -> deliverBytes(fetched.data, fileName)
            Fetched.LoggedOut -> runOnUiThread { finishWith(PaypalConnector.RESULT_SESSION_EXPIRED, null, null) }
            is Fetched.Error -> runOnUiThread { fail(fetched.message) }
        }
    }

    private fun fetch(startUrl: String, agent: String, referer: String?): Fetched {
        var current = startUrl.toHttpUrlOrNull() ?: return Fetched.Error("Indirizzo di download non valido.")
        for (hop in 0..MAX_REDIRECTS) {
            if (current.scheme != "https" || !PaypalConnector.isPaypalHost(current.host)) {
                return Fetched.Error("Download bloccato: non proviene da un sito PayPal.")
            }
            val request = Request.Builder().url(current).header("User-Agent", agent).apply {
                CookieManager.getInstance().getCookie(current.toString())?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                referer?.let { header("Referer", it) }
            }.get().build()
            http.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) } ?: return Fetched.Error("Risposta di PayPal non valida.")
                    current = next
                    return@use
                }
                if (response.code == 401 || response.code == 403) return Fetched.LoggedOut
                if (!response.isSuccessful) return Fetched.Error("PayPal ha risposto con un errore (${response.code}). Riprova.")
                val type = response.header("Content-Type").orEmpty().lowercase()
                // A web page where a file was expected is the sign-in page: the session is gone.
                if (type.startsWith("text/html")) return Fetched.LoggedOut
                val body = response.body ?: return Fetched.Error("Risposta di PayPal vuota.")
                if (body.contentLength() > MAX_BYTES) return Fetched.Error("Il file è troppo grande (oltre 10 MB): non sembra il report Attività.")
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_BYTES) return Fetched.Error("Il file è troppo grande (oltre 10 MB): non sembra il report Attività.")
                        out.write(buffer, 0, n)
                    }
                }
                return Fetched.Bytes(out.toByteArray())
            }
        }
        return Fetched.Error("Troppi reindirizzamenti dal sito PayPal.")
    }

    /** Any thread. Parses and finishes on the UI thread. */
    private fun deliverBytes(bytes: ByteArray, fileName: String) {
        val batch: ImportBatch = try {
            PaypalParser.parse(bytes, fileName, Instant.now())
        } catch (e: IllegalArgumentException) {
            return runOnUiThread { fail(e.message ?: "Il file non è il report Attività di PayPal.") }
        }
        val token = ImportHandoff.put(batch)
        runOnUiThread { finishWith(RESULT_OK, token, null) }
    }

    private fun fail(message: String) = finishWith(PaypalConnector.RESULT_FAILED, null, message)

    private fun finishWith(resultCode: Int, token: String?, error: String?) {
        if (isFinishing) return
        setResult(resultCode, Intent().apply {
            token?.let { putExtra(PaypalConnector.EXTRA_TOKEN, it) }
            error?.let { putExtra(PaypalConnector.EXTRA_ERROR, it) }
        })
        finish()
    }

    companion object {
        /** The automatic sync saved the data through the app's archive: nothing to preview ([PaypalConnector.parseResult] reads it as Cancelled). */
        const val RESULT_APPLIED = Activity.RESULT_FIRST_USER + 2
        /**
         * Signed in; the page closed and the activity list is being read in the background ([AutoSyncKit] coordinator state).
         * [PaypalConnector.parseResult] reads it as Cancelled.
         */
        const val RESULT_SIGNED_IN = Activity.RESULT_FIRST_USER + 3

        private const val BRIDGE_NAME = "PilastriBridge"
        private const val MAX_BYTES = 10 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val BLOB_URL = Regex("blob:https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9-]+")
    }
}
