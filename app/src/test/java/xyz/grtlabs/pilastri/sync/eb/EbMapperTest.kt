package xyz.grtlabs.pilastri.sync.eb

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveCodec
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.withImportBatch
import xyz.grtlabs.pilastri.sync.auto.SyncRange
import xyz.grtlabs.pilastri.sync.eb.EbFx.tx

/** Synthetic data only. */
class EbMapperTest {
    private val account = EbLinkedAccount("uid-a", "hash-a", "Conto sintetico A", "EUR")
    private val range = SyncRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private val fetchedAt = Instant.parse("2026-10-31T10:00:00Z")

    private fun result(vararg rows: String, unreadable: Int = 0, complete: Boolean = true) =
        EbTransactionsResult(parse(rows.toList()), 1, unreadable, complete)

    private fun parse(rows: List<String>): List<EbTransaction> =
        rows.map { EbApi.parseTransaction(org.json.JSONObject(it)) }

    private fun map(booked: EbTransactionsResult, pending: EbTransactionsResult? = result(), balances: List<EbBalance>? = emptyList(), acct: EbLinkedAccount = account) =
        EbMapper.map(ImportProvider.ISYBANK, "isybank", acct, range, booked, pending, balances, fetchedAt)

    private fun ok(r: EbReadResult) = r as EbReadResult.Ok

    @Test fun bookedRowsBecomeMovementsWithSignAndCoverageOfTheRequestedRange() {
        val r = ok(map(result(
            tx("12.30", "DBIT", creditor = "Bar Sintetico", remittance = listOf("Pagamento POS", "carta 1234")),
            tx("1500.00", "CRDT", debtor = "Azienda Sintetica", creditor = null, remittance = listOf("Stipendio sintetico"), booking = "2026-10-27"),
        )))
        val b = r.batch
        assertEquals(ImportProvider.ISYBANK, b.provider)
        assertEquals(range.from, b.coverageFrom)
        assertEquals(range.to, b.coverageTo)
        assertEquals(listOf(150_000L, -1_230L), b.movements.map { it.signedCents })
        assertEquals(LocalDate.of(2026, 10, 27), b.movements[0].date)
        assertEquals("Azienda Sintetica · Stipendio sintetico", b.movements[0].description)
        assertEquals("Bar Sintetico · Pagamento POS carta 1234", b.movements[1].description)
        assertEquals(ExternalKind.DEPOSIT, b.movements[0].kind)
        assertEquals(ExternalKind.CARD_PAYMENT, b.movements[1].kind)
        assertEquals("isybank · Conto sintetico A", b.accountLabel)
        assertEquals(EbStats(booked = 2, pending = 0, ignored = 0, pages = 2), r.stats)
    }

    @Test fun pendingRowsAreKeptApartAndNeverBecomeMovements() {
        val booked = result(tx("5.00", "DBIT"), tx("9.00", "DBIT", status = "PDNG"), tx("7.00", "DBIT", status = "CNCL"), tx("8.00", "DBIT", status = "RJCT"))
        val pending = result(tx("20.00", "DBIT", status = "PDNG"), tx("5.00", "DBIT", status = "BOOK"))
        val r = ok(map(booked, pending))
        assertEquals(listOf(-500L), r.batch.movements.map { it.signedCents })
        assertEquals(setOf(-900L, -2_000L), r.pending.map { it.signedCents }.toSet())
        assertTrue(r.pendingComplete)
        assertEquals(2, r.stats.ignored)
        assertTrue(r.batch.warnings.any { it.contains("in sospeso") && it.contains("non contano come spesa") })
    }

    @Test fun anUnreadPendingListIsFlaggedButDoesNotAffectCoverage() {
        val r = ok(map(result(tx("5.00", "DBIT")), pending = null))
        assertFalse(r.pendingComplete)
        assertEquals(range.to, r.batch.coverageTo)
        assertTrue(r.batch.warnings.contains("Movimenti in sospeso non letti."))
    }

    @Test fun anIncompleteReadClaimsNothing() {
        val r = map(result(tx("5.00", "DBIT"), complete = false))
        assertTrue(r is EbReadResult.Failed)
    }

    @Test fun anyRowThatCannotBeReadFailsTheWholeRead() {
        val rows = listOf(
            tx(null, "DBIT"), tx("abc", "DBIT"), tx("1.005", "DBIT"), tx("-5.00", "DBIT"), tx("5.00", null), tx("5.00", "DBIT", booking = null), tx("5.00", "DBIT", currency = null),
        )
        rows.forEach { row -> assertTrue(row, map(result(tx("1.00", "DBIT"), row)) is EbReadResult.Failed) }
        assertTrue(map(result(tx("1.00", "DBIT"), unreadable = 1)) is EbReadResult.Failed)
        val failed = map(result(tx("1.00", "DBIT"), tx(null, "DBIT"))) as EbReadResult.Failed
        assertEquals("1 movimenti non leggibili: lettura non confermata.", failed.message)
    }

    @Test fun aValueDateIsUsedWhenThereIsNoBookingDate() {
        val row = """{"transaction_amount":{"currency":"EUR","amount":"2.00"},"credit_debit_indicator":"DBIT","status":"BOOK","value_date":"2026-10-12","remittance_information":["x"]}"""
        assertEquals(LocalDate.of(2026, 10, 12), ok(map(result(row))).batch.movements.single().date)
    }

    @Test fun anExplicitEmptyAnswerIsAConfirmedEmptyRangeNotAFailure() {
        val r = ok(map(result()))
        assertTrue(r.emptyConfirmed)
        assertEquals(0, r.batch.movements.size)
        assertEquals(range.from, r.batch.coverageFrom)
        assertTrue(r.batch.warnings.any { it.contains("confermato nessun movimento") })
    }

    @Test fun idsAreStableOrderIndependentAndKeepIdenticalRowsDistinct() {
        val a = tx("3.00", "DBIT", booking = "2026-10-05")
        val b = tx("4.00", "DBIT", booking = "2026-10-06")
        val one = ok(map(result(a, a, b))).batch.movements
        val two = ok(map(result(b, a, a))).batch.movements
        assertEquals(3, one.map { it.externalId }.toSet().size)
        assertEquals(one.map { it.externalId }.toSet(), two.map { it.externalId }.toSet())
        assertTrue(one.all { it.externalId.matches(Regex("[0-9a-f]{64}")) })
        assertFalse(one.any { it.externalId.contains("sintetico", ignoreCase = true) })
        val other = ok(map(result(a, a, b), acct = EbLinkedAccount("uid-z", "hash-z", "Altro", "EUR"))).batch.movements
        assertTrue(one.map { it.externalId }.intersect(other.map { it.externalId }.toSet()).isEmpty())
        // The same account in a new session (new uid, same identification hash) keeps its ids.
        val relinked = ok(map(result(a, a, b), acct = account.copy(uid = "uid-new"))).batch.movements
        assertEquals(one.map { it.externalId }.toSet(), relinked.map { it.externalId }.toSet())
    }

    @Test fun balanceKeepsItsDateAndPrefersTheBookedOne() {
        fun bal(type: String, amount: String, date: String?, ccy: String = "EUR") =
            EbBalance(type, amount, ccy, date?.let(LocalDate::parse), null)
        val booked = ok(map(result(), balances = listOf(bal("CLAV", "100.00", "2026-10-31"), bal("CLBD", "1234.56", "2026-10-30"))))
        assertEquals(123_456L, booked.batch.balance!!.cents)
        assertEquals(LocalDate.of(2026, 10, 30), booked.batch.balance!!.date)
        assertEquals(2, booked.balances.size)
        assertTrue(booked.balances.all { it.dateKnown })

        val available = ok(map(result(), balances = listOf(bal("CLAV", "100.00", "2026-10-31"))))
        assertEquals(10_000L, available.batch.balance!!.cents)
        assertTrue(available.batch.warnings.any { it.contains("saldo disponibile") })

        val undated = ok(map(result(), balances = listOf(bal("CLBD", "50.00", null))))
        assertEquals(LocalDate.of(2026, 10, 31), undated.batch.balance!!.date)
        assertFalse(undated.balances.single().dateKnown)
        assertTrue(undated.batch.warnings.any { it.contains("Data del saldo non indicata") })

        val foreign = ok(map(result(), balances = listOf(bal("CLBD", "50.00", "2026-10-30", "USD"))))
        assertNull(foreign.batch.balance)
        assertNull(ok(map(result(), balances = null)).batch.balance)
        assertTrue(ok(map(result(), balances = null)).batch.warnings.contains("Saldo non letto."))
    }

    @Test fun nonEuroRowsAreWarnedAboutAndTheArchiveSkipsThem() {
        val r = ok(map(result(tx("5.00", "DBIT", currency = "USD"), tx("1.00", "DBIT"))))
        assertTrue(r.batch.warnings.any { it.contains("valuta diversa") })
    }

    @Test fun theBatchSavesIntoTheArchiveAsPendingReviewAndResyncDoesNotDuplicate() {
        val acct = Account("isy", "Conto sintetico", 1, "fixture")
        val rows = result(tx("12.30", "DBIT", booking = "2026-10-20"), tx("30.00", "DBIT", status = "PDNG", booking = "2026-10-21"))
        val r = ok(map(rows, pending = result(), balances = listOf(EbBalance("CLBD", "500.00", "EUR", LocalDate.of(2026, 10, 30), null))))
        val first = Archive(accounts = listOf(acct)).withImportBatch(r.batch, acct.id, "2026-10-31T10:00:01Z")
        assertEquals(1, first.added)
        assertTrue(first.archive.externalMovements.all { it.status == ExternalStatus.PENDING && it.id.startsWith("ISYBANK:") })
        assertEquals(50_000L, first.archive.accountValuations.single().amount.cents)
        assertEquals(range.to, first.archive.connections.single().coverageTo)
        val again = first.archive.withImportBatch(r.batch, acct.id, "2026-10-31T12:00:00Z")
        assertEquals(0, again.added)
        assertEquals(1, again.alreadyPresent)
        assertEquals(first.archive, ArchiveCodec.decode(ArchiveCodec.encode(first.archive)))
        assertEquals(1, r.pending.size) // the pending row is outside the archive by construction
        assertEquals(1, first.archive.externalMovements.size)
    }
}
