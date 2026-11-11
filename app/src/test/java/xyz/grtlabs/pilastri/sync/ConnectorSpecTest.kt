package xyz.grtlabs.pilastri.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ConnectorKind
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.PositionKind

class ConnectorSpecTest {
    private val all = PositionKind.entries

    /** Every combination of positions, including none. */
    private val combinations: List<Set<PositionKind>> = (0 until (1 shl all.size)).map { mask -> all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() }

    @Test fun registryHasTheAgreedEntriesAndKinds() {
        assertEquals(listOf("ENABLE_BANKING", ImportProvider.TRADE_REPUBLIC.id, ImportProvider.PAYPAL.id, "NOIPA", "GMAIL", "ASSISTANT"), Connectors.all.map { it.id })
        assertEquals(ConnectorKind.BANK_AGGREGATOR, Connectors.ENABLE_BANKING.kind)
        assertEquals(ConnectorKind.BROKER, Connectors.TRADE_REPUBLIC.kind)
        assertEquals(ConnectorKind.WALLET, Connectors.PAYPAL.kind)
        assertEquals(ConnectorKind.PAYROLL, Connectors.NOIPA.kind)
        assertEquals(ConnectorKind.MAIL, Connectors.GMAIL.kind)
        assertEquals(ConnectorKind.ASSISTANT, Connectors.ASSISTANT.kind)
        assertEquals(SetupSlot.SCEGLI_BANCA, Connectors.ENABLE_BANKING.slot)
        assertEquals(SetupSlot.NONE, Connectors.GMAIL.slot)
        assertEquals(setOf(PositionKind.DIPENDENTE_PUBBLICO), Connectors.NOIPA.offeredWhen)
        assertTrue(Connectors.ENABLE_BANKING.staleness is Staleness.ConsentAndDays)
        assertEquals(Staleness.Never, Connectors.ASSISTANT.staleness)
    }

    @Test fun isybankHasNoDirectEntry() {
        assertNull(Connectors.byId(ImportProvider.ISYBANK.id))
        assertTrue(Connectors.all.none { it.label.contains("isybank", ignoreCase = true) })
    }

    @Test fun anUnimplementedConnectorIsNeverOffered() {
        assertFalse(Connectors.NOIPA.implemented)
        combinations.forEach { c ->
            assertTrue("NoiPA offered for $c", Connectors.offered(c).none { it.id == "NOIPA" })
            assertTrue(Connectors.offered(c).all { it.implemented })
        }
    }

    @Test fun noipaWouldMatchOnlyPublicEmployeesOnceImplemented() {
        val ready = Connectors.NOIPA.copy(implemented = true)
        combinations.forEach { c -> assertEquals("for $c", PositionKind.DIPENDENTE_PUBBLICO in c, ready.matches(c)) }
    }

    @Test fun altriContiOffersTheSameThreeToEveryPositionAndCombination() {
        combinations.forEach { c ->
            assertEquals("for $c", listOf(Connectors.TRADE_REPUBLIC, Connectors.PAYPAL, Connectors.ASSISTANT), Connectors.offered(c, SetupSlot.ALTRI_CONTI))
        }
    }

    @Test fun scegliBancaOffersOnlyEnableBankingAndGmailStaysOutOfSetup() {
        combinations.forEach { c ->
            assertEquals(listOf(Connectors.ENABLE_BANKING), Connectors.offered(c, SetupSlot.SCEGLI_BANCA))
            assertEquals(listOf(Connectors.GMAIL), Connectors.offered(c, SetupSlot.NONE))
        }
    }

    @Test fun eachSinglePositionGetsEveryImplementedConnectorMeantForEveryone() {
        PositionKind.entries.forEach { k ->
            assertEquals(listOf("ENABLE_BANKING", "TRADE_REPUBLIC", "PAYPAL", "GMAIL", "ASSISTANT"), Connectors.offered(setOf(k)).map { it.id })
        }
        assertEquals(Connectors.offered(setOf(PositionKind.STUDENTE)), Connectors.offered(emptySet()))
    }

    @Test(expected = IllegalArgumentException::class) fun blankIdsAreRefused() {
        ConnectorSpec(" ", "x", "y", ConnectorKind.MAIL, emptySet(), SetupSlot.NONE, Staleness.Never, true)
    }
}
