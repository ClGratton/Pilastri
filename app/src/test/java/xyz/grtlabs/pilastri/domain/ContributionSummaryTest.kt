package xyz.grtlabs.pilastri.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class ContributionSummaryTest {
    private val january = YearMonth.of(2026, 1)
    private val february = YearMonth.of(2026, 2)
    private val indices = mapOf(
        january to ContributionIndex(BigDecimal("100"), "synthetic NIC series"),
        february to ContributionIndex(BigDecimal("125"), "synthetic NIC series")
    )

    private fun event(id: String, type: EventType, cents: Long, pillar: Int = 4, day: LocalDate = LocalDate.of(2026, 2, 5)) =
        LedgerEvent(id, day, pillar, type, Money(cents), Confidence.USER_REPORTED, "synthetic test record")

    private fun available(cents: Long) = ContributionAmount.Available(Money(cents))
    private fun unavailable(reason: ContributionAmountUnavailableReason) = ContributionAmount.Unavailable(reason)

    @Test fun separatesOwnEmployerAndWithdrawalsForPillarAndAllPillars() {
        val summary = summarizeContributions(listOf(
            event("own-jan", EventType.PERSONAL_CONTRIBUTION, 10_000, pillar = 1, day = LocalDate.of(2026, 1, 10)),
            event("own-feb", EventType.PERSONAL_CONTRIBUTION, 12_500),
            event("employer", EventType.EMPLOYER_CONTRIBUTION, 5_000),
            event("withdrawal", EventType.WITHDRAWAL, 2_500),
            event("income", EventType.INCOME, 300),
            event("fee", EventType.FEE, 80)
        ), indices)

        assertEquals(available(10_000), summary.byPillar.getValue(1).personalGross.nominal)
        assertEquals(available(10_000), summary.byPillar.getValue(1).personalGross.realJanuary2026)
        assertEquals(available(12_500), summary.byPillar.getValue(4).personalGross.nominal)
        assertEquals(available(10_000), summary.byPillar.getValue(4).personalGross.realJanuary2026)
        assertEquals(available(22_500), summary.allPillars.personalGross.nominal)
        assertEquals(available(20_000), summary.allPillars.personalGross.realJanuary2026)
        assertEquals(available(5_000), summary.allPillars.employerGross.nominal)
        assertEquals(available(4_000), summary.allPillars.employerGross.realJanuary2026)
        assertEquals(available(2_500), summary.allPillars.withdrawals.nominal)
        assertEquals(available(2_000), summary.allPillars.withdrawals.realJanuary2026)
        assertEquals(available(300), summary.allPillars.income.nominal)
        assertEquals(available(80), summary.allPillars.fees.nominal)
        // Own contributions remain gross; an unknown-origin withdrawal is never subtracted from them.
        assertEquals(available(22_500), summary.allPillars.personalGross.nominal)
    }

    @Test fun reportsNoRecordsWithoutCallingItZero() {
        val summary = summarizeContributions(emptyList(), emptyMap()).allPillars
        assertEquals(unavailable(ContributionAmountUnavailableReason.NO_RECORDS), summary.personalGross.nominal)
        assertEquals(unavailable(ContributionAmountUnavailableReason.NO_RECORDS), summary.personalGross.realJanuary2026)
        assertEquals(unavailable(ContributionAmountUnavailableReason.NO_RECORDS), summary.withdrawals.nominal)
    }

    @Test fun distinguishesMissingBaseMissingDatedIndexAndIncompatibleSources() {
        val februaryEvent = listOf(event("own", EventType.PERSONAL_CONTRIBUTION, 1_000))
        assertEquals(unavailable(ContributionAmountUnavailableReason.MISSING_JANUARY_2026_BASE),
            summarizeContributions(februaryEvent, mapOf(february to indices.getValue(february))).allPillars.personalGross.realJanuary2026)

        val marchEvent = listOf(event("march", EventType.PERSONAL_CONTRIBUTION, 1_000, day = LocalDate.of(2026, 3, 1)))
        assertEquals(unavailable(ContributionAmountUnavailableReason.MISSING_MONTH_INDEX),
            summarizeContributions(marchEvent, indices).allPillars.personalGross.realJanuary2026)

        val mixed = indices + (february to ContributionIndex(BigDecimal("125"), "other synthetic series"))
        assertEquals(unavailable(ContributionAmountUnavailableReason.INCOMPATIBLE_INDEX_SERIES),
            summarizeContributions(februaryEvent, mixed).allPillars.personalGross.realJanuary2026)
    }

    @Test fun detectsNominalAndDeflatedSumOverflow() {
        val events = listOf(
            event("huge", EventType.PERSONAL_CONTRIBUTION, Long.MAX_VALUE, day = LocalDate.of(2026, 1, 5)),
            event("one", EventType.PERSONAL_CONTRIBUTION, 1, day = LocalDate.of(2026, 1, 6))
        )
        val result = summarizeContributions(events, mapOf(january to indices.getValue(january))).allPillars.personalGross
        assertEquals(unavailable(ContributionAmountUnavailableReason.OVERFLOW), result.nominal)
        assertEquals(unavailable(ContributionAmountUnavailableReason.OVERFLOW), result.realJanuary2026)
    }

    @Test fun invalidMonthlyIndexIsUnavailable() {
        val bad = mapOf(
            january to indices.getValue(january),
            february to ContributionIndex(BigDecimal.ZERO, "synthetic NIC series")
        )
        assertEquals(unavailable(ContributionAmountUnavailableReason.INVALID_INDEX),
            summarizeContributions(listOf(event("own", EventType.PERSONAL_CONTRIBUTION, 1_000)), bad)
                .allPillars.personalGross.realJanuary2026)
    }
}
