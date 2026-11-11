package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ChangeLayer
import xyz.grtlabs.pilastri.data.Coded
import xyz.grtlabs.pilastri.data.PackVersion
import xyz.grtlabs.pilastri.data.ParamClass
import xyz.grtlabs.pilastri.data.PersonalParam
import xyz.grtlabs.pilastri.data.RowChange
import xyz.grtlabs.pilastri.data.RulesBook
import xyz.grtlabs.pilastri.data.RulesCodec
import xyz.grtlabs.pilastri.data.RulesData
import xyz.grtlabs.pilastri.data.UpdateWinner
import xyz.grtlabs.pilastri.data.VersionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The rows the card was made on are no longer in force: the proposal must be made again. */
class RuleConflictException(message: String) : IllegalStateException(message)

/** The pack after the change would not validate: the change is refused (reject haptic in the panel). */
class RulePackRefusedException(message: String) : IllegalStateException(message)

enum class HistoryKind { CONFIRMED, UNDONE, UPDATE_RESOLVED, REFRESHED }

/**
 * One line of "Fonti e regole" (slice 5c renders it). [rowId] is a law row id or a personal slot; [before] and [after] are
 * short labels. UPDATE_RESOLVED: [winner] says which side stayed; SHIPPED lines read person's row → update, KEPT lines
 * read update (not applied) → person's row.
 */
data class HistoryLine(
    val at: Instant,
    val date: LocalDate,
    val rowId: String,
    val layer: ChangeLayer?,
    val before: String?,
    val after: String?,
    val source: String?,
    val kind: HistoryKind,
    val versionId: String,
    val winner: UpdateWinner? = null,
)

object RulesHistory {
    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    /** Every history line, newest first. Read model for "Fonti e regole". */
    fun lines(archive: Archive): List<HistoryLine> = lines(archive.rulesData)

    fun lines(data: RulesData): List<HistoryLine> {
        val book = try { RulesCodec.decode(data) } catch (_: Exception) { return emptyList() }
        val params = book.params.associateBy { it.id }
        val byId = book.versions.associateBy { it.id }
        val out = ArrayList<HistoryLine>()
        for (v in book.versions) {
            val kind = when (v.kind.known) {
                VersionKind.CONFIRMED -> HistoryKind.CONFIRMED
                VersionKind.UNDONE -> HistoryKind.UNDONE
                VersionKind.UPDATE_RESOLVED -> HistoryKind.UPDATE_RESOLVED
                VersionKind.REFRESHED -> HistoryKind.REFRESHED
                null -> continue
            }
            val origin = if (kind == HistoryKind.UNDONE) byId[v.undone] else v
            for (c in origin?.changes.orEmpty()) {
                val winner = c.winner?.known
                var (before, after) = when {
                    c.layer.known == ChangeLayer.PERSONAL -> label(params[c.beforeParam]) to label(params[c.afterParam])
                    winner == UpdateWinner.KEPT -> row(c.shippedRow) to row(c.afterRow)
                    else -> row(c.beforeRow) to row(c.afterRow)
                }
                if (kind == HistoryKind.UNDONE) { val t = before; before = after; after = t }
                val source = when {
                    c.layer.known == ChangeLayer.PERSONAL -> params[c.afterParam]?.let(::originLabel)
                    kind == HistoryKind.UPDATE_RESOLVED -> rowSource(c.afterRow ?: c.shippedRow)
                    else -> origin?.sourceUrl ?: rowSource(c.afterRow)
                }
                out += HistoryLine(v.at, v.at.atZone(zone).toLocalDate(), c.target, c.layer.known, before, after, source, kind, v.id, winner)
            }
        }
        // Versions are appended in order, so the stored order is the true sequence (timestamps can tie).
        return out.asReversed().toList()
    }

    /** Writes a confirmed Tier 3 row (or a refresh) as a new version on the latest archive. */
    fun confirm(archive: Archive, edit: PreparedEdit, shipped: ShippedPack, now: Instant, versionId: String): Archive {
        require(edit.kind != EditKind.SCENARIO) { "Uno scenario non ha versioni." }
        if (!edit.confirmable) throw IllegalStateException("Fonte non primaria: non confermabile.")
        val stored = RulesCodec.decode(archive.rulesData)
        if (stored.versions.any { it.id == versionId }) return archive
        val book = withResolutions(stored, EffectiveRules.build(shipped, stored), shipped, now)
        val eff = EffectiveRules.build(shipped, book)
        for (c in edit.changes) when (c.layer.known) {
            ChangeLayer.LAW -> {
                val now0 = eff.row(RowKey(c.target, c.key))?.let(::canonical)
                if (now0 != c.beforeRow?.let { canonical(JSONObject(it)) }) throw RuleConflictException("${c.target} è cambiata dopo la proposta: chiedine una nuova.")
            }
            ChangeLayer.PERSONAL -> if (eff.activeParam(SlotKey(c.target, c.key))?.id != c.beforeParam) {
                throw RuleConflictException("${c.target} è cambiato dopo la proposta: chiedine una nuova.")
            }
            null -> Unit
        }
        val refresh = edit.kind == EditKind.REFRESH_LAW || edit.kind == EditKind.REFRESH_PERSONAL
        val version = PackVersion(
            versionId, eff.headId, Coded.Known(if (refresh) VersionKind.REFRESHED else VersionKind.CONFIRMED), now, shipped.hash,
            sourceUrl = edit.evidence?.sourceUrl, primary = edit.primary, quote = edit.evidence?.quote, snapshotHash = edit.snapshot?.hash,
            sources = edit.sources, changes = edit.changes,
        )
        val next = book.copy(
            versions = book.versions + version,
            snapshots = book.snapshots + listOfNotNull(edit.snapshot?.takeIf { s -> book.snapshots.none { it.hash == s.hash } }),
            params = book.params + listOfNotNull(edit.param?.takeIf { p -> book.params.none { it.id == p.id } }),
        )
        return archive.copy(rulesData = checked(shipped, next))
    }

    /** Undo re-points the head to the version's parent; every version stays. Only the head can be undone. */
    fun undo(archive: Archive, versionId: String, shipped: ShippedPack, now: Instant): Archive {
        val book = RulesCodec.decode(archive.rulesData)
        val v = book.versions.firstOrNull { it.id == versionId && it.isNode } ?: throw RuleConflictException("Versione delle regole non trovata.")
        val head = EffectiveRules.headId(book)
        val undos = book.versions.count { it.kind.known == VersionKind.UNDONE && it.undone == versionId }
        if (head != versionId) {
            if (undos > 0 && head == v.parentId) return archive
            throw RuleConflictException("Dopo questa conferma ce ne sono altre: annulla prima quelle.")
        }
        val entry = PackVersion("pu:${versionId.substringAfter(':').take(40)}:$undos", v.parentId, Coded.Known(VersionKind.UNDONE), now, shipped.hash, undone = versionId)
        return archive.copy(rulesData = checked(shipped, book.copy(versions = book.versions + entry)))
    }

    /**
     * After an app update: records each conflict between a confirmed row and a newer shipped pack as an UPDATE_RESOLVED
     * history line (later primary checkedOn wins, ties go to the shipped pack). Never a Tier 3 row, never the dot. Returns
     * [archive] itself when there is nothing to record. The effective pack already applies the same resolution in memory.
     */
    fun resolveUpdate(archive: Archive, shipped: ShippedPack, now: Instant): Archive {
        val book = try { RulesCodec.decode(archive.rulesData) } catch (_: Exception) { return archive }
        val next = withResolutions(book, EffectiveRules.build(shipped, book), shipped, now)
        return if (next === book) archive else archive.copy(rulesData = RulesCodec.encode(next))
    }

    /** A SCENARIO value (Tier 1, no version). Idempotent by record id. */
    fun addScenario(archive: Archive, param: PersonalParam): Archive {
        require(param.paramClass.known == ParamClass.SCENARIO) { "Solo valori di scenario." }
        val book = RulesCodec.decode(archive.rulesData)
        if (book.params.any { it.id == param.id }) return archive
        return archive.copy(rulesData = RulesCodec.encode(book.copy(params = book.params + param)))
    }

    /** Undo of [addScenario]: removes that record only. */
    fun removeScenario(archive: Archive, paramId: String): Archive {
        val book = RulesCodec.decode(archive.rulesData)
        val p = book.params.firstOrNull { it.id == paramId && it.paramClass.known == ParamClass.SCENARIO }
            ?: throw RuleConflictException("Lo scenario è già stato rimosso.")
        return archive.copy(rulesData = RulesCodec.encode(book.copy(params = book.params.filterNot { it === p })))
    }

    internal fun withResolutions(book: RulesBook, eff: EffectivePack, shipped: ShippedPack, now: Instant): RulesBook {
        if (eff.pendingResolutions.isEmpty()) return book
        val id = "pr:" + RulesCodec.sha256("${shipped.hash}|${eff.headId}").removePrefix("sha256:").take(16)
        if (book.versions.any { it.id == id }) return book
        val changes = eff.pendingResolutions.map { r ->
            RowChange(Coded.Known(ChangeLayer.LAW), r.key.id, r.key.validFrom, beforeRow = r.userRow.toString(),
                afterRow = if (r.winner == UpdateWinner.KEPT) r.userRow.toString() else r.shippedRow?.toString(),
                winner = Coded.Known(r.winner), shippedRow = r.shippedRow?.toString())
        }
        return book.copy(versions = book.versions + PackVersion(id, eff.headId, Coded.Known(VersionKind.UPDATE_RESOLVED), now, shipped.hash, changes = changes))
    }

    private fun checked(shipped: ShippedPack, book: RulesBook): RulesData {
        val data = RulesCodec.encode(book)
        try { RulesCodec.validate(data) } catch (e: IllegalArgumentException) { throw RulePackRefusedException(e.message ?: "Storico delle regole non valido") }
        val errors = EffectiveRules.errors(shipped, data)
        if (errors.isNotEmpty()) throw RulePackRefusedException("Regole non valide: " + errors.take(3).joinToString("; "))
        return data
    }

    private fun row(json: String?): String? = json?.let { RuleDisplay.value(JSONObject(it)) }

    private fun rowSource(json: String?): String? = json?.let { JSONObject(it).str("sourceUrl") }

    private fun label(p: PersonalParam?): String? = p?.let { RuleDisplay.literal(it.unit, it.value) }

    private fun originLabel(p: PersonalParam): String = if (p.docId != null) "${p.docId} · ${p.fieldLabel ?: "campo"}" else "dichiarato"
}
