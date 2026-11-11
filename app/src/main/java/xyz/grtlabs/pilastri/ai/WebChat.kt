// The account chat adapted from the user's own Cattedra app (registro_elettronico/nextapp: ChatActivity.kt, AiWebActivity.kt,
// data/Assistant.kt AssistLaunch): WebView set-up, cookie-name session checks, the hidden-page touch shield and /logout
// block learned from Cattedra's 2026-10-02 incident, and the clipboard handoff. Same author; Cattedra's repository is
// CC BY-NC-SA 4.0 and its next-app licence is unresolved (see COUNCIL.md). Rewritten for Pilastri.
package xyz.grtlabs.pilastri.ai

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.coroutines.resume

/**
 * The provider's own chat page (claude.ai or chatgpt.com) in a WebView kept by [AssistantState] so the conversation
 * survives tab switches. The page normally sits under the native Agente chat, covered and touch-shielded; it is shown
 * when the user asks, or when a sign-in or a CAPTCHA/bot check needs a person (the app never automates those).
 *
 * Consumer terms: scripting claude.ai or chatgpt.com may conflict with those sites' terms on automated access; the API keys
 * and the routine endpoint are the supported routes. Every [ask] starts from the user's tap on "Invia"; nothing here sends
 * on a timer or in the background.
 *
 * Only cookie names are read (never values); no credential, cookie or page content enters the archive, a backup or a log.
 */
class WebChatSession(val account: WebAccount, context: Context) {
    private val wrapper = MutableContextWrapper(context)
    private val mock = DebugMock.page(context)
    private val script = context.assets.open("pilastri_inject.js").bufferedReader().use { it.readText() }
    private val origins: Set<String> = buildSet {
        add(account.cookieUrl)
        if (mock != null) add(DebugMock.ORIGIN)
    }

    /** False when this device's WebView cannot carry page messages safely (origin-checked listener). */
    val supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    var snap by mutableStateOf<PageSnap?>(null)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var pageUrl by mutableStateOf("")
        private set
    private var shownState by mutableStateOf(false)

    /**
     * The page is on screen (only for a challenge the user must solve) and takes touches and focus; covered, it takes
     * neither (Cattedra: the hidden page grabbed focus), and TalkBack does not reach it either.
     */
    var shown: Boolean
        get() = shownState
        set(value) {
            shownState = value
            web.isFocusable = value
            web.isFocusableInTouchMode = value
            web.importantForAccessibility =
                if (value) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    /** The renderer died: the session must be recreated. */
    var crashed by mutableStateOf(false)
        private set
    /** The user started the conversation on this page (the instructions were sent). */
    var conversationStarted = false

    /**
     * Taint of the conversation held by THIS page, which keeps every earlier message: cleared only when the page starts a
     * new chat ([load], [newConversation]), independently of the Agente's own [AssistantState.conversationTaint].
     */
    val conversationTaint = AiConversationTaint()

    private var lastBeat = 0L
    /** AG-ERROR transport signals, main frame only: the last HTTP error status and whether the page could not reach the network. */
    private var mainHttp: Int? = null
    private var mainOffline = false
    /** Page counts before a send that appeared in the page and is still unanswered: the next pass only reads the answer. */
    private var pendingBase: Pair<Int, Int>? = null
    /** The last send appeared in the page but no answer was read yet. */
    val hasPendingSend: Boolean get() = pendingBase != null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    val web: WebView = xyz.grtlabs.pilastri.sync.WebViewDebug.create(wrapper).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        // The user's own account in the provider's own page: it should see an ordinary browser (as in Cattedra and TR).
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // While the page is covered no touch may reach it (Cattedra: a tap through the cover once opened the menu and signed out).
        setOnTouchListener { _, _: MotionEvent -> !shown }
        // Hidden from the start: no focus, no accessibility node, until a challenge shows it.
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webViewClient = client()
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@WebChatSession.progress = newProgress }
        }
        if (supported) {
            WebViewCompat.addWebMessageListener(this, "__pilastri", origins) { _: WebView, message: WebMessageCompat, _: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
                if (isMainFrame) receive(message.data)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, script, origins)
            }
        }
    }

    private fun allowedPage(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        if (mock != null && url.startsWith(DebugMock.ORIGIN)) return true
        return uri.scheme == "https" && account.matches(uri.host.orEmpty(), listOf(Uri.parse(account.cookieUrl).host.orEmpty()))
    }

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (mock != null && url.toString().startsWith(DebugMock.ORIGIN)) return false
            // Only the provider's own hosts stay in this always-signed-in view (not the sign-in providers: a new sign-in
            // happens in AiWebActivity). Signing out happens only from Collegamenti, never through the covered page.
            return when (account.chatNavigation(url.scheme, url.host, url.path, shown)) {
                ChatNavigation.STAY -> false
                ChatNavigation.BLOCK -> true
                ChatNavigation.OPEN_OUTSIDE -> {
                    try { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
                    true
                }
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
            if (request.isForMainFrame) mainHttp = errorResponse.statusCode
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            // ERROR_HOST_LOOKUP -2, ERROR_CONNECT -6, ERROR_IO -7: the page never reached the site.
            mainOffline = error.errorCode == WebViewClient.ERROR_HOST_LOOKUP || error.errorCode == WebViewClient.ERROR_CONNECT || error.errorCode == WebViewClient.ERROR_IO
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageUrl = url
            mainHttp = null
            mainOffline = false
            if (supported && allowedPage(url)) view.evaluateJavascript(script, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (supported && allowedPage(url)) view.evaluateJavascript(script, null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            crashed = true
            return true
        }
    }

    private fun receive(data: String?) {
        val o = try { JSONObject(data ?: return) } catch (_: Exception) { return }
        lastBeat = SystemClock.elapsedRealtime()
        o.optJSONObject("snap")?.let { s -> PageSnap.parse(s.toString())?.let { snap = it } }
    }

    fun load() {
        crashed = false
        pendingBase = null
        snap = null
        conversationStarted = false
        conversationTaint.clear()
        if (mock != null) web.loadDataWithBaseURL(DebugMock.ORIGIN + "/", mock, "text/html", "utf-8", null)
        else web.loadUrl(account.siteUrl)
    }

    /** The page's first path segment is a sign-in or onboarding page. */
    val onSignInPage: Boolean
        get() = Uri.parse(pageUrl).let { u -> account.matches(u.host.orEmpty(), account.hosts) && WebSessions.isSignInPath(account, u.path.orEmpty()) } ||
            // the sites are single-page apps: the script's own path is more current than the last page load
            snap?.let { WebSessions.isSignInPath(account, it.path) } == true

    /** Moves the page into [container] (removing it from any earlier one) and binds it to the current activity. */
    fun attach(container: FrameLayout, context: Context) {
        wrapper.baseContext = context
        (web.parent as? ViewGroup)?.removeView(web)
        container.addView(web)
    }

    fun detach() { (web.parent as? ViewGroup)?.removeView(web) }

    fun destroy() {
        detach()
        web.stopLoading()
        web.destroy()
    }

    /** One evaluation, bounded: WebView does not call back when the document changes under a pending evaluation. "" = no answer. */
    private suspend fun eval(js: String): String = kotlinx.coroutines.withTimeoutOrNull(EVAL_TIMEOUT_MS) {
        suspendCancellableCoroutine<String> { c -> web.evaluateJavascript(js) { v -> if (c.isActive) c.resume(v.orEmpty()) } }
    }.orEmpty()

    /** Opens a new conversation on the provider page (the next question carries the instructions again). */
    suspend fun newConversation() {
        conversationStarted = false
        eval("window.__pilNew && window.__pilNew()")
        // Only once the page was asked for a new chat: if that failed, the old messages may still be there.
        conversationTaint.clear()
    }

    suspend fun stop() { eval("window.__pilStop && window.__pilStop()") }

    /**
     * Types [message] into the page and returns the reply. Must run on the main thread; the caller (the user's tap) is the
     * only trigger. Never touches a sign-in form or a bot check: those come back as [WebAsk.NeedsLogin] / [WebAsk.NeedsUser].
     */
    suspend fun ask(message: String, onStage: (String) -> Unit = {}, readOnly: Boolean = false): WebAsk {
        val read = readOnly && pendingBase != null
        if (!supported) return WebAsk.NotDriven("la WebView non permette di collegare la pagina in modo sicuro", SendCause.NOT_SENT, false)
        if (crashed) return WebAsk.NotDriven("la pagina si è chiusa", SendCause.NOT_SENT, false)
        if (pageUrl.isEmpty()) load()
        onStage("Apro ${account.label}…")
        val readyBy = SystemClock.elapsedRealtime() + 45_000L
        while (true) {
            val s = snap
            if (s?.challenge == true) return WebAsk.NeedsUser
            if (onSignInPage || s?.loggedOut == true) return WebAsk.NeedsLogin
            if (s != null && s.ready && (read || !s.busy)) break
            if (SystemClock.elapsedRealtime() > readyBy) {
                val cause = SendCause.classify(TransportState(offline = mainOffline, http = mainHttp, timedOut = mainHttp == null && !mainOffline && s == null))
                return WebAsk.NotDriven(if (s?.busy == true) "la pagina sta ancora rispondendo alla domanda precedente" else "campo di scrittura non trovato", cause, read)
            }
            delay(250)
        }
        val base = snap!!
        val baseUsers: Int
        val baseAssistants: Int
        if (read) {
            baseUsers = pendingBase!!.first; baseAssistants = pendingBase!!.second
            onStage("${account.label} sta rispondendo…")
        } else {
            onStage("Invio a ${account.label}…")
            val sent = eval("window.__pilSend(${JSONObject.quote(message)})").trim('"')
            when (sent) {
                "ok" -> Unit
                "challenge" -> return WebAsk.NeedsUser
                else -> return WebAsk.NotDriven("la pagina non ha accettato il testo ($sent)", SendCause.NOT_SENT, false)
            }
            baseUsers = base.users; baseAssistants = base.assistants
            onStage("${account.label} sta rispondendo…")
        }
        val watcher = ReplyWatcher(baseUsers, baseAssistants, SystemClock.elapsedRealtime())
        while (true) {
            delay(250)
            val now = SystemClock.elapsedRealtime()
            val hung = lastBeat != 0L && now - lastBeat > 25_000L
            val outcome = watcher.update(snap, now, onSignInPage, hung, mainHttp, mainOffline) ?: continue
            // The send is confirmed once the message appeared in the page: a later pass only reads the answer.
            pendingBase = if (outcome is WebAsk.Reply || !watcher.delivered && !read) null else baseUsers to baseAssistants
            return outcome
        }
    }

    companion object {
        const val EVAL_TIMEOUT_MS = 5_000L
        /** The session cookie of [account] exists (name only). */
        fun hasSession(account: WebAccount): Boolean =
            try { WebSessions.hasSession(CookieManager.getInstance().getCookie(account.cookieUrl), account.sessionCookies) } catch (_: Exception) { false }
    }
}

/** Linking state and "Scollega" for the account routes. */
object WebAccounts {
    fun state(context: Context, account: WebAccount, settings: AiSettings): AiLinkState =
        if (DebugMock.page(context) != null) AiLinkState.LINKED
        else WebSessions.state(WebChatSession.hasSession(account), settings.linkedAt(account), settings.rejectedAt(account))

    /**
     * Deletes the provider's own cookies and site storage (and only those: other connectors share the cookie jar) and
     * forgets the link. Returns false when a session cookie is still present afterwards.
     */
    fun unlink(account: WebAccount, settings: AiSettings): Boolean {
        val cookies = CookieManager.getInstance()
        account.ownHosts.forEach { host ->
            val url = "https://$host"
            WebSessions.expiryCookies(cookies.getCookie(url), host).forEach { cookies.setCookie(url, it) }
            try { WebStorage.getInstance().deleteOrigin(url) } catch (_: Exception) { }
        }
        cookies.flush()
        settings.clearLink(account)
        return !WebChatSession.hasSession(account)
    }
}

/**
 * The only clipboard write of the app: text the person asked to copy (the routine's instructions, an assistant reply), on their tap.
 * Never read back, never tracked, never cleared. The system clipboard carries at most about 1 MB, so longer text is cut with a note.
 */
object ClipWrite {
    const val MAX_CHARS = 100_000

    fun fit(text: String): String = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS).trimEnd() + "\n\n[Testo troppo lungo: il resto non è stato copiato.]"

    fun copyPlain(context: Context, label: String, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, fit(text)))
    }
}

/**
 * Debug builds only: a fake provider page (`src/debug/.../MockProviderPage.kt`) instead of claude.ai / chatgpt.com, to try
 * the whole flow on the emulator without an account. Enabled by writing `<ui> <scenario>` (for example `claude proposal`)
 * to the app's `files/ai_mock`:
 *
 *     adb shell run-as xyz.grtlabs.pilastri sh -c "echo 'claude proposal' > files/ai_mock"
 *
 * Release builds never contain the page and never read the file.
 */
internal object DebugMock {
    const val ORIGIN = "https://mock.pilastri.test"

    fun page(context: Context): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return try {
            val config = File(context.filesDir, "ai_mock").takeIf { it.exists() }?.readText()?.trim()?.split(Regex("\\s+")) ?: return null
            val clazz = Class.forName("xyz.grtlabs.pilastri.ai.MockProviderPage")
            val instance = clazz.getField("INSTANCE").get(null)
            clazz.getMethod("html", String::class.java, String::class.java)
                .invoke(instance, config.getOrElse(0) { "claude" }, config.getOrElse(1) { "chat" }) as? String
        } catch (_: Exception) {
            null
        }
    }
}

/** The activity behind a Compose context, for launching. */
internal tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** ISO instant for settings timestamps. */
internal fun nowIso(): String = Instant.now().toString()
