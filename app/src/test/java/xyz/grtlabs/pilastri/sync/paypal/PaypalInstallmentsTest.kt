package xyz.grtlabs.pilastri.sync.paypal

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveCodec
import xyz.grtlabs.pilastri.data.ExternalDecision
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview
import xyz.grtlabs.pilastri.data.installmentCommitment
import xyz.grtlabs.pilastri.data.monthSpending
import xyz.grtlabs.pilastri.data.suggestedDecisionFor
import xyz.grtlabs.pilastri.data.transferPairs
import xyz.grtlabs.pilastri.data.withImportBatch
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.Tx
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.id

/**
 * Pay in 3 and bank pairing, on synthetic files. How PayPal's CSV really lays out the parts of a Paga in 3 rate purchase is NOT
 * documented (only that the purchase shows at its full amount and a second item shows the one-third payment of the purchase day),
 * so these tests pin the documented behaviour of the extractor for both plausible layouts, not PayPal's real output.
 */
class PaypalInstallmentsTest {
    private val at = Instant.parse("2026-10-31T10:00:00Z")
    private val purchaseId = id(101)
    private fun parse(vararg rows: Tx) = PaypalParser.parse(PaypalFixtures.it(*rows), "Download.csv", at)
    private fun ImportBatch.byId() = movements.associateBy { it.externalId }

    private fun purchase(date: String = "05/10/2026", gross: String = "-90,00", id: String = purchaseId, type: String = "Pagamento Express Checkout",
                         installments: String = "", currency: String = "EUR") =
        Tx(date, type, gross, id, name = "Negozio Sintetico", status = "Completata", installments = installments, currency = currency)

    private fun part(date: String, gross: String, n: Int, time: String = "10:00:01", type: String = "Pagamento Paga in 3 rate", ref: String = purchaseId) =
        Tx(date, type, gross, id(n), status = "Completata", ref = ref, time = time)

    // ---- layout 1: the parts enter PayPal as funding; the purchase is the spending ------------------------------

    @Test fun purchaseAtFullAmountWithAFundedFirstPartMakesAPendingPlan() {
        val batch = parse(purchase(), part("05/10/2026", "30,00", 102))
        val plan = batch.installmentPlans.single()
        assertEquals("paypal-pi3:$purchaseId", plan.id)
        assertEquals("PayPal Paga in 3 rate", plan.lender)
        assertEquals("Negozio Sintetico", plan.merchant)
        assertEquals(InstallmentSource.PAYPAL, plan.source)
        assertEquals(PlanReview.PENDING, plan.review)
        assertEquals(9_000L, plan.totalCents)
        assertEquals(3, plan.count)
        assertEquals("EUR", plan.currency)
        assertEquals(LocalDate.of(2026, 10, 5), plan.purchaseDate)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.DUE, InstallmentState.DUE), plan.installments.map { it.state })
        assertEquals(listOf(3_000L, 3_000L, 3_000L), plan.installments.map { it.cents })
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5)), plan.installments.map { it.dueDate })
        assertEquals(LocalDate.of(2026, 10, 5), plan.installments[0].paidOn)
        assertEquals(6_000L, plan.remainingCents)
        assertEquals(listOf("paypal:$purchaseId", "paypal:${id(102)}"), plan.evidence.map { it.ref })
        assertEquals(1, batch.installmentPayments.size)
        assertEquals(1, batch.installmentPayments.single().seq)
        // the purchase is the spending, the funded part is a deposit that can pair with the bank debit
        assertEquals(ExternalKind.CARD_PAYMENT, batch.byId().getValue(purchaseId).kind)
        assertEquals(-9_000L, batch.byId().getValue(purchaseId).signedCents)
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(102)).kind)
        assertEquals("Rata 1/3 · Negozio Sintetico", batch.byId().getValue(id(102)).description)
        assertTrue(batch.warnings.any { it.contains("piano a rate") })
    }

    @Test fun laterPartsAreNumberedByMonthAndRepaymentsOutOfThePurchaseAreNotCountedAgain() {
        val batch = parse(
            purchase(),
            part("05/10/2026", "30,00", 102),
            part("05/11/2026", "30,00", 103),
            part("05/12/2026", "30,00", 104),
            // a negative repayment row of the same plan in this layout is the repayment of the financing, not a new purchase
            part("05/11/2026", "-30,00", 105, time = "10:00:02"),
        )
        val plan = batch.installmentPlans.single()
        assertTrue(plan.installments.all { it.state == InstallmentState.PAID })
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5)), plan.installments.map { it.paidOn })
        assertFalse(plan.active)
        assertEquals(listOf(1, 2, 3), batch.installmentPayments.mapNotNull { it.seq }.sorted())
        assertEquals(setOf(purchaseId, id(102), id(103), id(104)), batch.movements.map { it.externalId }.toSet())
        assertTrue(batch.warnings.any { it.contains("già nell'acquisto o nelle rate") })
    }

    // ---- layout 2: each part leaves PayPal; the parts are the spending ------------------------------------------

    @Test fun whenTheFirstPartLeavesTheAccountThePartsAreTheSpendingAndThePurchaseIsNot() {
        val batch = parse(purchase(), part("05/10/2026", "-30,00", 102))
        assertNull("the financed purchase is not cash", batch.byId()[purchaseId])
        val first = batch.byId().getValue(id(102))
        assertEquals(ExternalKind.CARD_PAYMENT, first.kind)
        assertEquals(-3_000L, first.signedCents)
        assertEquals("Rata 1/3 · Negozio Sintetico", first.description)
        assertEquals(InstallmentState.PAID, batch.installmentPlans.single().installments.first().state)
    }

    @Test fun anUnreferencedFirstPartIsFoundByBeingAThirdOfThePurchaseOnItsDay() {
        val batch = parse(
            purchase(type = "Pagamento Paga in 3 rate"),
            Tx("05/10/2026", "Pagamento Paga in 3 rate", "-30,00", id(102), name = "Negozio Sintetico", status = "Completata"),
        )
        val plan = batch.installmentPlans.single()
        assertEquals(purchaseId, plan.id.substringAfter(':'))
        assertEquals(InstallmentState.PAID, plan.installments.first().state)
        assertEquals(setOf(id(102)), batch.movements.map { it.externalId }.toSet())
    }

    // ---- amounts and dates --------------------------------------------------------------------------------------

    @Test fun oddCentsSplitTheRemainderAndSayThatTheRoundingIsAnAssumption() {
        val batch = parse(purchase(gross = "-100,00"), part("05/10/2026", "33,33", 102))
        val plan = batch.installmentPlans.single()
        assertEquals(listOf(3_333L, 3_333L, 3_334L), plan.installments.map { it.cents })
        assertTrue(plan.evidence.any { it.summary.contains("1 centesimo") })
    }

    @Test fun aPurchaseWithoutAnyPartKeepsDatesUnknownAndNeverInventsAPaidPart() {
        val batch = parse(purchase(installments = "3"))
        val plan = batch.installmentPlans.single()
        assertEquals(listOf(InstallmentState.DUE, InstallmentState.DUE, InstallmentState.DUE), plan.installments.map { it.state })
        assertEquals(listOf<LocalDate?>(null, null, null), plan.installments.map { it.dueDate })
        assertEquals(listOf(3_000L, 3_000L, 3_000L), plan.installments.map { it.cents })
        assertTrue(plan.active)
        assertEquals(ExternalKind.CARD_PAYMENT, batch.byId().getValue(purchaseId).kind)
        assertTrue(batch.installmentPayments.isEmpty())
    }

    @Test fun aPlanFromAForeignCurrencyPurchaseKeepsItsTotalUnknown() {
        val batch = parse(purchase(installments = "3", currency = "USD"))
        val plan = batch.installmentPlans.single()
        assertNull(plan.totalCents)
        assertEquals(listOf<Long?>(null, null, null), plan.installments.map { it.cents })
        assertEquals(3, plan.remainingUnknown)
    }

    // ---- parts whose purchase is not in the file ------------------------------------------------------------

    @Test fun numberedPartsOfAnUnseenPurchaseBecomePaymentsAndOthersAreCountedNotInvented() {
        val ref = id(999)
        val batch = parse(
            Tx("05/11/2026", "Pagamento Paga in 3 rate - rata 2 di 3", "30,00", id(201), status = "Completata", ref = ref),
            Tx("05/12/2026", "Pagamento Paga in 3 rate - rata 3 di 3", "-30,00", id(202), status = "Completata", ref = ref),
            Tx("05/12/2026", "Pagamento Paga in 3 rate", "-31,00", id(203), status = "Completata", ref = ref),
        )
        assertTrue(batch.installmentPlans.isEmpty())
        assertEquals(listOf(2, 3), batch.installmentPayments.map { it.seq })
        assertEquals("paypal-pi3:$ref", batch.installmentPayments.first().planId)
        assertEquals("paypal:${id(201)}", batch.installmentPayments.first().evidence.ref)
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(201)).kind)
        assertNull("an outgoing part of an unseen purchase is not spending here", batch.byId()[id(202)])
        assertNull(batch.byId()[id(203)])
        assertTrue(batch.warnings.any { it.contains("non collegate a un acquisto del file") })
        assertTrue(batch.warnings.any { it.contains("non riconosco il numero") })
    }

    // ---- into the archive ---------------------------------------------------------------------------------------

    @Test fun planAndMovementsSaveTogetherAndReimportChangesNothing() {
        val paypal = Account("pp", "PayPal", 1, "fixture")
        val batch = parse(purchase("05/08/2026"), part("05/08/2026", "30,00", 102), part("05/09/2026", "30,00", 103))
        val first = Archive(accounts = listOf(paypal)).withImportBatch(batch, paypal.id, "2026-10-31T10:00:00Z")
        assertEquals(3, first.added)
        assertEquals(1, first.plansAdded)
        val stored = first.archive.installments.single()
        assertEquals(PlanReview.PENDING, stored.review)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.PAID, InstallmentState.DUE), stored.installments.map { it.state })
        assertEquals(LocalDate.of(2026, 10, 5), stored.open.single().dueDate)
        assertEquals(3_000L, first.archive.installmentCommitment(YearMonth.of(2026, 10)).cents)
        assertEquals(first.archive, ArchiveCodec.decode(ArchiveCodec.encode(first.archive)))
        val again = first.archive.withImportBatch(batch, paypal.id, "2026-10-31T10:05:00Z")
        assertEquals(0, again.added)
        assertEquals(3, again.alreadyPresent)
        assertEquals(0, again.plansAdded)
        assertEquals(0, again.plansUpdated)
        assertEquals(first.archive.installments.single().installments, again.archive.installments.single().installments)
    }

    @Test fun aLaterExportCompletesTheSamePlanWithoutDuplicatingIt() {
        val paypal = Account("pp", "PayPal", 1, "fixture")
        val early = Archive(accounts = listOf(paypal)).withImportBatch(parse(purchase("05/08/2026"), part("05/08/2026", "30,00", 102)), paypal.id, "2026-10-31T10:00:00Z").archive
        val later = early.withImportBatch(
            parse(purchase("05/08/2026"), part("05/08/2026", "30,00", 102), part("05/09/2026", "30,00", 103), part("05/10/2026", "30,00", 104)), paypal.id, "2026-10-31T10:05:00Z")
        assertEquals(1, later.archive.installments.size)
        assertEquals(0, later.plansAdded)
        assertTrue(later.archive.installments.single().installments.all { it.state == InstallmentState.PAID })
    }

    @Test fun financedLayoutsCountThePurchaseOnceInMonthSpending() {
        val paypal = Account("pp", "PayPal", 1, "fixture")
        val october = YearMonth.of(2026, 10)
        fun spending(vararg rows: Tx) = Archive(accounts = listOf(paypal)).withImportBatch(parse(*rows), paypal.id, "2026-10-31T10:00:00Z").archive.monthSpending(october).spesaCents
        assertEquals("purchase layout", 9_000L, spending(purchase(), part("05/10/2026", "30,00", 102)))
        assertEquals("parts layout", 3_000L, spending(purchase(), part("05/10/2026", "-30,00", 102)))
    }

    // ---- PayPal as its own account ----------------------------------------------------------------------------------

    @Test fun bankFundingPairsWithTheBankDebitAndIsNotSpendingTwice() {
        val bank = Account("bank", "Conto", 1, "fixture")
        val paypal = Account("pp", "PayPal", 1, "fixture")
        val bankBatch = ImportBatch(ImportProvider.ISYBANK, "isybank", at, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5),
            listOf(ImportedMovement("B1", LocalDate.of(2026, 10, 5), -4_990, "EUR", "PAYPAL *NEGOZIOSINTE", ExternalKind.CARD_PAYMENT)))
        val paypalBatch = parse(
            Tx("05/10/2026", "Deposito da conto bancario", "49,90", id(1), status = "Completata", time = "10:00:00"),
            Tx("05/10/2026", "Pagamento Express Checkout", "-49,90", id(2), name = "Negozio Sintetico", status = "Completata", time = "10:00:01"),
        )
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(bankBatch, bank.id, "2026-10-31T10:00:00Z").archive
            .withImportBatch(paypalBatch, paypal.id, "2026-10-31T10:00:00Z").archive
        val pairs = archive.transferPairs()
        assertEquals(mapOf("ISYBANK:B1" to "PAYPAL:${id(1)}", "PAYPAL:${id(1)}" to "ISYBANK:B1"), pairs)
        val funding = archive.externalMovements.single { it.id == "PAYPAL:${id(1)}" }
        assertEquals(ExternalDecision.AsTransfer("ISYBANK:B1"), archive.suggestedDecisionFor(funding).first)
        assertEquals("the purchase is counted once, on the PayPal account", 4_990L, archive.monthSpending(YearMonth.of(2026, 10)).spesaCents)
        val purchase = archive.externalMovements.single { it.id == "PAYPAL:${id(2)}" }
        assertEquals(ExternalKind.CARD_PAYMENT, purchase.kind)
        assertFalse(archive.transferPairs().containsKey(purchase.id))
    }

    @Test fun anUnpairedDepositNeverSuggestsAContribution() {
        val paypal = Account("pp", "PayPal", 1, "fixture")
        val archive = Archive(accounts = listOf(paypal))
            .withImportBatch(parse(Tx("05/10/2026", "Deposito da conto bancario", "20,00", id(1), status = "Completata")), paypal.id, "2026-10-31T10:00:00Z").archive
        val deposit = archive.externalMovements.single()
        assertNull(archive.suggestedDecisionFor(deposit).first)
    }
}
