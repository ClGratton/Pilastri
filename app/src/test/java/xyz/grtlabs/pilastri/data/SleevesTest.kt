package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.TradeSide
import xyz.grtlabs.pilastri.sync.tr.TrMapper

/** Broker cash and securities as separate accounts (Council 2 AccountSleeve). Synthetic data only. */
class SleevesTest {
    private val day = LocalDate.of(2026, 10, 2)
    private val at = "2026-10-02T09:00:00Z"
    private val isin = "IE00B4L5Y983"
    private val cash = Account("trc", "Trade Republic · liquidità", 2, "fixture")
    private val etf = Account("trs", "Trade Republic · titoli", 4, "fixture")
    private val buy = ImportedMovement("b1", day, -15_000, "EUR", "Piano di accumulo", ExternalKind.BUY, "SAVINGS_PLAN", isin, BigDecimal("1.5"))
    private val batch = ImportBatch(ImportProvider.TRADE_REPUBLIC, "Trade Republic", Instant.parse(at), day, day, listOf(buy),
        balance = ImportedBalance(day, 260_000), cashBalance = ImportedBalance(day, 60_000), securitiesBalance = ImportedBalance(day, 200_000),
        positions = listOf(ImportedPosition(isin, "ETF sintetico", BigDecimal("20"), 10_000)))

    @Test fun sleevesGoToTheirOwnAccountsAndAreRemembered() {
        val first = Archive(accounts = listOf(cash, etf)).withImportBatch(batch, cash.id, at, securitiesAccountId = etf.id).archive
        assertEquals(mapOf(cash.id to 60_000L, etf.id to 200_000L), first.accountValuations.associate { it.accountId to it.amount.cents })
        assertEquals(etf.id, first.holdings.single().accountId)
        assertEquals(etf.id, first.connections.single().securitiesAccountId)
        assertEquals(first, ArchiveCodec.decode(ArchiveCodec.encode(first)))
        // A later sync without the parameter keeps the split.
        val later = batch.copy(fetchedAt = Instant.parse("2026-10-03T09:00:00Z"), cashBalance = ImportedBalance(day.plusDays(1), 55_000),
            securitiesBalance = ImportedBalance(day.plusDays(1), 205_000), balance = ImportedBalance(day.plusDays(1), 260_000))
        val second = first.withImportBatch(later, cash.id, "2026-10-03T09:00:00Z").archive
        assertEquals(205_000L, second.accountValuations.single { it.accountId == etf.id && it.date == day.plusDays(1) }.amount.cents)
        assertThrows(IllegalArgumentException::class.java) { second.withoutAccount(etf.id) }
    }

    @Test fun reviewedPurchaseIsBookedOnTheSecuritiesAccountAndCountsAsMovedIntoTheLongTerm() {
        val imported = Archive(accounts = listOf(cash, etf)).withImportBatch(batch, cash.id, at, securitiesAccountId = etf.id).archive
        val reviewed = imported.withExternalDecision("TRADE_REPUBLIC:b1", ExternalDecision.AsTrade(TradeSide.BUY, "ETF sintetico"))
        assertEquals(etf.id, reviewed.trades.single().accountId)
        val inflow = reviewed.versatoInto(4).single()
        assertEquals(15_000L, inflow.cents)
        assertEquals(2, inflow.fromPillar)
        assertEquals("A move from P2 is not new money", emptyList<Inflow>(), reviewed.versatoTotal())
    }

    @Test fun withoutASecuritiesAccountTheWholeBalanceStaysTogether() {
        val single = Archive(accounts = listOf(cash)).withImportBatch(batch, cash.id, at).archive
        assertEquals(260_000L, single.accountValuations.single().amount.cents)
        assertNull(single.connections.single().securitiesAccountId)
    }

    @Test fun coverageGrowsWhenWindowsTouchAndNeverMovesBack() {
        val d = { m: Int, day: Int -> LocalDate.of(2026, m, day) }
        assertEquals(d(9, 1) to d(10, 30), mergeCoverage(d(10, 1), d(10, 30), d(9, 1), d(9, 30)))
        assertEquals(d(10, 1) to d(10, 30), mergeCoverage(d(10, 1), d(10, 30), d(8, 1), d(8, 20)))
        assertEquals(d(11, 5) to d(11, 20), mergeCoverage(d(10, 1), d(10, 30), d(11, 5), d(11, 20)))
        assertEquals(d(10, 1) to d(10, 30), mergeCoverage(null, null, d(10, 1), d(10, 30)))
    }

    @Test fun trCashIsKeptWhenAQuoteIsMissing() {
        val positions = listOf(TrMapper.TrPosition(isin, "ETF", BigDecimal("2")))
        val v = TrMapper.valuation(positions, listOf(TrMapper.TrCash("EUR", BigDecimal("12.34"))), emptyMap(), day)
        assertNull(v.balance)
        assertNull(v.securities)
        assertEquals(1_234L, v.cash?.cents)
        val priced = TrMapper.valuation(positions, listOf(TrMapper.TrCash("EUR", BigDecimal("12.34"))), mapOf(isin to BigDecimal("100")), day)
        assertEquals(21_234L, priced.balance?.cents)
        assertEquals(20_000L, priced.securities?.cents)
        assertEquals(10_000L, priced.positions.single().priceCents)
    }
}
