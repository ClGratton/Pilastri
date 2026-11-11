package xyz.grtlabs.pilastri.data

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-01 "0 rate pagate" bug: PayPal's activity list shows the purchase as one row (read as a plan with no parts) and every
 * collected part as an outflow to the payee "Paga in 3 rate". Nothing linked them, so every plan showed zero paid parts.
 * Synthetic names, amounts and dates only.
 */
class PaypalInstallmentPaymentsTest {
    private val pp = Account("pp", "PayPal sintetico", 2, "fixture")
    private val buy = LocalDate.of(2026, 8, 3)
    private val at = "2026-10-05T10:00:00Z"

    private fun plan(id: String = "paypal-web:p1", total: Long = 30_000, count: Int? = 3, purchase: LocalDate = buy, review: PlanReview = PlanReview.PENDING) = InstallmentPlan(
        id, "PayPal Paga in 3 rate", "Negozio sintetico", total, "EUR", count, emptyList(), purchase, source = InstallmentSource.PAYPAL,
        evidence = listOf(Evidence("paypal:web-${id.substringAfter(':')}", purchase, "Paga in 3 rate")), review = review, extractedByAgent = false, updatedAt = at)

    private fun part(id: String, date: LocalDate, cents: Long = -10_000, payee: String = "Paga in 3 rate") =
        ImportedMovement("web:$id", date, cents, "EUR", payee, ExternalKind.CARD_PAYMENT, "Pagamento")

    private fun batch(vararg movements: ImportedMovement, provider: ImportProvider = ImportProvider.PAYPAL) =
        ImportBatch(provider, "PayPal", Instant.parse("2026-10-31T10:00:00Z"), buy, LocalDate.of(2026, 10, 31), movements.toList())

    private fun archiveWith(vararg plans: InstallmentPlan) = Archive(accounts = listOf(pp)).withInstallmentPlans(plans.toList(), at).archive
    private fun Archive.sync(vararg movements: ImportedMovement) = withImportBatch(batch(*movements), pp.id, at).archive

    @Test fun collectedPartsPayThePlanAndKeepTheirEvidence() {
        val archive = archiveWith(plan()).sync(part("a", buy), part("b", buy.plusDays(31)), part("c", buy.plusDays(62)))
        val p = archive.installments.single()
        assertEquals(3, p.paidCount())
        assertEquals(listOf(1, 2, 3), p.installments.map { it.seq })
        assertTrue(p.installments.all { it.state == InstallmentState.PAID && it.cents == 10_000L })
        assertEquals(listOf(buy, buy.plusDays(31), buy.plusDays(62)), p.installments.map { it.paidOn })
        assertTrue(p.installments.all { it.movementId != null && archive.externalMovements.any { m -> m.id == it.movementId } })
        assertEquals(0, p.remainingCents)
        assertFalse(p.active)
        assertEquals(4, p.evidence.size)
        assertEquals(PlanReview.PENDING, p.review)
        assertEquals(archive, ArchiveCodec.decode(ArchiveCodec.encode(archive)))
    }

    @Test fun onlyEvidencedPartsArePaidTheRestStayUnknown() {
        val p = archiveWith(plan()).sync(part("a", buy), part("b", buy.plusDays(30))).installments.single()
        assertEquals(2, p.paidCount())
        assertNull(p.installments.firstOrNull { it.seq == 3 })
        assertEquals(1, p.remainingUnknown)
        assertTrue(p.active)
    }

    @Test fun rowsThatDoNotMatchPayNothing() {
        val archive = archiveWith(plan()).sync(
            part("wrongAmount", buy, cents = -9_000),
            part("tooEarly", buy.plusDays(15)),
            part("tooLate", buy.plusDays(45)),
            part("otherPayee", buy, payee = "Negozio sintetico"),
            part("refund", buy.plusDays(31), cents = 10_000),
            part("beforePurchase", buy.minusDays(10)),
        )
        assertEquals(6, archive.externalMovements.size)
        assertEquals(0, archive.installments.single().paidCount())
        assertTrue(archive.installments.single().installments.isEmpty())
    }

    @Test fun theSplitRemainderGoesOnTheFirstPartsAndBothAmountsMatch() {
        // 100.01 euro in three: 33.34, 33.34, 33.33.
        val p = archiveWith(plan(total = 10_001)).sync(part("a", buy, -3_334), part("b", buy.plusDays(31), -3_334), part("c", buy.plusDays(61), -3_333)).installments.single()
        assertEquals(3, p.paidCount())
        assertEquals(listOf(3_334L, 3_334L, 3_333L), p.installments.map { it.cents })
    }

    @Test fun whenTwoAmountsFitTheExactShareOfThePartWins() {
        // Seen on the Fold: next to the part's row sat another Pay in 3 row of the same day, one cent lower, from a plan not in the archive.
        val archive = archiveWith(plan(total = 10_001)).sync(part("a", buy, -3_334), part("lower", buy.plusDays(31), -3_333), part("b", buy.plusDays(31), -3_334))
        val p = archive.installments.single()
        assertEquals(2, p.paidCount())
        assertEquals("PAYPAL:web:b", p.installments.first { it.seq == 2 }.movementId)
        assertTrue(p.installments.none { it.movementId == "PAYPAL:web:lower" })
    }

    @Test fun twoPlansClaimingOneRowAreNotGuessedButTwoRowsPayBoth() {
        val twins = arrayOf(plan("paypal-web:p1"), plan("paypal-web:p2"))
        val one = archiveWith(*twins).sync(part("a", buy))
        assertEquals(listOf(0, 0), one.installments.map { it.paidCount() })
        val two = archiveWith(*twins).sync(part("a", buy), part("b", buy))
        assertEquals(listOf(1, 1), two.installments.sortedBy { it.id }.map { it.paidCount() })
        assertEquals(2, two.installments.mapNotNull { it.installments.single().movementId }.distinct().size)
    }

    @Test fun anExtraIdenticalRowPaysOnlyOnePart() {
        val archive = archiveWith(plan()).sync(part("a", buy), part("b", buy.plusDays(31)), part("c", buy.plusDays(31)))
        val p = archive.installments.single()
        assertEquals(2, p.paidCount())
        val linked = p.installments.mapNotNull { it.movementId }.toSet()
        assertEquals(3, archive.externalMovements.size)
        assertEquals(2, linked.size)
    }

    @Test fun differingCandidatesForOnePartStayUnmatched() {
        val p = archiveWith(plan()).sync(part("a", buy), part("b", buy.plusDays(30)), part("c", buy.plusDays(33))).installments.single()
        assertEquals(listOf(1), p.installments.map { it.seq })
    }

    @Test fun aLaterSyncCompletesAnEarlierPlanAndRepeatingChangesNothing() {
        val first = archiveWith(plan()).sync(part("a", buy))
        assertEquals(1, first.installments.single().paidCount())
        val later = first.sync(part("b", buy.plusDays(31)))
        assertEquals(2, later.installments.single().paidCount())
        val again = later.sync(part("a", buy), part("b", buy.plusDays(31)))
        assertEquals(later.installments, again.installments)
        assertEquals(later.installments, later.withPaypalInstallmentPayments("2026-11-02T10:00:00Z").installments)
    }

    @Test fun confirmedPlansKeepTheirReviewIgnoredPlansAndOtherCountsAreLeftAlone() {
        val confirmed = archiveWith(plan()).let { a -> a.withInstallmentPlan(a.installments.single().copy(review = PlanReview.CONFIRMED)) }.sync(part("a", buy))
        assertEquals(PlanReview.CONFIRMED, confirmed.installments.single().review)
        assertEquals(1, confirmed.installments.single().paidCount())
        val ignored = archiveWith(plan(review = PlanReview.PENDING)).let { a -> a.withInstallmentPlan(a.installments.single().copy(review = PlanReview.IGNORED)) }.sync(part("a", buy))
        assertEquals(0, ignored.installments.single().paidCount())
        val long = archiveWith(plan(count = null)).sync(part("a", buy))
        assertEquals(0, long.installments.single().paidCount())
    }

    @Test fun onlyPayPalImportsRunTheMatchAndItAlsoRepairsAnArchiveSavedBeforeTheFix() {
        // The saved state of the bug: the plan and the collected part are both in the archive, unlinked.
        val saved = Archive(accounts = listOf(pp)).sync(part("a", buy)).withInstallmentPlans(listOf(plan()), at).archive
        assertEquals(0, saved.installments.single().paidCount())
        val afterOtherProvider = saved.withImportBatch(batch(provider = ImportProvider.TRADE_REPUBLIC), pp.id, at).archive
        assertEquals(0, afterOtherProvider.installments.single().paidCount())
        val afterPaypal = saved.withImportBatch(batch(), pp.id, at).archive
        assertEquals(1, afterPaypal.installments.single().paidCount())
    }

    @Test fun partsAlreadyPaidByAnotherSourceAreKept() {
        val withPaid = archiveWith(plan()).let { a ->
            a.withInstallmentPlan(a.installments.single().copy(installments = listOf(Installment(1, buy, 10_000, InstallmentState.PAID, paidOn = buy))))
        }.sync(part("a", buy))
        val kept = withPaid.installments.single().installments.single()
        assertNull(kept.movementId)
        assertEquals(1, withPaid.installments.single().paidCount())
    }
}
