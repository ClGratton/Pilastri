package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.PackStatus
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.Rounding
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/*
 * The LAW layer: a shipped rule pack (`assets/rules/law-2026.json`) plus its quote snapshot store
 * (`assets/rules/snapshots-2026.json`). A law row is {id, value or steps, unit, validFrom/To, appliesTo, sourceUrl,
 * quote ≤200 chars, snapshotHash, checkedOn, status, personalSlot} (DATA-CONTRACT.md "Council 4 data rules").
 * Several rows may share an id with disjoint validity: they are versions of one parameter.
 * PackVersion history and undo are not here (slice 4).
 */

enum class Shape { SCALAR, TABLE, SCHEDULE }

/** PERSON_YEAR rules take amounts already summed over the person's positions; POSITION rules one position's. */
enum class Scope { PERSON_YEAR, POSITION }

/** Empty sets mean "any"; [otherCover] null means "either". */
data class AppliesTo(val kinds: Set<PositionKind>, val gestioni: Set<Gestione>, val otherCover: Boolean?) {
    companion object { val ANY = AppliesTo(emptySet(), emptySet(), null) }
}

data class ScheduleBand(val upToCents: Long?, val rate: BigDecimal)

sealed interface RowValue {
    data class Scalar(val value: RValue) : RowValue
    data class Table(val entries: Map<String, RValue>) : RowValue
    data class Schedule(val schedules: Map<String, List<ScheduleBand>>) : RowValue
}

/** Steps of a rule row. [personalSlots] are the only names a PERSONAL or SCENARIO value may fill. */
data class RuleDef(
    val scope: Scope,
    val inputs: Map<String, RuleUnit>,
    val personalSlots: Map<String, RuleUnit>,
    /** Rows whose status joins the output without a value (e.g. an INCERTO validity question makes the output n/d). */
    val requires: List<String>,
    /** Rows reported beside the output that do not change its status (an open question the research settled one way). */
    val caveats: List<String>,
    val output: String,
    val steps: List<Step>,
)

data class LawRow(
    val id: String,
    /** The c4-params.md row (or RESEARCH.md rule) this row was built from. */
    val research: String?,
    val unit: RuleUnit,
    val shape: Shape,
    /** Null for NOTE rows, rule rows and INCERTO rows without a usable value. */
    val value: RowValue?,
    val note: String?,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val appliesTo: AppliesTo,
    val sources: List<String>,
    val sourceUrl: String?,
    val quote: String,
    val snapshotHash: String,
    val checkedOn: LocalDate,
    val status: PackStatus,
    val statusNote: String?,
    /** A personal value this row needs before it applies (e.g. the domicile for a regional default rate). */
    val personalSlot: String?,
    val rule: RuleDef?,
) {
    /** validFrom null = in force at checkedOn with the start not read; validTo null = open. Both ends inclusive. */
    fun validAt(date: LocalDate): Boolean =
        (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || !date.isAfter(validTo))
}

enum class SourceRead { PRIMARY, SUMMARY_ONLY, FAILED }

data class SourceInfo(val url: String?, val read: SourceRead)

data class Retired(val id: String, val reason: String)

/**
 * A stored text excerpt. In the shipped store it is the text the Council 4 research recorded (a c4-params.md table row
 * or a research note line), not a full-page capture of the source; [kind] says which.
 */
data class Snapshot(
    val hash: String,
    val url: String?,
    val recordedOn: LocalDate,
    val kind: String,
    val summaryOnly: Boolean,
    val text: String,
)

class SnapshotStore(val snapshots: Map<String, Snapshot>) {
    operator fun get(hash: String): Snapshot? = snapshots[hash]

    companion object {
        fun sha256(text: String): String =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun parse(json: String): SnapshotStore {
            val arr = JSONObject(json).getJSONArray("snapshots")
            val map = LinkedHashMap<String, Snapshot>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Snapshot(o.getString("hash"), o.str("url"), LocalDate.parse(o.getString("recordedOn")), o.getString("kind"),
                    o.optBoolean("summaryOnly", true), o.getString("text"))
                map[s.hash] = s
            }
            return SnapshotStore(map)
        }
    }
}

class PackFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class PackInvalidException(val errors: List<String>) :
    IllegalStateException("Rule pack invalid (${errors.size}): " + errors.take(5).joinToString("; "))

class Pack(
    val packId: String,
    val checkedOn: LocalDate,
    val rows: List<LawRow>,
    val retired: List<Retired>,
    val sources: Map<String, SourceInfo>,
    val snapshots: SnapshotStore,
) {
    private val byId: Map<String, List<LawRow>> = rows.groupBy { it.id }

    val ids: Set<String> get() = byId.keys

    fun versions(id: String): List<LawRow> = byId[id].orEmpty()

    /** The version of [id] valid at [date], or null. Versions never overlap in a valid pack. */
    fun at(id: String, date: LocalDate): LawRow? = byId[id]?.firstOrNull { it.validAt(date) }

    /** Names a personal or scenario value may fill: every rule's personalSlots plus row gates. */
    val declaredSlots: Set<String> by lazy {
        rows.flatMap { (it.rule?.personalSlots?.keys ?: emptySet()) + listOfNotNull(it.personalSlot) }.toSet()
    }

    /** Load-time checks: forward references, units, roundings, statuses, version overlaps and the quote/snapshot rule. */
    fun validate(): List<String> = PackValidator(this).run()

    companion object {
        /** Parses and validates; throws [PackInvalidException] listing every problem. */
        fun load(packJson: String, snapshotJson: String): Pack {
            val pack = parse(packJson, snapshotJson)
            val errors = pack.validate()
            if (errors.isNotEmpty()) throw PackInvalidException(errors)
            return pack
        }

        /** Structural parse only (no semantic checks); see [validate]. */
        fun parse(packJson: String, snapshotJson: String): Pack {
            val root = JSONObject(packJson)
            val store = SnapshotStore.parse(snapshotJson)
            val sources = LinkedHashMap<String, SourceInfo>()
            root.optJSONObject("sources")?.let { s ->
                for (k in s.keys()) { val o = s.getJSONObject(k); sources[k] = SourceInfo(o.str("url"), SourceRead.valueOf(o.getString("read"))) }
            }
            val rowsArr = root.getJSONArray("rows")
            val rows = (0 until rowsArr.length()).map { i ->
                val o = rowsArr.getJSONObject(i)
                val id = o.optString("id", "#$i")
                try { parseRow(o) } catch (e: Exception) { throw PackFormatException("row $id: ${e.message}", e) }
            }
            val retired = root.optJSONArray("retired")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { r -> Retired(r.getString("id"), r.getString("reason")) } }
            }.orEmpty()
            return Pack(root.getString("packId"), LocalDate.parse(root.getString("checkedOn")), rows, retired, sources, store)
        }

        private fun parseRow(o: JSONObject): LawRow {
            val unit = RuleUnit.valueOf(o.getString("unit"))
            val shape = Shape.valueOf(o.optString("shape", "SCALAR"))
            val value: RowValue? = if (o.isNull("value") || !o.has("value")) null else when (shape) {
                Shape.SCALAR -> RowValue.Scalar(RValue.parse(unit, o.getString("value")))
                Shape.TABLE -> o.getJSONObject("value").let { t -> RowValue.Table(t.keys().asSequence().associateWith { RValue.parse(unit, t.getString(it)) }) }
                Shape.SCHEDULE -> o.getJSONObject("value").let { t ->
                    RowValue.Schedule(t.keys().asSequence().associateWith { k -> parseSchedule(t.getJSONArray(k)) })
                }
            }
            val a = o.optJSONObject("appliesTo")
            val applies = if (a == null) AppliesTo.ANY else AppliesTo(
                a.optJSONArray("kinds").strings().map { PositionKind.valueOf(it) }.toSet(),
                a.optJSONArray("gestioni").strings().map { Gestione.valueOf(it) }.toSet(),
                if (a.isNull("otherCover") || !a.has("otherCover")) null else a.getBoolean("otherCover"),
            )
            val rule = if (o.has("steps")) RuleDef(
                Scope.valueOf(o.optString("scope", "PERSON_YEAR")),
                o.optJSONObject("inputs").unitMap(),
                o.optJSONObject("personalSlots").unitMap(),
                o.optJSONArray("requires").strings(),
                o.optJSONArray("caveats").strings(),
                o.getString("output"),
                o.getJSONArray("steps").let { s -> (0 until s.length()).map { parseStep(s.getJSONObject(it)) } },
            ) else null
            return LawRow(
                id = o.getString("id"), research = o.str("research"), unit = unit, shape = shape, value = value, note = o.str("note"),
                validFrom = o.str("validFrom")?.let(LocalDate::parse), validTo = o.str("validTo")?.let(LocalDate::parse),
                appliesTo = applies, sources = o.optJSONArray("sources").strings(), sourceUrl = o.str("sourceUrl"),
                quote = o.optString("quote", ""), snapshotHash = o.optString("snapshotHash", ""),
                checkedOn = LocalDate.parse(o.getString("checkedOn")), status = PackStatus.valueOf(o.getString("status")),
                statusNote = o.str("statusNote"), personalSlot = o.str("personalSlot"), rule = rule,
            )
        }

        private fun parseSchedule(a: JSONArray): List<ScheduleBand> = (0 until a.length()).map { i ->
            val b = a.getJSONArray(i)
            ScheduleBand(if (b.isNull(0)) null else (RValue.parse(RuleUnit.EUR, b.getString(0)) as RValue.Num).amount.longValueExact(), BigDecimal(b.getString(1)))
        }

        private fun parseStep(o: JSONObject): Step {
            val out = o.getString("out")
            val rounding = Rounding.valueOf(o.optString("rounding", "NONE"))
            val guards = o.optJSONArray("when")?.let { w ->
                (0 until w.length()).map { i ->
                    val g = w.getJSONObject(i)
                    Guard(GuardOp.valueOf(g.getString("op")), Operand.parse(g.getString("a")), g.str("b")?.let(Operand::parse), g.optJSONArray("values").strings())
                }
            }.orEmpty()
            val otherwise = o.str("else")?.let(Operand::parse)
            fun op(k: String) = o.str(k)?.let(Operand::parse)
            fun req(k: String) = op(k) ?: throw PackFormatException("step $out: '$k' missing")
            return when (StepKind.valueOf(o.getString("kind"))) {
                StepKind.BRACKETS -> BracketsStep(out, rounding, guards, otherwise, req("input"),
                    o.optJSONArray("bands")?.let { b -> (0 until b.length()).map { i -> b.getJSONObject(i).let { Band(it.str("upTo")?.let(Operand::parse), Operand.parse(it.getString("rate"))) } } }.orEmpty(),
                    op("schedule")?.let { it as? Operand.Param ?: throw PackFormatException("step $out: schedule must be par:") }, op("key"))
                StepKind.PIECEWISE -> PiecewiseStep(out, rounding, guards, otherwise, req("input"),
                    o.getJSONArray("pieces").let { p -> (0 until p.length()).map { i -> p.getJSONObject(i).let { x ->
                        Piece(x.str("upTo")?.let(Operand::parse), Operand.parse(x.getString("base")), x.str("slope")?.let(Operand::parse),
                            x.str("anchor")?.let(Operand::parse), x.str("span")?.let(Operand::parse), Rounding.valueOf(x.optString("quotient", "NONE")))
                    } } })
                StepKind.RATE -> RateStep(out, rounding, guards, otherwise, req("input"), op("rate"), op("num"), op("den"), op("min"), op("max"))
                StepKind.LOOKUP -> LookupStep(out, rounding, guards, otherwise,
                    op("table")?.let { it as? Operand.Param ?: throw PackFormatException("step $out: table must be par:") }, o.str("family"), req("key"), op("date"))
                StepKind.SUM -> SumStep(out, rounding, guards, otherwise, o.getJSONArray("terms").strings().map { t ->
                    if (t.startsWith("-")) Term(true, Operand.parse(t.substring(1))) else Term(false, Operand.parse(t))
                })
            }
        }
    }
}

/** Null when absent or JSON null (Android's optString would return "null"). */
internal fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun JSONObject?.unitMap(): Map<String, RuleUnit> =
    if (this == null) emptyMap() else keys().asSequence().associateWith { RuleUnit.valueOf(getString(it)) }
