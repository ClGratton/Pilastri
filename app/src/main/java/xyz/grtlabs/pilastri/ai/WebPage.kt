package xyz.grtlabs.pilastri.ai

import org.json.JSONObject

/**
 * What `pilastri_inject.js` reports about the provider page: site, path, whether the editor is ready, whether a reply
 * is streaming, sign-in and bot-check signs, message counts and the last few messages. Pure, so it is tested without a
 * WebView.
 */
data class PageSnap(
    val site: String,
    val path: String,
    val ready: Boolean,
    val busy: Boolean,
    val loggedOut: Boolean,
    val challenge: Boolean,
    val users: Int,
    val assistants: Int,
    val messages: List<PageMessage>,
) {
    val lastAssistant: PageMessage? get() = messages.lastOrNull { !it.user }

    companion object {
        const val MAX_TEXT = 60_000

        /** Null for anything that is not a well-formed snapshot (the page is not trusted to send one). */
        fun parse(json: String): PageSnap? = try {
            val o = JSONObject(json)
            val list = o.optJSONArray("msgs")
            val messages = if (list == null) emptyList() else (0 until minOf(list.length(), 8)).mapNotNull { i ->
                val m = list.optJSONObject(i) ?: return@mapNotNull null
                PageMessage(m.optString("r") == "u", m.optString("t").take(MAX_TEXT), m.optBoolean("x"))
            }
            PageSnap(
                o.optString("site").take(20), o.optString("path").take(300), o.optBoolean("ready"), o.optBoolean("busy"),
                o.optBoolean("loggedOut"), o.optBoolean("challenge"),
                o.optInt("nu").coerceIn(0, 100_000), o.optInt("na").coerceIn(0, 100_000), messages,
            )
        } catch (_: Exception) {
            null
        }
    }
}

data class PageMessage(val user: Boolean, val text: String, val external: Boolean)

/**
 * AG-ERROR: why a question did not go through. Decided from transport and page state only (network state, URL, HTTP status,
 * timeout, composer present), never from the provider page's wording. Anything unclassified is [NOT_SENT].
 */
enum class SendCause(val button: String) {
    NO_NETWORK("Riprova"),
    SESSION_ENDED("Accedi di nuovo"),
    VERIFICATION("Verifica"),
    PROVIDER_LIMIT("Riprova"),
    NO_ANSWER("Riprova"),
    NOT_SENT("Riprova");

    /** The one line of the error row, in the user's words; [label] is the linked account's label. */
    fun line(label: String): String = when (this) {
        NO_NETWORK -> "Nessuna connessione."
        SESSION_ENDED -> "Accesso a $label scaduto."
        VERIFICATION -> "$label chiede una verifica."
        PROVIDER_LIMIT -> "Limite di messaggi di $label raggiunto."
        NO_ANSWER -> "$label non ha risposto."
        NOT_SENT -> "Messaggio non inviato."
    }

    companion object {
        fun classify(t: TransportState): SendCause = when {
            t.challenge -> VERIFICATION
            t.signInPath || t.http == 401 || t.http == 403 -> SESSION_ENDED
            t.offline -> NO_NETWORK
            t.http == 429 -> PROVIDER_LIMIT
            (t.http != null && t.http in 500..599) || t.timedOut -> NO_ANSWER
            else -> NOT_SENT
        }
    }
}

/** What the WebView client and the page state report; no page wording. */
data class TransportState(
    val offline: Boolean = false,
    val http: Int? = null,
    val timedOut: Boolean = false,
    val signInPath: Boolean = false,
    val challenge: Boolean = false,
)

/**
 * How one question on the provider page ended. [NotDriven] and [Failed] carry the [SendCause] and whether the send was
 * [confirmed] (the message appeared in the page): a confirmed question is never sent twice.
 */
sealed interface WebAsk {
    data class Reply(val text: String, val external: Boolean) : WebAsk
    /** The page wants a sign-in: the session expired or was revoked. */
    data object NeedsLogin : WebAsk
    /** A CAPTCHA or bot check is showing: the user completes it on the page; the app never does. */
    data object NeedsUser : WebAsk
    /** The page could not be driven (no editor, the message did not leave, the page froze). [why] is for logs only. */
    data class NotDriven(val why: String, val cause: SendCause = SendCause.NOT_SENT, val confirmed: Boolean = false) : WebAsk
    data class Failed(val why: String, val cause: SendCause = SendCause.NO_ANSWER, val confirmed: Boolean = true) : WebAsk
}

/** The cause of a failed outcome; null for a reply. */
val WebAsk.cause: SendCause?
    get() = when (this) {
        is WebAsk.Reply -> null
        WebAsk.NeedsLogin -> SendCause.SESSION_ENDED
        WebAsk.NeedsUser -> SendCause.VERIFICATION
        is WebAsk.NotDriven -> cause
        is WebAsk.Failed -> cause
    }

/**
 * Decides when a reply is complete, from the snapshots that follow a send (timestamps in milliseconds). Adapted from the
 * thresholds Cattedra uses for its claude.ai chat (delivery 30 s, idle answer 6 s, empty 30 s, hung 25 s).
 */
class ReplyWatcher(
    private val baseUsers: Int,
    private val baseAssistants: Int,
    private val sentAt: Long,
    private val idleMs: Long = 4_000L,
    private val deliveryMs: Long = 30_000L,
    private val emptyMs: Long = 30_000L,
    private val totalMs: Long = 6 * 60_000L,
) {
    /** The message appeared in the page. */
    var delivered = false
        private set
    private var lastText = ""
    private var lastChange = sentAt
    private var idleSince = 0L

    /** Null while waiting; the outcome once the reply is complete or the wait failed. */
    fun update(snap: PageSnap?, now: Long, signInPath: Boolean = false, hung: Boolean = false, http: Int? = null, offline: Boolean = false): WebAsk? {
        if (snap != null) {
            if (snap.challenge) return WebAsk.NeedsUser
            if (snap.loggedOut || signInPath) return WebAsk.NeedsLogin
            if (snap.users > baseUsers) delivered = true
        }
        if (offline) return WebAsk.Failed("rete assente", SendCause.NO_NETWORK, delivered)
        if (http == 429 || (http != null && http in 500..599)) return WebAsk.Failed("http $http", SendCause.classify(TransportState(http = http)), delivered)
        if (hung) return WebAsk.NotDriven("la pagina non risponde più", SendCause.NO_ANSWER, delivered)
        if (!delivered) {
            return if (now - sentAt > deliveryMs) WebAsk.NotDriven("il messaggio non è comparso nella pagina", SendCause.NO_ANSWER, false) else null
        }
        val reply = snap?.takeIf { it.assistants > baseAssistants }?.lastAssistant
        val text = reply?.text.orEmpty()
        if (text != lastText) { lastText = text; lastChange = now }
        val busy = snap?.busy == true
        if (busy) idleSince = 0L else if (idleSince == 0L) idleSince = now
        if (!busy && text.isNotBlank() && now - lastChange >= idleMs && now - idleSince >= idleMs / 2) return WebAsk.Reply(text, reply?.external == true)
        if (!busy && text.isBlank() && idleSince != 0L && now - idleSince >= emptyMs) return WebAsk.Failed("la risposta è vuota", SendCause.NO_ANSWER, true)
        if (now - sentAt > totalMs) {
            return if (text.isNotBlank()) WebAsk.Reply(text + "\n\n[Risposta forse incompleta: tempo massimo di attesa raggiunto.]", reply?.external == true)
            else WebAsk.Failed("nessuna risposta in ${totalMs / 60_000} minuti", SendCause.NO_ANSWER, true)
        }
        return null
    }
}
