package xyz.grtlabs.pilastri.sync.auto

import java.net.URI
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.sync.isybank.FixtureSheet
import xyz.grtlabs.pilastri.sync.isybank.XlsxFixture
import xyz.grtlabs.pilastri.sync.isybank.row

/** Synthetic endpoints on the providers' hosts; no request leaves the test. */
class SessionFetcherTest {
    private val site = ProviderSites.ISYBANK
    private val window = SyncRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private val exportUrl = "https://www.isybank.com/synthetic/export"
    private val xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun spec(
        method: String = "POST",
        url: String = exportUrl,
        headers: Map<String, String> = mapOf("X-Csrf" to "{page:csrf}"),
        body: String? = """{"dal":"{from}","al":"{to}"}""",
        values: Map<String, PageValue> = mapOf("csrf" to PageValue.Cookie("XSRF-TOKEN")),
        windowDays: Int = 365,
    ) = HttpFetchSpec(method, url, headers, body, if (body != null) BodyType.JSON else null, "dd/MM/yyyy", values, windowDays = windowDays)

    private val cookies = FakeCookies(mapOf("www.isybank.com" to "SESSION=s1; XSRF-TOKEN=tok"))
    private val http = FakeHttp()
    private fun fetcher(s: HttpFetchSpec? = spec()) = HttpSessionFetcher(site, s, http, cookies, "UA-test", { T.fetchedAt }, Dispatchers.Unconfined)
    private fun fetch(s: HttpFetchSpec? = spec(), range: SyncRange = window) = runBlocking { fetcher(s).fetch(range, null, mayNavigate = false) }

    private fun xlsx(): ByteArray = XlsxFixture.build(FixtureSheet("Lista Movimenti", buildList<List<Any?>> {
        add(row("Data contabile", "Data valuta", "Descrizione", "Accrediti", "Addebiti", "Descrizione estesa", "Mezzo"))
        add(row(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), "Bonifico a vostro favore", 500.0, null, "Bonifico sintetico", "Bonifico"))
        add(row(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19), "Pagamento POS", null, 12.34, "Pagamento POS sintetico", "Carta"))
    }))

    // ---- request building ----

    @Test fun placeholdersAreEscapedForWhereTheyStand() {
        val s = HttpFetchSpec("POST", "https://www.isybank.com/x?da={from}&q={page:q}", mapOf("X-Id" to "{uuid}"),
            """{"q":"{page:q}","to":"{toEpochMs}"}""", BodyType.JSON, pageValues = mapOf("q" to PageValue.Meta("q")))
        val call = SpecRequest.build(s, site, window, mapOf("q" to "a b\"&c"), uuid = { "u-1" })
        assertEquals("https://www.isybank.com/x?da=2026-10-01&q=a%20b%22%26c", call.url)
        assertEquals("u-1", call.header("X-Id"))
        val body = JSONObject(String(call.body!!))
        assertEquals("a b\"&c", body.getString("q"))
        // 31 Oct 2026 23:59:59.999 in Rome (CET, after the DST change on 25 Oct) = 22:59:59.999 UTC.
        assertEquals("1793487599999", body.getString("to"))
        assertEquals(BodyType.JSON.contentType, call.contentType)
        val form = HttpFetchSpec("POST", exportUrl, bodyTemplate = "dal={from}&x={page:q}", bodyType = BodyType.FORM, pageValues = mapOf("q" to PageValue.Meta("q")))
        assertEquals("dal=2026-10-01&x=a+b%26c", String(SpecRequest.build(form, site, window, mapOf("q" to "a b&c")).body!!))
    }

    @Test fun badSpecsAndValuesFailLoudly() {
        assertThrows(IllegalArgumentException::class.java) { SpecRequest.build(HttpFetchSpec("GET", "https://www.isybank.com/x?d={form}"), site, window, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { SpecRequest.build(HttpFetchSpec("GET", "https://evil.example.test/x"), site, window, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { SpecRequest.build(HttpFetchSpec("GET", "http://www.isybank.com/x"), site, window, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { HttpFetchSpec("GET", exportUrl, headers = mapOf("Cookie" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { HttpFetchSpec("DELETE", exportUrl) }
        assertThrows(IllegalArgumentException::class.java) { HttpFetchSpec("GET", exportUrl, pageValues = mapOf("g" to PageValue.Global("a;alert(1)"))) }
        val injected = HttpFetchSpec("GET", exportUrl, headers = mapOf("X-T" to "{page:t}"), pageValues = mapOf("t" to PageValue.Meta("t")))
        assertThrows(IllegalArgumentException::class.java) { SpecRequest.build(injected, site, window, mapOf("t" to "a\r\nX-Evil: 1")) }
        assertThrows(SpecRequest.MissingValue::class.java) { SpecRequest.build(injected, site, window, emptyMap()) }
    }

    @Test fun rangesSplitIntoWindowsNewestFirst() {
        val r = SyncRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31))
        val w = r.windows(90)
        assertEquals(LocalDate.of(2026, 10, 31), w.first().to)
        assertEquals(LocalDate.of(2026, 1, 1), w.last().from)
        assertTrue(w.all { it.days <= 90 })
        assertEquals(r.days, w.sumOf { it.days })
        assertEquals(SyncRange(LocalDate.of(2026, 10, 18), T.today), SyncRange.incremental(T.today, LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2025, 11, 1), SyncRange.incremental(T.today, null).from)
    }

    // ---- fetching ----

    @Test fun fetchesTheExportWithTheSessionAndParsesIt() {
        http.on(exportUrl) { FakeHttp.ok(xlsx(), xlsxType, "Content-Disposition" to "attachment; filename=\"movimenti.xlsx\"", "Set-Cookie" to "SESSION=s2; Secure") }
        val out = fetch() as FetchOutcome.Fetched
        assertEquals(listOf(50_000L, -1_234L), out.batch.movements.map { it.signedCents })
        assertEquals(ExternalKind.DEPOSIT, out.batch.movements[0].kind)
        val sent = http.calls.single()
        assertEquals("SESSION=s1; XSRF-TOKEN=tok", sent.header("Cookie"))
        assertEquals("tok", sent.header("X-Csrf"))
        assertEquals("UA-test", sent.header("User-Agent"))
        assertEquals("""{"dal":"01/10/2026","al":"31/10/2026"}""", String(sent.body!!))
        assertEquals(listOf(exportUrl to listOf("SESSION=s2; Secure")), cookies.stored)
    }

    @Test fun cookiesNeverLeaveTheProviderEvenThroughRedirects() {
        http.on(exportUrl) { FakeHttp.redirect(302, "https://intesasanpaolo.com/hop") }
        http.on("https://intesasanpaolo.com/hop") { FakeHttp.redirect(302, "https://collector.example.test/steal") }
        val out = fetch()
        assertTrue(out is FetchOutcome.Failed)
        assertTrue(cookies.asked.all { site.isProviderHost(URI(it).host) })
        assertTrue(http.calls.none { URI(it.url).host.endsWith("example.test") })
        assertEquals(2, http.calls.size)
        // The second hop is a GET without the body.
        assertEquals("GET", http.calls[1].method)
        assertNull(http.calls[1].body)
    }

    @Test fun signedOutIsRecognizedByStatusRedirectAndWebPage() {
        http.on(exportUrl) { FakeHttp.status(401) }
        assertEquals(FetchOutcome.SignedOut, fetch())
        http.on(exportUrl) { FakeHttp.redirect(302, "/isyweblogin/webapp/authorize?x=1") }
        assertEquals(FetchOutcome.SignedOut, fetch())
        http.on(exportUrl) { FakeHttp.ok("<html>accedi</html>".toByteArray(), "text/html; charset=utf-8") }
        assertEquals(FetchOutcome.SignedOut, fetch())
        // A declared cookie value that the jar no longer has: no session.
        val noCsrf = HttpSessionFetcher(site, spec(), http, FakeCookies(mapOf("www.isybank.com" to "SESSION=s1")), null, { T.fetchedAt }, Dispatchers.Unconfined)
        assertEquals(FetchOutcome.SignedOut, runBlocking { noCsrf.fetch(window, null, false) })
    }

    @Test fun sizeLimitErrorsAndMissingSpecAreClear() {
        http.on(exportUrl) { FakeHttp.ok(ByteArray(2048), xlsxType) }
        val small = spec().copy(maxBytes = 1024)
        assertTrue((fetch(small) as FetchOutcome.Failed).message.contains("supera"))
        http.on(exportUrl) { FakeHttp.status(500) }
        assertTrue((fetch() as FetchOutcome.Failed).message.contains("500"))
        val none = fetch(null) as FetchOutcome.NotConfigured
        assertTrue(none.message.contains("formato non ancora configurato"))
        assertTrue(http.calls.none { it.url != exportUrl })
    }

    @Test fun longRangesAreFetchedPerWindowAndMerged() {
        http.on(exportUrl) { FakeHttp.ok(xlsx(), xlsxType) }
        val out = fetch(spec(windowDays = 31), SyncRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31))) as FetchOutcome.Fetched
        assertEquals(3, http.calls.size)
        assertEquals(listOf("31/10/2026", "30/09/2026", "30/08/2026"), http.calls.map { JSONObject(String(it.body!!)).getString("al") })
        assertEquals(listOf("01/10/2026", "31/08/2026", "01/08/2026"), http.calls.map { JSONObject(String(it.body!!)).getString("dal") })
        // Identical files per window collapse by id: the same two movements once.
        assertEquals(2, out.batch.movements.size)
    }

    @Test fun pageValuesComeFromThePageAndNeedOne() {
        val s = spec(values = mapOf("csrf" to PageValue.Meta("csrf-token")))
        http.on(exportUrl) { FakeHttp.ok(xlsx(), xlsxType) }
        assertEquals(FetchOutcome.NotReady, runBlocking { fetcher(s).fetch(window, null, false) })
        val page = FakePage("https://www.isybank.com/app/home").apply { onEvaluate = { "\"{\\\"csrf\\\":\\\"from-meta\\\"}\"" } }
        val out = runBlocking { fetcher(s).fetch(window, page, false) }
        assertTrue(out is FetchOutcome.Fetched)
        assertEquals("from-meta", http.calls.last().header("X-Csrf"))
        // On the sign-in page it may load the signed-in page only when allowed to navigate; landing on the sign-in page = signed out.
        val login = FakePage("https://www.isybank.com/isyweblogin/webapp/authorize").apply { onLoad = { "https://www.isybank.com/isyweblogin/x" } }
        assertEquals(FetchOutcome.NotReady, runBlocking { fetcher(s).fetch(window, login, false) })
        assertEquals(FetchOutcome.SignedOut, runBlocking { fetcher(s).fetch(window, login, true) })
    }

    @Test fun pageValueScriptQuotesNamesAndDecodesDoubleEncodedResults() {
        val script = PageValues.script(mapOf("a" to PageValue.Meta("x\"y"), "b" to PageValue.Storage("tok", local = true, jsonPath = "auth.access")))
        assertTrue(script.contains("meta(\"x\\\"y\")"))
        assertTrue(script.contains("store(true,\"tok\",\"auth.access\")"))
        assertEquals(mapOf("a" to "1"), PageValues.decode("\"{\\\"a\\\":\\\"1\\\"}\""))
        assertEquals(mapOf("a" to "1"), PageValues.decode("{\"a\":\"1\"}"))
        assertTrue(PageValues.decode("null").isEmpty() && PageValues.decode(null).isEmpty() && PageValues.decode("garbage").isEmpty())
    }

    @Test fun jsonListsMapByDeclaredPathsWithoutGuessing() {
        val mapping = JsonMapping(items = "data.items", id = "id", date = "when", amount = "amount.value", accountLabel = "isybank",
            currency = "amount.cur", description = "text", type = "kind", status = "state", skipStatuses = setOf("PENDING"),
            kinds = mapOf("pos" to ExternalKind.CARD_PAYMENT))
        val json = """{"data":{"items":[
            {"id":"a1","when":"2026-10-05","amount":{"value":"-12,34","cur":"EUR"},"text":"Bar sintetico","kind":"POS","state":"BOOKED"},
            {"id":"a2","when":"2026-10-06T23:30:00Z","amount":{"value":-0.5},"text":"Sintetico","kind":"altro"},
            {"id":"a4","when":"2026-10-08","amount":{"value":"-3,00"},"state":"pending"}]}}"""
        val b = JsonMovements.read(json.toByteArray(), mapping, site.provider, window, T.fetchedAt)
        assertEquals(listOf("a1", "a2"), b.movements.map { it.externalId })
        assertEquals(listOf(-1_234L, -50L), b.movements.map { it.signedCents })
        assertEquals(LocalDate.of(2026, 10, 7), b.movements[1].date)
        assertEquals(listOf(ExternalKind.CARD_PAYMENT, ExternalKind.OTHER), b.movements.map { it.kind })
        assertTrue(b.warnings.any { it.contains("in sospeso") })
        assertEquals(LocalDate.of(2026, 10, 5), b.coverageFrom)
    }

    @Test fun amountTextReadsItalianAndEnglishShapes() {
        fun p(s: String) = AmountText.parse(s)?.toPlainString()
        assertEquals("-1234.56", p("−1.234,56 €"))
        assertEquals("1234.56", p("1,234.56"))
        assertEquals("12.34", p("+12,34 €"))
        assertEquals("1234", p("1.234"))
        assertEquals("-5.00", p("EUR -5,00"))
        assertNull(p("abc"))
        assertNull(p("1,2,3"))
        assertEquals("USD", AmountText.currency("-3,00 USD"))
        assertFalse(AmountText.currency("12,00") != null)
    }
}
