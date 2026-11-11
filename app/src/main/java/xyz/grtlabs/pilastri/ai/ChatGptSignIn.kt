package xyz.grtlabs.pilastri.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The auth endpoints over the app's shared OkHttp client (no redirects followed). */
class OkHttpChatGptAuth : ChatGptAuthHttp {
    private val client = OkHttpAiHttp.shared

    override suspend fun postForm(url: String, fields: Map<String, String>): AiHttpReply =
        call(Request.Builder().url(url).post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()).build())

    override suspend fun get(url: String, bearer: String?): AiHttpReply =
        call(Request.Builder().url(url).get().apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build())

    private suspend fun call(request: Request): AiHttpReply = suspendCancellableCoroutine { c ->
        val call = client.newCall(request)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val reply = try { response.use { AiHttpReply(it.code, it.body?.string().orEmpty()) } } catch (e: IOException) { c.resumeWithException(e); return }
                c.resume(reply)
            }
        })
    }
}

/**
 * The one HTTP request the browser makes to the loopback callback. Pure: the request line and the page sent back. The page
 * carries no token (the code is exchanged by the app, never shown).
 */
object LoopbackCallback {
    /** The query of `GET /auth/callback?… HTTP/1.1`, or null for any other request (favicon, other paths, other methods). */
    fun query(requestLine: String): String? {
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        val target = parts[1]
        if (target.substringBefore('?') != ChatGptOAuth.CALLBACK_PATH) return null
        return if ('?' in target) target.substringAfter('?') else ""
    }

    fun response(ok: Boolean): String {
        val title = if (ok) "Accesso completato" else "Accesso non completato"
        val body = "<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title></head><body style=\"font-family:sans-serif;padding:32px\"><h2>$title</h2><p>Torna a Pilastri.</p></body></html>"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n$body"
    }
}

/** Listens on 127.0.0.1 only (never on the network), for one callback. Started before the browser opens, as the docs require. */
class LoopbackListener private constructor(private val socket: ServerSocket) : AutoCloseable {
    val port: Int get() = socket.localPort

    /** Waits for the callback request and answers it; null on timeout. Other requests (a favicon) are answered and ignored. */
    suspend fun awaitQuery(timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            socket.soTimeout = 1_000
            while (true) {
                val client = try { socket.accept() } catch (_: SocketTimeoutException) { kotlinx.coroutines.yield(); continue }
                client.use { s ->
                    s.soTimeout = 5_000
                    val line = try { s.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine().orEmpty() } catch (_: IOException) { "" }
                    val query = LoopbackCallback.query(line)
                    val reply = if (query != null) LoopbackCallback.response(ok = "code=" in query) else "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    try { s.getOutputStream().apply { write(reply.toByteArray(StandardCharsets.UTF_8)); flush() } } catch (_: IOException) { }
                    if (query != null) return@withTimeoutOrNull query
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    override fun close() { try { socket.close() } catch (_: IOException) { } }

    companion object {
        /** Binds the preferred port, or any free one: only the port may change between sign-ins. */
        fun open(): LoopbackListener {
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(loopback, ChatGptOAuth.PREFERRED_PORT), 1) }
            } catch (_: IOException) {
                ServerSocket(0, 1, loopback)
            }
            return LoopbackListener(socket)
        }
    }
}

/** The process-wide registration and session (refreshes are serialized here). */
object ChatGptAccount {
    @Volatile private var cached: Pair<ChatGptStore, ChatGptSession>? = null

    fun store(context: Context): ChatGptStore? = parts(context)?.first
    fun session(context: Context): ChatGptSession? = parts(context)?.second

    private fun parts(context: Context): Pair<ChatGptStore, ChatGptSession>? {
        cached?.let { return it }
        val vault = AiVault.createOrNull(context) ?: return null
        val store = ChatGptStore(vault, SharedPrefsStore(AiVault.prefs(context)))
        return (store to ChatGptSession(store, OkHttpChatGptAuth())).also { cached = it }
    }

    /** Linking state for the route card. */
    fun state(context: Context): AiLinkState {
        val creds = store(context)?.load() ?: return AiLinkState.NOT_LINKED
        return if (creds.hasTokens) AiLinkState.LINKED else AiLinkState.EXPIRED
    }

    /** The system browser at ChatGPT's usage settings ("Gestisci utilizzo"). */
    fun openUsage(context: Context) = ChatGptSignIn.openInBrowser(context, ChatGptOAuth.USAGE_URL)

    /** "Scollega": revokes the renewable session, then clears the tokens; keeps the registration for a later sign-in. */
    suspend fun signOut(context: Context): Boolean {
        val store = store(context) ?: return false
        val creds = store.load() ?: return true
        var revoked = creds.refreshToken == null
        val refresh = creds.refreshToken
        if (refresh != null) {
            val http = OkHttpChatGptAuth()
            revoked = try {
                val endpoint = JSONObject(http.get(ChatGptOAuth.DISCOVERY).body).optString("revocation_endpoint").takeIf { it.startsWith("https://auth.openai.com/") }
                endpoint != null && http.postForm(endpoint, ChatGptOAuth.revokeForm(creds.clientId, refresh)).status == 200
            } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
        store.save(creds.withoutTokens().copy(scopes = emptySet()))
        return revoked
    }
}

/**
 * The sign-in: a loopback listener, the system browser in a Custom Tab (never a WebView: the app sees no password and fills
 * nothing), the code exchange, the ID-token check against OpenAI's keys, and the credentials saved in the vault. The user
 * does every step in the browser; the app never signs in for them.
 */
object ChatGptSignIn {
    sealed interface Outcome {
        data class Linked(val planEnabled: Boolean, val email: String?, val firstTime: Boolean) : Outcome
        /** The person declined in the browser (`access_denied`). */
        data class Declined(val error: String) : Outcome
        /** OpenAI refused this account or this app (403 / structured refusal): OpenAI's refusal, not a fault of Pilastri. [error] is `[a-z_]{1,40}`. */
        data class Refused(val error: String) : Outcome
        /** The browser never reached the loopback address (another browser or device, a closed tab, or the app's process ended meanwhile). */
        data object NoReturn : Outcome
        /** [ours]: a fault of Pilastri itself (the local port, an exception in our code), never the provider's refusal. */
        data class Failed(val why: String, val ours: Boolean = false) : Outcome
    }

    const val TIMEOUT_MS = 10 * 60_000L

    /** Process-wide, so a sign-in survives the screen that started it (the Custom Tab covers the app meanwhile). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var running by mutableStateOf(false)
        private set
    var lastOutcome by mutableStateOf<Outcome?>(null)
    /** A short state line after "Scollega". */
    var note by mutableStateOf<String?>(null)
    /** Bumped when a sign-in ends, so route states are read again. */
    var revision by mutableIntStateOf(0)
        private set

    private const val TAG = "Pilastri.AI"
    private var job: kotlinx.coroutines.Job? = null

    /** One line in the durable debug log. Callers pass names, lengths, HTTP statuses and `[a-z_]` error names only. */
    internal fun log(text: String) = xyz.grtlabs.pilastri.sync.eb.EbLog.line(TAG, "chatgpt: $text")

    /** An error name from a provider, kept only when it is `[a-z_]{1,40}` (never free text); otherwise `other`. */
    internal fun safeName(s: String?): String = s?.takeIf { Regex("[a-z_]{1,40}").matches(it) } ?: "other"

    /** The names and lengths of the callback's parameters: `code#43,state#32`. Values never. */
    internal fun queryShape(query: String): String = query.split('&').filter { it.isNotEmpty() }.joinToString(",") { p ->
        val k = p.substringBefore('='); "${safeName(k)}#${p.substringAfter('=', "").length}"
    }

    /** The one wording of a sign-in that did not end linked, with whose error it is: the person's, OpenAI's, a missing return, or Pilastri's. */
    fun failureText(o: Outcome?): String? = when (o) {
        is Outcome.Declined -> "Accesso a ChatGPT annullato"
        is Outcome.Refused -> "OpenAI non accetta questo account per Pilastri (${o.error})"
        is Outcome.NoReturn -> "ChatGPT non ha mandato l'esito a Pilastri"
        is Outcome.Failed -> if (o.ours) "Errore di Pilastri nell'accesso a ChatGPT: ${o.why}" else "Accesso a ChatGPT non riuscito: ${o.why}"
        is Outcome.Linked, null -> null
    }

    /** A failure with its log line: [at] is a fixed step name of ours, never provider text. */
    private fun failed(at: String, why: String, ours: Boolean = false): Outcome.Failed { log("failed at=$at ours=$ours"); return Outcome.Failed(why, ours) }

    private const val PENDING_KEY = "chatgpt_signin_pending"

    /** The pending sign-in is marked outside process memory: the loopback socket cannot outlive the process, so a wait lost with it becomes a named failure with its retry. */
    internal fun markPending(store: KeyValueStore, on: Boolean) { if (on) store.put(PENDING_KEY, "1") else store.remove(PENDING_KEY) }

    /** True once, when a sign-in was pending and the process that held its listener is gone. */
    internal fun lostPending(store: KeyValueStore, runningNow: Boolean): Boolean {
        if (runningNow || store.get(PENDING_KEY) == null) return false
        store.remove(PENDING_KEY)
        return true
    }

    /** Called by every screen that shows the sign-in: a wait lost with the process becomes [Outcome.NoReturn], never a silent return to the start. */
    fun restore(context: Context) {
        if (running || lastOutcome != null) return
        val store = try { SharedPrefsStore(AiVault.prefs(context.applicationContext)) } catch (_: Exception) { return }
        if (lostPending(store, running)) { log("pending sign-in lost with the process"); lastOutcome = Outcome.NoReturn }
    }

    /**
     * THE sign-in entry, used by the setup AI step and by the assistant (same concept, same code). Starts from the user's tap
     * (never on its own). [forceConsent] null: re-consent only when plan use was declined before. The listener and the pending
     * attempt live in this process-wide scope, not in a screen: they survive the app going to the background.
     */
    fun start(activity: Context, forceConsent: Boolean? = null) {
        if (running) return
        running = true
        note = null
        lastOutcome = null
        val force = forceConsent ?: ChatGptAccount.store(activity)?.load()?.let { it.hasTokens && !it.planEnabled } ?: false
        val pending = try { SharedPrefsStore(AiVault.prefs(activity.applicationContext)) } catch (_: Exception) { null }
        pending?.let { markPending(it, true) }
        log("start forceConsent=$force")
        job = scope.launch {
            try {
                lastOutcome = run(activity, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("failed at=exception type=${safeName(e::class.simpleName?.lowercase())} ours=true")
                lastOutcome = Outcome.Failed("errore imprevisto", ours = true)
            } finally {
                pending?.let { markPending(it, false) }
                running = false
                revision++
                log("outcome ${lastOutcome?.let { it::class.simpleName } ?: "none"}")
            }
        }
    }

    /** "Riprova" while waiting: closes the pending listener and starts a new attempt. Reachable from the first frame of the wait. */
    fun restart(activity: Context) {
        if (!running) { start(activity); return }
        log("restart while waiting")
        val old = job
        scope.launch {
            old?.cancelAndJoin()
            running = false
            start(activity)
        }
    }

    /** Opens [url] in a Custom Tab through the plain Custom Tabs intent protocol (no extra library); any browser otherwise. */
    fun openInBrowser(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            // Custom Tabs protocol: a session extra (null binder) makes a supporting browser open a Custom Tab.
            putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            putExtra("androidx.browser.customtabs.extra.SHARE_STATE", 2)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
    }

    /** Brings the app back over the Custom Tab once the callback arrived. */
    private fun returnToApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(launch) } catch (_: Exception) { }
    }

    /** [forceConsent] when plan use was declined before and the user now enables it. */
    suspend fun run(activity: Context, forceConsent: Boolean = false, http: ChatGptAuthHttp = OkHttpChatGptAuth()): Outcome {
        val store = ChatGptAccount.store(activity) ?: return failed("vault", "il deposito sicuro del telefono non funziona")
        val saved = store.load()
        val listener = try { withContext(Dispatchers.IO) { LoopbackListener.open() } } catch (e: IOException) {
            return failed("listener_port_busy", "porta locale occupata", ours = true)
        }
        listener.use { l ->
            val attempt = ChatGptOAuth.newAttempt(l.port, saved?.clientId)
            val url = ChatGptOAuth.authorizeUrl(attempt, store.hostId(), saved?.idToken, saved?.email, forceConsent)
            log("listener port=${l.port} preferred=${l.port == ChatGptOAuth.PREFERRED_PORT} newRegistration=${attempt.newRegistration}")
            if (!openInBrowser(activity, url)) { return failed("no_browser", "nessun browser disponibile") }
            log("browser opened")
            val query = l.awaitQuery(TIMEOUT_MS) ?: run { log("no return within timeout"); return Outcome.NoReturn }
            log("return received params=${queryShape(query)}")
            returnToApp(activity.applicationContext)
            return when (val cb = ChatGptOAuth.parseCallback(query, attempt)) {
                is ChatGptOAuth.Callback.Invalid -> failed("callback_invalid", cb.reason)
                is ChatGptOAuth.Callback.Denied -> {
                    log("return error=${safeName(cb.error)}")
                    if (cb.error == "access_denied" && saved != null) store.save(saved.copy(scopes = saved.scopes - ChatGptOAuth.PLAN_SCOPE))
                    if (cb.error == "access_denied") Outcome.Declined(cb.error) else Outcome.Refused(safeName(cb.error))
                }
                is ChatGptOAuth.Callback.Code -> exchange(cb, attempt, saved, store, http)
            }
        }
    }

    /** OpenAI's own refusals of this account or app (documented structured codes and 403), as opposed to a stale code or a transport fault. */
    internal fun isRefusal(status: Int, code: String?): Boolean =
        status == 403 || code?.let { it.startsWith("subscription_sharing") || it.startsWith("chatpass_v2") || it == "unauthorized_client" || it == "invalid_client" } == true

    private suspend fun exchange(cb: ChatGptOAuth.Callback.Code, attempt: ChatGptOAuth.Attempt, saved: ChatGptCredentials?, store: ChatGptStore, http: ChatGptAuthHttp): Outcome {
        val now = System.currentTimeMillis() / 1000
        return try {
            val reply = http.postForm(ChatGptOAuth.TOKEN, ChatGptOAuth.tokenForm(cb.code, cb.clientId, attempt))
            if (reply.status != 200) {
                val code = ChatGptOAuth.errorCode(reply.body)
                log("token exchange status=${reply.status} error=${safeName(code)}")
                if (isRefusal(reply.status, code)) return Outcome.Refused(safeName(code))
                return Outcome.Failed(if (code == "invalid_grant") "codice di accesso scaduto: riprova" else "scambio del codice rifiutato (${reply.status})")
            }
            log("token exchange status=200")
            val tokens = ChatGptOAuth.parseTokens(reply.body, now)
            val idToken = tokens.idToken ?: return failed("no_id_token", "OpenAI non ha restituito il token di identità")
            val jwksUri = JSONObject(http.get(ChatGptOAuth.DISCOVERY).body).optString("jwks_uri").takeIf { it.startsWith("https://auth.openai.com/") }
                ?: return failed("jwks_missing", "chiavi di OpenAI non trovate")
            val identity = ChatGptIdToken.verify(idToken, JSONObject(http.get(jwksUri).body), cb.clientId, attempt.nonce, now)
            if (saved != null && saved.clientId == cb.clientId && saved.subject != identity.subject) {
                return failed("other_account", "è stato scelto un altro account ChatGPT: scollega prima quello salvato")
            }
            store.save(ChatGptCredentials(cb.clientId, identity.subject, identity.email, idToken, tokens.access, tokens.refresh, tokens.expiresAt, tokens.scopes))
            // The model list belongs to this account; the first listed model becomes the default unless the user chose one.
            try {
                val models = http.get(ResponsesWire.MODELS, tokens.access)
                if (models.status == 200) ResponsesWire.parseModels(models.body).firstOrNull()?.let { (slug, _) ->
                    if (store.model == ChatGptStore.DEFAULT_MODEL) store.model = slug
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            Outcome.Linked(ChatGptOAuth.PLAN_SCOPE in tokens.scopes, identity.email, firstTime = !store.welcomed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptAuthException) {
            failed("id_token_invalid", e.message ?: "accesso non valido")
        } catch (e: IOException) {
            failed("network", "connessione non riuscita")
        } catch (e: JSONException) {
            failed("unreadable_reply", "risposta di OpenAI non leggibile")
        }
    }
}
