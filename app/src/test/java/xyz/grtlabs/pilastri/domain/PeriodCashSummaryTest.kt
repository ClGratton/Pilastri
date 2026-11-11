package xyz.grtlabs.pilastri.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PeriodCashSummaryTest {
    private val asOf = LocalDate.of(2026, 10, 31)

    private fun event(
        id: String,
        type: EventType,
        cents: Long,
        date: LocalDate = asOf,
        pillar: Int = 2
    ) = LedgerEvent(id, date, pillar, type, Money(cents), Confidence.USER_REPORTED, "synthetic source")

    @Test fun trailingWindowsHaveExplicitInclusiveBounds() {
        val day = periodCashSummary(emptyList(), 2, CashSummaryPeriod.DAY, asOf)
        assertEquals(asOf, day.startDate)
        assertEquals(asOf, day.endDate)

        val month = periodCashSummary(emptyList(), 2, CashSummaryPeriod.MONTH, asOf)
        assertEquals(LocalDate.of(2026, 10, 1), month.startDate)
        assertEquals(asOf, month.endDate)

        val year = periodCashSummary(emptyList(), 2, CashSummaryPeriod.YEAR, asOf)
        assertEquals(LocalDate.of(2025, 11, 1), year.startDate)
        assertEquals(asOf, year.endDate)
    }

    @Test fun monthlyBoundaryIsIncludedOnceAndFutureAndOutsideEventsAreExcluded() {
        val events = listOf(
            event("prior-boundary", EventType.INCOME, 100, LocalDate.of(2026, 9, 30)),
            event("first-day", EventType.INCOME, 200, LocalDate.of(2026, 10, 1)),
            event("last-day", EventType.INCOME, 300, asOf),
            event("future", EventType.INCOME, 400, asOf.plusDays(1)),
            event("other-pillar", EventType.INCOME, 500, asOf, pillar = 1)
        )
        val result = periodCashSummary(events, 2, CashSummaryPeriod.MONTH, asOf)
        assertEquals(PeriodCashTotal.Available(Money(500), 2), result.income)
        assertEquals(PeriodCashUnavailableReason.NO_RECORDS,
            (result.fees as PeriodCashTotal.Unavailable).reason)
    }

    @Test fun incomeAndFeesAreSummedSeparatelyAndOtherEventKindsAreExcluded() {
        val events = listOf(
            event("income-a", EventType.INCOME, 1_000),
            event("income-b", EventType.INCOME, 250),
            event("fee", EventType.FEE, 70),
            event("own", EventType.PERSONAL_CONTRIBUTION, 900),
            event("employer", EventType.EMPLOYER_CONTRIBUTION, 800),
            event("withdrawal", EventType.WITHDRAWAL, 700)
        )
        val result = periodCashSummary(events, 2, CashSummaryPeriod.DAY, asOf)
        assertEquals(PeriodCashTotal.Available(Money(1_250), 2), result.income)
        assertEquals(PeriodCashTotal.Available(Money(70), 1), result.fees)
    }

    @Test fun noRecordsIsDifferentFromARecordedZeroAmount() {
        val result = periodCashSummary(listOf(event("zero-income", EventType.INCOME, 0)), 2, CashSummaryPeriod.DAY, asOf)
        assertEquals(PeriodCashTotal.Available(Money(0), 1), result.income)
        assertEquals(PeriodCashTotal.Unavailable(PeriodCashUnavailableReason.NO_RECORDS, 0), result.fees)
    }

    @Test fun overflowIsReportedPerCategoryAndPreservesRecordCount() {
        val result = periodCashSummary(listOf(
            event("large-a", EventType.INCOME, Long.MAX_VALUE),
            event("large-b", EventType.INCOME, 1),
            event("fee", EventType.FEE, 10)
        ), 2, CashSummaryPeriod.DAY, asOf)
        assertEquals(PeriodCashTotal.Unavailable(PeriodCashUnavailableReason.OVERFLOW, 2), result.income)
        assertEquals(PeriodCashTotal.Available(Money(10), 1), result.fees)
    }

    @Test fun dayWindowOnlyIncludesEventsOnAsOfDate() {
        val result = periodCashSummary(listOf(
            event("yesterday", EventType.FEE, 10, asOf.minusDays(1)),
            event("today", EventType.FEE, 20, asOf),
            event("tomorrow", EventType.FEE, 30, asOf.plusDays(1))
        ), 2, CashSummaryPeriod.DAY, asOf)
        assertEquals(PeriodCashTotal.Available(Money(20), 1), result.fees)
    }
}
