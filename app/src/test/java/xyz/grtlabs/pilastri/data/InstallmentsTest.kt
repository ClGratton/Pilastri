package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Synthetic installment plans only. */
class InstallmentsTest {
    private val day = LocalDate.of(2026, 10, 5)
    private val at = "2026-10-05T10:00:00Z"
    private fun ev(ref: String) = Evidence(ref, day, "Ricevuta sintetica")
    private fun plan(parts: List<Installment>, id: String = "paypal-pi3:R1", review: PlanReview = PlanReview.PENDING, count: Int? = 3) = InstallmentPlan(
        id, "PayPal Paga in 3 rate", "Negozio sintetico", 9_999, "EUR", count, parts, day, source = InstallmentSource.PAYPAL,
        evidence = listOf(ev("paypal:T1")), review = review, extractedByAgent = false, updatedAt = at)
    private val three = listOf(
        Installment(1, day, 3_333, InstallmentState.PAID, paidOn = day),
        Installment(2, day.plusMonths(1), 3_333, InstallmentState.DUE),
        Installment(3, day.plusMonths(2), 3_333, InstallmentState.DUE),
    )

    @Test fun newPlansArePendingAndRoundTripInTheArchive() {
        val merged = Archive().withInstallmentPlans(listOf(plan(three, review = PlanReview.CONFIRMED)), at)
        assertEquals(1, merged.added)
        val stored = merged.archive.installments.single()
        assertEquals(PlanReview.PENDING, stored.review)
        assertEquals(6_666, stored.remainingCents)
        assertEquals(merged.archive, ArchiveCodec.decode(ArchiveCodec.encode(merged.archive)))
    }

    @Test fun rescanKeepsReviewAndPaidPartsAndAppendsEvidence() {
        val first = Archive().withInstallmentPlans(listOf(plan(three)), at).archive
        val confirmed = first.withInstallmentPlan(first.installments.single().copy(review = PlanReview.CONFIRMED))
        val rescan = plan(three.map { if (it.seq == 1) it.copy(state = InstallmentState.DUE, paidOn = null) else it }).copy(evidence = listOf(ev("gmail:M2")))
        val again = confirmed.withInstallmentPlans(listOf(rescan), "2026-10-06T10:00:00Z")
        val stored = again.archive.installments.single()
        assertEquals(0, again.added)
        assertEquals(PlanReview.CONFIRMED, stored.review)
        assertEquals(InstallmentState.PAID, stored.installments.first { it.seq == 1 }.state)
        assertEquals(listOf("paypal:T1", "gmail:M2"), stored.evidence.map { it.ref })
    }

    @Test fun ignoredPlanStaysIgnoredAndLeavesTheCommitment() {
        val first = Archive().withInstallmentPlans(listOf(plan(three)), at).archive
        val ignored = first.withInstallmentPlan(first.installments.single().copy(review = PlanReview.IGNORED))
        val again = ignored.withInstallmentPlans(listOf(plan(three)), at).archive
        assertEquals(PlanReview.IGNORED, again.installments.single().review)
        assertEquals(0, again.installmentCommitment(YearMonth.from(day.plusMonths(1))).cents)
    }

    @Test fun paymentMarksTheNumberedPartElseTheMatchingAmount() {
        val archive = Archive().withInstallmentPlans(listOf(plan(three)), at).archive
        val numbered = archive.withInstallmentPaid(InstallmentPaymentSeen("paypal-pi3:R1", 3, day.plusMonths(2), 3_333, ev("gmail:P3")), at)
        assertEquals(InstallmentState.PAID, numbered.installments.single().installments.first { it.seq == 3 }.state)
        val unnumbered = archive.withInstallmentPaid(InstallmentPaymentSeen("paypal-pi3:R1", null, day.plusMonths(1), 3_333, ev("gmail:P2")), at)
        assertEquals(InstallmentState.PAID, unnumbered.installments.single().installments.first { it.seq == 2 }.state)
        assertEquals(archive, archive.withInstallmentPaid(InstallmentPaymentSeen("altro:X", 1, day, 1, ev("gmail:P9")), at))
    }

    @Test fun commitmentCountsDueAmountsAndUnknownParts() {
        val unknown = plan(listOf(Installment(1, LocalDate.of(2026, 11, 20), null, InstallmentState.DUE)), id = "klarna:K1", count = 4)
        val archive = Archive().withInstallmentPlans(listOf(plan(three), unknown), at).archive
        val november = archive.installmentCommitment(YearMonth.of(2026, 11))
        assertEquals(3_333, november.cents)
        assertEquals(2, november.parts)
        assertEquals(1, november.unknown)
        assertEquals(4, archive.installments.first { it.id == "klarna:K1" }.remainingUnknown)
        val dollars = plan(listOf(Installment(1, LocalDate.of(2026, 11, 20), 5_000, InstallmentState.DUE)), id = "klarna:K2", count = 3).copy(currency = "USD", totalCents = 15_000)
        val mixed = archive.withInstallmentPlans(listOf(dollars), at).archive.installmentCommitment(YearMonth.of(2026, 11))
        assertEquals("Other currencies are never added to euro", 3_333L, mixed.cents)
        assertEquals(2, mixed.unknown)
    }

    @Test fun briefingShowsPartsDueThisWeekAndPlansToConfirm() {
        val soon = plan(listOf(Installment(1, LocalDate.of(2026, 11, 3), 2_500, InstallmentState.DUE)), id = "scalapay:S1", count = 3)
        val items = Archive().withInstallmentPlans(listOf(soon), at).archive.briefing(LocalDate.of(2026, 10, 31))
        assertEquals(listOf(BriefingKind.INSTALLMENT_DUE, BriefingKind.INSTALLMENT_REVIEW), items.map { it.kind })
        assertEquals(2_500L, items.first().cents)
    }

    @Test fun importBatchCarriesPlansIntoTheSameSave() {
        val paypal = Account("pp", "PayPal", 1, "fixture")
        val batch = ImportBatch(ImportProvider.PAYPAL, "PayPal", Instant.parse(at), day, day,
            listOf(ImportedMovement("T1", day, -3_333, "EUR", "Negozio sintetico", ExternalKind.CARD_PAYMENT)), installmentPlans = listOf(plan(three)))
        val outcome = Archive(accounts = listOf(paypal)).withImportBatch(batch, paypal.id, at)
        assertEquals(1, outcome.added)
        assertEquals(1, outcome.plansAdded)
        assertTrue(outcome.archive.installments.single().active)
    }

    @Test fun gmailAndPaypalViewsOfOnePurchaseBecomeOnePlan() {
        val fromPaypal = Archive().withInstallmentPlans(listOf(plan(three)), at).archive
        val mail = plan(listOf(Installment(2, day.plusMonths(1), 3_333, InstallmentState.DUE)), id = "paypal:ordine-77").copy(
            source = InstallmentSource.GMAIL, purchaseDate = day.plusDays(1), merchant = "NEGOZIO SINTETICO", evidence = listOf(ev("gmail:M1")))
        val merged = fromPaypal.withInstallmentPlans(listOf(mail), at)
        assertEquals(0, merged.added)
        val stored = merged.archive.installments.single()
        assertEquals("paypal-pi3:R1", stored.id)
        assertEquals(listOf("paypal:ordine-77"), stored.aliases)
        // A later receipt addressed to the Gmail id still reaches the plan, and a rescan does not duplicate it.
        val paid = merged.archive.withInstallmentPaid(InstallmentPaymentSeen("paypal:ordine-77", 2, day.plusMonths(1), 3_333, ev("gmail:M2")), at)
        assertEquals(InstallmentState.PAID, paid.installments.single().installments.first { it.seq == 2 }.state)
        assertEquals(1, paid.withInstallmentPlans(listOf(mail), at).archive.installments.size)
        assertEquals(paid, ArchiveCodec.decode(ArchiveCodec.encode(paid)))
        // A different total is a different purchase.
        assertEquals(2, fromPaypal.withInstallmentPlans(listOf(mail.copy(totalCents = 12_000)), at).archive.installments.size)
    }

    @Test fun whatAnAiReadNeverChangesAConfirmedPlanWithoutATap() {
        val first = Archive().withInstallmentPlans(listOf(plan(three)), at).archive
        val confirmed = first.withInstallmentPlan(first.installments.single().copy(review = PlanReview.CONFIRMED))
        val aiPaid = plan(three.map { it.copy(state = InstallmentState.PAID, paidOn = day) }).copy(extractedByAgent = true, evidence = listOf(ev("gmail:AI1")))
        assertEquals(confirmed, confirmed.withInstallmentPlans(listOf(aiPaid), at).archive)
        val seen = InstallmentPaymentSeen("paypal-pi3:R1", 2, day.plusMonths(1), 3_333, ev("gmail:AI2"), byAgent = true)
        assertEquals(confirmed, confirmed.withInstallmentPaid(seen, at))
        // The same receipt read by a parser still counts, and an AI fact still fills a plan nobody confirmed yet.
        assertEquals(InstallmentState.PAID, confirmed.withInstallmentPaid(seen.copy(byAgent = false), at).installments.single().installments.first { it.seq == 2 }.state)
        assertEquals(InstallmentState.PAID, first.withInstallmentPaid(seen, at).installments.single().installments.first { it.seq == 2 }.state)
    }

    @Test fun paypalPurchaseFundedByTheBankCountsOnceBeforeReview() {
        val bank = Account("bank", "Conto", 1, "fixture")
        val paypal = Account("pp", "PayPal", 1, "fixture")
        fun b(provider: ImportProvider, vararg m: ImportedMovement) = ImportBatch(provider, provider.label, Instant.parse(at), day, day, m.toList())
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(b(ImportProvider.ISYBANK, ImportedMovement("B1", day, -4_990, "EUR", "PAYPAL *NEGOZIO", ExternalKind.CARD_PAYMENT)), bank.id, at).archive
            .withImportBatch(b(ImportProvider.PAYPAL,
                ImportedMovement("F1", day, 4_990, "EUR", "Trasferimento bancario", ExternalKind.DEPOSIT),
                ImportedMovement("P1", day, -4_990, "EUR", "Negozio sintetico", ExternalKind.CARD_PAYMENT)), paypal.id, at).archive
        assertEquals(mapOf("ISYBANK:B1" to "PAYPAL:F1", "PAYPAL:F1" to "ISYBANK:B1"), archive.transferPairs())
        assertEquals(4_990L, archive.monthSpending(YearMonth.from(day)).spesaCents)
    }

    @Test fun invalidReferencesAndOlderVersionsAreRejected() {
        val bad = plan(three).copy(categoryId = "missing")
        assertThrows(IllegalArgumentException::class.java) { Archive().withInstallmentPlans(listOf(bad), at) }
        assertThrows(IllegalArgumentException::class.java) { Installment(1, day, 0, InstallmentState.DUE) }
        assertThrows(IllegalArgumentException::class.java) { Evidence("gmail:<script>", day, "x") }
        val archive = Archive().withInstallmentPlans(listOf(plan(three)), at).archive
        assertFalse(archive.installments.isEmpty())
        val accountRemoval = Archive(accounts = listOf(Account("pp", "PayPal", 1, "fixture")))
            .withInstallmentPlans(listOf(plan(three).copy(fundingAccountId = "pp")), at).archive
        assertThrows(IllegalArgumentException::class.java) { accountRemoval.withoutAccount("pp") }
    }
}
