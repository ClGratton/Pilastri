package xyz.grtlabs.pilastri.domain.rules

import xyz.grtlabs.pilastri.data.PackStatus
import xyz.grtlabs.pilastri.data.Rounding
import java.time.LocalDate

/**
 * Load-time checks of a [Pack]. Every message is "rowId: problem". A pack with any error is not loaded.
 *
 * - Quote rule: quote non-blank, at most 200 chars, a verbatim substring of its stored snapshot, whose text hashes to
 *   the row's snapshotHash. VERIFIED also needs an https source URL, a primary source among its codes and a snapshot
 *   that is not summary-only (a summary-only source is never VERIFIED).
 * - Versions of one id share unit, shape and kind (value vs rule) and never overlap.
 * - Rule rows: pipeline order (no forward references), declared inputs and slots, units per step kind, allowed
 *   roundings, `else` on guarded steps, output unit, existing `requires`/`caveats`, and parameter applicability
 *   covering the rule's kinds. Personal slots never share a name with a law row.
 */
internal class PackValidator(private val pack: Pack) {
    private val errors = mutableListOf<String>()
    private fun err(id: String, msg: String) { errors += "$id: $msg" }

    fun run(): List<String> {
        val retired = pack.retired.map { it.id }.toSet()
        for (row in pack.rows) {
            checkRow(row)
            if (row.id in retired) err(row.id, "row is retired but still present")
        }
        for (id in pack.ids) checkVersions(id, pack.versions(id))
        for (slot in pack.declaredSlots) if (slot in pack.ids) err(slot, "personalSlot shares a name with a law row")
        return errors
    }

    private fun checkRow(row: LawRow) {
        val id = row.id
        if (id.isBlank()) err("#", "blank id")
        if (row.quote.isBlank()) err(id, "missing quote")
        if (row.quote.length > 200) err(id, "quote longer than 200 chars")
        val snap = pack.snapshots[row.snapshotHash]
        if (snap == null) err(id, "snapshot ${row.snapshotHash} not in the store")
        else {
            if (SnapshotStore.sha256(snap.text) != snap.hash) err(id, "snapshot text does not match its hash")
            if (row.quote.isNotBlank() && !snap.text.contains(row.quote)) err(id, "quote is not a verbatim substring of its snapshot")
            if (row.sourceUrl != null && snap.url != null && snap.url != row.sourceUrl) err(id, "snapshot URL differs from sourceUrl")
        }
        for (s in row.sources) if (s !in pack.sources) err(id, "unknown source code $s")
        if (row.status == PackStatus.VERIFIED) {
            if (row.sourceUrl?.startsWith("https://") != true) err(id, "VERIFIED needs an https source URL")
            if (snap?.summaryOnly != false) err(id, "VERIFIED on a summary-only snapshot")
            if (row.sources.none { pack.sources[it]?.read == SourceRead.PRIMARY }) err(id, "VERIFIED without a primary source")
        }
        if (row.validFrom != null && row.validTo != null && row.validTo.isBefore(row.validFrom)) err(id, "validTo before validFrom")
        when {
            row.unit == RuleUnit.NOTE -> if (row.value != null || row.rule != null) err(id, "NOTE rows carry no value or steps")
            row.rule != null -> {
                if (row.value != null) err(id, "a row has a value or steps, not both")
                if (row.shape != Shape.SCALAR) err(id, "rule rows are SCALAR")
                checkRule(row, row.rule)
            }
            row.value == null -> if (row.status != PackStatus.INCERTO) err(id, "missing value (only INCERTO may lack one)")
            else -> checkValueShape(row)
        }
    }

    private fun checkValueShape(row: LawRow) {
        val v = row.value
        val ok = when (row.shape) {
            Shape.SCALAR -> v is RowValue.Scalar
            Shape.TABLE -> v is RowValue.Table && v.entries.isNotEmpty()
            Shape.SCHEDULE -> v is RowValue.Schedule && row.unit == RuleUnit.EUR && v.schedules.values.all(::bandsOk)
        }
        if (!ok) err(row.id, "value does not match shape ${row.shape}/${row.unit}")
    }

    private fun bandsOk(bands: List<ScheduleBand>): Boolean {
        if (bands.isEmpty() || bands.last().upToCents != null) return false
        val bounds = bands.dropLast(1).map { it.upToCents ?: return false }
        return bounds.zipWithNext().all { (a, b) -> a < b }
    }

    private fun checkVersions(id: String, versions: List<LawRow>) {
        if (versions.map { Triple(it.unit, it.shape, it.rule != null) }.distinct().size > 1) err(id, "versions differ in unit, shape or kind")
        val sorted = versions.sortedBy { it.validFrom ?: LocalDate.MIN }
        for ((a, b) in sorted.zipWithNext()) {
            val aEnd = a.validTo
            if (aEnd == null || b.validFrom == null || !aEnd.isBefore(b.validFrom)) err(id, "overlapping versions (${a.validFrom}..${a.validTo} and ${b.validFrom}..${b.validTo})")
        }
    }

    // ---- rules -------------------------------------------------------------------------------------------

    private fun checkRule(row: LawRow, rule: RuleDef) {
        val id = row.id
        val defined = LinkedHashMap<String, RuleUnit>()
        val names = rule.inputs.keys + rule.personalSlots.keys
        if (rule.inputs.keys.intersect(rule.personalSlots.keys).isNotEmpty()) err(id, "an input and a slot share a name")
        if (rule.steps.isEmpty()) err(id, "rule without steps")

        /** Unit of an operand; null = unknown (error already reported) or the `nd` wildcard. */
        fun unitOf(op: Operand, where: String, shape: Shape = Shape.SCALAR): RuleUnit? = when (op) {
            is Operand.Input -> rule.inputs[op.name] ?: null.also { err(id, "$where: undeclared input '${op.name}'") }
            is Operand.Slot -> rule.personalSlots[op.name] ?: null.also { err(id, "$where: undeclared personalSlot '${op.name}'") }
            is Operand.Ref -> defined[op.name] ?: null.also { err(id, "$where: '${op.name}' is not an earlier step (forward or unknown reference)") }
            is Operand.Lit -> op.value.unit
            Operand.Nd -> null.also { err(id, "$where: 'nd' is allowed only as a guard's else") }
            is Operand.Param -> paramUnit(row, op.id, where, shape)
        }
        fun numeric(op: Operand?, where: String): RuleUnit? = op?.let { o -> unitOf(o, where)?.also { if (!it.numeric) err(id, "$where: $it is not numeric") } }
        fun expect(op: Operand?, unit: RuleUnit?, where: String) {
            if (op == null || unit == null) return
            val u = unitOf(op, where) ?: return
            if (u != unit) err(id, "$where: unit $u, expected $unit")
        }

        for (step in rule.steps) {
            val w = "step ${step.out}"
            if (step.out in defined || step.out in names) err(id, "$w: name already defined")
            val out: RuleUnit? = when (step) {
                is BracketsStep -> {
                    expect(step.input, RuleUnit.EUR, "$w input")
                    if (step.schedule != null) {
                        if (step.bands.isNotEmpty()) err(id, "$w: bands and schedule are exclusive")
                        unitOf(step.schedule, "$w schedule", Shape.SCHEDULE)
                        val k = step.key?.let { unitOf(it, "$w key") }
                        if (k != null && k != RuleUnit.TEXT && k != RuleUnit.COUNT) err(id, "$w key: $k is not TEXT/COUNT")
                        if (step.key == null) err(id, "$w: schedule needs a key")
                    } else {
                        if (step.bands.isEmpty() || step.bands.last().upTo != null || step.bands.dropLast(1).any { it.upTo == null }) err(id, "$w: bands need bounds and an open last band")
                        step.bands.forEachIndexed { i, b -> b.upTo?.let { expect(it, RuleUnit.EUR, "$w band $i upTo") }; expect(b.rate, RuleUnit.RATIO, "$w band $i rate") }
                    }
                    RuleUnit.EUR
                }
                is PiecewiseStep -> {
                    val inUnit = numeric(step.input, "$w input")
                    if (step.pieces.isEmpty() || step.pieces.last().upTo != null || step.pieces.dropLast(1).any { it.upTo == null }) err(id, "$w: pieces need bounds and an open last piece")
                    val outUnit = step.pieces.firstOrNull()?.let { numeric(it.base, "$w piece 0 base") }
                    step.pieces.forEachIndexed { i, p ->
                        p.upTo?.let { expect(it, inUnit, "$w piece $i upTo") }
                        if (i > 0) expect(p.base, outUnit, "$w piece $i base")
                        val linear = listOf(p.slope, p.anchor, p.span)
                        if (linear.any { it != null } && linear.any { it == null }) err(id, "$w piece $i: slope, anchor and span go together")
                        expect(p.slope, outUnit, "$w piece $i slope"); expect(p.anchor, inUnit, "$w piece $i anchor"); expect(p.span, inUnit, "$w piece $i span")
                        if (p.quotient != Rounding.NONE && p.quotient != Rounding.TRUNC_4DEC) err(id, "$w piece $i: quotient rounding ${p.quotient}")
                    }
                    outUnit
                }
                is RateStep -> {
                    val inUnit = numeric(step.input, "$w input")
                    if ((step.rate == null) == (step.num == null && step.den == null) || (step.num == null) != (step.den == null)) err(id, "$w: give rate, or num and den")
                    step.rate?.let { r -> unitOf(r, "$w rate")?.let { if (it != RuleUnit.RATIO && it != RuleUnit.COUNT) err(id, "$w rate: $it is not RATIO/COUNT") } }
                    if (step.num != null && step.den != null) { val nu = numeric(step.num, "$w num"); expect(step.den, nu, "$w den") }
                    expect(step.min, inUnit, "$w min"); expect(step.max, inUnit, "$w max")
                    inUnit
                }
                is LookupStep -> {
                    val k = unitOf(step.key, "$w key")
                    if (k != null && k != RuleUnit.TEXT && k != RuleUnit.COUNT) err(id, "$w key: $k is not TEXT/COUNT")
                    step.date?.let { expect(it, RuleUnit.DATE, "$w date") }
                    when {
                        (step.table == null) == (step.family == null) -> null.also { err(id, "$w: give table or family") }
                        step.table != null -> unitOf(step.table, "$w table", Shape.TABLE)
                        else -> familyUnit(row, step.family!!, w)
                    }
                }
                is SumStep -> {
                    if (step.terms.isEmpty()) err(id, "$w: no terms")
                    val units = step.terms.mapNotNull { numeric(it.operand, "$w term") }.distinct()
                    if (units.size > 1) err(id, "$w: terms mix units $units")
                    units.firstOrNull()
                }
            }
            if (step.guards.isNotEmpty() && step.otherwise == null) err(id, "$w: guarded step needs 'else'")
            for (g in step.guards) checkGuard(id, g, "$w when") { op, where -> unitOf(op, where) }
            step.otherwise?.let { e -> if (e != Operand.Nd) expect(e, out, "$w else") }
            if (out != null && !roundingAllowed(out, step.kind, step.rounding)) err(id, "$w: rounding ${step.rounding} not allowed for $out ${step.kind}")
            if (out != null) defined[step.out] = out
        }
        val outUnit = defined[rule.output]
        if (outUnit == null) err(id, "output '${rule.output}' is not a step")
        else if (outUnit != row.unit) err(id, "output unit $outUnit differs from row unit ${row.unit}")
        for (r in rule.requires + rule.caveats) if (pack.versions(r).isEmpty()) err(id, "requires/caveat '$r' is not a row")
    }

    private fun checkGuard(id: String, g: Guard, where: String, unitOf: (Operand, String) -> RuleUnit?) {
        val a = unitOf(g.a, "$where a")
        when (g.op) {
            GuardOp.KNOWN -> if (g.b != null) err(id, "$where: KNOWN takes one operand")
            GuardOp.IN -> {
                if (g.values.isEmpty()) err(id, "$where: IN needs values")
                if (a != null && a != RuleUnit.TEXT && a != RuleUnit.COUNT) err(id, "$where: IN on $a")
            }
            else -> {
                val b = g.b?.let { unitOf(it, "$where b") }
                if (g.b == null) err(id, "$where: ${g.op} needs two operands")
                if (a != null && b != null && a != b) err(id, "$where: compares $a with $b")
                if (a != null && !a.numeric && g.op != GuardOp.EQ && g.op != GuardOp.NE) err(id, "$where: ${g.op} on $a")
            }
        }
    }

    private fun paramUnit(rule: LawRow, pid: String, where: String, shape: Shape): RuleUnit? {
        val versions = pack.versions(pid)
        if (versions.isEmpty()) {
            err(rule.id, if (pack.retired.any { it.id == pid }) "$where: references retired row '$pid'" else "$where: unknown row '$pid'")
            return null
        }
        val first = versions.first()
        if (first.unit == RuleUnit.NOTE) { err(rule.id, "$where: '$pid' is a NOTE row"); return null }
        if (first.rule != null) { err(rule.id, "$where: '$pid' is a rule, not a parameter"); return null }
        if (first.shape != shape) { err(rule.id, "$where: '$pid' is ${first.shape}, expected $shape"); return null }
        val ruleKinds = rule.appliesTo.kinds
        for (v in versions) {
            val k = v.appliesTo.kinds
            if (k.isNotEmpty() && (ruleKinds.isEmpty() || !k.containsAll(ruleKinds))) err(rule.id, "$where: '$pid' applies to $k, narrower than the rule")
        }
        return first.unit
    }

    private fun familyUnit(rule: LawRow, family: String, where: String): RuleUnit? {
        val members = pack.ids.filter { it.startsWith("$family.") }
        if (members.isEmpty()) { err(rule.id, "$where: family '$family' has no rows"); return null }
        val units = members.map { m -> paramUnit(rule, m, "$where family", Shape.SCALAR) }.distinct()
        if (units.size != 1 || units[0] == null) { err(rule.id, "$where: family '$family' mixes units $units"); return null }
        return units[0]
    }
}
