package xyz.grtlabs.pilastri.sync.gmail

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import xyz.grtlabs.pilastri.sync.gmail.GmailApiException.Kind

/** The only network seam of the Gmail connector, so list/get parsing and error mapping are tested without a socket. */
internal fun interface GmailHttp {
    /** GET [url] with a bearer token. The token is only ever put in the Authorization header: never logged, never in a URL. */
    suspend fun get(url: String, bearer: String): GmailReply
}

internal data class GmailReply(val status: Int, val body: String)

internal class OkHttpGmailHttp(private val client: OkHttpClient = shared) : GmailHttp {
    override suspend fun get(url: String, bearer: String): GmailReply = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer").header("Accept", "application/json").get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { GmailReply(it.code, it.body?.string().orEmpty()) }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(reply)
            }
        })
    }

    companion object {
        /** No logging interceptor on purpose: requests carry the access token. */
        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        }
    }
}

/** A failure of the Gmail API, with an Italian message that never contains the token or the response body. */
internal class GmailApiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { AUTH, NOT_CONFIGURED, RATE_LIMIT, NETWORK, SERVER, BAD_RESPONSE }
}

internal data class MailPage(val ids: List<String>, val next: String?)

/** Where messages come from. The real one is [GmailRestSource]; tests use a fake. */
internal interface MailSource {
    suspend fun list(query: String, pageToken: String?, pageSize: Int): MailPage

    /** Null when the message is gone (deleted between list and get) or unreadable. */
    suspend fun fetch(id: String): MailMessage?
}

/** Gmail REST v1 `users.messages.list` / `users.messages.get` with the read-only scope. */
internal class GmailRestSource(private val http: GmailHttp, private val token: String) : MailSource {
    @Volatile private var useFields = true

    override suspend fun list(query: String, pageToken: String?, pageSize: Int): MailPage {
        val url = BASE.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", pageSize.coerceIn(1, 500).toString())
            .addQueryParameter("fields", "nextPageToken,messages/id")
            .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
            .build().toString()
        val json = parse(request(url))
        val ids = ArrayList<String>()
        val arr = json.optJSONArray("messages")
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.takeIf { ID.matches(it) }?.let(ids::add)
        return MailPage(ids, json.optString("nextPageToken").takeIf { it.isNotEmpty() })
    }

    override suspend fun fetch(id: String): MailMessage? {
        if (!ID.matches(id)) return null
        var reply = http.callMapped(messageUrl(id, useFields), token)
        if (reply.status == 400 && useFields) {
            // The partial-response mask is only an optimization: if Gmail rejects it, go without and keep going.
            useFields = false
            reply = http.callMapped(messageUrl(id, false), token)
        }
        if (reply.status == 404) return null
        return MailText.fromGmailJson(parse(ensureOk(reply)))
    }

    private fun messageUrl(id: String, fields: Boolean): String = BASE.toHttpUrl().newBuilder().addPathSegment(id)
        .addQueryParameter("format", "full")
        .apply { if (fields) addQueryParameter("fields", MESSAGE_FIELDS) }
        .build().toString()

    private suspend fun request(url: String): GmailReply = ensureOk(http.callMapped(url, token))

    private fun parse(reply: GmailReply): JSONObject = try {
        JSONObject(reply.body)
    } catch (_: JSONException) {
        throw GmailApiException(GmailApiException.Kind.BAD_RESPONSE, "Risposta di Gmail non leggibile.")
    }

    companion object {
        const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me/messages"
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** Headers, MIME structure and text bodies only; attachments are skipped at read time (parts with a filename). */
        val MESSAGE_FIELDS: String = run {
            fun part(depth: Int): String =
                if (depth == 0) "mimeType,filename,headers,body/data" else "mimeType,filename,headers,body/data,parts(${part(depth - 1)})"
            "id,internalDate,payload(${part(5)})"
        }

        private suspend fun GmailHttp.callMapped(url: String, token: String): GmailReply = try {
            get(url, token)
        } catch (e: IOException) {
            throw GmailApiException(GmailApiException.Kind.NETWORK, "Connessione a Gmail non riuscita: controlla la rete e riprova.")
        }

        /** Maps a non-2xx reply to an exception. Only Google's `reason` words are read from the body. */
        internal fun ensureOk(reply: GmailReply): GmailReply {
            if (reply.status in 200..299) return reply
            val reason = Regex(""""(?:reason|status)"\s*:\s*"([A-Za-z_]+)"""").findAll(reply.body).map { it.groupValues[1] }.toList()
            throw when {
                reply.status == 401 -> GmailApiException(Kind.AUTH, "Accesso a Gmail scaduto o revocato (401).")
                reply.status == 403 && reason.any { it == "accessNotConfigured" || it == "SERVICE_DISABLED" } ->
                    GmailApiException(Kind.NOT_CONFIGURED, GmailSetup.API_DISABLED_IT)
                reply.status == 403 && reason.any { it == "insufficientPermissions" || it == "ACCESS_TOKEN_SCOPE_INSUFFICIENT" } ->
                    GmailApiException(Kind.AUTH, "Il permesso di lettura di Gmail non è stato concesso (403).")
                reply.status == 403 && reason.any { it.contains("ateLimit", ignoreCase = true) || it == "quotaExceeded" } ->
                    GmailApiException(Kind.RATE_LIMIT, "Gmail ha limitato le richieste (403). Riprova tra qualche minuto.")
                reply.status == 403 -> GmailApiException(Kind.AUTH, "Gmail ha negato l'accesso (403).")
                reply.status == 429 -> GmailApiException(Kind.RATE_LIMIT, "Troppe richieste a Gmail (429). Riprova tra qualche minuto.")
                reply.status >= 500 -> GmailApiException(Kind.SERVER, "Gmail è temporaneamente non disponibile (${reply.status}). Riprova tra poco.")
                else -> GmailApiException(Kind.BAD_RESPONSE, "Richiesta a Gmail rifiutata (${reply.status}).")
            }
        }
    }
}

/** The bounded Gmail searches. Narrow queries first, so a broad one cannot starve them when the cap is reached. */
internal object GmailQueries {
    private val day = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    /**
     * Gmail's `after:`/`before:` compare in Pacific time; slack keeps the boundary days in (the scanner then filters exactly). With [until],
     * the window is `since..until`: how a host reaches back past the message cap, one older window at a time.
     */
    fun build(since: LocalDate, until: LocalDate? = null): List<String> {
        val after = "after:" + since.minusDays(1).format(day) + (until?.let { " before:" + it.plusDays(2).format(day) } ?: "")
        val lenders = Lenders.dedicatedDomains.joinToString(" OR ")
        val paypal = Lenders.PAYPAL.domains.joinToString(" OR ")
        // Any other sender whose subject says rata/rate: card issuers, a shop's own financing, lenders no parser knows. The broadest search, so last.
        val subjects = listOf(
            "pagamento a rate", "pagamento rateale", "piano rate", "piano di rimborso", "rateizzazione", "rata pagata", "prossima rata",
            "paga in 3", "in 3 rate", "in 4 rate", "pay in 3", "rata", "rate", "rateale", "rateali", "rateizzato",
        ).joinToString(" OR ") { "\"$it\"" }
        return listOf(
            "$after from:($paypal) (\"paga in 3\" OR \"paga a rate\" OR \"pay in 3\" OR rata OR rate)",
            "$after from:($lenders) -category:promotions",
            "$after subject:($subjects) -from:($lenders) -from:($paypal)",
        )
    }
}
