package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.domain.Valuation

class AccountReconciliationTest {
    @Test fun comparesOnlyCompleteSameDateSnapshotsAndRetainsLatestAccountDetails() {
        val date = LocalDate.of(2026, 9, 30)
        val later = LocalDate.of(2026, 10, 15)
        val data = Archive(
            accounts = listOf(account("a", "Alfa"), account("b", "Beta")),
            accountValuations = listOf(
                accountValue("a", date, 4_000, "Estratto A"),
                accountValue("a", later, 4_500, "Estratto A aggiornato"),
                accountValue("b", date, 6_000, "Estratto B"),
            ),
            valuations = listOf(pillarValue(1, date, 10_000)),
        )

        val result = reconcileAccountValuations(data).first()

        val comparison = result.comparison as AccountPillarComparison.Available
        assertEquals(date, comparison.date)
        assertEquals(10_000L, comparison.accountTotalCents)
        assertEquals(10_000L, comparison.pillarTotalCents)
        assertEquals(0L, comparison.differenceCents)
        assertEquals(later, result.accounts.first { it.account.id == "a" }.latest?.date)
        assertEquals("Estratto A aggiornato", result.accounts.first { it.account.id == "a" }.latest?.source)
    }

    @Test fun missingAccountSnapshotOnPillarDateMakesComparisonUnavailable() {
        val date = LocalDate.of(2026, 9, 30)
        val data = Archive(
            accounts = listOf(account("a", "Alfa"), account("b", "Beta")),
            accountValuations = listOf(accountValue("a", date, 4_000, "Estratto A")),
            valuations = listOf(pillarValue(1, date, 4_000)),
        )

        val result = reconcileAccountValuations(data).first()

        val unavailable = result.comparison as AccountPillarComparison.Unavailable
        assertEquals(AccountPillarComparison.Reason.MISSING_SAME_DATE_SNAPSHOT, unavailable.reason)
        assertEquals("b", unavailable.accountsWithoutMatchingDate.single().account.id)
        assertNull(unavailable.accountsWithoutMatchingDate.single().latest)
    }

    @Test fun datedMismatchIsReportedInsteadOfSummed() {
        val pillarDate = LocalDate.of(2026, 9, 30)
        val accountDate = LocalDate.of(2026, 9, 29)
        val data = Archive(
            accounts = listOf(account("a", "Alfa")),
            accountValuations = listOf(accountValue("a", accountDate, 4_000, "Estratto A")),
            valuations = listOf(pillarValue(1, pillarDate, 4_000)),
        )

        val result = reconcileAccountValuations(data).first()

        val unavailable = result.comparison as AccountPillarComparison.Unavailable
        assertEquals(AccountPillarComparison.Reason.MISSING_SAME_DATE_SNAPSHOT, unavailable.reason)
        assertEquals(accountDate, unavailable.accountsWithoutMatchingDate.single().latest?.date)
    }

    @Test fun doesNotCompareWithoutRegisteredAccountsOrExplicitPillarValuation() {
        val noAccounts = reconcileAccountValuations(Archive(valuations = listOf(pillarValue(1, LocalDate.now(), 1_000))))[0]
        assertEquals(AccountPillarComparison.Reason.NO_REGISTERED_ACCOUNTS,
            (noAccounts.comparison as AccountPillarComparison.Unavailable).reason)

        val noPillar = reconcileAccountValuations(Archive(accounts = listOf(account("a", "Alfa"))))[0]
        assertEquals(AccountPillarComparison.Reason.NO_PILLAR_VALUATION,
            (noPillar.comparison as AccountPillarComparison.Unavailable).reason)
    }

    @Test fun summedAccountCentsOverflowMakesComparisonUnavailable() {
        val date = LocalDate.of(2026, 9, 30)
        val data = Archive(
            accounts = listOf(account("a", "Alfa"), account("b", "Beta")),
            accountValuations = listOf(
                accountValue("a", date, Long.MAX_VALUE, "Estratto A"),
                accountValue("b", date, 1, "Estratto B"),
            ),
            valuations = listOf(pillarValue(1, date, Long.MAX_VALUE)),
        )

        val result = reconcileAccountValuations(data).first().comparison

        assertEquals(AccountPillarComparison.Reason.TOTAL_OVERFLOW,
            (result as AccountPillarComparison.Unavailable).reason)
    }

    private fun account(id: String, name: String) = Account(id, name, 1, "synthetic fixture")
    private fun accountValue(id: String, date: LocalDate, cents: Long, source: String) =
        AccountValuation(id, date, Money(cents), source, Confidence.DOCUMENT)
    private fun pillarValue(pillar: Int, date: LocalDate, cents: Long) =
        Valuation(date, pillar, Money(cents), "synthetic pillar fixture", Confidence.DOCUMENT)
}
