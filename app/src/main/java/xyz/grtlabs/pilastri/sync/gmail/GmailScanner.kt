package xyz.grtlabs.pilastri.sync.gmail

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.grtlabs.pilastri.ai.AiTransport
import xyz.grtlabs.pilastri.ai.AiTransportException
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentSync

/** Hard bounds of one scan: how many messages are listed and read, how fast, and how many go to the optional AI fallback. */
internal data class ScanLimits(
    val maxMessages: Int = 300,
    val pageSize: Int = 100,
    val maxPagesPerQuery: Int = 3,
    val concurrency: Int = 6,
    val aiMaxMessages: Int = 20,
)

/**
 * List, read, parse, assemble. Pure orchestration over a [MailSource]: no Android, no tokens, no archive. Messages are held in memory for
 * the duration of the scan and dropped; only the facts survive, as plans, payments and short evidence summaries.
 */
internal class GmailScanner(
    private val source: MailSource,
    private val ai: AiTransport? = null,
    private val aiModel: String? = null,
    private val limits: ScanLimits = ScanLimits(),
    private val now: () -> Instant = Instant::now,
) {
    private class Listed(val query: String, val ids: List<String>, val truncated: Boolean)

    private suspend fun list(since: LocalDate, until: LocalDate?): List<Listed> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Listed>()
        val queries = GmailQueries.build(since, until)
        for (query in queries) {
            if (seen.size >= limits.maxMessages) {
                // The cap was spent before this search ran: its coverage is nil, not complete.
                out += Listed(query, emptyList(), truncated = true)
                continue
            }
            val mine = ArrayList<String>()
            var token: String? = null
            var pages = 0
            var truncated = false
            do {
                val room = limits.maxMessages - seen.size
                if (room <= 0) { truncated = true; break }
                val page = source.list(query, token, minOf(limits.pageSize, room))
                for (id in page.ids) {
                    if (seen.size >= limits.maxMessages) { truncated = true; break }
                    if (seen.add(id)) mine += id
                }
                token = page.next
                pages++
                if (token != null && (pages >= limits.maxPagesPerQuery || seen.size >= limits.maxMessages)) truncated = true
            } while (token != null && !truncated)
            out += Listed(query, mine, truncated)
        }
        return out
    }

    private suspend fun fetchAll(ids: List<String>): List<MailMessage> = coroutineScope {
        ids.chunked(limits.concurrency.coerceAtLeast(1)).flatMap { chunk -> chunk.map { id -> async { source.fetch(id) } }.awaitAll() }.filterNotNull()
    }

    /**
     * Reads `since..until` (until defaults to [today]). Newest messages come first, so a window hitting the cap loses its oldest end.
     * [known] are the plans the archive already holds: mails of this window about them (a cancellation, a paid notice without a part number)
     * are resolved against them instead of an empty draft, and only the known plans a mail changed come back in `plans`.
     */
    suspend fun scan(since: LocalDate, today: LocalDate, until: LocalDate? = null, known: List<InstallmentPlan> = emptyList()): GmailResult.Found {
        val end = until?.coerceAtMost(today)
        val listed = list(since, end)
        val messages = fetchAll(listed.flatMap { it.ids }.distinct()).filter { it.date >= since && (end == null || it.date <= end) }
        val byId = messages.associateBy { it.id }
        val ordered = messages.sortedWith(compareBy({ it.date }, { it.id }))

        val facts = ArrayList<MailFact>()
        val unknown = ArrayList<MailMessage>()
        for (m in ordered) when (val parsed = InstallmentMailParser.parse(m)) {
            is Parsed.Facts -> facts += parsed.facts
            Parsed.Skip -> Unit
            Parsed.Unknown -> unknown += m
        }

        var aiTried = 0
        var aiRead = 0
        var aiFailed = 0
        if (ai != null && unknown.isNotEmpty()) {
            val model = aiModel ?: ai.provider.defaultModel
            for (m in unknown.asReversed().filter { GmailAiFallback.hasEnoughText(it) }.take(limits.aiMaxMessages)) {
                aiTried++
                try {
                    val found = GmailAiFallback.extract(ai, model, m, Lenders.generic(m.from))
                    if (found.isNotEmpty()) { facts += found; aiRead++ }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiTransportException) {
                    aiFailed++
                    // A bad key or an exhausted quota will fail every following call too: stop asking.
                    if (e.kind == AiTransportException.Kind.AUTH || e.kind == AiTransportException.Kind.RATE_LIMIT) break
                } catch (_: Exception) {
                    aiFailed++
                }
            }
        }

        val stamp = now().toString()
        val built = PlanAssembler.build(facts, stamp, known)

        // Coverage is the date from which every search was read to its end: a search stopped by the cap only reaches back to its oldest message read.
        val truncated = listed.filter { it.truncated }
        val coverageTo = end ?: today
        val coverageFrom = if (truncated.isEmpty()) since else truncated.maxOf { l -> l.ids.mapNotNull { byId[it]?.date }.minOrNull() ?: coverageTo }.coerceAtLeast(since)
        val result = buildString {
            append("Letti ${messages.size} messaggi")
            if (truncated.isNotEmpty()) append(" (limite di ${limits.maxMessages} raggiunto: copertura parziale dal $coverageFrom)")
            append("; ${built.plans.size} piani, ${built.payments.size} pagamenti")
            if (built.unmatched > 0) append("; ${built.unmatched} avvisi non associati a un piano")
            if (built.invalid > 0) append("; ${built.invalid} piani scartati perché incoerenti")
            if (aiTried > 0) append("; assistente: $aiRead/$aiTried messaggi di mittenti sconosciuti letti" + if (aiFailed > 0) ", $aiFailed non riusciti" else "")
            append('.')
        }.take(500)
        val sync = InstallmentSync(InstallmentSource.GMAIL, stamp, coverageFrom, coverageTo, result)
        return GmailResult.Found(built.plans, built.payments, sync, messages.size)
    }
}
