package xyz.grtlabs.pilastri.sync.eb

import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import xyz.grtlabs.pilastri.sync.auto.JsonMovements

/**
 * Errors of the Enable Banking API. Messages are Italian and user-facing; they never carry a token, an id, a code from a redirect,
 * or any part of a response body (which holds account data).
 */
sealed class EbException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** HTTP 429 or ASPSP_RATE_LIMIT_EXCEEDED: banks allow about 4 unattended reads a day. The docs advise waiting about 6 hours. */
    class RateLimited(val retryAfterSeconds: Long?) : EbException("La banca ha limitato le letture automatiche: riprova tra qualche ora.")
    /** The session is gone: closed, expired or revoked (in the bank app). Linking again is the only fix. */
    class SessionGone(val errorCode: String?) : EbException("L'autorizzazione alla banca è scaduta o è stata revocata: collega di nuovo il conto.")
    /** 401/403 without a session error: the application JWT or the application itself was refused. */
    class Unauthorized(val httpCode: Int) : EbException("Enable Banking ha rifiutato la chiave dell'applicazione ($httpCode).")
    /** 422 PSU_HEADER_*: the bank wants the PSU headers (all or none). */
    class PsuHeaders(val errorCode: String?) : EbException("La banca richiede dati del dispositivo che non sono disponibili.")
    class Rejected(val httpCode: Int, val errorCode: String?) : EbException("Enable Banking ha rifiutato la richiesta ($httpCode).")
    class Server(val httpCode: Int) : EbException("Enable Banking non risponde ($httpCode): riprova più tardi.")
    class Network(cause: Throwable) : EbException("Impossibile raggiungere Enable Banking: controlla la connessione.", cause)
    /** The answer is not the documented shape. [detail] names a field, never a value. */
    class Malformed(val detail: String) : EbException("Risposta di Enable Banking non leggibile.")
    /** Paging could not be completed (a repeated or endless continuation key). */
    class Incomplete(val reason: String) : EbException("Lettura dei movimenti incompleta.")
}

data class EbAspsp(
    val name: String,
    val country: String,
    val maximumConsentSeconds: Long?,
    val psuTypes: List<String>,
    val authMethods: List<String>,
    val requiredPsuHeaders: List<String>,
    val beta: Boolean,
)

data class EbAuthRequest(
    val aspspName: String,
    val country: String,
    val state: String,
    val redirectUrl: String,
    val validUntil: Instant,
    val psuType: String = "personal",
    val language: String? = "it",
)

data class EbAuthStart(val url: String, val authorizationId: String?)

/** One account of a session. [uid] is valid for that session only; [identificationHash] identifies the account across sessions. */
data class EbAccount(val uid: String, val identificationHash: String?, val label: String, val currency: String?)

class EbSession(val sessionId: String, val accounts: List<EbAccount>, val validUntil: Instant?, val aspspName: String?, val aspspCountry: String?) {
    override fun toString() = "EbSession(***)"
}

/** The state of an existing session ([status] is the API's text, e.g. AUTHORIZED). */
class EbSessionInfo(val status: String?, val validUntil: Instant?, val accounts: List<EbAccount>) {
    override fun toString() = "EbSessionInfo($status)"
}

data class EbBalance(val type: String?, val amountText: String?, val currency: String?, val referenceDate: LocalDate?, val lastChange: Instant?)

/**
 * A transaction as the API gives it, fields not interpreted. The amount is unsigned (the sign comes from [indicator]); anything
 * the API left out is null, and the mapper decides whether the row is usable. Never logged or printed.
 */
class EbTransaction(
    val amountText: String?,
    val currency: String?,
    val indicator: String?,
    val status: String?,
    val bookingDate: LocalDate?,
    val valueDate: LocalDate?,
    val transactionDate: LocalDate?,
    val remittance: List<String>,
    val creditorName: String?,
    val debtorName: String?,
    val note: String?,
    val bankCode: String?,
    val bankCodeText: String?,
    val entryReference: String?,
    val transactionId: String?,
) {
    override fun toString() = "EbTransaction(***)"
}

class EbTransactionsPage(val transactions: List<EbTransaction>, val continuationKey: String?, val unreadable: Int)

/**
 * Every page of one query, read until the API stopped giving a continuation key. Only [EbApi] builds it, and only when the loop
 * ended that way: [complete] is true by construction and a partial read is an [EbException] instead. [unreadable] counts array
 * elements that were not JSON objects.
 */
class EbTransactionsResult internal constructor(val transactions: List<EbTransaction>, val pages: Int, val unreadable: Int, val complete: Boolean = true) {
    override fun toString() = "EbTransactionsResult(${transactions.size} rows, $pages pages)"
}

/**
 * Enable Banking account-information API (https://enablebanking.com/docs/api/reference/, read 2026-10-31). Blocking calls: run
 * them on an IO dispatcher. Each call sends `Authorization: Bearer <JWT>` from [tokens].
 *
 * Optional [psuHeaders] go out only when the caller says the user is present (the bank then lifts its unattended-read limit);
 * the docs ask for all required PSU headers or none, which [EbPsu.select] decides.
 */
class EbApi(
    private val client: OkHttpClient,
    private val tokens: EbTokens,
    baseUrl: String = xyz.grtlabs.pilastri.BuildConfig.EB_BASE_URL.ifEmpty { BASE_URL },   // the debug build points it at a local fake provider (src/debug); empty everywhere else
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** Banks the application can use. [service] "AIS" keeps only banks that support account information. */
    fun aspsps(country: String? = null, psuType: String? = "personal", service: String? = "AIS"): List<EbAspsp> {
        val url = base.newBuilder().addPathSegment("aspsps").apply {
            country?.let { addQueryParameter("country", it) }
            psuType?.let { addQueryParameter("psu_type", it) }
            service?.let { addQueryParameter("service", it) }
        }.build()
        return call(Request.Builder().url(url).get()) { json ->
            val array = json.optJSONArray("aspsps") ?: throw EbException.Malformed("aspsps")
            array.objects().mapNotNull { o ->
                val name = o.text("name") ?: return@mapNotNull null
                EbAspsp(
                    name = name,
                    country = o.text("country") ?: return@mapNotNull null,
                    maximumConsentSeconds = if (o.isNull("maximum_consent_validity")) null else o.optLong("maximum_consent_validity").takeIf { it > 0 },
                    psuTypes = o.optJSONArray("psu_types").strings(),
                    authMethods = o.optJSONArray("auth_methods")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.text("name") ?: (a.opt(i) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } } }.orEmpty(),
                    requiredPsuHeaders = o.optJSONArray("required_psu_headers").strings(),
                    beta = o.optBoolean("beta", false),
                )
            }
        }
    }

    /** Starts a user authorization and returns the address to open in a browser. */
    fun startAuth(request: EbAuthRequest): EbAuthStart {
        val body = JSONObject()
            .put("access", JSONObject().put("valid_until", RFC3339.format(request.validUntil.atOffset(ZoneOffset.UTC))))
            .put("aspsp", JSONObject().put("name", request.aspspName).put("country", request.country))
            .put("state", request.state)
            .put("redirect_url", request.redirectUrl)
            .put("psu_type", request.psuType)
        request.language?.let { body.put("language", it) }
        return call(Request.Builder().url(base.newBuilder().addPathSegment("auth").build()).post(body.toString().toRequestBody(JSON))) { json ->
            EbAuthStart(json.text("url") ?: throw EbException.Malformed("url"), json.text("authorization_id"))
        }
    }

    /** Exchanges the `code` of the redirect for a session. The code is single-use and short-lived. */
    fun createSession(code: String): EbSession {
        val body = JSONObject().put("code", code)
        return call(Request.Builder().url(base.newBuilder().addPathSegment("sessions").build()).post(body.toString().toRequestBody(JSON))) { json ->
            EbSession(
                sessionId = json.text("session_id") ?: throw EbException.Malformed("session_id"),
                accounts = parseAccounts(json),
                validUntil = json.optJSONObject("access")?.text("valid_until")?.let(::instant),
                aspspName = json.optJSONObject("aspsp")?.text("name"),
                aspspCountry = json.optJSONObject("aspsp")?.text("country"),
            )
        }
    }

    fun session(sessionId: String): EbSessionInfo =
        call(Request.Builder().url(base.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()).get()) { json ->
            EbSessionInfo(json.text("status"), json.optJSONObject("access")?.text("valid_until")?.let(::instant), parseAccounts(json))
        }

    /** Revokes the session on the Enable Banking side. */
    fun deleteSession(sessionId: String) {
        call(Request.Builder().url(base.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()).delete(), emptyAllowed = true) { }
    }

    fun balances(accountUid: String, psuHeaders: Map<String, String> = emptyMap()): List<EbBalance> {
        val url = base.newBuilder().addPathSegment("accounts").addPathSegment(accountUid).addPathSegment("balances").build()
        return call(Request.Builder().url(url).get().withPsu(psuHeaders)) { json ->
            val array = json.optJSONArray("balances") ?: throw EbException.Malformed("balances")
            array.objects().map { o ->
                val amount = o.optJSONObject("balance_amount")
                EbBalance(
                    type = o.text("balance_type"),
                    amountText = amount?.text("amount"),
                    currency = amount?.text("currency"),
                    referenceDate = o.text("reference_date")?.let(JsonMovements::date),
                    lastChange = o.text("last_change_date_time")?.let(::instantOrNull),
                )
            }
        }
    }

    /**
     * One page. [status] is the API's `transaction_status` (BOOK, PDNG, ...) or null for the bank's default. A missing
     * `transactions` array is [EbException.Malformed], never an empty page: only an explicit `[]` is "no transactions".
     */
    fun transactionsPage(
        accountUid: String,
        from: LocalDate,
        to: LocalDate,
        status: String?,
        continuationKey: String? = null,
        psuHeaders: Map<String, String> = emptyMap(),
    ): EbTransactionsPage {
        val url = base.newBuilder().addPathSegment("accounts").addPathSegment(accountUid).addPathSegment("transactions")
            .addQueryParameter("date_from", from.toString())
            .addQueryParameter("date_to", to.toString())
            .apply {
                status?.let { addQueryParameter("transaction_status", it) }
                continuationKey?.let { addQueryParameter("continuation_key", it) }
            }.build()
        return call(Request.Builder().url(url).get().withPsu(psuHeaders)) { json ->
            val array = json.optJSONArray("transactions") ?: throw EbException.Malformed("transactions")
            var unreadable = 0
            val rows = ArrayList<EbTransaction>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i)
                if (o == null) unreadable++ else rows += parseTransaction(o)
            }
            EbTransactionsPage(rows, json.text("continuation_key"), unreadable)
        }
    }

    /** Every page of one query. Throws, instead of returning a partial list, if any page fails or the paging does not end. */
    fun allTransactions(
        accountUid: String,
        from: LocalDate,
        to: LocalDate,
        status: String?,
        psuHeaders: Map<String, String> = emptyMap(),
        maxPages: Int = MAX_PAGES,
    ): EbTransactionsResult {
        val rows = ArrayList<EbTransaction>()
        val seen = HashSet<String>()
        var key: String? = null
        var pages = 0
        var unreadable = 0
        do {
            val page = transactionsPage(accountUid, from, to, status, key, psuHeaders)
            pages++
            rows += page.transactions
            unreadable += page.unreadable
            key = page.continuationKey
            if (key != null) {
                if (!seen.add(key)) throw EbException.Incomplete("continuation_key ripetuta")
                if (pages >= maxPages) throw EbException.Incomplete("troppe pagine")
            }
        } while (key != null)
        return EbTransactionsResult(rows, pages, unreadable)
    }

    private fun Request.Builder.withPsu(headers: Map<String, String>): Request.Builder {
        headers.forEach { (name, value) -> if (name.isNotBlank() && value.isNotBlank()) header(name, value) }
        return this
    }

    private fun <T> call(builder: Request.Builder, emptyAllowed: Boolean = false, parse: (JSONObject) -> T): T {
        val request = builder.header("Authorization", "Bearer " + tokens.bearer()).header("Accept", "application/json").build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw EbException.Network(e)
        }
        response.use { r ->
            val bytes = readCapped(r)
            if (!r.isSuccessful) throw failure(r, bytes)
            if (bytes.isEmpty() && emptyAllowed) return parse(JSONObject())
            val json = try {
                JSONObject(String(bytes, StandardCharsets.UTF_8))
            } catch (_: JSONException) {
                throw EbException.Malformed("json")
            }
            return try {
                parse(json)
            } catch (_: JSONException) {
                throw EbException.Malformed("campo")
            }
        }
    }

    private fun readCapped(r: Response): ByteArray {
        val body = r.body ?: return ByteArray(0)
        val source = body.source()
        try {
            if (source.request(MAX_BYTES + 1)) throw EbException.Malformed("risposta troppo grande")
            return source.readByteArray()
        } catch (e: IOException) {
            throw EbException.Network(e)
        }
    }

    private fun failure(r: Response, bytes: ByteArray): EbException {
        val code = errorCode(bytes)
        val retryAfter = r.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        return when {
            r.code == 429 || code == "ASPSP_RATE_LIMIT_EXCEEDED" -> EbException.RateLimited(retryAfter)
            code != null && SESSION_ERRORS.any { it in code } -> EbException.SessionGone(code)
            code != null && code.startsWith("PSU_HEADER") -> EbException.PsuHeaders(code)
            r.code == 401 || r.code == 403 -> EbException.Unauthorized(r.code)
            r.code == 408 || r.code >= 500 -> EbException.Server(r.code)
            else -> EbException.Rejected(r.code, code)
        }
    }

    /** The `error` text code of an error body ("SESSION_DOES_NOT_EXIST"), only when it looks like one. */
    private fun errorCode(bytes: ByteArray): String? = try {
        JSONObject(String(bytes, StandardCharsets.UTF_8)).text("error")?.takeIf { CODE.matches(it) }
    } catch (_: JSONException) {
        null
    }

    private fun parseAccounts(json: JSONObject): List<EbAccount> {
        val byUid = LinkedHashMap<String, EbAccount>()
        val raw = json.optJSONArray("accounts")
        if (raw != null) for (i in 0 until raw.length()) {
            when (val item = raw.opt(i)) {
                is String -> if (item.isNotBlank()) byUid[item] = EbAccount(item, null, "Conto ${byUid.size + 1}", null)
                is JSONObject -> {
                    val uid = item.text("uid") ?: continue
                    byUid[uid] = EbAccount(uid, item.text("identification_hash"), accountLabel(item, byUid.size + 1), item.text("currency"))
                }
            }
        }
        // `accounts_data` (GET /sessions/{id}) pairs each uid with its identification hash.
        json.optJSONArray("accounts_data")?.objects()?.forEach { d ->
            val uid = d.text("uid") ?: return@forEach
            val known = byUid[uid]
            val hash = d.text("identification_hash")
            byUid[uid] = if (known != null) known.copy(identificationHash = known.identificationHash ?: hash)
            else EbAccount(uid, hash, "Conto ${byUid.size + 1}", null)
        }
        return byUid.values.toList()
    }

    /** A label without the IBAN: the account's name or product, else a number. */
    private fun accountLabel(o: JSONObject, n: Int): String =
        (o.text("name") ?: o.text("product") ?: o.text("details"))?.take(60) ?: "Conto $n"

    companion object {
        const val BASE_URL = "https://api.enablebanking.com"
        const val MAX_PAGES = 100
        private const val MAX_BYTES = 8L * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val CODE = Regex("[A-Z][A-Z0-9_]{2,80}")
        private val SESSION_ERRORS = listOf("SESSION", "CONSENT")

        internal fun parseTransaction(o: JSONObject): EbTransaction {
            val amount = o.optJSONObject("transaction_amount")
            val code = o.opt("bank_transaction_code")
            return EbTransaction(
                amountText = amount?.text("amount"),
                currency = amount?.text("currency"),
                indicator = o.text("credit_debit_indicator"),
                status = o.text("status"),
                bookingDate = o.text("booking_date")?.let(JsonMovements::date),
                valueDate = o.text("value_date")?.let(JsonMovements::date),
                transactionDate = o.text("transaction_date")?.let(JsonMovements::date),
                remittance = o.optJSONArray("remittance_information").strings(),
                creditorName = o.optJSONObject("creditor")?.text("name"),
                debtorName = o.optJSONObject("debtor")?.text("name"),
                note = o.text("note"),
                bankCode = (code as? JSONObject)?.text("code") ?: (code as? String)?.takeIf { it.isNotBlank() },
                bankCodeText = (code as? JSONObject)?.text("description"),
                entryReference = o.text("entry_reference"),
                transactionId = o.text("transaction_id"),
            )
        }

        internal fun instant(text: String): Instant = instantOrNull(text) ?: throw EbException.Malformed("data")

        internal fun instantOrNull(text: String): Instant? =
            runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
                ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

        /** Unsigned or signed decimal text with at most two decimals, as signed cents; null for anything else (never rounded). */
        internal fun cents(text: String?, signed: Boolean): Long? {
            val t = text?.trim() ?: return null
            if (!Regex(if (signed) "-?\\d{1,15}(\\.\\d+)?" else "\\d{1,15}(\\.\\d+)?").matches(t)) return null
            val value = BigDecimal(t)
            return runCatching { value.setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }.getOrNull()
        }
    }
}

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else opt(key)?.let { if (it is String || it is Number || it is Boolean) it.toString().trim() else null }?.takeIf { it.isNotEmpty() }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> opt(i)?.let { v -> if (v is String) v.trim().takeIf { s -> s.isNotEmpty() } else null } }

/** PSU headers of a user-triggered read. */
object EbPsu {
    /**
     * The docs: "either all required PSU headers or none". [required] comes from the bank's list entry; [available] is what the
     * device knows (at least Psu-User-Agent; the IP address is only sent if the caller has it). Returns [available] when the
     * user is present and every required header is known, else nothing, so an unattended read carries none.
     */
    fun select(userPresent: Boolean, required: List<String>, available: Map<String, String>): Map<String, String> {
        if (!userPresent) return emptyMap()
        val have = available.filterValues { it.isNotBlank() }
        val names = have.keys.map { it.lowercase() }.toSet()
        return if (required.all { it.lowercase() in names }) have else emptyMap()
    }
}
