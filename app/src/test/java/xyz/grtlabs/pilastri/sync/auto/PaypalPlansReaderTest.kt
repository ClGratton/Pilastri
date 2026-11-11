package xyz.grtlabs.pilastri.sync.auto

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PLAN_PAGE_REF
import xyz.grtlabs.pilastri.data.PlanPageLink
import xyz.grtlabs.pilastri.data.installmentCommitment
import xyz.grtlabs.pilastri.data.paidCount
import xyz.grtlabs.pilastri.data.progressText
import xyz.grtlabs.pilastri.data.withImportBatch

/**
 * SYNTHETIC plan pages. The SHAPE (schedule rows holding "Rata N di M", an amount, a date and a status word; a "Totale" line) is the
 * shape the parser expects, NOT yet a recording of the user's page (see output/pp-plans-shapes.md); names, amounts and dates invented.
 */
class PaypalPlansReaderTest {
    private val labels = PaypalPlanLabels()
    private val today = T.today

    private fun page(rows: List<String>, lines: List<String>, ready: Boolean = true) =
        JSONObject().put("v", 1).put("ready", ready).put("rows", JSONArray(rows)).put("lines", JSONArray(lines)).put("items", 0).toString()
    private fun evaluated(json: String) = JSONObject.quote(json)
    private fun parse(rows: List<String>, lines: List<String> = listOf("Dettagli del piano", "Totale", "300,00 €", "Paga in 3 rate")) =
        PaypalPlanMapper.parse(PaypalPlanMapper.snapshot(evaluated(page(rows, lines)))!!, labels, today)

    private val pi3Rows = listOf(
        "Rata 1 di 3 · 100,00 € · 3 ago 2026 · Pagata",
        "Rata 2 di 3 · 100,00 € · 2 set 2026 · Pagata",
        "Rata 3 di 3 · 100,00 € · 2 nov 2026 · Da pagare",
    )
    private val pi3Lines = listOf("Dettagli del piano", "Negozio Sintetico", "Totale", "300,00 €", "Paga in 3 rate") + pi3Rows

    // ---- the page parser ----

    @Test fun aPayIn3PageGivesTotalCountAndEveryPart() {
        val p = parse(pi3Rows, pi3Lines)!!
        assertEquals(30_000L, p.totalCents)
        assertEquals(3, p.count)
        assertEquals(listOf(1, 2, 3), p.parts.map { it.seq })
        assertEquals(listOf(true, true, false), p.parts.map { it.paid })
        assertEquals(listOf(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 11, 2)), p.parts.map { it.date })
        assertTrue(p.parts.all { it.cents == 10_000L })
    }

    @Test fun theLongPlanPageGivesItsCountEvenWhenOnlyPartOfTheScheduleIsListed() {
        val rows = listOf(
            "Rata 1 di 12 · 100,00 € · 3 ago 2026 · Pagata",
            "Rata 2 di 12 · 100,00 € · 3 set 2026 · Pagata",
            "Rata 3 di 12 · 100,00 € · 3 ott 2026 · Pagata",
            "Rata 4 di 12 · 100,00 € · 3 nov 2026 · Da pagare",
        )
        val p = parse(rows, listOf("Dettagli del piano", "Totale", "1.200,00 €", "Paga in 12 rate"))!!
        assertEquals(12, p.count)
        assertEquals(120_000L, p.totalCents)
        assertEquals(4, p.parts.size)       // the other eight are not listed: they stay unknown, not invented
        assertEquals(3, p.parts.count { it.paid })
    }

    @Test fun theCountComesFromAPlanLineWhenRowsAreNotNumbered() {
        val rows = listOf("3 ago 2026 · 100,00 € · Pagata", "2 set 2026 · 100,00 € · Da pagare", "2 ott 2026 · 100,00 € · Da pagare")
        val p = parse(rows, listOf("Dettagli", "Totale 300,00 €", "Paga in 3 rate", "Piano"))!!
        assertEquals(listOf(1, 2, 3), p.parts.map { it.seq })
        assertEquals(3, p.count)
        assertEquals(30_000L, p.totalCents)
    }

    @Test fun aDateWithoutAYearTakesTheYearNearestToday() {
        val rows = listOf("Rata 1 di 3 · 100,00 € · 3 ago · Pagata", "Rata 2 di 3 · 100,00 € · 2 set · Pagata", "Rata 3 di 3 · 100,00 € · 2 nov · Da pagare")
        assertEquals(LocalDate.of(2026, 11, 2), parse(rows, pi3Lines)!!.parts.last().date)
        // More than ~4 months from today and no year written: not guessed.
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · 3 mag · Da pagare", "Rata 2 di 3 · 100,00 € · 3 giu · Da pagare", "Rata 3 di 3 · 100,00 € · 3 lug · Da pagare"), pi3Lines))
    }

    @Test fun anythingThatDoesNotAddUpIsNotRecognized() {
        assertNull(parse(emptyList()))
        assertNull(parse(listOf("Nessuna rata")))
        // two amounts in one row
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € di 300,00 € · 3 ago 2026 · Pagata")))
        // no status word
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · 3 ago 2026")))
        // paid and still to pay in one row
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · 3 ago 2026 · Pagata · Da pagare")))
        // no date
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · Pagata")))
        // some rows numbered, some not
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · 3 ago 2026 · Pagata", "100,00 € · 2 set 2026 · Da pagare")))
        // a part beyond the stated count
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · 3 ago 2026 · Pagata", "Rata 4 di 3 · 100,00 € · 2 set 2026 · Da pagare")))
        // the whole schedule is listed but does not add up to the total
        assertNull(parse(pi3Rows, listOf("Dettagli", "Totale", "290,00 €", "Paga in 3 rate")))
        // duplicate part numbers
        assertNull(parse(listOf("Rata 1 di 3 · 100,00 € · 3 ago 2026 · Pagata", "Rata 1 di 3 · 100,00 € · 2 set 2026 · Da pagare")))
    }

    @Test fun aProviderStatedLateStaysLateAndTheTotalIsOptional() {
        val p = parse(listOf("Rata 1 di 2 · 50,00 € · 3 ago 2026 · In ritardo", "Rata 2 di 2 · 50,00 € · 3 nov 2026 · Da pagare"), listOf("Dettagli", "Piano", "Prezzo"))!!
        assertNull(p.totalCents)
        assertEquals(listOf(true, false), p.parts.map { it.late })
    }

    // ---- the reader over a fake PayPal session ----

    private val fast = PaypalActivitySpec(waitMs = 60, pollMs = 5, betweenWindowsMs = 0, plans = PaypalPlanSpec(waitMs = 60, pollMs = 5, betweenPagesMs = 0))
    private fun reader(log: (String) -> Unit = {}) = PaypalActivityReader(fast, { T.fetchedAt }, { T.today }, log)

    private fun row(h: String, n: String, a: String, d: String, link: String = "") =
        JSONObject().put("h", h).put("n", n).put("a", a).put("d", d).put("x", "").put("l", link)
    private fun list(vararg rows: JSONObject) =
        JSONObject().put("v", 3).put("rows", JSONArray(rows.toList())).put("headers", JSONArray(rows.map { it.getString("h") }.distinct())).put("more", false).put("empty", false).put("ready", true).toString()

    private val planLink = "/myaccount/activities/details/SYNTH0001"
    private val longLink = "/myaccount/activities/details/SYNTH0002"
    private val activity = list(
        row("Questa settimana", "Negozio Sintetico", "−12,34 €", "29 ott · Pagamento"),
        row("Settimana scorsa", "Elettronica Sintetica", "−300,00 €", "20 ott · Paga in 3 rate", planLink),
        row("Settimana scorsa", "Arredo Sintetico", "−1.200,00 €", "19 ott · Paga in 6, 12 o 24 rate", longLink),
    )
    private val longRows = listOf("Rata 1 di 12 · 100,00 € · 19 ott 2026 · Pagata", "Rata 2 di 12 · 100,00 € · 19 nov 2026 · Da pagare")
    private val longLines = listOf("Dettagli del piano", "Totale", "1.200,00 €", "Paga in 12 rate")

    /** Activity list pages answer the list script; plan pages answer [pages] by their path. */
    private fun session(pages: Map<String, String?>, activityJson: String = activity, onLoad: (String) -> String? = { it }) = FakePage("https://www.paypal.com/myaccount/summary").apply {
        this.onLoad = onLoad
        onEvaluate = { script ->
            if ("trans_overview_root_container" in script) evaluated(activityJson)
            else pages.entries.firstOrNull { (path, _) -> url.orEmpty().endsWith(path) }?.value?.let(::evaluated)
        }
    }

    @Test fun thePlanPagesOfTheRowsAreReadAndReplaceTheRowMadePlans() {
        val logs = ArrayList<String>()
        val page = session(mapOf(planLink to page(pi3Rows, pi3Lines), longLink to page(longRows, longLines)))
        val out = runBlocking { reader { logs += it }.fetch(SyncRange(LocalDate.of(2026, 8, 3), T.today), page, mayNavigate = true) } as FetchOutcome.Fetched
        val plans = out.batch.installmentPlans
        assertEquals(2, plans.size)
        assertEquals(listOf("https://www.paypal.com$planLink", "https://www.paypal.com$longLink"), page.loads.filter { "/details/" in it })
        val pi3 = plans.single { it.count == 3 }
        assertEquals(30_000L, pi3.totalCents)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.PAID, InstallmentState.DUE), pi3.installments.map { it.state })
        assertEquals("Elettronica Sintetica", pi3.merchant)                         // the row keeps what only it knows
        assertTrue(pi3.evidence.any { it.ref.startsWith("paypal:web-") } && pi3.evidence.any { it.ref.startsWith(PLAN_PAGE_REF) })
        assertEquals(planLink, PlanPageLink.decode(pi3.planPageToken!!))
        val long = plans.single { it.count == 12 }
        assertEquals(120_000L, long.totalCents)
        assertEquals(2, long.installments.size)
        assertTrue(logs.any { it.startsWith("paypal plans: targets=2 read=2 failed=0 paid=3 due=2") })
        assertTrue(logs.none { it.contains("Elettronica") || it.contains("300") })    // counts only
        assertEquals(1, out.batch.movements.size)                                     // the activity rows are unchanged
    }

    @Test fun aPageThatFailsOrIsNotRecognizedLeavesThePlanAsTheRowMadeIt() {
        val logs = ArrayList<String>()
        // The first plan page never loads; the second loads but is not the plan page.
        val page = session(mapOf(longLink to page(listOf("Altro 1,00 €"), listOf("Una pagina", "qualunque", "cosa"))), onLoad = { if (it.endsWith(planLink)) null else it })
        val out = runBlocking { reader { logs += it }.fetch(SyncRange(LocalDate.of(2026, 8, 3), T.today), page, mayNavigate = true) } as FetchOutcome.Fetched
        val plans = out.batch.installmentPlans
        assertEquals(2, plans.size)
        assertTrue(plans.all { it.installments.isEmpty() && !it.fromPlanPage })        // nothing wiped, nothing claimed
        assertEquals(setOf<Int?>(3, null), plans.map { it.count }.toSet())
        assertTrue(out.batch.warnings.any { it.contains("2 pagine di piani") })
        assertTrue(logs.any { it.contains("targets=2 read=0 failed=2") })
        assertEquals(1, out.batch.movements.size)
        assertNotNull(out.batch.coverageTo)                                            // the activity coverage is untouched
    }

    @Test fun aSignInPageOnAPlanPageStopsThePlansButKeepsTheActivityRead() {
        val page = session(emptyMap(), onLoad = { if ("/details/" in it) "https://www.paypal.com/signin?returnUri=x" else it })
        val out = runBlocking { reader().fetch(SyncRange(LocalDate.of(2026, 8, 3), T.today), page, mayNavigate = true) }
        assertTrue(out is FetchOutcome.Fetched)
        assertEquals(1, page.loads.count { "/details/" in it })                        // the second plan page is not even tried
        assertEquals(2, (out as FetchOutcome.Fetched).batch.installmentPlans.size)
        assertTrue(out.batch.installmentPlans.all { it.installments.isEmpty() })
    }

    @Test fun plansAlreadyInTheArchiveAreReadAgainFromTheirStoredLink() {
        val none = list(row("Questa settimana", "Negozio Sintetico", "−12,34 €", "29 ott · Pagamento"))
        val ref = PlanRef("gmail:known-plan", longLink)
        val page = session(mapOf(longLink to page(longRows, longLines)), activityJson = none)
        val out = runBlocking { reader().fetch(SyncRange(LocalDate.of(2026, 8, 3), T.today, plans = listOf(ref)), page, mayNavigate = true) } as FetchOutcome.Fetched
        val plan = out.batch.installmentPlans.single()
        assertEquals("gmail:known-plan", plan.id)                                      // merged into the existing plan by id
        assertEquals(12, plan.count)
        assertEquals(1, page.loads.count { "/details/" in it })
    }

    @Test fun onlyPayPalPagesAndNeverAnActionLinkAreOpened() {
        val reader = PaypalPlansReader(PaypalPlanSpec(), ProviderSites.PAYPAL)
        assertEquals("https://www.paypal.com/myaccount/activities/details/X1", reader.absolute("/myaccount/activities/details/X1"))
        assertEquals("https://www.paypal.com/a/b", reader.absolute("https://www.paypal.com/a/b"))
        assertNull(reader.absolute("https://evil.example/a"))
        assertNull(reader.absolute("//evil.example/a"))
        assertNull(reader.absolute("javascript:alert(1)"))
        assertNull(reader.absolute("/myaccount/paylater/plan/X1/cancel"))
        assertNull(reader.absolute("/pay-later/pay-now?id=1"))
        assertNull(reader.absolute("/myaccount/transfer/send"))
    }

    // ---- into the archive ----

    @Test fun readPartsBecomePaidAndDueWithTheirRealDatesInTheArchive() {
        val page = session(mapOf(planLink to page(pi3Rows, pi3Lines), longLink to page(longRows, longLines)))
        val out = runBlocking { reader().fetch(SyncRange(LocalDate.of(2026, 8, 3), T.today), page, mayNavigate = true) } as FetchOutcome.Fetched
        val pp = Account("pp", "PayPal sintetico", 2, "fixture")
        val archive = Archive(accounts = listOf(pp)).withImportBatch(out.batch, pp.id, "2026-10-31T10:00:00Z").archive
        val pi3 = archive.installments.single { it.count == 3 }
        assertEquals(2, pi3.paidCount())
        assertEquals(LocalDate.of(2026, 11, 2), pi3.open.single().dueDate)
        assertEquals(10_000L, pi3.remainingCents)
        val long = archive.installments.single { it.count == 12 }
        assertEquals("1 di 12 pagate", long.progressText())
        assertFalse(archive.installmentCommitment(java.time.YearMonth.of(2026, 11)).parts == 0)
        assertEquals(ImportProvider.PAYPAL, out.batch.provider)
    }

    // ---- the debug log describes an unrecognized page without any value (2026-11-01) ----

    @Test fun theShapeKeepsLabelWordsAndLayoutAndMasksEveryValue() {
        val line = "Rata 2 di 3 · 123,45 € · 12 ott 2026 · Pagata · Negozio Segreto Srl"
        val shaped = PaypalPlanMapper.shape(line)
        assertEquals("rata 9 di 9 · 99,99 € · 99 ott 99 · pagata · aaa aaa aaa", shaped)
        assertFalse(shaped.contains("Negozio") || shaped.contains("123") || shaped.contains("Segreto"))
    }

    @Test fun anUnrecognizedPageLogsItsMaskedLayoutAndTestIdsButNoValue() {
        val unknownRows = listOf("Rata uno · 100,00 € · senza stato")
        val lines = listOf("Il tuo piano Negozio Segreto", "Totale", "300,00 €", "Prossimo pagamento 12 nov")
        val json = JSONObject().put("v", 2).put("ready", true).put("rows", JSONArray(unknownRows)).put("lines", JSONArray(lines)).put("items", 5)
            .put("testids", "installment-row:3,plan-total:1").toString()
        val logs = ArrayList<String>()
        val target = PlanTarget("paypal-web:x", "/myaccount/pay-later/plan/ABC123")
        val page = FakePage().apply { onEvaluate = { evaluated(json) } }
        val reader = PaypalPlansReader(PaypalPlanSpec(waitMs = 60, pollMs = 5, betweenPagesMs = 0), ProviderSites.PAYPAL, { T.fetchedAt }, { T.today }) { logs += it }
        val out = runBlocking { reader.read(page, listOf(target)) }
        assertEquals(1, out.failed)
        assertTrue(logs.any { it.contains("not recognized") && it.contains("rows=1") })
        assertTrue(logs.any { it.contains("testids installment-row:3,plan-total:1") })
        assertTrue(logs.any { it.contains(": L2 99,99 €") })
        assertTrue(logs.any { it.contains(": L3 prossimo pagamento 99 nov") })
        assertTrue(logs.none { it.contains("Segreto") || it.contains("Negozio") || it.contains("300") || it.contains("ABC123") })
    }
}
