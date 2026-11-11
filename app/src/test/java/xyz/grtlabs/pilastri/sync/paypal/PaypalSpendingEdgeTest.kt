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
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.data.monthSpending
import xyz.grtlabs.pilastri.data.transferPairs
import xyz.grtlabs.pilastri.data.withImportBatch
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.Tx
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.id

/**
 * Two ways a purchase used to vanish from spending in a PayPal export: instalments whose purchase is outside the file, and purchases in a
 * foreign currency whose euro funding still paired with the bank debit. Synthetic rows; PayPal's real layout for either is not documented.
 */
class PaypalSpendingEdgeTest {
    private val at = Instant.parse("2026-10-31T10:00:00Z")
    private fun parse(vararg rows: Tx) = PaypalParser.parse(PaypalFixtures.en(*rows), "Download.csv", at)
    private fun ImportBatch.byId() = movements.associateBy { it.externalId }
    private fun ImportBatch.warned(text: String) = warnings.any { text in it }

    private val paypal = Account("pp", "PayPal", 1, "fixture")
    private val bank = Account("bank", "Conto", 1, "fixture")
    private fun bankDebit(date: LocalDate, cents: Long) = ImportBatch(
        ImportProvider.ISYBANK, "isybank", at, date, date, listOf(ImportedMovement("B1", date, -cents, "EUR", "PAYPAL *SHOPUSA", ExternalKind.CARD_PAYMENT)),
    )

    // ---- orphan instalments --------------------------------------------------------------------------------------------------

    private val outside = id(999)

    private fun orphanPart(date: String, gross: String, n: Int, type: String = "Pagamento Paga in 3 rate") =
        Tx(date, type, gross, id(n), name = "Negozio Sintetico", status = "Completed", ref = outside)

    @Test fun anOutgoingPartOfAPurchaseOutsideTheFileIsTheSpendingWhenNoPartOfThePlanEntersPayPal() {
        val batch = parse(orphanPart("05/09/2026", "-30.00", 201, "Pagamento Paga in 3 rate - rata 2 di 3"), orphanPart("05/10/2026", "-30.00", 202))
        val numbered = batch.byId().getValue(id(201))
        assertEquals(ExternalKind.CARD_PAYMENT, numbered.kind)
        assertEquals(-3_000L, numbered.signedCents)
        assertEquals("Rata 2/3 · Negozio Sintetico", numbered.description)
        val unnumbered = batch.byId().getValue(id(202))
        assertEquals("a part with no readable number is still the spending, under its own description", ExternalKind.CARD_PAYMENT, unnumbered.kind)
        assertEquals("Rata a rate · Negozio Sintetico", unnumbered.description)
        assertEquals("the numbered one is still a payment for the plan the archive may already hold", listOf(2), batch.installmentPayments.map { it.seq })
        assertTrue(batch.installmentPlans.isEmpty())
        assertTrue("the warning stays: the purchase is not in the file", batch.warned("non collegate a un acquisto del file importate come spesa"))
        assertTrue(batch.warned("non riconosco il numero"))
        // and it reaches the month's spending exactly once
        val archive = Archive(accounts = listOf(paypal)).withImportBatch(batch, paypal.id, "2026-10-31T10:00:00Z").archive
        assertEquals(3_000L, archive.monthSpending(YearMonth.of(2026, 9)).spesaCents)
        assertEquals(3_000L, archive.monthSpending(YearMonth.of(2026, 10)).spesaCents)
    }

    @Test fun anOutgoingPartIsStillARepaymentWhenAnotherPartOfTheSamePlanEntersPayPal() {
        val batch = parse(
            orphanPart("05/09/2026", "30.00", 201, "Pagamento Paga in 3 rate - rata 2 di 3"),
            orphanPart("05/09/2026", "-30.00", 202, "Pagamento Paga in 3 rate - rata 2 di 3"),
        )
        assertNull("the purchase was counted when it was made: this is the repayment of the financing", batch.byId()[id(202)])
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(201)).kind)
        assertTrue(batch.warned("non collegata a un acquisto del file"))
        assertFalse(batch.warned("importata come spesa"))
    }

    @Test fun anOrphanInAnotherCurrencyIsNeverImportedAsEuroSpending() {
        val batch = parse(Tx("05/09/2026", "Pagamento Paga in 3 rate - rata 2 di 3", "-30.00", id(201), name = "Shop", status = "Completed", ref = outside, currency = "USD"))
        assertTrue(batch.movements.isEmpty())
    }

    // ---- foreign-currency purchases ------------------------------------------------------------------------------------------

    private fun funding(n: Int, gross: String = "45.00", ref: String = "", date: String = "05/10/2026") =
        Tx(date, "Bank Deposit to PP Account", gross, id(n), status = "Completed", time = "10:00:00", ref = ref)

    private fun usdPurchase(n: Int = 4, date: String = "05/10/2026") =
        Tx(date, "Express Checkout Payment", "-50.00", id(n), name = "Shop USA", status = "Completed", currency = "USD", time = "10:00:01")

    private fun conversionPair(purchase: Int = 4, euroLeg: Int = 2, usdLeg: Int = 3, eur: String = "-45.00", date: String = "05/10/2026") = arrayOf(
        Tx(date, "General Currency Conversion", eur, id(euroLeg), status = "Completed", ref = id(purchase), time = "10:00:01"),
        Tx(date, "General Currency Conversion", "50.00", id(usdLeg), status = "Completed", ref = id(purchase), currency = "USD", time = "10:00:01"),
    )

    @Test fun aForeignPurchaseWithItsConversionPairIsImportedInEuroAtTheEuroLegsAmount() {
        val batch = parse(funding(1), *conversionPair(), usdPurchase())
        val purchase = batch.byId().getValue(id(4))
        assertEquals(ExternalKind.CARD_PAYMENT, purchase.kind)
        assertEquals(-4_500L, purchase.signedCents)
        assertEquals("EUR", purchase.currency)
        assertEquals("the original merchant description", "Shop USA", purchase.description)
        assertEquals(setOf(id(1), id(4)), batch.movements.map { it.externalId }.toSet())
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(1)).kind)
        assertTrue(batch.warned("acquisto in valuta estera importato in euro"))
        assertFalse("nothing is left in a foreign currency", batch.warned("valuta diversa da EUR"))
        assertFalse(batch.warned("conversione di valuta esclusa"))
    }

    @Test fun theConvertedPurchaseCountsOnceAndTheFundingStillPairsWithTheBankDebit() {
        val oct5 = LocalDate.of(2026, 10, 5)
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(bankDebit(oct5, 4_500), bank.id, "2026-10-31T10:00:00Z").archive
            .withImportBatch(parse(funding(1), *conversionPair(), usdPurchase()), paypal.id, "2026-10-31T10:00:00Z").archive
        assertEquals(mapOf("ISYBANK:B1" to "PAYPAL:${id(1)}", "PAYPAL:${id(1)}" to "ISYBANK:B1"), archive.transferPairs())
        assertEquals("the purchase is the spending, once", 4_500L, archive.monthSpending(YearMonth.of(2026, 10)).spesaCents)
    }

    @Test fun aForeignPurchaseWithoutItsConversionExcludesTheMatchingEuroFundingSoNothingPairsAndWarns() {
        val batch = parse(funding(1), usdPurchase())
        assertNull("the euro funding row is excluded", batch.byId()[id(1)])
        assertEquals("the foreign purchase itself stays foreign for the import to count out", "USD", batch.byId().getValue(id(4)).currency)
        assertTrue(batch.warned("senza la conversione di valuta nel file"))
        assertTrue(batch.warned("ricarica in euro corrispondente esclusa"))
        val oct5 = LocalDate.of(2026, 10, 5)
        val archive = Archive(accounts = listOf(bank, paypal))
            .withImportBatch(bankDebit(oct5, 4_500), bank.id, "2026-10-31T10:00:00Z").archive
            .withImportBatch(batch, paypal.id, "2026-10-31T10:00:00Z").archive
        assertTrue("nothing pairs", archive.transferPairs().isEmpty())
        assertEquals("so the bank debit is the spending", 4_500L, archive.monthSpending(YearMonth.of(2026, 10)).spesaCents)
    }

    @Test fun theFundingThatNamesTheForeignPurchaseIsTheOneExcluded() {
        val batch = parse(funding(1, "20.00"), funding(2, "45.00", ref = id(4)), usdPurchase())
        assertNull(batch.byId()[id(2)])
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(1)).kind)
    }

    @Test fun whenTheFundingCannotBeIdentifiedNothingIsExcludedAndTheWarningSaysSo() {
        val batch = parse(funding(1, "20.00"), funding(2, "45.00"), usdPurchase())
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(1)).kind)
        assertEquals(ExternalKind.DEPOSIT, batch.byId().getValue(id(2)).kind)
        assertTrue(batch.warned("Non ho trovato con certezza la ricarica in euro corrispondente"))
    }

    @Test fun aLoneForeignPurchaseStillKeepsItsCurrencyForTheImportToCountOut() {
        val batch = parse(usdPurchase())
        assertEquals("USD", batch.movements.single().currency)
        assertTrue(batch.warned("valuta diversa da EUR"))
        assertTrue(batch.warned("senza la conversione di valuta nel file"))
    }
}
