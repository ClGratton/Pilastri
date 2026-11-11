package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.Installment
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview
import xyz.grtlabs.pilastri.data.withInstallmentPaid
import xyz.grtlabs.pilastri.data.withInstallmentPlans
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures as F

/**
 * Scan windows are read independently, so a mail is often about a plan an earlier window found: the host hands those plans over as `known`.
 * Synthetic data only.
 */
class PlanAssemblerKnownTest {
    private val at = "2026-12-11T09:00:00Z"

    private fun facts(vararg ms: MailMessage) = ms.flatMap { (InstallmentMailParser.parse(it) as? Parsed.Facts)?.facts.orEmpty() }
    private fun build(known: List<InstallmentPlan>, vararg ms: MailMessage) = PlanAssembler.build(facts(*ms), at, known)
    private fun d(s: String) = LocalDate.parse(s)
    private fun InstallmentPlan.part(seq: Int) = installments.single { it.seq == seq }

    /** The Klarna plan an earlier window found: part 1 paid at checkout, parts 2 and 3 open. */
    private val earlier: List<InstallmentPlan> = PlanAssembler.build(facts(F.klarnaPlan), "2026-11-10T09:00:00Z").plans

    private fun plan(
        id: String, parts: List<Installment>, merchant: String? = "Shop Esempio", count: Int? = parts.size, aliases: List<String> = emptyList(),
        source: InstallmentSource = InstallmentSource.GMAIL, review: PlanReview = PlanReview.CONFIRMED,
    ) = InstallmentPlan(
        id = id, lender = "Klarna", merchant = merchant, totalCents = null, currency = "EUR", count = count, installments = parts, purchaseDate = d("2026-11-05"),
        source = source, evidence = emptyList(), review = review, extractedByAgent = false, updatedAt = "2026-11-10T09:00:00Z", aliases = aliases,
    )

    private fun part(seq: Int, cents: Long?, state: InstallmentState = InstallmentState.DUE, due: String? = null, paidOn: String? = null) =
        Installment(seq, due?.let(::d), cents, state, paidOn?.let(::d))

    /** Part 1 paid, then 20,00 due 5 Dec and 40,00 due 4 Jan: distinct amounts show which open part a notice lands on. */
    private fun distinct(id: String = "klarna:KL-2026-778899") = plan(
        id, listOf(part(1, 3000, InstallmentState.PAID, "2026-11-05", "2026-11-05"), part(2, 2000, due = "2026-12-05"), part(3, 4000, due = "2027-01-04")),
    )

    private fun paidNotice(id: String, cents: String, date: String = "2026-12-20", ref: String = "Riferimento ordine: KL-2026-778899") =
        F.msg(id, F.KLARNA, "Pagamento ricevuto", date, "Abbiamo ricevuto il tuo pagamento di $cents €.\n$ref")

    // ---- (a) cancellation -------------------------------------------------------------------------------------------------

    @Test fun aCancellationMailForAKnownPlanReturnsThePlanWithItsOpenPartsCancelled() {
        val out = build(earlier, F.klarnaCancelled)
        val p = out.plans.single()
        assertEquals("klarna:KL-2026-778899", p.id)
        assertEquals(InstallmentState.PAID, p.part(1).state)
        assertEquals(InstallmentState.CANCELLED, p.part(2).state)
        assertEquals(InstallmentState.CANCELLED, p.part(3).state)
        assertEquals(0, out.unmatched)
        assertEquals("only the new evidence travels", listOf("gmail:k4"), p.evidence.map { it.ref })
        // the archive's own merge applies it, and keeps what the user decided
        val archive = Archive(installments = earlier.map { it.copy(review = PlanReview.CONFIRMED) })
        val merged = archive.withInstallmentPlans(out.plans, at)
        assertEquals(1, merged.updated)
        val stored = merged.archive.installments.single()
        assertEquals(PlanReview.CONFIRMED, stored.review)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.CANCELLED, InstallmentState.CANCELLED), stored.installments.map { it.state })
        assertFalse(stored.active)
    }

    @Test fun aCancellationFindsTheKnownPlanThroughAnAliasAndThePayPalSpellingOfItsId() {
        val aliased = distinct(id = "manual:rate-shop").copy(aliases = listOf("klarna:KL-2026-778899"))
        val viaAlias = build(listOf(aliased), F.klarnaCancelled).plans.single()
        assertEquals("the plan keeps its own id", "manual:rate-shop", viaAlias.id)
        assertEquals(InstallmentState.CANCELLED, viaAlias.part(2).state)
        assertTrue("the alias is already stored: nothing to add", viaAlias.aliases.isEmpty())

        val paypalKnown = plan("paypal-pi3:7AB12345CD678901E", listOf(part(1, 3334, InstallmentState.PAID, "2026-11-05", "2026-11-05"), part(2, 3333, due = "2026-12-05"), part(3, 3333, due = "2027-01-05")),
            merchant = "Negozio Esempio", count = 3, source = InstallmentSource.PAYPAL)
        val cancelled = F.msg("p5", F.PAYPAL, "Il tuo acquisto Paga in 3 rate è stato annullato", "2026-12-08", "Abbiamo annullato il tuo ordine. ID transazione: 7AB12345CD678901E")
        val out = build(listOf(paypalKnown), cancelled)
        val p = out.plans.single()
        assertEquals("paypal-pi3:7AB12345CD678901E", p.id)
        assertEquals(listOf("paypal:7AB12345CD678901E"), p.aliases)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.CANCELLED, InstallmentState.CANCELLED), p.installments.map { it.state })
        val merged = Archive(installments = listOf(paypalKnown)).withInstallmentPlans(out.plans, at)
        assertEquals(listOf("paypal:7AB12345CD678901E"), merged.archive.installments.single().aliases)
        assertEquals(InstallmentState.CANCELLED, merged.archive.installments.single().part(3).state)
    }

    @Test fun aCancellationThatNamesOnlyAMerchantNeverClosesAKnownPlan() {
        val noOrder = F.msg("k8", F.KLARNA, "Il tuo ordine da Shop Esempio è stato annullato", "2026-12-10", "Abbiamo annullato il tuo ordine.")
        val out = build(earlier, noOrder)
        assertTrue("it could be another order of the same shop", out.plans.isEmpty())
        assertEquals(1, out.unmatched)
    }

    @Test fun aCancellationOfAnUnknownPlanStillRecordsNothing() {
        val out = build(emptyList(), F.klarnaCancelled)
        assertTrue(out.plans.isEmpty())
        assertEquals(0, out.unmatched)
    }

    // ---- (b) paid notice without a part number ----------------------------------------------------------------------------

    @Test fun anUnnumberedPaidNoticeLandsOnTheKnownPlansOpenPartWithTheSameAmount() {
        val out = build(listOf(distinct()), paidNotice("k7", "40,00"))
        val p = out.plans.single()
        assertEquals("klarna:KL-2026-778899", p.id)
        assertEquals("the amount decides, not the earliest part", InstallmentState.PAID, p.part(3).state)
        assertEquals(d("2026-12-20"), p.part(3).paidOn)
        assertEquals(InstallmentState.DUE, p.part(2).state)
        assertEquals(listOf(3), out.payments.map { it.seq })
        assertEquals(4000L, out.payments.single().cents)
        assertEquals(0, out.unmatched)
    }

    @Test fun anUnnumberedPaidNoticeWithAnotherAmountTakesTheEarliestOpenPartNotPartOne() {
        val out = build(listOf(distinct()), paidNotice("k7", "25,00"))
        val p = out.plans.single()
        assertEquals(InstallmentState.PAID, p.part(1).state)
        assertEquals(d("2026-11-05"), p.part(1).paidOn)
        assertEquals(InstallmentState.PAID, p.part(2).state)
        assertEquals(d("2026-12-20"), p.part(2).paidOn)
        assertEquals(InstallmentState.DUE, p.part(3).state)
        assertEquals("no part 1 was invented or overwritten", listOf(2), out.payments.map { it.seq })
        // end to end through the host's two calls
        val archive = Archive(installments = listOf(distinct()))
        val applied = out.payments.fold(archive.withInstallmentPlans(out.plans, at).archive) { a, seen -> a.withInstallmentPaid(seen, at) }
        val stored = applied.installments.single()
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.PAID, InstallmentState.DUE), stored.installments.map { it.state })
        assertEquals(d("2026-11-05"), stored.part(1).paidOn)
    }

    @Test fun aPaidNoticeWithNoOpenPartLeftIsUnmatchedAndTheKnownPlanIsLeftAlone() {
        val allPaid = distinct().copy(installments = listOf(
            part(1, 3000, InstallmentState.PAID, "2026-11-05", "2026-11-05"), part(2, 2000, InstallmentState.PAID, "2026-12-05", "2026-12-05"),
            part(3, 4000, InstallmentState.PAID, "2027-01-04", "2027-01-04"),
        ))
        val out = build(listOf(allPaid), paidNotice("k7", "20,00"))
        assertTrue(out.plans.isEmpty())
        assertTrue(out.payments.isEmpty())
        assertEquals(1, out.unmatched)
    }

    // ---- (c) numberless mails never make stubs -----------------------------------------------------------------------------

    @Test fun aNumberlessPaidNoticeThatMatchesNothingStaysUnmatchedWithoutAStub() {
        val noKnown = build(emptyList(), paidNotice("k7", "33,30"))
        assertTrue("no 'part 1' of an empty plan", noKnown.plans.isEmpty())
        assertTrue(noKnown.payments.isEmpty())
        assertEquals(1, noKnown.unmatched)

        val otherOrder = build(listOf(distinct()), paidNotice("k7", "33,30", ref = "Riferimento ordine: KL-2026-000111"))
        assertTrue("another order id is another plan, not this one", otherOrder.plans.isEmpty())
        assertEquals(1, otherOrder.unmatched)
    }

    @Test fun aNumberlessReminderThatMatchesNothingStaysUnmatchedWithoutAStub() {
        val withOrder = build(emptyList(), F.klarnaReminder)
        assertTrue(withOrder.plans.isEmpty()); assertEquals(1, withOrder.unmatched)
        val anonymous = F.msg("x4", F.KLARNA, "Promemoria di pagamento", "2026-12-03", "Il pagamento di 33,30 € verrà addebitato il 5 dic 2026.")
        val out = build(emptyList(), anonymous)
        assertTrue("it used to create a stub plan", out.plans.isEmpty()); assertEquals(1, out.unmatched)
    }

    @Test fun aNumberlessReminderForAKnownPlanAttachesToItAndCreatesNoStub() {
        val byOrder = build(earlier, F.klarnaReminder)
        assertEquals(listOf("klarna:KL-2026-778899"), byOrder.plans.map { it.id })
        assertEquals(0, byOrder.unmatched)
        val byMerchant = F.msg("x3", F.KLARNA, "Promemoria di pagamento da Shop Esempio", "2026-12-03", "Il pagamento di 33,30 € verrà addebitato il 5 dic 2026. Acquisto da Shop Esempio.")
        val out = build(earlier, byMerchant)
        assertEquals("matched by merchant, no stub next to the known plan", listOf("klarna:KL-2026-778899"), out.plans.map { it.id })
        assertEquals(d("2026-12-05"), out.plans.single().part(2).dueDate)
    }

    @Test fun aNumberedReminderStillMakesAStubWhenItsPlanIsOutsideEveryWindow() {
        val stub = build(emptyList(), F.paypalReminder).plans.single()
        assertEquals("paypal:7AB12345CD678901E", stub.id)
        assertEquals(listOf(2), stub.installments.map { it.seq })
    }

    // ---- known plans and rescans --------------------------------------------------------------------------------------------

    @Test fun aMailTheKnownPlanAlreadyRecordedIsNotAppliedAgainSoARescanNeverUndoesACorrection() {
        val first = PlanAssembler.build(facts(F.paypalPlan, F.paypalReminder, F.paypalPaid), at)
        val corrected = first.plans.map { p -> p.copy(installments = p.installments.map { if (it.seq == 2) it.copy(state = InstallmentState.DUE, paidOn = null) else it }) }
        val again = PlanAssembler.build(facts(F.paypalPlan, F.paypalReminder, F.paypalPaid), at, corrected)
        assertTrue("nothing new in these mails", again.plans.isEmpty())
        assertTrue(again.payments.isEmpty())
        assertEquals(0, again.unmatched)
        // a genuinely new mail changes only what it states
        val third = F.msg("p4", F.PAYPAL, "Pagamento ricevuto: rata 3 di 3 – Paga in 3 rate", "2027-01-05", "Hai pagato la rata 3 di 3 di € 33,33 per l'acquisto da Negozio Esempio.\nID transazione: 7AB12345CD678901E")
        val next = PlanAssembler.build(facts(F.paypalPlan, F.paypalReminder, F.paypalPaid, third), at, corrected)
        val p = next.plans.single()
        assertEquals(InstallmentState.PAID, p.part(3).state)
        assertEquals("the user's correction of part 2 stands", InstallmentState.DUE, p.part(2).state)
        assertEquals(listOf(3), next.payments.map { it.seq })
        assertEquals(listOf("gmail:p4"), p.evidence.map { it.ref })
    }

    @Test fun anUnmatchedFactLeavesNoEvidenceOnTheKnownPlanSoALaterScanCanStillUseIt() {
        val allPaid = distinct().copy(installments = listOf(part(1, 3000, InstallmentState.PAID, "2026-11-05", "2026-11-05"), part(2, 2000, InstallmentState.PAID, "2026-12-05", "2026-12-05"),
            part(3, 4000, InstallmentState.PAID, "2027-01-04", "2027-01-04")))
        val out = build(listOf(allPaid), paidNotice("k7", "20,00"))
        assertTrue(out.plans.isEmpty())
        // the same notice read against a plan that does have an open part is applied
        val later = build(listOf(distinct()), paidNotice("k7", "20,00"))
        assertEquals(listOf(2), later.payments.map { it.seq })
    }

    @Test fun withoutKnownPlansTheResultIsUnchanged() {
        val a = PlanAssembler.build(facts(F.paypalPlan, F.paypalReminder, F.paypalPaid), at)
        val b = PlanAssembler.build(facts(F.paypalPlan, F.paypalReminder, F.paypalPaid), at, emptyList())
        assertEquals(a, b)
        assertEquals(3, a.plans.single().evidence.size)
    }

    // ---- through the scanner ---------------------------------------------------------------------------------------------------

    @Test fun theScannerPassesKnownPlansToTheAssembler() {
        val since = LocalDate.of(2026, 10, 1)
        val withKnown = runBlocking { GmailScanner(FakeMailSource.of(F.klarnaCancelled), now = { F.fixedNow }).scan(since, F.today, known = earlier) }
        val p = withKnown.plans.single()
        assertEquals("klarna:KL-2026-778899", p.id)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.CANCELLED, InstallmentState.CANCELLED), p.installments.map { it.state })
        val without = runBlocking { GmailScanner(FakeMailSource.of(F.klarnaCancelled), now = { F.fixedNow }).scan(since, F.today) }
        assertTrue("a cancellation alone, with nothing known, records nothing", without.plans.isEmpty())
        assertTrue(without.sync.lastResult!!, "0 piani" in without.sync.lastResult!!)
    }
}
