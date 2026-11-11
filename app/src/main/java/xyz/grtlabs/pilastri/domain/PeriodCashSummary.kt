package xyz.grtlabs.pilastri.domain

import java.time.LocalDate

enum class CashSummaryPeriod { DAY, MONTH, YEAR }

enum class PeriodCashUnavailableReason { NO_RECORDS, OVERFLOW }

sealed interface PeriodCashTotal {
    data class Available(val amount: Money, val recordCount: Int) : PeriodCashTotal
    data class Unavailable(val reason: PeriodCashUnavailableReason, val recordCount: Int) : PeriodCashTotal
}

/**
 * Reported cash income and costs in a trailing, inclusive date window. These are not investment returns
 * or complete actual totals. Window starts are one day after the prior boundary, so adjacent periods
 * do not count their shared boundary date twice.
 */
data class PeriodCashSummary(
    val pillar: Int,
    val period: CashSummaryPeriod,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val income: PeriodCashTotal,
    val fees: PeriodCashTotal
)

/** Pure period calculation over reported ledger events. Only INCOME and FEE records are included. */
fun periodCashSummary(
    events: List<LedgerEvent>,
    pillar: Int,
    period: CashSummaryPeriod,
    asOf: LocalDate
): PeriodCashSummary {
    require(pillar in 1..4) { "Pilastro non valido." }
    val start = when (period) {
        CashSummaryPeriod.DAY -> asOf
        CashSummaryPeriod.MONTH -> asOf.minusMonths(1).plusDays(1)
        CashSummaryPeriod.YEAR -> asOf.minusYears(1).plusDays(1)
    }
    fun total(type: EventType): PeriodCashTotal {
        val matching = events.filter { it.pillar == pillar && it.type == type && it.date in start..asOf }
        if (matching.isEmpty()) return PeriodCashTotal.Unavailable(PeriodCashUnavailableReason.NO_RECORDS, 0)
        return try {
            val cents = matching.fold(0L) { sum, event -> Math.addExact(sum, event.amount.cents) }
            PeriodCashTotal.Available(Money(cents), matching.size)
        } catch (_: ArithmeticException) {
            PeriodCashTotal.Unavailable(PeriodCashUnavailableReason.OVERFLOW, matching.size)
        }
    }
    return PeriodCashSummary(
        pillar = pillar,
        period = period,
        startDate = start,
        endDate = asOf,
        income = total(EventType.INCOME),
        fees = total(EventType.FEE)
    )
}
