package xyz.grtlabs.pilastri.domain.rules

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.AddedSource
import xyz.grtlabs.pilastri.data.ChangeLayer
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.PackVersion
import xyz.grtlabs.pilastri.data.ParamClass
import xyz.grtlabs.pilastri.data.PersonalParam
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.RulesBook
import xyz.grtlabs.pilastri.data.RulesCodec
import xyz.grtlabs.pilastri.data.RulesData
import xyz.grtlabs.pilastri.data.UpdateWinner
import xyz.grtlabs.pilastri.data.VersionKind
import java.time.LocalDate

/*
 * The effective rule pack (Council 4 c "Layers" and "History"): shipped LAW + the confirmed versions on the head chain +
 * the confirmed personal params. SCENARIO values are kept apart and reach an evaluation only in Futuro ([Mode.FUTURO]).
 *
 * Replay: the head chain is applied from the root; a LAW change replaces or adds one row version (keyed by id + validFrom).
 * After an app update (the chain's last base differs from the shipped pack hash) every overridden row the update changed is
 * resolved: the person's row stays only when it is primary (VERIFIED) and its checkedOn is later than the shipped row's;
 * ties go to the shipped pack. Resolutions are history lines ([RulesHistory.resolveUpdate]), never Tier 3 rows.
 */

/** The shipped LAW pack texts. [hash] identifies the shipped content: a different hash means an app update. */
class ShippedPack(val packJson: String, val snapshotJson: String) {
    val hash: String = SnapshotStore.sha256(packJson)
    val pack: Pack by lazy { Pack.load(packJson, snapshotJson) }
    internal val root: JSONObject by lazy { JSONObject(packJson) }
    internal val rows: Map<RowKey, JSONObject> by lazy {
        val a = root.getJSONArray("rows")
        LinkedHashMap<RowKey, JSONObject>().apply { for (i in 0 until a.length()) a.getJSONObject(i).let { put(RowKey.of(it), it) } }
    }
    internal val snapshots: JSONArray by lazy { JSONObject(snapshotJson).getJSONArray("snapshots") }

    companion object {
        fun fromAssets(assets: AssetManager): ShippedPack = ShippedPack(
            assets.open(Rules.PACK_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
            assets.open(Rules.SNAPSHOT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }
}

/** Process-wide access to the shipped pack for the assistant's rule tools; loaded once from the app assets. */
object ShippedRules {
    @Volatile private var loaded: ShippedPack? = null
    @Volatile private var assets: AssetManager? = null

    fun install(assets: AssetManager) { if (this.assets == null) this.assets = assets }

    /** Tests and hosts that already hold the texts. */
    fun install(pack: ShippedPack?) { loaded = pack }

    fun current(): ShippedPack? = loaded ?: assets?.let { a ->
        try { ShippedPack.fromAssets(a).also { it.pack; loaded = it } } catch (_: Exception) { null }
    }
}

/** A law row version: its id and validFrom (ISO, null = start not read). */
data class RowKey(val id: String, val validFrom: String?) {
    companion object { fun of(row: JSONObject) = RowKey(row.getString("id"), row.str("validFrom")) }
}

/** A personal slot, for the person ([scope] null) or one fund or position. */
data class SlotKey(val slot: String, val scope: String?)

/** One app-update conflict. [userRow] is the person's confirmed row; [shippedRow] null when the update removed that version. */
data class UpdateResolution(val key: RowKey, val winner: UpdateWinner, val userRow: JSONObject, val shippedRow: JSONObject?, val reason: String)

class EffectivePack internal constructor(
    val shipped: ShippedPack,
    val pack: Pack,
    val book: RulesBook,
    /** The head version, null = the shipped pack alone. */
    val headId: String?,
    internal val rowJson: Map<RowKey, JSONObject>,
    internal val overridden: Set<RowKey>,
    internal val sources: Map<String, AddedSource>,
    val personalIds: Map<SlotKey, String>,
    val params: Map<String, PersonalParam>,
    /** Confirmed PERSONAL values for the person, only for declared slots. */
    val personal: Map<String, LayerValue>,
    /** SCENARIO values for declared slots: read only in Futuro. */
    val scenario: Map<String, LayerValue>,
    /** Update conflicts resolved in memory and not yet in the history. */
    val pendingResolutions: List<UpdateResolution>,
    /** The stored layer could not be applied; this is the shipped pack alone (plus personal and scenario values). */
    val degraded: Boolean,
) {
    val rules: Rules by lazy { Rules(pack) }

    fun row(key: RowKey): JSONObject? = rowJson[key]

    /** Row versions of [id] in the effective pack. */
    fun rows(id: String): List<JSONObject> = rowJson.filterKeys { it.id == id }.values.toList()

    /** The active PERSONAL param record for [key], if any. */
    fun activeParam(key: SlotKey): PersonalParam? = personalIds[key]?.let { params[it] }

    /** Personal values for an evaluation: the person's, with [scope]'s own values over them. */
    fun personalFor(scope: String?): Map<String, LayerValue> =
        if (scope == null) personal else personal + layer(personalIds.filterKeys { it.scope == scope }.mapKeys { it.key.slot })

    /** ACTUALS never receives the SCENARIO layer (Council 4 c: SCENARIO is Futuro only, never read by actuals). */
    fun context(
        date: LocalDate,
        inputs: Map<String, RuleInput> = emptyMap(),
        mode: Mode = Mode.ACTUALS,
        kinds: Set<PositionKind>? = null,
        gestioni: Set<Gestione>? = null,
        scope: String? = null,
    ): EvalContext = EvalContext(date, inputs, personalFor(scope), if (mode == Mode.FUTURO) scenario else emptyMap(), mode, kinds, gestioni)

    internal fun layer(ids: Map<String, String>): Map<String, LayerValue> =
        ids.mapNotNull { (slot, id) -> params[id]?.let { p -> layerValue(p)?.let { slot to it } } }.toMap()
            .filterKeys { it in pack.declaredSlots && it !in pack.ids }
}

object EffectiveRules {
    fun build(shipped: ShippedPack, data: RulesData): EffectivePack {
        val book = try { RulesCodec.decode(data) } catch (_: Exception) {
            return assemble(shipped, RulesBook(), Replay(emptyList()), emptyList(), degraded = true)
        }
        return build(shipped, book)
    }

    fun build(shipped: ShippedPack, book: RulesBook): EffectivePack {
        val replay = replay(book)
        val updated = replay.chain.isNotEmpty() && replay.chain.last().base != shipped.hash
        val resolutions = ArrayList<UpdateResolution>()
        if (updated) resolutions += resolve(shipped, replay)
        resolutions.forEach { if (it.winner == UpdateWinner.SHIPPED) replay.overlay.remove(it.key) }
        var errors = candidate(shipped, book, replay).second
        var degraded = false
        // A stored row that no longer fits the shipped pack gives way to it; a stored layer that cannot be repaired is dropped.
        var guard = 0
        while (errors.isNotEmpty() && replay.overlay.isNotEmpty() && guard++ < 8) {
            val bad = errors.map { it.substringBefore(':').trim() }.toSet()
            val keys = replay.overlay.keys.filter { it.id in bad }.ifEmpty { replay.overlay.keys.toList() }
            for (key in keys) {
                if (updated) resolutions += UpdateResolution(key, UpdateWinner.SHIPPED, replay.overlay.getValue(key), shipped.rows[key], "regola non più valida con l'aggiornamento")
                else degraded = true
                replay.overlay.remove(key)
            }
            errors = candidate(shipped, book, replay).second
        }
        if (errors.isNotEmpty()) degraded = true
        return assemble(shipped, book, replay, resolutions, degraded)
    }

    /** Problems of the pack [data] describes, without any repair: empty = a version may be written. */
    fun errors(shipped: ShippedPack, data: RulesData): List<String> {
        val book = try { RulesCodec.decode(data) } catch (e: Exception) { return listOf("storico: ${e.message}") }
        val replay = replay(book)
        if (replay.chain.isNotEmpty() && replay.chain.last().base != shipped.hash) {
            resolve(shipped, replay).forEach { if (it.winner == UpdateWinner.SHIPPED) replay.overlay.remove(it.key) }
        }
        return candidate(shipped, book, replay).second
    }

    // ---- history replay ----

    internal class Replay(
        val chain: List<PackVersion>,
        val overlay: LinkedHashMap<RowKey, JSONObject> = LinkedHashMap(),
        /** Canonical shipped row an override was made against (null = the version was absent). */
        val baseline: HashMap<RowKey, String?> = HashMap(),
        val sources: LinkedHashMap<String, AddedSource> = LinkedHashMap(),
        val personalIds: LinkedHashMap<SlotKey, String> = LinkedHashMap(),
    )

    /** The head: the last node, or an undo's target parent. Unknown kinds from a newer app are skipped. */
    fun headId(book: RulesBook): String? {
        var head: String? = null
        for (v in book.versions) when {
            v.kind.known == VersionKind.UNDONE -> head = v.parentId
            v.isNode -> head = v.id
        }
        return head
    }

    /** The nodes from the root to the head. */
    fun chain(book: RulesBook): List<PackVersion> {
        val nodes = book.versions.filter { it.isNode }.associateBy { it.id }
        val out = ArrayList<PackVersion>()
        val seen = HashSet<String>()
        var id = headId(book)
        while (id != null && seen.add(id)) {
            val v = nodes[id] ?: break
            out += v
            id = v.parentId
        }
        return out.asReversed()
    }

    private fun replay(book: RulesBook): Replay {
        val r = Replay(chain(book))
        for (v in r.chain) {
            r.sources.putAll(v.sources)
            val resolution = v.kind.known == VersionKind.UPDATE_RESOLVED
            for (c in v.changes) when (c.layer.known) {
                ChangeLayer.LAW -> {
                    val key = RowKey(c.target, c.key)
                    when {
                        resolution && c.winner?.known == UpdateWinner.SHIPPED -> { r.overlay.remove(key); r.baseline.remove(key) }
                        resolution && c.winner?.known == UpdateWinner.KEPT -> {
                            c.afterRow?.let { r.overlay[key] = JSONObject(it) }
                            r.baseline[key] = c.shippedRow?.let { canonical(JSONObject(it)) }
                        }
                        resolution -> Unit
                        c.afterRow == null -> { r.overlay.remove(key); r.baseline.remove(key) }
                        else -> {
                            if (key !in r.overlay) r.baseline[key] = c.beforeRow?.let { canonical(JSONObject(it)) }
                            r.overlay[key] = JSONObject(c.afterRow)
                        }
                    }
                }
                ChangeLayer.PERSONAL -> {
                    val key = SlotKey(c.target, c.key)
                    if (c.afterParam == null) r.personalIds.remove(key) else r.personalIds[key] = c.afterParam
                }
                null -> Unit
            }
        }
        return r
    }

    private fun resolve(shipped: ShippedPack, r: Replay): List<UpdateResolution> = r.overlay.mapNotNull { (key, user) ->
        val shippedRow = shipped.rows[key]
        if (shippedRow?.let(::canonical) == r.baseline[key]) return@mapNotNull null
        val userChecked = user.str("checkedOn")?.let(LocalDate::parse)
        val shippedChecked = shippedRow?.str("checkedOn")?.let(LocalDate::parse)
        val keep = shippedRow != null && user.str("status") == "VERIFIED" && userChecked != null && shippedChecked != null && userChecked.isAfter(shippedChecked)
        val reason = when {
            shippedRow == null -> "l'aggiornamento ha tolto questa versione"
            keep -> "fonte primaria verificata dopo ($userChecked > $shippedChecked)"
            userChecked == shippedChecked -> "stessa data di verifica: vale l'aggiornamento"
            else -> "aggiornamento verificato dopo ($shippedChecked)"
        }
        UpdateResolution(key, if (keep) UpdateWinner.KEPT else UpdateWinner.SHIPPED, user, shippedRow, reason)
    }

    // ---- assembly ----

    private fun candidate(shipped: ShippedPack, book: RulesBook, r: Replay): Pair<Pack?, List<String>> {
        val root = JSONObject()
        for (k in shipped.root.keys()) if (k != "rows" && k != "sources") root.put(k, shipped.root.get(k))
        val sources = JSONObject()
        shipped.root.optJSONObject("sources")?.let { s -> for (k in s.keys()) sources.put(k, s.get(k)) }
        r.sources.forEach { (k, s) -> if (!sources.has(k)) sources.put(k, JSONObject().put("url", s.url ?: JSONObject.NULL).put("read", s.read)) }
        root.put("sources", sources)
        val rows = JSONArray()
        shipped.rows.forEach { (key, row) -> rows.put(r.overlay[key] ?: row) }
        r.overlay.forEach { (key, row) -> if (key !in shipped.rows) rows.put(row) }
        root.put("rows", rows)
        val snaps = JSONArray()
        val hashes = HashSet<String>()
        for (i in 0 until shipped.snapshots.length()) shipped.snapshots.getJSONObject(i).let { snaps.put(it); hashes += it.getString("hash") }
        book.snapshots.forEach { s ->
            if (hashes.add(s.hash)) snaps.put(JSONObject().put("hash", s.hash).put("url", s.url ?: JSONObject.NULL).put("recordedOn", s.recordedOn.toString())
                .put("kind", s.kind).put("summaryOnly", s.summaryOnly).put("text", s.text))
        }
        return try {
            val pack = Pack.parse(root.toString(), JSONObject().put("snapshots", snaps).toString())
            pack to pack.validate()
        } catch (e: Exception) {
            null to listOf("${e.message}")
        }
    }

    private fun assemble(shipped: ShippedPack, book: RulesBook, r: Replay, resolutions: List<UpdateResolution>, degraded: Boolean): EffectivePack {
        val overlay = if (degraded) LinkedHashMap() else r.overlay
        val replay = if (degraded) Replay(r.chain, personalIds = r.personalIds) else r
        val pack = (if (degraded) shipped.pack else candidate(shipped, book, replay).first) ?: shipped.pack
        val rowJson = LinkedHashMap<RowKey, JSONObject>()
        shipped.rows.forEach { (key, row) -> rowJson[key] = overlay[key] ?: row }
        overlay.forEach { (key, row) -> if (key !in rowJson) rowJson[key] = row }
        val params = book.params.associateBy { it.id }
        val usable = { slot: String -> slot in pack.declaredSlots && slot !in pack.ids }
        val personal = r.personalIds.filterKeys { it.scope == null }
            .mapNotNull { (k, id) -> params[id]?.let { p -> layerValue(p)?.let { k.slot to it } } }.toMap().filterKeys(usable)
        val scenario = LinkedHashMap<String, LayerValue>()
        book.params.filter { it.paramClass.known == ParamClass.SCENARIO && it.scope == null && usable(it.slot) }
            .forEach { p -> layerValue(p)?.let { scenario[p.slot] = it } }
        return EffectivePack(shipped, pack, book, headId(book), rowJson, overlay.keys.toSet(), replay.sources, r.personalIds, params,
            personal, scenario, resolutions, degraded)
    }
}

/** A stored param as a layer value; null when its unit, class or literal is not usable by this app version. */
internal fun layerValue(p: PersonalParam): LayerValue? = try {
    val cls = p.paramClass.known ?: return null
    if (cls == ParamClass.LAW) return null
    val origin = when (cls) {
        ParamClass.SCENARIO -> Origin.DICHIARATO
        else -> p.origin.known?.takeIf { it == Origin.DOCUMENTO || it == Origin.DICHIARATO } ?: return null
    }
    LayerValue(RValue.parse(RuleUnit.valueOf(p.unit), p.value), cls, origin)
} catch (_: Exception) { null }

/** JSON with sorted keys and without null members, so equal rows compare equal whatever their key order. */
internal fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().filter { !value.isNull(it) }.toList().sorted()
        .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}
