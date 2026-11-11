package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth
import xyz.grtlabs.pilastri.domain.Money

class PlanAnalyticsTest {
    @Test fun computesRecordedPositiveAndNegativeNetWithRepresentedMonths() {
        val rows = listOf(
            item("a", 2026, 1, PlanKind.INCOME, PlanStatus.ACTUAL, 10_000),
            item("b", 2026, 3, PlanKind.INCOME, PlanStatus.ACTUAL, 20_000),
            item("c", 2026, 3, PlanKind.EXPENSE, PlanStatus.ACTUAL, 5_000),
            item("d", 2026, 4, PlanKind.INCOME, PlanStatus.SCENARIO, 8_000),
            item("e", 2026, 5, PlanKind.EXPENSE, PlanStatus.SCENARIO, 11_000),
        )

        val summary = summarizePlanYear(rows, 2026)

        val actual = summary.actual as PlanStatusAnalytics.Available
        assertEquals(3, actual.rowCount)
        assertEquals(2, actual.monthCount)
        assertEquals(30_000L, actual.recordedIncomeCents)
        assertEquals(5_000L, actual.recordedExpenseCents)
        assertEquals(25_000L, actual.recordedNetCents)
        val scenario = summary.scenario as PlanStatusAnalytics.Available
        assertEquals(2, scenario.rowCount)
        assertEquals(2, scenario.monthCount)
        assertEquals(8_000L, scenario.recordedIncomeCents)
        assertEquals(11_000L, scenario.recordedExpenseCents)
        assertEquals(-3_000L, scenario.recordedNetCents)
    }

    @Test fun filtersSelectedYearAndDoesNotTurnMissingKindsIntoZeroTotals() {
        val rows = listOf(
            item("a", 2026, 1, PlanKind.EXPENSE, PlanStatus.ACTUAL, 700),
            item("b", 2027, 1, PlanKind.INCOME, PlanStatus.ACTUAL, 2_000),
        )

        val summary = summarizePlanYear(rows, 2026)
        val actual = summary.actual as PlanStatusAnalytics.Available
        assertEquals(1, actual.rowCount)
        assertEquals(1, actual.monthCount)
        assertNull(actual.recordedIncomeCents)
        assertEquals(700L, actual.recordedExpenseCents)
        assertNull(actual.recordedNetCents)
        assertEquals(PlanStatusAnalytics.Unavailable(PlanStatusAnalytics.Reason.NO_RECORDED_ROWS), summary.scenario)
    }

    @Test fun noRowsForSelectedYearAreUnavailableForBothStatuses() {
        val result = summarizePlanYear(emptyList(), 2040)
        val unavailable = PlanStatusAnalytics.Unavailable(PlanStatusAnalytics.Reason.NO_RECORDED_ROWS)
        assertEquals(unavailable, result.actual)
        assertEquals(unavailable, result.scenario)
    }

    @Test fun overflowingRecordedTotalsBecomeUnavailable() {
        val rows = listOf(
            item("a", 2026, 1, PlanKind.INCOME, PlanStatus.ACTUAL, Long.MAX_VALUE),
            item("b", 2026, 2, PlanKind.INCOME, PlanStatus.ACTUAL, 1),
        )

        val result = summarizePlanYear(rows, 2026).actual

        assertEquals(PlanStatusAnalytics.Unavailable(PlanStatusAnalytics.Reason.TOTAL_OVERFLOW), result)
    }

    private fun item(id: String, year: Int, month: Int, kind: PlanKind, status: PlanStatus, cents: Long) =
        PlanItem(id, YearMonth.of(year, month), id, kind, status, Money(cents), "synthetic test fixture")
}
