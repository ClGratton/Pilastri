package xyz.grtlabs.pilastri.sync.auto

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.isybank.IsybankParser
import xyz.grtlabs.pilastri.sync.paypal.PaypalParser

/** One HTTP exchange, without following redirects. The body is read up to maxBytes; [HttpReply.truncated] says it was longer. */
interface SessionHttp {
    @Throws(IOException::class)
    fun execute(call: HttpCall, maxBytes: Int): HttpReply
}

class HttpReply(val code: Int, headers: Map<String, List<String>>, val body: ByteArray, val truncated: Boolean = false) {
    private val byName = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
    fun header(name: String): String? = byName[name.lowercase(Locale.ROOT)]?.firstOrNull()
    fun headers(name: String): List<String> = byName[name.lowercase(Locale.ROOT)].orEmpty()
}

/** The WebView cookie jar, seen per URL. The fetcher only ever asks for provider URLs; implementations check again. */
interface SessionCookies {
    /** The Cookie header for [url], or null. */
    fun header(url: String): String?
    /** One cookie's value for [url] (a double-submit CSRF token), or null. */
    fun value(url: String, name: String): String?
    /** Set-Cookie headers the provider sent for [url], so the stored session follows rotated cookies. */
    fun store(url: String, setCookie: List<String>)
}

/**
 * Fetches a provider's export with the stored session, following an [HttpFetchSpec]. Security as in the existing download code:
 * https only, every hop checked against the provider's hosts BEFORE its cookies are attached, redirects followed by hand (a redirect
 * to the sign-in page means signed out; one off the provider's hosts is refused), the body capped at the spec's size, and nothing
 * logged. The response goes to the provider's own parser, or to [JsonMovements] for a JSON list.
 */
class HttpSessionFetcher(
    override val site: ProviderSite,
    private val spec: HttpFetchSpec?,
    private val http: SessionHttp,
    private val cookies: SessionCookies,
    private val userAgent: String? = null,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionFetcher {
    override val configured: Boolean get() = spec != null
    override val needsPage: Boolean get() = spec?.needsPage == true
    override val navigates: Boolean get() = false

    override suspend fun fetch(range: SyncRange, page: PageAccess?, mayNavigate: Boolean): FetchOutcome {
        val spec = spec ?: return FetchOutcome.NotConfigured("${site.label}: $NOT_CONFIGURED")
        val values = HashMap<String, String>()
        val fromPage = spec.pageValues.filterValues { it !is PageValue.Cookie }
        if (fromPage.isNotEmpty()) {
            if (page == null) return FetchOutcome.NotReady
            if (!site.isProviderUrl(page.currentUrl()) || site.classify(page.currentUrl()) == SitePage.LOGIN) {
                if (!mayNavigate) return FetchOutcome.NotReady
                val landed = page.load(spec.pageUrl ?: site.appUrl) ?: return FetchOutcome.Failed("Impossibile aprire ${site.label}. Controlla la connessione.")
                when (site.classify(landed)) {
                    SitePage.LOGIN, SitePage.SCA -> return FetchOutcome.SignedOut
                    SitePage.CHALLENGE -> return FetchOutcome.Challenge
                    SitePage.FOREIGN -> return FetchOutcome.Failed("${site.label} ha aperto una pagina esterna.")
                    else -> Unit
                }
            }
            values += PageValues.decode(page.evaluate(PageValues.script(fromPage)))
        }
        spec.pageValues.forEach { (key, v) -> if (v is PageValue.Cookie) cookies.value(site.appUrl, v.name)?.let { values[key] = it } }

        val parts = ArrayList<ImportBatch>()
        for (window in range.windows(spec.windowDays)) {
            val call = try {
                SpecRequest.build(spec, site, window, values)
            } catch (_: SpecRequest.MissingValue) {
                return FetchOutcome.SignedOut
            } catch (e: IllegalArgumentException) {
                return FetchOutcome.Failed(e.message ?: "Formato di ${site.label} non valido.")
            }
            when (val raw = withContext(io) { exchange(call, spec.maxBytes) }) {
                is Raw.Bytes -> try {
                    parts += decode(spec, raw, window)
                } catch (e: IllegalArgumentException) {
                    return FetchOutcome.Failed(e.message ?: "Risposta di ${site.label} non leggibile.")
                }
                is Raw.Outcome -> return raw.outcome
            }
        }
        return FetchOutcome.Fetched(mergeBatches(parts))
    }

    private sealed interface Raw {
        class Bytes(val data: ByteArray, val type: String, val fileName: String?) : Raw
        class Outcome(val outcome: FetchOutcome) : Raw
    }

    private fun exchange(first: HttpCall, maxBytes: Int): Raw {
        var call = first
        for (hop in 0..MAX_REDIRECTS) {
            // Checked before anything is attached: cookies never leave the provider's hosts.
            if (!site.isProviderUrl(call.url)) return Raw.Outcome(FetchOutcome.Failed("Richiesta bloccata: indirizzo fuori dal sito di ${site.label}."))
            val headers = call.headers.toMutableList()
            if (userAgent != null && call.header("User-Agent") == null) headers += "User-Agent" to userAgent
            cookies.header(call.url)?.takeIf { it.isNotBlank() }?.let { headers += "Cookie" to it }
            val reply = try {
                http.execute(HttpCall(call.method, call.url, headers, call.body, call.contentType), maxBytes)
            } catch (_: IOException) {
                return Raw.Outcome(FetchOutcome.Failed("Impossibile raggiungere ${site.label}. Controlla la connessione."))
            }
            reply.headers("Set-Cookie").takeIf { it.isNotEmpty() }?.let { cookies.store(call.url, it) }
            if (reply.code in 300..399 && reply.code != 304) {
                val next = reply.header("Location")?.let { resolve(call.url, it) }
                    ?: return Raw.Outcome(FetchOutcome.Failed("Risposta di ${site.label} non valida."))
                when (site.classify(next)) {
                    SitePage.LOGIN, SitePage.SCA -> return Raw.Outcome(FetchOutcome.SignedOut)
                    SitePage.CHALLENGE -> return Raw.Outcome(FetchOutcome.Challenge)
                    SitePage.FOREIGN -> return Raw.Outcome(FetchOutcome.Failed("Richiesta bloccata: ${site.label} rimanda fuori dal suo sito."))
                    else -> Unit
                }
                call = if (reply.code == 307 || reply.code == 308) HttpCall(call.method, next, call.headers, call.body, call.contentType)
                else HttpCall("GET", next, call.headers, null, null)
                continue
            }
            if (reply.code == 401 || reply.code == 403 || reply.code == 419 || reply.code == 440) return Raw.Outcome(FetchOutcome.SignedOut)
            if (reply.code !in 200..299) return Raw.Outcome(FetchOutcome.Failed("${site.label} ha risposto con un errore (${reply.code}). Riprova più tardi."))
            if (reply.truncated) return Raw.Outcome(FetchOutcome.Failed("La risposta di ${site.label} supera il limite di ${(maxBytes / (1024 * 1024)).coerceAtLeast(1)} MB."))
            val type = reply.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            // A web page where data was expected is the sign-in page: the session is gone.
            if (type.startsWith("text/html")) return Raw.Outcome(FetchOutcome.SignedOut)
            return Raw.Bytes(reply.body, type, fileName(reply.header("Content-Disposition")))
        }
        return Raw.Outcome(FetchOutcome.Failed("Troppi reindirizzamenti da ${site.label}."))
    }

    private fun decode(spec: HttpFetchSpec, raw: Raw.Bytes, window: SyncRange): ImportBatch {
        if (raw.data.isEmpty()) throw IllegalArgumentException("${site.label} ha inviato una risposta vuota.")
        return when (spec.response) {
            ResponseKind.JSON -> JsonMovements.read(raw.data, spec.json!!, site.provider, window, now())
            ResponseKind.FILE -> {
                val name = raw.fileName ?: spec.fileName
                when (site.provider) {
                    ImportProvider.ISYBANK -> IsybankParser.parse(raw.data, name, now())
                    ImportProvider.PAYPAL -> PaypalParser.parse(raw.data, name, now())
                    else -> throw IllegalArgumentException("Fornitore non gestito.")
                }
            }
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        private val SAFE_NAME = Regex("[A-Za-z0-9._ -]{1,80}")

        fun resolve(base: String, location: String): String? = try {
            URI(base).resolve(location.trim()).toString()
        } catch (_: Exception) {
            null
        }

        fun fileName(disposition: String?): String? {
            if (disposition == null) return null
            val star = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(disposition)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(disposition)?.groupValues?.get(1)
            return (star ?: plain)?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { SAFE_NAME.matches(it) }
        }
    }
}

/** Reads declared session values from the current provider page; never logged. */
object PageValues {
    /** A script that evaluates to a JSON string `{"name":"value",…}`; names and keys are embedded as JSON strings. */
    fun script(values: Map<String, PageValue>): String {
        val parts = values.entries.joinToString("") { (key, v) ->
            val k = JSONObject.quote(key)
            when (v) {
                is PageValue.Meta -> "put($k,meta(${JSONObject.quote(v.name)}));"
                is PageValue.Storage -> "put($k,store(${v.local},${JSONObject.quote(v.key)},${v.jsonPath?.let(JSONObject::quote) ?: "null"}));"
                is PageValue.Global -> "put($k,walk(window,${JSONObject.quote(v.path)}));"
                is PageValue.Cookie -> ""
            }
        }
        return "(function(){var o={};" +
            "function put(k,v){if(typeof v==='number')v=String(v);if(typeof v==='string'&&v.length>0&&v.length<=8192)o[k]=v;}" +
            "function walk(x,p){var s=p.split('.');for(var i=0;i<s.length;i++){if(x==null)return null;x=x[s[i]];}return x;}" +
            "function meta(n){var m=document.getElementsByTagName('meta');for(var i=0;i<m.length;i++){if(m[i].getAttribute('name')===n||m[i].getAttribute('property')===n)return m[i].getAttribute('content');}return null;}" +
            "function store(l,k,p){try{var v=(l?window.localStorage:window.sessionStorage).getItem(k);if(v==null||p==null)return v;return walk(JSON.parse(v),p);}catch(e){return null;}}" +
            "try{$parts}catch(e){}return JSON.stringify(o);})()"
    }

    /** [result] is what the script evaluated to: a JSON string holding the JSON object (evaluateJavascript double-encodes it). */
    fun decode(result: String?): Map<String, String> {
        if (result.isNullOrBlank() || result == "null") return emptyMap()
        return try {
            val inner = JSONTokener(result).nextValue() as? String ?: result
            val o = JSONObject(inner)
            o.keys().asSequence().mapNotNull { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
