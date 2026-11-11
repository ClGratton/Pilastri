package xyz.grtlabs.pilastri.sync.tr

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ISIN_X = "ZZ0000000011"

/** Quotes are euro or nothing, and a portfolio answer this reader does not understand is unknown, not empty. Synthetic answers. */
class TrMapperQuotesTest {
    private val today = LocalDate.of(2026, 10, 31)
    private fun ticker(extra: String = "") = JSONObject("""{"bid":{"price":"1"},"last":{"price":"160.56","size":1}$extra}""")
    private fun json(s: String) = JSONObject(s)

    // ---- the quote's currency --------------------------------------------------------------------------------------------

    @Test fun aTickerThatStatesAnotherCurrencyIsLeftUnpriced() {
        assertEquals("160.56", TrMapper.lastPrice(ticker())!!.toPlainString())
        assertEquals("EUR is accepted in any spelling", "160.56", TrMapper.lastPrice(ticker(",\"currency\":\"eur\""))!!.toPlainString())
        assertNull(TrMapper.lastPrice(ticker(",\"currency\":\"USD\"")))
        assertNull(TrMapper.lastPrice(json("""{"last":{"price":"160.56","currency":"GBP"}}""")))
        assertNull("an object with an id", TrMapper.lastPrice(json("""{"currency":{"id":"CHF"},"last":{"price":"160.56"}}""")))
        assertNull("a currency that cannot be read is never taken for euro", TrMapper.lastPrice(json("""{"currency":5,"last":{"price":"160.56"}}""")))
        assertEquals("€", "160.56", TrMapper.lastPrice(json("""{"currency":"€","last":{"price":"160.56"}}"""))!!.toPlainString())
    }

    @Test fun theInstrumentsCurrenciesDecideWhenTheHostPassesIt() {
        val t = ticker()
        assertEquals("160.56", TrMapper.lastPrice(t, json("""{"currencies":[{"id":"EUR","name":"Euro"}]}"""), "LSX")!!.toPlainString())
        assertNull(TrMapper.lastPrice(t, json("""{"currencies":[{"id":"USD"}]}"""), "LSX"))
        assertNull("two currencies and no exchange entry: not confirmed as euro", TrMapper.lastPrice(t, json("""{"currencies":["EUR","USD"]}"""), "LSX"))
        assertEquals("160.56", TrMapper.lastPrice(t, json("""{"currency":"EUR"}"""), null)!!.toPlainString())
        assertEquals("an instrument that says nothing changes nothing", "160.56", TrMapper.lastPrice(t, json("""{"exchangeIds":["LSX"]}"""), "LSX")!!.toPlainString())
        val perExchange = json("""{"exchangeIds":["NAS","LSX"],"currencies":["EUR","USD"],"exchanges":[{"exchangeId":"NAS","currency":"USD"},{"slug":"LSX","currency":{"id":"EUR"}}]}""")
        assertNull(TrMapper.lastPrice(t, perExchange, "NAS"))
        assertEquals("160.56", TrMapper.lastPrice(t, perExchange, "LSX")!!.toPlainString())
        assertNull("a missing or non positive price stays null", TrMapper.lastPrice(json("""{"last":{"price":"0"}}"""), perExchange, "LSX"))
    }

    @Test fun anExchangeKnownToQuoteInAnotherCurrencyIsPassedOverWhileAnotherIsOffered() {
        val instrument = json("""{"exchangeIds":["NAS","GER","LSX"],"exchanges":[{"exchangeId":"NAS","currency":"USD"},{"exchangeId":"GER","currency":"EUR"},{"exchangeId":"LSX"}]}""")
        assertEquals("the preferred one is not euro", "GER", TrMapper.pickExchange(instrument, "NAS"))
        assertEquals("not offered: the first usable one", "GER", TrMapper.pickExchange(instrument, "TDG"))
        assertEquals("an exchange with no stated currency is still usable", "LSX", TrMapper.pickExchange(instrument, "LSX"))
        val onlyUsd = json("""{"exchangeIds":["NAS"],"exchanges":[{"exchangeId":"NAS","currency":"USD"}]}""")
        assertEquals("nothing better: the choice stands and the price check rejects it", "NAS", TrMapper.pickExchange(onlyUsd, "LSX"))
        assertNull(TrMapper.lastPrice(ticker(), onlyUsd, "NAS"))
    }

    @Test fun anUnpricedPositionNullsTheSecuritiesValueButKeepsTheCash() {
        val positions = listOf(TrMapper.TrPosition(ISIN_X, "X", BigDecimal("2")))
        val cash = listOf(TrMapper.TrCash("EUR", BigDecimal("100.25")))
        val prices = TrMapper.lastPrice(ticker(",\"currency\":\"USD\""))?.let { mapOf(ISIN_X to it) }.orEmpty()
        val v = TrMapper.valuation(positions, cash, prices, today)
        assertNull("never a wrong total", v.balance)
        assertNull(v.securities)
        assertEquals(10_025L, v.cash!!.cents)
        assertTrue(v.warnings.single(), "Prezzo non disponibile" in v.warnings.single())
        assertNull(v.positions.single().priceCents)
    }

    // ---- positions: unknown is not zero ----------------------------------------------------------------------------------------

    @Test fun categoriesWithoutAnyPositionsListAreUnknownNotAnEmptyPortfolio() {
        assertNull(TrMapper.parsePositions(json("""{"categories":[{"categoryType":"stocksAndETFs"}]}""")))
        assertNull(TrMapper.parsePositions(json("""{"categories":[{"categoryType":"a"},{"categoryType":"b","other":[1]}]}""")))
        assertNull(TrMapper.parsePositions(json("""{"categories":"oops"}""")))
        assertNull(TrMapper.parsePositions(json("""{"nothing":1}""")))
    }

    @Test fun noCategoriesAtAllAndAnEmptyPositionsListAreAKnownEmptyPortfolio() {
        assertEquals(emptyList<TrMapper.TrPosition>(), TrMapper.parsePositions(json("""{"categories":[]}""")))
        assertEquals(emptyList<TrMapper.TrPosition>(), TrMapper.parsePositions(json("""{"categories":[{"categoryType":"stocksAndETFs","positions":[]}]}""")))
        val mixed = TrMapper.parsePositions(json("""{"categories":[{"categoryType":"a"},{"categoryType":"b","positions":[{"isin":"$ISIN_X","name":"X","netSize":"1"}]}]}"""))
        assertEquals(1, mixed!!.size)
    }

    @Test fun anUnreadablePortfolioNullsTheTotalWithAWarningInsteadOfValuingSecuritiesAtZero() {
        val cash = listOf(TrMapper.TrCash("EUR", BigDecimal("100.25")))
        val positions = TrMapper.parsePositions(json("""{"categories":[{"categoryType":"stocksAndETFs"}]}"""))
        val v = TrMapper.valuation(positions, cash, emptyMap(), today)
        assertNull(v.balance)
        assertEquals("Posizioni non lette: il valore del conto non è stato calcolato.", v.warnings.single())
        assertNotNull("the cash sleeve is still usable", v.cash)
    }
}
