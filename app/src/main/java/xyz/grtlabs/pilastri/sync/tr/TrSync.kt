package xyz.grtlabs.pilastri.sync.tr

import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider

/**
 * One subscription channel over a [TrTransport]: `sub n {json}`, the first answer (A, or D against the previous payload of
 * that id), then `unsub n`. Several requests may be in flight; one pump coroutine routes inbound frames by id.
 * Payloads, ids and server text are never logged.
 */
internal class TrSession(private val transport: TrTransport, private val config: TrConfig, private val scope: CoroutineScope) {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val baselines = ConcurrentHashMap<Int, String>()
    private val nextId = AtomicInteger(1)
    @Volatile private var failure: TrError? = null
    private var pump: Job? = null

    /** Opens the socket, sends `connect <n> {...}` and waits for the literal `connected` before routing frames. */
    suspend fun connect() {
        try {
            withTimeout(config.handshakeTimeoutMs) {
                transport.open(TrUrls.WS_URL)
                transport.send(TrProtocol.connectFrame(config))
                if (TrProtocol.parseFrame(transport.receive()) != TrFrame.Connected) {
                    throw TrError.Protocol("Trade Republic non ha confermato la connessione (versione del protocollo ${config.protocol} non accettata?).")
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw TrError.Timeout("connessione")
        }
        pump = scope.launch { route() }
    }

    private suspend fun route() {
        try {
            while (true) {
                when (val frame = TrProtocol.parseFrame(transport.receive())) {
                    is TrFrame.Full -> { baselines[frame.id] = frame.payload; waiting[frame.id]?.complete(frame.payload) }
                    is TrFrame.Delta -> {
                        val previous = baselines[frame.id]
                        if (previous == null) waiting[frame.id]?.completeExceptionally(TrError.Protocol("Aggiornamento incrementale senza base."))
                        else try {
                            val next = TrProtocol.decodeDelta(previous, frame.payload)
                            baselines[frame.id] = next
                            waiting[frame.id]?.complete(next)
                        } catch (e: TrError) { waiting[frame.id]?.completeExceptionally(e) }
                    }
                    is TrFrame.Closed -> { baselines.remove(frame.id); waiting[frame.id]?.completeExceptionally(TrError.Subscription(null, "Sottoscrizione chiusa da Trade Republic.")) }
                    is TrFrame.Failed -> waiting[frame.id]?.completeExceptionally(subscriptionError(frame.payload))
                    is TrFrame.Connected, is TrFrame.Unknown -> Unit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(e as? TrError ?: TrError.Connection("Connessione con Trade Republic interrotta."))
        }
    }

    private fun subscriptionError(payload: String): TrError {
        val code = TrProtocol.errorCode(payload)
        // Heuristic, not verified against a live expired session: an auth-looking error code means the login is gone.
        return if (code != null && (code.contains("UNAUTHORIZED", true) || code.startsWith("AUTH", true))) TrError.SessionExpired()
        else TrError.Subscription(code, "Trade Republic ha rifiutato la richiesta" + (code?.let { " ($it)" } ?: "") + ".")
    }

    private fun fail(e: TrError) {
        failure = e
        waiting.values.forEach { it.completeExceptionally(e) }
    }

    /** Raw JSON text of the first answer. */
    suspend fun request(payload: String): String {
        failure?.let { throw it }
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<String>()
        waiting[id] = answer
        failure?.let { waiting.remove(id); throw it }
        try {
            transport.send(TrProtocol.subscribeFrame(id, payload))
            return try {
                withTimeout(config.requestTimeoutMs) { answer.await() }
            } catch (_: TimeoutCancellationException) {
                throw TrError.Timeout("richiesta")
            }
        } finally {
            waiting.remove(id)
            baselines.remove(id)
            runCatching { transport.send(TrProtocol.unsubscribeFrame(id)) }
        }
    }

    suspend fun requestObject(payload: String): JSONObject = TrProtocol.parseObject(request(payload))
    suspend fun requestValue(payload: String): Any? = TrProtocol.parseValue(request(payload))

    fun close() {
        pump?.cancel()
        runCatching { transport.close() }
    }
}

/**
 * Read-only sync of a Trade Republic account: refresh the session, read the account number, connect, page through the
 * timeline (transactions and activity log) down to a lower date bound, read details for trades/dividends/interest, then the
 * portfolio, cash and last prices. Returns an [ImportBatch] for the preview; nothing is written here.
 *
 * Nothing in this class sees a phone number, PIN, 2FA code, cookie or token: the transport relays frames for a session that
 * the user already established in Trade Republic's own web page.
 */
class TrSync(
    private val transport: TrTransport,
    private val config: TrConfig = TrConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val refreshLock = Mutex()
    @Volatile private var lastRefreshAt: Long? = null

    private class Feed(val items: List<JSONObject>, val end: End, val invalidDates: Int) {
        enum class End { NATURAL, BOUND, LIMIT }
    }

    suspend fun run(onProgress: (TrProgress) -> Unit = {}): ImportBatch = coroutineScope {
        val session = TrSession(transport, config, this)
        try {
            val today = LocalDate.now(clock.withZone(TrProtocol.ROME))
            val bound = today.minusDays(config.sinceDays.toLong())
            val warnings = ArrayList<String>()

            onProgress(TrProgress("Controllo della sessione…"))
            refreshSession(force = true)
            var accountNumber = readAccountNumber()

            onProgress(TrProgress("Connessione a Trade Republic…"))
            session.connect()
            if (accountNumber == null) accountNumber = softOrNull { accountFromPairs(session) }

            // Timeline: the main feed is required, the activity log is merged in when it can be read.
            val transactions = fetchFeed(session, "timelineTransactions", bound, 0, onProgress)
            val activity = try {
                fetchFeed(session, "timelineActivityLog", bound, transactions.items.size, onProgress)
            } catch (e: TrError) {
                if (!e.isSoft) throw e
                warnings += "Registro attività non letto: possono mancare alcuni movimenti (pagamenti con carta, accrediti)."
                null
            }
            val merged = LinkedHashMap<String, JSONObject>()
            transactions.items.forEach { it.str("id")?.let { id -> merged[id] = it } }
            activity?.items?.forEach { it.str("id")?.let { id -> merged[id] = it } }   // as in pytr, the activity entry wins on a clash
            val items = merged.values.sortedWith(compareByDescending<JSONObject> { TrProtocol.parseTimestamp(it.str("timestamp"))?.toInstant() }.thenBy { it.str("id") })

            val details = fetchDetails(session, items, warnings, onProgress)
            val mapped = TrMapper.map(items, details)

            onProgress(TrProgress("Lettura di posizioni e saldo…"))
            val valuation = fetchValuation(session, accountNumber, today, warnings, onProgress)
            warnings += valuation.warnings

            (mapped.skipped[TrMapper.Skip.NO_AMOUNT] ?: 0).takeIf { it > 0 }?.let { warnings += "$it elementi senza importo non sono stati importati." }
            (mapped.skipped[TrMapper.Skip.INVALID] ?: 0).plus(transactions.invalidDates + (activity?.invalidDates ?: 0)).takeIf { it > 0 }
                ?.let { warnings += "$it elementi con dati non validi (id, data o importo) non sono stati importati." }
            if (mapped.incompleteTrades > 0) warnings += "${mapped.incompleteTrades} operazioni senza ISIN o quantità: vanno completate nella revisione."
            if (transactions.end == Feed.End.LIMIT || activity?.end == Feed.End.LIMIT) {
                warnings += "La cronologia è stata letta solo in parte (limite di pagine raggiunto): la copertura parte dalla data indicata."
            }

            onProgress(TrProgress("Completato", 1, 1))
            ImportBatch(
                provider = ImportProvider.TRADE_REPUBLIC,
                accountLabel = "Trade Republic",
                fetchedAt = clock.instant(),
                coverageFrom = coverageFrom(listOfNotNull(transactions, activity), bound, today),
                coverageTo = today,
                movements = mapped.movements,
                balance = valuation.balance,
                warnings = warnings,
                cashBalance = valuation.cash,
                securitiesBalance = valuation.securities,
                positions = valuation.positions,
            )
        } finally {
            session.close()
        }
    }

    // ---- session and account ---------------------------------------------------------------------------------------

    /** GET the session endpoint (it refreshes the cookies); repeated before a request when more than ~290 s have passed. */
    private suspend fun refreshSession(force: Boolean = false) {
        refreshLock.withLock {
            val last = lastRefreshAt
            if (!force && last != null && clock.millis() - last < config.sessionRefreshMs) return
            var retries = 0
            while (true) {
                val r = transport.get(TrUrls.SESSION_URL, wantBody = false)
                when {
                    r.status in 200..299 -> { lastRefreshAt = clock.millis(); return }
                    r.status == 401 -> throw TrError.SessionExpired()
                    // The WAF token may still be being obtained by the page's own scripts: wait a little before giving up.
                    r.status == 403 && retries < config.wafRetries -> { retries++; delay(config.wafRetryDelayMs) }
                    r.status == 403 -> throw TrError.Blocked()
                    r.status == 0 -> throw TrError.Connection("Impossibile raggiungere Trade Republic. Controlla la connessione.")
                    else -> throw TrError.Connection("Trade Republic ha risposto con un errore (HTTP ${r.status}).")
                }
            }
        }
    }

    private suspend fun readAccountNumber(): String? {
        val r = transport.get(TrUrls.ACCOUNT_URL)
        if (r.status == 401) throw TrError.SessionExpired()
        if (r.status !in 200..299) return null
        val number = runCatching { JSONObject(r.body).str("securitiesAccountNumber") }.getOrNull()
        return number?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{3,40}")) }
    }

    /** pytr's fallback: the account the session acts for, from `accountPairs` (the default product first). */
    private suspend fun accountFromPairs(session: TrSession): String? {
        val accounts = session.requestObject(TrProtocol.accountPairs()).arr("accounts") ?: return null
        val list = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it) }
            .filter { !it.str("securitiesAccountNumber").isNullOrBlank() }
            .sortedBy { it.str("productType") != "DEFAULT" }
        return list.firstOrNull()?.str("securitiesAccountNumber")?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{3,40}")) }
    }

    private suspend fun <T> softOrNull(block: suspend () -> T): T? =
        try { block() } catch (e: TrError) { if (e.isSoft) null else throw e }

    // ---- timeline --------------------------------------------------------------------------------------------------

    /**
     * Newest-first pages linked by `cursors.after`. Stops when the cursor is null, when a page contains an item older than
     * [bound] (older items are dropped), or at the page limit / a repeated cursor.
     */
    private suspend fun fetchFeed(session: TrSession, type: String, bound: LocalDate, already: Int, onProgress: (TrProgress) -> Unit): Feed {
        val kept = ArrayList<JSONObject>()
        val cursors = HashSet<String>()
        var cursor: String? = null
        var invalid = 0
        var pages = 0
        while (true) {
            refreshSession()
            val payload = if (type == "timelineTransactions") TrProtocol.timelineTransactions(cursor) else TrProtocol.timelineActivityLog(cursor)
            val reply = session.requestObject(payload)
            pages++
            // An empty feed counts as complete only when the provider answered a well-formed page: an `items` array must be there.
            // A reply without it (unrecognized shape) is a failed read, never "no movements" and never coverage.
            val page = reply.arr("items") ?: throw TrError.Protocol("Risposta di Trade Republic non riconosciuta: elenco movimenti assente.")
            var older = false
            for (i in 0 until page.length()) {
                val item = page.optJSONObject(i) ?: continue
                val date = TrProtocol.parseDate(item.str("timestamp"))
                when {
                    date == null -> invalid++
                    date.isBefore(bound) -> older = true
                    else -> kept += item
                }
            }
            onProgress(TrProgress("Lettura dei movimenti…", already + kept.size))
            val next = reply.obj("cursors")?.str("after")?.takeIf { it.isNotEmpty() }
            when {
                older -> return Feed(kept, Feed.End.BOUND, invalid)
                next == null || page.length() == 0 -> return Feed(kept, Feed.End.NATURAL, invalid)
                !cursors.add(next) || pages >= config.maxPages -> return Feed(kept, Feed.End.LIMIT, invalid)
            }
            cursor = next
        }
    }

    /**
     * Coverage is where every feed is complete: today's bound if any feed stopped on it, else the oldest kept item when
     * the history ended by itself, else (page limit) the oldest item that was kept. Null when nothing was read.
     */
    private fun coverageFrom(feeds: List<Feed>, bound: LocalDate, today: LocalDate): LocalDate? {
        val oldest = { f: Feed -> f.items.mapNotNull { TrProtocol.parseDate(it.str("timestamp")) }.minOrNull() }
        val starts = feeds.mapNotNull { f ->
            when (f.end) {
                Feed.End.BOUND -> bound
                Feed.End.NATURAL -> null
                Feed.End.LIMIT -> oldest(f) ?: today
            }
        }
        if (starts.isNotEmpty()) return starts.max()
        return feeds.mapNotNull(oldest).minOrNull()
    }

    private suspend fun fetchDetails(
        session: TrSession, items: List<JSONObject>, warnings: MutableList<String>, onProgress: (TrProgress) -> Unit,
    ): Map<String, JSONObject> {
        val targets = items.filter { TrMapper.wantsDetail(it) }
        if (targets.isEmpty()) return emptyMap()
        val details = ConcurrentHashMap<String, JSONObject>()
        val failed = AtomicInteger()
        val done = AtomicInteger()
        val gate = Semaphore(config.detailConcurrency)
        coroutineScope {
            targets.map { item ->
                async {
                    gate.withPermit {
                        refreshSession()
                        val id = item.str("id")!!
                        try {
                            details[id] = session.requestObject(TrProtocol.timelineDetail(id))
                        } catch (e: TrError) {
                            if (!e.isSoft) throw e
                            failed.incrementAndGet()
                        }
                        onProgress(TrProgress("Lettura dei dettagli…", done.incrementAndGet(), targets.size))
                    }
                }
            }.awaitAll()
        }
        if (failed.get() > 0) warnings += "${failed.get()} dettagli non letti: su quei movimenti possono mancare ISIN e quantità."
        return details
    }

    // ---- portfolio -------------------------------------------------------------------------------------------------

    private suspend fun fetchValuation(
        session: TrSession, accountNumber: String?, today: LocalDate, warnings: MutableList<String>, onProgress: (TrProgress) -> Unit,
    ): TrMapper.Valuation {
        refreshSession()
        if (accountNumber == null) {
            return TrMapper.Valuation(null, listOf("Numero di deposito non disponibile: posizioni e saldo non sono stati letti."))
        }
        val positions = softOrNull { TrMapper.parsePositions(session.requestObject(TrProtocol.compactPortfolio(accountNumber))) }
        val cash = softOrNull { TrMapper.parseCash(session.requestValue(TrProtocol.cash())) }
        if (positions == null || cash == null) return TrMapper.valuation(positions, cash, emptyMap(), today)

        val toPrice = positions.filter { it.isin.isNotEmpty() && it.netSize != null }.map { it.isin }.distinct()
        val prices = ConcurrentHashMap<String, BigDecimal>()
        val done = AtomicInteger()
        val gate = Semaphore(config.detailConcurrency)
        coroutineScope {
            toPrice.map { isin ->
                async {
                    gate.withPermit {
                        refreshSession()
                        // Unknown exchange list or a failed quote leaves this ISIN unpriced, which nulls the total.
                        val instrument = softOrNull { session.requestObject(TrProtocol.instrument(isin)) }
                        val exchange = TrMapper.pickExchange(instrument, config.preferredExchange)
                        softOrNull { TrMapper.lastPrice(session.requestObject(TrProtocol.ticker(isin, exchange)), instrument, exchange) }?.let { prices[isin] = it }
                        onProgress(TrProgress("Lettura dei prezzi…", done.incrementAndGet(), toPrice.size))
                    }
                }
            }.awaitAll()
        }
        return TrMapper.valuation(positions, cash, prices, today)
    }
}
