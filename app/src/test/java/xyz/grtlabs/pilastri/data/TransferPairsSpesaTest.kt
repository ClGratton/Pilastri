package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.EventType

/** An outflow the user answered "Spesa" (a category override) must not pair as a transfer again. Synthetic data only. */
class TransferPairsSpesaTest {
    private val bank = Account("bank", "Conto", 1, "fixture")
    private val trCash = Account("trc", "Liquidità broker", 2, "fixture")
    private val oct = LocalDate.of(2026, 10, 10)
    private val at = "2026-10-31T10:00:00Z"
    private fun batch(p: ImportProvider, vararg m: ImportedMovement) = ImportBatch(p, p.label, Instant.parse(at), oct, oct.plusDays(1), m.toList())

    private fun base(): Archive = Archive(accounts = listOf(bank, trCash), planning = Planning(categories = listOf(Category("c:spesa", "Spesa", CategoryKind.SPEND))))
        .withImportBatch(batch(ImportProvider.ISYBANK, ImportedMovement("out", oct, -10_000, "EUR", "Bonifico a un conto", ExternalKind.WITHDRAWAL)), bank.id, at).archive
        .withImportBatch(batch(ImportProvider.TRADE_REPUBLIC, ImportedMovement("in", oct.plusDays(1), 10_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT)), trCash.id, at).archive

    @Test fun anAppliedOutflowFiledAsSpesaNoLongerPairs() {
        val answered = base()
            .withExternalDecision("ISYBANK:out", ExternalDecision.AsEvent(EventType.WITHDRAWAL))
            .withMovementCategory("ISYBANK:out", "c:spesa")
        assertTrue(answered.transferPairs().isEmpty())
    }

    @Test fun anAppliedOutflowWithoutACategoryStillPairs() {
        val reviewed = base().withExternalDecision("ISYBANK:out", ExternalDecision.AsEvent(EventType.WITHDRAWAL))
        assertEquals("ISYBANK:out", reviewed.transferPairs()["TRADE_REPUBLIC:in"])
    }

    @Test fun aPendingOutflowWithACategoryStillPairs() {
        val pending = base().withMovementCategory("ISYBANK:out", "c:spesa")
        assertEquals("ISYBANK:out", pending.transferPairs()["TRADE_REPUBLIC:in"])
    }

    @Test fun reopeningOrClearingTheCategoryOffersThePairAgain() {
        val answered = base()
            .withExternalDecision("ISYBANK:out", ExternalDecision.AsEvent(EventType.WITHDRAWAL))
            .withMovementCategory("ISYBANK:out", "c:spesa")
        assertTrue(answered.transferPairs().isEmpty())
        val reopened = answered.withExternalReopened("ISYBANK:out", at)
        assertEquals("ISYBANK:out", reopened.transferPairs()["TRADE_REPUBLIC:in"])
        val cleared = answered.withMovementCategory("ISYBANK:out", null)
        assertEquals("ISYBANK:out", cleared.transferPairs()["TRADE_REPUBLIC:in"])
    }
}
