package xyz.grtlabs.pilastri.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A flow boundary is valued immediately before the flow is applied. Positive flows add
 * owner-provided capital to the measured portfolio; negative flows remove it. Aggregate
 * same-day external flows into one boundary. Fees and investment income are portfolio
 * performance, not external flows.
 */
data class ExternalFlowBoundary(
    val date: java.time.LocalDate,
    val valuationBeforeFlow: Valuation?,
    val externalFlowCents: Long
)

enum class PerformanceUnavailableReason {
    MISSING_START_VALUATION,
    MISSING_END_VALUATION,
    MISSING_FLOW_BOUNDARIES,
    MISSING_FLOW_BOUNDARY_VALUATION,
    INCOMPLETE_EXTERNAL_FLOWS,
    INVALID_VALUATION,
    INVALID_DATE_ORDER,
    INCONSISTENT_PILLAR,
    SCENARIO_VALUATION,
    ZERO_STARTING_VALUE,
    ZERO_POST_FLOW_VALUE,
    NEGATIVE_POST_FLOW_VALUE,
    TOO_MANY_FLOW_BOUNDARIES,
    NUMERIC_LIMIT
}

sealed interface TimeWeightedReturnResult {
    data class Available(
        /** Compounded wealth factor; 1.0 means no change and 1.1 means +10%. */
        val factor: BigDecimal,
        /** Compounded return expressed as percent, e.g. 10.00000000 means +10%. */
        val percent: BigDecimal,
        val subperiodCount: Int
    ) : TimeWeightedReturnResult

    data class Unavailable(val reason: PerformanceUnavailableReason) : TimeWeightedReturnResult
}

/**
 * Calculate a geometrically linked time-weighted return for one pillar.
 *
 * `start` is the valuation at the beginning of the measurement window, after any flows
 * on that date. Each boundary contains a valuation immediately before its aggregate
 * external flow; the flow is then added to that value to form the next subperiod's
 * starting value. `end` is the final valuation after all included flows. Therefore
 * flows on the start date belong before `start`; flows on the end date are outside
 * this date-granular measurement window and must be excluded.
 *
 * Callers must explicitly attest that all external flows in the interval are present.
 * Pass an empty list only when the source history confirms there were no such flows.
 * Investment return, fees and internal income stay in the portfolio and affect the
 * subperiod return. The function never estimates missing valuations or flow timing.
 */
fun timeWeightedReturn(
    start: Valuation?,
    flowBoundaries: List<ExternalFlowBoundary>?,
    end: Valuation?,
    externalFlowsComplete: Boolean
): TimeWeightedReturnResult {
    fun unavailable(reason: PerformanceUnavailableReason) = TimeWeightedReturnResult.Unavailable(reason)

    if (start == null) return unavailable(PerformanceUnavailableReason.MISSING_START_VALUATION)
    if (end == null) return unavailable(PerformanceUnavailableReason.MISSING_END_VALUATION)
    if (flowBoundaries == null) return unavailable(PerformanceUnavailableReason.MISSING_FLOW_BOUNDARIES)
    if (!externalFlowsComplete) return unavailable(PerformanceUnavailableReason.INCOMPLETE_EXTERNAL_FLOWS)
    if (flowBoundaries.size > MAX_FLOW_BOUNDARIES) return unavailable(PerformanceUnavailableReason.TOO_MANY_FLOW_BOUNDARIES)

    val allValuations = listOf(start, end) + flowBoundaries.mapNotNull { it.valuationBeforeFlow }
    if (allValuations.any { it.amount.cents < 0 || it.source.isBlank() }) {
        return unavailable(PerformanceUnavailableReason.INVALID_VALUATION)
    }
    if (allValuations.any { it.confidence == Confidence.SCENARIO }) {
        return unavailable(PerformanceUnavailableReason.SCENARIO_VALUATION)
    }
    if (allValuations.any { it.pillar != start.pillar }) {
        return unavailable(PerformanceUnavailableReason.INCONSISTENT_PILLAR)
    }
    if (!start.date.isBefore(end.date)) return unavailable(PerformanceUnavailableReason.INVALID_DATE_ORDER)

    var previousDate = start.date
    for (boundary in flowBoundaries) {
        val valuation = boundary.valuationBeforeFlow
            ?: return unavailable(PerformanceUnavailableReason.MISSING_FLOW_BOUNDARY_VALUATION)
        if (boundary.date != valuation.date || !boundary.date.isAfter(previousDate) || !boundary.date.isBefore(end.date)) {
            return unavailable(PerformanceUnavailableReason.INVALID_DATE_ORDER)
        }
        previousDate = boundary.date
    }

    if (start.amount.cents == 0L) return unavailable(PerformanceUnavailableReason.ZERO_STARTING_VALUE)

    var subperiodStart = start.amount.cents
    var linkedFactor = BigDecimal.ONE.setScale(FACTOR_SCALE)
    for (boundary in flowBoundaries) {
        val beforeFlow = boundary.valuationBeforeFlow!!.amount.cents
        val periodFactor = BigDecimal.valueOf(beforeFlow).divide(BigDecimal.valueOf(subperiodStart), FACTOR_SCALE, RoundingMode.HALF_EVEN)
        linkedFactor = linkedFactor.multiply(periodFactor).setScale(FACTOR_SCALE, RoundingMode.HALF_EVEN)
        if (linkedFactor.precision() > MAX_FACTOR_PRECISION) return unavailable(PerformanceUnavailableReason.NUMERIC_LIMIT)

        subperiodStart = try {
            Math.addExact(beforeFlow, boundary.externalFlowCents)
        } catch (_: ArithmeticException) {
            return unavailable(PerformanceUnavailableReason.NUMERIC_LIMIT)
        }
        if (subperiodStart < 0L) return unavailable(PerformanceUnavailableReason.NEGATIVE_POST_FLOW_VALUE)
        if (subperiodStart == 0L) return unavailable(PerformanceUnavailableReason.ZERO_POST_FLOW_VALUE)
    }

    val finalFactor = BigDecimal.valueOf(end.amount.cents).divide(BigDecimal.valueOf(subperiodStart), FACTOR_SCALE, RoundingMode.HALF_EVEN)
    linkedFactor = linkedFactor.multiply(finalFactor).setScale(FACTOR_SCALE, RoundingMode.HALF_EVEN)
    if (linkedFactor.precision() > MAX_FACTOR_PRECISION) return unavailable(PerformanceUnavailableReason.NUMERIC_LIMIT)
    val percent = linkedFactor.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
        .setScale(PERCENT_SCALE, RoundingMode.HALF_EVEN)
    return TimeWeightedReturnResult.Available(linkedFactor, percent, flowBoundaries.size + 1)
}

private const val FACTOR_SCALE = 18
private const val PERCENT_SCALE = 8
private const val MAX_FLOW_BOUNDARIES = 10_000
private const val MAX_FACTOR_PRECISION = 100
