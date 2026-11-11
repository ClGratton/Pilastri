package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.domain.rules.FundContribution

/*
 * Pure logic of the Previdenza page (board G3-4b) and the Futuro card (4a-07). Nothing here names a fund, employer or regime:
 * every label comes from the person's positions and funds. Missing is null / "n/d", never zero.
 */

/* ---------- 4b-05: deduction bar, one segment per fund x party ---------- */

enum class DeductionParty { TUO, DATORE }

/** One drawn segment: what [party] put into [fundId] in the year. TFR is never a segment. */
data class DeductionSegment(val fundId: String, val fundName: String, val party: DeductionParty, val cents: Long)

/**
 * The 4b-05 bar. [countedCents] is null when no contribution of the year is recorded or any party of any fund-month is unknown
 * ([unknownParts] > 0): then the total is n/d and so is the room. [excessCents] is set only when the total is over the ceiling.
 * [scaleCents] is the printed scale end (the ceiling or the total, rounded up to the next full thousand euros).
 */
data class DeductionBar(
    val segments: List<DeductionSegment>,
    val unknownParts: Int,
    val countedCents: Long?,
    val ceilingCents: Long?,
    val roomCents: Long?,
    val excessCents: Long?,
    val scaleCents: Long,
)

private const val SCALE_STEP = 100_000L

/** Round [cents] up to the next full 1.000 EUR (at least one step). */
fun deductionScale(cents: Long): Long = if (cents <= 0) SCALE_STEP else ((cents + SCALE_STEP - 1) / SCALE_STEP) * SCALE_STEP

/**
 * Builds the bar from the fund contributions of [year] with the same dedupe as the rule pack (first record per
 * (fund, month) wins). Segments are ordered by fund then party; a known zero adds no width. [fundNames] maps a fund id to the
 * name to show (unknown id: "Fondo").
 */
fun deductionBar(contributions: List<FundContribution>, year: Int, ceilingCents: Long?, fundNames: Map<String, String> = emptyMap()): DeductionBar {
    val kept = contributions.filter { it.period.year == year }.groupBy { it.fundId to it.period }.values.map { it.first() }
    val unknown = kept.count { it.workerCents == null || it.employerCents == null }
    val byFund = kept.groupBy { it.fundId }.toSortedMap()
    val segments = byFund.flatMap { (fundId, rows) ->
        val name = fundNames[fundId] ?: "Fondo"
        listOf(
            DeductionSegment(fundId, name, DeductionParty.TUO, rows.sumOf { it.workerCents ?: 0L }),
            DeductionSegment(fundId, name, DeductionParty.DATORE, rows.sumOf { it.employerCents ?: 0L }),
        ).filter { it.cents > 0 }
    }
    val counted = if (kept.isEmpty() || unknown > 0) null else segments.sumOf { it.cents }
    val room = if (ceilingCents != null && counted != null) (ceilingCents - counted).coerceAtLeast(0) else null
    val excess = if (ceilingCents != null && counted != null && counted > ceilingCents) counted - ceilingCents else null
    val scale = deductionScale(maxOf(ceilingCents ?: 0L, counted ?: segments.sumOf { it.cents }))
    return DeductionBar(segments, unknown, counted, ceilingCents, room, excess, scale)
}

/* ---------- 4b-02: regime and its source ---------- */

/** The word for where a value came from; ND has none (the caller prints "n/d"). */
fun originWord(o: Origin): String? = when (o) {
    Origin.DOCUMENTO -> "documentato"
    Origin.DICHIARATO -> "dichiarato"
    Origin.CALCOLATO -> "calcolato"
    Origin.ND -> null
}

/** "TFR", "TFS" or "n/d" for a stated regime. */
fun regimeWord(s: Sourced<Coded<SeveranceRegime>>): String = when (s.value?.known) {
    SeveranceRegime.TFR -> "TFR"
    SeveranceRegime.TFS -> "TFS"
    SeveranceRegime.ND, null -> "n/d"
}

/** "Regime TFR · documentato" (4b-02); a missing regime is only "Regime n/d". */
fun regimeLine(s: Sourced<Coded<SeveranceRegime>>): String {
    val word = regimeWord(s)
    return "Regime $word" + (if (word == "n/d") "" else originWord(s.origin)?.let { " · $it" } ?: "")
}

/**
 * The name of the document a stated value was read from, "<field> <mmm yyyy>" (4b-02, for example a payslip). Only a document
 * origin has one. The archive stores the field label and the document date, not a document type, so the field label stands
 * in and a missing one reads "documento".
 */
fun sourceDocLabel(origin: Origin, fieldLabel: String?, asOf: LocalDate?, monthShort: (YearMonth) -> String): String? {
    if (origin != Origin.DOCUMENTO) return null
    val what = fieldLabel?.takeIf { it.isNotBlank() } ?: "documento"
    return asOf?.let { "$what ${monthShort(YearMonth.from(it))} ${it.year}" } ?: what
}

/**
 * The regime shown on the Futuro card (4a-07): the stated regimes of all positions. One known regime reads as itself, several
 * different ones are listed, none known is "n/d". Nothing is inferred from the kind of position.
 */
fun portfolioRegime(positions: List<IncomePosition>): String {
    val known = positions.mapNotNull { it.severanceRegime.value?.known }.filter { it != SeveranceRegime.ND }.distinct().sortedBy { it.ordinal }
    return if (known.isEmpty()) "n/d" else known.joinToString(" · ") { it.name }
}

/* ---------- 4b-08: which parts a position's block has ---------- */

enum class BlockPart { REGIME, TFR_DESTINATION, FUNDS, TFR_FIGURATIVO, GESTIONE, CONTRIBUTIONS_BY_GESTIONE, PENSION_SLIP }

/**
 * Council 4 d, per position: privato = TFR destination and funds; pubblico = regime, funds and, only beside a negotiated
 * fund, the TFR figurativo strip; autonomo and collaboratore = gestione and contributions by gestione; pensionato = the pension
 * slip. A position without a block (studente, nessun reddito) has no parts.
 */
fun blockParts(kind: PositionKind, funds: List<PensionFund>): List<BlockPart> = when (kind) {
    PositionKind.DIPENDENTE_PRIVATO -> listOf(BlockPart.TFR_DESTINATION, BlockPart.FUNDS)
    PositionKind.DIPENDENTE_PUBBLICO ->
        listOf(BlockPart.REGIME, BlockPart.FUNDS) + if (funds.any { it.formType.value?.known == FundFormType.NEGOZIALE }) listOf(BlockPart.TFR_FIGURATIVO) else emptyList()
    PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO, PositionKind.COLLABORATORE ->
        listOf(BlockPart.GESTIONE, BlockPart.CONTRIBUTIONS_BY_GESTIONE)
    PositionKind.PENSIONATO -> listOf(BlockPart.PENSION_SLIP)
    PositionKind.STUDENTE, PositionKind.NESSUN_REDDITO -> emptyList()
}

/** The positions that get a block, in the archive's order. */
fun previdenzaBlocks(positions: List<IncomePosition>): List<IncomePosition> =
    positions.filter { p -> p.kind.known?.let { blockParts(it, emptyList()).isNotEmpty() } == true }

/* ---------- 4b-04: fund card figures ---------- */

/** What a party put in: an amount, an attested "nessuno", or unknown. */
sealed interface PartyFigure {
    data class Amount(val cents: Long) : PartyFigure
    data object Nessuno : PartyFigure
    data object Nd : PartyFigure
}

fun ownFigure(ownCents: Long?): PartyFigure = ownCents?.let { PartyFigure.Amount(it) } ?: PartyFigure.Nd

/** Datore "nessuno" only when attested (a stated employer rate of zero); a recorded amount wins; otherwise n/d. */
fun employerFigure(employerCents: Long?, fund: PensionFund?): PartyFigure {
    if (employerCents != null) return PartyFigure.Amount(employerCents)
    val rate = fund?.employerPct
    return if (rate != null && rate.origin != Origin.ND && rate.value?.signum() == 0) PartyFigure.Nessuno else PartyFigure.Nd
}

/** The fund's date stamp: "al <date>" while fresh, "datato <date>" after [staleAfterDays] days. */
data class FundStamp(val date: LocalDate, val stale: Boolean)

fun fundStamp(date: LocalDate?, today: LocalDate, staleAfterDays: Long = 35): FundStamp? =
    date?.let { FundStamp(it, ChronoUnit.DAYS.between(it, today) > staleAfterDays) }
