package xyz.grtlabs.pilastri.sync.auto

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

enum class BodyType(val contentType: String) {
    JSON("application/json; charset=utf-8"),
    FORM("application/x-www-form-urlencoded; charset=utf-8"),
}

/** FILE: bytes for the provider's own file parser (isybank: [xyz.grtlabs.pilastri.sync.isybank.IsybankParser]). JSON: [JsonMapping]. */
enum class ResponseKind { FILE, JSON }

/** A value the request needs from the signed-in session. Values are never logged. */
sealed interface PageValue {
    /** `<meta name|property="…" content="…">` of the current provider page (a CSRF token, typically). */
    data class Meta(val name: String) : PageValue
    /** A cookie of the provider's jar (double-submit CSRF). Read in Kotlin, never from the page. */
    data class Cookie(val name: String) : PageValue
    /** sessionStorage (or localStorage) item of the provider page, optionally a dotted path inside its JSON (a bearer token). */
    data class Storage(val key: String, val local: Boolean = false, val jsonPath: String? = null) : PageValue
    /** A string at a dotted path under `window`. */
    data class Global(val path: String) : PageValue

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.:-]{1,120}")
        private val PATH = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,8}")
        private val JSON_PATH = Regex("[A-Za-z0-9_$-]{1,60}(\\.[A-Za-z0-9_$-]{1,60}){0,8}")

        fun validate(v: PageValue) = require(
            when (v) {
                is Meta -> NAME.matches(v.name)
                is Cookie -> NAME.matches(v.name)
                is Storage -> NAME.matches(v.key) && (v.jsonPath == null || JSON_PATH.matches(v.jsonPath))
                is Global -> PATH.matches(v.path)
            },
        ) { "Valore di sessione non valido nel formato." }
    }
}

/**
 * The provider's own request for the movements (the "Esporta Excel" button, or the list call), as recorded from a signed-in
 * session: paths and keys only. Templates take these placeholders, escaped for where they stand (URL, header, JSON or form body):
 *
 *  - `{from}` / `{to}`: the window's first and last day in [dateFormat];
 *  - `{fromEpochMs}` / `{toEpochMs}`: start of the first day and end of the last day, Europe/Rome, in milliseconds;
 *  - `{uuid}`: a fresh random id;
 *  - `{page:NAME}`: the session value [pageValues] names NAME.
 *
 * Anything else in braces that looks like a placeholder is an error, so a typo fails loudly instead of being sent.
 */
data class HttpFetchSpec(
    val method: String,
    val urlTemplate: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String? = null,
    val bodyType: BodyType? = null,
    val dateFormat: String = "yyyy-MM-dd",
    val pageValues: Map<String, PageValue> = emptyMap(),
    /** The page to load (hidden) before reading non-cookie [pageValues]; default: the site's signed-in page. */
    val pageUrl: String? = null,
    val response: ResponseKind = ResponseKind.FILE,
    /** File name given to the parser when the response has no Content-Disposition (its extension guides the parser). */
    val fileName: String = "movimenti.xlsx",
    val json: JsonMapping? = null,
    /** The longest period one request may ask for; longer ranges are fetched in windows and merged. */
    val windowDays: Int = 365,
    val maxBytes: Int = 10 * 1024 * 1024,
) {
    init {
        require(method == "GET" || method == "POST") { "Metodo non supportato." }
        require(bodyTemplate == null || (method == "POST" && bodyType != null)) { "Corpo della richiesta incoerente." }
        require(response != ResponseKind.JSON || json != null) { "Manca la mappatura JSON." }
        require(windowDays in 1..3660 && maxBytes in 1..(20 * 1024 * 1024))
        pageValues.forEach { (k, v) -> require(KEY.matches(k)) { "Nome di valore non valido." }; PageValue.validate(v) }
        headers.keys.forEach { require(HEADER_NAME.matches(it) && it.lowercase(Locale.ROOT) !in FORBIDDEN_HEADERS) { "Intestazione non consentita: $it" } }
        DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT)
    }

    /** Non-cookie values need the provider page. */
    val needsPage: Boolean get() = pageValues.values.any { it !is PageValue.Cookie }

    internal companion object {
        val KEY = Regex("[A-Za-z0-9_-]{1,40}")
        val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
        val FORBIDDEN_HEADERS = setOf("cookie", "host", "content-length", "transfer-encoding", "connection", "content-type")
    }
}

/**
 * The request shapes recorded from the user's own signed-in sessions (paths and keys only, never values). The primary agent fills
 * these; until then the fetcher answers "formato non ancora configurato".
 */
object RecordedSpec {
    /**
     * isybank's "Esporta Excel" of the movements list. NOT RECORDED YET. Shape to fill (illustrative only, not a real endpoint):
     *
     * ```
     * HttpFetchSpec(
     *     method = "POST",
     *     urlTemplate = "https://www.isybank.com/<path of the export call>",
     *     headers = mapOf("Accept" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "X-Csrf-Token" to "{page:csrf}"),
     *     bodyType = BodyType.JSON,
     *     bodyTemplate = """{"<from key>":"{from}","<to key>":"{to}"}""",
     *     dateFormat = "yyyy-MM-dd",
     *     pageValues = mapOf("csrf" to PageValue.Meta("<meta name>")),
     *     response = ResponseKind.FILE,
     *     fileName = "movimenti.xlsx",
     *     windowDays = 365,
     * )
     * ```
     */
    val ISYBANK: HttpFetchSpec? = null

    /** PayPal has no list call: the activity page is server-rendered and read in a hidden WebView (recorded 2026-10-31). */
    val PAYPAL: PaypalActivitySpec = PaypalActivitySpec()
}

/** One request, ready for [SessionHttp]. Cookies are not part of it: the fetcher adds them per hop, for provider hosts only. */
class HttpCall(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
    val contentType: String?,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    override fun toString() = "HttpCall($method)"
}

/** Builds [HttpCall]s from an [HttpFetchSpec]. Throws [IllegalArgumentException] (Italian) for a spec or value that is not acceptable. */
object SpecRequest {
    private val ROME: ZoneId = ZoneId.of("Europe/Rome")
    private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9]*)(?::([A-Za-z0-9_-]{1,40}))?\\}")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")

    fun build(spec: HttpFetchSpec, site: ProviderSite, window: SyncRange, values: Map<String, String>, uuid: () -> String = { UUID.randomUUID().toString() }): HttpCall {
        val dates = DateTimeFormatter.ofPattern(spec.dateFormat, Locale.ROOT)
        val id by lazy(uuid)
        fun resolve(name: String, arg: String?): String = when {
            name == "from" && arg == null -> window.from.format(dates)
            name == "to" && arg == null -> window.to.format(dates)
            name == "fromEpochMs" && arg == null -> window.from.atStartOfDay(ROME).toInstant().toEpochMilli().toString()
            name == "toEpochMs" && arg == null -> (window.to.plusDays(1).atStartOfDay(ROME).toInstant().toEpochMilli() - 1).toString()
            name == "uuid" && arg == null -> id
            name == "page" && arg != null -> {
                require(arg in spec.pageValues) { "Valore di sessione non dichiarato: $arg" }
                values[arg] ?: throw MissingValue(arg)
            }
            else -> throw IllegalArgumentException("Segnaposto sconosciuto nel formato: {$name}")
        }
        fun fill(template: String, escape: (String) -> String) = PLACEHOLDER.replace(template) { m ->
            escape(resolve(m.groupValues[1], m.groupValues[2].ifEmpty { null }))
        }

        val url = fill(spec.urlTemplate) { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        require(site.isProviderUrl(url)) { "Indirizzo fuori dal sito del fornitore." }
        val headers = spec.headers.map { (name, template) ->
            val value = fill(template) { it }
            require(!CONTROL.containsMatchIn(value) && value.length <= 8192) { "Valore di intestazione non valido." }
            name to value
        }
        val body = spec.bodyTemplate?.let { template ->
            when (spec.bodyType!!) {
                BodyType.JSON -> fill(template) { JSONObject.quote(it).let { q -> q.substring(1, q.length - 1) } }
                BodyType.FORM -> fill(template) { URLEncoder.encode(it, "UTF-8") }
            }.toByteArray(StandardCharsets.UTF_8)
        }
        val contentType = if (spec.method == "POST") (spec.bodyType ?: BodyType.FORM).contentType else null
        return HttpCall(spec.method, url, headers, if (spec.method == "POST") body ?: ByteArray(0) else null, contentType)
    }

    /** A declared session value is absent: the session does not provide it (signed out, or a stale page). */
    class MissingValue(val name: String) : IllegalArgumentException("Valore di sessione assente.")
}
