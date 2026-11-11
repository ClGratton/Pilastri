package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.sync.auto.JsonMapping
import xyz.grtlabs.pilastri.sync.auto.JsonMovements
import xyz.grtlabs.pilastri.sync.auto.SyncRange

/** Data-path invariants (polish 2026-11-10, owner C). Every value is synthetic. */
class DataPathInvariantsTest {
    private val d = LocalDate.of(2026, 10, 20)

    /** Four pillars, each with one dated DOCUMENT account value, plus a pre-v14 ESPERO fund account in pillar 4. */
    private fun synthetic(): Archive {
        val accounts = (1..4).map { Account("acc-$it", "Conto sintetico $it", it, "fixture") } +
            Account("acc-fund", "Fondo sintetico", 4, "fixture", AccountType.PENSION_FUND, null, LegacyAccountType.ESPERO)
        val values = accounts.mapIndexed { i, a ->
            AccountValuation(a.id, d, Money(100_000L * (i + 1)), "Fixture sintetica", Confidence.DOCUMENT)
        }
        return Archive(accounts = accounts, accountValuations = values)
    }

    private fun expectedTotal() = (1..5).sumOf { 100_000L * it }

    @Test fun pillarsKeepTheirValuesWhenAnArchiveWrittenBeforeV14IsLoadedAndMigrated() {
        val v13 = V13.seal(13, V13.downgrade(V13.payloadOf(ArchiveCodec.encode(synthetic()))))
        val loaded = ArchiveCodec.decode(v13)
        (1..4).forEach { assertNotNull("pillar $it has no value after the migration", loaded.pillarValue(it).centsOrNull()) }
        val sum = loaded.attivitaSum()
        assertEquals(4, sum.known)
        assertEquals(expectedTotal(), sum.cents)
        assertEquals(d, loaded.newestValueDate())
        // The migrated fund keeps its legacy code and its value; writing it back and loading again changes nothing.
        assertEquals(LegacyAccountType.ESPERO, loaded.accounts.single { it.id == "acc-fund" }.legacyType)
        assertEquals(loaded, ArchiveCodec.decode(ArchiveCodec.encode(loaded)))
    }

    @Test fun pillarsKeepTheirValuesWhenACurrentArchiveIsReloaded() {
        val loaded = ArchiveCodec.decode(ArchiveCodec.encode(synthetic()))
        assertEquals(4, loaded.attivitaSum().known)
        assertEquals(expectedTotal(), loaded.attivitaSum().cents)
        // Through the read model the destinations use (same code, computed once per archive version).
        assertEquals(expectedTotal(), loaded.attivitaSum().cents)
    }

    private val mapping = JsonMapping(items = "items", id = "id", date = "when", amount = "amount", accountLabel = "sintetico", returnsWholeRange = true)
    private val window = SyncRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private fun read(json: String) = JsonMovements.read(json.toByteArray(), mapping, ImportProvider.ISYBANK, window, java.time.Instant.parse("2026-11-01T10:00:00Z"))

    @Test fun aReadThatFindsNoRowsIsAFailureAndNeverClaimsCoverage() {
        assertThrows(IllegalArgumentException::class.java) { read("""{"items":[]}""") }
    }

    @Test fun rowsThatDoNotParseMakeTheReadFailInsteadOfClaimingCoverage() {
        val json = """{"items":[{"id":"a1","when":"2026-10-05","amount":"-1,00"},{"id":"a2","when":"non una data","amount":"-2,00"}]}"""
        assertThrows(IllegalArgumentException::class.java) { read(json) }
    }

    @Test fun aReadWithRowsClaimsTheWholeRequestedWindow() {
        val b = read("""{"items":[{"id":"a1","when":"2026-10-05","amount":"-1,00"}]}""")
        assertEquals(1, b.movements.size)
        assertEquals(window.to, b.coverageTo)
    }

    @Test fun aBatchWithoutCoverageNeverMovesTheConnectionsCoverage() {
        val a = Archive(accounts = listOf(Account("acc-1", "Conto sintetico", 1, "fixture")))
        val first = a.withImportBatch(ImportBatch(ImportProvider.ISYBANK, "x", java.time.Instant.parse("2026-10-31T10:00:00Z"),
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30), listOf(ImportedMovement("m1", LocalDate.of(2026, 10, 5), -100, "EUR", "Bar sintetico", ExternalKind.CARD_PAYMENT, null))), "acc-1").archive
        val second = first.withImportBatch(ImportBatch(ImportProvider.ISYBANK, "x", java.time.Instant.parse("2026-11-05T10:00:00Z"), null, null, emptyList()), "acc-1").archive
        val c = second.connections.single()
        assertEquals(LocalDate.of(2026, 10, 30), c.coverageTo)
        assertTrue(c.coverageFrom == LocalDate.of(2026, 10, 1))
    }
}
