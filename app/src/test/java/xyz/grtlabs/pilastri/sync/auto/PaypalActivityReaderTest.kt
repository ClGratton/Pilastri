package xyz.grtlabs.pilastri.sync.auto

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.PlanReview

/**
 * SYNTHETIC rows shaped like the recorded PayPal activity list (selectors and label kinds from the 2026-10-31 recording; names,
 * amounts and dates invented).
 */
class PaypalActivityReaderTest {
    private val labels = PaypalActivityLabels()
    private val window = SyncRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 10, 31))

    private fun r(h: String, n: String, a: String, d: String) = JSONObject().put("h", h).put("n", n).put("a", a).put("d", d).put("x", "")
    private fun json(vararg rows: JSONObject, headers: List<String> = rows.map { it.getString("h") }.distinct(), more: Boolean = false, empty: Boolean = false, ready: Boolean = true) =
        JSONObject().put("v", 2).put("rows", JSONArray(rows.toList())).put("headers", JSONArray(headers)).put("more", more).put("empty", empty).put("ready", ready).toString()
    /** What evaluateJavascript hands back: the script's string result, JSON-encoded once more. */
    private fun evaluated(json: String) = JSONObject.quote(json)

    private val cleanRows = arrayOf(
        r("In sospeso", "Negozio Sintetico", "−19,99 €", "30 ott · Pagamento"),
        r("Completato", "Negozio Sintetico", "−12,34 €", "29 ott · Pagamento"),
        r("Questa settimana", "Abbonamento Sintetico", "−5,00 €", "27 ott · Pagamento automatico"),
        r("Questa settimana", "Albergo Sintetico", "−100,00 €", "26 ott · Autorizzazione"),
        r("Settimana scorsa", "Elettronica Sintetica", "−300,00 €", "20 ott · Paga in 3 rate"),
        r("Settimana scorsa", "Arredo Sintetico", "−900,00 €", "19 ott · Paga in 6, 12 o 24 rate"),
        r("set 2026", "Negozio Sintetico", "12,34 €", "15 set · Rimborso"),
        r("set 2026", "Sconosciuto Sintetico", "−7,00 €", "10 set · Operazione misteriosa"),
        r("ago 2026", "Negozio Sintetico", "−1.234,56 €", "4 ago · Pagamento"),
    )
    private val clean = json(*cleanRows)
    /** The same list with one row that does not parse. */
    private val sample = json(*cleanRows, r("ago 2026", "Riga rotta", "abc", "senza data"))

    private fun read(s: String = sample, w: SyncRange = window) =
        PaypalActivityMapper.read(PaypalActivityMapper.snapshot(evaluated(s))!!, w, labels, T.today)

    @Test fun rowsMapToMovementsPlansAndHonestSkips() {
        val read = read(clean)
        val b = PaypalActivityMapper.batch(listOf(read), T.fetchedAt, stoppedEarly = false)
        assertEquals(listOf(-1_234L, -500L, 1_234L, -123_456L), b.movements.map { it.signedCents })
        assertEquals(listOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 8, 4)), b.movements.map { it.date })
        assertEquals(listOf(ExternalKind.CARD_PAYMENT, ExternalKind.CARD_PAYMENT, ExternalKind.OTHER, ExternalKind.CARD_PAYMENT), b.movements.map { it.kind })
        assertEquals("Negozio Sintetico", b.movements[0].description)
        assertEquals("Rimborso · Negozio Sintetico", b.movements[2].description)
        assertEquals("Pagamento automatico", b.movements[1].rawType)
        assertTrue(b.movements.all { it.externalId.startsWith("web:") && it.currency == "EUR" })
        assertEquals(9, read.rows)
        assertEquals(8, read.valid)
        assertEquals(1, read.pending)
        assertEquals(1, read.holds)
        assertEquals(1, read.unknown)
        assertEquals(0, read.invalid)
        assertEquals(window.from, b.coverageFrom)
        assertEquals(window.to, b.coverageTo)
        assertEquals("PayPal", b.accountLabel)
        assertTrue(b.warnings.any { it.contains("in sospeso") } && b.warnings.any { it.contains("autorizzazion") } && b.warnings.any { it.contains("piani") })
    }

    @Test fun aWindowWithAnUnreadableRowKeepsItsMovementsButClaimsNoCoverage() {
        val read = read(sample)
        assertEquals(1, read.invalid)
        assertFalse(read.allInvalid)
        val b = PaypalActivityMapper.batch(listOf(read), T.fetchedAt, stoppedEarly = false)
        assertEquals(4, b.movements.size)
        assertNull(b.coverageFrom)
        assertNull(b.coverageTo)
        assertTrue(b.warnings.any { it.contains("riga non leggibile") } && b.warnings.any { it.contains("nessun periodo dato per completo") })
        assertNull(PaypalActivityMapper.verdict(listOf(read)))
    }

    @Test fun installmentRowsBecomePendingPlansWithUnknownParts() {
        val plans = PaypalActivityMapper.batch(listOf(read()), T.fetchedAt, false).installmentPlans
        assertEquals(2, plans.size)
        val pi3 = plans.first { it.lender == PaypalActivityMapper.PI3_LENDER }
        assertEquals(3, pi3.count)
        assertEquals(30_000L, pi3.totalCents)
        assertTrue(pi3.installments.isEmpty())
        assertEquals("Elettronica Sintetica", pi3.merchant)
        assertEquals(LocalDate.of(2026, 10, 20), pi3.purchaseDate)
        assertEquals(InstallmentSource.PAYPAL, pi3.source)
        assertEquals(PlanReview.PENDING, pi3.review)
        assertEquals("Paga in 3 rate · Elettronica Sintetica · 300,00 €", pi3.evidence.single().summary)
        val long = plans.first { it.lender == PaypalActivityMapper.LONG_LENDER }
        assertNull(long.count)
        assertEquals(3, pi3.remainingUnknown)
    }

    @Test fun idsAreStableAcrossReadsAndDistinctForIdenticalRows() {
        val twins = json(
            r("Completato", "Bar Sintetico", "−1,50 €", "29 ott · Pagamento"),
            r("Completato", "Bar Sintetico", "−1,50 €", "29 ott · Pagamento"),
        )
        val a = read(twins).movements.map { it.externalId }
        val b = read(twins).movements.map { it.externalId }
        assertEquals(a, b)
        assertEquals(2, a.distinct().size)
    }

    @Test fun yearsComeFromTheRowTheMonthHeaderOrTheWindow() {
        val winter = SyncRange(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 28))
        val rows = json(
            r("Settimana scorsa", "A", "−1,00 €", "5 gen · Pagamento"),
            r("Questa settimana", "B", "−1,00 €", "29 dic · Pagamento"),
            r("dic 2024", "C", "−1,00 €", "3 dic · Pagamento"),
            r("Completato", "D", "−1,00 €", "2 mar 2023 · Pagamento"),
        )
        val dates = read(rows, winter).movements.map { it.date }
        assertEquals(listOf(LocalDate.of(2026, 1, 5), LocalDate.of(2025, 12, 29), LocalDate.of(2024, 12, 3), LocalDate.of(2023, 3, 2)), dates)
        assertNull(PaypalActivityMapper.dateOf(31, 2, 2026, winter, T.today))
    }

    /** Description shapes PayPal may print (the Fold showed rows that the first parser could not read). All synthetic. */
    @Test fun descriptionsWithDayPrefixesOtherSeparatorsYearsAndNbspParse() {
        val nbsp = ' '
        val narrow = ' '
        val cases = listOf(
            "Oggi, 30 ott · Pagamento automatico" to (LocalDate.of(2026, 10, 30) to "Pagamento automatico"),
            "Ieri, 29 ott · Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "ven, 29 ott · Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "venerdì 29 ott • Autorizzazione" to (LocalDate.of(2026, 10, 29) to "Autorizzazione"),
            "ven.,29 ott·Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "29 ott . Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "29 ott. Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "29 ott.Rimborso" to (LocalDate.of(2026, 10, 29) to "Rimborso"),
            "29 ott 2025 · Pagamento" to (LocalDate.of(2025, 10, 29) to "Pagamento"),
            "lun, 3 ago 2026 • Paga in 3 rate" to (LocalDate.of(2026, 8, 3) to "Paga in 3 rate"),
            "29${nbsp}ott${nbsp}·${nbsp}Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "Oggi,${narrow}30${nbsp}ott ${nbsp}·  Pagamento automatico " to (LocalDate.of(2026, 10, 30) to "Pagamento automatico"),
            "1 settembre · Pagamento" to (LocalDate.of(2026, 9, 1) to "Pagamento"),
            "Oggi · Pagamento" to (T.today to "Pagamento"),
            "Ieri • Rimborso" to (T.today.minusDays(1) to "Rimborso"),
        )
        for ((text, expected) in cases) {
            val d = PaypalActivityMapper.describe(text, "Completato", window, labels, T.today)
            assertEquals(text, expected.first, d?.date)
            assertEquals(text, expected.second, d?.typeLabel)
        }
        for (bad in listOf("senza data", "29 xyz · Pagamento", "· Pagamento", "Pagamento", "")) {
            assertNull(bad, PaypalActivityMapper.describe(bad, "Completato", window, labels, T.today))
        }
    }

    @Test fun aWindowThatMayHoldMoreRowsLimitsCoverage() {
        val newest = read(clean)
        val older = PaypalActivityMapper.read(PaypalActivityMapper.snapshot(evaluated(json(
            r("lug 2026", "X", "−2,00 €", "20 lug · Pagamento"), more = true)))!!,
            SyncRange(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 8, 2)), labels, T.today)
        val b = PaypalActivityMapper.batch(listOf(newest, older), T.fetchedAt, false)
        // The list is cut at its oldest end: the day of the oldest row read may have hidden rows too, so the claim starts the day after.
        assertEquals(LocalDate.of(2026, 7, 21), b.coverageFrom)
        assertEquals(window.to, b.coverageTo)
        assertTrue(b.warnings.any { it.contains("forse incompleto") })
    }

    @Test fun theListStateNeedsALoadedDocumentAndRowsOrTheEmptyStateInsideTheList() {
        assertNull(PaypalActivityMapper.snapshot(evaluated(json()))!!.listState)
        assertEquals("empty", PaypalActivityMapper.snapshot(evaluated(json(empty = true)))!!.listState)
        // Section headers alone are not a list (the Fold accepted such a page at once and read nothing).
        assertNull(PaypalActivityMapper.snapshot(json(headers = listOf("Completato")))!!.listState)
        assertNull(PaypalActivityMapper.snapshot(evaluated(json(*cleanRows, ready = false)))!!.listState)
        assertEquals("rows:9", PaypalActivityMapper.snapshot(evaluated(clean))!!.listState)
        assertNull(PaypalActivityMapper.snapshot("null"))
        assertNull(PaypalActivityMapper.snapshot("<html>"))
    }

    @Test fun typeLabelsComeFromTheTableThenFromKeywords() {
        assertEquals(PaypalRowType.PLAN_LONG, PaypalActivityMapper.typeOf("Paga in 6, 12 o 24 rate", labels))
        assertEquals(PaypalRowType.HOLD, PaypalActivityMapper.typeOf("Autorizzazione", labels))
        assertEquals(PaypalRowType.REFUND, PaypalActivityMapper.typeOf("Rimborso parziale", labels))
        assertEquals(PaypalRowType.PLAN_IN_3, PaypalActivityMapper.typeOf("Pay in 3", labels))
        assertEquals(PaypalRowType.UNKNOWN, PaypalActivityMapper.typeOf("Operazione misteriosa", labels))
    }

    @Test fun nothingReadAndNoEmptyStateIsAFailureNotAnEmptyResult() {
        assertEquals(PaypalActivityMapper.FORMAT_FAILED, PaypalActivityMapper.verdict(emptyList()))
        val onlyBroken = read(json(r("Completato", "A", "−1,00 €", "Pagamento ricorrente"), r("Completato", "B", "?", "29 ott · Pagamento")))
        assertTrue(onlyBroken.allInvalid)
        assertEquals(PaypalActivityMapper.FORMAT_FAILED, PaypalActivityMapper.verdict(listOf(read(clean), onlyBroken)))
        val confirmedEmpty = read(json(empty = true))
        assertTrue(confirmedEmpty.emptyConfirmed)
        assertNull(PaypalActivityMapper.verdict(listOf(confirmedEmpty)))
        val onlyPending = read(json(r("In sospeso", "A", "−1,00 €", "30 ott · Pagamento")))
        assertNull(PaypalActivityMapper.verdict(listOf(onlyPending)))
    }

    // ---- the reader over a fake page ----

    private val fast = PaypalActivitySpec(waitMs = 60, pollMs = 5, betweenWindowsMs = 0, loadStepMs = 1)
    private fun reader(log: (String) -> Unit = {}) = PaypalActivityReader(fast, { T.fetchedAt }, { T.today }, log)

    @Test fun readsEachWindowFromTheListPage() {
        val page = FakePage("https://www.paypal.com/myaccount/summary").apply { onEvaluate = { evaluated(clean) } }
        val range = SyncRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 31))
        val out = runBlocking { reader().fetch(range, page, mayNavigate = true) }
        assertTrue(out is FetchOutcome.Fetched)
        assertEquals(3, page.loads.size)
        assertTrue(page.loads[0].startsWith("https://www.paypal.com/myaccount/activities/?free_text_search=&start_date=2026-08-03&end_date=2026-10-31&"))
        assertTrue(page.loads.none { it.contains("/myaccount/money") })
        assertEquals(range.from, (out as FetchOutcome.Fetched).batch.coverageFrom)
    }

    @Test fun theFoldShapeOfRowsIsReadInsteadOfClaimingAnEmptyYear() {
        // 32 rows like the user's list (descriptions prefixed by a day word), all invented.
        val rows = (0 until 32).map { i ->
            val day = 30 - (i % 28)
            r(if (i < 2) "Questa settimana" else "set 2026", "Negozio $i", "−${i + 1},00 €", (if (i == 0) "Oggi, " else "ven, ") + "$day ${if (i < 2) "ott" else "set"} · Pagamento automatico")
        }
        val page = FakePage().apply { onEvaluate = { evaluated(json(*rows.toTypedArray())) } }
        // This list is below the page size of the spec used here (a cut list is tested separately below).
        val out = runBlocking { PaypalActivityReader(fast.copy(pageCap = 40), { T.fetchedAt }, { T.today }).fetch(window, page, true) } as FetchOutcome.Fetched
        assertEquals(32, out.batch.movements.size)
        assertEquals(window.from, out.batch.coverageFrom)
    }

    @Test fun allRowsUnreadableFailsWithoutCoverage() {
        val broken = json(r("Completato", "A", "−1,00 €", "Pagamento ricorrente"), r("Completato", "B", "−2,00 €", "pagamento del mese"))
        val page = FakePage().apply { onEvaluate = { evaluated(broken) } }
        val out = runBlocking { reader().fetch(window, page, true) }
        assertEquals(FetchOutcome.Failed("Lettura di PayPal non riuscita: formato da aggiornare"), out)
    }

    @Test fun aGrowingListIsReadOnlyOnceItSettles() {
        var polls = 0
        val page = FakePage().apply {
            onEvaluate = {
                polls++
                when (polls) {
                    1 -> evaluated(json(ready = false))
                    2 -> evaluated(json(cleanRows[1]))
                    else -> evaluated(clean)
                }
            }
        }
        val out = runBlocking { reader().fetch(window, page, true) } as FetchOutcome.Fetched
        assertEquals(4, out.batch.movements.size)
        assertTrue(polls >= 4)
    }

    @Test fun aListThatNeverLoadsOrNeverSettlesFails() {
        val loading = FakePage().apply { onEvaluate = { evaluated(json(*cleanRows, ready = false)) } }
        assertTrue((runBlocking { reader().fetch(window, loading, true) } as FetchOutcome.Failed).message.contains("formato da aggiornare"))
        val headersOnly = FakePage().apply { onEvaluate = { evaluated(json()) } }
        assertEquals(FetchOutcome.Failed(PaypalActivityMapper.NOT_LOADED), runBlocking { reader().fetch(window, headersOnly, true) })
        var n = 0
        val flickering = FakePage().apply { onEvaluate = { n++; evaluated(json(*cleanRows.take(1 + n % 3).toTypedArray())) } }
        assertTrue(runBlocking { reader().fetch(window, flickering, true) } is FetchOutcome.Failed)
    }

    @Test fun aConfirmedEmptyListIsAnEmptyReadWithCoverage() {
        val page = FakePage().apply { onEvaluate = { evaluated(json(empty = true)) } }
        val out = runBlocking { reader().fetch(window, page, true) } as FetchOutcome.Fetched
        assertTrue(out.batch.movements.isEmpty())
        assertEquals(window.from, out.batch.coverageFrom)
        assertEquals(window.to, out.batch.coverageTo)
    }

    @Test fun aLaterWindowThatNeverLoadsEndsTheCoverageAtTheLastConfirmedWindow() {
        val range = SyncRange(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 10, 31))
        val page = FakePage().apply { onEvaluate = { if (url.orEmpty().contains("end_date=2026-10-31")) evaluated(clean) else evaluated(json()) } }
        val out = runBlocking { reader().fetch(range, page, true) } as FetchOutcome.Fetched
        assertEquals(2, page.loads.size)
        assertEquals(window.from, out.batch.coverageFrom)
        assertEquals(window.to, out.batch.coverageTo)
        assertTrue(out.batch.warnings.any { it.contains("forse incompleto") })
    }

    @Test fun signInPageMeansSignedOutAndNoNavigationMeansNotReady() {
        val reader = reader()
        val page = FakePage("https://www.paypal.com/myaccount/summary").apply { onLoad = { "https://www.paypal.com/signin?returnUri=x" } }
        assertEquals(FetchOutcome.SignedOut, runBlocking { reader.fetch(window, page, true) })
        assertEquals(FetchOutcome.NotReady, runBlocking { reader.fetch(window, page, false) })
        assertEquals(FetchOutcome.NotReady, runBlocking { reader.fetch(window, null, true) })
        val captcha = FakePage().apply { onLoad = { "https://www.paypal.com/auth/validatecaptcha" } }
        assertEquals(FetchOutcome.Challenge, runBlocking { reader.fetch(window, captcha, true) })
    }

    @Test fun theLogCarriesCountsPerWindowAndNoValues() {
        val lines = ArrayList<String>()
        val page = FakePage().apply { onEvaluate = { evaluated(sample) } }
        runBlocking { reader { lines += it }.fetch(window, page, true) }
        assertEquals(listOf("paypal window 1/1: rows=10 valid=8 invalid=1 pending=1 emptyConfirmed=false more=false"), lines.filter { it.startsWith("paypal window") })
        // The two plan rows of the sample have no link: the log says so (counts only), which is why no plan page is read.
        assertTrue(lines.contains("paypal plans: rowPlans=2 withLink=0 archive=0"))
        assertTrue(lines.none { it.contains("Negozio") || it.contains("€") || it.contains("ott") })
    }

    @Test fun theReadScriptCarriesTheSelectorTableAndLooksForTheEmptyStateOnlyInTheList() {
        val s = PaypalActivityReader.script(fast)
        assertTrue(s.contains("[data-testid=trans_overview_root_container]"))
        assertTrue(s.contains("[data-testid=transaction_description]"))
        assertTrue(s.contains("\"mostra altro\""))
        assertTrue(s.contains("parentElement") && s.contains("document.readyState === 'complete'"))
        assertFalse(s.contains("document.body"))
        assertFalse(s.contains("click()"))
        // Rows, headers, the empty state and "load more" are looked up inside the list container only.
        assertTrue(s.contains("root.querySelectorAll(S.header + ',' + S.row)"))
        assertFalse(s.contains("document.querySelectorAll(S.header + ',' + S.row)"))
        assertTrue(s.contains("root && rows.length === 0"))
        assertTrue(s.contains("S.list ? document.querySelector(S.list)"))
    }

    /** Missing and extra parts never make a row unreadable; only a row with no date is. All synthetic. */
    @Test fun descriptionsWithMissingOrExtraPartsKeepTheRow() {
        val cases = listOf(
            "29 ott" to (LocalDate.of(2026, 10, 29) to ""),
            "Oggi" to (T.today to ""),
            "29 ott · Pagamento · Carta di credito" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "29 ott · alle 10:12 · Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "29 ott 10:12 · Rimborso" to (LocalDate.of(2026, 10, 29) to "Rimborso"),
            "29 ott · Conto · Autorizzazione" to (LocalDate.of(2026, 10, 29) to "Autorizzazione"),
            "Pagamento · 29 ott" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "ven, 29 ott" to (LocalDate.of(2026, 10, 29) to ""),
            "19 ott · Paga in 6, 12 o 24 rate" to (LocalDate.of(2026, 10, 19) to "Paga in 6, 12 o 24 rate"),
            "29/10/2026 - Pagamento" to (LocalDate.of(2026, 10, 29) to "Pagamento"),
            "29 oct · Payment" to (LocalDate.of(2026, 10, 29) to "Payment"),
        )
        for ((text, expected) in cases) {
            val d = PaypalActivityMapper.describe(text, "Completato", window, labels, T.today)
            assertEquals(text, expected.first, d?.date)
            assertEquals(text, expected.second, d?.typeLabel)
        }
        // The date or the type may sit on the row's second line.
        assertEquals(LocalDate.of(2026, 10, 29) to "Pagamento",
            PaypalActivityMapper.describe("Pagamento", "Completato", window, labels, T.today, extra = "29 ott").let { it?.date to it?.typeLabel })
        assertEquals(LocalDate.of(2026, 10, 29) to "Pagamento automatico",
            PaypalActivityMapper.describe("29 ott", "Completato", window, labels, T.today, extra = "Pagamento automatico").let { it?.date to it?.typeLabel })
    }

    @Test fun aRowWithoutTypeIsReadAndCountedButItsOutflowIsNotGuessed() {
        val read = read(json(
            r("Completato", "Negozio Sintetico", "−12,34 €", "29 ott"),
            r("Completato", "Negozio Sintetico", "−5,00 €", "28 ott · Pagamento · Saldo PayPal"),
        ))
        assertEquals(2, read.valid)
        assertEquals(0, read.invalid)
        assertEquals(1, read.unknown)
        assertEquals(listOf(-500L), read.movements.map { it.signedCents })
        assertTrue(read.complete)
        assertNull(PaypalActivityMapper.verdict(listOf(read)))
    }

    @Test fun rowsOutsideTheListAreCountedForTheLogOnly() {
        val snap = PaypalActivityMapper.snapshot(evaluated(JSONObject(clean).put("outside", 3).toString()))!!
        assertEquals(3, snap.outside)
        assertEquals(9, snap.rows.size)
        val lines = ArrayList<String>()
        val page = FakePage().apply { onEvaluate = { evaluated(JSONObject(clean).put("outside", 2).toString()) } }
        runBlocking { reader { lines += it }.fetch(window, page, true) }
        assertTrue(lines.single { it.startsWith("paypal window") }.endsWith(" outside=2"))
    }

    @Test fun headersWithoutRowsAndWithoutTheEmptyStateNeverSettle() {
        val s = PaypalActivityMapper.snapshot(evaluated(json(headers = listOf("In sospeso", "Completato"))))!!
        assertNull(s.listState)
        val emptyRead = read(json(empty = false))
        assertEquals(PaypalActivityMapper.FORMAT_FAILED, PaypalActivityMapper.verdict(listOf(emptyRead)))
    }

    // ---- a window the page may cut (2026-11-01): the Fold showed exactly 30 rows for two 90-day windows ----

    private val itMonth = mapOf(8 to "ago", 9 to "set", 10 to "ott")

    /** A synthetic server: every day from [newest] back holds one row; the page shows at most [cap] rows per window, newest first. */
    private inner class CuttingServer(val days: Int, val cap: Int, val newest: LocalDate = LocalDate.of(2026, 10, 31)) {
        val all = (0 until days).map { i -> newest.minusDays(i.toLong()) to i }
        fun rowsFor(url: String): List<JSONObject> {
            val from = LocalDate.parse(Regex("start_date=([0-9-]{10})").find(url)!!.groupValues[1])
            val to = LocalDate.parse(Regex("end_date=([0-9-]{10})").find(url)!!.groupValues[1])
            return all.filter { (d, _) -> !d.isBefore(from) && !d.isAfter(to) }.take(cap).map { (d, i) ->
                val mon = itMonth.getValue(d.monthValue)
                r("$mon 2026", "Negozio $i", "−${1 + i % 9},00 €", "${d.dayOfMonth} $mon · Pagamento")
            }
        }
    }

    private fun serve(server: CuttingServer) = FakePage().apply { onEvaluate = { evaluated(json(*server.rowsFor(url!!).toTypedArray())) } }

    @Test fun aWindowThatSitsAtThePageSizeIsReadAgainAsHalvesUntilEveryRowIsShown() {
        val server = CuttingServer(days = 50, cap = 30)
        val page = serve(server)
        val lines = ArrayList<String>()
        val out = runBlocking { reader { lines += it }.fetch(window, page, true) } as FetchOutcome.Fetched
        assertEquals(50, out.batch.movements.size)
        assertEquals(window.from, out.batch.coverageFrom)
        assertEquals(window.to, out.batch.coverageTo)
        assertFalse(out.batch.warnings.any { it.contains("forse incompleto") })
        // The whole window, its two halves, and the newer half (45 days, still cut) as two quarters.
        assertEquals(5, page.loads.size)
        assertEquals(2, lines.count { it.endsWith(" split") })
        assertTrue(page.loads[1].contains("start_date=2026-09-17&end_date=2026-10-31"))
        assertTrue(lines.none { it.contains("capped") })
        assertTrue(lines.first().startsWith("paypal window 1/1: rows=30 ") && lines.first().endsWith(" split"))
    }

    @Test fun aCutWindowThatCannotBeSplitFurtherClaimsNothingOlderThanItsOldestRowRead() {
        val server = CuttingServer(days = 50, cap = 30)
        val lines = ArrayList<String>()
        val noSplits = PaypalActivityReader(fast.copy(maxSplits = 0), { T.fetchedAt }, { T.today }) { lines += it }
        val out = runBlocking { noSplits.fetch(window, serve(server), true) } as FetchOutcome.Fetched
        assertEquals(30, out.batch.movements.size)
        // The 30 newest days are 10-02..10-31: coverage starts the day after the oldest row read, not at the window start.
        assertEquals(LocalDate.of(2026, 10, 3), out.batch.coverageFrom)
        assertEquals(window.to, out.batch.coverageTo)
        assertTrue(out.batch.warnings.any { it.contains("forse incompleto") })
        assertTrue(lines.single().endsWith(" capped"))
    }

    @Test fun aSingleDayWithManyRowsIsNeverClaimedAsComplete() {
        val day = LocalDate.of(2026, 10, 31)
        val rows = (0 until 31).map { i -> r("ott 2026", "Negozio $i", "−${1 + i},00 €", "31 ott · Pagamento") }
        val page = FakePage().apply { onEvaluate = { evaluated(json(*rows.toTypedArray())) } }
        val out = runBlocking { reader().fetch(SyncRange(day, day), page, true) } as FetchOutcome.Fetched
        assertEquals(31, out.batch.movements.size)
        assertNull(out.batch.coverageFrom)
        assertNull(out.batch.coverageTo)
        assertEquals(1, page.loads.size)
    }

    @Test fun aListThatGrowsWhenScrolledIsReadInFullWithoutSplitting() {
        var batches = 1
        val everything = (0 until 70).map { i -> r("ott 2026", "Negozio $i", "−${1 + i % 9},00 €", "${31 - i / 3} ott · Pagamento") }
        val page = FakePage().apply {
            onEvaluate = { script ->
                if (script.contains("pressed")) { batches++; "\"{}\"" }
                else evaluated(json(*everything.take(minOf(70, 30 * batches)).toTypedArray()))
            }
        }
        val lines = ArrayList<String>()
        val out = runBlocking { reader { lines += it }.fetch(window, page, true) } as FetchOutcome.Fetched
        assertEquals(70, out.batch.movements.size)
        assertEquals(1, page.loads.size)
        assertEquals(window.from, out.batch.coverageFrom)
        assertTrue(lines.single().contains(" loaded=+40") && !lines.single().contains("split") && !lines.single().contains("capped"))
    }

    @Test fun aListThatKeepsGrowingPastTheStepBoundClaimsNoCoverageForItsTail() {
        var grown = 0
        val page = FakePage().apply {
            onEvaluate = { script ->
                if (script.contains("pressed")) { grown++; "\"{}\"" }
                else evaluated(json(*(0 until 5 + grown).map { i -> r("ott 2026", "Negozio $i", "−1,00 €", "${31 - i} ott · Pagamento") }.toTypedArray()))
            }
        }
        val lines = ArrayList<String>()
        val bounded = PaypalActivityReader(fast.copy(maxLoadSteps = 4), { T.fetchedAt }, { T.today }) { lines += it }
        val out = runBlocking { bounded.fetch(window, page, true) } as FetchOutcome.Fetched
        assertTrue(lines.single().contains("loadBound"))
        assertTrue(out.batch.coverageFrom!!.isAfter(window.from))
        assertTrue(out.batch.warnings.any { it.contains("forse incompleto") })
    }

    @Test fun halvesAreNewestFirstContiguousAndNullForASingleDay() {
        val h = PaypalActivityMapper.halves(window)!!
        assertEquals(SyncRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 10, 31)), h.first)
        assertEquals(SyncRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 16)), h.second)
        val odd = PaypalActivityMapper.halves(SyncRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)))!!
        assertEquals(SyncRange(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)), odd.first)
        assertEquals(SyncRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)), odd.second)
        assertNull(PaypalActivityMapper.halves(SyncRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1))))
    }

    @Test fun theLoadMoreScriptScrollsPressesOnlyAButtonAndFollowsNoLink() {
        val s = PaypalActivityReader.loadMoreScript(fast)
        assertTrue(s.contains("scrollTo") && s.contains("scrollTop") && s.contains("\"mostra altro\""))
        assertTrue(s.contains("click()"))
        // A link with a destination is skipped; nothing sets location or opens a window.
        assertTrue(s.contains("href.charAt(0) !== '#'") && s.contains("continue;"))
        assertFalse(s.contains("location") || s.contains("window.open") || s.contains("submit"))
        // The read script stays free of any click.
        assertFalse(PaypalActivityReader.script(fast).contains("click()"))
    }

    @Test fun aPlanLinkIsLoggedAsAMaskedShapeOnly() {
        val reader = reader()
        assertEquals("/myaccount/pay-later/plan/:id?locale", reader.linkShape("https://www.paypal.com/myaccount/pay-later/plan/9XK12345?locale=it_IT"))
        assertEquals("/myaccount/activity/details/:id", reader.linkShape("/myaccount/activity/details/1AB23456CD789012E"))
        assertEquals("/a/b", reader.linkShape("/a/b#frag"))
    }
}
