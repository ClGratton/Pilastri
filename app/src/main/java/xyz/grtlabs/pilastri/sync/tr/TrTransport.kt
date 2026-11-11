package xyz.grtlabs.pilastri.sync.tr

/** Status and body of one GET made with the browser session's credentials. Status 0 means the request itself failed. */
data class TrHttpResponse(val status: Int, val body: String)

/**
 * The only door to Trade Republic. The production implementation runs inside a WebView page on the app.traderepublic.com
 * origin (see [TrWebBridge]); tests use a fake. It carries raw text frames and two fixed read-only GETs, never credentials:
 * cookies stay inside the WebView and are neither read nor stored by this app.
 */
interface TrTransport {
    /** GET with the session's credentials. [wantBody] false discards the body (the session refresh needs only the status). */
    suspend fun get(url: String, wantBody: Boolean = true): TrHttpResponse

    /** Opens the WebSocket and suspends until it is open. Fails with [TrError.Connection]. */
    suspend fun open(url: String)

    fun send(frame: String)

    /** The next inbound text frame; throws [TrError.Connection] once the socket is closed. */
    suspend fun receive(): String

    fun close()
}

/** Typed failures, all with Italian user-facing messages. They never carry server payloads, cookies or account data. */
sealed class TrError(message: String) : Exception(message) {
    class SessionExpired : TrError("La sessione di Trade Republic è scaduta: serve un nuovo accesso.")
    class Blocked : TrError("Trade Republic ha rifiutato la richiesta (protezione anti-bot o accesso negato). Riprova più tardi.")
    class Connection(detail: String) : TrError(detail)
    class Timeout(what: String) : TrError("Trade Republic non ha risposto in tempo ($what).")
    class Protocol(detail: String) : TrError(detail)

    /** The server answered a subscription with an error frame. [code] is a sanitized machine code when one was present. */
    class Subscription(val code: String?, detail: String) : TrError(detail)

    /** Failures of one optional request that must not abort the whole sync (a missing detail, one price). */
    val isSoft: Boolean get() = this is Timeout || this is Subscription || this is Protocol
}

/** Progress shown on the native screen. [total] 0 means unknown. */
data class TrProgress(val label: String, val done: Int = 0, val total: Int = 0)
