package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Trade
import xyz.grtlabs.pilastri.domain.TradeSide
import xyz.grtlabs.pilastri.domain.Instrument

class TradingArchiveTest {
    private val account = Account("acct", "Conto", 1, "utente")
    private val instrument = Instrument("instrument", "Fondo indice", null, "contratto")
    private val trade = Trade("trade", "acct", "instrument", LocalDate.of(2026, 1, 5), TradeSide.BUY,
        BigDecimal("1.2500"), Money(12345), Money(25), "estratto")

    @Test fun v9RoundTripKeepsReportedTradeAndNeverAddsLedgerEvents() {
        val archive = Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(trade))
        val bytes = ArchiveCodec.encode(archive)
        assertEquals(14, JSONObject(bytes.toString(Charsets.UTF_8)).getInt("version"))
        assertEquals(archive, ArchiveCodec.decode(bytes))
        assertTrue(ArchiveCodec.decode(bytes).events.isEmpty())
    }

    @Test fun v7ArchiveRemainsReadableAndHasEmptyTradingLists() {
        val legacyCashFlow = LedgerEvent("income-v7", LocalDate.of(2026, 1, 2), 1, EventType.INCOME, Money(90), Confidence.USER_REPORTED, "synthetic source")
        val current = JSONObject(ArchiveCodec.encode(Archive(events = listOf(legacyCashFlow))).toString(Charsets.UTF_8))
        val legacyPayload = JSONObject(String(java.util.Base64.getDecoder().decode(current.getString("payload")), Charsets.UTF_8))
        legacyPayload.remove("instruments"); legacyPayload.remove("trades")
        legacyPayload.remove("transfers")
        legacyPayload.remove("projections")
        legacyPayload.remove("performanceAttestations"); legacyPayload.remove("externalMovements"); legacyPayload.remove("connections"); legacyPayload.remove("planning"); legacyPayload.remove("pension"); legacyPayload.remove("holdings"); legacyPayload.remove("installments"); legacyPayload.remove("installmentSync"); V13.downgrade(legacyPayload)
        val payload = legacyPayload.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        val legacy = JSONObject().put("format", "pilastri-archive").put("version", 7).put("sha256", hash)
            .put("payload", java.util.Base64.getEncoder().encodeToString(payload)).toString().toByteArray(Charsets.UTF_8)
        val decoded = ArchiveCodec.decode(legacy)
        assertTrue(decoded.instruments.isEmpty())
        assertTrue(decoded.trades.isEmpty())
        assertEquals(listOf(legacyCashFlow), decoded.events)
    }

    @Test fun archiveRejectsDuplicateIdentifiersOrOrphanedTradeReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), instruments = listOf(instrument.copy(identifier = "EXAMPLE"), instrument.copy(id = "other", identifier = "example"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(trade.copy(accountId = "missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(trade.copy(instrumentId = "missing"))))
        }
    }

    @Test fun archiveRejectsInvalidQuantityMoneyAndFutureDates() {
        assertThrows(IllegalArgumentException::class.java) { trade.copy(quantity = BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { trade.copy(quantity = BigDecimal("1.1234567890123")) }
        assertThrows(IllegalArgumentException::class.java) { trade.copy(grossAmount = Money(0)) }
        assertThrows(IllegalArgumentException::class.java) { trade.copy(fees = Money(-1)) }
        val future = trade.copy(date = LocalDate.now().plusDays(1))
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(future))) }
        assertThrows(ArithmeticException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(trade.copy(grossAmount = Money(Long.MAX_VALUE)), trade.copy(id = "second"))))
        }
    }

    @Test fun referencedInstrumentAndAccountCannotBeRemoved() {
        val archive = Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(trade))
        assertThrows(IllegalArgumentException::class.java) { archive.withoutInstrument(instrument.id) }
        assertThrows(IllegalArgumentException::class.java) { archive.withoutAccount(account.id) }
        assertTrue(archive.withoutTrade(trade.id).withoutInstrument(instrument.id).withoutAccount(account.id).trades.isEmpty())
    }

    @Test fun removalsKeepTheRemovedRecordsInAuditHistory() {
        val archive = Archive(accounts = listOf(account), instruments = listOf(instrument), trades = listOf(trade))
        val afterTrade = archive.withoutTrade(trade.id)
        assertTrue(afterTrade.audit.single().previous.contains(trade.id))
        val afterInstrument = afterTrade.withoutInstrument(instrument.id)
        assertTrue(afterInstrument.audit.last().previous.contains(instrument.id))
    }
}
