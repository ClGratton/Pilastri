package xyz.grtlabs.pilastri.domain.rules

import xyz.grtlabs.pilastri.data.Rounding
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/*
 * Rule pipeline model (Council 4 c, COUNCIL.md). A rule is an ordered list of [Step]s; each step writes one named
 * value from earlier names, law parameters, personal slots and inputs. Pipeline, not DAG: no forward references.
 * The step kinds are closed: a new kind is an app update, never a pack edit.
 */

/** Unit of a named value. EUR is held in cents; NOTE rows are documentation for people and are never computed. */
enum class RuleUnit {
    EUR, RATIO, COUNT, TEXT, DATE, NOTE;

    val numeric: Boolean get() = this == EUR || this == RATIO || this == COUNT
}

/** A stored or computed value. EUR amounts are exact cents and are integral after every money step. */
sealed interface RValue {
    val unit: RuleUnit

    data class Num(val amount: BigDecimal, override val unit: RuleUnit) : RValue {
        init { require(unit.numeric) { "Num needs a numeric unit, not $unit" } }
    }

    /** TEXT, or DATE as ISO `yyyy-MM-dd` or month-day `MM-dd`. */
    data class Str(val text: String, override val unit: RuleUnit) : RValue {
        init {
            require(unit == RuleUnit.TEXT || unit == RuleUnit.DATE) { "Str needs TEXT or DATE, not $unit" }
            require(unit != RuleUnit.DATE || DATE_RE.matches(text)) { "bad date '$text'" }
        }
    }

    companion object {
        private val DATE_RE = Regex("""(\d{4}-)?\d{2}-\d{2}""")
        fun cents(cents: Long): RValue = Num(BigDecimal.valueOf(cents), RuleUnit.EUR)
        fun ratio(value: String): RValue = Num(BigDecimal(value), RuleUnit.RATIO)
        fun count(value: Long): RValue = Num(BigDecimal.valueOf(value), RuleUnit.COUNT)
        fun text(value: String): RValue = Str(value, RuleUnit.TEXT)
        fun date(value: LocalDate): RValue = Str(value.toString(), RuleUnit.DATE)

        /** Parses a pack literal. EUR is written in euros ("5164.57") and becomes exact cents; sub-cent EUR is rejected. */
        fun parse(unit: RuleUnit, raw: String): RValue = when (unit) {
            RuleUnit.EUR -> {
                val c = BigDecimal(raw.trim()).movePointRight(2)
                require(c.stripTrailingZeros().scale() <= 0) { "EUR '$raw' has more than two decimals" }
                Num(c.setScale(0), RuleUnit.EUR)
            }
            RuleUnit.RATIO, RuleUnit.COUNT -> Num(BigDecimal(raw.trim()), unit)
            RuleUnit.TEXT, RuleUnit.DATE -> Str(raw, unit)
            RuleUnit.NOTE -> throw IllegalArgumentException("NOTE has no computable value")
        }
    }
}

/** Where a step reads a value from. JSON form: `in:x`, `par:row.id`, `slot:x`, `val:x`, `eur:1955`, `ratio:0.5`, `count:365`, `text:10`, `date:2027-01-01`, `nd`. */
sealed interface Operand {
    /** A runtime input of the rule (declared in `inputs`). */
    data class Input(val name: String) : Operand
    /** A LAW row, resolved by id at the evaluation date. */
    data class Param(val id: String) : Operand
    /** A personal (or, in Futuro, scenario) value filling a declared `personalSlot`. */
    data class Slot(val name: String) : Operand
    /** An earlier step's output. */
    data class Ref(val name: String) : Operand
    data class Lit(val value: RValue) : Operand
    /** Explicit n/d, allowed only as a guard's `else`. */
    data object Nd : Operand

    companion object {
        fun parse(raw: String): Operand {
            val s = raw.trim()
            if (s == "nd") return Nd
            val i = s.indexOf(':')
            require(i > 0 && i < s.length - 1) { "bad operand '$raw'" }
            val tag = s.substring(0, i)
            val body = s.substring(i + 1)
            return when (tag) {
                "in" -> Input(body)
                "par" -> Param(body)
                "slot" -> Slot(body)
                "val" -> Ref(body)
                "eur" -> Lit(RValue.parse(RuleUnit.EUR, body))
                "ratio" -> Lit(RValue.parse(RuleUnit.RATIO, body))
                "count" -> Lit(RValue.parse(RuleUnit.COUNT, body))
                "text" -> Lit(RValue.parse(RuleUnit.TEXT, body))
                "date" -> Lit(RValue.parse(RuleUnit.DATE, body))
                else -> throw IllegalArgumentException("unknown operand tag '$tag' in '$raw'")
            }
        }
    }
}

/** Fixed `when` predicates. KNOWN tests n/d itself; IN compares a TEXT/COUNT key with literal [Guard.values]. */
enum class GuardOp { GT, GE, LT, LE, EQ, NE, KNOWN, IN }

data class Guard(val op: GuardOp, val a: Operand, val b: Operand? = null, val values: List<String> = emptyList())

enum class StepKind { BRACKETS, PIECEWISE, RATE, LOOKUP, SUM }

/**
 * One pipeline step. When every guard holds the step computes its body; when a guard fails it takes [otherwise]
 * (required whenever [guards] is not empty). [rounding] applies to the body's result.
 */
sealed interface Step {
    val out: String
    val kind: StepKind
    val rounding: Rounding
    val guards: List<Guard>
    val otherwise: Operand?
}

/** Upper bound (inclusive, EUR; null only on the last band) and marginal rate. */
data class Band(val upTo: Operand?, val rate: Operand)

/** Progressive tax on [input] by inline [bands] or by a SCHEDULE row selected with [key]. */
data class BracketsStep(
    override val out: String, override val rounding: Rounding, override val guards: List<Guard>, override val otherwise: Operand?,
    val input: Operand, val bands: List<Band>, val schedule: Operand.Param?, val key: Operand?,
) : Step { override val kind get() = StepKind.BRACKETS }

/** value = base + slope × quotient((anchor − x) / span) on the first piece with x ≤ upTo (null upTo = the rest). */
data class Piece(val upTo: Operand?, val base: Operand, val slope: Operand?, val anchor: Operand?, val span: Operand?, val quotient: Rounding)

data class PiecewiseStep(
    override val out: String, override val rounding: Rounding, override val guards: List<Guard>, override val otherwise: Operand?,
    val input: Operand, val pieces: List<Piece>,
) : Step { override val kind get() = StepKind.PIECEWISE }

/** input × rate, or input × num / den; then clamped to [min], [max]. */
data class RateStep(
    override val out: String, override val rounding: Rounding, override val guards: List<Guard>, override val otherwise: Operand?,
    val input: Operand, val rate: Operand?, val num: Operand?, val den: Operand?, val min: Operand?, val max: Operand?,
) : Step { override val kind get() = StepKind.RATE }

/** Entry [key] of a TABLE row, or the row `family.key`; [date] (default: the evaluation date) picks the row version. */
data class LookupStep(
    override val out: String, override val rounding: Rounding, override val guards: List<Guard>, override val otherwise: Operand?,
    val table: Operand.Param?, val family: String?, val key: Operand, val date: Operand?,
) : Step { override val kind get() = StepKind.LOOKUP }

data class Term(val negate: Boolean, val operand: Operand)

data class SumStep(
    override val out: String, override val rounding: Rounding, override val guards: List<Guard>, override val otherwise: Operand?,
    val terms: List<Term>,
) : Step { override val kind get() = StepKind.SUM }

/** Applies a step [Rounding] to an exact amount (EUR amounts are in cents). */
internal fun Rounding.applyTo(v: BigDecimal, unit: RuleUnit): BigDecimal = when (this) {
    Rounding.NONE -> v
    Rounding.HALF_UP_CENT -> v.setScale(0, RoundingMode.HALF_UP)
    Rounding.FLOOR_CENT -> v.setScale(0, RoundingMode.FLOOR)
    Rounding.TRUNC_4DEC -> v.setScale(4, RoundingMode.DOWN)
    Rounding.FLOOR_UNIT ->
        if (unit == RuleUnit.EUR) v.movePointLeft(2).setScale(0, RoundingMode.FLOOR).movePointRight(2).setScale(0)
        else v.setScale(0, RoundingMode.FLOOR)
}

/**
 * Money stays integral cents: an EUR step rounds to cents (or whole euros); NONE only where the result is integral by
 * construction (SUM of cents, LOOKUP of a stored amount). TRUNC_4DEC is for quotients and rates.
 */
internal fun roundingAllowed(unit: RuleUnit, kind: StepKind, r: Rounding): Boolean = when (unit) {
    RuleUnit.EUR -> r == Rounding.HALF_UP_CENT || r == Rounding.FLOOR_CENT || r == Rounding.FLOOR_UNIT ||
        (r == Rounding.NONE && (kind == StepKind.SUM || kind == StepKind.LOOKUP))
    RuleUnit.RATIO, RuleUnit.COUNT -> r == Rounding.NONE || r == Rounding.TRUNC_4DEC || r == Rounding.FLOOR_UNIT
    RuleUnit.TEXT, RuleUnit.DATE -> r == Rounding.NONE && kind == StepKind.LOOKUP
    RuleUnit.NOTE -> false
}
