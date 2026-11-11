package xyz.grtlabs.pilastri.ui

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedBalance
import xyz.grtlabs.pilastri.data.ImportedMovement

/** Where an automatic sync saves, without a preview ([applySyncedBatch]). Synthetic data only. */
class AutoSyncHostTest {
    private val day = LocalDate.of(2026, 3, 2)
    private val at = "2026-03-03T10:00:00Z"

    private fun batch(p: ImportProvider, vararg ids: String, balance: ImportedBalance? = null, cash: ImportedBalance? = null, securities: ImportedBalance? = null) = ImportBatch(
        p, p.label, Instant.parse("2026-03-03T09:00:00Z"), day.minusDays(10), day,
        ids.map { ImportedMovement(it, day, -1_500, "EUR", "Acquisto sintetico $it", ExternalKind.CARD_PAYMENT) },
        balance = balance, cashBalance = cash, securitiesBalance = securities,
    )

    @Test fun firstPaypalSyncCreatesItsLiquidityAccountAndLeavesMovementsToReview() {
        val out = applySyncedBatch(Archive(), batch(ImportProvider.PAYPAL, "p1", "p2"), at)
        val account = out.archive.accounts.single()
        assertEquals("PayPal", account.name)
        assertEquals(1, account.pillar)
        assertEquals(2, out.added)
        assertTrue(out.archive.externalMovements.all { it.status == ExternalStatus.PENDING && it.accountId == account.id })
        assertEquals(account.id, out.archive.connections.single().accountId)
        assertEquals(day, out.archive.connections.single().coverageTo)
    }

    @Test fun laterSyncsUseTheConnectedAccountAndSkipDuplicates() {
        val first = applySyncedBatch(Archive(), batch(ImportProvider.ISYBANK, "i1"), at).archive
        val again = applySyncedBatch(first, batch(ImportProvider.ISYBANK, "i1", "i2"), "2026-03-04T10:00:00Z")
        assertEquals(1, again.archive.accounts.size)
        assertEquals(1, again.added)
        assertEquals(1, again.alreadyPresent)
    }

    @Test fun anAccountThePersonMadeIsReusedSoItsBalanceIsNotCountedTwice() {
        val own = Account("a:isy", "Conto isybank", 2, "manuale")
        val out = applySyncedBatch(Archive(accounts = listOf(own)), batch(ImportProvider.ISYBANK, "i1", balance = ImportedBalance(day, 120_000)), at)
        assertEquals(listOf(own), out.archive.accounts)
        assertEquals(own.id, out.archive.accountValuations.single().accountId)
    }

    @Test fun paypalNeverLandsOutsideLiquidity() {
        val elsewhere = Account("a:pp", "PayPal", 3, "manuale")
        val out = applySyncedBatch(Archive(accounts = listOf(elsewhere)), batch(ImportProvider.PAYPAL, "p1"), at)
        val target = out.archive.accounts.single { it.id == out.archive.connections.single().accountId }
        assertEquals(1, target.pillar)
        assertEquals(2, out.archive.accounts.size)
    }

    @Test fun twoAccountsContainingTheNameAreAmbiguousSoANewOneIsMade() {
        val a = Account("a:pp1", "PayPal personale", 1, "manuale")
        val b = Account("a:pp2", "PayPal condiviso", 1, "manuale")
        val out = applySyncedBatch(Archive(accounts = listOf(a, b)), batch(ImportProvider.PAYPAL, "p1"), at)
        assertEquals(3, out.archive.accounts.size)
        assertEquals("PayPal", out.archive.accounts.single { it.id == out.archive.connections.single().accountId }.name)
    }

    @Test fun firstTradeRepublicSyncSplitsCashAndSecurities() {
        val out = applySyncedBatch(Archive(), batch(ImportProvider.TRADE_REPUBLIC, "t1", cash = ImportedBalance(day, 50_000), securities = ImportedBalance(day, 200_000)), at)
        val cash = out.archive.accounts.single { it.name == TR_CASH_ACCOUNT }
        val securities = out.archive.accounts.single { it.name == TR_SECURITIES_ACCOUNT }
        assertEquals(2, cash.pillar)
        assertEquals(4, securities.pillar)
        val connection = out.archive.connections.single()
        assertEquals(cash.id, connection.accountId)
        assertEquals(securities.id, connection.securitiesAccountId)
        assertEquals(50_000L, out.archive.accountValuations.single { it.accountId == cash.id }.amount.cents)
        assertEquals(200_000L, out.archive.accountValuations.single { it.accountId == securities.id }.amount.cents)
    }

    @Test fun oneExistingTradeRepublicAccountHoldsEverything() {
        val own = Account("a:tr", "Trade Republic", 4, "manuale")
        val out = applySyncedBatch(Archive(accounts = listOf(own)), batch(ImportProvider.TRADE_REPUBLIC, "t1", balance = ImportedBalance(day, 250_000)), at)
        assertEquals(listOf(own), out.archive.accounts)
        assertNull(out.archive.connections.single().securitiesAccountId)
        assertEquals(250_000L, out.archive.accountValuations.single().amount.cents)
    }
}
