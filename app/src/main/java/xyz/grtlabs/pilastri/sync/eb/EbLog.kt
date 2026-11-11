package xyz.grtlabs.pilastri.sync.eb

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The debug log of the Enable Banking setup and of the mail notifications (user, 2026-11-10: "registra contenuto, che debug è"):
 * CONTENT, in every build, `grep -E "Pilastri\.(EB|Mail)"`. Never written: passwords, key material, API secrets, cookies, session or ID
 * tokens, the value of the one-time code in a sign-in link (its parameter name and length are), amounts, account numbers.
 */
object EbLog {
    const val EB = "Pilastri.EB"
    const val MAIL = "Pilastri.Mail"
    private const val CHUNK = 900
    private val URL = Regex("https?://[^\\s<>\"')\\]]+", RegexOption.IGNORE_CASE)
    private val LONG = Regex("[A-Za-z0-9_\\-.]{40,}")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")
    private val SECRET_KEYS = setOf("appId", "url", "token", "idToken", "certificate", "key", "cookie", "password", "oobCode", "apiKey", "body", "reason")

    /** One line, split so logcat does not cut it (continuation parts carry `[i/n]`). */
    fun line(tag: String, text: String) {
        val t = text.replace('\n', ' ')
        val parts = if (t.length <= CHUNK) listOf(t) else t.chunked(CHUNK)
        parts.forEachIndexed { i, p ->
            val s = if (parts.size > 1) "[${i + 1}/${parts.size}] $p" else p
            try { Log.i(tag, s) } catch (_: Throwable) { /* JVM tests */ }
            xyz.grtlabs.pilastri.sync.DurableLog.append(tag, s)   // the durable copy: same text, off the main thread
        }
    }

    /** An address with its query VALUES replaced by their lengths: `https://host/path?mode=signIn&oobCode=#42`. Parameter names stay. */
    fun link(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url.substringBefore('#') + if (url.contains('#')) "#…" else ""
        val base = url.substring(0, q)
        val rest = url.substring(q + 1).substringBefore('#')
        val params = rest.split('&').joinToString("&") { p ->
            val k = p.substringBefore('='); val v = p.substringAfter('=', "")
            if (k in setOf("mode", "lang", "next") && v.length < 60) "$k=$v" else "$k=#${v.length}"
        }
        return "$base?$params"
    }

    private val MAIL_RUN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+")
    private val UUID_RUN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F-]{20,}")
    private val TOKENISH = Regex("[A-Za-z0-9_+/=-]{40,}")
    private val WS = Regex("\\s+")

    /** An error answer's body for the log: at most 600 chars, e-mail addresses, uuids and long token-like runs masked. */
    fun maskBody(text: String?): String =
        text.orEmpty().replace(MAIL_RUN, "<mail>").replace(UUID_RUN, "<uuid>").replace(TOKENISH, "<long>").replace(WS, " ").take(600)

    /** Free text with every address reduced by [link], long token-like strings and IBAN-like numbers replaced by their length. */
    fun scrub(text: String?): String =
        text.orEmpty().replace(URL) { link(it.value) }.replace(LONG) { "#${it.value.length}" }.replace(IBAN, "#iban")

    /** A script's result: every key and value, secrets by length only, strings scrubbed. */
    fun json(o: JSONObject?): String {
        if (o == null) return "null"
        val keys = o.keys().asSequence().toList()
        return keys.joinToString(", ", "{", "}") { k ->
            val v = o.opt(k)
            k + "=" + when {
                k in SECRET_KEYS && v is String -> if (k == "url") link(v) else "#${v.length}"
                v is JSONObject -> json(v)
                v is JSONArray -> "[" + (0 until v.length()).joinToString(",") { i -> val e = v.opt(i); if (e is JSONObject) json(e) else scrub(e?.toString()) } + "]"
                else -> scrub(v?.toString())
            }
        }
    }

    /** An address as host + path only: no scheme, no query, no fragment (the step-6 navigation log). */
    fun shape(url: String?): String = try {
        val u = java.net.URI(url ?: ""); (u.host ?: "?") + (u.path ?: "").ifEmpty { "/" }
    } catch (_: Exception) { "?" }

    /** An error name for the log: lower case letters and underscores only, at most 40 characters; anything else is "other". */
    fun errName(raw: String?): String = raw?.lowercase()?.takeIf { Regex("[a-z_]{1,40}").matches(it) } ?: "other"

    /** The failure kind of a thrown error, by type only (never its message): `rate_limited`, `server http=502`, `network`, ... */
    fun kind(e: Throwable): String = when (e) {
        is EbException.RateLimited -> "rate_limited"
        is EbException.SessionGone -> "session_gone"
        is EbException.Unauthorized -> "unauthorized http=${e.httpCode}"
        is EbException.PsuHeaders -> "psu_headers"
        is EbException.Rejected -> "rejected http=${e.httpCode}" + (e.errorCode?.let { " code=" + errName(it) } ?: "")
        is EbException.Server -> "server http=${e.httpCode}"
        is EbException.Network -> "network"
        is EbException.Malformed -> "malformed"
        is EbException.Incomplete -> "incomplete"
        else -> errName(e.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1_$2"))
    }

    /**
     * What the redirect claim saw, shapes only: whether the address is the registered redirect, a code (and its length), an
     * error (its name), and whether `state` is the pending authorization's. `[expectedState]` null: no pending authorization.
     */
    fun claim(url: String, registered: String, expectedState: String?): String {
        val matched = EbRedirect.matches(url, registered)
        val q = try {
            java.net.URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { p ->
                val i = p.indexOf('='); java.net.URLDecoder.decode(if (i < 0) p else p.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(if (i < 0) "" else p.substring(i + 1), "UTF-8")
            }
        } catch (_: Exception) { emptyMap() }
        val code = q["code"]
        return "claim matched=${yn(matched)} code=${yn(!code.isNullOrBlank())}" + (if (!code.isNullOrBlank()) " len=${code.length}" else "") +
            " error=" + (q["error"]?.let { errName(it) } ?: "no") + " state=${yn(expectedState != null && q["state"] == expectedState)} pending=${yn(expectedState != null)}"
    }

    fun yn(b: Boolean) = if (b) "yes" else "no"
}
