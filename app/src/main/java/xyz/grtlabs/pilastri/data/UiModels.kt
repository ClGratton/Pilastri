package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.domain.*

/*
 * Read models and small, validated edits for the redone screens (concepts 9 and 10, 2026-10-31). Pure functions over the
 * archive; every amount keeps "missing is not zero": null means unknown and the screen says why.
 */

val PillarNames = listOf("Liquidità", "Emergenze", "Progetti", "Lungo termine")
fun pillarName(p: Int) = PillarNames.getOrElse(p - 1) { "Pilastro $p" }
fun pillarShort(p: Int) = if (p == 4) "Lungo t." else pillarName(p)

const val SamplePrefix = "Dati di esempio"

/** True when the archive was filled by the debug sample loader: every screen then says "Dati di esempio". */
val Archive.isSample: Boolean get() = accounts.isNotEmpty() && accounts.all { it.source.startsWith(SamplePrefix) }

val Archive.isEmpty: Boolean get() = accounts.isEmpty() && events.isEmpty() && valuations.isEmpty() && externalMovements.isEmpty() && installments.isEmpty()

/* ---------- Pillar values over time ---------- */

/** The pillar value as it was known on [date], with the same rules as [pillarValue] restricted to records up to that date. */
fun Archive.pillarValueAt(pillar: Int, date: LocalDate): Long? = pillarValue(pillar, asOf = date).centsOrNull()

internal fun Archive.pillarValueDates(pillar: Int): List<LocalDate> {
    val accountIds = accounts.filter { it.pillar == pillar }.map { it.id }.toSet()
    return (valuations.filter { it.pillar == pillar && Finance.isActual(it) }.map { it.date } +
        accountValuations.filter { it.accountId in accountIds }.map { it.date }).distinct().sorted()
}

/** A raw change of value, not a return: both dates are printed and money put in during the span is shown beside it. */
data class ValueChange2(val from: LocalDate, val to: LocalDate, val cents: Long)

/** Latest value against the nearest earlier one 28–35 days back (Council 1). Null when no such pair exists. */
fun Archive.pillarMonthChange(pillar: Int): ValueChange2? {
    val dates = pillarValueDates(pillar)
    val end = dates.lastOrNull() ?: return null
    val endValue = pillarValueAt(pillar, end) ?: return null
    // Only a real record date 28–35 days before the latest one: no invented start dates.
    val start = dates.filter { it.isBefore(end) && ChronoUnit.DAYS.between(it, end) in 28..35 }
        .lastOrNull { pillarValueAt(pillar, it) != null } ?: return null
    val startValue = pillarValueAt(pillar, start) ?: return null
    return ValueChange2(start, end, Math.subtractExact(endValue, startValue))
}

data class TotalChange(val from: LocalDate, val to: LocalDate, val cents: Long, val versatoCents: Long)

/**
 * Attività over one common window: the total as known on the latest record date against the total as known on a real
 * record date 28–35 days earlier, both with all four pillars valued. Money put in from outside in the span sits beside it.
 */
fun Archive.totalMonthChange(): TotalChange? {
    val dates = (1..4).flatMap { pillarValueDates(it) }.distinct().sorted()
    fun totalAt(d: LocalDate): Long? = (1..4).map { pillarValueAt(it, d) ?: return null }.sum()
    val end = dates.lastOrNull() ?: return null
    val endTotal = totalAt(end) ?: return null
    val start = dates.filter { it.isBefore(end) && ChronoUnit.DAYS.between(it, end) in 28..35 }.lastOrNull { totalAt(it) != null } ?: return null
    val versato = versatoTotal().filter { it.date.isAfter(start) && !it.date.isAfter(end) }.sumOf { it.cents }
    return TotalChange(start, end, Math.subtractExact(endTotal, totalAt(start)!!), versato)
}

/* ---------- Targets ---------- */

sealed interface TargetView {
    data class Amount(val cents: Long, val userSet: Boolean, val months: BigDecimal? = null) : TargetView
    data class Unavailable(val reason: String) : TargetView
    data object None : TargetView
}

/** What the liquidity data covers: the latest start and the earliest end across the pillar-1 connections. */
data class Coverage(val from: LocalDate?, val to: LocalDate?) {
    fun complete(m: YearMonth) = from != null && to != null && !from.isAfter(m.atDay(1)) && !to.isBefore(m.atEndOfMonth())
    /** First and last covered day inside [m], or null when the month is outside the coverage. */
    fun daysIn(m: YearMonth, today: LocalDate): Pair<Int, Int>? {
        if (from == null || to == null) return null
        val a = maxOf(from, m.atDay(1)); val b = minOf(to, m.atEndOfMonth(), today)
        return if (a.isAfter(b)) null else a.dayOfMonth to b.dayOfMonth
    }
}

fun Archive.liquidityCoverage(): Coverage {
    val liquidity = liquidityIds()
    val c = connections.filter { it.accountId in liquidity }
    return Coverage(c.mapNotNull { it.coverageFrom }.maxOrNull(), c.mapNotNull { it.coverageTo }.minOrNull())
}

/** Mean essential spending of the last [months] complete months, or null when categories are not flagged or months are missing. */
fun Archive.essentialMonthlyCents(today: LocalDate = LocalDate.now(), months: Int = 3): Long? {
    val essential = planning.categories.filter { it.essential == Essential.ESSENTIAL }.map { it.id }.toSet()
    if (essential.isEmpty()) return null
    val last = YearMonth.from(today).minusMonths(1)
    val values = (0 until months).map { i ->
        val m = last.minusMonths(i.toLong())
        // A month counts only when every liquidity source covers it from the first to the last day.
        val s = monthSpending(m)
        if (!Coverage(s.coverageFrom, s.coverageTo).complete(m)) return null
        s.byCategory.filterKeys { it in essential }.values.sum()
    }
    return values.sum() / months
}

fun Archive.targetFor(pillar: Int, today: LocalDate = LocalDate.now()): TargetView {
    if (pillar == 4) return TargetView.None
    val t = planning.targets.firstOrNull { it.pillar == pillar } ?: return TargetView.None
    return when (t.kind) {
        TargetKind.BUFFER_CENTS -> TargetView.Amount(t.cents!!, t.userSet)
        TargetKind.MONTHS -> essentialMonthlyCents(today)?.let { TargetView.Amount(t.months!!.multiply(BigDecimal.valueOf(it)).setScale(0, RoundingMode.HALF_UP).toLong(), t.userSet, t.months) }
            ?: TargetView.Unavailable("${t.months!!.stripTrailingZeros().toPlainString()} mesi: servono 3 mesi completi e le categorie essenziali")
    }
}

/* ---------- Freshness ---------- */

data class StaleItem(val label: String, val since: LocalDate)
data class Freshness(val known: Int, val stale: List<StaleItem>, val lastSync: Pair<ImportProvider, Instant>?)

fun Archive.freshness(today: LocalDate = LocalDate.now()): Freshness {
    val stale = mutableListOf<StaleItem>()
    accounts.forEach { a ->
        val last = accountValuations.filter { it.accountId == a.id }.maxByOrNull { it.date } ?: return@forEach
        if (ChronoUnit.DAYS.between(last.date, today) > 35) stale += StaleItem(a.name, last.date)
    }
    (1..4).forEach { p ->
        val v = pillarValue(p)
        if (v is PillarValue.Snapshot && ChronoUnit.DAYS.between(v.date, today) > 35) stale += StaleItem(pillarName(p), v.date)
    }
    val sync = connections.mapNotNull { c -> c.lastSyncAt?.let { runCatching { c.provider to Instant.parse(it) }.getOrNull() } }.maxByOrNull { it.second }
    return Freshness(assetsTotal().known, stale.sortedBy { it.since }, sync)
}

fun ImportProvider.short(): String = when (this) { ImportProvider.TRADE_REPUBLIC -> "TR"; ImportProvider.ISYBANK -> "isybank"; ImportProvider.PAYPAL -> "PayPal" }

fun Instant.localDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()

/* ---------- Month spending view ---------- */

data class CategoryLine(val id: String?, val name: String, val spentCents: Long, val budgetCents: Long?, val recurring: Int) {
    val over: Boolean get() = budgetCents != null && spentCents > budgetCents
    val fraction: Float? get() = budgetCents?.takeIf { it > 0 }?.let { (spentCents.toDouble() / it).toFloat() }
}

fun Archive.categoryName(id: String?): String = id?.let { c -> planning.categories.firstOrNull { it.id == c }?.name } ?: "Senza categoria"

fun Archive.budgetFor(categoryId: String, month: YearMonth): Long? =
    planning.budgets.filter { it.categoryId == categoryId && !it.effectiveFrom.isAfter(month) }.maxByOrNull { it.effectiveFrom }?.monthlyCents

fun Archive.liquidityIds(): Set<String> = accounts.filter { it.pillar == 1 }.map { it.id }.toSet()

/** Spending movements of a month (same filter as [monthSpending]) grouped by the resolved category. */
fun Archive.spendingMovements(month: YearMonth): List<Pair<ExternalMovement, String?>> {
    val liquidity = liquidityIds()
    val transferIds = transfers.map { it.id }.toSet()
    val paired = transferPairs().keys
    val notSpending = setOf(MovementRole.OWN_TRANSFER, MovementRole.FUND_CONTRIBUTION, MovementRole.SALARY, MovementRole.INCOME)
    val parts = installmentPaymentStates()
    return externalMovements.filter {
        it.accountId in liquidity && YearMonth.from(it.date) == month && it.status != ExternalStatus.IGNORED &&
            it.linkedRecordId !in transferIds && it.kind != ExternalKind.BUY && it.kind != ExternalKind.SELL &&
            it.signedCents < 0 && it.role !in notSpending && it.id !in paired
    }.map { it to spendCategoryOf(it, parts) }
}

data class MonthView(
    val month: YearMonth, val spending: MonthSpending, val categories: List<CategoryLine>, val budgetCents: Long?,
    val toPillars: Map<Int, Long>, val versatoCents: Long, val extraSources: List<Pair<String, Long>>, val salaryDate: LocalDate?,
    val dayOfMonth: Int, val daysInMonth: Int, val coverage: Coverage = Coverage(null, null),
) {
    /** First and last day the data covers in this month (for "dal 5", "fino al 25"); null when not covered at all. */
    val coveredDays: Pair<Int, Int>? get() = coverage.daysIn(month, if (YearMonth.from(LocalDate.now()) == month) LocalDate.now() else month.atEndOfMonth())
    val partial: Boolean get() = !coverage.complete(month)
    val budgetFraction: Float? get() = budgetCents?.takeIf { it > 0 }?.let { (spending.spesaCents.toDouble() / it).toFloat() }
    /** What stays in Liquidità from this month's income after pillars and spending; null when it would be negative. */
    val restCents: Long get() = spending.entrateCents - toPillars.values.sum() - spending.spesaCents
    /** "In linea" when spending is within the budget share of the days covered, with 5% slack. */
    val paceOk: Boolean? get() {
        val b = budgetCents ?: return null
        val days = coveredDays?.let { it.second - it.first + 1 } ?: return null
        return spending.spesaCents <= b * days / daysInMonth * 105 / 100
    }
}

fun Archive.monthView(month: YearMonth, today: LocalDate = LocalDate.now()): MonthView {
    val s = monthSpending(month)
    val moves = spendingMovements(month)
    val merchantsRecurring = recurringMerchants(month)
    val spendCats = planning.categories.filter { it.kind == CategoryKind.SPEND }
    val lines = (spendCats.map { it.id } + s.byCategory.keys.filterNotNull()).distinct()
        .map { id ->
            val spent = s.byCategory[id] ?: 0L
            CategoryLine(id, categoryName(id), spent, budgetFor(id, month),
                moves.filter { it.second == id }.mapNotNull { it.first.merchantKey }.distinct().count { it in merchantsRecurring })
        }
        .filter { it.spentCents > 0 || it.budgetCents != null } +
        (s.byCategory[null]?.let { listOf(CategoryLine(null, "Senza categoria", it, null, 0)) } ?: emptyList())
    val budgets = planning.categories.filter { it.kind == CategoryKind.SPEND }.mapNotNull { budgetFor(it.id, month) }
    // Money that left Liquidità this month into another pillar: own transfers and purchases paid from P1 cash (inflows()).
    val monthInflows = inflows().filter { YearMonth.from(it.date) == month }
    val toPillars = monthInflows.filter { it.fromPillar == 1 && it.pillar != 1 }.groupBy { it.pillar }
        .mapValues { (_, f) -> f.sumOf { it.cents } }.toSortedMap()
    val versato = versatoTotal().filter { YearMonth.from(it.date) == month }.sumOf { it.cents }
    // Separately, and dashed: money into a pillar that did not come from this month's liquidity, named by where it came from.
    val extras = monthInflows.filter { it.fromPillar != 1 && it.pillar != 1 }.groupBy { it.pillar to fundingName(it) }
        .map { (k, f) -> (if (k.second != null) "${pillarName(k.first)} da ${k.second}" else "Versamento in ${pillarName(k.first)}") to f.sumOf { it.cents } }
    val salary = externalMovements.filter { YearMonth.from(it.date) == month && it.signedCents > 0 && (it.role == MovementRole.SALARY || planning.incomeRules.any { r -> r.matches(it) }) }
        .maxByOrNull { it.date }?.date
    val sorted = lines.sortedWith(compareBy<CategoryLine> { it.id == null }.thenByDescending { it.spentCents })
    val day = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
    return MonthView(month, s, sorted, budgets.takeIf { it.isNotEmpty() }?.sum(), toPillars, versato, extras, salary, day, month.lengthOfMonth(), Coverage(s.coverageFrom, s.coverageTo))
}

/** The account an inflow came from: the source of a transfer, or the cash account that paid a purchase; null for money from outside. */
internal fun Archive.fundingName(f: Inflow): String? {
    if (f.fromPillar == null) return null
    transfers.firstOrNull { it.id == f.recordId }?.let { t -> return accounts.firstOrNull { it.id == t.fromAccountId }?.name }
    val cash = externalMovements.firstOrNull { it.linkedRecordId == f.recordId }?.accountId
    return accounts.firstOrNull { it.id == cash }?.name ?: pillarName(f.fromPillar)
}

/** Months that have movements, newest first, always including the current month. */
fun Archive.monthsWithData(today: LocalDate = LocalDate.now()): List<YearMonth> =
    (externalMovements.map { YearMonth.from(it.date) } + YearMonth.from(today)).distinct().sortedDescending()

/** Merchants charged in at least three consecutive months up to [month] with amounts within 10%. */
fun Archive.recurringMerchants(month: YearMonth): Set<String> {
    val byMerchant = (0..2).flatMap { spendingMovements(month.minusMonths(it.toLong())).map { p -> p.first } }.filter { it.merchantKey != null }
        .groupBy { it.merchantKey!! }
    return byMerchant.filter { (_, rows) ->
        val byMonth = rows.groupBy { YearMonth.from(it.date) }
        val months = (0..2).map { month.minusMonths(it.toLong()) }
        months.all { it in byMonth } && months.map { m -> -byMonth[m]!!.sumOf { it.signedCents } }.let { a -> a.max() <= a.min() * 11 / 10 }
    }.keys
}

sealed interface Flag {
    val key: String
    data class NewRecurring(override val key: String, val merchant: String, val cents: Long, val first: LocalDate, val categoryId: String?) : Flag
    data class Unusual(override val key: String, val movement: ExternalMovement, val categoryId: String?, val typicalCents: Long) : Flag
}

/**
 * Segnalazioni (calcolato, on merchant keys only): a recurring charge that started in the last three months, and a single
 * spend from a merchant not seen in the three previous months, at least 2× the category's median there and above 100 €.
 */
fun Archive.flags(month: YearMonth): List<Flag> {
    val out = mutableListOf<Flag>()
    val recurring = recurringMerchants(month)
    val liquidity = liquidityIds()
    val dataStart = externalMovements.filter { it.accountId in liquidity }.minOfOrNull { YearMonth.from(it.date) }
    recurring.forEach { key ->
        val rows = externalMovements.filter { it.merchantKey == key && it.signedCents < 0 }
        val first = rows.minOf { it.date }
        // "New" needs history before it: at the start of the imported data every merchant would look new.
        val hasHistory = dataStart != null && YearMonth.from(first).isAfter(dataStart.plusMonths(1))
        if (hasHistory && !YearMonth.from(first).isBefore(month.minusMonths(2))) {
            val latest = rows.maxBy { it.date }
            out += Flag.NewRecurring("rec:$key", latest.description.take(40), -latest.signedCents, first, latest.categoryId ?: planning.categorize(latest).first)
        }
    }
    val now = spendingMovements(month)
    val history = (1..3).flatMap { spendingMovements(month.minusMonths(it.toLong())) }
    now.forEach { (m, cat) ->
        val past = history.filter { it.second == cat }.map { -it.first.signedCents }.sorted()
        val seenBefore = m.merchantKey != null && history.any { it.first.merchantKey == m.merchantKey }
        if (past.size >= 3 && !seenBefore) {
            val median = past[past.size / 2]
            if (-m.signedCents >= 10_000 && -m.signedCents >= median * 2) out += Flag.Unusual("odd:${m.id}", m, cat, median)
        }
    }
    return out
}

/* ---------- Review queue summary ---------- */

data class ReviewSummary(val pending: Int, val transfers: Int, val uncategorized: Int, val duplicates: Int)

fun Archive.reviewSummary(): ReviewSummary {
    val q = reviewQueue()
    return ReviewSummary(
        externalMovements.count { it.status == ExternalStatus.PENDING },
        q.filter { it.decision is ExternalDecision.AsTransfer }.sumOf { it.count },
        externalMovements.count { it.status == ExternalStatus.PENDING && it.signedCents < 0 && it.categoryId == null && planning.categorize(it).first == null },
        q.filter { it.label == "Possibile duplicato" }.sumOf { it.count },
    )
}

/* ---------- Payday proposal ---------- */

data class PaydayLine(val pillar: Int, val cents: Long, val why: String)

/**
 * Coletti's order as a proposal only (it creates no records): Liquidità keeps the month's budget, then Emergenze and
 * Progetti up to their targets, the rest to Lungo termine. Lines without a target say so.
 */
fun Archive.paydayProposal(salaryCents: Long, today: LocalDate = LocalDate.now()): List<PaydayLine> {
    val month = YearMonth.from(today)
    val budget = planning.categories.filter { it.kind == CategoryKind.SPEND }.mapNotNull { budgetFor(it.id, month) }.sum()
    var left = salaryCents
    val lines = mutableListOf<PaydayLine>()
    val keep = if (budget > 0) minOf(left, budget) else 0L
    lines += PaydayLine(1, keep, if (budget > 0) "il budget del mese" else "nessun budget impostato")
    left -= keep
    for (p in 2..3) {
        val target = targetFor(p, today)
        val value = pillarValue(p).centsOrNull()
        if (target is TargetView.Amount && value != null) {
            val need = (target.cents - value).coerceAtLeast(0)
            val put = minOf(left, need)
            lines += PaydayLine(p, put, if (need == 0L) "obiettivo raggiunto" else "mancano ${need / 100}\u00A0€ all'obiettivo")
            left -= put
        } else lines += PaydayLine(p, 0, if (value == null) "valore sconosciuto" else "nessun obiettivo")
    }
    lines += PaydayLine(4, left, "il resto")
    return lines
}

/* ---------- Edits (validated by encoding before they are returned) ---------- */

private fun Archive.checked(): Archive = also { ArchiveCodec.encode(it) }

/** The user's own category for one movement: an override that wins over rules and provider maps. */
fun Archive.withMovementCategory(movementId: String, categoryId: String?): Archive {
    require(categoryId == null || planning.categories.any { it.id == categoryId }) { "Categoria non disponibile" }
    return copy(externalMovements = externalMovements.map {
        if (it.id == movementId) it.copy(categoryId = categoryId, categorySource = if (categoryId == null) null else CategorySource.OVERRIDE) else it
    }).checked()
}

/**
 * Sets, replaces or (with a null [amount]) removes the contributions paid by [positionId] in [year]. The other years of the
 * position are kept, in year order.
 */
fun Archive.withPaidContribution(positionId: String, year: Int, amount: Sourced<Long>?): Archive {
    require(positions.any { it.id == positionId }) { "Posizione non disponibile" }
    require(year in 2000..2100) { "Anno non valido" }
    return copy(positions = positions.map { p ->
        if (p.id != positionId) p
        else {
            val others = p.paidContributions.filter { it.year != year }
            p.copy(paidContributions = (if (amount == null) others else others + PaidContribution(year, amount)).sortedBy { it.year })
        }
    }).checked()
}

/** The person's typed figure for [year], in cents: origin DICHIARATO ("inserito da te"), dated [today]. Null removes the year. */
fun Archive.withDeclaredPaidContribution(positionId: String, year: Int, cents: Long?, today: LocalDate): Archive =
    withPaidContribution(positionId, year, cents?.let { Sourced.declared(it, today) })

/**
 * The assistant's category choices applied together ([assignments]: movement id to category id), as "suggerita". A movement the
 * user categorized themselves (OVERRIDE) is never overwritten. One validation for the whole batch.
 */
fun Archive.withAgentCategories(assignments: Map<String, String>): Archive {
    val known = planning.categories.map { it.id }.toSet()
    require(assignments.values.all { it in known }) { "Categoria non disponibile" }
    if (assignments.isEmpty()) return this
    return copy(externalMovements = externalMovements.map {
        val target = assignments[it.id]
        if (target != null && it.categorySource != CategorySource.OVERRIDE) it.copy(categoryId = target, categorySource = CategorySource.AGENT) else it
    }).checked()
}

/**
 * Reverses [withAgentCategories]: each movement in [restore] (id to its earlier category id and source) goes back only while it
 * still holds the assistant's choice in [expected]; categories in [created] are removed when nothing references them any more.
 */
fun Archive.withoutAgentCategories(restore: Map<String, Pair<String?, CategorySource?>>, expected: Map<String, String>, created: Set<String>): Archive {
    val moved = externalMovements.map { m ->
        val old = restore[m.id]
        if (old != null && m.categoryId == expected[m.id] && m.categorySource == CategorySource.AGENT) m.copy(categoryId = old.first, categorySource = old.second) else m
    }
    val used = moved.mapNotNull { it.categoryId }.toSet() + planning.rules.map { it.categoryId } + planning.providerCategories.map { it.categoryId } +
        planning.budgets.map { it.categoryId } + planning.categories.mapNotNull { it.parentId } + installments.mapNotNull { it.categoryId }
    val drop = created.filter { it !in used }.toSet()
    return copy(externalMovements = moved, planning = planning.copy(categories = planning.categories.filterNot { it.id in drop })).checked()
}

fun Archive.withBudget(categoryId: String, cents: Long?, from: YearMonth): Archive {
    val rest = planning.budgets.filterNot { it.categoryId == categoryId && it.effectiveFrom == from }
    val next = if (cents == null) rest else rest + Budget(categoryId, cents, from)
    return copy(planning = planning.copy(budgets = next)).checked()
}

fun Archive.withNewCategory(name: String, kind: CategoryKind = CategoryKind.SPEND, essential: Essential = Essential.UNSET): Pair<Archive, String> {
    val clean = name.trim()
    require(clean.isNotEmpty() && clean.length <= 60) { "Nome non valido" }
    require(planning.categories.none { it.name.equals(clean, ignoreCase = true) }) { "Esiste già una categoria con questo nome" }
    val base = "c:" + clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "categoria" }.take(40)
    var id = base; var n = 2
    while (planning.categories.any { it.id == id }) id = base + "-" + n++
    return copy(planning = planning.copy(categories = planning.categories + Category(id, clean, kind, essential))).checked() to id
}

fun Archive.withCategoryEssential(categoryId: String, essential: Essential): Archive =
    copy(planning = planning.copy(categories = planning.categories.map { if (it.id == categoryId) it.copy(essential = essential) else it })).checked()

fun Archive.withPillarTarget(pillar: Int, cents: Long?): Archive {
    val rest = planning.targets.filterNot { it.pillar == pillar }
    return copy(planning = planning.copy(targets = if (cents == null) rest else rest + PillarTarget(pillar, TargetKind.BUFFER_CENTS, cents = cents, userSet = true))).checked()
}

fun Archive.withPlanReview(planId: String, review: PlanReview, at: String = Instant.now().toString()): Archive {
    val plan = installments.first { it.id == planId }
    return withInstallmentPlan(plan.copy(review = review, updatedAt = at))
}

fun Archive.withPlanCategory(planId: String, categoryId: String?, at: String = Instant.now().toString()): Archive {
    val plan = installments.first { it.id == planId }
    return withInstallmentPlan(plan.copy(categoryId = categoryId, updatedAt = at))
}

/** Records a connector outcome (for example an expired session) on the connection, so the home can offer to reconnect. */
fun Archive.withConnectionResult(provider: ImportProvider, result: String): Archive {
    val existing = connections.filter { it.provider == provider }
    if (existing.isEmpty()) return this
    return copy(connections = connections.map { if (it.provider == provider) it.copy(lastResult = result.take(500)) else it }).checked()
}

fun Archive.withNewAccount(name: String, pillar: Int, source: String): Pair<Archive, String> {
    val clean = name.trim()
    require(clean.isNotEmpty() && clean.length <= 200) { "Nome del conto non valido" }
    require(pillar in 1..4)
    val base = "a:" + clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "conto" }.take(60)
    var id = base; var n = 2
    while (accounts.any { it.id == id }) id = base + "-" + n++
    return copy(accounts = accounts + Account(id, clean, pillar, source.take(500))).checked() to id
}

/** A value the user reads in a provider's app or statement and types in: USER_REPORTED, dated, never computed. */
fun Archive.withReportedAccountValue(accountId: String, date: LocalDate, cents: Long, source: String): Archive {
    require(cents >= 0) { "L'importo non può essere negativo" }
    require(source.isNotBlank()) { "Indica la fonte" }
    require(!date.isAfter(LocalDate.now())) { "La data non può essere futura" }
    require(accountValuations.none { it.accountId == accountId && it.date == date }) { "Esiste già un valore per quel giorno" }
    return copy(accountValuations = accountValuations + AccountValuation(accountId, date, Money(cents), source.take(500), Confidence.USER_REPORTED)).checked()
}

sealed interface EuroInput {
    data class Ok(val cents: Long) : EuroInput
    data class Invalid(val message: String) : EuroInput
}

/**
 * An amount typed the Italian way: comma for decimals (at most two), dots only as thousands groups of three digits.
 * "1.500" is 1.500,00 €; "1.500,50", "12,5", "0,99" are read as written; "1.5" or "1.5000" are refused as ambiguous.
 */
fun readEuroInput(text: String): EuroInput {
    val t = text.trim().removeSuffix("€").trim().replace(" ", "").replace("\u00A0", "")
    if (t.isEmpty()) return EuroInput.Invalid("Inserisci un importo.")
    val grouped = Regex("[0-9]{1,3}(\\.[0-9]{3})+")
    val plain = Regex("[0-9]+")
    val (whole, decimals) = when {
        t.count { it == ',' } > 1 -> return EuroInput.Invalid("Usa una sola virgola per i decimali.")
        t.contains(',') -> t.substringBefore(',') to t.substringAfter(',')
        else -> t to ""
    }
    if (!(whole.matches(plain) || whole.matches(grouped))) return EuroInput.Invalid(
        if (t.contains('.') && !t.contains(',')) "Importo ambiguo: usa la virgola per i decimali (1,50) e il punto solo per le migliaia (1.500)."
        else "Non è un importo valido.")
    if (decimals.isNotEmpty() && !decimals.matches(Regex("[0-9]{1,2}"))) return EuroInput.Invalid("Al massimo due decimali, dopo la virgola.")
    if (t.endsWith(',')) return EuroInput.Invalid("Mancano i decimali dopo la virgola.")
    return runCatching { BigDecimal(whole.replace(".", "") + if (decimals.isEmpty()) "" else ".$decimals").movePointRight(2).longValueExact() }
        .fold({ EuroInput.Ok(it) }, { EuroInput.Invalid("Importo troppo grande.") })
}

/** Cents for a valid Italian amount, null otherwise (the sheets show [readEuroInput]'s message). */
fun parseEuroInput(text: String): Long? = (readEuroInput(text) as? EuroInput.Ok)?.cents

/* ---------- Unknown-aware sums ---------- */

/** "almeno 120,00 €" when some parts have no amount, "—" when none has one: never a bare zero for an unknown. */
fun atLeastText(knownCents: Long, unknownParts: Int, format: (Long) -> String): String = when {
    unknownParts == 0 -> format(knownCents)
    knownCents == 0L -> "—"
    else -> "almeno " + format(knownCents)
}

/* ---------- Installments ---------- */

fun InstallmentPlan.title(): String = merchant ?: lender
fun InstallmentPlan.paidCount(): Int = installments.count { it.state == InstallmentState.PAID }
/** "1 di 3 pagate"; when the lender's count is unknown, "rate non indicate" (never "0 di 0 pagate"): missing is not zero. */
fun InstallmentPlan.progressText(): String = when {
    count != null -> "${paidCount()} di $count pagate"
    installments.isNotEmpty() -> "${paidCount()} pagate · rate non indicate"
    else -> "rate non indicate"
}
fun InstallmentPlan.nextOpen(): Installment? = open.filter { it.dueDate != null }.minByOrNull { it.dueDate!! } ?: open.minByOrNull { it.seq }

/* ---------- Pension facts for Futuro ---------- */

data class FundView(val account: Account, val statement: PensionStatement?, val value: AccountValuation?, val ownCents: Long?, val employerCents: Long?, val profile: FundProfile?,
    /** The described fund of a PENSION_FUND account, when there is one. */
    val fund: PensionFund? = null)

fun Archive.fundViews(): List<FundView> = accounts.filter { it.pillar == 4 && (it.type == AccountType.PENSION_FUND || pension.statements.any { s -> s.accountId == it.id }) }.map { a ->
    val own = events.filter { it.accountId == a.id && it.type == EventType.PERSONAL_CONTRIBUTION }.takeIf { it.isNotEmpty() }?.sumOf { it.amount.cents }
    val employer = events.filter { it.accountId == a.id && it.type == EventType.EMPLOYER_CONTRIBUTION }.takeIf { it.isNotEmpty() }?.sumOf { it.amount.cents }
    FundView(a, pension.statements.filter { it.accountId == a.id }.maxByOrNull { it.positionDate },
        accountValuations.filter { it.accountId == a.id }.maxByOrNull { it.date }, own, employer, pension.profiles.firstOrNull { it.accountId == a.id }, fundOf(a.id))
}

/* ---------- Manual entry (the exception path: statements, payroll, transfers no connector sees) ---------- */

/** A personal or employer contribution the user reads on a statement or payslip: USER_REPORTED, dated, sourced. Employer money only in pillar 4. */
fun Archive.withManualContribution(type: EventType, pillar: Int, accountId: String?, date: LocalDate, cents: Long, source: String): Archive {
    require(type == EventType.PERSONAL_CONTRIBUTION || type == EventType.EMPLOYER_CONTRIBUTION)
    require(cents > 0) { "L'importo deve essere positivo" }
    require(!date.isAfter(LocalDate.now())) { "La data non può essere futura" }
    require(type != EventType.EMPLOYER_CONTRIBUTION || pillar == 4) { "Il contributo del datore appartiene al pilastro Lungo termine" }
    require(source.isNotBlank()) { "Indica la fonte" }
    require(accountId == null || accounts.any { it.id == accountId && it.pillar == pillar }) { "Il conto non appartiene a questo pilastro" }
    val event = LedgerEvent("m:" + java.util.UUID.randomUUID(), date, pillar, type, Money(cents), Confidence.USER_REPORTED, source.trim().take(500), accountId = accountId)
    return copy(events = events + event).checked()
}

/** Money moved between two of the user's own accounts: one transfer, never a contribution or a withdrawal. */
fun Archive.withManualTransfer(fromId: String, toId: String, date: LocalDate, cents: Long, source: String): Archive {
    require(fromId != toId) { "Scegli due conti diversi" }
    require(cents > 0) { "L'importo deve essere positivo" }
    require(!date.isAfter(LocalDate.now())) { "La data non può essere futura" }
    require(source.isNotBlank()) { "Indica la fonte" }
    val t = AccountTransfer("m:" + java.util.UUID.randomUUID().toString().take(30), date, fromId, toId, Money(cents), source.trim().take(500))
    return copy(transfers = transfers + t).checked()
}

/** A registry entry; a PENSION_FUND account gets a described fund named from the account so values and flows attach to it. */
fun Archive.withManualAccount(name: String, pillar: Int, type: AccountType?, source: String): Pair<Archive, String> {
    val (a, id) = withNewAccount(name, pillar, source)
    if (type == null || type == AccountType.GENERAL) return a to id
    return a.copy(accounts = a.accounts.map { if (it.id == id) it.copy(type = type) else it }).withAccountFund(id).checked() to id
}

/** Adds the frozen, verified ISTAT months that are missing; never mixes series. Returns the archive and how many months were added. */
fun Archive.withIstatSnapshot(): Pair<Archive, Int> {
    require(indices.all { it.source == Istat.SERIES }) { "Gli indici esistenti usano un'altra serie: non si mescolano basi diverse." }
    val add = Istat.snapshot().filter { p -> indices.none { it.month == p.month } }
    return copy(indices = indices + add).checked() to add.size
}
