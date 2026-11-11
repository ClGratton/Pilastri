package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/*
 * Read models of Futuro (board 4a, cover 7d): the Lungo termine value now and its sources, the money put in per year, and the
 * scenario fan. Pure functions over the archive, nothing stored. Missing is not zero (a source without a value is n/d); the
 * scenario is a "stima" in € gen 2026 with real growth rates 2–6% (median 4%): never an actual value, never a forecast.
 */

/** Years ahead when the person's data names no horizon. Stated in the scenario caption. */
const val DefaultHorizonYears = 30
const val MaxHorizonYears = 60

/** Real annual growth of the fan: the median is [MedianRate]; 3–5% is the near band, 2–6% the far band. */
val FanRates: List<Int> = listOf(2, 3, 4, 5, 6)
const val MedianRate = 4
const val NearLow = 3
const val NearHigh = 5
const val FarLow = 2
const val FarHigh = 6

/* ---------- Horizon ---------- */

/** [retirement]: the date the horizon comes from (the person's own data); null when [years] is the stated default. */
data class Horizon(val years: Int, val endYear: Int, val retirement: LocalDate?)

/** Up to the retirement date of the person's pension estimate when it lies 1..[MaxHorizonYears] years ahead; otherwise [DefaultHorizonYears]. */
fun scenarioHorizon(start: LocalDate, retirement: LocalDate?): Horizon {
    val y = retirement?.let { it.year - start.year }
    return if (retirement != null && y != null && y in 1..MaxHorizonYears) Horizon(y, retirement.year, retirement)
    else Horizon(DefaultHorizonYears, start.year + DefaultHorizonYears, null)
}

/** The retirement date of the latest pension estimate the person recorded; null when none carries one. */
fun Archive.retirementDate(): LocalDate? = pension.inps.maxByOrNull { it.asOf }?.retirementDate

/* ---------- The fan ---------- */

/**
 * The value at the end of each year 0..[years] at a real [ratePercent]: growth applies to the opening balance and the yearly
 * addition arrives at year end (the same convention as the scenario calculator). Null on overflow.
 */
fun projectPath(startCents: Long, yearlyCents: Long, ratePercent: Int, years: Int): List<Long>? {
    require(years >= 0 && yearlyCents >= 0 && startCents >= 0)
    return try {
        val factor = BigDecimal.ONE.add(BigDecimal(ratePercent).movePointLeft(2))
        var w = startCents
        val out = ArrayList<Long>(years + 1)
        out += w
        repeat(years) {
            w = Math.addExact(BigDecimal.valueOf(w).multiply(factor).setScale(0, RoundingMode.HALF_UP).longValueExact(), yearlyCents)
            out += w
        }
        out
    } catch (_: ArithmeticException) { null }
}

data class FanRow(val offset: Int, val year: Int, val median: Long, val low: Long, val high: Long)

/**
 * The scenario in € gen 2026: [paths] by real rate, each with [years] + 1 values (offset 0 is the start). [yearlyCents] null
 * means the person's contribution is unknown and the paths grow the starting value alone.
 */
data class Fan(val startDate: LocalDate, val startCents: Long, val yearlyCents: Long?, val years: Int, val paths: Map<Int, List<Long>>) {
    val median: List<Long> get() = paths.getValue(MedianRate)
    fun row(offset: Int) = FanRow(offset, startDate.year + offset, median[offset], paths.getValue(FarLow)[offset], paths.getValue(FarHigh)[offset])
    val end: FanRow get() = row(years)
}

fun buildFan(startDate: LocalDate, startCents: Long, yearlyCents: Long?, years: Int): Fan? {
    val paths = FanRates.associateWith { projectPath(startCents, yearlyCents ?: 0L, it, years) ?: return null }
    return Fan(startDate, startCents, yearlyCents, years, paths)
}

/** The table rows of the scenario: +10, +20, +30 years and so on up to the horizon, then the horizon itself. A short horizon is its own single row. */
fun milestoneOffsets(years: Int): List<Int> {
    if (years < 1) return emptyList()
    return ((10..years step 10).toList() + years).distinct()
}

/** A stima is printed to the nearest 1.000 € from 10.000 € up, else to the nearest 100 €, so it never claims more precision than it has. */
fun roundStima(cents: Long): Long {
    val unit = if (cents >= 1_000_000L) 100_000L else 10_000L
    return Math.floorDiv(cents + unit / 2, unit) * unit
}

/* ---------- Long-term value now and its sources ---------- */

/** The Lungo termine pillar today: its nominal value, its date, and the same value in € gen 2026 (null without a compatible index). */
data class LongTermNow(val nominalCents: Long?, val realCents: Long?, val date: LocalDate?)

fun Archive.longTermNow(def: Deflator = deflator()): LongTermNow {
    val (cents, date) = when (val v = pillarValue(4)) {
        is PillarValue.Snapshot -> v.cents to v.date
        is PillarValue.Composed -> v.cents to v.newest
        is PillarValue.Unknown -> null to null
    }
    return LongTermNow(cents, if (cents != null && date != null) def.real(cents, YearMonth.from(date)) else null, date)
}

/** One source of the Lungo termine value (a fund, a broker account); [cents] null = no value known (a dashed "n/d" slot). */
data class SourceSlice(val name: String, val cents: Long?, val date: LocalDate?) {
    fun stale(today: LocalDate) = date != null && ChronoUnit.DAYS.between(date, today) > GapDays
}

/** The slice in the chosen basis: nominal as recorded, or deflated from the month of its date; null when unknown or without an index. */
fun SourceSlice.inBasis(real: Boolean, def: Deflator): Long? =
    if (cents == null || date == null) null else if (real) def.real(cents, YearMonth.from(date)) else cents

private fun String.sourceName(): String = removePrefix("$SamplePrefix · ").substringBefore(" · ").trim().take(36).ifBlank { "Lungo termine" }

/**
 * The sources behind the Lungo termine value: each account of the pillar with its latest dated value, or the single reported
 * snapshot when that is what the pillar value rests on. An account without a value stays in the list with null cents.
 */
fun Archive.longTermSources(): List<SourceSlice> = when (val v = pillarValue(4)) {
    is PillarValue.Snapshot -> listOf(SourceSlice(v.source.sourceName(), v.cents, v.date))
    is PillarValue.Composed -> v.parts.map { (acc, val4) -> SourceSlice(acc.name, val4.amount.cents, val4.date) }
    is PillarValue.Unknown -> accounts.filter { it.pillar == 4 }.map { acc ->
        accountValuations.filter { it.accountId == acc.id }.maxByOrNull { it.date }?.let { SourceSlice(acc.name, it.amount.cents, it.date) } ?: SourceSlice(acc.name, null, null)
    }
}

/** "Aggiornato al … · N/M con valore · <fonte> datato …": the newest date, the sources with a value, the oldest stale one. */
data class LongTermFreshness(val asOf: LocalDate?, val known: Int, val of: Int, val stale: StaleItem?) {
    val complete: Boolean get() = known == of && stale == null
}

fun longTermFreshness(slices: List<SourceSlice>, today: LocalDate): LongTermFreshness {
    val known = slices.filter { it.cents != null }
    val stale = slices.filter { it.cents != null && it.stale(today) }.minByOrNull { it.date!! }?.let { StaleItem(it.name, it.date!!) }
    return LongTermFreshness(known.mapNotNull { it.date }.maxOrNull(), known.size, slices.size.coerceAtLeast(1), stale)
}

/* ---------- Money put in per year ---------- */

/** One year of "Versato tuo per anno". [cents] null = n/d (no record, or none with a compatible index); [toDate]: the current year, drawn dashed. */
data class YearBar(val year: Int, val cents: Long?, val count: Int, val indexed: Int, val state: BarState, val toDate: Boolean)

/**
 * A bar per year from the first contribution to [today]'s year. In € gen 2026 each contribution is deflated from its month and
 * one without an index is left out and counted ([indexed] of [count]); a year where none can be indexed is n/d. A year with
 * no record is NONE only when the Liquidità data covers every month that has ended, otherwise n/d, never a zero bar.
 */
fun yearlyBars(flows: List<Inflow>, today: LocalDate, real: Boolean, def: Deflator, coverage: Coverage): List<YearBar> {
    val first = flows.minOfOrNull { it.date.year } ?: return emptyList()
    val byYear = flows.groupBy { it.date.year }
    return (first..today.year).map { y ->
        val rows = byYear[y].orEmpty()
        val toDate = y == today.year
        if (rows.isEmpty()) {
            val ended = (1..12).map { YearMonth.of(y, it) }.filter { it.isBefore(YearMonth.from(today)) }
            val none = ended.isNotEmpty() && ended.all { coverage.complete(it) }
            return@map YearBar(y, null, 0, 0, if (none) BarState.NONE else BarState.ND, toDate)
        }
        val values = rows.mapNotNull { f -> if (real) def.real(f.cents, YearMonth.from(f.date)) else f.cents }
        val sum = values.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact)
        YearBar(y, sum, rows.size, values.size, if (sum == null) BarState.ND else BarState.DATA, toDate)
    }
}

fun Archive.yearlyContributions(today: LocalDate, real: Boolean, def: Deflator = deflator()): List<YearBar> =
    yearlyBars(versatoInto(4), today, real, def, liquidityCoverage())

/**
 * The person's yearly contribution as an average: the sum of the contributions in € gen 2026 over the months from the first one to
 * [today] (at least 12, so a short history is not annualised upwards). Null without any indexable contribution.
 */
fun averageYearlyContribution(realFlows: List<SeriesPoint>, today: LocalDate): Long? {
    val first = realFlows.minOfOrNull { it.date } ?: return null
    val months = ChronoUnit.MONTHS.between(first, today).coerceAtLeast(12)
    val sum = realFlows.fold(0L) { s, p -> Math.addExact(s, p.cents) }
    return BigDecimal.valueOf(sum).multiply(BigDecimal(12)).divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_UP).longValueExact()
}

/* ---------- The scenario of Futuro ---------- */

/**
 * The whole Futuro scenario: the starting value in € gen 2026 ([startReal] null → n/d), the average yearly contribution
 * ([yearlyReal] null → the paths grow the starting value alone), the horizon and the fan (null without a start or on overflow).
 * [startDate] is today unless the value is dated (older than [GapDays]): then it is that date, so the scenario never pretends
 * a stale value is current.
 */
data class FutureScenario(
    val startDate: LocalDate, val startReal: Long?, val yearlyReal: Long?, val yearlySince: LocalDate?, val horizon: Horizon, val fan: Fan?,
)

fun Archive.futureScenario(today: LocalDate, def: Deflator = deflator()): FutureScenario {
    val now = longTermNow(def)
    val startDate = now.date?.takeIf { ChronoUnit.DAYS.between(it, today) > GapDays } ?: today
    val flows = versatoInto(4).mapNotNull { f -> def.real(f.cents, YearMonth.from(f.date))?.let { SeriesPoint(f.date, it) } }
    val yearly = averageYearlyContribution(flows, today)
    val horizon = scenarioHorizon(startDate, retirementDate())
    val fan = now.realCents?.let { buildFan(startDate, it, yearly, horizon.years) }
    return FutureScenario(startDate, now.realCents, yearly, flows.minOfOrNull { it.date }, horizon, fan)
}
