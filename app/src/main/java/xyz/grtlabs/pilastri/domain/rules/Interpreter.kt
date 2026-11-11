package xyz.grtlabs.pilastri.domain.rules

import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.PackStatus
import xyz.grtlabs.pilastri.data.ParamClass
import xyz.grtlabs.pilastri.data.PositionKind
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** Result status shown by screens: VERIFIED inputs only = EXACT; any "stima" input = STIMA; any n/d input = ND. */
enum class RuleStatus { EXACT, STIMA, ND }

fun PackStatus.toRuleStatus(): RuleStatus = when (this) {
    PackStatus.VERIFIED -> RuleStatus.EXACT
    PackStatus.CARRIED, PackStatus.UNVERIFIED -> RuleStatus.STIMA
    PackStatus.INCERTO -> RuleStatus.ND
}

internal fun worst(a: RuleStatus, b: RuleStatus): RuleStatus = if (a.ordinal >= b.ordinal) a else b

/** A computed value with its status and the law rows (and `slot:` names) it rests on. ND carries no value, never zero. */
data class Res(val value: RValue?, val status: RuleStatus, val sources: Set<String>, val reason: String? = null) {
    init { require((status == RuleStatus.ND) == (value == null)) { "Res: ND exactly when the value is missing" } }

    fun joined(status: RuleStatus, sources: Set<String>, reason: String? = null): Res {
        val all = this.sources + sources
        val s = worst(this.status, status)
        return if (s == RuleStatus.ND) Res(null, RuleStatus.ND, all, this.reason ?: reason ?: "n/d") else Res(value, s, all, this.reason)
    }

    companion object {
        fun nd(reason: String, sources: Set<String> = emptySet()) = Res(null, RuleStatus.ND, sources, reason)
    }
}

/** A runtime input. EUR inputs must be whole cents. */
data class RuleInput(val value: RValue?, val status: RuleStatus) {
    init {
        require((status == RuleStatus.ND) == (value == null))
        if (value is RValue.Num && value.unit == RuleUnit.EUR) require(value.amount.stripTrailingZeros().scale() <= 0) { "EUR input must be whole cents" }
    }

    companion object {
        val ND = RuleInput(null, RuleStatus.ND)
        fun exact(value: RValue) = RuleInput(value, RuleStatus.EXACT)
        fun stima(value: RValue) = RuleInput(value, RuleStatus.STIMA)
        fun cents(cents: Long, status: RuleStatus = RuleStatus.EXACT) = RuleInput(RValue.cents(cents), status)
    }
}

/**
 * A PERSONAL or SCENARIO value for a declared personalSlot. Status: origin ND = n/d; CALCOLATO or SCENARIO = stima;
 * a document or the person's own declaration = exact.
 */
data class LayerValue(val value: RValue?, val paramClass: ParamClass, val origin: Origin) {
    init {
        require((origin == Origin.ND) == (value == null))
        require(paramClass != ParamClass.LAW) { "LAW values live in the pack, not in a layer" }
    }

    val status: RuleStatus
        get() = when {
            origin == Origin.ND -> RuleStatus.ND
            paramClass == ParamClass.SCENARIO || origin == Origin.CALCOLATO -> RuleStatus.STIMA
            else -> RuleStatus.EXACT
        }
}

/** ACTUALS never reads the SCENARIO layer; FUTURO reads SCENARIO over PERSONAL. */
enum class Mode { ACTUALS, FUTURO }

/**
 * @param date picks the version of every law row (and of the rule itself).
 * @param kinds the person's (PERSON_YEAR) or the position's kinds; null = a plain schedule query with no position context.
 */
data class EvalContext(
    val date: LocalDate,
    val inputs: Map<String, RuleInput> = emptyMap(),
    val personal: Map<String, LayerValue> = emptyMap(),
    val scenario: Map<String, LayerValue> = emptyMap(),
    val mode: Mode = Mode.ACTUALS,
    val kinds: Set<PositionKind>? = null,
    val gestioni: Set<Gestione>? = null,
    val otherCover: Boolean? = null,
) {
    init {
        require(personal.values.all { it.paramClass == ParamClass.PERSONAL_DOCUMENT || it.paramClass == ParamClass.PERSONAL_DECLARED }) { "personal layer takes PERSONAL_* values" }
        require(scenario.values.all { it.paramClass == ParamClass.SCENARIO }) { "scenario layer takes SCENARIO values" }
    }
}

/** [values] are every step's output, each joined with the rule's own status and `requires`. */
data class Evaluation(val ruleId: String, val output: Res, val values: Map<String, Res>, val caveats: List<String>)

/** Evaluates rule rows of a validated [Pack]. Stateless; safe to share. */
class Interpreter(private val pack: Pack) {

    fun evaluate(ruleId: String, ctx: EvalContext): Evaluation {
        val versions = pack.versions(ruleId)
        require(versions.isNotEmpty() && versions.all { it.rule != null }) { "'$ruleId' is not a rule row" }
        val layerKeys = ctx.personal.keys + ctx.scenario.keys
        for (k in layerKeys) {
            require(k !in pack.ids) { "'$k' is a law row: a personal value never overrides a law row" }
            require(k in pack.declaredSlots) { "'$k' is not a personalSlot declared by the pack" }
        }
        val row = versions.firstOrNull { it.validAt(ctx.date) }
            ?: return Evaluation(ruleId, Res.nd("$ruleId: nessuna versione valida al ${ctx.date}", setOf(ruleId)), emptyMap(), emptyList())
        val rule = row.rule!!
        val caveats = rule.caveats.filter { pack.at(it, ctx.date) != null }
        notApplicable(row, ctx)?.let { return Evaluation(ruleId, Res.nd(it, setOf(ruleId)), emptyMap(), caveats) }

        var baseStatus = row.status.toRuleStatus()
        val baseSources = linkedSetOf(ruleId)
        var baseReason: String? = if (baseStatus == RuleStatus.ND) "$ruleId INCERTO" else null
        for (r in rule.requires) {
            val req = pack.at(r, ctx.date) ?: continue
            baseSources += r
            val s = req.status.toRuleStatus()
            if (s == RuleStatus.ND && baseReason == null) baseReason = "$r INCERTO"
            baseStatus = worst(baseStatus, s)
        }
        val env = Env(ctx, rule, LinkedHashMap())
        for (step in rule.steps) env.values[step.out] = step(step, env)
        val joined = env.values.mapValues { (_, v) -> v.joined(baseStatus, baseSources, baseReason) }
        val output = joined[rule.output] ?: Res.nd("output mancante", baseSources)
        return Evaluation(ruleId, output, joined, caveats)
    }

    private class Env(val ctx: EvalContext, val rule: RuleDef, val values: LinkedHashMap<String, Res>)

    private fun notApplicable(row: LawRow, ctx: EvalContext): String? {
        val a = row.appliesTo
        ctx.kinds?.let { k -> if (a.kinds.isNotEmpty() && !a.kinds.containsAll(k)) return "combinazione non definita nel pacchetto: ${(k - a.kinds).sorted()}" }
        ctx.gestioni?.let { g -> if (a.gestioni.isNotEmpty() && !a.gestioni.containsAll(g)) return "gestione non coperta: ${(g - a.gestioni).sorted()}" }
        if (a.otherCover != null && ctx.kinds != null) {
            if (ctx.otherCover == null) return "altra copertura obbligatoria non nota"
            if (ctx.otherCover != a.otherCover) return "non si applica con altraCopertura=${ctx.otherCover}"
        }
        return null
    }

    /** Accumulates status and sources over operands; the first n/d wins the reason. */
    private class Acc {
        var status = RuleStatus.EXACT
        val sources = linkedSetOf<String>()
        var nd: String? = null
        fun take(r: Res): RValue? {
            sources += r.sources
            if (r.status == RuleStatus.ND) { if (nd == null) nd = r.reason ?: "n/d"; return null }
            status = worst(status, r.status)
            return r.value
        }
        fun num(r: Res): BigDecimal? = (take(r) as? RValue.Num)?.amount
        fun result(v: RValue?): Res = if (nd != null || v == null) Res.nd(nd ?: "n/d", sources) else Res(v, status, sources)
        fun fail(reason: String): Res { if (nd == null) nd = reason; return result(null) }
    }

    private fun step(step: Step, env: Env): Res {
        val guard = Acc()
        for (g in step.guards) {
            val decision = guard(g, env, guard) ?: return guard.result(null)
            if (!decision) {
                val e = step.otherwise ?: return guard.fail("guard senza else")
                return operand(e, env).joined(guard.status, guard.sources)
            }
        }
        val acc = Acc()
        acc.status = guard.status
        acc.sources += guard.sources
        val raw: BigDecimal? = when (step) {
            is BracketsStep -> brackets(step, env, acc)
            is PiecewiseStep -> piecewise(step, env, acc)
            is RateStep -> rate(step, env, acc)
            is SumStep -> step.terms.fold(BigDecimal.ZERO as BigDecimal?) { sum, t -> val v = acc.num(operand(t.operand, env)); if (sum == null || v == null) null else if (t.negate) sum - v else sum + v }
            is LookupStep -> return lookup(step, env, acc)
        }
        if (acc.nd != null || raw == null) return acc.result(null)
        val unit = outUnit(step, env)
        return acc.result(RValue.Num(step.rounding.applyTo(raw, unit), unit))
    }

    private fun outUnit(step: Step, env: Env): RuleUnit = when (step) {
        is BracketsStep -> RuleUnit.EUR
        is PiecewiseStep -> unitOf(step.pieces.first().base, env)
        is RateStep -> unitOf(step.input, env)
        is SumStep -> unitOf(step.terms.first().operand, env)
        is LookupStep -> RuleUnit.TEXT
    }

    private fun unitOf(op: Operand, env: Env): RuleUnit = when (op) {
        is Operand.Input -> env.rule.inputs.getValue(op.name)
        is Operand.Slot -> env.rule.personalSlots.getValue(op.name)
        is Operand.Ref -> env.values[op.name]?.value?.unit ?: unitOfStep(op.name, env)
        is Operand.Lit -> op.value.unit
        is Operand.Param -> pack.versions(op.id).first().unit
        Operand.Nd -> RuleUnit.NOTE
    }

    private fun unitOfStep(name: String, env: Env): RuleUnit {
        val s = env.rule.steps.first { it.out == name }
        return if (s is LookupStep) s.table?.let { pack.versions(it.id).first().unit }
            ?: pack.ids.first { it.startsWith("${s.family}.") }.let { pack.versions(it).first().unit }
        else outUnit(s, env)
    }

    private fun guard(g: Guard, env: Env, acc: Acc): Boolean? {
        if (g.op == GuardOp.KNOWN) {
            val r = operand(g.a, env)
            acc.sources += r.sources
            if (r.status != RuleStatus.ND) acc.status = worst(acc.status, r.status)
            return r.status != RuleStatus.ND
        }
        val a = acc.take(operand(g.a, env)) ?: return null
        if (g.op == GuardOp.IN) return keyOf(a) in g.values
        val b = acc.take(operand(g.b ?: return null, env)) ?: return null
        val cmp = if (a is RValue.Num && b is RValue.Num) a.amount.compareTo(b.amount) else if (a == b) 0 else 1
        return when (g.op) {
            GuardOp.GT -> cmp > 0
            GuardOp.GE -> cmp >= 0
            GuardOp.LT -> cmp < 0
            GuardOp.LE -> cmp <= 0
            GuardOp.EQ -> cmp == 0
            GuardOp.NE -> cmp != 0
            GuardOp.KNOWN, GuardOp.IN -> null
        }
    }

    private fun keyOf(v: RValue): String = when (v) {
        is RValue.Num -> v.amount.stripTrailingZeros().toPlainString()
        is RValue.Str -> v.text
    }

    // ---- operands ------------------------------------------------------------------------------------------

    private fun operand(op: Operand, env: Env): Res = when (op) {
        is Operand.Input -> env.ctx.inputs[op.name]?.let { if (it.status == RuleStatus.ND) Res.nd("dato mancante: ${op.name}") else Res(it.value, it.status, emptySet()) }
            ?: Res.nd("dato mancante: ${op.name}")
        is Operand.Slot -> slot(op.name, env.ctx)
        is Operand.Ref -> env.values[op.name] ?: Res.nd("passo ${op.name} non calcolato")
        is Operand.Lit -> Res(op.value, RuleStatus.EXACT, emptySet())
        Operand.Nd -> Res.nd("non definito dal pacchetto")
        is Operand.Param -> param(op.id, env.ctx.date, env.ctx).let { (row, res) ->
            if (row == null || res.status == RuleStatus.ND) res
            else (row.value as? RowValue.Scalar)?.let { Res(it.value, res.status, res.sources) } ?: Res.nd("${op.id}: non scalare", res.sources)
        }
    }

    private fun slot(name: String, ctx: EvalContext): Res {
        val v = (if (ctx.mode == Mode.FUTURO) ctx.scenario[name] else null) ?: ctx.personal[name]
            ?: return Res.nd("valore personale mancante: $name", setOf("slot:$name"))
        return if (v.status == RuleStatus.ND) Res.nd("valore personale n/d: $name", setOf("slot:$name")) else Res(v.value, v.status, setOf("slot:$name"))
    }

    /** The row version valid at [date] with its status; INCERTO, a missing value or an unfilled gate slot is n/d. */
    private fun param(id: String, date: LocalDate, ctx: EvalContext): Pair<LawRow?, Res> {
        val row = pack.at(id, date) ?: return null to Res.nd("$id: nessuna riga valida al $date", setOf(id))
        val status = row.status.toRuleStatus()
        val ok = Res(RValue.text("ok"), RuleStatus.EXACT, setOf(id))
        if (status == RuleStatus.ND) return row to Res.nd("$id INCERTO", setOf(id))
        if (row.value == null) return row to Res.nd("$id senza valore", setOf(id))
        row.personalSlot?.let { s -> val g = slot(s, ctx); if (g.status == RuleStatus.ND) return row to Res.nd("$id richiede $s", setOf(id) + g.sources) }
        return row to ok.joined(status, emptySet())
    }

    // ---- step bodies -----------------------------------------------------------------------------------------

    private fun brackets(step: BracketsStep, env: Env, acc: Acc): BigDecimal? {
        val x = acc.num(operand(step.input, env)) ?: return null
        val bands: List<Pair<BigDecimal?, BigDecimal>> = if (step.schedule != null) {
            val key = acc.take(operand(step.key ?: return null, env))?.let(::keyOf) ?: return null
            val (row, res) = param(step.schedule.id, env.ctx.date, env.ctx)
            acc.take(res) ?: return null
            val list = (row?.value as? RowValue.Schedule)?.schedules?.get(key) ?: run { acc.fail("${step.schedule.id}: nessuno scaglione per '$key'"); return null }
            list.map { b -> b.upToCents?.let { BigDecimal.valueOf(it) } to b.rate }
        } else step.bands.map { b -> (b.upTo?.let { acc.num(operand(it, env)) ?: return null }) to (acc.num(operand(b.rate, env)) ?: return null) }
        val bounds = bands.dropLast(1).map { it.first }
        if (bounds.any { it == null } || bounds.zipWithNext().any { (a, b) -> a!! >= b!! }) { acc.fail("scaglioni non crescenti"); return null }
        var tax = BigDecimal.ZERO
        var lower = BigDecimal.ZERO
        for ((top, rate) in bands) {
            val reach = if (top == null) x else x.min(top)
            if (reach > lower) tax += (reach - lower) * rate
            if (top == null || x <= top) break
            lower = top
        }
        return tax
    }

    private fun piecewise(step: PiecewiseStep, env: Env, acc: Acc): BigDecimal? {
        val x = acc.num(operand(step.input, env)) ?: return null
        for (p in step.pieces) {
            if (p.upTo != null) { val up = acc.num(operand(p.upTo, env)) ?: return null; if (x > up) continue }
            val base = acc.num(operand(p.base, env)) ?: return null
            if (p.slope == null) return base
            val slope = acc.num(operand(p.slope, env)) ?: return null
            val anchor = acc.num(operand(p.anchor ?: return null, env)) ?: return null
            val span = acc.num(operand(p.span ?: return null, env)) ?: return null
            if (span.signum() == 0) { acc.fail("divisione per zero"); return null }
            val q = if (p.quotient == xyz.grtlabs.pilastri.data.Rounding.TRUNC_4DEC) (anchor - x).divide(span, 4, RoundingMode.DOWN)
                else (anchor - x).divide(span, MathContext.DECIMAL128)
            return base + slope * q
        }
        return null
    }

    private fun rate(step: RateStep, env: Env, acc: Acc): BigDecimal? {
        val x = acc.num(operand(step.input, env)) ?: return null
        val r = if (step.rate != null) acc.num(operand(step.rate, env)) ?: return null else {
            val num = acc.num(operand(step.num ?: return null, env)) ?: return null
            val den = acc.num(operand(step.den ?: return null, env)) ?: return null
            if (den.signum() == 0) { acc.fail("divisione per zero"); return null }
            num.divide(den, MathContext.DECIMAL128)
        }
        var v = x * r
        step.min?.let { m -> v = v.max(acc.num(operand(m, env)) ?: return null) }
        step.max?.let { m -> v = v.min(acc.num(operand(m, env)) ?: return null) }
        return v
    }

    private fun lookup(step: LookupStep, env: Env, acc: Acc): Res {
        val key = acc.take(operand(step.key, env))?.let(::keyOf) ?: return acc.result(null)
        val date = if (step.date == null) env.ctx.date else {
            val d = acc.take(operand(step.date, env)) as? RValue.Str ?: return acc.result(null)
            runCatching { LocalDate.parse(d.text) }.getOrNull() ?: return acc.fail("data non valida: ${d.text}")
        }
        val value: RValue? = if (step.table != null) {
            val (row, res) = param(step.table.id, date, env.ctx)
            acc.take(res) ?: return acc.result(null)
            (row?.value as? RowValue.Table)?.entries?.get(key) ?: return acc.fail("${step.table.id}: chiave '$key' assente")
        } else {
            val id = "${step.family}.$key"
            if (pack.versions(id).isEmpty()) return acc.fail("${step.family}: chiave '$key' assente")
            val (row, res) = param(id, date, env.ctx)
            acc.take(res) ?: return acc.result(null)
            (row?.value as? RowValue.Scalar)?.value
        }
        if (value == null) return acc.result(null)
        val rounded = if (value is RValue.Num) RValue.Num(step.rounding.applyTo(value.amount, value.unit), value.unit) else value
        return acc.result(rounded)
    }
}
