package xyz.grtlabs.pilastri.data

/** Totals of recorded plan rows only; this is neither a complete budget nor a forecast. */
data class PlanYearAnalytics(
    val year: Int,
    val actual: PlanStatusAnalytics,
    val scenario: PlanStatusAnalytics,
)

sealed interface PlanStatusAnalytics {
    data class Available(
        val rowCount: Int,
        val monthCount: Int,
        /** Null means no income rows were recorded; it does not mean zero income. */
        val recordedIncomeCents: Long?,
        /** Null means no expense rows were recorded; it does not mean zero expenses. */
        val recordedExpenseCents: Long?,
        /** Available only when at least one income row and one expense row are recorded. */
        val recordedNetCents: Long?,
    ) : PlanStatusAnalytics

    data class Unavailable(val reason: Reason) : PlanStatusAnalytics

    enum class Reason { NO_RECORDED_ROWS, TOTAL_OVERFLOW }
}

/** Summarizes only rows matching [year], keeping actual and scenario records separate. */
fun summarizePlanYear(items: List<PlanItem>, year: Int): PlanYearAnalytics = PlanYearAnalytics(
    year = year,
    actual = summarizePlanStatus(items, year, PlanStatus.ACTUAL),
    scenario = summarizePlanStatus(items, year, PlanStatus.SCENARIO),
)

private fun summarizePlanStatus(items: List<PlanItem>, year: Int, status: PlanStatus): PlanStatusAnalytics {
    val rows = items.filter { it.month.year == year && it.status == status }
    if (rows.isEmpty()) return PlanStatusAnalytics.Unavailable(PlanStatusAnalytics.Reason.NO_RECORDED_ROWS)

    return try {
        val incomes = rows.filter { it.kind == PlanKind.INCOME }
        val expenses = rows.filter { it.kind == PlanKind.EXPENSE }
        val incomeCents = incomes.takeIf { it.isNotEmpty() }?.sumCents()
        val expenseCents = expenses.takeIf { it.isNotEmpty() }?.sumCents()
        val netCents = if (incomeCents != null && expenseCents != null) {
            Math.subtractExact(incomeCents, expenseCents)
        } else null
        PlanStatusAnalytics.Available(
            rowCount = rows.size,
            monthCount = rows.map { it.month }.distinct().size,
            recordedIncomeCents = incomeCents,
            recordedExpenseCents = expenseCents,
            recordedNetCents = netCents,
        )
    } catch (_: ArithmeticException) {
        PlanStatusAnalytics.Unavailable(PlanStatusAnalytics.Reason.TOTAL_OVERFLOW)
    }
}

private fun List<PlanItem>.sumCents(): Long = fold(0L) { total, item ->
    Math.addExact(total, item.amount.cents)
}
