package xyz.grtlabs.pilastri.sync.tr

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import org.json.JSONObject
import org.json.JSONTokener

/*
 * Wire protocol of Trade Republic's private web API, as spoken by the web app and by the community client pytr
 * (https://github.com/pytr-org/pytr, MIT; notices in assets/licenses/pytr-MIT.txt). The frame grammar, the delta decoder
 * and the subscription payloads are adapted from pytr master (api.py, checked 2026-10-28). This is an unofficial,
 * unversioned interface: it can change without notice. Nothing here sends credentials or any mutating request.
 */

/** Tunables that pytr hard-codes. [protocol] 31 is pytr's connect number; another client uses 34 and some topics differ. */
data class TrConfig(
    val protocol: Int = 31,
    val locale: String = "it",
    val platformVersion: String = "chrome - 94.0.4606",
    val clientVersion: String = "5582",
    val sinceDays: Int = 400,
    val maxPages: Int = 200,
    val requestTimeoutMs: Long = 30_000,
    val handshakeTimeoutMs: Long = 15_000,
    /** pytr refreshes the session before a request when more than 290 s have passed since the last refresh. */
    val sessionRefreshMs: Long = 290_000,
    val wafRetries: Int = 2,
    val wafRetryDelayMs: Long = 1_500,
    val detailConcurrency: Int = 6,
    val preferredExchange: String = "LSX",
) {
    init {
        require(protocol in 1..999 && sinceDays in 1..3660 && maxPages in 1..2000 && detailConcurrency in 1..32)
        require(locale.matches(Regex("[a-z]{2}")) && preferredExchange.matches(Regex("[A-Z0-9]{1,10}")))
    }
}

/** One inbound frame. [Unknown] deliberately keeps only the length: unexpected frames are never retained or logged. */
sealed interface TrFrame {
    data object Connected : TrFrame
    data class Full(val id: Int, val payload: String) : TrFrame
    data class Delta(val id: Int, val payload: String) : TrFrame
    data class Closed(val id: Int) : TrFrame
    data class Failed(val id: Int, val payload: String) : TrFrame
    data class Unknown(val length: Int) : TrFrame
}

object TrProtocol {
    val ROME: ZoneId = ZoneId.of("Europe/Rome")

    // ---- outbound frames -------------------------------------------------------------------------------------------

    /** `connect 31 {...}` with the key order pytr sends (the order is built by hand: org.json maps are unordered). */
    fun connectFrame(config: TrConfig): String =
        "connect ${config.protocol} " + obj(
            "locale" to config.locale,
            "platformId" to "webtrading",
            "platformVersion" to config.platformVersion,
            "clientId" to "app.traderepublic.com",
            "clientVersion" to config.clientVersion,
        )

    fun subscribeFrame(id: Int, payloadJson: String) = "sub $id $payloadJson"
    fun unsubscribeFrame(id: Int) = "unsub $id"

    // Subscription payloads. Every one is a read: there is deliberately no builder for orders, payments or settings.
    fun timelineTransactions(after: String?) = obj("type" to "timelineTransactions", "after" to after)
    fun timelineActivityLog(after: String?) = obj("type" to "timelineActivityLog", "after" to after)
    fun timelineDetail(id: String) = obj("type" to "timelineDetailV2", "id" to id)
    fun compactPortfolio(secAccNo: String) = obj("type" to "compactPortfolioByType", "secAccNo" to secAccNo)
    fun cash() = obj("type" to "cash")
    fun accountPairs() = obj("type" to "accountPairs")
    fun instrument(isin: String) = obj("type" to "instrument", "id" to isin)
    fun ticker(isin: String, exchange: String) = obj("type" to "ticker", "id" to "$isin.$exchange")

    /** The set of subscription types this connector may ever send. Tests assert that nothing else leaves the transport. */
    val READ_ONLY_TYPES = setOf(
        "timelineTransactions", "timelineActivityLog", "timelineDetailV2", "compactPortfolioByType",
        "cash", "accountPairs", "instrument", "ticker",
    )

    private fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${JSONObject.quote(k)}:${literal(v)}" }

    private fun literal(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        else -> JSONObject.quote(v.toString())
    }

    // ---- inbound frames --------------------------------------------------------------------------------------------

    /**
     * `<id> <code> <payload>` with code A (full JSON, the new baseline), D (delta against the previous payload of that id),
     * C (closed) or E (error JSON); `connected` answers the connect frame. As in pytr, the payload is whatever follows the
     * code character and one separator, with leading Python whitespace stripped.
     */
    fun parseFrame(raw: String): TrFrame {
        if (raw.trim() == "connected") return TrFrame.Connected
        val space = raw.indexOf(' ')
        if (space <= 0 || raw.length < space + 2) return TrFrame.Unknown(raw.length)
        val id = raw.substring(0, space).toIntOrNull() ?: return TrFrame.Unknown(raw.length)
        val payload = if (raw.length > space + 2) trimStartPy(raw.substring(space + 2)) else ""
        return when (raw[space + 1]) {
            'A' -> TrFrame.Full(id, payload)
            'D' -> TrFrame.Delta(id, payload)
            'C' -> TrFrame.Closed(id)
            'E' -> TrFrame.Failed(id, payload)
            else -> TrFrame.Unknown(raw.length)
        }
    }

    /**
     * Rebuilds the new payload from the previous one. The delta is tab-separated tokens:
     *  - `+text` appends `unquote_plus(token).strip()`, so a leading `+` becomes a space that `strip()` removes. The same
     *    `strip()` also removes any leading or trailing whitespace the server genuinely meant to insert; pytr behaves
     *    identically and the real stream works with it, so this is reproduced rather than "fixed".
     *  - `=N` copies N characters of the previous payload starting at the cursor and advances it;
     *  - `-N` only advances the cursor.
     * Indices count Python code points, not UTF-16 units, so astral characters (emoji) count as one. Like Python slices,
     * a copy running past the end is clipped.
     */
    fun decodeDelta(previous: String, delta: String): String {
        val prev = previous.codePoints().toArray()
        val out = StringBuilder()
        var cursor = 0L
        for (token in delta.split('\t')) {
            if (token.isEmpty()) continue
            when (token[0]) {
                '+' -> out.append(trimPy(unquotePlus(token)))
                '=', '-' -> {
                    val n = token.substring(1).toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw TrError.Protocol("Aggiornamento incrementale di Trade Republic non valido.")
                    if (token[0] == '=') {
                        val from = minOf(cursor, prev.size.toLong()).toInt()
                        val to = minOf(cursor + n, prev.size.toLong()).toInt()
                        if (to > from) out.append(String(prev, from, to - from))
                    }
                    cursor += n
                }
                else -> throw TrError.Protocol("Aggiornamento incrementale di Trade Republic non riconosciuto.")
            }
        }
        return out.toString()
    }

    /** Python `urllib.parse.unquote_plus`: `+` is a space, `%XX` runs are UTF-8 (malformed bytes become U+FFFD). */
    internal fun unquotePlus(s: String): String {
        val sb = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) { sb.append(String(bytes.toByteArray(), Charsets.UTF_8)); bytes.reset() }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hi = if (c == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
            when {
                hi >= 0 && lo >= 0 -> { bytes.write(hi * 16 + lo); i += 3 }
                c == '+' -> { flush(); sb.append(' '); i++ }
                else -> { flush(); sb.append(c); i++ }
            }
        }
        flush()
        return sb.toString()
    }

    /** Python `str.isspace()` for one character. */
    internal fun isPySpace(c: Char): Boolean = when (c) {
        in '\u0009'..'\u000D', in '\u001C'..' ', '\u0085', ' ', ' ', in ' '..' ',
        ' ', ' ', ' ', ' ', '　' -> true
        else -> false
    }

    internal fun trimPy(s: String) = s.trim { isPySpace(it) }
    internal fun trimStartPy(s: String) = s.trimStart { isPySpace(it) }

    // ---- JSON ------------------------------------------------------------------------------------------------------

    fun parseObject(text: String): JSONObject =
        try { JSONObject(text) } catch (_: Exception) { throw TrError.Protocol("Risposta di Trade Republic non leggibile.") }

    /** Any JSON value (object, array, ...). The `cash` topic answers with an array. */
    fun parseValue(text: String): Any? =
        try { JSONTokener(text).nextValue() } catch (_: Exception) { throw TrError.Protocol("Risposta di Trade Republic non leggibile.") }

    /** A short machine code from an error payload, kept only when it looks like a code (never free server text). */
    fun errorCode(payload: String): String? =
        Regex("\"errorCode\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,60})\"").find(payload)?.groupValues?.get(1)

    // ---- time and money --------------------------------------------------------------------------------------------

    private val OFFSET_NO_COLON: DateTimeFormatter = DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HHmm", "Z").toFormatter()

    /** "2025-10-23T08:26:13.657+0000" (also `Z`, `+00:00`, or no offset, which pytr treats as UTC) as an instant. */
    fun parseTimestamp(text: String?): OffsetDateTime? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(s, OFFSET_NO_COLON) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atOffset(ZoneOffset.UTC) }.getOrNull()
    }

    /** The calendar day in Italy: a late-evening UTC timestamp belongs to the next Italian day. */
    fun parseDate(text: String?): LocalDate? = parseTimestamp(text)?.atZoneSameInstant(ROME)?.toLocalDate()

    fun toBigDecimal(v: Any?): BigDecimal? = when (v) {
        is BigDecimal -> v
        is Number -> runCatching { BigDecimal(v.toString()) }.getOrNull()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        else -> null
    }

    /** Whole cents, half away from zero. Null when the value does not fit a Long. */
    fun toCents(value: BigDecimal): Long? =
        runCatching { value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()

    private val NUMBER = Regex("[-+−]?\\d+(?:[.,  ' ]\\d+)*[.,]?")

    /**
     * First number in a localized display string ("0,685102", "10.640298", "1.234,56 €", "-0,78 €", "0,5 × "). With both
     * separators the last one is the decimal mark; repeated same separators are grouping; a single separator is taken as
     * the decimal mark. That last rule is a guess for the ambiguous "1.000" / "1,000" (TR prints shares without grouping in
     * the fixtures seen, but this is not verified for every locale).
     */
    fun parseDecimal(text: String?): BigDecimal? {
        val m = NUMBER.find(text ?: return null) ?: return null
        val negative = m.value.startsWith('-') || m.value.startsWith('−')
        val s = m.value.trimStart('-', '+', '−').filter { it.isDigit() || it == '.' || it == ',' }
        val dot = s.lastIndexOf('.')
        val comma = s.lastIndexOf(',')
        val decimalAt = when {
            dot >= 0 && comma >= 0 -> maxOf(dot, comma)
            dot >= 0 -> if (s.count { it == '.' } == 1) dot else -1
            comma >= 0 -> if (s.count { it == ',' } == 1) comma else -1
            else -> -1
        }
        val whole = (if (decimalAt >= 0) s.substring(0, decimalAt) else s).filter { it.isDigit() }.ifEmpty { "0" }
        val frac = if (decimalAt >= 0) s.substring(decimalAt + 1).filter { it.isDigit() } else ""
        val value = runCatching { BigDecimal(if (frac.isEmpty()) whole else "$whole.$frac") }.getOrNull() ?: return null
        return if (negative) value.negate() else value
    }
}

/** The few URLs this connector may touch, and how to read a WebView address. Pure so that it can be tested. */
object TrUrls {
    const val APP_URL = "https://app.traderepublic.com/"
    const val LOGIN_URL = "https://app.traderepublic.com/login"
    const val API_ORIGIN = "https://api.traderepublic.com"
    const val WS_URL = "wss://api.traderepublic.com"
    const val SESSION_URL = "$API_ORIGIN/api/v1/auth/web/session"
    const val ACCOUNT_URL = "$API_ORIGIN/api/v2/auth/account"

    /** Read-only: the transport refuses any GET that is not one of these two. */
    fun isAllowedGet(url: String) = url == SESSION_URL || url == ACCOUNT_URL
    fun isAllowedSocket(url: String) = url == WS_URL

    fun isTrHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "traderepublic.com" || h.endsWith(".traderepublic.com")
    }

    /** The WebView may only navigate to https pages on traderepublic.com. */
    fun isAllowedNavigation(url: String?): Boolean {
        val u = parse(url) ?: return false
        return u.scheme.equals("https", ignoreCase = true) && isTrHost(u.host)
    }

    enum class Page { LOGIN, PRE_LOGIN, ROOT, APP, OTHER }

    private val PRE_LOGIN = setOf("signup", "sign-up", "register", "registrieren", "reset", "forgot", "forgot-password", "password")

    fun classify(url: String?): Page {
        val u = parse(url) ?: return Page.OTHER
        if (!u.host.equals("app.traderepublic.com", ignoreCase = true)) return Page.OTHER
        val segments = (u.path ?: "").split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
        return when {
            "login" in segments -> Page.LOGIN
            segments.any { it in PRE_LOGIN } -> Page.PRE_LOGIN
            segments.isEmpty() -> Page.ROOT
            else -> Page.APP
        }
    }

    /**
     * The visible login has finished when the page left the login and sign-up routes. The bare root only counts when a
     * session cookie name is present, because the web app also shows it to signed-out visitors. The sync's own session probe
     * remains the authority: a wrong guess here costs one extra 401, not wrong data.
     */
    fun looksLoggedIn(url: String?, sessionCookie: Boolean): Boolean = when (classify(url)) {
        Page.APP -> true
        Page.ROOT -> sessionCookie
        else -> false
    }

    /** Cookie names that suggest a Trade Republic session (names only; values are never read). Unverified beyond pytr/TR habits. */
    fun hasSessionCookie(names: Collection<String>): Boolean =
        names.any { it.equals("tr_session", true) || it.equals("tr_refresh", true) || it.startsWith("tr_", ignoreCase = true) }

    private fun parse(url: String?): URI? = url?.let { runCatching { URI(it) }.getOrNull() }?.takeIf { it.scheme != null && it.host != null }
}
