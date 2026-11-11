package xyz.grtlabs.pilastri.sync.eb

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import xyz.grtlabs.pilastri.sync.WebViewDebug

/**
 * The one hidden WebView on enablebanking.com ([EbPage]). Created through [WebViewDebug]; its cookies and storage persist in the
 * app's private WebView profile and are flushed after sign-in. JS runs in the page and leaves only its small result: an async
 * body's result is parked in `window.__pl[id]` and read back by polling, so nothing is exposed to the page (no JS interface).
 * Not unit-testable (no WebView on the JVM): [EbPanel] is tested with a fake page.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewEbPage(context: Context) : EbPage {
    private val host: Context = context
    val view: WebView = WebViewDebug.createLight(context).apply {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        if (android.os.Build.VERSION.SDK_INT >= 29) @Suppress("DEPRECATION") { settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF }
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean = dialog("alert", message, result, null)
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean = dialog("confirm", message, result, null)
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean = dialog("prompt", message, result, result)
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                EbLog.line(EbLog.EB, "console level=${m.messageLevel()} length=${m.message()?.length ?: 0} host=${hostOf(m.sourceId())}")
                return true
            }
            // A popup (window.open) loads in the same view: nothing the bank opens is lost.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                EbLog.line(EbLog.EB, "window requested host=${hostOf(current)} gesture=$isUserGesture")
                val t = resultMsg.obj as? WebView.WebViewTransport ?: return false
                t.webView = WebViewDebug.createLight(view.context).also { w ->
                    w.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { view.loadUrl(r.url.toString()); w.destroy(); return true }
                    }
                }
                resultMsg.sendToTarget()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                // A bank's app switch (intent:// or its own scheme): hand it to the bank app when one is installed.
                openExternal(view.context, request.url.toString())
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) EbLog.line(EbLog.EB, "subresource error host=${request.url.host} path=${shape(request.url.path)} code=${error.errorCode}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame) EbLog.line(EbLog.EB, "subresource error host=${request.url.host} path=${shape(request.url.path)} code=${response.statusCode}")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                EbLog.line(EbLog.EB, "render process gone crashed=${detail.didCrash()}")
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { current = url }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { current = url }
        }
    }

    @Volatile private var current: String? = null
    private val ids = AtomicInteger()

    /** What the host shows: null keeps the page offscreen. Snapshot state, read by the setup screens. */
    var sheet by mutableStateOf<EbSheet?>(null)
        private set
    var sheetTitle by mutableStateOf<String?>(null)
        private set

    override suspend fun load(url: String) = withContext(Dispatchers.Main) { current = url; view.loadUrl(url) }

    override fun url(): String? = current

    override fun show(sheet: EbSheet?, title: String?) {
        val opened = sheet != null && this.sheet == null
        this.sheet = sheet; sheetTitle = title
        if (opened) view.postDelayed({ logSheet() }, 500)
    }

    /** One line per sheet: the page's viewport in dp, night yes/no, the WebView's version. No address, no value. */
    private fun logSheet() {
        val d = view.resources.displayMetrics.density
        val night = (host.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val v = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.versionName else null
        EbLog.line(EbLog.EB, "sheet viewport=${(view.width / d).toInt()}x${(view.height / d).toInt()}dp night=${if (night) "yes" else "no"} webview=${v ?: "?"}")
    }

    private fun hostOf(url: String?): String = runCatching { Uri.parse(url).host }.getOrNull() ?: "?"

    /** A path with its long (token-like) segments cut: host and shape only. */
    private fun shape(path: String?): String = (path ?: "").split('/').joinToString("/") { if (it.length > 24) "…" else it }

    /** A JS dialog is shown, not dropped (the bank may ask its confirmation this way) and logged by kind only. */
    private fun dialog(kind: String, message: String?, result: JsResult, prompt: JsPromptResult?): Boolean {
        EbLog.line(EbLog.EB, "dialog requested kind=$kind length=${message?.length ?: 0}")
        return try {
            val b = android.app.AlertDialog.Builder(host).setMessage(message).setOnCancelListener { result.cancel() }
            val input = if (prompt != null) android.widget.EditText(host).also { b.setView(it) } else null
            b.setPositiveButton(android.R.string.ok) { _, _ -> if (prompt != null) prompt.confirm(input?.text?.toString().orEmpty()) else result.confirm() }
            if (kind != "alert") b.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            b.show(); true
        } catch (_: Exception) { result.cancel(); true }
    }

    override fun persist() { CookieManager.getInstance().flush() }

    override suspend fun diagnose(): JSONObject? = run(EbPanelScripts.DIAG)

    override suspend fun run(body: String, args: JSONObject): JSONObject? {
        val id = "p" + ids.incrementAndGet()
        val start = "(function(){var ARGS=$args;window.__pl=window.__pl||{};window.__pl['$id']=null;" +
            "(async function(){$body})().then(function(r){window.__pl['$id']=JSON.stringify(r==null?{}:r)}," +
            "function(e){window.__pl['$id']=JSON.stringify({err:String(e&&e.message||e).slice(0,160)})});return 1;})()"
        eval(start) ?: return null
        val deadline = SystemClock.elapsedRealtime() + RUN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            // "gone": the document was replaced after the script started (a navigation), so its result will never arrive.
            val raw = eval("(function(){var p=window.__pl;if(!p||!('$id' in p))return 'gone';var r=p['$id'];if(r!=null){delete p['$id'];return r;}return null;})()")
            val text = raw?.takeIf { it != "null" }?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (text == GONE) { EbLog.line(EbLog.EB, "script result gone: the page changed under it"); return JSONObject().put("code", "gone") }
            if (text != null) return runCatching { JSONObject(text) }.getOrNull()
            delay(100)
        }
        return null
    }

    /**
     * One evaluation, with its own bound: WebView does not call back when the document is replaced under a pending evaluation
     * (Fold, 2026-11-10 14:05: the sign-in finished in the page while the flow waited on a callback that never came). Null: no answer.
     */
    private suspend fun eval(js: String): String? = withContext(Dispatchers.Main) {
        var answered = false
        val r = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { c -> view.evaluateJavascript(js) { answered = true; if (c.isActive) c.resume(it) } }
        }
        if (!answered) EbLog.line(EbLog.EB, "evaluation unanswered ms=$EVAL_TIMEOUT_MS")
        r
    }

    fun destroy() { view.stopLoading(); view.destroy() }

    private fun openExternal(context: Context, url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { } catch (_: Exception) { }
    }

    companion object {
        const val RUN_TIMEOUT_MS = 20_000L
        const val EVAL_TIMEOUT_MS = 3_000L
        private const val GONE = "gone"
    }
}
