package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.domain.*

/** The UI read models' money rules (2026-10-31 review), synthetic data only. */
class UiModelsTest {
    private fun ok(text: String): Long = (readEuroInput(text) as EuroInput.Ok).cents
    private fun refused(text: String) = assertTrue("$text must be refused", readEuroInput(text) is EuroInput.Invalid)

    @Test fun euroInputReadsItalianGroupingAndRefusesAmbiguity() {
        assertEquals(150_000L, ok("1.500"))
        assertEquals(150_050L, ok("1.500,50"))
        assertEquals(1_250L, ok("12,5"))
        assertEquals(99L, ok("0,99"))
        assertEquals(123_456_700L, ok("1.234.567"))
        assertEquals(4_500L, ok("45"))
        assertEquals(4_500L, ok("45 €"))
        refused("1.5")
        refused("1.5000")
        refused("12,345")
        refused("1,2,3")
        refused("abc")
        refused("-5")
        refused("")
        assertNull(parseEuroInput("1.5"))
    }

    @Test fun unknownPartsAreNeverAZero() {
        assertEquals("—", atLeastText(0, 2) { "$it" })
        assertEquals("almeno 4500", atLeastText(4500, 1) { "$it" })
        assertEquals("4500", atLeastText(4500, 0) { "$it" })
    }

    private val bank = Account("bank", "Conto", 1, "fixture")
    private val trCash = Account("trc", "Trade Republic · liquidità", 2, "fixture")
    private val trSec = Account("trs", "Trade Republic · titoli", 4, "fixture")
    private val goals = Account("goal", "Salvadanaio", 3, "fixture")

    private fun value(a: Account, d: LocalDate, c: Long) = AccountValuation(a.id, d, Money(c), "fixture", Confidence.DOCUMENT)

    @Test fun monthChangeUsesOnlyRealDatesAndOneCommonWindow() {
        val end = LocalDate.of(2026, 10, 20)
        // Pillar 3 has a value 43 days before its latest one and nothing in the 28–35 day window: no change, no invented date.
        val sparse = Archive(accounts = listOf(goals), accountValuations = listOf(value(goals, end.minusDays(43), 1_000_00), value(goals, end, 1_200_00)))
        assertNull(sparse.pillarMonthChange(3))

        val all = listOf(bank, trCash, trSec, goals)
        val vals = all.flatMap { a -> listOf(value(a, end.minusDays(30), 100_00), value(a, end, 110_00)) }
        val archive = Archive(accounts = all, accountValuations = vals)
        val total = archive.totalMonthChange()!!
        assertEquals(end.minusDays(30), total.from)
        assertEquals(end, total.to)
        assertEquals(4 * 10_00L, total.cents)
    }

    @Test fun purchasePaidFromLiquidityGoesToPillarsAndOthersAreNamedByTheirSource() {
        val m = YearMonth.of(2026, 10)
        val d = LocalDate.of(2026, 10, 3)
        // A buy on the securities account paid from the bank (P1), and one paid from the broker's cash (P2).
        val fromBank = ExternalMovement("ISYBANK:b1", ImportProvider.ISYBANK, bank.id, d, -150_00, "EUR", "PAC", ExternalKind.OTHER, null, null, null, ExternalStatus.APPLIED, "tr1", "x")
        val fromCash = ExternalMovement("TRADE_REPUBLIC:c1", ImportProvider.TRADE_REPUBLIC, trCash.id, d, -80_00, "EUR", "Acquisto", ExternalKind.BUY, null, "IE00B4L5Y983", java.math.BigDecimal.ONE, ExternalStatus.APPLIED, "tr2", "x")
        val instrument = Instrument("i", "ETF", "IE00B4L5Y983", "fixture")
        val trades = listOf(
            Trade("tr1", trSec.id, "i", d, TradeSide.BUY, java.math.BigDecimal.ONE, Money(150_00), Money(0), "fixture"),
            Trade("tr2", trSec.id, "i", d, TradeSide.BUY, java.math.BigDecimal.ONE, Money(80_00), Money(0), "fixture"),
        )
        val archive = Archive(accounts = listOf(bank, trCash, trSec), externalMovements = listOf(fromBank, fromCash), instruments = listOf(instrument), trades = trades)
        val v = archive.monthView(m, LocalDate.of(2026, 10, 31))
        assertEquals(mapOf(4 to 150_00L), v.toPillars.toMap())
        assertEquals(listOf("Lungo termine da Trade Republic · liquidità" to 80_00L), v.extraSources)
    }

    @Test fun coverageStartsWhereTheDataStartsAndPartialMonthsAreIncomplete() {
        val m = YearMonth.of(2026, 10)
        val c = Coverage(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 25))
        assertEquals(5 to 25, c.daysIn(m, LocalDate.of(2026, 10, 31)))
        assertFalse(c.complete(m))
        assertTrue(Coverage(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)).complete(m))
        val two = Archive(accounts = listOf(bank, Account("pp", "PayPal", 1, "fixture")), connections = listOf(
            Connection(ImportProvider.ISYBANK, "bank", null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 28), null),
            Connection(ImportProvider.PAYPAL, "pp", null, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 30), null),
        ))
        assertEquals(Coverage(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 28)), two.liquidityCoverage())
    }
}
