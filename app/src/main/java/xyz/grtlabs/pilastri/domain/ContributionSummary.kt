package xyz.grtlabs.pilastri.domain

import java.math.BigDecimal
import java.time.YearMonth

/** One explicitly sourced monthly observation, already normalized to the app's comparable index series. */
data class ContributionIndex(val value: BigDecimal, val source: String)

enum class ContributionAmountUnavailableReason {
    NO_RECORDS,
    MISSING_JANUARY_2026_BASE,
    MISSING_MONTH_INDEX,
    INCOMPATIBLE_INDEX_SERIES,
    INVALID_INDEX,
    OVERFLOW
}

sealed interface ContributionAmount {
    data class Available(val amount: Money) : ContributionAmount
    data class Unavailable(val reason: ContributionAmountUnavailableReason) : ContributionAmount
}

data class ContributionCategorySummary(
    val nominal: ContributionAmount,
    val realJanuary2026: ContributionAmount
)

data class PillarContributionSummary(
    val personalGross: ContributionCategorySummary,
    val employerGross: ContributionCategorySummary,
    val withdrawals: ContributionCategorySummary,
    val income: ContributionCategorySummary,
    val fees: ContributionCategorySummary
)

data class ContributionSummary(
    val byPillar: Map<Int, PillarContributionSummary>,
    val allPillars: PillarContributionSummary
)

/**
 * Summarizes only explicitly reported ledger amounts. Withdrawals remain separate because their source
 * (personal, employer, or investment) cannot be known reliably. Real values use the exact monthly
 * observations and January 2026 base used by [Finance.inBaseYear] and the archive-level realContributions helper.
 * No event is interpolated, netted, or treated as investment return.
 */
fun summarizeContributions(
    events: List<LedgerEvent>,
    monthlyIndices: Map<YearMonth, ContributionIndex>
): ContributionSummary {
    fun summarize(pillar: Int?): PillarContributionSummary {
        fun one(type: EventType): ContributionCategorySummary {
            val matching = events.filter { it.type == type && (pillar == null || it.pillar == pillar) }
            if (matching.isEmpty()) {
                val unavailable = ContributionAmount.Unavailable(ContributionAmountUnavailableReason.NO_RECORDS)
                return ContributionCategorySummary(unavailable, unavailable)
            }
            val nominal = try {
                ContributionAmount.Available(Money(matching.fold(0L) { total, event -> Math.addExact(total, event.amount.cents) }))
            } catch (_: ArithmeticException) {
                ContributionAmount.Unavailable(ContributionAmountUnavailableReason.OVERFLOW)
            }
            return ContributionCategorySummary(nominal, realAmount(matching, monthlyIndices))
        }
        return PillarContributionSummary(
            personalGross = one(EventType.PERSONAL_CONTRIBUTION),
            employerGross = one(EventType.EMPLOYER_CONTRIBUTION),
            withdrawals = one(EventType.WITHDRAWAL),
            income = one(EventType.INCOME),
            fees = one(EventType.FEE)
        )
    }
    return ContributionSummary((1..4).associateWith(::summarize), summarize(null))
}

private fun realAmount(
    events: List<LedgerEvent>,
    indices: Map<YearMonth, ContributionIndex>
): ContributionAmount {
    val baseMonth = YearMonth.of(2026, 1)
    val base = indices[baseMonth]
        ?: return ContributionAmount.Unavailable(ContributionAmountUnavailableReason.MISSING_JANUARY_2026_BASE)
    if (indices.values.any { it.value.signum() <= 0 || it.source.isBlank() }) {
        return ContributionAmount.Unavailable(ContributionAmountUnavailableReason.INVALID_INDEX)
    }
    if (indices.values.map { it.source.trim() }.distinct().size != 1) {
        return ContributionAmount.Unavailable(ContributionAmountUnavailableReason.INCOMPATIBLE_INDEX_SERIES)
    }
    val datedMonths = events.map { YearMonth.from(it.date) }
    if (datedMonths.any { it !in indices }) {
        return ContributionAmount.Unavailable(ContributionAmountUnavailableReason.MISSING_MONTH_INDEX)
    }
    return try {
        val total = events.fold(0L) { sum, event ->
            val index = indices.getValue(YearMonth.from(event.date))
            val deflated = Finance.inBaseYear(event.amount, base.value, index.value)
                ?: return ContributionAmount.Unavailable(ContributionAmountUnavailableReason.OVERFLOW)
            Math.addExact(sum, deflated.cents)
        }
        ContributionAmount.Available(Money(total))
    } catch (_: ArithmeticException) {
        ContributionAmount.Unavailable(ContributionAmountUnavailableReason.OVERFLOW)
    }
}
