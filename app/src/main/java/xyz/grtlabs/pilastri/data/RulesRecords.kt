package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/*
 * Typed view of the archive v14 rule collections ([RulesData], Council 4 c "History"). Storage stays the raw arrays, so
 * the archive version does not change. Each typed record keeps the object it was read from ([raw]); [RulesCodec.encode]
 * writes the known fields over it, so fields a newer app added survive a round trip verbatim. Unknown enum codes are kept
 * as [Coded.Unknown] and written back unchanged. `payslipLineClasses` stays raw (the cedolino parser is another slice).
 */

/** CONFIRMED: a Tier 3 row. UNDONE: the head moved back to the undone version's parent. UPDATE_RESOLVED: an app update met a
 * confirmed row. REFRESHED: a newer primary source or document with identical values (only dates and source change). */
enum class VersionKind { CONFIRMED, UNDONE, UPDATE_RESOLVED, REFRESHED }

enum class ChangeLayer { LAW, PERSONAL }

/** Which side an app-update conflict kept: the shipped row, or the person's confirmed row (later primary `checkedOn`). */
enum class UpdateWinner { SHIPPED, KEPT }

/**
 * One row of a [PackVersion]. LAW: [target] is the row id and [key] its version's validFrom (ISO, null = not read); the rows
 * are full row JSON objects as text (null = absent). PERSONAL: [target] is the slot, [key] the scope (a fund or position id,
 * null = the person), and the params are [PersonalParam] ids. [shippedRow] is set on UPDATE_RESOLVED rows only: the shipped
 * row the conflict was resolved against.
 */
data class RowChange(
    val layer: Coded<ChangeLayer>,
    val target: String,
    val key: String?,
    val beforeRow: String? = null,
    val afterRow: String? = null,
    val beforeParam: String? = null,
    val afterParam: String? = null,
    val winner: Coded<UpdateWinner>? = null,
    val shippedRow: String? = null,
    val raw: String = "{}",
)

/** A pack source code a version adds (the validator needs every row source to be declared). */
data class AddedSource(val url: String?, val read: String)

/**
 * An immutable entry of the rule history. Nodes ([VersionKind.CONFIRMED], [VersionKind.UPDATE_RESOLVED],
 * [VersionKind.REFRESHED]) chain through [parentId] (null = the shipped pack); an [VersionKind.UNDONE] entry re-points the
 * head to [parentId] and names the undone version in [undone]. [base] is the hash of the shipped pack JSON it was made on.
 */
data class PackVersion(
    val id: String,
    val parentId: String?,
    val kind: Coded<VersionKind>,
    val at: Instant,
    val base: String,
    val undone: String? = null,
    val sourceUrl: String? = null,
    val primary: Boolean = false,
    val quote: String? = null,
    val snapshotHash: String? = null,
    val sources: Map<String, AddedSource> = emptyMap(),
    val changes: List<RowChange> = emptyList(),
    val raw: String = "{}",
) {
    val isNode: Boolean get() = kind.known.let { it == VersionKind.CONFIRMED || it == VersionKind.UPDATE_RESOLVED || it == VersionKind.REFRESHED }
}

/**
 * A personal or scenario value for a declared personalSlot. [value] is the pack literal and [unit] a rule unit name.
 * PERSONAL_DOCUMENT carries the document id, the field label and the document hash; PERSONAL_DECLARED none of them.
 * PERSONAL records apply only through a confirmed [PackVersion]; SCENARIO records apply directly (Futuro only).
 */
data class PersonalParam(
    val id: String,
    val slot: String,
    val scope: String?,
    val value: String,
    val unit: String,
    val paramClass: Coded<ParamClass>,
    val origin: Coded<Origin>,
    val docId: String? = null,
    val fieldLabel: String? = null,
    val docHash: String? = null,
    val asOf: LocalDate? = null,
    val recordedAt: Instant,
    val raw: String = "{}",
)

/** A quote snapshot the person confirmed with a row: the same shape as the shipped snapshot store. */
data class StoredSnapshot(
    val hash: String,
    val url: String?,
    val recordedOn: LocalDate,
    val kind: String,
    val summaryOnly: Boolean,
    val text: String,
    val raw: String = "{}",
)

data class RulesBook(
    val versions: List<PackVersion> = emptyList(),
    val params: List<PersonalParam> = emptyList(),
    val snapshots: List<StoredSnapshot> = emptyList(),
    val payslipLineClasses: RawJsonArray = RawJsonArray.EMPTY,
)

object RulesCodec {
    private const val MAX_ITEMS = 10_000

    fun decode(data: RulesData): RulesBook = RulesBook(
        objects(data.packVersions).map(::decodeVersion),
        objects(data.personalParams).map(::decodeParam),
        objects(data.snapshots).map(::decodeSnapshot),
        data.payslipLineClasses,
    )

    fun encode(book: RulesBook): RulesData = RulesData(
        RawJsonArray.of(JSONArray().apply { book.versions.forEach { put(encodeVersion(it)) } }),
        RawJsonArray.of(JSONArray().apply { book.params.forEach { put(encodeParam(it)) } }),
        RawJsonArray.of(JSONArray().apply { book.snapshots.forEach { put(encodeSnapshot(it)) } }),
        book.payslipLineClasses,
    )

    /** Full check before an archive is written: every record decodes and the history is consistent. */
    fun validate(data: RulesData) {
        val book = try { decode(data) } catch (e: Exception) { throw IllegalArgumentException("Storico delle regole non valido", e) }
        require(book.versions.size <= MAX_ITEMS && book.params.size <= MAX_ITEMS && book.snapshots.size <= MAX_ITEMS) { "Troppe versioni delle regole" }
        val seen = HashSet<String>()
        for (v in book.versions) {
            require(v.id.isNotBlank() && v.id.length <= 120 && seen.add(v.id)) { "Versione delle regole non valida" }
            require(v.parentId == null || v.parentId in seen) { "Versione delle regole senza origine" }
            if (v.kind.known == VersionKind.UNDONE) require(v.undone != null && v.undone in seen) { "Annullamento di una versione inesistente" }
            require(v.quote == null || v.quote.length <= 200) { "Citazione troppo lunga" }
        }
        val params = HashSet<String>()
        for (p in book.params) {
            require(p.id.isNotBlank() && p.id.length <= 120 && params.add(p.id)) { "Parametro personale non valido" }
            require(p.slot.isNotBlank() && p.slot.length <= 120 && p.value.length <= 2000) { "Parametro personale non valido" }
        }
        val hashes = HashSet<String>()
        for (s in book.snapshots) {
            require(s.text.length <= 100_000 && sha256(s.text) == s.hash && hashes.add(s.hash)) { "Fonte salvata non valida" }
        }
    }

    fun sha256(text: String): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- versions ----

    private fun decodeVersion(o: JSONObject): PackVersion = PackVersion(
        id = o.getString("id"),
        parentId = o.text("parentId"),
        kind = coded(o.getString("kind")),
        at = Instant.parse(o.getString("at")),
        base = o.getString("base"),
        undone = o.text("undone"),
        sourceUrl = o.text("sourceUrl"),
        primary = o.optBoolean("primary", false),
        quote = o.text("quote"),
        snapshotHash = o.text("snapshotHash"),
        sources = o.optJSONObject("sources")?.let { s -> s.keys().asSequence().toList().associateWith { k -> s.getJSONObject(k).let { AddedSource(it.text("url"), it.getString("read")) } } }.orEmpty(),
        changes = o.optJSONArray("changes")?.let { a -> (0 until a.length()).map { decodeChange(a.getJSONObject(it)) } }.orEmpty(),
        raw = o.toString(),
    )

    private fun encodeVersion(v: PackVersion): JSONObject = JSONObject(v.raw)
        .put("id", v.id).put("parentId", v.parentId ?: JSONObject.NULL).put("kind", v.kind.raw).put("at", v.at.toString()).put("base", v.base)
        .keep("undone", v.undone).keep("sourceUrl", v.sourceUrl).apply { if (v.primary || has("primary")) put("primary", v.primary) }.keep("quote", v.quote)
        .keep("snapshotHash", v.snapshotHash)
        .keep("sources", v.sources.takeIf { it.isNotEmpty() }?.let { m -> JSONObject().apply { m.forEach { (k, s) -> put(k, JSONObject().put("url", s.url ?: JSONObject.NULL).put("read", s.read)) } } })
        .keep("changes", v.changes.takeIf { it.isNotEmpty() }?.let { c -> JSONArray().apply { c.forEach { put(encodeChange(it)) } } })

    private fun decodeChange(o: JSONObject): RowChange = RowChange(
        layer = coded(o.getString("layer")),
        target = o.getString("target"),
        key = o.text("key"),
        beforeRow = o.optJSONObject("before")?.toString(),
        afterRow = o.optJSONObject("after")?.toString(),
        beforeParam = o.text("beforeParam"),
        afterParam = o.text("afterParam"),
        winner = o.text("winner")?.let { coded<UpdateWinner>(it) },
        shippedRow = o.optJSONObject("shipped")?.toString(),
        raw = o.toString(),
    )

    /** A stored change keeps its own unknown fields through [RowChange.raw]. */
    private fun encodeChange(c: RowChange): JSONObject = JSONObject(c.raw)
        .put("layer", c.layer.raw).put("target", c.target).put("key", c.key ?: JSONObject.NULL)
        .keep("before", c.beforeRow?.let(::JSONObject)).keep("after", c.afterRow?.let(::JSONObject))
        .keep("beforeParam", c.beforeParam).keep("afterParam", c.afterParam)
        .keep("winner", c.winner?.raw).keep("shipped", c.shippedRow?.let(::JSONObject))

    // ---- params and snapshots ----

    private fun decodeParam(o: JSONObject): PersonalParam = PersonalParam(
        id = o.getString("id"), slot = o.getString("slot"), scope = o.text("scope"), value = o.getString("value"), unit = o.getString("unit"),
        paramClass = coded(o.getString("paramClass")), origin = coded(o.getString("origin")),
        docId = o.text("docId"), fieldLabel = o.text("fieldLabel"), docHash = o.text("docHash"),
        asOf = o.text("asOf")?.let(LocalDate::parse), recordedAt = Instant.parse(o.getString("recordedAt")), raw = o.toString(),
    )

    private fun encodeParam(p: PersonalParam): JSONObject = JSONObject(p.raw)
        .put("id", p.id).put("slot", p.slot).keep("scope", p.scope).put("value", p.value).put("unit", p.unit)
        .put("paramClass", p.paramClass.raw).put("origin", p.origin.raw).keep("docId", p.docId)
        .keep("fieldLabel", p.fieldLabel).keep("docHash", p.docHash).keep("asOf", p.asOf?.toString()).put("recordedAt", p.recordedAt.toString())

    private fun decodeSnapshot(o: JSONObject): StoredSnapshot = StoredSnapshot(
        o.getString("hash"), o.text("url"), LocalDate.parse(o.getString("recordedOn")), o.getString("kind"),
        o.optBoolean("summaryOnly", true), o.getString("text"), o.toString(),
    )

    private fun encodeSnapshot(s: StoredSnapshot): JSONObject = JSONObject(s.raw)
        .put("hash", s.hash).keep("url", s.url).put("recordedOn", s.recordedOn.toString()).put("kind", s.kind)
        .put("summaryOnly", s.summaryOnly).put("text", s.text)

    // ---- helpers ----

    private fun objects(raw: RawJsonArray): List<JSONObject> = raw.toJsonArray().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private inline fun <reified E : Enum<E>> coded(raw: String): Coded<E> =
        enumValues<E>().firstOrNull { it.name == raw }?.let { Coded.Known(it) } ?: Coded.Unknown(raw)

    /** Writes [value]; an absent value stays absent (or null where the stored object had the key), so a round trip adds nothing. */
    private fun JSONObject.keep(key: String, value: Any?): JSONObject =
        if (value != null) put(key, value) else { if (has(key)) put(key, JSONObject.NULL); this }

    private fun JSONObject.text(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
