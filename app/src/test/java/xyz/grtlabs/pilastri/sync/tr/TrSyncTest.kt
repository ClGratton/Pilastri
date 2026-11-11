package xyz.grtlabs.pilastri.sync.tr

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider

private const val ISIN_A = "ZZ0000000011"
private const val ISIN_B = "ZZ0000000029"

/** A transport that answers like Trade Republic would, from scripts. No network, no WebView. */
internal class FakeTr(
    private val http: (String) -> TrHttpResponse = { url ->
        if (url == TrUrls.SESSION_URL) TrHttpResponse(200, "") else TrHttpResponse(200, """{"securitiesAccountNumber":"SEC123"}""")
    },
    private val handshake: String? = "connected",
    private val topics: (type: String, payload: JSONObject) -> Reply,
) : TrTransport {
    sealed interface Reply {
        data class Json(val text: String) : Reply
        data class Err(val text: String) : Reply
        data object Silence : Reply
    }

    val sentFrames = CopyOnWriteArrayList<String>()
    val httpCalls = CopyOnWriteArrayList<String>()
    val subscriptions = CopyOnWriteArrayList<JSONObject>()
    @Volatile var closed = false
    private val inbound = Channel<String>(Channel.UNLIMITED)

    override suspend fun get(url: String, wantBody: Boolean): TrHttpResponse {
        require(TrUrls.isAllowedGet(url))
        httpCalls += url
        return http(url)
    }

    override suspend fun open(url: String) { require(TrUrls.isAllowedSocket(url)) }

    override fun send(frame: String) {
        sentFrames += frame
        when {
            frame.startsWith("connect ") -> handshake?.let { inbound.trySend(it) }
            frame.startsWith("sub ") -> {
                val rest = frame.removePrefix("sub ")
                val id = rest.substringBefore(' ')
                val payload = JSONObject(rest.substringAfter(' '))
                subscriptions += payload
                when (val r = topics(payload.getString("type"), payload)) {
                    is Reply.Json -> inbound.trySend("$id A ${r.text}")
                    is Reply.Err -> inbound.trySend("$id E ${r.text}")
                    Reply.Silence -> Unit
                }
            }
        }
    }

    override suspend fun receive(): String = inbound.receive()
    override fun close() { closed = true; inbound.close() }

    fun types(name: String) = subscriptions.filter { it.getString("type") == name }
}

internal class TestClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(z: ZoneId): Clock = TestClock(now, z)
    override fun instant(): Instant = now
}

private val NOW = Instant.parse("2026-10-28T10:00:00Z")      // 11:00 in Rome, so "today" is 2026-10-28
private val TODAY = LocalDate.of(2026, 10, 28)
private val BOUND = TODAY.minusDays(400)                       // 2025-09-23

private fun page(items: List<JSONObject>, after: String?): String =
    JSONObject().put("items", JSONArray(items)).put("cursors", JSONObject().put("after", after ?: JSONObject.NULL)).toString()

private fun after(payload: JSONObject): String? = payload.opt("after")?.takeIf { it != JSONObject.NULL }?.toString()

private val ERR = FakeTr.Reply.Err("""{"errors":[{"errorCode":"NOT_SCRIPTED"}]}""")

/** The scripted account used by most tests. Fields are mutable so that a test can break one part. */
private class Account {
    val transactions = mutableMapOf<String?, String>()
    val activity = mutableMapOf<String?, String>()
    val details = mutableMapOf<String, String>()
    val instruments = mutableMapOf<String, String>()
    val tickers = mutableMapOf<String, String>()
    var portfolio: String? = """{"categories":[{"positions":[{"isin":"$ISIN_A","name":"A","netSize":"2","averageBuyIn":"9.5"},{"isin":"$ISIN_B","name":"B","netSize":"0.5"}]}]}"""
    var cash: String? = """[{"accountNumber":"C1","currencyId":"EUR","amount":"100.25"}]"""
    var accountPairs: String? = null
    var onTimeline: () -> Unit = {}
    val log = CopyOnWriteArrayList<String>()

    fun reply(type: String, p: JSONObject): FakeTr.Reply {
        fun of(s: String?) = if (s == null) ERR else FakeTr.Reply.Json(s)
        return when (type) {
            "timelineTransactions" -> { onTimeline(); log += "tx:${after(p)}"; of(transactions[after(p)]) }
            "timelineActivityLog" -> { onTimeline(); log += "act:${after(p)}"; of(activity[after(p)]) }
            "timelineDetailV2" -> of(details[p.getString("id")])
            "compactPortfolioByType" -> of(portfolio)
            "cash" -> of(cash)
            "accountPairs" -> of(accountPairs)
            "instrument" -> of(instruments[p.getString("id")])
            "ticker" -> of(tickers[p.getString("id")])
            else -> ERR
        }
    }

    fun transport(
        http: ((String) -> TrHttpResponse)? = null,
        handshake: String? = "connected",
    ) = if (http == null) FakeTr(handshake = handshake, topics = ::reply) else FakeTr(http = http, handshake = handshake, topics = ::reply)

    /** buy in the main feed (2 pages, the second one holds an item older than the bound), activity log with a clash. */
    fun standard(): Account {
        transactions[null] = page(
            listOf(
                tlItem(id = "t1", ts = "2026-10-20T09:00:00.000+0000", value = -111.0),
                tlItem(id = "t2", ts = "2026-09-15T09:00:00.000+0000", value = 500.0, eventType = "ACCOUNT_TRANSFER_INCOMING", title = "Versamento", subtitle = null),
            ),
            "c1",
        )
        transactions["c1"] = page(
            listOf(
                tlItem(id = "t3", ts = "2025-11-02T09:00:00.000+0000", value = 300.0, eventType = "ORDER_EXECUTED", icon = "logos/$ISIN_B/v2", subtitle = "Verkaufsorder"),
                tlItem(id = "t4", ts = "2025-08-01T09:00:00.000+0000", value = -10.0, eventType = "ORDER_EXECUTED"),
            ),
            "c2",
        )
        activity[null] = page(
            listOf(
                tlItem(id = "a1", ts = "2026-10-21T09:00:00.000+0000", value = -4.28, eventType = "CARD_TRANSACTION", title = "REWE", subtitle = null, icon = "logos/merchant-rewe/v2"),
                tlItem(id = "a2", ts = "2026-10-15T09:00:00.000+0000", value = 2.24, eventType = "SSP_CORPORATE_ACTION_INVOICE_CASH", title = "Example Corp", subtitle = "Bardividende"),
                tlItem(id = "t2", ts = "2026-09-15T09:00:00.000+0000", value = 500.0, eventType = "ACCOUNT_TRANSFER_INCOMING", title = "Accredito", subtitle = null),
            ),
            null,
        )
        details["t1"] = tlDetail(ISIN_A, "Transaktion" to "0,685102 × 160,56 €", "Aktien" to "0,685102", "Gebühr" to "1,00 €").toString()
        details["t3"] = tlDetail(ISIN_B, "Aktien" to "3").toString()
        details["a2"] = tlDetail(ISIN_A, "Aktien" to "10.640298", "Steuer" to "-0,78 €").toString()
        instruments[ISIN_A] = """{"exchangeIds":["TDG","LSX"]}"""
        instruments[ISIN_B] = """{"exchangeIds":["GER"]}"""
        tickers["$ISIN_A.LSX"] = """{"last":{"price":"10.10","size":1}}"""
        tickers["$ISIN_B.GER"] = """{"last":{"price":"200","size":1}}"""
        return this
    }
}

private fun sync(t: FakeTr, config: TrConfig = TrConfig(wafRetryDelayMs = 0), clock: Clock = TestClock(NOW)): ImportBatch =
    runBlocking { TrSync(t, config, clock).run() }

private fun ImportBatch.byId(id: String) = movements.single { it.externalId == id }

class TrSyncTest {
    // ---- the full read ---------------------------------------------------------------------------------------------

    @Test fun `a full sync produces the batch the app expects`() {
        val account = Account().standard()
        val t = account.transport()
        val batch = sync(t)

        assertEquals(ImportProvider.TRADE_REPUBLIC, batch.provider)
        assertEquals("Trade Republic", batch.accountLabel)
        assertEquals(NOW, batch.fetchedAt)
        assertEquals(BOUND, batch.coverageFrom)
        assertEquals(TODAY, batch.coverageTo)
        assertEquals(setOf("t1", "t2", "t3", "a1", "a2"), batch.movements.map { it.externalId }.toSet())
        assertEquals(22045L, batch.balance!!.cents)                       // 100.25 + 2 x 10.10 + 0.5 x 200
        assertEquals(TODAY, batch.balance!!.date)
        assertTrue(batch.warnings.toString(), batch.warnings.isEmpty())

        val buy = batch.byId("t1")
        assertEquals(ExternalKind.BUY, buy.kind); assertEquals(-11100L, buy.signedCents)
        assertEquals(ISIN_A, buy.isin); assertEquals("0.685102", buy.quantity!!.toPlainString())
        val sell = batch.byId("t3")
        assertEquals(ExternalKind.SELL, sell.kind); assertEquals(ISIN_B, sell.isin); assertEquals("3", sell.quantity!!.toPlainString())
        val dividend = batch.byId("a2")
        assertEquals(ExternalKind.DIVIDEND, dividend.kind); assertEquals(ISIN_A, dividend.isin); assertNull(dividend.quantity)
        assertEquals(ExternalKind.CARD_PAYMENT, batch.byId("a1").kind)
        // the activity-log entry wins over the same id in the main feed, as in pytr
        assertEquals("Accredito", batch.byId("t2").description)
    }

    @Test fun `only reads leave the transport and every subscription is closed`() {
        val t = Account().standard().transport()
        sync(t)
        assertTrue(t.subscriptions.isNotEmpty())
        assertTrue(t.subscriptions.all { it.getString("type") in TrProtocol.READ_ONLY_TYPES })
        assertTrue(t.httpCalls.all { TrUrls.isAllowedGet(it) })
        assertEquals(TrProtocol.connectFrame(TrConfig()), t.sentFrames.first())
        assertTrue(t.sentFrames.all { it.startsWith("connect ") || it.startsWith("sub ") || it.startsWith("unsub ") })
        assertEquals(t.sentFrames.count { it.startsWith("sub ") }, t.sentFrames.count { it.startsWith("unsub ") })
        assertTrue(t.closed)
        // nothing but ids, cursors and ISINs went out: no credentials-shaped keys
        assertTrue(t.sentFrames.none { "pin" in it.lowercase() || "phone" in it.lowercase() || "password" in it.lowercase() || "cookie" in it.lowercase() })
    }

    @Test fun `the exchange comes from the instrument and defaults to LSX`() {
        val t = Account().standard().transport()
        sync(t)
        val ids = t.types("ticker").map { it.getString("id") }.toSet()
        assertEquals(setOf("$ISIN_A.LSX", "$ISIN_B.GER"), ids)
    }

    // ---- pagination ------------------------------------------------------------------------------------------------

    @Test fun `paging stops at the page that holds an item older than the bound`() {
        val account = Account().standard()
        val t = account.transport()
        val batch = sync(t)
        assertEquals(listOf("tx:null", "tx:c1"), account.log.filter { it.startsWith("tx") })    // c2 never asked
        assertTrue(batch.movements.none { it.externalId == "t4" })                                // older than the bound: dropped
        assertEquals(BOUND, batch.coverageFrom)
    }

    @Test fun `paging stops when the cursor is null and coverage starts at the oldest item`() {
        val account = Account()
        account.transactions[null] = page(listOf(tlItem(id = "x1", ts = "2026-01-05T09:00:00.000+0000", value = -20.0)), null)
        account.activity[null] = page(listOf(tlItem(id = "x2", ts = "2026-03-01T09:00:00.000+0000", value = 50.0, eventType = "ACCOUNT_TRANSFER_INCOMING")), null)
        account.details["x1"] = tlDetail(ISIN_A, "Aktien" to "1").toString()
        account.portfolio = """{"categories":[]}"""
        account.cash = """[{"currencyId":"EUR","amount":"0"}]"""
        val batch = sync(account.transport())
        assertEquals(listOf("tx:null"), account.log.filter { it.startsWith("tx") })
        assertEquals(LocalDate.of(2026, 1, 5), batch.coverageFrom)
        assertEquals(0L, batch.balance!!.cents)
    }

    @Test fun `the page limit and a repeated cursor stop the walk and say so`() {
        val account = Account()
        fun item(n: Int) = tlItem(id = "p$n", ts = "2026-09-${10 + n}T09:00:00.000+0000", value = 5.0, eventType = "ACCOUNT_TRANSFER_INCOMING")
        account.transactions[null] = page(listOf(item(9)), "c1")
        account.transactions["c1"] = page(listOf(item(8)), "c2")
        account.transactions["c2"] = page(listOf(item(7)), "c3")
        account.transactions["c3"] = page(listOf(item(6)), "c4")
        account.activity[null] = page(emptyList(), null)
        account.portfolio = """{"categories":[]}"""
        account.cash = """[{"currencyId":"EUR","amount":"1"}]"""
        val batch = sync(account.transport(), TrConfig(maxPages = 3, wafRetryDelayMs = 0))
        assertEquals(listOf("tx:null", "tx:c1", "tx:c2"), account.log.filter { it.startsWith("tx") })
        assertEquals(LocalDate.of(2026, 9, 17), batch.coverageFrom)                // the oldest item that was kept
        assertTrue(batch.warnings.any { "solo in parte" in it })

        val loop = Account()
        loop.transactions[null] = page(listOf(item(9)), "same")
        loop.transactions["same"] = page(listOf(item(8)), "same")
        loop.activity[null] = page(emptyList(), null)
        loop.portfolio = """{"categories":[]}"""
        loop.cash = """[{"currencyId":"EUR","amount":"1"}]"""
        sync(loop.transport())
        assertEquals(listOf("tx:null", "tx:same"), loop.log.filter { it.startsWith("tx") })
    }

    @Test fun `an empty account is a valid empty batch with unknown coverage`() {
        val account = Account()
        account.transactions[null] = page(emptyList(), null)
        account.activity[null] = page(emptyList(), null)
        account.portfolio = """{"categories":[]}"""
        account.cash = """[{"currencyId":"EUR","amount":"0"}]"""
        val batch = sync(account.transport())
        assertTrue(batch.movements.isEmpty())
        assertNull(batch.coverageFrom)
        assertEquals(TODAY, batch.coverageTo)
    }

    @Test fun `an unrecognized reply on the main feed is a failure with no coverage, not an empty feed`() {
        val account = Account()
        account.transactions[null] = """{"unexpected":true}"""
        account.activity[null] = page(emptyList(), null)
        account.portfolio = """{"categories":[]}"""
        account.cash = """[{"currencyId":"EUR","amount":"0"}]"""
        assertThrows(TrError.Protocol::class.java) { sync(account.transport()) }
    }

    @Test fun `an unparseable reply on the main feed is a failure`() {
        val account = Account()
        account.transactions[null] = "not json"
        account.activity[null] = page(emptyList(), null)
        assertThrows(TrError.Protocol::class.java) { sync(account.transport()) }
    }

    @Test fun `many details are all read with bounded concurrency`() {
        val account = Account()
        val items = (1..30).map { tlItem(id = "m$it", ts = String.format("2026-10-%02dT09:00:00.000+0000", (it % 20) + 1), value = -10.0) }
        account.transactions[null] = page(items, null)
        account.activity[null] = page(emptyList(), null)
        items.forEach { account.details[it.getString("id")] = tlDetail(ISIN_A, "Aktien" to "2").toString() }
        account.portfolio = """{"categories":[]}"""
        account.cash = """[{"currencyId":"EUR","amount":"0"}]"""
        val batch = sync(account.transport(), TrConfig(detailConcurrency = 3, wafRetryDelayMs = 0))
        assertEquals(30, batch.movements.size)
        assertTrue(batch.movements.all { it.quantity?.toPlainString() == "2" && it.isin == ISIN_A })
        assertTrue(batch.warnings.isEmpty())
    }

    // ---- session ---------------------------------------------------------------------------------------------------

    @Test fun `a 401 on the session endpoint is an expired session and nothing else is sent`() {
        val t = Account().standard().transport(http = { TrHttpResponse(401, "") })
        assertThrows(TrError.SessionExpired::class.java) { sync(t) }
        assertTrue(t.sentFrames.isEmpty())
        assertEquals(1, t.httpCalls.size)
    }

    @Test fun `a 401 on the account endpoint is an expired session`() {
        val t = Account().standard().transport(http = { if (it == TrUrls.SESSION_URL) TrHttpResponse(200, "") else TrHttpResponse(401, "") })
        assertThrows(TrError.SessionExpired::class.java) { sync(t) }
    }

    @Test fun `a persistent 403 is reported as blocked after the configured retries`() {
        val t = Account().standard().transport(http = { TrHttpResponse(403, "") })
        assertThrows(TrError.Blocked::class.java) { sync(t, TrConfig(wafRetries = 2, wafRetryDelayMs = 0)) }
        assertEquals(3, t.httpCalls.size)
    }

    @Test fun `a transient 403 from the WAF is retried`() {
        var first = true
        val t = Account().standard().transport(http = { url ->
            when {
                url == TrUrls.SESSION_URL && first -> { first = false; TrHttpResponse(403, "") }
                url == TrUrls.SESSION_URL -> TrHttpResponse(200, "")
                else -> TrHttpResponse(200, """{"securitiesAccountNumber":"SEC123"}""")
            }
        })
        assertNotNull(sync(t))
    }

    @Test fun `a failed fetch is a connection error`() {
        val t = Account().standard().transport(http = { TrHttpResponse(0, "") })
        assertThrows(TrError.Connection::class.java) { sync(t) }
        val t2 = Account().standard().transport(http = { TrHttpResponse(502, "") })
        assertThrows(TrError.Connection::class.java) { sync(t2) }
    }

    @Test fun `the session is refreshed again after about 290 seconds`() {
        val clock = TestClock(NOW)
        val account = Account().standard()
        account.onTimeline = { clock.now = clock.now.plusSeconds(300) }
        val t = account.transport()
        sync(t, clock = clock)
        assertTrue("session GETs: ${t.httpCalls.count { it == TrUrls.SESSION_URL }}", t.httpCalls.count { it == TrUrls.SESSION_URL } >= 2)
    }

    @Test fun `no refresh while the session is fresh`() {
        val t = Account().standard().transport()
        sync(t)
        assertEquals(1, t.httpCalls.count { it == TrUrls.SESSION_URL })
        assertEquals(1, t.httpCalls.count { it == TrUrls.ACCOUNT_URL })
    }

    // ---- websocket handshake and errors ----------------------------------------------------------------------------

    @Test fun `a wrong handshake answer is a protocol error`() {
        val t = Account().standard().transport(handshake = "nope")
        assertThrows(TrError.Protocol::class.java) { sync(t) }
    }

    @Test fun `no handshake answer times out`() {
        val t = Account().standard().transport(handshake = null)
        assertThrows(TrError.Timeout::class.java) { sync(t, TrConfig(handshakeTimeoutMs = 100, wafRetryDelayMs = 0)) }
    }

    @Test fun `a silent subscription times out with a typed error`() {
        val t = FakeTr(topics = { _, _ -> FakeTr.Reply.Silence })
        assertThrows(TrError.Timeout::class.java) { sync(t, TrConfig(requestTimeoutMs = 100, wafRetryDelayMs = 0)) }
        assertTrue(t.closed)
    }

    @Test fun `an error frame on the main feed is a typed subscription error with a safe code`() {
        val t = FakeTr(topics = { _, _ -> FakeTr.Reply.Err("""{"errors":[{"errorCode":"BAD_SUBSCRIPTION_TYPE","errorMessage":"secret server text"}]}""") })
        val e = assertThrows(TrError.Subscription::class.java) { sync(t) }
        assertEquals("BAD_SUBSCRIPTION_TYPE", e.code)
        assertTrue(e.message!!, "secret" !in e.message!!)
    }

    @Test fun `an authentication-looking error code means the session is gone`() {
        val t = FakeTr(topics = { _, _ -> FakeTr.Reply.Err("""{"errors":[{"errorCode":"UNAUTHORIZED"}]}""") })
        assertThrows(TrError.SessionExpired::class.java) { sync(t) }
    }

    @Test fun `a socket that closes mid-sync is a connection error`() {
        lateinit var t: FakeTr
        t = FakeTr(topics = { type, _ -> if (type == "timelineTransactions") { t.close(); FakeTr.Reply.Silence } else FakeTr.Reply.Silence })
        assertThrows(TrError.Connection::class.java) { sync(t) }
    }

    // ---- soft failures ---------------------------------------------------------------------------------------------

    @Test fun `an unreadable activity log keeps the main feed and warns`() {
        val account = Account().standard()
        account.activity.clear()
        val batch = sync(account.transport())
        assertTrue(batch.warnings.any { "Registro attività" in it })
        assertEquals(setOf("t1", "t2", "t3"), batch.movements.map { it.externalId }.toSet())
    }

    @Test fun `a failed detail keeps the movement without ISIN details and warns`() {
        val account = Account().standard()
        account.details.remove("t1")
        val batch = sync(account.transport())
        val buy = batch.byId("t1")
        assertEquals(ISIN_A, buy.isin)                     // still known from the item's logo
        assertNull(buy.quantity)
        assertTrue(batch.warnings.any { "1 dettagli non letti" in it })
        assertTrue(batch.warnings.any { "senza ISIN o quantità" in it })
    }

    @Test fun `a missing price nulls the balance and warns`() {
        val account = Account().standard()
        account.tickers.remove("$ISIN_B.GER")
        val batch = sync(account.transport())
        assertNull(batch.balance)
        assertTrue(batch.warnings.any { "Prezzo non disponibile" in it && ISIN_B in it })
        assertEquals(5, batch.movements.size)              // the movements are unaffected
    }

    @Test fun `unreadable cash or portfolio nulls the balance and warns`() {
        val noCash = Account().standard().also { it.cash = null }
        val b1 = sync(noCash.transport())
        assertNull(b1.balance)
        assertTrue(b1.warnings.any { "Liquidità" in it })
        val noPortfolio = Account().standard().also { it.portfolio = null }
        val b2 = sync(noPortfolio.transport())
        assertNull(b2.balance)
        assertTrue(b2.warnings.any { "Posizioni" in it })
    }

    @Test fun `the account number falls back to accountPairs and prefers the default product`() {
        val account = Account().standard()
        account.accountPairs = """{"accounts":[{"productType":"OTHER","securitiesAccountNumber":"SEC-OTHER"},{"productType":"DEFAULT","securitiesAccountNumber":"SEC-DEF"}]}"""
        val t = account.transport(http = { if (it == TrUrls.SESSION_URL) TrHttpResponse(200, "") else TrHttpResponse(200, "{}") })
        val batch = sync(t)
        assertEquals("SEC-DEF", t.types("compactPortfolioByType").single().getString("secAccNo"))
        assertNotNull(batch.balance)
    }

    @Test fun `without any account number the portfolio is skipped and the balance is unknown`() {
        val account = Account().standard()
        val t = account.transport(http = { if (it == TrUrls.SESSION_URL) TrHttpResponse(200, "") else TrHttpResponse(200, "{}") })
        val batch = sync(t)
        assertTrue(t.types("compactPortfolioByType").isEmpty())
        assertNull(batch.balance)
        assertTrue(batch.warnings.any { "Numero di deposito" in it })
        assertEquals(5, batch.movements.size)
    }

    @Test fun `items that cannot be imported are counted in a warning`() {
        val account = Account().standard()
        account.transactions[null] = page(
            listOf(
                tlItem(id = "ok", ts = "2026-10-20T09:00:00.000+0000", value = 5.0, eventType = "ACCOUNT_TRANSFER_INCOMING"),
                tlItem(id = "bad", ts = "garbage", value = 5.0),
                tlItem(id = "noamt", ts = "2026-10-20T09:00:00.000+0000", value = null, eventType = "SOME_CORPORATE_ACTION"),
            ),
            null,
        )
        account.activity.clear()
        val batch = sync(account.transport())
        assertEquals(setOf("ok"), batch.movements.map { it.externalId }.toSet())
        assertTrue(batch.warnings.any { "senza importo" in it })
        assertTrue(batch.warnings.any { "dati non validi" in it })
    }

    @Test fun `progress is reported in Italian`() {
        val labels = ArrayList<String>()
        runBlocking { TrSync(Account().standard().transport(), TrConfig(wafRetryDelayMs = 0), TestClock(NOW)).run { labels += it.label } }
        assertTrue(labels.first().startsWith("Controllo"))
        assertEquals("Completato", labels.last())
        assertTrue(labels.any { it.startsWith("Lettura dei movimenti") })
        assertTrue(labels.any { it.startsWith("Lettura dei dettagli") })
    }
}

class TrResultTest {
    private val batch = ImportBatch(ImportProvider.TRADE_REPUBLIC, "Trade Republic", NOW, null, null, emptyList())

    @Test fun `cancelled and logged in`() {
        assertEquals(xyz.grtlabs.pilastri.sync.ConnectorResult.Cancelled, TrResult.decode(false, TrResult.LOGGED_IN, null, null))
        assertEquals(xyz.grtlabs.pilastri.sync.ConnectorResult.LoggedIn, TrResult.decode(true, TrResult.LOGGED_IN, null, null))
        assertEquals(xyz.grtlabs.pilastri.sync.ConnectorResult.SessionExpired, TrResult.decode(true, TrResult.EXPIRED, null, null))
    }

    @Test fun `the batch travels by token and can be taken once`() {
        val token = xyz.grtlabs.pilastri.sync.ImportHandoff.put(batch)
        val first = TrResult.decode(true, TrResult.IMPORTED, token, null)
        assertEquals(xyz.grtlabs.pilastri.sync.ConnectorResult.Imported(batch), first)
        val again = TrResult.decode(true, TrResult.IMPORTED, token, null)
        assertTrue(again is xyz.grtlabs.pilastri.sync.ConnectorResult.Failed)
    }

    @Test fun `failures keep their Italian message and unknown kinds fail safely`() {
        assertEquals(xyz.grtlabs.pilastri.sync.ConnectorResult.Failed("Errore di prova"), TrResult.decode(true, TrResult.FAILED, null, "Errore di prova"))
        assertTrue(TrResult.decode(true, TrResult.FAILED, null, null) is xyz.grtlabs.pilastri.sync.ConnectorResult.Failed)
        assertTrue(TrResult.decode(true, null, null, null) is xyz.grtlabs.pilastri.sync.ConnectorResult.Failed)
        assertTrue(TrResult.decode(true, "bogus", null, null) is xyz.grtlabs.pilastri.sync.ConnectorResult.Failed)
        assertTrue(TrResult.decode(true, TrResult.IMPORTED, null, null) is xyz.grtlabs.pilastri.sync.ConnectorResult.Failed)
    }
}
