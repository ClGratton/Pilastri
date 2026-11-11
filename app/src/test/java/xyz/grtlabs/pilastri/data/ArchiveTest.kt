package xyz.grtlabs.pilastri.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import xyz.grtlabs.pilastri.domain.*

class ArchiveTest {
    private val sample = Archive(
        events = listOf(LedgerEvent("a", LocalDate.of(2026, 1, 1), 4, EventType.EMPLOYER_CONTRIBUTION,
            Money(1200), Confidence.USER_REPORTED, "payroll")),
        valuations = listOf(Valuation(LocalDate.of(2026, 1, 1), 4, Money(10000), "statement", Confidence.DOCUMENT))
    )
    @Test fun roundTripPreservesRecords() { assertEquals(sample, ArchiveCodec.decode(ArchiveCodec.encode(sample))) }
    @Test fun rejectsCorruption() {
        val root = JSONObject(ArchiveCodec.encode(sample).toString(Charsets.UTF_8))
        root.put("sha256", "bad")
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(root.toString().toByteArray()) }
    }
    @Test fun rejectsDuplicateIdsBeforeEncoding() {
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(sample.copy(events = sample.events + sample.events)) }
    }
    @Test fun realContributionsRequireEveryMonthlyIndexAndExcludeEmployer() {
        val personal = LedgerEvent("p", LocalDate.of(2026, 2, 5), 4, EventType.PERSONAL_CONTRIBUTION,
            Money(12000), Confidence.USER_REPORTED, "bank")
        val partial = sample.copy(events = sample.events + personal, indices = listOf(IndexPoint(YearMonth.of(2026, 1), BigDecimal("100"), "ISTAT")))
        assertNull(realContributions(partial))
        val complete = partial.copy(indices = partial.indices + IndexPoint(YearMonth.of(2026, 2), BigDecimal("120"), "ISTAT"))
        assertEquals(10000L, realContributions(complete)?.cents)
        assertEquals(complete, ArchiveCodec.decode(ArchiveCodec.encode(complete)))
    }
    @Test fun importsOnlyReportedPaymentsFromPrefill() {
        val json = """{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"netPaid2026September":111,"netPaid2026October":222},"illustrativeAssumptions":{"annualInflation":0.02}}"""
        val items = Prefill.parse(json.toByteArray())
        assertEquals(listOf(11100L, 22200L), items.map { it.amount.cents })
        assertTrue(items.all { it.status == PlanStatus.ACTUAL && it.kind == PlanKind.INCOME })
        assertEquals(Archive(plan = items), ArchiveCodec.decode(ArchiveCodec.encode(Archive(plan = items))))
    }
    @Test fun istatSnapshotStopsAtLastPublishedMonth() {
        val series = Istat.snapshot()
        assertEquals(9, series.size)
        assertEquals(BigDecimal("100.4"), series.first().value)
        assertEquals(YearMonth.of(2026, 9), series.last().month)
        assertEquals(BigDecimal("104.6"), series.last().value)
    }
    @Test fun mixedIndexSeriesAreRejectedBeforeSaving() {
        val invalid = sample.copy(indices = listOf(
            IndexPoint(YearMonth.of(2026, 1), BigDecimal("100.4"), "series A"),
            IndexPoint(YearMonth.of(2026, 2), BigDecimal("101.1"), "series B")))
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(invalid) }
    }
    @Test fun planAndCorrectionHistorySurviveBackup() {
        val item = PlanItem("x", YearMonth.of(2040, 3), "entrata ipotetica", PlanKind.INCOME,
            PlanStatus.SCENARIO, Money(10000), "scenario utente")
        val data = sample.copy(plan = listOf(item), audit = listOf(AuditEntry("2026-10-27T12:00:00Z", "rimozione", "test")))
        assertEquals(data, ArchiveCodec.decode(ArchiveCodec.encode(data)))
    }
    @Test fun correctionRetainsPriorValueAndSurvivesBackup() {
        val old = sample.valuations.single()
        val corrected = old.copy(amount = Money(15000), source = "corrected statement", confidence = Confidence.USER_REPORTED)
        val result = sample.correctValuation(old, corrected, "2026-10-27T12:00:00Z")
        assertEquals(listOf(corrected), result.valuations)
        assertEquals(old.toString(), result.audit.single().previous)
        assertEquals(result, ArchiveCodec.decode(ArchiveCodec.encode(result)))
        assertSame(sample, sample.correctValuation(old, old, "2026-10-27T12:00:00Z"))
    }
    @Test fun correctionDoesNotReplaceAnotherSnapshotOrChangeEventIdentity() {
        val old = sample.valuations.single()
        val other = old.copy(date = old.date.plusDays(1))
        val data = sample.copy(valuations = listOf(old, other))
        assertThrows(IllegalArgumentException::class.java) { data.correctValuation(old, old.copy(date = other.date), "now") }
        val event = sample.events.single()
        assertThrows(IllegalArgumentException::class.java) { sample.correctContribution(event, event.copy(id = "different"), "now") }
        val replacement = event.copy(amount = Money(1500))
        val result = sample.correctContribution(event, replacement, "2026-10-27T12:00:00Z")
        assertEquals("a", result.events.single().id)
        assertEquals(event.toString(), result.audit.single().previous)
        assertEquals(result, ArchiveCodec.decode(ArchiveCodec.encode(result)))
    }
}
