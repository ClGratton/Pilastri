package xyz.grtlabs.pilastri.sync.tr

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * The relay script. It defines `window.__pilastriTr` in the page's JavaScript context and touches nothing else: it reads no
 * DOM node, no form field and no storage, and it is never injected into the login page (see TrLoginActivity). The WebSocket
 * and the two GETs therefore run on the app.traderepublic.com origin with the page's own cookies, Origin header and WAF
 * state; the app only sees the frames.
 *
 * Every call into Kotlin carries a per-run random token so that no other frame or script in the page can feed the bridge.
 */
internal object TrBridgeScript {
    const val INTERFACE = "PilastriTrBridge"

    fun source(token: String): String = """
        (function (tok) {
          var ws = null;
          function post(kind, a, b) {
            try { $INTERFACE.event(tok, kind, a == null ? "" : String(a), b == null ? "" : String(b)); } catch (e) {}
          }
          window.__pilastriTr = {
            open: function (url) {
              try { if (ws) ws.close(); } catch (e) {}
              ws = new WebSocket(url);
              ws.onopen = function () { post("open"); };
              ws.onmessage = function (ev) { if (typeof ev.data === "string") post("msg", ev.data); };
              ws.onerror = function () { post("error"); };
              ws.onclose = function (ev) { post("close", ev.code); };
            },
            send: function (frame) { if (ws && ws.readyState === 1) ws.send(frame); else post("error"); },
            close: function () { try { if (ws) ws.close(); } catch (e) {} ws = null; },
            get: function (id, url, wantBody) {
              fetch(url, { credentials: "include", cache: "no-store" })
                .then(function (r) { return r.text().then(function (t) { post("http", id + " " + r.status, wantBody ? t : ""); }); })
                .catch(function () { post("http", id + " 0", ""); });
            }
          };
        })(${JSONObject.quote(token)});
    """.trimIndent()
}

/**
 * [TrTransport] over a WebView that already shows app.traderepublic.com with [TrBridgeScript] injected. Kotlin to page goes
 * through `evaluateJavascript` (posted to the main thread, so order is kept); page to Kotlin arrives on [event].
 *
 * Only the two allowed GET URLs and the one allowed socket URL are accepted, so this class cannot be turned into a general
 * request relay, and it never exposes or reads cookies.
 */
class TrWebBridge(private val web: WebView, private val token: String) : TrTransport {
    private val main = Handler(Looper.getMainLooper())
    private val inbound = Channel<String>(Channel.UNLIMITED)
    private val http = ConcurrentHashMap<Int, CompletableDeferred<TrHttpResponse>>()
    private val httpIds = AtomicInteger(1)
    @Volatile private var opening: CompletableDeferred<Unit>? = null
    @Volatile private var finished = false

    private fun run(function: String, args: String, evenIfFinished: Boolean = false) {
        val js = "(function(){var b=window.__pilastriTr;if(b){b.$function($args)}else{${TrBridgeScript.INTERFACE}.event('$token','missing','','')}})()"
        main.post { if (!finished || evenIfFinished) runCatching { web.evaluateJavascript(js, null) } }
    }

    override suspend fun get(url: String, wantBody: Boolean): TrHttpResponse {
        require(TrUrls.isAllowedGet(url)) { "URL non consentita." }
        val id = httpIds.getAndIncrement()
        val answer = CompletableDeferred<TrHttpResponse>()
        http[id] = answer
        try {
            run("get", "$id,${JSONObject.quote(url)},$wantBody")
            return try {
                withTimeout(HTTP_TIMEOUT_MS) { answer.await() }
            } catch (_: TimeoutCancellationException) {
                throw TrError.Timeout("sessione")
            }
        } finally {
            http.remove(id)
        }
    }

    override suspend fun open(url: String) {
        require(TrUrls.isAllowedSocket(url)) { "URL non consentita." }
        val ready = CompletableDeferred<Unit>()
        opening = ready
        run("open", JSONObject.quote(url))
        ready.await()
    }

    override fun send(frame: String) {
        if (finished) throw TrError.Connection("Connessione con Trade Republic chiusa.")
        run("send", JSONObject.quote(frame))
    }

    override suspend fun receive(): String = inbound.receive()

    override fun close() {
        if (finished) return
        run("close", "", evenIfFinished = true)
        shutdown()
    }

    /** Stops accepting events and releases waiters. Idempotent. */
    fun shutdown() {
        finished = true
        val gone = TrError.Connection("Connessione con Trade Republic chiusa.")
        inbound.close(gone)
        opening?.completeExceptionally(gone)
        http.values.forEach { it.completeExceptionally(gone) }
    }

    /** Called by the page's script on a WebView thread. [kind]: open, msg, error, close, http, missing. */
    @JavascriptInterface
    fun event(tok: String?, kind: String?, a: String?, b: String?) {
        if (finished || tok == null || kind == null) return
        if (!MessageDigest.isEqual(tok.toByteArray(), token.toByteArray())) return
        when (kind) {
            "open" -> opening?.complete(Unit)
            "msg" -> if (a != null) inbound.trySend(a)
            "http" -> {
                val spec = a ?: return
                val id = spec.substringBefore(' ').toIntOrNull() ?: return
                val status = spec.substringAfter(' ', "0").toIntOrNull() ?: 0
                http[id]?.complete(TrHttpResponse(status, b.orEmpty()))
            }
            "error", "close", "missing" -> {
                val e = TrError.Connection(
                    if (kind == "missing") "La pagina di Trade Republic non è più disponibile."
                    else "Connessione con Trade Republic interrotta.",
                )
                opening?.completeExceptionally(e)
                inbound.close(e)
                if (kind == "missing") http.values.forEach { it.completeExceptionally(e) }
            }
        }
    }

    private companion object { const val HTTP_TIMEOUT_MS = 30_000L }
}
