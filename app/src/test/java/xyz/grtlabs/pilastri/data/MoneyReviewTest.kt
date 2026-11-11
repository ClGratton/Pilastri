package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.domain.EventType

/** Regressions from the 2026-10-31 money-logic review. Synthetic data only. */
class MoneyReviewTest {
    private val bank = Account("bank", "Conto", 1, "fixture")
    private val trCash = Account("trc", "Trade Republic · liquidità", 2, "fixture")
    private val paypal = Account("pp", "PayPal", 1, "fixture")
    private val oct = LocalDate.of(2026, 10, 10)
    private val at = "2026-10-31T10:00:00Z"
    private fun batch(p: ImportProvider, from: LocalDate, to: LocalDate, vararg m: ImportedMovement) = ImportBatch(p, p.label, Instant.parse(at), from, to, m.toList())

    @Test fun aDepositBeforeTheBankExportStartsStillWaitsForIt() {
        val march = LocalDate.of(2026, 3, 10)
        val archive = Archive(accounts = listOf(bank, trCash))
            .withImportBatch(batch(ImportProvider.ISYBANK, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)), bank.id, at).archive
            .withImportBatch(batch(ImportProvider.TRADE_REPUBLIC, march, march, ImportedMovement("d", march, 10_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT)), trCash.id, at).archive
        val (decision, wait) = archive.suggestedDecisionFor(archive.externalMovements.single { it.provider == ImportProvider.TRADE_REPUBLIC })
        assertNull(decision)
        assertEquals(SuggestionWait.AWAITING_BANK_EXPORT, wait)
    }

    @Test fun moneyEnteringLiquidityAndLeavingItCountsOnce() {
        val p1 = Account("p1", "Liquidità", 1, "fixture"); val p2 = Account("p2", "Riserva", 2, "fixture")
        val archive = Archive(accounts = listOf(p1, p2))
            .withImportBatch(batch(ImportProvider.ISYBANK, oct, oct, ImportedMovement("in", oct, 10_000, "EUR", "Versamento", ExternalKind.DEPOSIT)), p1.id, at).archive
            .withExternalDecision("ISYBANK:in", ExternalDecision.AsEvent(EventType.PERSONAL_CONTRIBUTION))
        assertEquals(emptyList<Inflow>(), archive.versatoTotal())
    }

    @Test fun aBankOutflowAlreadyAppliedStillPairsWithTheLaterDeposit() {
        val reviewed = Archive(accounts = listOf(bank, trCash))
            .withImportBatch(batch(ImportProvider.ISYBANK, oct, oct, ImportedMovement("out", oct, -10_000, "EUR", "Bonifico a Trade Republic", ExternalKind.WITHDRAWAL)), bank.id, at).archive
            .withExternalDecision("ISYBANK:out", ExternalDecision.AsEvent(EventType.WITHDRAWAL))
        val both = reviewed.withImportBatch(batch(ImportProvider.TRADE_REPUBLIC, oct, oct, ImportedMovement("in", oct.plusDays(1), 10_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT)), trCash.id, at).archive
        assertEquals("ISYBANK:out", both.transferPairs()["TRADE_REPUBLIC:in"])
        assertEquals(0L, both.monthSpending(YearMonth.from(oct)).spesaCents)
        assertTrue(both.reviewQueue().any { it.decision is ExternalDecision.AsTransfer })
        val paired = both.withExternalDecision("TRADE_REPUBLIC:in", ExternalDecision.AsTransfer("ISYBANK:out"))
        assertEquals(1, paired.transfers.size)
        assertTrue(paired.events.isEmpty())
        assertEquals(paired, ArchiveCodec.decode(ArchiveCodec.encode(paired)))
    }

    @Test fun aPaymentFromSomeoneElseNeverHidesARealPurchase() {
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(batch(ImportProvider.ISYBANK, oct, oct, ImportedMovement("food", oct, -5_000, "EUR", "Ristorante", ExternalKind.CARD_PAYMENT)), bank.id, at).archive
            .withImportBatch(batch(ImportProvider.PAYPAL, oct, oct, ImportedMovement("friend", oct.plusDays(1), 5_000, "EUR", "Pagamento ricevuto", ExternalKind.OTHER)), paypal.id, at).archive
        assertTrue(archive.transferPairs().isEmpty())
        assertEquals(5_000L, archive.monthSpending(YearMonth.from(oct)).spesaCents)
    }

    @Test fun payoutsToTheOwnBankAreNotSpendingAndRefundsNet() {
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(batch(ImportProvider.PAYPAL, oct, oct,
                ImportedMovement("buy", oct, -10_000, "EUR", "Negozio", ExternalKind.CARD_PAYMENT),
                ImportedMovement("ref", oct.plusDays(5), 4_000, "EUR", "Negozio", ExternalKind.OTHER, rawType = "Rimborso di pagamento"),
                ImportedMovement("out", oct.plusDays(6), -20_000, "EUR", "Trasferimento su conto bancario", ExternalKind.WITHDRAWAL)), paypal.id, at).archive
        val month = archive.monthSpending(YearMonth.from(oct))
        assertEquals(6_000L, month.spesaCents)
        assertEquals(0L, month.entrateCents)
    }

    @Test fun coverageIsTheEarliestEndAndTheLatestStart() {
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(batch(ImportProvider.ISYBANK, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), bank.id, at).archive
            .withImportBatch(batch(ImportProvider.PAYPAL, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 25)), paypal.id, at).archive
        val month = archive.monthSpending(YearMonth.of(2026, 10))
        assertEquals(LocalDate.of(2026, 10, 25), month.coverageTo)
        assertEquals(LocalDate.of(2026, 10, 15), month.coverageFrom)
    }

    @Test fun anEventMustMatchTheSignOfItsMovement() {
        val archive = Archive(accounts = listOf(bank))
            .withImportBatch(batch(ImportProvider.ISYBANK, oct, oct, ImportedMovement("in", oct, 10_000, "EUR", "Bonifico", ExternalKind.DEPOSIT)), bank.id, at).archive
        assertThrows(IllegalArgumentException::class.java) { archive.withExternalDecision("ISYBANK:in", ExternalDecision.AsEvent(EventType.WITHDRAWAL)) }
    }

    @Test fun aHigherPartWithoutACountNeverBreaksTheMerge() {
        val ev = Evidence("gmail:M1", oct, "Ricevuta sintetica")
        fun plan(vararg parts: Installment) = InstallmentPlan("klarna:K9", "Klarna", "Negozio", null, "EUR", null, parts.toList(), oct,
            source = InstallmentSource.GMAIL, evidence = listOf(ev), review = PlanReview.PENDING, extractedByAgent = false, updatedAt = at)
        val first = Archive().withInstallmentPlans(listOf(plan(Installment(1, oct, 2_500, InstallmentState.PAID, oct))), at).archive
        val again = first.withInstallmentPlans(listOf(plan(Installment(3, oct.plusMonths(2), 2_500, InstallmentState.PAID, oct.plusMonths(2)))), at).archive
        assertEquals(3, again.installments.single().count)
        // A receipt without a part number is applied once.
        val seen = InstallmentPaymentSeen("klarna:K9", null, oct.plusMonths(1), 2_500, Evidence("gmail:M2", oct.plusMonths(1), "Rata pagata"))
        val once = again.withInstallmentPaid(seen, at)
        assertEquals(once, once.withInstallmentPaid(seen, at))
    }

    @Test fun aPairTotalCountsTheAmountOnce() {
        val archive = Archive(accounts = listOf(bank, trCash))
            .withImportBatch(batch(ImportProvider.ISYBANK, oct, oct, ImportedMovement("out", oct, -10_000, "EUR", "Bonifico", ExternalKind.WITHDRAWAL)), bank.id, at).archive
            .withImportBatch(batch(ImportProvider.TRADE_REPUBLIC, oct, oct, ImportedMovement("in", oct, 10_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT)), trCash.id, at).archive
        assertEquals(10_000L, archive.reviewQueue().single { it.decision is ExternalDecision.AsTransfer }.totalCents)
    }
}
