package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.PaymentMethodNames
import xyz.grtlabs.pilastri.data.installmentPaymentIds
import java.time.Instant

/**
 * The single write path for every assistant route (API tool calls, account web chat, routine, pasted reply). A write tool
 * only prepares an [AiChange]; this router decides, with [AiPolicy], whether it is applied at once (Tier 1) or waits for
 * the user's tap (Tier 2), and applies Tier 1 through the host's `commit` with retries and the [AiWriteQueue].
 *
 * A tap is forced (never the reverse) when the kind requires it, when third-party text was read in the same user turn
 * or earlier in the same conversation (taint rule, [AiTurnState.tainted], [AiConversationTaint]), when the reply came from
 * an account page, or when the turn holds more than one write. Tier 3 rule rows ([AiPolicy.tier3Kinds]) always wait, each
 * as its own row with [AiChange.rule]; they are never applied here and never merged with another change.
 */
internal const val CATEGORY_TOOL = "categorizza_movimenti"

internal class AiWriteRouter(
    var current: Archive,
    private val commit: suspend ((Archive) -> Archive) -> Boolean,
    private val sourceLabel: String,
    private val turn: AiTurnState,
    private val clock: () -> Instant,
    private val redact: Boolean,
    private val queue: AiWriteQueue,
    private val retryDelaysMs: List<Long> = AiCommits.DEFAULT_RETRY_DELAYS_MS,
    private val maxWrites: Int = 20,
    private val onApplied: (AiChange) -> Unit = {},
    private val onPending: (AiChange) -> Unit = {},
    private val onQueued: (AiChange) -> Unit = {},
    /** Extra kinds that must wait for a tap. Additive only: it can never make [AiPolicy.requiresTap] weaker. */
    private val extraTap: (AiChangeKind) -> Boolean = { false },
) {
    val applied = ArrayList<AiChange>()
    val pending = ArrayList<AiChange>()
    val queued = ArrayList<AiChange>()
    val failures = ArrayList<AiFailure>()
    private val seenWrites = HashSet<String>()

    /** What goes back to the model (or into the reply notes of a text-only route). */
    data class Routed(val text: String, val isError: Boolean)

    fun context(callId: String) = AiToolContext(current, sourceLabel, callId, turn, clock, redact)

    /** Runs a read tool on the latest archive and records it on the turn; the JSON is redacted for structured output. */
    suspend fun read(name: String, arguments: JSONObject, callId: String): AiToolResult =
        withContext(Dispatchers.Default) { AiTools.execute(name, arguments, context(callId)) }.also { r ->
            if (r is AiToolResult.Read) turn.recordRead(name, r.items)
        }

    /**
     * Prepares one write and routes it. [batch] is true when the same model response holds more than one write.
     * [argumentsError] is set when the arguments could not be parsed; nothing is prepared then.
     */
    suspend fun write(name: String, arguments: JSONObject, callId: String, batch: Boolean, argumentsError: String? = null): Routed {
        val tool = AiTools.find(name) ?: return Routed("Strumento sconosciuto: $name.", true)
        if (!tool.writes) return Routed("Risultato non valido.", true)
        if (argumentsError != null) return Routed(argumentsError, true)
        // Category batches are not capped: one call carries hundreds of assignments and the card shows a single count.
        if (name != CATEGORY_TOOL && turn.writesProposed >= maxWrites) return Routed("Limite di $maxWrites scritture per richiesta raggiunto: non ne propongo altre.", true)
        if (!seenWrites.add(name + canonicalJson(arguments))) {
            return Routed("Operazione identica già proposta in questa richiesta: non la ripeto. Se è davvero un secondo movimento, l'utente lo inserisce a mano.", true)
        }
        val r = withContext(Dispatchers.Default) { AiTools.execute(name, arguments, context(callId)) }
        return when (r) {
            is AiToolResult.Failure -> Routed(r.message, true)
            is AiToolResult.Read -> Routed("Risultato non valido.", true)
            is AiToolResult.Proposal -> route(r, batch)
        }
    }

    private suspend fun route(r: AiToolResult.Proposal, batch: Boolean): Routed {
        if (r.change.kind != AiChangeKind.SET_IMPORT_CATEGORY) turn.writesProposed++
        val reasons = ArrayList<String>()
        val tier3 = AiPolicy.isTier3(r.change.kind)
        if (tier3) reasons += "regola o parametro: conferma riga per riga"
        else if (AiPolicy.requiresTap(r.change.kind) || extraTap(r.change.kind)) reasons += "tipo di modifica che richiede conferma"
        if (turn.tainted) reasons += turn.taintReason ?: AiTurnState.READ_REASON
        val categoryBatch = r.change.kind == AiChangeKind.SET_IMPORT_CATEGORY
        // Category batches share one card: more than one call in a request is not a reason for a second confirmation.
        if (!categoryBatch && !tier3 && (batch || turn.writesProposed > 1)) reasons += "più modifiche nella stessa richiesta"
        val change = r.change.copy(
            // A category batch lists its own movements in the diff; the third-party rows are not repeated on its card.
            sourceRows = if (categoryBatch) emptyList() else (r.change.sourceRows + if (turn.tainted) turn.externalRows else emptySet()).distinct().take(20),
            reviewNote = reasons.joinToString("; ").ifEmpty { null },
        )
        if (reasons.isNotEmpty()) {
            pending += change
            onPending(change)
            return Routed("In attesa di conferma dell'utente (non ancora registrata): ${r.modelNote}. Dillo all'utente e non ripetere la chiamata.", false)
        }
        return when (val committed = AiCommits.run(commit, retryDelaysMs) { change.applyTo(it) }) {
            is AiCommitResult.Done -> {
                current = committed.archive
                applied += change
                onApplied(change)
                Routed("Registrato: ${r.modelNote}. L'utente può annullare dalla scheda.", false)
            }
            is AiCommitResult.Rejected -> {
                failures += AiFailure(change.summary, committed.message)
                Routed("Modifica non applicata: ${committed.message}", true)
            }
            is AiCommitResult.Failed -> {
                queue.add(change, committed.message)
                queued += change
                onQueued(change)
                failures += AiFailure(change.summary, committed.message + " La modifica è in coda e verrà ritentata.")
                Routed("Salvataggio non riuscito al momento: la modifica (${r.modelNote}) è in coda e verrà ritentata. Dillo all'utente e non ripetere la chiamata.", true)
            }
        }
    }
}

/**
 * The automatic categorization after a sync (slice 1b, [AutoCategorizeRun]): the one write that skips the tap, so it is
 * narrow by construction. It only runs [CATEGORY_TOOL]; only for movements in [allowed] (the batch that was sent); and only
 * for those still without any category at commit time, so neither the movements' third-party text nor anything else in the
 * model's reply can reach another record. Tier 1 ("suggerita", AGENT origin), one commit, no card; [Result.change] undoes it.
 */
internal object AiAutoCategoryWrite {
    class Result(val change: AiChange?, val count: Int, val outcome: AiCommitResult?)

    /** The tool's own per-call limit; larger runs are merged into one change. */
    private const val PER_CALL = 500

    /** [assignments]: movement id to category name (created as a spending category when missing). */
    suspend fun commit(
        assignments: Map<String, String>,
        allowed: Set<String>,
        commit: suspend ((Archive) -> Archive) -> Boolean,
        sourceLabel: String,
        clock: () -> Instant,
        retryDelaysMs: List<Long> = AiCommits.DEFAULT_RETRY_DELAYS_MS,
    ): Result {
        if (AiPolicy.requiresTap(AiChangeKind.SET_IMPORT_CATEGORY)) return Result(null, 0, null)
        // A payment method is never a category; the tool refuses it too, but one refused name must not sink the whole run.
        val wanted = assignments.filterKeys { it in allowed }.filterValues { !PaymentMethodNames.isMethodLike(it) }
        if (wanted.isEmpty()) return Result(null, 0, null)
        var change: AiChange? = null
        var count = 0
        val outcome = AiCommits.run(commit, retryDelaysMs) { latest ->
            // Installment parts take their purchase's category: never categorized from their own text.
            val parts = latest.installmentPaymentIds()
            val open = latest.externalMovements.filter { it.id in wanted && it.id !in parts && it.categoryId == null && it.categorySource == null }.map { it.id }.toSet()
            change = null
            count = 0
            if (open.isEmpty()) latest else {
                val stamp = clock().toEpochMilli()
                val prepared = wanted.filterKeys { it in open }.entries.chunked(PER_CALL).mapIndexed { i, part ->
                    val args = JSONObject().put("assegnazioni", JSONArray().apply {
                        part.forEach { (id, name) -> put(JSONObject().put("rif", movementRef(id)).put("categoria", name)) }
                    })
                    when (val r = AiTools.execute(CATEGORY_TOOL, args, AiToolContext(latest, sourceLabel, "auto-$stamp-$i", AiTurnState(), clock, true))) {
                        is AiToolResult.Proposal -> r.change.also { require(it.kind == AiChangeKind.SET_IMPORT_CATEGORY) }
                        is AiToolResult.Failure -> throw IllegalStateException(r.message)
                        is AiToolResult.Read -> throw IllegalStateException("Risultato non valido.")
                    }
                }.reduce { a, b -> a.mergedWith(b) }
                change = prepared
                count = open.size
                prepared.applyTo(latest)
            }
        }
        return Result(change, count, outcome)
    }
}
