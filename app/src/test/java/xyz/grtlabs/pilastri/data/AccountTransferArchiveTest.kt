package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.AccountTransfer
import xyz.grtlabs.pilastri.domain.Instrument
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.domain.Trade
import xyz.grtlabs.pilastri.domain.TradeSide
import java.math.BigDecimal

class AccountTransferArchiveTest {
    private val from = Account("from", "Conto origine", 1, "fixture")
    private val to = Account("to", "Conto destinazione", 2, "fixture")
    private val transfer = AccountTransfer("transfer-1", LocalDate.of(2026, 2, 3), from.id, to.id, Money(12_500), "estratto sintetico")

    private fun reseal(root: JSONObject, payload: JSONObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return root.put("sha256", hash).put("payload", java.util.Base64.getEncoder().encodeToString(bytes)).toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun v9RoundTripKeepsTransferSeparateFromContributionsAndTrades() {
        val archive = Archive(accounts = listOf(from, to), transfers = listOf(transfer))
        val encoded = ArchiveCodec.encode(archive)
        assertEquals(14, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("version"))
        val restored = ArchiveCodec.decode(encoded)
        assertEquals(archive, restored)
        assertTrue(restored.events.isEmpty())
        assertTrue(restored.trades.isEmpty())
    }

    @Test fun v8ArchiveDecodesWithNoTransfersAndV9RequiresTheList() {
        val instrument = Instrument("instrument", "Synthetic", null, "fixture")
        val trade = Trade("trade", from.id, instrument.id, LocalDate.of(2026, 1, 4), TradeSide.BUY, BigDecimal("2.5"), Money(500), Money(5), "fixture")
        val v8Archive = Archive(accounts = listOf(from, to), instruments = listOf(instrument), trades = listOf(trade))
        val root = JSONObject(ArchiveCodec.encode(v8Archive).toString(Charsets.UTF_8))
        val payload = JSONObject(String(java.util.Base64.getDecoder().decode(root.getString("payload")), Charsets.UTF_8))
        payload.remove("transfers")
        payload.remove("projections")
        payload.remove("performanceAttestations"); payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload)
        val v8 = reseal(root.put("version", 8), payload)
        val decodedV8 = ArchiveCodec.decode(v8)
        assertTrue(decodedV8.transfers.isEmpty())
        assertEquals(v8Archive, decodedV8)

        payload.put("transfers", org.json.JSONArray())
        payload.remove("projections")
        payload.remove("performanceAttestations"); payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload)
        val brokenV9 = reseal(root.put("version", 9), payload)
        val brokenPayload = JSONObject(String(java.util.Base64.getDecoder().decode(JSONObject(String(brokenV9)).getString("payload")), Charsets.UTF_8))
        brokenPayload.remove("transfers")
        assertThrows(Exception::class.java) { ArchiveCodec.decode(reseal(JSONObject(String(brokenV9)), brokenPayload)) }
    }

    @Test fun transferRequiresDistinctExistingAccountsPositiveEurAndAValidDate() {
        assertThrows(IllegalArgumentException::class.java) { transfer.copy(toAccountId = from.id) }
        assertThrows(IllegalArgumentException::class.java) { transfer.copy(amount = Money(0)) }
        assertThrows(IllegalArgumentException::class.java) { transfer.copy(amount = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { transfer.copy(amount = Money(1, "USD")) }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(from), transfers = listOf(transfer)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(from, to), transfers = listOf(transfer.copy(fromAccountId = "missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(from, to), transfers = listOf(transfer.copy(date = LocalDate.now().plusDays(1)))))
        }
    }

    @Test fun transferIdsAndAggregateAmountsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(from, to), transfers = listOf(transfer, transfer.copy(date = transfer.date.minusDays(1)))) )
        }
        assertThrows(ArithmeticException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(from, to), transfers = listOf(
                transfer.copy(amount = Money(Long.MAX_VALUE)), transfer.copy(id = "transfer-2")
            )))
        }
    }

    @Test fun referencedAccountsAreProtectedAndTransferRemovalIsAudited() {
        val archive = Archive(accounts = listOf(from, to), transfers = listOf(transfer))
        assertThrows(IllegalArgumentException::class.java) { archive.withoutAccount(from.id) }
        assertThrows(IllegalArgumentException::class.java) { archive.withoutAccount(to.id) }
        val removed = archive.withoutTransfer(transfer.id)
        assertTrue(removed.transfers.isEmpty())
        assertTrue(removed.audit.single().previous.contains(transfer.id))
        assertEquals(listOf(from), removed.withoutAccount(to.id).accounts)
    }

    @Test fun correctionsKeepStableIdsAuditPriorValuesAndGuardAccountScope() {
        val archive = Archive(accounts = listOf(from, to), transfers = listOf(transfer))
        val replacement = transfer.copy(amount = Money(15_000))
        val corrected = archive.correctTransfer(transfer, replacement, "2026-10-28T10:00:00Z")
        assertEquals(replacement, corrected.transfers.single())
        assertTrue(corrected.audit.single().previous.contains("12500"))
        assertEquals(archive, archive.correctTransfer(transfer, transfer, "unused"))
        assertThrows(IllegalArgumentException::class.java) { archive.correctTransfer(transfer, replacement.copy(id = "new"), "now") }
        assertThrows(IllegalArgumentException::class.java) { archive.updateAccount(to.copy(pillar = 3)) }

        val instrument = Instrument("i", "Synthetic", null, "fixture")
        val trade = Trade("t", from.id, instrument.id, transfer.date, TradeSide.BUY, BigDecimal.ONE, Money(100), Money(0), "fixture")
        val tradingArchive = Archive(accounts = listOf(from, to), instruments = listOf(instrument), trades = listOf(trade))
        val changedInstrument = instrument.copy(name = "Renamed")
        assertEquals(changedInstrument, tradingArchive.correctInstrument(instrument, changedInstrument, "now").instruments.single())
        assertEquals(trade.copy(grossAmount = Money(200)), tradingArchive.correctTrade(trade, trade.copy(grossAmount = Money(200)), "now").trades.single())
        assertThrows(IllegalArgumentException::class.java) { tradingArchive.updateAccount(from.copy(pillar = 3)) }
        assertEquals(tradingArchive, tradingArchive.correctInstrument(instrument, instrument, "unused"))
        assertEquals(tradingArchive, tradingArchive.correctTrade(trade, trade, "unused"))
    }
}
