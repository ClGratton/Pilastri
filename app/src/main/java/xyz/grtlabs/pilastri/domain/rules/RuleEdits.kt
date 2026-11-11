package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.AddedSource
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ChangeLayer
import xyz.grtlabs.pilastri.data.Coded
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.PackStatus
import xyz.grtlabs.pilastri.data.PackVersion
import xyz.grtlabs.pilastri.data.ParamClass
import xyz.grtlabs.pilastri.data.PersonalParam
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.RowChange
import xyz.grtlabs.pilastri.data.RulesCodec
import xyz.grtlabs.pilastri.data.StoredSnapshot
import xyz.grtlabs.pilastri.data.VersionKind
import xyz.grtlabs.pilastri.domain.EventType
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/*
 * Proposals the assistant may make on the rule pack (Council 4 c "Sources" and "Tiers"). Every LAW or step change and every
 * personal change is a Tier 3 row: confirmed one by one, with the diff on the person's own numbers. A LAW value needs a
 * source URL, a quote of at most 200 characters and the snapshot text it was taken from; the quote must be a verbatim
 * substring of that text. Only a primary source (host allowlist below) can confirm; any other source may only propose.
 * A personal value fills only a slot the pack declares and never a law row. SCENARIO values need no confirmation.
 */

class RuleEditException(message: String) : IllegalArgumentException(message)

/**
 * Hosts whose pages are primary sources: law text (Gazzetta Ufficiale, Normattiva, Senato, Camera), Agenzia delle Entrate,
 * INPS, COVIP and the Dipartimento delle Finanze (comune rates are LAW). MEF and Finanze match only their own host, so a
 * payroll portal under them is not primary. A fund statute is not in code (no fund is hardcoded): it may propose only.
 */
object PrimarySources {
    private val withSubdomains = listOf("gazzettaufficiale.it", "normattiva.it", "agenziaentrate.gov.it", "inps.it", "covip.it", "senato.it", "camera.it")
    private val ownHostOnly = listOf("mef.gov.it", "finanze.gov.it")

    fun host(url: String): String? = try {
        URI(url.trim()).takeIf { it.scheme.equals("https", ignoreCase = true) }?.host?.lowercase()?.trimEnd('.')
    } catch (_: Exception) { null }

    fun isPrimary(url: String): Boolean {
        val h = host(url) ?: return false
        return withSubdomains.any { h == it || h.endsWith(".$it") } || ownHostOnly.any { h == it || h == "www.$it" }
    }
}

/** What a LAW proposal rests on. [snapshotText] is the text the quote was read from; it is stored with the version. */
data class Evidence(val sourceUrl: String, val quote: String, val snapshotText: String, val validFrom: LocalDate, val checkedOn: LocalDate) {
    /** Checks the evidence; returns whether the source is primary. */
    fun check(today: LocalDate): Boolean {
        if (sourceUrl.length > 500 || PrimarySources.host(sourceUrl) == null) throw RuleEditException("fonte_url deve essere un indirizzo https valido.")
        if (quote.isBlank() || quote.length > 200) throw RuleEditException("La citazione deve avere da 1 a 200 caratteri.")
        if (snapshotText.isBlank() || snapshotText.length > 20_000) throw RuleEditException("Il testo della fonte deve avere da 1 a 20.000 caratteri.")
        if (!snapshotText.contains(quote)) throw RuleEditException("La citazione non è un estratto letterale del testo della fonte: proposta rifiutata.")
        if (checkedOn.isAfter(today) || checkedOn.year < 2000) throw RuleEditException("verificato_il non valido: una data passata, AAAA-MM-GG.")
        if (validFrom.year !in 1990..2100) throw RuleEditException("valida_dal fuori intervallo.")
        return PrimarySources.isPrimary(sourceUrl)
    }
}

/** One "Cosa cambia" line: an output on the person's own numbers, before and after. */
data class AffectedOutput(val card: String, val label: String, val before: String, val after: String)

/** What a Tier 3 row shows (board G3-5b): labels and figures only. */
data class RuleRowView(
    val rowId: String,
    val label: String?,
    val layer: ChangeLayer,
    val before: String,
    val after: String,
    /** A primary source whose quote was found verbatim in its snapshot. */
    val verified: Boolean,
    val confirmable: Boolean,
    val validFrom: String?,
    val sourceUrl: String?,
    val quote: String?,
    /** PERSONAL: "documento · campo" or "dichiarato". */
    val origin: String?,
    val outputs: List<AffectedOutput>,
    val cards: List<String>,
)

enum class EditKind {
    LAW_VALUE, LAW_STEPS, PERSONAL, REFRESH_LAW, REFRESH_PERSONAL, SCENARIO;

    /** Row by row, never Tier 1 or 2. */
    val tier3: Boolean get() = this == LAW_VALUE || this == LAW_STEPS || this == PERSONAL
}

/** A prepared proposal; [RulesHistory.confirm] writes it (re-checked against the latest archive). */
class PreparedEdit(
    val kind: EditKind,
    val target: String,
    val changes: List<RowChange>,
    val sources: Map<String, AddedSource>,
    val snapshot: StoredSnapshot?,
    val param: PersonalParam?,
    val evidence: Evidence?,
    val primary: Boolean,
    val view: RuleRowView,
) {
    val confirmable: Boolean get() = view.confirmable
}

/** The person's own numbers the diff is computed on. Missing figures stay n/d; nothing is assumed. */
data class PersonInputs(
    val year: Int,
    val kinds: Set<PositionKind>?,
    val taxableCents: Long? = null,
    val contributions: List<FundContribution> = emptyList(),
    val previousReturn: PreviousReturn? = null,
) {
    companion object {
        /**
         * From the archive: position kinds in force in [year] and the pillar-4 contributions of [year] by (fund, month); a
         * contribution account without a described fund keys on the account. Taxable income is not in the archive (n/d).
         */
        fun from(archive: Archive, year: Int): PersonInputs {
            val start = LocalDate.of(year, 1, 1)
            val end = LocalDate.of(year, 12, 31)
            val kinds = archive.positions.filter { p ->
                (p.validFrom.value?.let { !it.isAfter(end) } ?: true) && (p.validTo.value?.let { !it.isBefore(start) } ?: true)
            }.mapNotNull { it.kind.known }.toSet().ifEmpty { null }
            val fundOf = archive.accounts.associate { it.id to (it.fundId ?: "conto:${it.id}") }
            val contributions = archive.events
                .filter { it.pillar == 4 && it.date.year == year && (it.type == EventType.PERSONAL_CONTRIBUTION || it.type == EventType.EMPLOYER_CONTRIBUTION) }
                .groupBy { (it.accountId?.let(fundOf::get) ?: "pilastro4") to YearMonth.from(it.date) }
                .map { (k, events) ->
                    FundContribution(k.first, k.second,
                        events.filter { it.type == EventType.PERSONAL_CONTRIBUTION }.sumOf { it.amount.cents },
                        events.filter { it.type == EventType.EMPLOYER_CONTRIBUTION }.sumOf { it.amount.cents })
                }
            return PersonInputs(year, kinds, null, contributions, null)
        }
    }
}

object RuleEdits {
    const val SNAPSHOT_KIND = "AGENT_CAPTURE"

    /** A LAW value or table change. [value] is the pack literal: a string for SCALAR, a JSON object for TABLE or SCHEDULE. */
    fun lawValue(shipped: ShippedPack, archive: Archive, rowId: String, value: Any, evidence: Evidence, label: String?,
                 today: LocalDate, seed: String, inputs: PersonInputs): PreparedEdit =
        law(shipped, archive, rowId, steps = false, evidence, label, today, seed, inputs) { row ->
            val shape = row.optString("shape", "SCALAR")
            when {
                shape == "SCALAR" && value is String -> row.put("value", value.trim())
                shape != "SCALAR" && value is JSONObject -> row.put("value", value)
                shape != "SCALAR" && value is String -> row.put("value", try { JSONObject(value) } catch (_: Exception) { throw RuleEditException("valore: per una riga $shape serve un oggetto JSON.") })
                else -> throw RuleEditException("valore: per una riga SCALAR serve una stringa.")
            }
            row.put("note", JSONObject.NULL)
        }

    /** A step change: the whole new step list of a rule row (order, guards, values, added steps of existing kinds). */
    fun lawSteps(shipped: ShippedPack, archive: Archive, rowId: String, steps: JSONArray, evidence: Evidence, label: String?,
                 today: LocalDate, seed: String, inputs: PersonInputs): PreparedEdit {
        if (steps.length() == 0 || steps.length() > 60) throw RuleEditException("passi: da 1 a 60 passi.")
        return law(shipped, archive, rowId, steps = true, evidence, label, today, seed, inputs) { row -> row.put("steps", steps) }
    }

    private fun law(shipped: ShippedPack, archive: Archive, rowId: String, steps: Boolean, evidence: Evidence, label: String?,
                    today: LocalDate, seed: String, inputs: PersonInputs, mutate: (JSONObject) -> Unit): PreparedEdit {
        val primary = evidence.check(today)
        val eff = EffectiveRules.build(shipped, archive.rulesData)
        val versions = eff.rows(rowId)
        if (versions.isEmpty()) throw RuleEditException("Riga di legge sconosciuta: $rowId. Gli id sono in leggi_regole; una riga nuova è un aggiornamento dell'app.")
        val isRule = versions.any { it.has("steps") && !it.isNull("steps") }
        if (versions.first().optString("unit") == "NOTE") throw RuleEditException("$rowId è una nota: non ha valori.")
        if (steps && !isRule) throw RuleEditException("$rowId non ha passi: usa proponi_regola per il valore.")
        if (!steps && isRule) throw RuleEditException("$rowId è una regola a passi: usa proponi_passi_regola.")
        val from = evidence.validFrom
        val current = eff.pack.at(rowId, from)?.let { eff.row(RowKey(rowId, it.validFrom?.toString())) }
        val template = current ?: versions.maxByOrNull { it.str("validFrom") ?: "" }!!
        val proposed = JSONObject(template.toString())
        mutate(proposed)
        val field = if (steps) "steps" else "value"
        val same = current != null && canonical(current.opt(field)) == canonical(proposed.opt(field))
        val code = "A" + RulesCodec.sha256(evidence.sourceUrl).removePrefix("sha256:").take(10)
        val snapshot = StoredSnapshot(RulesCodec.sha256(evidence.snapshotText), evidence.sourceUrl, evidence.checkedOn, SNAPSHOT_KIND, !primary, evidence.snapshotText)
        proposed.put("sourceUrl", evidence.sourceUrl).put("quote", evidence.quote).put("snapshotHash", snapshot.hash)
            .put("checkedOn", evidence.checkedOn.toString()).put("status", if (primary) PackStatus.VERIFIED.name else PackStatus.UNVERIFIED.name)
            .put("statusNote", JSONObject.NULL).put("sources", JSONArray().put(code))
        val changes = ArrayList<RowChange>()
        val kind: EditKind
        when {
            same -> {
                if (!primary) throw RuleEditException("Il valore di $rowId è già in vigore: niente da proporre.")
                proposed.put("validFrom", current!!.opt("validFrom") ?: JSONObject.NULL).put("validTo", current.opt("validTo") ?: JSONObject.NULL)
                // Identical values from a newer primary source refresh the dates; a status change (stima → esatto) is Tier 3.
                kind = if (current.str("status") == PackStatus.VERIFIED.name) EditKind.REFRESH_LAW else if (steps) EditKind.LAW_STEPS else EditKind.LAW_VALUE
                if (kind == EditKind.REFRESH_LAW && !LocalDate.parse(current.getString("checkedOn")).isBefore(evidence.checkedOn)) {
                    throw RuleEditException("$rowId è già verificata a questa data: niente da aggiornare.")
                }
                changes += lawChange(rowId, current, proposed)
            }
            current != null && current.str("validFrom") == from.toString() -> {
                kind = if (steps) EditKind.LAW_STEPS else EditKind.LAW_VALUE
                changes += lawChange(rowId, current, proposed)
            }
            current != null -> {
                // A new version from [from]: the one in force closes the day before.
                kind = if (steps) EditKind.LAW_STEPS else EditKind.LAW_VALUE
                val closed = JSONObject(current.toString()).put("validTo", from.minusDays(1).toString())
                proposed.put("validFrom", from.toString()).put("validTo", current.opt("validTo") ?: JSONObject.NULL)
                changes += lawChange(rowId, current, closed)
                changes += lawChange(rowId, null, proposed)
            }
            else -> {
                kind = if (steps) EditKind.LAW_STEPS else EditKind.LAW_VALUE
                val next = versions.mapNotNull { it.str("validFrom")?.let(LocalDate::parse) }.filter { it.isAfter(from) }.minOrNull()
                proposed.put("validFrom", from.toString()).put("validTo", next?.minusDays(1)?.toString() ?: JSONObject.NULL)
                changes += lawChange(rowId, null, proposed)
            }
        }
        val sources = mapOf(code to AddedSource(evidence.sourceUrl, if (primary) "PRIMARY" else "SUMMARY_ONLY"))
        val after = candidate(shipped, archive, eff, changes, sources, snapshot, null, seed)
        val view = RuleRowView(
            rowId, label?.trim()?.take(60)?.ifEmpty { null }, ChangeLayer.LAW,
            before = current?.let { RuleDisplay.value(it) } ?: "—",
            after = RuleDisplay.value(proposed),
            verified = primary, confirmable = primary,
            validFrom = RuleDisplay.date(proposed.str("validFrom")?.let(LocalDate::parse)),
            sourceUrl = evidence.sourceUrl, quote = evidence.quote, origin = null,
            outputs = RuleImpact.outputs(eff, after, setOf(rowId), inputs),
            cards = emptyList(),
        ).let { it.copy(cards = it.outputs.map { o -> o.card }.distinct()) }
        return PreparedEdit(kind, rowId, changes, sources, snapshot, null, evidence, primary, view)
    }

    /** A personal value for a declared slot ([scope]: a fund or position id, or null for the person). */
    fun personal(shipped: ShippedPack, archive: Archive, slot: String, scope: String?, raw: String, origin: Origin,
                 docId: String?, fieldLabel: String?, docHash: String?, asOf: LocalDate?, today: LocalDate, now: Instant,
                 seed: String, inputs: PersonInputs): PreparedEdit {
        val eff = EffectiveRules.build(shipped, archive.rulesData)
        val unit = slotUnit(eff, slot)
        if (scope != null && archive.funds.none { it.id == scope } && archive.positions.none { it.id == scope }) throw RuleEditException("Ambito sconosciuto: $scope (serve l'id di un fondo o di una posizione).")
        val value = literal(unit, raw, slot)
        val cls = when (origin) {
            Origin.DOCUMENTO -> {
                if (docId.isNullOrBlank() || docId.length > 120) throw RuleEditException("documento_id mancante.")
                if (fieldLabel.isNullOrBlank() || fieldLabel.length > 120) throw RuleEditException("campo mancante: l'etichetta del campo nel documento.")
                if (docHash == null || !Regex("(sha256:)?[0-9a-f]{64}").matches(docHash)) throw RuleEditException("hash_documento: SHA-256 esadecimale.")
                if (asOf == null || asOf.isAfter(today)) throw RuleEditException("data_documento mancante o futura.")
                ParamClass.PERSONAL_DOCUMENT
            }
            Origin.DICHIARATO -> ParamClass.PERSONAL_DECLARED
            else -> throw RuleEditException("origine: DOCUMENTO o DICHIARATO.")
        }
        val key = SlotKey(slot, scope)
        val current = eff.activeParam(key)
        val same = current != null && current.value == value && current.unit == unit.name
        val refresh = same && cls == ParamClass.PERSONAL_DOCUMENT && (current!!.asOf == null || asOf!!.isAfter(current.asOf))
        if (same && !refresh) throw RuleEditException("$slot vale già $value: niente da proporre.")
        val param = PersonalParam(
            id = "pp:" + RulesCodec.sha256("$seed|$slot|$scope|$value|$docHash").removePrefix("sha256:").take(16),
            slot = slot, scope = scope, value = value, unit = unit.name, paramClass = Coded.Known(cls), origin = Coded.Known(origin),
            docId = docId?.takeIf { cls == ParamClass.PERSONAL_DOCUMENT }, fieldLabel = fieldLabel?.takeIf { cls == ParamClass.PERSONAL_DOCUMENT },
            docHash = docHash?.takeIf { cls == ParamClass.PERSONAL_DOCUMENT }, asOf = asOf, recordedAt = now,
        )
        val changes = listOf(RowChange(Coded.Known(ChangeLayer.PERSONAL), slot, scope, beforeParam = current?.id, afterParam = param.id))
        val after = candidate(shipped, archive, eff, changes, emptyMap(), null, param, seed)
        val outputs = RuleImpact.outputs(eff, after, setOf("slot:$slot"), inputs)
        val view = RuleRowView(
            slot + (scope?.let { " · $it" } ?: ""), null, ChangeLayer.PERSONAL,
            before = current?.let { RuleDisplay.literal(it.unit, it.value) } ?: "—",
            after = RuleDisplay.literal(unit.name, value),
            verified = cls == ParamClass.PERSONAL_DOCUMENT, confirmable = true,
            validFrom = RuleDisplay.date(asOf), sourceUrl = null, quote = null,
            origin = if (cls == ParamClass.PERSONAL_DOCUMENT) "$docId · $fieldLabel" else "dichiarato",
            outputs = outputs, cards = outputs.map { it.card }.distinct(),
        )
        return PreparedEdit(if (refresh) EditKind.REFRESH_PERSONAL else EditKind.PERSONAL, slot, changes, emptyMap(), null, param, null, false, view)
    }

    /** A SCENARIO value (Futuro only): no confirmation, no version; undo removes the record. */
    fun scenario(shipped: ShippedPack, archive: Archive, slot: String, raw: String, now: Instant, seed: String): PreparedEdit {
        val eff = EffectiveRules.build(shipped, archive.rulesData)
        val unit = slotUnit(eff, slot)
        val value = literal(unit, raw, slot)
        val param = PersonalParam(
            id = "ps:" + RulesCodec.sha256("$seed|$slot|$value").removePrefix("sha256:").take(16),
            slot = slot, scope = null, value = value, unit = unit.name, paramClass = Coded.Known(ParamClass.SCENARIO),
            origin = Coded.Known(Origin.DICHIARATO), recordedAt = now,
        )
        val view = RuleRowView(slot, null, ChangeLayer.PERSONAL, eff.scenario[slot]?.let { RuleDisplay.rvalue(it.value) } ?: "—",
            RuleDisplay.literal(unit.name, value), verified = false, confirmable = true, validFrom = null, sourceUrl = null, quote = null,
            origin = "scenario", outputs = emptyList(), cards = listOf("Futuro"))
        return PreparedEdit(EditKind.SCENARIO, slot, emptyList(), emptyMap(), null, param, null, false, view)
    }

    // ---- helpers ----

    private fun lawChange(rowId: String, before: JSONObject?, after: JSONObject) =
        RowChange(Coded.Known(ChangeLayer.LAW), rowId, after.str("validFrom") ?: before?.str("validFrom"), before?.toString(), after.toString())

    /** The effective pack with the edit applied; refused when the result does not validate. */
    private fun candidate(shipped: ShippedPack, archive: Archive, eff: EffectivePack, changes: List<RowChange>, sources: Map<String, AddedSource>,
                          snapshot: StoredSnapshot?, param: PersonalParam?, seed: String): EffectivePack {
        // Update resolutions not yet in the history are applied first, exactly as the confirmation will write them.
        val base = RulesHistory.withResolutions(eff.book, eff, shipped, Instant.EPOCH)
        val head = if (base === eff.book) eff.headId else EffectiveRules.headId(base)
        val probe = PackVersion("probe:$seed", head, Coded.Known(VersionKind.CONFIRMED), Instant.EPOCH, shipped.hash, sources = sources, changes = changes)
        val book = base.copy(
            versions = base.versions + probe,
            snapshots = base.snapshots + listOfNotNull(snapshot?.takeIf { s -> base.snapshots.none { it.hash == s.hash } }),
            params = base.params + listOfNotNull(param),
        )
        val data = RulesCodec.encode(book)
        val errors = EffectiveRules.errors(shipped, data)
        if (errors.isNotEmpty()) throw RuleEditException("La regola risultante non è valida: " + errors.take(3).joinToString("; "))
        return EffectiveRules.build(shipped, data)
    }

    private fun slotUnit(eff: EffectivePack, slot: String): RuleUnit {
        if (slot in eff.pack.ids) throw RuleEditException("«$slot» è una riga di legge: un valore personale non la sostituisce.")
        if (slot !in eff.pack.declaredSlots) {
            throw RuleEditException("«$slot» non è un parametro personale previsto dalle regole. Previsti: ${eff.pack.declaredSlots.sorted().joinToString().ifEmpty { "nessuno" }}.")
        }
        return eff.pack.rows.firstNotNullOfOrNull { it.rule?.personalSlots?.get(slot) } ?: RuleUnit.TEXT
    }

    private fun literal(unit: RuleUnit, raw: String, slot: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 200) throw RuleEditException("valore di $slot: da 1 a 200 caratteri.")
        try { RValue.parse(unit, text) } catch (_: Exception) { throw RuleEditException("valore di $slot non valido per l'unità $unit.") }
        return text
    }
}

/** The outputs a change reaches, evaluated with the person's own numbers before and after. */
object RuleImpact {
    private class Output(val ruleIds: List<String>, val card: String, val compute: (Rules, PersonInputs) -> List<Pair<String, String>>)

    private val facade = listOf(
        Output(listOf(Rules.IRPEF), "Imposta") { r, p ->
            listOf("IRPEF lorda ${p.year}" to (p.taxableCents?.let { RuleDisplay.money(r.irpefGross(it, p.year, p.kinds)) } ?: "n/d"))
        },
        Output(listOf(Rules.DEDUCTION), "Deduzione") { r, p ->
            val d = r.deductionCeiling(p.year, p.contributions)
            val room = if (d.ceiling.value != null && d.counted.value != null) RuleValue(maxOf(0L, d.ceiling.value - d.counted.value), worst(d.ceiling.status, d.counted.status), emptyList()) else RuleValue.nd("n/d")
            listOf("tetto annuo" to RuleDisplay.money(d.ceiling), "deducibile" to RuleDisplay.money(d.deductible), "spazio di deduzione" to RuleDisplay.money(room))
        },
        Output(listOf(Rules.FIRSTJOB), "Deduzione") { r, p -> listOf("primo impiego" to RuleDisplay.money(r.firstJobExtraDeduction(p.year))) },
        Output(listOf(Rules.SALDO, Rules.ACCONTO_ORD, Rules.ACCONTO_ISA, Rules.DEADLINES), "F24") { r, p ->
            r.f24Schedule(p.year, p.previousReturn).flatMap { f ->
                val name = when (f.kind) { F24Kind.SALDO -> "saldo"; F24Kind.ACCONTO_1 -> "1° acconto"; F24Kind.ACCONTO_2 -> "2° acconto" }
                listOf("$name · scadenza" to (f.due.value?.let { RuleDisplay.date(it) } ?: "n/d"), "$name · importo" to RuleDisplay.money(f.amount))
            }
        },
    )

    fun outputs(before: EffectivePack, after: EffectivePack, changed: Set<String>, inputs: PersonInputs): List<AffectedOutput> {
        val out = ArrayList<AffectedOutput>()
        val reached = HashSet<String>()
        for (o in facade) {
            if (o.ruleIds.none { id -> (closure(before.pack, id) + closure(after.pack, id)).any { it in changed } }) continue
            reached += o.ruleIds
            val old = safe { o.compute(before.rules, inputs) }
            val new = safe { o.compute(after.rules, inputs) }
            new.forEachIndexed { i, (label, value) -> out += AffectedOutput(o.card, label, old.getOrNull(i)?.second ?: "n/d", value) }
        }
        // Rules no card reads yet: listed under "Fonti e regole", evaluated without inputs (usually n/d).
        val date = LocalDate.of(inputs.year, 12, 31)
        after.pack.rows.filter { it.rule != null && it.id !in reached }.map { it.id }.distinct()
            .filter { id -> closure(after.pack, id).any { it in changed } }.take(6).forEach { id ->
                fun eval(e: EffectivePack) = try {
                    val ev = Interpreter(e.pack).evaluate(id, e.context(date, kinds = inputs.kinds))
                    RuleDisplay.res(ev.output)
                } catch (_: Exception) { "n/d" }
                out += AffectedOutput("Fonti e regole", id, eval(before), eval(after))
            }
        return out
    }

    private fun safe(f: () -> List<Pair<String, String>>): List<Pair<String, String>> = try { f() } catch (_: Exception) { emptyList() }

    /** Rows and `slot:` names a rule rests on, transitively. */
    internal fun closure(pack: Pack, ruleId: String): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(ruleId))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!out.add(id)) continue
            for (row in pack.versions(id)) {
                row.personalSlot?.let { out += "slot:$it" }
                val rule = row.rule ?: continue
                queue += rule.requires
                rule.personalSlots.keys.forEach { out += "slot:$it" }
                for (step in rule.steps) {
                    operands(step).forEach { op ->
                        when (op) {
                            is Operand.Param -> queue += op.id
                            is Operand.Slot -> out += "slot:${op.name}"
                            else -> Unit
                        }
                    }
                    if (step is LookupStep && step.family != null) queue += pack.ids.filter { it.startsWith("${step.family}.") }
                }
            }
        }
        return out
    }

    private fun operands(step: Step): List<Operand> {
        val common = step.guards.flatMap { listOfNotNull(it.a, it.b) } + listOfNotNull(step.otherwise)
        return common + when (step) {
            is BracketsStep -> listOf(step.input) + step.bands.flatMap { listOfNotNull(it.upTo, it.rate) } + listOfNotNull(step.schedule, step.key)
            is PiecewiseStep -> listOf(step.input) + step.pieces.flatMap { listOfNotNull(it.upTo, it.base, it.slope, it.anchor, it.span) }
            is RateStep -> listOfNotNull(step.input, step.rate, step.num, step.den, step.min, step.max)
            is LookupStep -> listOfNotNull(step.table, step.key, step.date)
            is SumStep -> step.terms.map { it.operand }
        }
    }
}

/** Short Italian labels for figures; no sentences. */
object RuleDisplay {
    private val months = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")

    fun date(d: LocalDate?): String? = d?.let { "${it.dayOfMonth} ${months[it.monthValue - 1]} ${it.year}" }

    fun money(v: RuleValue<Long>): String = v.value?.let { euro(it) + if (v.status == RuleStatus.STIMA) " stima" else "" } ?: "n/d"

    fun euro(cents: Long): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        return (if (neg) "−" else "") + "$whole,${(abs % 100).toString().padStart(2, '0')}\u00A0€"
    }

    fun rvalue(v: RValue?): String = when (v) {
        null -> "n/d"
        is RValue.Num -> when (v.unit) {
            RuleUnit.EUR -> euro(v.amount.longValueExact())
            RuleUnit.RATIO -> v.amount.movePointRight(2).stripTrailingZeros().toPlainString().replace('.', ',') + "%"
            else -> v.amount.stripTrailingZeros().toPlainString().replace('.', ',')
        }
        is RValue.Str -> v.text
    }

    fun res(r: Res): String = if (r.status == RuleStatus.ND) "n/d" else rvalue(r.value) + if (r.status == RuleStatus.STIMA) " stima" else ""

    fun literal(unit: String, raw: String): String = try { rvalue(RValue.parse(RuleUnit.valueOf(unit), raw)) } catch (_: Exception) { raw }

    /** A row's value as a short label: a scalar, a table, a schedule or a step list. */
    fun value(row: JSONObject): String {
        row.optJSONArray("steps")?.let { s ->
            val outs = (0 until s.length()).map { s.getJSONObject(it).optString("out") }
            return ("${s.length()} passi · " + outs.joinToString(", ")).take(80)
        }
        if (!row.has("value") || row.isNull("value")) return "n/d"
        val unit = row.optString("unit")
        return when (val v = row.get("value")) {
            is JSONObject -> if (row.optString("shape") == "SCHEDULE") "scaglioni (${v.length()})"
                else v.keys().asSequence().joinToString("; ") { k -> "$k: " + literal(unit, v.optString(k)) }.let { if (it.length > 80) it.take(79) + "…" else it }
            else -> literal(unit, v.toString())
        }
    }
}
