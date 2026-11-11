package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.TradeSide

/** Synthetic provider data only. */
class ImportsTest {
    private val broker = Account("tr", "Broker sintetico", 4, "fixture")
    private val day = LocalDate.of(2026, 3, 2)
    private fun batch(vararg movements: ImportedMovement, balance: ImportedBalance? = null) = ImportBatch(
        ImportProvider.TRADE_REPUBLIC, "Broker sintetico", Instant.parse("2026-03-03T10:00:00Z"), day, day, movements.toList(), balance)

    private val deposit = ImportedMovement("d1", day, 50_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT)
    private val buy = ImportedMovement("b1", day, -20_100, "EUR", "ETF sintetico", ExternalKind.BUY, "ORDER", "IE00B4L5Y983", BigDecimal("2.5"))

    @Test fun importIsPendingUntilReviewedAndDoesNotCountAsContribution() {
        val outcome = Archive(accounts = listOf(broker)).withImportBatch(batch(deposit, buy, balance = ImportedBalance(day, 70_000)), broker.id, "2026-03-03T10:00:01Z")
        assertEquals(2, outcome.added)
        assertTrue(outcome.archive.events.isEmpty())
        assertTrue(outcome.archive.externalMovements.all { it.status == ExternalStatus.PENDING })
        assertEquals(70_000, outcome.archive.accountValuations.single().amount.cents)
        assertEquals(outcome.archive, ArchiveCodec.decode(ArchiveCodec.encode(outcome.archive)))
    }

    @Test fun resyncKeepsReviewDecisionsAndSkipsDuplicates() {
        val first = Archive(accounts = listOf(broker)).withImportBatch(batch(deposit), broker.id).archive
        val reviewed = first.withExternalDecision("TRADE_REPUBLIC:d1", ExternalDecision.AsEvent(EventType.PERSONAL_CONTRIBUTION))
        val again = reviewed.withImportBatch(batch(deposit, buy), broker.id)
        assertEquals(1, again.added)
        assertEquals(1, again.alreadyPresent)
        assertEquals(ExternalStatus.APPLIED, again.archive.externalMovements.first { it.id == "TRADE_REPUBLIC:d1" }.status)
        assertEquals(1, again.archive.events.size)
    }

    @Test fun suggestionsFollowSignsAndTradesNeedIsinAndQuantity() {
        val archive = Archive(accounts = listOf(broker)).withImportBatch(batch(deposit, buy), broker.id).archive
        val movements = archive.externalMovements.associateBy { it.id }
        assertNull("A deposit is never a contribution by default", movements.getValue("TRADE_REPUBLIC:d1").suggestedDecision())
        assertEquals(null to SuggestionWait.AWAITING_BANK_EXPORT, archive.suggestedDecisionFor(movements.getValue("TRADE_REPUBLIC:d1")))
        assertEquals(ExternalDecision.AsTrade(TradeSide.BUY, "ETF sintetico"), movements.getValue("TRADE_REPUBLIC:b1").suggestedDecision())
        val traded = archive.withExternalDecision("TRADE_REPUBLIC:b1", ExternalDecision.AsTrade(TradeSide.BUY, "ETF sintetico"))
        assertEquals(20_100, traded.trades.single().grossAmount.cents)
        assertEquals("IE00B4L5Y983", traded.instruments.single().identifier)
        assertTrue("A trade is internal: no contribution", traded.events.isEmpty())
        val reopened = traded.withExternalReopened("TRADE_REPUBLIC:b1")
        assertTrue(reopened.trades.isEmpty())
        assertNull(reopened.externalMovements.first { it.id == "TRADE_REPUBLIC:b1" }.linkedRecordId)
    }

    @Test fun userValuationOnTheSameDayIsNotOverwritten() {
        val manual = xyz.grtlabs.pilastri.domain.AccountValuation(broker.id, day, xyz.grtlabs.pilastri.domain.Money(1), "dichiarato", xyz.grtlabs.pilastri.domain.Confidence.USER_REPORTED)
        val outcome = Archive(accounts = listOf(broker), accountValuations = listOf(manual)).withImportBatch(batch(balance = ImportedBalance(day, 5)), broker.id)
        assertEquals(listOf(manual), outcome.archive.accountValuations)
        assertEquals(false, outcome.valuationAdded)
    }

    @Test fun accountWithImportsCannotBeRemoved() {
        val archive = Archive(accounts = listOf(broker)).withImportBatch(batch(deposit), broker.id).archive
        assertThrows(IllegalArgumentException::class.java) { archive.withoutAccount(broker.id) }
    }

    @Test fun transferBetweenOwnAccountsIsPairedAndNeverAContribution() {
        val bank = Account("bank", "Conto sintetico", 1, "fixture")
        val base = Archive(accounts = listOf(broker, bank))
        val out = ImportBatch(ImportProvider.ISYBANK, "Conto sintetico", Instant.parse("2026-03-03T10:00:00Z"), day, day,
            listOf(ImportedMovement("o1", day, -50_000, "EUR", "Bonifico a broker", ExternalKind.WITHDRAWAL)))
        val withBank = base.withImportBatch(out, bank.id).archive.withImportBatch(batch(deposit.copy(date = day.plusDays(1))), broker.id).archive
        val pairs = withBank.transferPairs()
        assertEquals("TRADE_REPUBLIC:d1", pairs["ISYBANK:o1"])
        val applied = withBank.withExternalDecision("ISYBANK:o1", ExternalDecision.AsTransfer("TRADE_REPUBLIC:d1"))
        assertEquals(1, applied.transfers.size)
        assertTrue(applied.events.isEmpty())
        assertTrue(applied.externalMovements.all { it.status == ExternalStatus.APPLIED })
        assertEquals(applied, ArchiveCodec.decode(ArchiveCodec.encode(applied)))
        val reopened = applied.withExternalReopened("TRADE_REPUBLIC:d1")
        assertTrue(reopened.transfers.isEmpty())
        assertTrue(reopened.externalMovements.all { it.status == ExternalStatus.PENDING })
    }

    @Test fun unpairedBrokerDepositIsProposedOnlyOnceTheBankExportCoversIt() {
        val bank = Account("bank", "Conto sintetico", 1, "fixture")
        val bankBatch = ImportBatch(ImportProvider.ISYBANK, "Conto sintetico", Instant.parse("2026-03-20T10:00:00Z"), day.minusDays(30), day.plusDays(10),
            listOf(ImportedMovement("salary", day, 180_000, "EUR", "Stipendio", ExternalKind.DEPOSIT)))
        val archive = Archive(accounts = listOf(broker, bank)).withImportBatch(batch(deposit), broker.id).archive.withImportBatch(bankBatch, bank.id).archive
        val trDeposit = archive.externalMovements.first { it.id == "TRADE_REPUBLIC:d1" }
        assertEquals(ExternalDecision.AsEvent(EventType.PERSONAL_CONTRIBUTION) to SuggestionWait.NONE, archive.suggestedDecisionFor(trDeposit))
        val salary = archive.externalMovements.first { it.id == "ISYBANK:salary" }
        assertEquals("Salary is income, never a contribution", null to SuggestionWait.NONE, archive.suggestedDecisionFor(salary))
    }
}
