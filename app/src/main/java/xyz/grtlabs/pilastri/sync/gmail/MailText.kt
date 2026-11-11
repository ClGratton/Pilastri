package xyz.grtlabs.pilastri.sync.gmail

import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import org.json.JSONObject

/** One message as the parsers see it: the headers they need and plain text. Never stored; only facts extracted from it are. */
internal data class MailMessage(val id: String, val from: String, val subject: String, val date: LocalDate, val text: String, val html: String = "")
// [html] is the raw HTML part, kept only so a link that exists as an href target (button text without the URL) can be found; parsers ignore it.

/** Gmail `messages.get` JSON (format=full) to a [MailMessage]: MIME walk, base64url, HTML to text. */
internal object MailText {
    /** Longest text kept per message: receipts are short, and this bounds parsing cost and what could ever be sent to an AI. */
    const val MAX_CHARS = 20_000
    private const val MAX_RAW_CHARS = 400_000
    private const val MAX_DEPTH = 8
    val ZONE: ZoneId = ZoneId.of("Europe/Rome")

    fun decodeBase64Url(data: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(data.trim())
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Null when the JSON is not a message with an id, headers and a date. */
    fun fromGmailJson(json: JSONObject): MailMessage? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val payload = json.optJSONObject("payload") ?: return null
        val headers = headerMap(payload)
        val date = json.optString("internalDate").toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ZONE).toLocalDate() }
            ?: headers["date"]?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZONE).toLocalDate() }.getOrNull() }
            ?: return null
        val plain = StringBuilder()
        val html = StringBuilder()
        collect(payload, plain, html, 0)
        val plainText = cleanText(plain.toString())
        val htmlText = if (html.isEmpty()) "" else htmlToText(html.toString())
        // Prefer the plain alternative unless the HTML says clearly more (schedules often live only in the HTML tables).
        val text = if (plainText.isEmpty() || htmlText.length > plainText.length * 1.3 + 50) htmlText.ifEmpty { plainText } else plainText
        return MailMessage(id, headers["from"].orEmpty(), (headers["subject"].orEmpty()).replace(Regex("\\s+"), " ").trim(), date, text.take(MAX_CHARS), html.toString().take(MAX_RAW_CHARS))
    }

    private fun headerMap(part: JSONObject): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = part.optJSONArray("headers") ?: return out
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val name = h.optString("name").lowercase()
            if (name.isNotEmpty() && name !in out) out[name] = h.optString("value")
        }
        return out
    }

    private fun charsetOf(part: JSONObject): Charset {
        val contentType = headerMap(part)["content-type"].orEmpty()
        val name = Regex("""charset\s*=\s*"?([A-Za-z0-9_\-:.]+)""", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    private fun collect(part: JSONObject, plain: StringBuilder, html: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH || plain.length + html.length > MAX_RAW_CHARS) return
        val mime = part.optString("mimeType").lowercase()
        val isAttachment = part.optString("filename").isNotEmpty()
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (!isAttachment && data.isNotEmpty() && (mime == "text/plain" || mime == "text/html")) {
            val bytes = decodeBase64Url(data)
            if (bytes != null) {
                val text = String(bytes, charsetOf(part))
                val target = if (mime == "text/plain") plain else html
                if (target.isNotEmpty()) target.append('\n')
                target.append(text)
            }
        }
        val children = part.optJSONArray("parts") ?: return
        for (i in 0 until children.length()) children.optJSONObject(i)?.let { collect(it, plain, html, depth + 1) }
    }

    /** Plain text: invisible characters out, trailing spaces trimmed, blank-line runs collapsed. */
    fun cleanText(text: String): String {
        val lines = ItalianFormats.normalizeSpaces(text).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>()
        for (raw in lines) {
            val line = raw.replace('\t', ' ').replace(Regex(" {2,}"), " ").trim()
            if (line.isEmpty()) continue // blank lines carry no meaning for the parsers, and would split a date from its amount
            out += line
        }
        return out.joinToString("\n").trim()
    }

    private val dropBlocks = Regex("""<(script|style|head|title)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comments = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val lineBreaks = Regex("""<\s*br\s*/?\s*>|</\s*(?:p|div|tr|li|ul|ol|h[1-6]|table|section|article|blockquote|center)\s*>|<\s*(?:p|div|tr|li|h[1-6]|table)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val cellBreaks = Regex("""</\s*t[dh]\s*>""", RegexOption.IGNORE_CASE)
    private val tags = Regex("""<[^>]*>""")
    private val entity = Regex("""&(#x[0-9A-Fa-f]{1,6}|#\d{1,7}|[A-Za-z]{2,8});""")
    private val namedEntities = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "euro" to "€", "egrave" to "è", "eacute" to "é",
        "agrave" to "à", "ograve" to "ò", "igrave" to "ì", "ugrave" to "ù", "Egrave" to "È", "Eacute" to "É", "Agrave" to "À", "ordm" to "º",
        "ordf" to "ª", "deg" to "°", "middot" to "·", "bull" to "•", "ndash" to "–", "mdash" to "—", "rsquo" to "'", "lsquo" to "'",
        "ldquo" to "\"", "rdquo" to "\"", "hellip" to "…", "times" to "×", "shy" to "", "zwnj" to "", "zwj" to "",
    )

    /** HTML to readable text: block ends and table rows become lines, cells are space-separated, entities are decoded. */
    fun htmlToText(html: String): String {
        var s = comments.replace(dropBlocks.replace(html, " "), " ")
        s = lineBreaks.replace(s, "\n")
        s = cellBreaks.replace(s, " ")
        s = tags.replace(s, "") // inline tags vanish without splitting "€<b>33</b>,33"; cells and blocks were handled above
        s = entity.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePointString) ?: " "
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePointString) ?: " "
                else -> namedEntities[body] ?: " "
            }
        }
        return cleanText(s)
    }

    private fun codePointString(cp: Int): String = if (cp in 1..0x10FFFF && !(cp in 0xD800..0xDFFF)) String(Character.toChars(cp)) else " "
}
