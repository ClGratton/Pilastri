package xyz.grtlabs.pilastri.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plans read from PayPal's own plan page ("PayPal, pagina del piano") merged into the plans the activity list created: the provider's
 * total, count and parts replace the guesses; paid parts link to their activity outflow only when clearly the same row.
 * Synthetic names, amounts and dates only.
 */
class PaypalPlanPageMergeTest {
    private val pp = Account("pp", "PayPal sintetico", 2, "fixture")
    private val buy = LocalDate.of(2026, 8, 3)
    private val at = "2026-10-05T10:00:00Z"
    private val read = LocalDate.of(2026, 11, 1)

    /** What the activity list leaves: a plan with no parts, the row's amount as total. */
    private fun rowPlan(id: String = "paypal-web:p1", total: Long = 30_000, count: Int? = 3, lender: String = "PayPal Paga in 3 rate") = InstallmentPlan(
        id, lender, "Negozio sintetico", total, "EUR", count, emptyList(), buy, source = InstallmentSource.PAYPAL,
        evidence = listOf(Evidence("paypal:web-${id.substringAfter(':')}", buy, "Paga in 3 rate")), review = PlanReview.PENDING, extractedByAgent = false, updatedAt = at)

    /** What a successful read of the plan page returns. */
    private fun pagePlan(
        id: String = "paypal-web:p1", total: Long? = 30_000, count: Int? = 3, parts: List<Installment>, token: String = "tok1", on: LocalDate = read,
        lender: String = "PayPal Paga in 3 rate",
    ) = InstallmentPlan(
        id, lender, "Negozio sintetico", total, "EUR", count, parts, buy, source = InstallmentSource.PAYPAL,
        evidence = listOf(Evidence("$PLAN_PAGE_REF$token", on, "PayPal, pagina del piano")), review = PlanReview.PENDING, extractedByAgent = false, updatedAt = at)

    private fun paid(seq: Int, due: LocalDate, cents: Long = 10_000, on: LocalDate? = due, movement: String? = null) =
        Installment(seq, due, cents, InstallmentState.PAID, paidOn = on, movementId = movement)
    private fun due(seq: Int, due: LocalDate, cents: Long? = 10_000) = Installment(seq, due, cents, InstallmentState.DUE)

    private fun outflow(id: String, date: LocalDate, cents: Long = -10_000, payee: String = "Paga in 3 rate") =
        ImportedMovement("web:$id", date, cents, "EUR", payee, ExternalKind.CARD_PAYMENT, "Pagamento")

    private fun withRowPlan(vararg plans: InstallmentPlan) = Archive(accounts = listOf(pp)).withInstallmentPlans(plans.toList(), at).archive
    private fun Archive.sync(vararg movements: ImportedMovement, plans: List<InstallmentPlan> = emptyList()) = withImportBatch(
        ImportBatch(ImportProvider.PAYPAL, "PayPal", Instant.parse("2026-11-01T10:00:00Z"), buy, read, movements.toList(), installmentPlans = plans), pp.id, at).archive

    @Test fun thePlanPageGivesTheTotalTheCountAndEveryPart() {
        val parts = listOf(paid(1, buy), due(2, LocalDate.of(2026, 11, 20)), due(3, LocalDate.of(2026, 12, 20)))
        val archive = withRowPlan(rowPlan(total = 30_000)).sync(plans = listOf(pagePlan(total = 30_090, parts = parts)))
        val p = archive.installments.single()
        assertEquals(30_090L, p.totalCents)            // the page's total replaces the activity row's amount
        assertEquals(3, p.count)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.DUE, InstallmentState.DUE), p.installments.map { it.state })
        assertEquals(listOf(buy, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 20)), p.installments.map { it.dueDate })
        assertEquals(1, p.paidCount())
        assertEquals(20_000L, p.remainingCents)
        assertTrue(p.fromPlanPage)
        assertEquals("tok1", p.planPageToken)
        assertEquals("1 di 3 pagate", p.progressText())
        assertEquals(archive, ArchiveCodec.decode(ArchiveCodec.encode(archive)))
    }

    @Test fun theLongPlanGetsItsCountFromThePage() {
        val long = rowPlan(id = "paypal-web:L", total = 120_000, count = null, lender = "PayPal pagamento a rate")
        assertEquals("rate non indicate", long.progressText())
        val parts = listOf(paid(1, buy, 10_000), paid(2, LocalDate.of(2026, 9, 3), 10_000)) + (3..12).map { due(it, buy.plusMonths(it - 1L), 10_000) }
        val archive = withRowPlan(long).sync(plans = listOf(pagePlan(id = "paypal-web:L", total = 120_000, count = 12, parts = parts, token = "tokL", lender = "PayPal pagamento a rate")))
        val p = archive.installments.single()
        assertEquals(12, p.count)
        assertEquals(12, p.installments.size)
        assertEquals("2 di 12 pagate", p.progressText())
        assertEquals(100_000L, p.remainingCents)
    }

    @Test fun anUnknownCountReadsRateNonIndicateNeverZeroOfZero() {
        val p = rowPlan(count = null)
        assertEquals("rate non indicate", p.progressText())
        val seen = p.copy(installments = listOf(paid(1, buy)))
        assertEquals("1 pagate · rate non indicate", seen.progressText())
        assertEquals("0 di 3 pagate", rowPlan(count = 3).progressText())
    }

    @Test fun duePartsEnterTheMonthsCommitmentsWithTheirRealDates() {
        val parts = listOf(paid(1, buy), due(2, LocalDate.of(2026, 11, 20), 10_050), due(3, LocalDate.of(2026, 12, 20), 10_050))
        val archive = withRowPlan(rowPlan()).sync(plans = listOf(pagePlan(parts = parts)))
        val nov = archive.installmentCommitment(YearMonth.of(2026, 11))
        assertEquals(1, nov.parts); assertEquals(10_050L, nov.cents); assertEquals(0, nov.unknown)
        assertEquals(1, archive.installmentCommitment(YearMonth.of(2026, 12)).parts)
        assertEquals(0, archive.installmentCommitment(YearMonth.of(2026, 10)).parts)
    }

    @Test fun aPartAlreadyPaidStaysPaidAndGainsTheDueDateTheProviderStates() {
        // The activity guess paid part 1 from a movement on the day after the purchase; the page states its real due date and shows it unpaid.
        val archive = withRowPlan(rowPlan()).sync(outflow("a", buy.plusDays(1)))
        val guessed = archive.installments.single().installments.single()
        assertEquals(InstallmentState.PAID, guessed.state)
        assertNull(archive.installments.single().planPageToken)
        val next = archive.sync(plans = listOf(pagePlan(parts = listOf(due(1, buy), due(2, LocalDate.of(2026, 11, 20)), due(3, LocalDate.of(2026, 12, 20))))))
        val p = next.installments.single()
        val first = p.installments.first { it.seq == 1 }
        assertEquals(InstallmentState.PAID, first.state)
        assertEquals(guessed.movementId, first.movementId)
        assertEquals(buy, first.dueDate)
        assertEquals(buy.plusDays(1), first.paidOn)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.DUE, InstallmentState.DUE), p.installments.map { it.state })
    }

    @Test fun aNewerReadReplacesThePlanPageEvidenceInsteadOfPilingUp() {
        val a1 = withRowPlan(rowPlan()).sync(plans = listOf(pagePlan(parts = listOf(due(1, buy)), on = LocalDate.of(2026, 10, 20))))
        val a2 = a1.sync(plans = listOf(pagePlan(parts = listOf(paid(1, buy)), on = read)))
        val p = a2.installments.single()
        assertEquals(1, p.evidence.count { it.ref.startsWith(PLAN_PAGE_REF) })
        assertEquals(read, p.evidence.single { it.ref.startsWith(PLAN_PAGE_REF) }.date)
        assertEquals("PayPal, pagina del piano", p.evidence.single { it.ref.startsWith(PLAN_PAGE_REF) }.summary)
        assertEquals(InstallmentState.PAID, p.installments.single().state)
    }

    @Test fun aPlanNotReadThisTimeIsLeftAsItWas() {
        // A failed or unrecognized plan page emits no plan: the batch carries the row-made plan (no parts) and nothing is wiped or downgraded.
        val parts = listOf(paid(1, buy), due(2, LocalDate.of(2026, 11, 20)), due(3, LocalDate.of(2026, 12, 20)))
        val read1 = withRowPlan(rowPlan()).sync(plans = listOf(pagePlan(total = 30_090, parts = parts)))
        val read2 = read1.sync(plans = listOf(rowPlan(total = 30_000)))
        assertEquals(read1.installments.single().installments, read2.installments.single().installments)
        assertEquals(30_090L, read2.installments.single().totalCents)
        assertTrue(read2.installments.single().fromPlanPage)
        assertEquals(3, read2.installments.single().count)
    }

    @Test fun aPaidPartLinksToItsOutflowOnlyWhenOneRowFits() {
        val parts = listOf(paid(1, buy, on = buy), paid(2, LocalDate.of(2026, 9, 3), on = LocalDate.of(2026, 9, 3)), due(3, LocalDate.of(2026, 12, 3)))
        val archive = withRowPlan(rowPlan()).sync(
            outflow("a", buy), outflow("b", LocalDate.of(2026, 9, 4)), outflow("other", LocalDate.of(2026, 9, 3), payee = "Negozio altrui"),
            plans = listOf(pagePlan(parts = parts)))
        val p = archive.installments.single()
        assertTrue(p.installments.first { it.seq == 1 }.movementId!!.endsWith("web:a"))
        assertTrue(p.installments.first { it.seq == 2 }.movementId!!.endsWith("web:b"))   // one day after the page's paid date still fits
        assertNull(p.installments.first { it.seq == 3 }.movementId)
        assertEquals(2, p.paidCount())
        assertTrue(p.evidence.any { it.summary.startsWith("Rata 2 pagata") })
    }

    @Test fun aRowThatTwoPartsFitLinksToNeither() {
        // Two parts of equal amount paid on the same day and only one outflow: the row cannot say which it paid.
        val parts = listOf(paid(1, buy, on = buy), paid(2, buy, on = buy), due(3, LocalDate.of(2026, 12, 3)))
        val p = withRowPlan(rowPlan()).sync(outflow("a", buy), plans = listOf(pagePlan(parts = parts))).installments.single()
        assertEquals(2, p.paidCount())                       // the provider's word stands
        assertTrue(p.installments.all { it.movementId == null })
    }

    @Test fun theActivityGuessDoesNotOverrideAPartThePageDescribes() {
        // The page says part 2 is still to pay; a Pay in 3 outflow of the right amount and date must not mark it paid.
        val parts = listOf(paid(1, buy), due(2, buy.plusDays(31)), due(3, buy.plusDays(61)))
        val archive = withRowPlan(rowPlan()).sync(outflow("a", buy), outflow("late", buy.plusDays(31)), plans = listOf(pagePlan(parts = parts)))
        val p = archive.installments.single()
        assertEquals(InstallmentState.DUE, p.installments.first { it.seq == 2 }.state)
        assertEquals(1, p.paidCount())
    }

    @Test fun anIgnoredPlanStaysIgnoredAfterAPlanRead() {
        val ignored = withRowPlan(rowPlan()).withPlanReview("paypal-web:p1", PlanReview.IGNORED)
        val after = ignored.sync(plans = listOf(pagePlan(parts = listOf(paid(1, buy)))))
        assertEquals(PlanReview.IGNORED, after.installments.single().review)
        assertFalse(after.installments.single().active)
    }

    @Test fun aPlanPageLinkRoundTripsAsAnEvidenceRefAndRejectsAnythingElse() {
        val link = "/myaccount/activities/details/ABC123?x=1&y=2"
        val token = PlanPageLink.encode(link)!!
        assertEquals(link, PlanPageLink.decode(token))
        assertEquals(link, PlanPageLink.decode(PlanPageLink.encode("https://www.paypal.com$link")!!))
        assertTrue(Evidence("$PLAN_PAGE_REF$token", read, "PayPal, pagina del piano").ref.length <= 200)
        assertNull(PlanPageLink.encode("//evil.example/x"))
        assertNull(PlanPageLink.encode("https://evil.example/x"))
        assertNull(PlanPageLink.encode("relative/without/slash"))
        assertNull(PlanPageLink.encode("/a b"))
        assertNull(PlanPageLink.encode("/" + "x".repeat(200)))
        assertNull(PlanPageLink.decode("not base64 !!"))
        assertNull(PlanPageLink.decode(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("//evil".toByteArray())))
    }
}
