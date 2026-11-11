package xyz.grtlabs.pilastri.domain.rules

import android.content.res.AssetManager
import xyz.grtlabs.pilastri.data.PositionKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

/**
 * A figure for a screen: [value] with its [status] (EXACT from VERIFIED rows only, STIMA if any input is CARRIED,
 * UNVERIFIED or calculated, ND otherwise) and the law row ids it rests on ([sourceIds]; a `slot:` entry is a personal
 * value). ND has no value and is never shown as zero; [reason] says why. [caveats] are open questions (row ids) the
 * research settled one way; show them in "Fonti e regole", they do not change the status.
 */
data class RuleValue<out T : Any>(
    val value: T?,
    val status: RuleStatus,
    val sourceIds: List<String>,
    val reason: String? = null,
    val caveats: List<String> = emptyList(),
) {
    init { require((status == RuleStatus.ND) == (value == null)) { "RuleValue: ND exactly when the value is missing" } }

    companion object {
        fun <T : Any> nd(reason: String, sourceIds: List<String> = emptyList()): RuleValue<T> = RuleValue(null, RuleStatus.ND, sourceIds, reason)
    }
}

/**
 * One contribution to a complementary pension form for one period. Worker includes contributions paid for fiscally
 * dependent family members. [tfrCents] is never counted. Null = not known (the person-year total is then n/d).
 */
data class FundContribution(
    val fundId: String,
    val period: YearMonth,
    val workerCents: Long?,
    val employerCents: Long?,
    val tfrCents: Long? = null,
)

/** [counted] = worker + employer of the year after dedupe; [deductible] = min(counted, ceiling); [excess] = counted − deductible. */
data class DeductionCeiling(
    val year: Int,
    val ceiling: RuleValue<Long>,
    val counted: RuleValue<Long>,
    val deductible: RuleValue<Long>,
    val excess: RuleValue<Long>,
    /** Records dropped because another record had the same (fundId, period). */
    val duplicatesDropped: Int,
)

/** Figures of the dichiarazione for [taxYear]: imposta netta (after detrazioni, crediti, ritenute) and acconti already paid for it. */
data class PreviousReturn(
    val taxYear: Int,
    val impostaNettaCents: Long,
    val accontiVersatiCents: Long?,
    /** True for ISA subjects and forfettari (two equal acconti); null = not known (acconti n/d). */
    val isaOrForfettario: Boolean?,
)

enum class F24Kind { SALDO, ACCONTO_1, ACCONTO_2 }

/** One F24 commitment. [lateWithSurcharge] is the later date with the +0.40% of row `ord.saldo.late_surcharge`. */
data class F24Due(
    val kind: F24Kind,
    val taxYear: Int,
    val due: RuleValue<LocalDate>,
    val lateWithSurcharge: RuleValue<LocalDate>?,
    val amount: RuleValue<Long>,
)

/**
 * Facade for the screens (slice 5c) over the validated law pack. Screens never see steps or rows; every result is a
 * [RuleValue]. Build it once ([fromAssets]) and share it; it is immutable.
 *
 * Not here: PackVersion history, undo and the PERSONAL layer's archive (slice 4). The engine itself is [Interpreter].
 */
class Rules(val pack: Pack) {
    private val interpreter = Interpreter(pack)

    /**
     * Imposta lorda IRPEF on a person-year [taxableCents] for tax [year] (rule `irpef.gross`, brackets by year).
     * [kinds] are the person's position kinds in that year; a combination the pack does not define (any forfettario
     * position) is ND. Null = a plain schedule query. Years without sourced brackets are ND.
     */
    fun irpefGross(taxableCents: Long, year: Int, kinds: Set<PositionKind>? = null): RuleValue<Long> {
        val e = interpreter.evaluate(IRPEF, EvalContext(yearEnd(year), mapOf("taxable" to RuleInput.cents(taxableCents)), kinds = kinds))
        return e.output.money(e.caveats)
    }

    /**
     * Complementary-pension deduction ceiling for one person-year: all forms plus employer, deduplicated by
     * (fundId, period), TFR excluded. The ceiling itself is known even when [contributions] are incomplete; with no
     * contribution of [year] or any unknown amount, counted, deductible and excess are ND. Duplicate records with
     * different amounts make the totals STIMA.
     */
    fun deductionCeiling(year: Int, contributions: List<FundContribution>): DeductionCeiling {
        val inYear = contributions.filter { it.period.year == year }
        val groups = inYear.groupBy { it.fundId to it.period }
        val kept = groups.values.map { it.first() }
        val conflict = groups.values.any { g -> g.map { it.workerCents to it.employerCents }.distinct().size > 1 }
        val missing = when {
            kept.isEmpty() -> "nessun contributo registrato per il $year"
            kept.any { it.workerCents == null || it.employerCents == null } -> "contributo lavoratore o datore non noto"
            else -> null
        }
        val counted = if (missing != null) RuleInput.ND
            else RuleInput.cents(kept.sumOf { it.workerCents!! + it.employerCents!! }, if (conflict) RuleStatus.STIMA else RuleStatus.EXACT)
        val e = interpreter.evaluate(DEDUCTION, EvalContext(yearEnd(year), mapOf("counted" to counted)))
        fun pick(name: String): RuleValue<Long> = e.values[name].money(e.caveats).let { v ->
            if (missing != null && v.status == RuleStatus.ND && name != "ceiling") v.copy(reason = missing) else v
        }
        val countedValue: RuleValue<Long> = if (missing != null) RuleValue.nd(missing) else RuleValue(counted.value.let { (it as RValue.Num).amount.longValueExact() }, counted.status, emptyList())
        return DeductionCeiling(year, pick("ceiling"), countedValue, pick("deductible"), pick("excess"), inYear.size - kept.size)
    }

    /** First-job extra deduction (art. 8 c. 6 D.Lgs. 252/2005): ND while `pc.deduction.firstjob.unused` is INCERTO. */
    fun firstJobExtraDeduction(year: Int): RuleValue<Long> {
        val e = interpreter.evaluate(FIRSTJOB, EvalContext(yearEnd(year)))
        return e.output.money(e.caveats)
    }

    /**
     * F24 commitments paid in [year]: saldo of tax year year−1 and the two acconti of [year]. Dates come from row
     * `ord.saldo.deadline` (a weekend rolls to Monday; public holidays are not in the pack and none of the stored
     * dates or their rolled Mondays is a national holiday). Amounts need the dichiarazione of year−1 ([previousReturn]);
     * they are always at most STIMA, and ND without it.
     */
    fun f24Schedule(year: Int, previousReturn: PreviousReturn?): List<F24Due> {
        val row = pack.at(DEADLINES, LocalDate.of(year, 1, 1))
        fun dueDate(key: String): RuleValue<LocalDate> {
            if (row == null) return RuleValue.nd("$DEADLINES: nessuna riga valida nel $year", listOf(DEADLINES))
            val status = row.status.toRuleStatus()
            if (status == RuleStatus.ND) return RuleValue.nd("$DEADLINES INCERTO", listOf(DEADLINES))
            val md = ((row.value as? RowValue.Table)?.entries?.get(key) as? RValue.Str)?.text
                ?: return RuleValue.nd("$DEADLINES: '$key' assente", listOf(DEADLINES))
            var d = MonthDay.parse("--$md").atYear(year)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            return RuleValue(d, status, listOf(DEADLINES))
        }
        val (saldo, first, second) = amounts(year, previousReturn)
        return listOf(
            F24Due(F24Kind.SALDO, year - 1, dueDate("saldo"), dueDate("saldo_late"), saldo),
            F24Due(F24Kind.ACCONTO_1, year, dueDate("saldo"), dueDate("saldo_late"), first),
            F24Due(F24Kind.ACCONTO_2, year, dueDate("acconto_2"), null, second),
        )
    }

    private fun amounts(year: Int, ret: PreviousReturn?): Triple<RuleValue<Long>, RuleValue<Long>, RuleValue<Long>> {
        if (ret == null || ret.taxYear != year - 1) {
            val nd = RuleValue.nd<Long>("serve la dichiarazione dei redditi ${year - 1}")
            return Triple(nd, nd, nd)
        }
        val date = LocalDate.of(year, 6, 30)
        val imposta = RuleInput.cents(ret.impostaNettaCents)
        val saldo = ret.accontiVersatiCents?.let { paid ->
            val e = interpreter.evaluate(SALDO, EvalContext(date, mapOf("imposta" to imposta, "acconti" to RuleInput.cents(paid))))
            e.output.money(e.caveats).atMostStima()
        } ?: RuleValue.nd("acconti versati per il ${year - 1} non noti")
        val accontoRule = when (ret.isaOrForfettario) {
            null -> return Triple(saldo, RuleValue.nd("regime ISA/forfettario non noto"), RuleValue.nd("regime ISA/forfettario non noto"))
            true -> ACCONTO_ISA
            false -> ACCONTO_ORD
        }
        val e = interpreter.evaluate(accontoRule, EvalContext(date, mapOf("imposta" to imposta)))
        return Triple(saldo, e.values["first"].money(e.caveats).atMostStima(), e.values["second"].money(e.caveats).atMostStima())
    }

    companion object {
        const val PACK_ASSET = "rules/law-2026.json"
        const val SNAPSHOT_ASSET = "rules/snapshots-2026.json"
        const val IRPEF = "irpef.gross"
        const val DEDUCTION = "pc.deduction.person_year"
        const val FIRSTJOB = "pc.deduction.firstjob.cap"
        const val ACCONTO_ORD = "f24.acconto.ordinario"
        const val ACCONTO_ISA = "f24.acconto.isa"
        const val SALDO = "f24.saldo"
        const val DEADLINES = "ord.saldo.deadline"

        /** Parses and validates; throws [PackInvalidException] if the pack breaks any load rule. */
        fun fromJson(packJson: String, snapshotJson: String): Rules = Rules(Pack.load(packJson, snapshotJson))

        /** Loads the shipped LAW pack and its snapshot store from the app assets. */
        fun fromAssets(assets: AssetManager): Rules = fromJson(
            assets.open(PACK_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
            assets.open(SNAPSHOT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
        )

        private fun yearEnd(year: Int) = LocalDate.of(year, 12, 31)

        private fun Res?.money(caveats: List<String>): RuleValue<Long> = when {
            this == null -> RuleValue.nd("n/d")
            status == RuleStatus.ND -> RuleValue(null, RuleStatus.ND, sources.toList(), reason, caveats)
            else -> RuleValue((value as RValue.Num).amount.longValueExact(), status, sources.toList(), null, caveats)
        }

        private fun RuleValue<Long>.atMostStima(): RuleValue<Long> = if (status == RuleStatus.EXACT) copy(status = RuleStatus.STIMA) else this
    }
}
