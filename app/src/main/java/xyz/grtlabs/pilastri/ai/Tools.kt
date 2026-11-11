package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.AuditEntry
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One line of the diff the app renders for a change: [before] -> [after] (either side may be absent). */
data class AiDiffLine(val label: String, val before: String?, val after: String?, val isNew: Boolean = false)

/**
 * A reversible change to the archive made on the assistant's behalf.
 *
 * [apply] and [undo] are inverse operations on one record: [undo] first checks that the record this change created
 * is still present and unchanged and otherwise throws [AiAlreadyModifiedException]; it never restores a snapshot.
 * Both are meant to run inside the host's `commit` on the latest archive. [apply] is idempotent: applying a change
 * whose record already exists unchanged returns the archive as is, so a repeated tap cannot duplicate a record.
 * Applying appends an `AuditEntry(kind = "assistente", previous = summary)`.
 *
 * [diff] is what the app renders for a pending change; [sourceRows] are the imported rows (third-party text) the
 * change is based on or that were read in the same user turn; [reviewNote] says why a tap is required.
 */
data class AiChange(
    val id: String,
    val kind: AiChangeKind,
    val summary: String,
    val apply: (Archive) -> Archive,
    val undo: (Archive) -> Archive,
    val diff: List<AiDiffLine> = emptyList(),
    val sourceRows: List<String> = emptyList(),
    val reviewNote: String? = null,
    /** How many records this change touches (a category batch holds many); the card shows the count, not one card per record. */
    val count: Int = 1,
    /** Ids of the changes merged into this one (a request's changes share one card and one Accetta). */
    val memberIds: List<String> = listOf(id),
    /** Set on a Tier 3 rule row ([AiPolicy.tier3Kinds]): what its own row shows. Such a change is never merged. */
    val rule: xyz.grtlabs.pilastri.domain.rules.RuleRowView? = null,
    /** Spending categories this change creates; the card shows each one distinctly ("nuova"). */
    val newCategories: List<String> = emptyList(),
) {
    fun applyTo(archive: Archive): Archive = this.apply.invoke(archive)
    fun undoFrom(archive: Archive): Archive = this.undo.invoke(archive)
}

/** Summary prefix of a category batch; the card turns it into "N categorie da applicare". */
const val CATEGORY_BATCH_SUMMARY = "Categorizza"

/**
 * Two changes of the same request as one card: applied together (all or nothing, because [apply] is a pure transform of the
 * archive), undone in reverse order. A request never shows one card per record.
 */
fun AiChange.mergedWith(other: AiChange): AiChange {
    require(rule == null && other.rule == null && !AiPolicy.isTier3(kind) && !AiPolicy.isTier3(other.kind)) { "Una riga da confermare non si unisce ad altre." }
    val bothCategories = summary.startsWith(CATEGORY_BATCH_SUMMARY) && other.summary.startsWith(CATEGORY_BATCH_SUMMARY)
    val ids = memberIds + other.memberIds
    val total = count + other.count
    return AiChange(
        id = sha256Hex(ids.joinToString("+")).take(16),
        kind = kind,
        summary = if (bothCategories) "$CATEGORY_BATCH_SUMMARY $total movimenti importati" else "${ids.size} modifiche",
        apply = { a -> other.applyTo(this.applyTo(a)) },
        undo = { a -> this.undoFrom(other.undoFrom(a)) },
        diff = diff + other.diff,
        sourceRows = (sourceRows + other.sourceRows).distinct(),
        reviewNote = reviewNote ?: other.reviewNote,
        count = total,
        memberIds = ids,
        newCategories = (newCategories + other.newCategories).distinct(),
    )
}

/** The record to undo was changed or removed after the assistant wrote it. */
class AiAlreadyModifiedException(message: String) : IllegalStateException(message)

/** The model passed arguments that cannot be used. The message is Italian and goes back to the model. */
class AiToolInputException(message: String) : IllegalArgumentException(message)

internal fun bad(message: String): Nothing = throw AiToolInputException(message)

data class AiReadStat(val tool: String, val calls: Int, val items: Int)

/**
 * State shared by all tool calls of one user turn: what was read, whether third-party text was seen (the taint
 * rule forces every write of the turn to wait for a tap) and how many writes were proposed so far.
 */
class AiTurnState {
    val externalRows = LinkedHashSet<String>()
    var writesProposed = 0
    private val readStats = LinkedHashMap<String, AiReadStat>()
    private val reasonRows = HashSet<String>()
    private val reasons = LinkedHashSet<String>()

    val tainted: Boolean get() = externalRows.isNotEmpty()

    /** "da cedolino ottobre 2026 (allegato il 2026-11-01)" when the turn works on attached documents: appended to every written record's source. */
    var documentSource: String? = null

    /**
     * Why the turn is tainted (Italian, for the pending card), or null when it is not: rows read by a tool give
     * [READ_REASON]; [taint] adds its own reason (a conversation tainted earlier, an account page, a routine, a paste).
     */
    val taintReason: String?
        get() {
            if (!tainted) return null
            val all = ArrayList<String>()
            if (externalRows.any { it !in reasonRows }) all += READ_REASON
            all += reasons
            return all.joinToString("; ")
        }

    /** Marks the whole turn as tainted for a reason other than a row read by a tool. [row] is shown on every pending card. */
    fun taint(row: String, reason: String) {
        externalRows += row
        reasonRows += row
        reasons += reason
    }
    val reads: List<AiReadStat> get() = readStats.values.toList()

    companion object {
        const val READ_REASON = "in questa richiesta sono stati letti testi di terzi o testi scritti prima dall'assistente"
    }

    fun recordRead(tool: String, items: Int) {
        val old = readStats[tool]
        readStats[tool] = AiReadStat(tool, (old?.calls ?: 0) + 1, (old?.items ?: 0) + items)
    }
}

/**
 * Taint that outlives a turn. The provider keeps the earlier turns of a conversation (the API history, the account page's
 * own chat), so text read in one turn can still steer a later one. Once any turn of the open conversation was tainted,
 * every later turn starts tainted, with the earlier rows shown on its cards, until the user starts a new conversation
 * ("Nuova", [AssistantState.newConversation]) and [clear] runs.
 */
class AiConversationTaint {
    private val rows = LinkedHashSet<String>()

    @Volatile var tainted: Boolean = false
        private set

    /** Records a finished (or failed) turn: if it was tainted, the conversation is from now on. */
    @Synchronized fun absorb(turn: AiTurnState) {
        if (!turn.tainted) return
        tainted = true
        turn.externalRows.forEach { if (rows.size < MAX_ROWS) rows += it }
    }

    /** Starts [turn] tainted when the conversation already is. */
    @Synchronized fun carryInto(turn: AiTurnState) {
        if (!tainted) return
        if (rows.isEmpty()) turn.taint(GENERIC_ROW, REASON) else rows.forEach { turn.taint(it, REASON) }
    }

    @Synchronized fun clear() {
        tainted = false
        rows.clear()
    }

    companion object {
        const val MAX_ROWS = 20
        const val REASON = "in una richiesta precedente di questa conversazione sono entrati testi di terzi o non verificabili: " +
            "fino a «Nuova» ogni modifica aspetta la tua conferma"
        const val GENERIC_ROW = "Testi di terzi letti prima in questa conversazione"
    }
}

/** Start of [AiToolContext.source]: a record whose source starts with it, or whose id starts with `ai:`, was written by the assistant. */
internal const val ASSISTANT_SOURCE_PREFIX = "Assistente ·"

/** The record's free text (a name, a label) was written by the assistant, so it is untrusted when read back. */
internal fun writtenByAssistant(id: String, source: String): Boolean =
    id.startsWith("ai:") || source.trimStart().startsWith(ASSISTANT_SOURCE_PREFIX, ignoreCase = true)

class AiToolContext(
    val archive: Archive,
    /** Who wrote the change, as shown in its source: "Anthropic", "Claude (account)"… */
    val sourceLabel: String,
    val toolCallId: String,
    val turn: AiTurnState = AiTurnState(),
    val clock: () -> Instant = { Instant.now() },
    val redact: Boolean = true,
) {
    constructor(
        archive: Archive,
        provider: AiProvider,
        toolCallId: String,
        turn: AiTurnState = AiTurnState(),
        clock: () -> Instant = { Instant.now() },
        redact: Boolean = true,
    ) : this(archive, provider.label, toolCallId, turn, clock, redact)

    /** Free text going to the model. Ids and numbers produced by the app are not passed through this. */
    fun text(value: String): String = if (redact) AiPolicy.redact(value) else value

    /** Records a row whose untrusted text (third-party, or written earlier by the assistant) is part of a tool result. */
    fun markExternal(row: String) { turn.externalRows += row }

    fun source(): String = "$ASSISTANT_SOURCE_PREFIX $sourceLabel · ${clock().truncatedTo(ChronoUnit.SECONDS)}" +
        (turn.documentSource?.let { " · $it" } ?: "")
}

sealed interface AiToolResult {
    /** [items] is the number of rows returned, for the "letto" line of the reply. */
    data class Read(val json: String, val items: Int) : AiToolResult

    /** A prepared write. [modelNote] describes it without third-party text. */
    data class Proposal(val change: AiChange, val modelNote: String) : AiToolResult

    data class Failure(val message: String) : AiToolResult
}

class AiTool(
    val name: String,
    /** Italian description shown to the model. */
    val description: String,
    /** JSON Schema of the arguments. */
    val parameters: JSONObject,
    val writes: Boolean,
    private val body: (AiToolContext, JSONObject) -> AiToolResult,
) {
    val spec = AiToolSpec(name, description, parameters)
    fun run(context: AiToolContext, arguments: JSONObject): AiToolResult = body(context, arguments)
}

/**
 * The neutral tool registry. Read tools are paged (limit at most 50). Write tools only prepare an [AiChange];
 * the agent decides whether it applies directly or waits for a tap. There is deliberately no tool for performance
 * attestations, archive restore, API keys or sessions, or the privacy and redaction toggles.
 */
object AiTools {
    const val MAX_RESULT_CHARS = 40_000

    val all: List<AiTool> by lazy { ReadTools.all + InstallmentTools.reads + RuleTools.reads + WriteTools.all + InstallmentTools.writes + RuleTools.writes }

    fun specs(): List<AiToolSpec> = all.map { it.spec }

    fun find(name: String): AiTool? = all.firstOrNull { it.name == name }

    /** Runs a tool and turns every input problem into an Italian [AiToolResult.Failure]. */
    fun execute(name: String, arguments: JSONObject, context: AiToolContext): AiToolResult {
        val tool = find(name) ?: return AiToolResult.Failure("Strumento sconosciuto: $name.")
        return try {
            val result = tool.run(context, arguments)
            if (result is AiToolResult.Read && result.json.length > MAX_RESULT_CHARS) {
                AiToolResult.Failure("Il risultato è troppo grande: usa un limite più basso o più filtri.")
            } else result
        } catch (e: AiAlreadyModifiedException) {
            AiToolResult.Failure(e.message ?: "Il record è già modificato.")
        } catch (e: IllegalArgumentException) {
            AiToolResult.Failure(e.message?.takeIf { it != "Failed requirement." } ?: "Valori non validi per questa operazione.")
        } catch (e: IllegalStateException) {
            AiToolResult.Failure(e.message ?: "Operazione non possibile nello stato attuale.")
        } catch (e: java.time.DateTimeException) {
            AiToolResult.Failure("Data non valida: usa il formato AAAA-MM-GG (mese AAAA-MM).")
        } catch (e: ArithmeticException) {
            AiToolResult.Failure("Importo fuori dai limiti consentiti.")
        }
    }

    /**
     * Renders the registry as an instruction block for a text-only transport: each tool is called with a fenced
     * JSON block. Not wired to any transport; native tool calling is used today.
     */
    fun renderInstructionBlock(): String = buildString {
        appendLine("Hai a disposizione questi strumenti. Per usarne uno, rispondi SOLO con un blocco di codice JSON, uno per chiamata:")
        appendLine("```json")
        appendLine("""{"tool": "<nome>", "arguments": { }}""")
        appendLine("```")
        appendLine("Il risultato arriverà in un messaggio che inizia con RISULTATO <nome>. Quando hai finito, rispondi in italiano senza blocchi JSON.")
        appendLine()
        all.forEach { tool ->
            appendLine("### ${tool.name} (${if (tool.writes) "scrittura" else "lettura"})")
            appendLine(tool.description)
            appendLine("Argomenti (JSON Schema):")
            appendLine("```json")
            appendLine(tool.parameters.toString())
            appendLine("```")
            appendLine()
        }
    }
}

// ---- shared helpers for the read and write tools ----

internal val PILLAR_NAMES = listOf("Liquidità", "Emergenze", "Progetti", "Lungo termine")

internal fun schema(required: List<String> = emptyList(), vararg properties: Pair<String, JSONObject>): JSONObject =
    JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { properties.forEach { put(it.first, it.second) } })
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

internal fun prop(type: String, description: String, values: List<String>? = null): JSONObject =
    JSONObject().put("type", type).put("description", description).apply { if (values != null) put("enum", JSONArray(values)) }

internal val pagingProps: Array<Pair<String, JSONObject>> = arrayOf(
    "limite" to prop("integer", "Quante righe restituire (1-50, predefinito 20)."),
    "offset" to prop("integer", "Quante righe saltare per la pagina successiva (predefinito 0)."),
)

internal fun JSONObject.argText(key: String): String? {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) return null
    return value.toString().trim().ifEmpty { null }
}

internal fun JSONObject.argInt(key: String): Int? {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) return null
    if (value is Number) return if (value.toDouble() % 1.0 == 0.0) value.toInt() else null
    return value.toString().trim().toIntOrNull()
}

internal fun JSONObject.argBool(key: String): Boolean {
    val value = opt(key) ?: return false
    return value == true || value.toString().equals("true", ignoreCase = true)
}

internal fun JSONObject.argDate(key: String): LocalDate? =
    argText(key)?.let { LocalDate.parse(it) }

/** Plain euro amount from cents: `1234.56`. */
internal fun plain(cents: Long): String {
    val negative = cents < 0
    val absolute = if (negative) -cents else cents
    return (if (negative) "-" else "") + "${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
}

/** Italian display: `1.234,56 €`. */
internal fun italian(cents: Long): String {
    val negative = cents < 0
    val absolute = if (negative) -cents else cents
    val whole = (absolute / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return (if (negative) "−" else "") + "$whole,${(absolute % 100).toString().padStart(2, '0')}\u00A0€"
}

/** A euro amount with at most two decimals and no thousands separator. Anything else is refused. */
internal fun parseEuroToCents(raw: Any?, field: String): Long {
    val text = when (raw) {
        null, JSONObject.NULL -> bad("Manca $field.")
        is Number -> BigDecimal(raw.toString()).toPlainString()
        else -> raw.toString().trim()
    }
    if (!Regex("[0-9]{1,12}([.,][0-9]{1,2})?").matches(text)) {
        bad("$field non valido: usa un numero in euro con al massimo due decimali, senza separatore delle migliaia e senza segno (es. 1234.56).")
    }
    return BigDecimal(text.replace(',', '.')).movePointRight(2).longValueExact()
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** JSON with sorted keys, so equal arguments always hash equally (org.json key order is not stable across runtimes). */
internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
        JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}

/** Deterministic idempotency key: the same tool call id, tool and arguments always give the same key. */
fun idempotencyKey(toolCallId: String, toolName: String, arguments: JSONObject): String =
    sha256Hex("$toolCallId\n$toolName\n${canonicalJson(arguments)}").take(16)

/** Writes the audit entry for a creation or an undo. */
internal fun Archive.audited(clock: () -> Instant, kind: String, summary: String): Archive =
    copy(audit = audit + AuditEntry(clock().toString().take(50), kind, summary.take(2000)))

/** Stable, short reference for an imported movement; keeps provider ids out of the model's view. */
internal fun movementRef(id: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
    var value = 0L
    for (i in 0 until 5) value = (value shl 8) or (bytes[i].toLong() and 255)
    return "m" + value.toString(36).padStart(8, '0')
}
