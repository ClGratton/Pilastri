package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import xyz.grtlabs.pilastri.domain.*

class PillarPerformanceTest {
    private val start = LocalDate.parse("2025-01-03")
    private val middle = LocalDate.parse("2025-07-02")
    private val end = LocalDate.parse("2026-01-03")
    private val source = "Estratto sintetico"

    private fun valuation(date: LocalDate, cents: Long, pillar: Int = 1, confidence: Confidence = Confidence.DOCUMENT) =
        Valuation(date, pillar, Money(cents), "Valore ${date}", confidence)

    private fun event(id: String, date: LocalDate, type: EventType, cents: Long, pillar: Int = 1,
                      confidence: Confidence = Confidence.DOCUMENT, source: String = "Movimento") =
        LedgerEvent(id, date, pillar, type, Money(cents), confidence, source)

    private fun archive(
        valuations: List<Valuation> = listOf(valuation(start, 10_000), valuation(middle, 11_000), valuation(end, 13_800)),
        events: List<LedgerEvent> = emptyList(),
        accounts: List<Account> = emptyList(),
        transfers: List<AccountTransfer> = emptyList(),
        indices: List<IndexPoint> = emptyList()
    ) = Archive(events = events, valuations = valuations, accounts = accounts, transfers = transfers, indices = indices)

    private fun attestation(data: Archive, period: PerformancePeriod = PerformancePeriod.YEAR): PerformanceAttestation {
        val preflight = preparePillarPerformance(data, 1, period, source)
        assertNull(preflight.blocker)
        return PerformanceAttestation(
            id = "test-attestation", pillar = 1, start = preflight.window!!.start, end = preflight.window.end,
            source = source, reviewedAt = "2026-10-28T10:00:00Z",
            inputFingerprint = preflight.fingerprint!!, externalFlowsComplete = true, valuationRolesConfirmed = true
        )
    }

    @Test fun exactTrailingWindowRequiresMatchingActualEndpoints() {
        val data = archive()
        assertEquals(PerformanceDateWindow(start, end), performanceWindow(data, 1, PerformancePeriod.YEAR))
        assertNull(performanceWindow(data.copy(valuations = data.valuations.filterNot { it.date == start }), 1, PerformancePeriod.YEAR))
        assertNull(performanceWindow(data.copy(valuations = data.valuations.map {
            if (it.date == start) it.copy(confidence = Confidence.SCENARIO) else it
        }), 1, PerformancePeriod.YEAR))
    }

    @Test fun contributionIsRemovedFromReturnByPreFlowBoundary() {
        val data = archive(events = listOf(event("deposit", middle, EventType.PERSONAL_CONTRIBUTION, 1_000)))
        val result = evaluatePillarPerformance(data, 1, PerformancePeriod.YEAR, attestation(data))
        assertTrue(result is PillarPerformanceResult.Available)
        assertEquals(BigDecimal("26.50000000"), (result as PillarPerformanceResult.Available).nominal.percent)
    }

    @Test fun missingBoundaryAndEndpointFlowAreUnavailable() {
        val missing = archive(valuations = listOf(valuation(start, 10_000), valuation(end, 13_800)),
            events = listOf(event("deposit", middle, EventType.PERSONAL_CONTRIBUTION, 10_000)))
        assertEquals(PillarPerformanceBlocker.MISSING_FLOW_SNAPSHOT,
            preparePillarPerformance(missing, 1, PerformancePeriod.YEAR, source).blocker)
        val endpoint = archive(events = listOf(event("deposit", start, EventType.PERSONAL_CONTRIBUTION, 10_000)))
        assertEquals(PillarPerformanceBlocker.ENDPOINT_FLOW,
            preparePillarPerformance(endpoint, 1, PerformancePeriod.YEAR, source).blocker)
    }

    @Test fun withdrawalsAreNegativeAndSameDateMultipleFlowsAreRejected() {
        val one = archive(events = listOf(event("withdrawal", middle, EventType.WITHDRAWAL, 1_000)))
        val pre = preparePillarPerformance(one, 1, PerformancePeriod.YEAR, source)
        assertEquals(-1_000L, pre.flowBoundaries!!.single().externalFlowCents)
        val two = one.copy(events = one.events + event("deposit", middle, EventType.PERSONAL_CONTRIBUTION, 1_000))
        val rejected = preparePillarPerformance(two, 1, PerformancePeriod.YEAR, source)
        assertEquals(PillarPerformanceBlocker.MULTIPLE_FLOWS_ON_DATE, rejected.blocker)
        assertEquals(listOf(middle), rejected.flowConflictDates)
    }

    @Test fun incomeIsAmbiguousAndFeesRemainInternal() {
        val income = archive(events = listOf(event("income", middle, EventType.INCOME, 100)))
        assertEquals(PillarPerformanceBlocker.UNSUPPORTED_INCOME_ROLE,
            preparePillarPerformance(income, 1, PerformancePeriod.YEAR, source).blocker)
        val fee = archive(events = listOf(event("fee", middle, EventType.FEE, 100)))
        assertTrue(preparePillarPerformance(fee, 1, PerformancePeriod.YEAR, source).flowBoundaries!!.isEmpty())
    }

    @Test fun crossPillarTransferIsExternalButSamePillarTransferIsInternal() {
        val accounts = listOf(Account("a", "A", 1, "Registro"), Account("b", "B", 2, "Registro"), Account("c", "C", 1, "Registro"))
        val cross = AccountTransfer("cross", middle, "a", "b", Money(500), "Estratto")
        val internal = AccountTransfer("internal", middle, "a", "c", Money(700), "Estratto")
        val data = archive(accounts = accounts, transfers = listOf(cross, internal))
        val boundaries = preparePillarPerformance(data, 1, PerformancePeriod.YEAR, source).flowBoundaries!!
        assertEquals(1, boundaries.size)
        assertEquals(-500L, boundaries.single().externalFlowCents)
    }

    @Test fun editedInputsInvalidateAttestationButUnrelatedPillarDoesNot() {
        val data = archive(events = listOf(event("deposit", middle, EventType.PERSONAL_CONTRIBUTION, 100)))
        val confirmed = attestation(data)
        assertTrue(evaluatePillarPerformance(data, 1, PerformancePeriod.YEAR, confirmed) is PillarPerformanceResult.Available)
        val changed = data.copy(events = data.events.map { it.copy(source = "Rettificato") })
        assertEquals(PillarPerformanceBlocker.STALE_ATTESTATION,
            (evaluatePillarPerformance(changed, 1, PerformancePeriod.YEAR, confirmed) as PillarPerformanceResult.Unavailable).blocker)
        val unrelated = data.copy(events = data.events + event("p2", middle, EventType.FEE, 50, pillar = 2))
        assertEquals(confirmed.inputFingerprint, performanceInputFingerprint(unrelated, 1, start, end, source))
    }

    @Test fun valuationTransferAndScopeSourceEditsChangeFingerprint() {
        val base = archive()
        val expected = performanceInputFingerprint(base, 1, start, end, source)
        val changedValuation = base.copy(valuations = base.valuations.map {
            if (it.date == middle) it.copy(amount = Money(it.amount.cents + 1)) else it
        })
        assertNotEquals(expected, performanceInputFingerprint(changedValuation, 1, start, end, source))

        val accounts = listOf(Account("one", "One", 1, "Registry"), Account("two", "Two", 2, "Registry"))
        val transferData = base.copy(accounts = accounts,
            transfers = listOf(AccountTransfer("move", middle, "one", "two", Money(5), "Statement")))
        val transferFingerprint = performanceInputFingerprint(transferData, 1, start, end, source)
        val remapped = transferData.copy(accounts = accounts.map { if (it.id == "one") it.copy(pillar = 3) else it })
        assertNotEquals(transferFingerprint, performanceInputFingerprint(remapped, 1, start, end, source))
        assertNotEquals(expected, performanceInputFingerprint(base, 1, start, end, "Different attestation source"))
    }

    @Test fun missingAttestationAndUnconfirmedFlagsNeverExposeReturn() {
        val data = archive()
        assertEquals(PillarPerformanceBlocker.MISSING_ATTESTATION,
            (evaluatePillarPerformance(data, 1, PerformancePeriod.YEAR) as PillarPerformanceResult.Unavailable).blocker)
    }

    @Test fun realMonthReturnUsesCompatibleExactMonthlyIndices() {
        val monthStart = LocalDate.parse("2026-02-28")
        val monthEnd = LocalDate.parse("2026-03-28")
        val data = archive(
            valuations = listOf(valuation(monthStart, 10_000), valuation(monthEnd, 21_000)),
            indices = listOf(IndexPoint(YearMonth.of(2026, 2), BigDecimal("100"), "NIC test"),
                IndexPoint(YearMonth.of(2026, 3), BigDecimal("105"), "NIC test"))
        )
        val confirmed = attestation(data, PerformancePeriod.MONTH)
        val available = evaluatePillarPerformance(data, 1, PerformancePeriod.MONTH, confirmed) as PillarPerformanceResult.Available
        assertEquals(BigDecimal("100.00000000"), available.realPercent)
        val dailyData = archive(valuations = listOf(valuation(LocalDate.parse("2026-01-02"), 10_000), valuation(LocalDate.parse("2026-01-03"), 11_000)))
        val daily = evaluatePillarPerformance(dailyData, 1, PerformancePeriod.DAY, attestation(dailyData, PerformancePeriod.DAY)) as PillarPerformanceResult.Available
        assertEquals(RealPerformanceUnavailableReason.DAILY_WINDOW, daily.realUnavailableReason)
        assertNull(daily.realPercent)
    }
}
