package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/*
 * Spese's period (COUNCIL.md "Council 5"; DATA-CONTRACT "Council 5 data rules"). Spese reads a rolling window of one calendar
 * month back, derived and never stored: t = min(today, coverageTo); window k = (t − (k+1) months, t − k months], 28–31 days.
 * Counting rules are those of [monthSpending] (ReadModels.kt), keyed by the window instead of the calendar month and limited
 * to the covered days; installment parts count on the day they are paid. Mese, Rate and F24 stay calendar.
 */

/** Window [k] (0 = "Ultimo mese"): [from]..[to] inclusive; [coveredFrom] is its first covered day, null when none is covered. */
data class SpendWindow(val k: Int, val from: LocalDate, val to: LocalDate, val coveredFrom: LocalDate?) {
    /** The first day the figures cover: the window's start, or the first covered day when the data starts later (the header shows this range, user 2026-11-09). */
    val shownFrom: LocalDate get() = coveredFrom ?: from
    /** N, 28–31. */
    val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
    /** c: covered days, [coveredFrom] to the end (the end is always covered: it is at most t). */
    val covered: Int get() = coveredFrom?.let { (ChronoUnit.DAYS.between(it, to) + 1).toInt() } ?: 0
    val full: Boolean get() = covered == days
    val partial: Boolean get() = covered in 1 until days
    operator fun contains(d: LocalDate): Boolean = !d.isBefore(from) && !d.isAfter(to)
    fun isCovered(d: LocalDate): Boolean = coveredFrom != null && !d.isBefore(coveredFrom) && !d.isAfter(to)
    /** Day 1..N of [d]. */
    fun dayOf(d: LocalDate): Int = (ChronoUnit.DAYS.between(from, d) + 1).toInt()
}

object SpendWindows {
    private const val MAX = 240

    /** t, the last covered day; null without any liquidity coverage (n/d). */
    fun anchor(today: LocalDate, cover: Coverage): LocalDate? = cover.to?.let { minOf(it, today) }

    /** Window [k] from t: its end is t − k months and its start the day after t − (k+1) months, so windows never gap or overlap. */
    fun window(t: LocalDate, k: Int, coverageFrom: LocalDate?): SpendWindow {
        require(k >= 0)
        val to = t.minusMonths(k.toLong())
        val from = t.minusMonths(k + 1L).plusDays(1)
        return SpendWindow(k, from, to, coverageFrom?.let { maxOf(it, from) }?.takeIf { !it.isAfter(to) })
    }

    /** How many windows reach back to [earliest] (window count − 1 holds it); at least one. */
    fun count(t: LocalDate, earliest: LocalDate?): Int {
        if (earliest == null || !earliest.isBefore(t)) return 1
        var k = 0
        while (k < MAX && !t.minusMonths(k + 1L).isBefore(earliest)) k++
        return k + 1
    }

    /** Each window ends in its own calendar month: the window whose end falls in [month] (0 for t's month or later). */
    fun indexFor(t: LocalDate, month: YearMonth): Int = ChronoUnit.MONTHS.between(month, YearMonth.from(t)).toInt().coerceAtLeast(0)

    /** The calendar month a window ends in (the calendar's and the category page's handle on a window). */
    fun monthOf(t: LocalDate, k: Int): YearMonth = YearMonth.from(t.minusMonths(k.toLong()))
}

/* ---------- The counted lines ---------- */

/** One counted line: an outflow (positive cents) or a refund netted into its purchase's category (negative cents). */
data class SpendEntry(val movement: ExternalMovement, val categoryId: String?, val cents: Long)

/** Spese's counted lines and income credits, computed once per archive (the window views filter by date). */
class SpendLedger internal constructor(
    val entries: List<SpendEntry>, val credits: List<ExternalMovement>, val rules: List<IncomeRule>,
    val states: Map<String, InstallmentPaymentState>, val cover: Coverage, val earliest: LocalDate?,
) {
    /** Per category over the covered days of [w]: refunds net into their category, a category never goes below 0. Null when nothing is covered. */
    fun byCategory(w: SpendWindow): Map<String?, Long>? {
        if (w.covered == 0) return null
        return entries.filter { w.isCovered(it.movement.date) }.groupBy { it.categoryId }
            .mapValues { (_, rows) -> rows.sumOf { it.cents }.coerceAtLeast(0L) }.filterValues { it > 0 }
    }

    /** The spending movements of [categoryId] in [w]'s covered days, newest first. */
    fun movements(w: SpendWindow, categoryId: String?): List<SpendEntry> =
        entries.filter { it.categoryId == categoryId && w.isCovered(it.movement.date) }.sortedByDescending { it.movement.date }
}

private val notSpendingRoles = setOf(MovementRole.OWN_TRANSFER, MovementRole.FUND_CONTRIBUTION, MovementRole.SALARY, MovementRole.INCOME)
private val payoutOnlyProviders = setOf(ImportProvider.PAYPAL, ImportProvider.TRADE_REPUBLIC)

/** The same filters as [monthSpending] (own transfers, suggested pairs, trades, ignored rows, payouts excluded; refunds net). */
fun Archive.spendLedger(): SpendLedger {
    val liquidity = liquidityIds()
    val transferIds = transfers.map { it.id }.toSet()
    val suggested = transferPairs().keys
    val counted = externalMovements.filter {
        it.accountId in liquidity && it.status != ExternalStatus.IGNORED && it.linkedRecordId !in transferIds && it.id !in suggested &&
            it.kind != ExternalKind.BUY && it.kind != ExternalKind.SELL
    }
    val rules = planning.incomeRules
    val credits = counted.filter { it.signedCents > 0 && (it.role == MovementRole.SALARY || it.role == MovementRole.INCOME || rules.any { r -> r.matches(it) }) }
    val states = installmentPaymentStates()
    val spend = counted.filter { it.signedCents < 0 && it.role !in notSpendingRoles && !(it.kind == ExternalKind.WITHDRAWAL && it.provider in payoutOnlyProviders) }
        .map { SpendEntry(it, spendCategoryOf(it, states), -it.signedCents) }
    val refunds = counted.filter { it.isRefund() }.map { r ->
        val purchase = externalMovements.filter { it.accountId == r.accountId && it.signedCents < 0 && -it.signedCents >= r.signedCents && !it.date.isAfter(r.date) && !it.date.isBefore(r.date.minusDays(90)) }
            .maxByOrNull { it.date }
        SpendEntry(r, r.categoryId ?: purchase?.let { spendCategoryOf(it, states) }, -r.signedCents)
    }
    val cover = liquidityCoverage()
    return SpendLedger(spend + refunds, credits, rules, states, cover, cover.from ?: counted.minOfOrNull { it.date })
}

/* ---------- Income (Council 5 e) ---------- */

sealed interface IncomeCause {
    val rule: IncomeRule
    /** No credit in the window: the rule's next day range ([from]..[to]) on or after the window's end. */
    data class Expected(override val rule: IncomeRule, val from: LocalDate, val to: LocalDate) : IncomeCause
    /** More than one credit in the window. */
    data class Credits(override val rule: IncomeRule, val count: Int) : IncomeCause
}

/**
 * Entrate of a window: [cents] null means "—" (never a partial sum, never 0). With confirmed rules it is complete only when each
 * rule has exactly one credit ([matched], the caption's dates); otherwise [causes] name what is missing. Without rules: the
 * income-classified credits ([credits]), null when none.
 */
data class WindowIncome(val cents: Long?, val matched: List<Pair<IncomeRule, LocalDate>>, val causes: List<IncomeCause>, val credits: Int)

/** The next occurrence of [r]'s day range that ends on or after [end]. */
internal fun expectedRange(r: IncomeRule, end: LocalDate): Pair<LocalDate, LocalDate> {
    fun at(m: YearMonth, d: Int) = m.atDay(minOf(d, m.lengthOfMonth()))
    val base = YearMonth.from(end)
    return (-1L..2L).map { i ->
        val m = base.plusMonths(i)
        if (r.dayFrom <= r.dayTo) at(m, r.dayFrom) to at(m, r.dayTo) else at(m, r.dayFrom) to at(m.plusMonths(1), r.dayTo)
    }.first { !it.second.isBefore(end) }
}

internal fun windowIncome(w: SpendWindow, ledger: SpendLedger): WindowIncome {
    val inWindow = ledger.credits.filter { w.isCovered(it.date) }
    val confirmed = ledger.rules.filter { it.confirmedAt != null }
    if (confirmed.isEmpty()) return WindowIncome(inWindow.takeIf { it.isNotEmpty() }?.sumOf { it.signedCents }, emptyList(), emptyList(), inWindow.size)
    val per = confirmed.map { r -> r to inWindow.filter { r.matches(it) } }
    if (w.covered > 0 && per.all { it.second.size == 1 }) return WindowIncome(inWindow.sumOf { it.signedCents }, per.map { (r, m) -> r to m.single().date }, emptyList(), inWindow.size)
    val causes = per.filter { it.second.size != 1 }.map { (r, m) ->
        if (m.isEmpty()) expectedRange(r, w.to).let { (a, b) -> IncomeCause.Expected(r, a, b) } else IncomeCause.Credits(r, m.size)
    }
    return WindowIncome(null, emptyList(), causes, inWindow.size)
}

/* ---------- Budgets, pace and the overspend signal (Council 5 c, d) ---------- */

/** A category's budget for a window: as typed, or the derived [estimate] ("stima"). */
data class WindowBudget(val cents: Long, val estimate: Boolean)

object SpendSignal {
    const val ON = 1.10
    const val OFF = 1.05
    const val MIN_COVERED_DAYS = 14
    const val MAX_SYNC_AGE_DAYS = 3L
    const val TOTAL_KEY = "total"
    const val SPARK_WINDOWS = 6
    const val SPARK_MIN_POINTS = 3
    const val DERIVED_WINDOWS = 3

    /** spend ÷ pace, pace = budget × c/N; null without a positive budget or covered days. */
    fun ratio(spent: Long, budget: Long?, covered: Int, days: Int): Double? =
        if (budget == null || budget <= 0 || covered <= 0 || days <= 0) null else spent / (budget.toDouble() * covered / days)

    /** On at ≥ 1.10 (c ≥ 14, a budget, last sync ≤ 3 days); once on ([wasOn]), it clears below 1.05. No cached state: plain 1.10. */
    fun on(ratio: Double?, covered: Int, t: LocalDate, today: LocalDate, wasOn: Boolean?): Boolean {
        if (ratio == null || covered < MIN_COVERED_DAYS || t.isBefore(today.minusDays(MAX_SYNC_AGE_DAYS))) return false
        return ratio >= ON || (wasOn == true && ratio >= OFF)
    }
}

/** Median of the last three consecutive fully covered windows; none (null) if any is not full or the median is 0. */
internal fun derivedBudget(values: List<Long?>): Long? {
    if (values.size < SpendSignal.DERIVED_WINDOWS || values.any { it == null }) return null
    return values.map { it!! }.sorted()[values.size / 2].takeIf { it > 0 }
}

data class SpendCategoryLine(
    val id: String?, val name: String, val spentCents: Long, val budget: WindowBudget?, val essential: Essential,
    /** The last [SpendSignal.SPARK_WINDOWS] windows ending at this one, oldest first; null = not fully covered (a gap, never 0). */
    val spark: List<Long?>,
    val ratio: Double?, val over: Boolean, val paceCents: Long?,
) {
    val sparkShown: Boolean get() = spark.count { it != null } >= SpendSignal.SPARK_MIN_POINTS
    val sparkMax: Long? get() = if (sparkShown) spark.filterNotNull().max() else null
    val fraction: Float? get() = budget?.cents?.takeIf { it > 0 }?.let { (spentCents.toDouble() / it).toFloat() }
    val overPaceCents: Long get() = paceCents?.let { (spentCents - it).coerceAtLeast(0L) } ?: 0L
}

data class SpendWindowView(
    val window: SpendWindow, val t: LocalDate, val stale: Boolean,
    /** Spesa over the covered days; null when nothing is covered. */
    val spentCents: Long?,
    val income: WindowIncome,
    /** Sum of the category budgets (typed or "stima"); null: "budget n/d". */
    val budget: WindowBudget?,
    /** Like with like: spend in the budgeted categories only. */
    val budgetedSpentCents: Long,
    val ratio: Double?, val over: Boolean,
    /** Day 1..N cumulative spend; null before the first covered day. */
    val pace: List<Long?>,
    val categories: List<SpendCategoryLine>,
) {
    val savedCents: Long? get() = if (income.cents != null && spentCents != null) income.cents - spentCents else null
    val savedFraction: Double? get() = savedCents?.let { s -> income.cents?.takeIf { it > 0 }?.let { s.toDouble() / it } }
    val budgetFraction: Double? get() = spentCents?.let { s -> budget?.cents?.takeIf { it > 0 }?.let { s.toDouble() / it } }
    /** The budget's share of the covered days. */
    val paceCents: Long? get() = budget?.let { it.cents * window.covered / window.days }
    /** "Oltre il ritmo +N%": spend ÷ pace − 1. */
    val overPercent: Int? get() = ratio?.let { kotlin.math.round((it - 1) * 100).toInt() }
    /** Spend above the pace (like with like), only in the over state. */
    val abovePaceCents: Long? get() = if (!over) null else paceCents?.let { (budgetedSpentCents - it).coerceAtLeast(0L) }
    /** "Dove limare": categories not marked essential above their own pace, largest first; only in the over state. */
    val trim: List<SpendCategoryLine> get() = if (!over) emptyList() else
        categories.filter { it.id != null && it.essential != Essential.ESSENTIAL && it.overPaceCents > 0 }.sortedByDescending { it.overPaceCents }
}

/** Spese's window [k]; null without any liquidity coverage. [wasOn] reads the UI cache (key: [SpendSignal.TOTAL_KEY] or a category id), for k = 0 only. */
fun Archive.spendWindowView(today: LocalDate, k: Int, ledger: SpendLedger = spendLedger(), wasOn: (String) -> Boolean? = { null }): SpendWindowView? {
    val t = SpendWindows.anchor(today, ledger.cover) ?: return null
    val cache = HashMap<Int, Map<String?, Long>?>()
    fun win(j: Int) = SpendWindows.window(t, j, ledger.cover.from)
    fun totals(j: Int) = cache.getOrPut(j) { ledger.byCategory(win(j)) }
    val w = win(k)
    val by = totals(k)
    val month = YearMonth.from(w.to)
    val categories = planning.categories.filter { it.kind == CategoryKind.SPEND }
    fun budgetOf(id: String): WindowBudget? =
        budgetFor(id, month)?.let { WindowBudget(it, false) }
            ?: derivedBudget((1..SpendSignal.DERIVED_WINDOWS).map { j -> win(k + j).takeIf { it.full }?.let { totals(k + j)?.get(id) ?: 0L } })?.let { WindowBudget(it, true) }
    fun spark(id: String?): List<Long?> = (SpendSignal.SPARK_WINDOWS - 1 downTo 0).map { j -> if (win(k + j).full) totals(k + j)?.get(id) ?: 0L else null }
    val live = k == 0
    val ids = (categories.map { it.id } + (by?.keys?.filterNotNull() ?: emptyList())).distinct()
    val lines = ids.mapNotNull { id ->
        val spent = by?.get(id) ?: 0L
        val b = budgetOf(id)
        if (spent == 0L && b == null) return@mapNotNull null
        val r = if (by == null) null else SpendSignal.ratio(spent, b?.cents, w.covered, w.days)
        val cat = categories.firstOrNull { it.id == id }
        SpendCategoryLine(id, categoryName(id), spent, b, cat?.essential ?: Essential.UNSET, spark(id), r,
            SpendSignal.on(r, w.covered, t, today, if (live) wasOn(id) else null), b?.let { it.cents * w.covered / w.days })
    } + (by?.get(null)?.let { listOf(SpendCategoryLine(null, categoryName(null), it, null, Essential.UNSET, spark(null), null, false, null)) } ?: emptyList())
    val budgeted = lines.filter { it.budget != null && it.id != null }
    val budget = budgeted.takeIf { it.isNotEmpty() }?.let { b -> WindowBudget(b.sumOf { it.budget!!.cents }, b.any { it.budget!!.estimate }) }
    val budgetedSpent = budgeted.sumOf { it.spentCents }
    val ratio = if (by == null) null else SpendSignal.ratio(budgetedSpent, budget?.cents, w.covered, w.days)
    val over = SpendSignal.on(ratio, w.covered, t, today, if (live) wasOn(SpendSignal.TOTAL_KEY) else null)
    val sorted = lines.sortedWith(compareBy<SpendCategoryLine> { it.id == null }.thenBy { !it.over }.thenByDescending { it.spentCents })
    return SpendWindowView(w, t, t.isBefore(today.minusDays(SpendSignal.MAX_SYNC_AGE_DAYS)), by?.values?.sum(), windowIncome(w, ledger), budget,
        budgetedSpent, ratio, over, paceSeries(w, ledger), sorted)
}

/** Cumulative spend per day 1..N from the first covered day (earlier days null); each category clamped at 0 like the totals. */
internal fun paceSeries(w: SpendWindow, ledger: SpendLedger): List<Long?> {
    val first = w.coveredFrom ?: return List(w.days) { null }
    val byDay = ledger.entries.filter { w.isCovered(it.movement.date) }.groupBy { w.dayOf(it.movement.date) }
    val running = HashMap<String?, Long>()
    val start = w.dayOf(first)
    return (1..w.days).map { d ->
        if (d < start) null
        else {
            byDay[d]?.forEach { e -> running[e.categoryId] = (running[e.categoryId] ?: 0L) + e.cents }
            running.values.sumOf { it.coerceAtLeast(0L) }
        }
    }
}

/** Windows Spese can show, oldest at index 0: index i is window k = count − 1 − i. */
fun SpendLedger.windowCount(today: LocalDate): Int = SpendWindows.anchor(today, cover)?.let { SpendWindows.count(it, earliest) } ?: 1

/** The live signal (window 0), for the Spese dot (3b-03). */
fun Archive.spendSignalNow(today: LocalDate, wasOn: (String) -> Boolean?): Boolean = spendWindowView(today, 0, wasOn = wasOn)?.over == true

/* ---------- Da controllare (3b-04) ---------- */

enum class CheckKind { TRANSFER, DUPLICATE, AMBIGUOUS }

/** One "Da controllare" item: a possible own transfer (both sides), a possible duplicate or an outflow held back as ambiguous. */
data class CheckItem(val kind: CheckKind, val movements: List<ExternalMovement>) {
    val key: String get() = kind.name + ":" + movements.joinToString(",") { it.id }
    val date: LocalDate get() = movements.minOf { it.date }
    val cents: Long get() = movements.maxOf { kotlin.math.abs(it.signedCents) }
    /** The outflow side (the one a decision applies to). */
    val outflow: ExternalMovement? get() = movements.firstOrNull { it.signedCents < 0 } ?: movements.firstOrNull()
}

/** The items behind [toCheck]'s count, in the same order of kinds (duplicates, transfers, ambiguous). */
fun Archive.toCheckItems(heldAmbiguous: (String) -> Boolean = { false }): List<CheckItem> {
    val q = reviewQueue()
    fun open(m: ExternalMovement) = m.status == ExternalStatus.PENDING && m.categoryId == null
    val dups = q.filter { it.label == "Possibile duplicato" && it.movements.any(::open) }
    val transfers = q.filter { it.label != "Possibile duplicato" && it.decision is ExternalDecision.AsTransfer && it.movements.any(::open) }
    val grouped = (dups + transfers).flatMap { g -> g.movements.map { it.id } }.toSet() + transferPairs().keys
    val ambiguous = externalMovements.filter { open(it) && it.signedCents < 0 && it.id !in grouped && planning.categorize(it).first == null && heldAmbiguous(it.id) }
    return dups.map { CheckItem(CheckKind.DUPLICATE, it.movements) } + transfers.map { CheckItem(CheckKind.TRANSFER, it.movements) } +
        ambiguous.map { CheckItem(CheckKind.AMBIGUOUS, listOf(it)) }
}

/* ---------- Likely categories (lazy-UX #4) ---------- */

/** Up to [n] spending categories most likely for [m]: the rules' choice, then the same merchant's history, then the most used. */
fun Archive.likelyCategories(m: ExternalMovement, n: Int = 3): List<String> {
    val known = planning.categories.filter { it.kind == CategoryKind.SPEND }.map { it.id }
    val key = m.merchantKey ?: merchantKey(m.description)
    val history = externalMovements.filter { it.id != m.id && it.categoryId != null && it.signedCents < 0 }
    val sameMerchant = history.filter { (it.merchantKey ?: merchantKey(it.description)) == key }.groupingBy { it.categoryId!! }.eachCount()
    val overall = history.groupingBy { it.categoryId!! }.eachCount()
    return (listOfNotNull(m.categoryId, planning.categorize(m).first) + sameMerchant.entries.sortedByDescending { it.value }.map { it.key } +
        overall.entries.sortedByDescending { it.value }.map { it.key } + known).distinct().filter { it in known }.take(n)
}

/* ---------- Rate: the next 12 calendar months (3a-07, 3a-08) ---------- */

/** One calendar month of parts per plan: known amounts of confirmed plans, of plans still to confirm, and parts without an amount. */
data class RateMonth(val month: YearMonth, val confirmed: Map<String, Long>, val pending: Map<String, Long>, val unknown: Int) {
    val any: Boolean get() = confirmed.isNotEmpty() || pending.isNotEmpty() || unknown > 0
}

/** A plan's row: the next part's amount ("importo al mese"), the month of its last part (null: count or dates unknown, "n/d"). */
data class RatePlanLine(val plan: InstallmentPlan, val monthlyCents: Long?, val until: YearMonth?, val pending: Boolean, val hue: Int)

data class RateOutlook(val months: List<RateMonth>, val plans: List<RatePlanLine>) {
    /** The last month with any part: nothing is drawn after it. */
    val lastKnown: Int get() = months.indexOfLast { it.any }
    private fun sum(n: Int, pending: Boolean) = months.take(n).sumOf { (if (pending) it.pending else it.confirmed).values.sum() }
    val next6: Long get() = sum(6, false)
    val next6Pending: Long get() = sum(6, true)
    val next12: Long get() = sum(12, false)
    val next12Pending: Long get() = sum(12, true)
    val unknown12: Int get() = months.sumOf { it.unknown }
    fun hueOf(planId: String): Int = plans.firstOrNull { it.plan.id == planId }?.hue ?: RATE_OTHER_HUE
}

const val RATE_HUES = 4
const val RATE_OTHER_HUE = RATE_HUES

/** From this month while it still has parts due from [today], else from next month; plans by 12-month total, four hues plus "altri". */
fun Archive.rateOutlook(today: LocalDate): RateOutlook {
    val now = YearMonth.from(today)
    val start = if (dueInstallments(today, now.atEndOfMonth()).isNotEmpty()) now else now.plusMonths(1)
    val months = (0L until 12L).map { i ->
        val m = start.plusMonths(i)
        val due = dueInstallments(maxOf(m.atDay(1), today.takeIf { m == now } ?: m.atDay(1)), m.atEndOfMonth())
        val euro = due.filter { it.plan.currency == "EUR" && it.part.cents != null }
        fun group(p: Boolean) = euro.filter { (it.plan.review == PlanReview.PENDING) == p }.groupBy { it.plan.id }.mapValues { (_, v) -> v.sumOf { it.part.cents!! } }
        RateMonth(m, group(false), group(true), due.size - euro.size)
    }
    val totals = months.flatMap { it.confirmed.entries + it.pending.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    val plans = installments.filter { it.review != PlanReview.IGNORED && (it.active || it.review == PlanReview.PENDING) }
        .sortedWith(compareByDescending<InstallmentPlan> { totals[it.id] ?: 0L }.thenBy { it.id })
    val lines = plans.mapIndexed { i, p ->
        val next = p.nextOpen()
        val complete = p.count != null && p.installments.size >= p.count && p.installments.all { it.dueDate != null }
        RatePlanLine(p, next?.cents, if (complete) p.installments.maxOf { it.dueDate!! }.let(YearMonth::from) else null, p.review == PlanReview.PENDING,
            if (i < RATE_HUES && (totals[p.id] ?: 0L) > 0) i else RATE_OTHER_HUE)
    }
    return RateOutlook(months, lines)
}
