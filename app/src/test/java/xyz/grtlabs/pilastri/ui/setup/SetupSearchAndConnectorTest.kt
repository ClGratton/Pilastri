package xyz.grtlabs.pilastri.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.Connectors
import xyz.grtlabs.pilastri.sync.eb.EbAspsp

/** Setup-M: the bank search with its "Cancella ricerca" way out, and how an Altri conti row offers its connector. Synthetic names only. */
class SetupSearchAndConnectorTest {
    private fun bank(name: String) = EbAspsp(name, "IT", 15_552_000, listOf("personal"), emptyList(), emptyList(), false)
    private val banks = listOf(bank("Banca Alfa"), bank("Banca Beta"), bank("Cassa Gamma"))

    @Test fun blankQueryShowsEveryBank() {
        assertEquals(banks, SetupRules.filterBanks(banks, ""))
        assertEquals(banks, SetupRules.filterBanks(banks, "   "))
    }

    @Test fun queryIsTrimmedAndCaseInsensitive() {
        assertEquals(listOf("Banca Alfa", "Banca Beta"), SetupRules.filterBanks(banks, "  BANCA ").map { it.name })
        assertEquals(listOf("Cassa Gamma"), SetupRules.filterBanks(banks, "gam").map { it.name })
    }

    @Test fun noMatchIsEmptyAndClearingBringsTheWholeListBack() {
        var query = "zzz"
        assertTrue(SetupRules.filterBanks(banks, query).isEmpty())
        query = ""  // what "Cancella ricerca" and the field's × both do
        assertEquals(banks, SetupRules.filterBanks(banks, query))
    }

    @Test fun emptyResultLineNamesTheSearch() {
        assertEquals("Nessuna banca", SetupRules.noBankLine(""))
        assertEquals("Nessuna banca", SetupRules.noBankLine("   "))
        assertEquals("Nessuna banca per «zzz»", SetupRules.noBankLine("  zzz "))
        assertEquals("Nessuna banca per «" + "x".repeat(40) + "»", SetupRules.noBankLine("x".repeat(90)))
    }

    @Test fun assistantRowChoosesInPlaceWithoutAnExtraHop() {
        assertEquals(ConnectorShape.CHOOSE, SetupRules.connectorShape(Connectors.ASSISTANT, linked = false))
        assertEquals(ConnectorShape.LINKED, SetupRules.connectorShape(Connectors.ASSISTANT, linked = true))
    }

    @Test fun otherConnectorsKeepOneCollega() {
        assertEquals(ConnectorShape.LINK, SetupRules.connectorShape(Connectors.TRADE_REPUBLIC, linked = false))
        assertEquals(ConnectorShape.LINK, SetupRules.connectorShape(Connectors.PAYPAL, linked = false))
        assertEquals(ConnectorShape.LINKED, SetupRules.connectorShape(Connectors.PAYPAL, linked = true))
    }

    @Test fun altriContiShapesFollowTheRegistryOrder() {
        val shapes = SetupRules.offered(SetupModel()).map { SetupRules.connectorShape(it, linked = false) }
        assertEquals(listOf(ConnectorShape.LINK, ConnectorShape.LINK, ConnectorShape.CHOOSE), shapes)
    }
}
