package xyz.grtlabs.pilastri.sync.eb

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.auto.SyncLog
import xyz.grtlabs.pilastri.sync.auto.SyncRange

/** The redirect the bank flow ends on: `<registered https url>?code=...&state=...`, or `?error=...` when the person declined. */
object EbRedirect {
    sealed interface Result {
        data class Code(val code: String) : Result
        /** The person (or the bank) ended the authorization without consent. [error] is a sanitized code or null. */
        data class Denied(val error: String?) : Result
        /** Not our redirect, no `state`, a different `state`, or no code: ignored, never exchanged. */
        data object Invalid : Result
    }

    /** True when [url] is the registered redirect (same scheme, host, port and path), so a WebView or App Link handler can claim it. */
    fun matches(url: String, registered: String): Boolean = try {
        val a = URI(url)
        val b = URI(registered)
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port && (a.path ?: "") == (b.path ?: "")
    } catch (_: Exception) {
        false
    }

    fun parse(url: String, registered: String, expectedState: String): Result {
        if (!matches(url, registered)) return Result.Invalid
        val query = try {
            URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                val i = part.indexOf('=')
                val name = URLDecoder.decode(if (i < 0) part else part.substring(0, i), "UTF-8")
                name to URLDecoder.decode(if (i < 0) "" else part.substring(i + 1), "UTF-8")
            }
        } catch (_: Exception) {
            return Result.Invalid
        }
        if (query["state"] != expectedState) return Result.Invalid
        query["error"]?.let { return Result.Denied(it.lowercase(Locale.ROOT).takeIf { e -> Regex("[a-z_]{1,60}").matches(e) }) }
        val code = query["code"]?.takeIf { it.isNotBlank() && it.length <= 2000 } ?: return Result.Invalid
        return Result.Code(code)
    }
}

/** Which dates to ask a bank for. */
object EbRanges {
    /**
     * Italian PSD2 banks (CBI Globe, isybank included) give about 90 days of history unless the person has just authorized:
     * Enable Banking's docs say the full history is available for about an hour after authorization. [sinceAuthorization] is
     * that elapsed time (null: unknown, treated as long ago). Beyond the window the range starts [shallowDays] − 1 days back,
     * so a request never reaches the part that needs another strong authentication.
     */
    fun plan(
        today: LocalDate,
        lastCoveredTo: LocalDate?,
        sinceAuthorization: Duration?,
        firstDays: Int = 365,
        shallowDays: Int = 90,
        overlapDays: Int = 7,
        deepWindow: Duration = Duration.ofMinutes(50),
    ): SyncRange {
        val wanted = SyncRange.incremental(today, lastCoveredTo, firstDays, overlapDays)
        val deep = sinceAuthorization != null && sinceAuthorization < deepWindow
        if (deep) return wanted
        val floor = today.minusDays(shallowDays - 1L)
        return SyncRange(if (wanted.from.isBefore(floor)) floor else wanted.from, today)
    }
}

class EbLinkStart(val authorizationUrl: String, val state: String, val validUntil: Instant)

sealed interface EbLinkResult {
    class Linked(val bankName: String, val accounts: List<EbLinkedAccount>, val validUntil: Instant?) : EbLinkResult
    data class Denied(val error: String?) : EbLinkResult
    /** The redirect did not belong to a started authorization. Nothing was exchanged. */
    data object Ignored : EbLinkResult
}

class EbAccountOutcome(val account: EbLinkedAccount, val appAccountId: String?, val result: EbReadResult)

sealed interface EbSyncOutcome {
    class Done(val accounts: List<EbAccountOutcome>) : EbSyncOutcome
    data object NotLinked : EbSyncOutcome
    /** Consent expired or revoked: the person has to link again (approve in the bank app). */
    data object NeedsLink : EbSyncOutcome
    data class RateLimited(val until: Instant) : EbSyncOutcome
    data class Failed(val message: String) : EbSyncOutcome
}

/**
 * Enable Banking as a bank connector, without a server: the app signs its own JWTs ([EbJwt]), links the person's accounts
 * ([startLink]/[completeLink]) and reads them ([sync]) with the stored session. Logs carry counts only.
 *
 * [providerFor] chooses the app's [ImportProvider] for a bank name; null means the app has no provider value for that bank yet
 * and its accounts are reported as failed instead of being filed under a wrong provider. [defaultProviderFor] knows isybank.
 */
class EbConnector(
    private val api: EbApi,
    private val state: EbStateStore,
    private val providerFor: (String) -> ImportProvider? = { defaultProviderFor(it) },
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
) {
    fun local(): EbLocal = state.load()

    /** The application is registered once: its id (the JWT `kid`) and the https redirect URL whitelisted for it. */
    fun saveRegistration(applicationId: String, redirectUrl: String) {
        require(redirectUrl.startsWith("https://")) { "L'indirizzo di ritorno deve iniziare con https://." }
        state.save(state.load().copy(applicationId = applicationId.trim(), redirectUrl = redirectUrl.trim()))
    }

    suspend fun banks(country: String): List<EbAspsp> = withContext(io) { api.aspsps(country) }

    /** Starts the authorization at [bank] and remembers the `state`. The returned address is opened in a browser or WebView. */
    suspend fun startLink(bank: EbAspsp): EbLinkStart = withContext(io) {
        val local = state.load()
        val redirect = local.redirectUrl ?: throw IllegalStateException("Applicazione Enable Banking non registrata.")
        val now = clock()
        val validUntil = consentEnd(now, bank.maximumConsentSeconds)
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val start = api.startAuth(EbAuthRequest(bank.name, bank.country, nonce, redirect, validUntil))
        state.save(local.copy(pending = EbPendingAuth(nonce, bank.name, bank.country, redirect, validUntil, now)))
        SyncLog.d("EB link started")
        EbLinkStart(start.url, nonce, validUntil)
    }

    /** Handles the redirect: checks the `state`, exchanges the code, stores the session. [bank] supplies the PSU header list. */
    suspend fun completeLink(redirectedUrl: String, bank: EbAspsp?): EbLinkResult = withContext(io) {
        val local = state.load()
        val pending = local.pending ?: return@withContext EbLinkResult.Ignored
        when (val parsed = EbRedirect.parse(redirectedUrl, pending.redirectUrl, pending.state)) {
            EbRedirect.Result.Invalid -> EbLinkResult.Ignored
            is EbRedirect.Result.Denied -> { state.save(local.copy(pending = null)); EbLinkResult.Denied(parsed.error) }
            is EbRedirect.Result.Code -> {
                val session = api.createSession(parsed.code)
                val now = clock()
                val accounts = session.accounts.map { EbLinkedAccount(it.uid, it.identificationHash, it.label, it.currency) }
                val validUntil = session.validUntil ?: pending.validUntil
                state.save(local.withNewSession(EbSessionState(session.sessionId, session.aspspName ?: pending.bankName,
                    session.aspspCountry ?: pending.country, validUntil, now, bank?.requiredPsuHeaders.orEmpty(), accounts)))
                SyncLog.d("EB linked accounts=${accounts.size}")
                EbLinkResult.Linked(session.aspspName ?: pending.bankName, accounts, validUntil)
            }
        }
    }

    /** Ties one linked account to one of the app's accounts. The link survives a new session (same identification hash). */
    fun assign(account: EbLinkedAccount, appAccountId: String?) = state.save(state.load().assign(account.key, appAccountId))

    /** Revokes the sessions at Enable Banking (best effort) and forgets them locally: one bank's when [bankName] is given, else all. */
    suspend fun unlink(bankName: String? = null) = withContext(io) {
        val local = state.load()
        val gone = local.sessions.filter { bankName == null || sameBank(it.bankName, bankName) }
        gone.forEach { s -> runCatching { api.deleteSession(s.sessionId) } }
        state.save(local.copy(sessions = local.sessions - gone.toSet(), pending = null, rateLimitedUntil = null))
    }

    /**
     * Reads every linked account: booked rows (all pages), pending rows (optional), balances. [coveredTo] gives the last day
     * already covered for an app account. [userPresent] with [psuHeaders] marks a read the person triggered; an unattended
     * read sends no PSU headers and counts against the bank's daily limit (about 4 calls): it skips the optional pending read
     * ([readPending]) so that a sync costs two calls (booked rows, balances) instead of three.
     */
    suspend fun sync(
        coveredTo: (appAccountId: String?) -> LocalDate?,
        userPresent: Boolean = false,
        psuHeaders: Map<String, String> = emptyMap(),
        readPending: Boolean = userPresent,
    ): EbSyncOutcome = withContext(io) {
        val local = state.load()
        if (local.sessions.isEmpty()) return@withContext EbSyncOutcome.NotLinked
        val now = clock()
        local.rateLimitedUntil?.let { if (now.isBefore(it)) return@withContext EbSyncOutcome.RateLimited(it) }
        val results = ArrayList<EbAccountOutcome>()
        var needsLink = false
        sessions@ for (session in local.sessions) {
        if (session.validUntil != null && !now.isBefore(session.validUntil)) { needsLink = true; continue }
        val provider = providerFor(session.bankName)
        val psu = EbPsu.select(userPresent, session.requiredPsuHeaders, psuHeaders)
        val today = now.atZone(ZONE).toLocalDate()
        val sinceAuth = Duration.between(session.createdAt, now)
        for (account in session.accounts) {
            val appId = local.assignments[account.key]
            if (provider == null) {
                results += EbAccountOutcome(account, appId, EbReadResult.Failed("${session.bankName}: banca non ancora supportata dall'app."))
                continue
            }
            val range = EbRanges.plan(today, coveredTo(appId), sinceAuth)
            try {
                val booked = api.allTransactions(account.uid, range.from, range.to, "BOOK", psu)
                val pending = if (!readPending) null else try {
                    api.allTransactions(account.uid, range.from, range.to, "PDNG", psu)
                } catch (e: EbException) {
                    // Pending rows are optional (not every bank has them): a session or limit error still stops the sync below.
                    if (e is EbException.Rejected || e is EbException.Malformed || e is EbException.Incomplete) null else throw e
                }
                val balances = try {
                    api.balances(account.uid, psu)
                } catch (e: EbException) {
                    if (e is EbException.Rejected || e is EbException.Malformed) null else throw e
                }
                val result = EbMapper.map(provider, session.bankName, account, range, booked, pending, balances, now)
                if (result is EbReadResult.Ok) SyncLog.d("EB read booked=${result.stats.booked} pending=${result.stats.pending} pages=${result.stats.pages}")
                else SyncLog.d("EB read failed")
                results += EbAccountOutcome(account, appId, result)
            } catch (e: EbException.RateLimited) {
                val until = now.plusSeconds(e.retryAfterSeconds ?: RATE_LIMIT_BACKOFF.seconds)
                state.save(state.load().copy(rateLimitedUntil = until))
                SyncLog.d("EB rate limited")
                return@withContext EbSyncOutcome.RateLimited(until)
            } catch (_: EbException.SessionGone) {
                SyncLog.d("EB session gone")
                needsLink = true
                continue@sessions
            } catch (e: EbException) {
                return@withContext EbSyncOutcome.Failed(e.message ?: "Lettura non riuscita.")
            }
        }
        }
        if (results.isEmpty() && needsLink) return@withContext EbSyncOutcome.NeedsLink
        EbSyncOutcome.Done(results)
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Rome")
        /** The docs advise continuing after about 6 hours when the bank limits unattended reads. */
        val RATE_LIMIT_BACKOFF: Duration = Duration.ofHours(6)
        private val DEFAULT_CONSENT: Duration = Duration.ofDays(90)

        /** The end of the consent asked for: the bank's maximum (180 days for most) less an hour, or 90 days when unknown. */
        fun consentEnd(now: Instant, maximumSeconds: Long?): Instant {
            val max = maximumSeconds?.let { Duration.ofSeconds(it) } ?: DEFAULT_CONSENT
            return now.plus(max.minusHours(1).takeIf { !it.isNegative && !it.isZero } ?: max)
        }

        /** isybank is the only bank the app has a provider value for. A bank without one stays unsupported, never mis-filed. */
        fun defaultProviderFor(bankName: String): ImportProvider? =
            if (bankName.lowercase(Locale.ROOT).contains("isybank")) ImportProvider.ISYBANK else null

        /** The first aspsp whose name equals [query] ignoring case, else the only one containing it. */
        fun findBank(banks: List<EbAspsp>, query: String): EbAspsp? {
            val q = query.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) return null
            return banks.firstOrNull { it.name.lowercase(Locale.ROOT) == q } ?: banks.filter { q in it.name.lowercase(Locale.ROOT) }.singleOrNull()
        }
    }
}
