package xyz.grtlabs.pilastri.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PerformanceTest {
    private val startDate = LocalDate.of(2026, 1, 1)
    private fun valuation(date: LocalDate, cents: Long, confidence: Confidence = Confidence.DOCUMENT) =
        Valuation(date, 4, Money(cents), "independent statement", confidence)

    private fun available(result: TimeWeightedReturnResult): TimeWeightedReturnResult.Available =
        result as TimeWeightedReturnResult.Available

    @Test fun cashDepositIsRemovedFromReturnByValuingImmediatelyBeforeTheFlow() {
        val flowDate = startDate.plusMonths(1)
        val result = available(timeWeightedReturn(
            valuation(startDate, 10_000),
            listOf(ExternalFlowBoundary(flowDate, valuation(flowDate, 11_000), 5_000)),
            valuation(startDate.plusMonths(2), 17_600),
            externalFlowsComplete = true
        ))

        assertEquals(BigDecimal("1.210000000000000000"), result.factor)
        assertEquals(BigDecimal("21.00000000"), result.percent)
        assertEquals(2, result.subperiodCount)
    }

    @Test fun subperiodReturnsAreGeometricallyCompounded() {
        val middleDate = startDate.plusMonths(1)
        val result = available(timeWeightedReturn(
            valuation(startDate, 10_000),
            listOf(ExternalFlowBoundary(middleDate, valuation(middleDate, 11_000), 0)),
            valuation(startDate.plusMonths(2), 13_200),
            externalFlowsComplete = true
        ))

        assertEquals(BigDecimal("1.320000000000000000"), result.factor)
        assertEquals(BigDecimal("32.00000000"), result.percent)
    }

    @Test fun lossIsReportedAsNegativeReturn() {
        val result = available(timeWeightedReturn(
            valuation(startDate, 10_000), emptyList(), valuation(startDate.plusDays(1), 9_000),
            externalFlowsComplete = true
        ))

        assertEquals(BigDecimal("0.900000000000000000"), result.factor)
        assertEquals(BigDecimal("-10.00000000"), result.percent)
    }

    @Test fun missingFlowBoundaryAndIncompleteEventHistoryAreUnavailable() {
        val date = startDate.plusDays(1)
        assertEquals(
            PerformanceUnavailableReason.MISSING_FLOW_BOUNDARY_VALUATION,
            (timeWeightedReturn(valuation(startDate, 10_000), listOf(ExternalFlowBoundary(date, null, 100)),
                valuation(date.plusDays(1), 10_100), true) as TimeWeightedReturnResult.Unavailable).reason
        )
        assertEquals(
            PerformanceUnavailableReason.INCOMPLETE_EXTERNAL_FLOWS,
            (timeWeightedReturn(valuation(startDate, 10_000), emptyList(), valuation(date, 10_000), false)
                as TimeWeightedReturnResult.Unavailable).reason
        )
    }

    @Test fun zeroStartingValueAndFlowArithmeticOverflowAreUnavailable() {
        val date = startDate.plusDays(1)
        assertEquals(
            PerformanceUnavailableReason.ZERO_STARTING_VALUE,
            (timeWeightedReturn(valuation(startDate, 0), emptyList(), valuation(date, 100), true)
                as TimeWeightedReturnResult.Unavailable).reason
        )

        val maximum = Long.MAX_VALUE
        assertEquals(
            PerformanceUnavailableReason.NUMERIC_LIMIT,
            (timeWeightedReturn(valuation(startDate, maximum),
                listOf(ExternalFlowBoundary(date, valuation(date, maximum), 1)),
                valuation(date.plusDays(1), maximum), true) as TimeWeightedReturnResult.Unavailable).reason
        )
    }

    @Test fun scenarioValuationsCannotBeReportedAsActualPerformance() {
        val result = timeWeightedReturn(
            valuation(startDate, 10_000, Confidence.SCENARIO), emptyList(),
            valuation(startDate.plusDays(1), 11_000), true
        ) as TimeWeightedReturnResult.Unavailable

        assertEquals(PerformanceUnavailableReason.SCENARIO_VALUATION, result.reason)
    }
}
