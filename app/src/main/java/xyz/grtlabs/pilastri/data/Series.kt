package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.domain.*
import xyz.grtlabs.pilastri.domain.rules.DeductionCeiling
import xyz.grtlabs.pilastri.domain.rules.RuleStatus

/*
 * Time series and month read models for Pilastri, Mese and the pillar detail (Giro 3 boards 1a–1c, 2, 7b; DESIGN.md
 * "Screen specs"). Pure functions over the archive, nothing stored. Missing is not zero: a month without a dated value is
 * a gap; personal and employer money stay apart; the change of value and the money put in are never netted.
 */

/** Two dated values further apart than this are not joined by a line (DESIGN "Charts": the line breaks after >35 days). */
const val GapDays = 35L

data class SeriesPoint(val date: LocalDate, val cents: Long)

/** Splits [points] into runs; a run ends where the next point is more than [gapDays] after the previous one. */
fun runsOf(points: List<SeriesPoint>, gapDays: Long = GapDays): List<List<SeriesPoint>> {
    val out = mutableListOf<MutableList<SeriesPoint>>()
    points.sortedBy { it.date }.forEach { p ->
        val cur = out.lastOrNull()
        if (cur == null || ChronoUnit.DAYS.between(cur.last().date, p.date) > gapDays) out += mutableListOf(p) else cur += p
    }
    return out
}

/** A chart's time window, both ends included. */
data class Window(val from: LocalDate, val to: LocalDate) {
    val months: List<YearMonth> get() = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()
    operator fun contains(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
}

/** The 12-month window of the Pilastri and pillar charts: one year back from today. */
fun yearWindow(today: LocalDate) = Window(today.minusYears(1), today)

/** The twelve calendar months ending with today's (the contribution bars). */
fun lastTwelveMonths(today: LocalDate): List<YearMonth> = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }

/* ---------- Attività and pillar values over time ---------- */

private fun Archive.totalOn(d: LocalDate): Long? = (1..4).fold(0L) { s, p -> Math.addExact(s, pillarValue(p, d).centsOrNull() ?: return null) }

/** [partial]: today's total is not complete, so the last point is older than today's partial sum and is drawn open. */
data class AttivitaLine(val window: Window, val points: List<SeriesPoint>, val partial: Boolean)

/**
 * Monthly Attività over the last year: in each month, the latest record date on which all four pillars have a value as
 * known that day, with their total. A month without such a date is a gap (never 0); the line breaks after >35 days.
 */
fun Archive.attivitaLine(today: LocalDate): AttivitaLine {
    val w = yearWindow(today)
    val byMonth = (1..4).flatMap { pillarValueDates(it) }.distinct().filter { it in w }.groupBy { YearMonth.from(it) }
    val points = w.months.mapNotNull { m -> byMonth[m]?.sortedDescending()?.firstNotNullOfOrNull { d -> totalOn(d)?.let { SeriesPoint(d, it) } } }
    return AttivitaLine(w, points, (1..4).any { pillarValue(it) is PillarValue.Unknown })
}

/** One dated value per month of the last year for [pillar] (its latest record date in the month); months without one are gaps. */
fun Archive.pillarLine(pillar: Int, today: LocalDate): List<SeriesPoint> {
    val w = yearWindow(today)
    val byMonth = pillarValueDates(pillar).filter { it in w }.groupBy { YearMonth.from(it) }
    return w.months.mapNotNull { m -> byMonth[m]?.sortedDescending()?.firstNotNullOfOrNull { d -> pillarValue(pillar, d).centsOrNull()?.let { SeriesPoint(d, it) } } }
}

/** "Attività" as the sum of the pillars that have a value ("su N di 4" when [known] < 4); null when none has one. */
data class AttivitaSum(val cents: Long?, val known: Int)

fun Archive.attivitaSum(): AttivitaSum {
    val known = (1..4).mapNotNull { pillarValue(it).centsOrNull() }
    return AttivitaSum(known.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact), known.size)
}

/** The newest date among the pillars' current values ("Aggiornato al …"); null when no pillar has a value. */
fun Archive.newestValueDate(): LocalDate? = (1..4).mapNotNull {
    when (val v = pillarValue(it)) { is PillarValue.Snapshot -> v.date; is PillarValue.Composed -> v.newest; is PillarValue.Unknown -> null }
}.maxOrNull()

/** The date printed under a pillar column (1a-07). [stale]: drawn in the "datato" style. */
sealed interface ValueDate {
    data class At(val date: LocalDate) : ValueDate
    data class Range(val from: LocalDate, val to: LocalDate) : ValueDate
    /** The value is older than 35 days, or unknown now and this is the last day it was known. */
    data class Old(val date: LocalDate, val stale: Boolean) : ValueDate
    data object None : ValueDate
}

fun Archive.pillarValueDate(pillar: Int, today: LocalDate): ValueDate {
    fun old(d: LocalDate) = ChronoUnit.DAYS.between(d, today) > GapDays
    return when (val v = pillarValue(pillar)) {
        is PillarValue.Snapshot -> if (old(v.date)) ValueDate.Old(v.date, true) else ValueDate.At(v.date)
        is PillarValue.Composed -> when {
            old(v.newest) -> ValueDate.Old(v.newest, true)
            v.oldest == v.newest -> ValueDate.At(v.newest)
            else -> ValueDate.Range(v.oldest, v.newest)
        }
        is PillarValue.Unknown -> pillarValueDates(pillar).sortedDescending().firstOrNull { pillarValue(pillar, it).centsOrNull() != null }
            ?.let { ValueDate.Old(it, old(it)) } ?: ValueDate.None
    }
}

/* ---------- The month's two figures (1a-09, 1a-10): never netted ---------- */

/** The change of value over one common window of real record dates, summed over the [counted] pillars known at both ends. */
data class MonthValueChange(val from: LocalDate, val to: LocalDate, val cents: Long, val counted: Int)

/**
 * "Variazione del mese · valore": the latest record date against a real record date 28–35 days earlier (the one where most
 * pillars are known at both ends, then the latest). Pillars missing at either end are left out and counted ("su N di 4").
 * It is a change of value, not a return: the money put in is the separate [monthVersamenti] figure.
 */
fun Archive.monthValueChange(): MonthValueChange? {
    val dates = (1..4).flatMap { pillarValueDates(it) }.distinct().sorted()
    val end = dates.lastOrNull() ?: return null
    val atEnd = (1..4).associateWith { pillarValue(it, end).centsOrNull() }
    fun diffs(start: LocalDate) = (1..4).mapNotNull { p -> val b = atEnd[p] ?: return@mapNotNull null; pillarValue(p, start).centsOrNull()?.let { Math.subtractExact(b, it) } }
    val start = dates.filter { it.isBefore(end) && ChronoUnit.DAYS.between(it, end) in 28..35 }
        .map { it to diffs(it) }.filter { it.second.isNotEmpty() }
        .maxWithOrNull(compareBy<Pair<LocalDate, List<Long>>> { it.second.size }.thenBy { it.first }) ?: return null
    return MonthValueChange(start.first, end, start.second.fold(0L, Math::addExact), start.second.size)
}

/**
 * The same change of value inside one month (the month row swipes, G-11): the latest record date in [month] against a real
 * record date 28–35 days earlier. No record date in the month → null ("n/d"), never a guess from another month.
 */
fun Archive.monthValueChange(month: YearMonth): MonthValueChange? {
    val dates = (1..4).flatMap { pillarValueDates(it) }.distinct().sorted()
    val end = dates.lastOrNull { YearMonth.from(it) == month } ?: return null
    val atEnd = (1..4).associateWith { pillarValue(it, end).centsOrNull() }
    fun diffs(start: LocalDate) = (1..4).mapNotNull { p -> val b = atEnd[p] ?: return@mapNotNull null; pillarValue(p, start).centsOrNull()?.let { Math.subtractExact(b, it) } }
    val start = dates.filter { it.isBefore(end) && ChronoUnit.DAYS.between(it, end) in 28..35 }
        .map { it to diffs(it) }.filter { it.second.isNotEmpty() }
        .maxWithOrNull(compareBy<Pair<LocalDate, List<Long>>> { it.second.size }.thenBy { it.first }) ?: return null
    return MonthValueChange(start.first, end, start.second.fold(0L, Math::addExact), start.second.size)
}

/* ---------- Which months the month row and Mese can show (1a-20, G-11) ---------- */

/**
 * Months that hold data: a movement, a personal or employer contribution, a dated pillar value, or days covered by a
 * liquidity connection. Never after [today]'s month.
 */
fun Archive.dataMonths(today: LocalDate): Set<YearMonth> {
    val now = YearMonth.from(today)
    val out = mutableSetOf<YearMonth>()
    externalMovements.filter { it.status != ExternalStatus.IGNORED }.forEach { out += YearMonth.from(it.date) }
    versatoTotal().forEach { out += YearMonth.from(it.date) }
    events.filter { it.type == EventType.EMPLOYER_CONTRIBUTION }.forEach { out += YearMonth.from(it.date) }
    (1..4).forEach { p -> pillarValueDates(p).forEach { out += YearMonth.from(it) } }
    val c = liquidityCoverage()
    if (c.from != null && c.to != null && !c.from.isAfter(c.to)) {
        var m = YearMonth.from(c.from); val last = minOf(YearMonth.from(c.to), now)
        while (!m.isAfter(last)) { out += m; m = m.plusMonths(1) }
    }
    return out.filterTo(mutableSetOf()) { !it.isAfter(now) }
}

/**
 * The months to swipe through, oldest first: from the first month with data to the latest one (no data before the first,
 * nothing after today). Months in between without data stay, shown as "—" with their coverage, never "0 €". The current
 * month is in only once it has data. Empty when nothing has data.
 */
fun monthTimeline(dataMonths: Set<YearMonth>, today: LocalDate): List<YearMonth> {
    val now = YearMonth.from(today)
    val valid = dataMonths.filter { !it.isAfter(now) }
    val first = valid.minOrNull() ?: return emptyList()
    val last = valid.max()
    return generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()
}

/** The month the row and Mese show: the chosen one when it is on the timeline, else the latest with data, else today's. */
fun shownMonth(chosen: String?, timeline: List<YearMonth>, today: LocalDate): YearMonth {
    val c = chosen?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    return c?.takeIf { it in timeline } ?: timeline.lastOrNull() ?: YearMonth.from(today)
}

/**
 * THE month coverage label (hero, Mese subtitle, cover month row). Coverage that starts on the 1st reads "dati al 6 nov"; one that starts
 * later reads "dati dal 10 al 31 ott" ("dati dal 12 nov" for the running month, open-ended). Never "dati dal 1 al". Null for a fully
 * covered month unless [fullAsEnd] (then "dati al 31 ott"); "nessun dato" outside the coverage.
 */
/** THE coverage wording, for months and for any other window: "dati al <end>" when coverage starts with the period, "dati dal <start> al <end>" when it starts later. */
fun coverageWording(start: String?, end: String): String = if (start == null) "dati al $end" else "dati dal $start al $end"

fun coverageLabel(month: YearMonth, coverage: Coverage, today: LocalDate, monthShort: String, fullAsEnd: Boolean = false): String? {
    val days = coverage.daysIn(month, today) ?: return "nessun dato"
    val (a, b) = days
    return when {
        a == 1 && b == month.lengthOfMonth() && !fullAsEnd -> null
        a == 1 -> coverageWording(null, "$b $monthShort")
        a == b || (YearMonth.from(today) == month && b == today.dayOfMonth) -> "dati dal $a $monthShort"
        else -> coverageWording("$a", "$b $monthShort")
    }
}

enum class VersamentoKind { TUO, DATORE, DA_VERIFICARE }

/** One row of the month's Versamenti (1a-17). [from]: the account or pillar the money left; [note]: the record's source when it came from outside. */
data class VersamentoRow(val date: LocalDate, val name: String, val from: String?, val note: String?, val cents: Long, val kind: VersamentoKind)

data class MonthVersamenti(val month: YearMonth, val rows: List<VersamentoRow>) {
    private val own get() = rows.filter { it.kind == VersamentoKind.TUO }
    /** Confirmed personal money put in this month. */
    val tuoCents: Long get() = own.fold(0L) { s, r -> Math.addExact(s, r.cents) }
    val tuoCount: Int get() = own.size
    /** Personal rows including those still to verify: the M of "su N di M versamenti". */
    val tuoTotalCount: Int get() = own.size + rows.count { it.kind == VersamentoKind.DA_VERIFICARE }
    /** Employer money, kept separate; null when no employer record exists for the month (n/d, never 0). */
    val employerCents: Long? get() = rows.filter { it.kind == VersamentoKind.DATORE }.takeIf { it.isNotEmpty() }?.fold(0L) { s, r -> Math.addExact(s, r.cents) }
}

private fun String.shortSource(): String = removePrefix("$SamplePrefix · ").substringBefore(" · ").trim().take(36)

/**
 * The month's money into the pillars: confirmed personal money (contributions from outside, transfers leaving Liquidità,
 * purchases paid from a cash account in another pillar) tagged "tuo"; employer contributions tagged "datore"; outgoing
 * own-transfer or fund movements on Liquidità still pending review tagged "da verificare" (not in the "tuo" sum).
 */
fun Archive.monthVersamenti(month: YearMonth): MonthVersamenti {
    val names = accounts.associate { it.id to it.name }
    fun dest(f: Inflow): String? = events.firstOrNull { it.id == f.recordId }?.accountId?.let(names::get)
        ?: transfers.firstOrNull { it.id == f.recordId }?.toAccountId?.let(names::get)
        ?: trades.firstOrNull { it.id == f.recordId }?.accountId?.let(names::get)
    val tuo = versatoTotal().filter { YearMonth.from(it.date) == month }.map { f ->
        VersamentoRow(f.date, pillarName(f.pillar) + (dest(f)?.let { " · $it" } ?: ""), fundingName(f),
            events.firstOrNull { it.id == f.recordId }?.source?.shortSource(), f.cents, VersamentoKind.TUO)
    }
    val employer = events.filter { it.type == EventType.EMPLOYER_CONTRIBUTION && YearMonth.from(it.date) == month }.map { e ->
        VersamentoRow(e.date, pillarName(e.pillar) + (e.accountId?.let(names::get)?.let { " · $it" } ?: ""), null, e.source.shortSource(), e.amount.cents, VersamentoKind.DATORE)
    }
    val liquidity = liquidityIds()
    val pending = externalMovements.filter {
        it.accountId in liquidity && YearMonth.from(it.date) == month && it.status == ExternalStatus.PENDING && it.signedCents < 0 &&
            (it.role == MovementRole.OWN_TRANSFER || it.role == MovementRole.FUND_CONTRIBUTION)
    }.map { VersamentoRow(it.date, it.description.trim().take(40).ifEmpty { "Movimento" }, names[it.accountId], null, -it.signedCents, VersamentoKind.DA_VERIFICARE) }
    return MonthVersamenti(month, (tuo + employer + pending).sortedWith(compareByDescending<VersamentoRow> { it.date }.thenBy { it.kind }))
}

/* ---------- Contributions per month for one pillar (2-03) ---------- */

/** DATA: something was recorded; NONE: known nothing (drawn hollow); ND: not known (a dashed "n/d" slot, never 0). */
enum class BarState { DATA, NONE, ND }

data class MonthBar(val month: YearMonth, val tuoCents: Long, val datoreCents: Long, val state: BarState)

/**
 * Personal and employer money into [pillar] for each of the last twelve months, kept apart. A month with no record is
 * NONE only when the Liquidità data covers it whole and it is not before the pillar's first record; otherwise ND.
 */
fun Archive.pillarContributionBars(pillar: Int, today: LocalDate): List<MonthBar> {
    val own = versatoInto(pillar).groupBy { YearMonth.from(it.date) }
    val emp = events.filter { it.pillar == pillar && it.type == EventType.EMPLOYER_CONTRIBUTION }.groupBy { YearMonth.from(it.date) }
    val first = (own.keys + emp.keys).minOrNull()
    val cover = liquidityCoverage()
    return lastTwelveMonths(today).map { m ->
        val t = own[m]?.fold(0L) { s, f -> Math.addExact(s, f.cents) } ?: 0L
        val d = emp[m]?.fold(0L) { s, e -> Math.addExact(s, e.amount.cents) } ?: 0L
        val state = when {
            own[m] != null || emp[m] != null -> BarState.DATA
            first != null && !m.isBefore(first) && cover.complete(m) -> BarState.NONE
            else -> BarState.ND
        }
        MonthBar(m, t, d, state)
    }
}

/* ---------- € gen 2026 (ISTAT NIC, same base and series rule as summarizeContributions) ---------- */

/** Converts a dated amount to January-2026 euros; null when the month (or the base) has no compatible index: n/d, never a guess. */
class Deflator(indices: List<IndexPoint>) {
    private val base = indices.firstOrNull { it.month == BaseMonth }
    private val byMonth = indices.filter { base != null && it.source.trim() == base.source.trim() }.associateBy { it.month }
    fun real(cents: Long, month: YearMonth): Long? = Finance.inBaseYear(Money(cents), base?.value, byMonth[month]?.value)?.cents
    fun real(p: SeriesPoint): SeriesPoint? = real(p.cents, YearMonth.from(p.date))?.let { SeriesPoint(p.date, it) }
    /** The bar in January-2026 euros; a DATA month without an index becomes ND. */
    fun real(b: MonthBar): MonthBar {
        if (b.state != BarState.DATA) return b
        val t = real(b.tuoCents, b.month); val d = real(b.datoreCents, b.month)
        return if (t == null || d == null) MonthBar(b.month, 0, 0, BarState.ND) else MonthBar(b.month, t, d, BarState.DATA)
    }
    companion object { val BaseMonth: YearMonth = YearMonth.of(2026, 1) }
}

fun Archive.deflator() = Deflator(indices)

/* ---------- Scales ---------- */

/** A printed value scale: three ticks lo, mid, hi. */
data class Scale(val lo: Long, val hi: Long) { val ticks: List<Long> get() = listOf(lo, lo + (hi - lo) / 2, hi) }

private val niceSteps: List<Long> = (2..14).flatMap { k -> val p = Math.pow(10.0, k.toDouble()).toLong(); listOf(p, 2 * p, 5 * p / 2, 5 * p) }.sorted()

/**
 * The smallest round step (1, 2, 2,5, 5 × 10^k cents, at least 1 €) with lo = floor(min) and lo + 2·step ≥ max. The span
 * is at least 4% of the largest magnitude, so a nearly flat series is not stretched into a dramatic slope.
 */
fun niceScale(min: Long, max: Long): Scale {
    require(min <= max)
    val minSpan = maxOf(kotlin.math.abs(min), kotlin.math.abs(max)) / 25
    for (s in niceSteps) {
        val lo = Math.floorDiv(min, s) * s
        if (lo + 2 * s >= max && 2 * s >= minSpan) return Scale(lo, lo + 2 * s)
    }
    return Scale(min, max)
}

/** The smallest round value ≥ [max] for a scale starting at 0 (bars); at least 1 €. */
fun niceCeil(max: Long): Long = niceSteps.firstOrNull { it >= max } ?: max

/* ---------- Income card: deduction room (1b-03) ---------- */

/** September to December: when the income card may show the deduction room. */
fun deductionSeason(today: LocalDate) = today.monthValue >= 9

/** The room left under the ceiling, only when the ceiling and the counted amount rest on VERIFIED rows (EXACT); otherwise null and the line is absent. */
fun verifiedDeductionRoom(d: DeductionCeiling?): Long? {
    val ceiling = d?.ceiling?.takeIf { it.status == RuleStatus.EXACT }?.value ?: return null
    val counted = d.counted.takeIf { it.status == RuleStatus.EXACT }?.value ?: return null
    return (ceiling - counted).coerceAtLeast(0)
}
