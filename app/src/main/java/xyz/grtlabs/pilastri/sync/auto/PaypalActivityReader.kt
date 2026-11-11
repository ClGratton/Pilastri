package xyz.grtlabs.pilastri.sync.auto

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import xyz.grtlabs.pilastri.data.Evidence
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.PlanReview

/** CSS selectors of PayPal's activity list, recorded 2026-10-31 on the user's signed-in session (Italian locale). */
data class PaypalActivitySelectors(
    val row: String = "[data-testid=trans_overview_root_container]",
    /** Where a row's fields are looked up when they are not inside [row]. */
    val rowScope: String = "[data-testid=activity-tile]",
    val header: String = "[data-testid=activity_list_header_view]",
    val counterparty: String = "[data-testid=counterparty_name]",
    val amount: String = "[data-testid=transaction_amount]",
    val description: String = "[data-testid=transaction_description]",
    val extra: String = "[data-testid=second_row_description_right]",
    /**
     * The list container itself, when known. UNVERIFIED (null): the container is then the closest element that holds every
     * activity tile and section header ([PaypalActivityReader.script]).
     */
    val list: String? = null,
    /** PayPal's own empty-list element, when recorded; looked up only inside the list container. UNVERIFIED (null). */
    val emptyState: String? = null,
)

enum class PaypalRowType { PAYMENT, HOLD, PLAN_IN_3, PLAN_LONG, REFUND, FUNDING, WITHDRAWAL, FEE, BONUS, UNKNOWN }

/** Words of the list, compared after [LoginMatcher.norm]. "Seen" values were recorded; the others are UNVERIFIED guesses. */
data class PaypalActivityLabels(
    val pendingHeaders: List<String> = listOf("In sospeso"),
    val completedHeaders: List<String> = listOf("Completato", "Completate", "Questa settimana", "Settimana scorsa"),
    val months: Map<String, Int> = mapOf(
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6,
        "lug" to 7, "ago" to 8, "set" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
        // English abbreviations, in case the account's language changes (none collides with an Italian one).
        "jan" to 1, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "dec" to 12,
    ),
    /** Exact type labels. Seen: Pagamento, Pagamento automatico, Autorizzazione, Paga in 3 rate, Paga in 6, 12 o 24 rate. */
    val types: Map<String, PaypalRowType> = mapOf(
        "Pagamento" to PaypalRowType.PAYMENT,
        "Pagamento automatico" to PaypalRowType.PAYMENT,
        "Autorizzazione" to PaypalRowType.HOLD,
        "Paga in 3 rate" to PaypalRowType.PLAN_IN_3,
        "Paga in 6, 12 o 24 rate" to PaypalRowType.PLAN_LONG,
        "Rimborso" to PaypalRowType.REFUND,
        "Denaro ricevuto" to PaypalRowType.PAYMENT,
        "Trasferimento dalla banca" to PaypalRowType.FUNDING,
        "Trasferimento sul conto bancario" to PaypalRowType.WITHDRAWAL,
    ),
    /**
     * Text of an empty list for the chosen dates. UNVERIFIED. Searched only inside the list container (the parent of an activity
     * tile or of a section header), never in the whole page, where a search box or hidden text may hold the same words.
     */
    val emptyPhrases: List<String> = listOf("nessuna attivita", "non ci sono attivita", "nessun risultato", "nessuna transazione", "no activity", "no results"),
    /** Controls that would load more rows. UNVERIFIED (none seen for 90 days). */
    val morePhrases: List<String> = listOf("mostra altro", "mostra altre", "carica altro", "carica altre", "vedi altro", "load more", "show more"),
)

/**
 * PayPal's activity list as recorded on 2026-10-31: there is no JSON list call; the page is server-rendered and lists the whole
 * chosen period (32 rows for 90 days, no "load more" seen). Never automate `/myaccount/money` (it triggers reCAPTCHA Enterprise).
 */
data class PaypalActivitySpec(
    val listUrl: String = "https://www.paypal.com/myaccount/activities/?free_text_search=&start_date={from}&end_date={to}" +
        "&type=&status=&currency=&filter_id=&issuance_product_name=&asset_names=&asset_symbols=",
    val windowDays: Int = 90,
    val keepAliveUrl: String = "https://www.paypal.com/myaccount/activities/extendSession",
    val selectors: PaypalActivitySelectors = PaypalActivitySelectors(),
    val labels: PaypalActivityLabels = PaypalActivityLabels(),
    /** How long to wait for the list of one window to load and settle, and the polling step. */
    val waitMs: Long = 20_000,
    val pollMs: Long = 500,
    /** Consecutive polls that must show the same list (same row count, or the empty state) before a window is accepted. */
    val stablePolls: Int = 2,
    /** Pause between windows, to look like a person paging back. */
    val betweenWindowsMs: Long = 800,
    /** The installment plan pages read after the windows ([PaypalPlansReader]). */
    val plans: PaypalPlanSpec = PaypalPlanSpec(),
    /**
     * Loading the rest of a window's list (2026-11-01): after the list settles, the page is scrolled to its end and a visible "load
     * more" control is pressed, one step each [loadStepMs], until [loadIdleSteps] steps in a row add no row or [maxLoadSteps] is
     * reached. A list still growing at the bound is not complete.
     */
    val maxLoadSteps: Int = 40,
    val loadStepMs: Long = 700,
    val loadIdleSteps: Int = 2,
    /**
     * Rows at which a window is suspected of being cut by the page (the Fold showed exactly 30 rows for two 90-day windows). A window
     * with at least this many rows that did not grow when scrolled is read again as two halves (newest first) until each half holds fewer, or
     * [maxSplits] extra loads were spent, or a half is a single day; what still cannot be shown in full claims no coverage past its
     * oldest row read.
     */
    val pageCap: Int = 30,
    val maxSplits: Int = 8,
) {
    init { require(windowDays in 1..366 && stablePolls >= 1 && maxLoadSteps >= 0 && loadIdleSteps >= 1 && pageCap >= 2 && maxSplits >= 0 && listUrl.startsWith("https://www.paypal.com/") && "{from}" in listUrl && "{to}" in listUrl) }

    fun urlFor(window: SyncRange): String = listUrl.replace("{from}", window.from.toString()).replace("{to}", window.to.toString())
}

/**
 * Reads PayPal's activity list in the background WebView that holds the session (never the visible page): loads the list for
 * windows of at most [PaypalActivitySpec.windowDays] days (newest first), waits until the window's list has loaded and settled,
 * reads it with [script] and maps it with [PaypalActivityMapper]. A list page that redirects to the sign-in page means signed out.
 *
 * A window counts only when the document has loaded and, for [PaypalActivitySpec.stablePolls] polls in a row, either shows the
 * same number of rows or shows PayPal's empty state inside the list container. A window that never settles is not "no
 * movements": the first one fails the run, a later one ends the read there (no coverage is claimed for it). Rows that do not
 * parse make the run fail when they are all a window has ([PaypalActivityMapper.verdict]).
 *
 * [log] gets one line per window with counts only (debug builds: [SyncLog]); never names, amounts or dates.
 */
class PaypalActivityReader(
    private val spec: PaypalActivitySpec = RecordedSpec.PAYPAL,
    private val now: () -> Instant = Instant::now,
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = SyncLog::d,
) : SessionFetcher {
    override val site: ProviderSite = ProviderSites.PAYPAL
    private val plansReader = PaypalPlansReader(spec.plans, site, now, today, log)
    override val configured: Boolean get() = true
    override val needsPage: Boolean get() = true
    override val navigates: Boolean get() = true

    override suspend fun fetch(range: SyncRange, page: PageAccess?, mayNavigate: Boolean): FetchOutcome {
        if (page == null || !mayNavigate) return FetchOutcome.NotReady
        val queue = ArrayDeque(range.windows(spec.windowDays))
        var total = queue.size
        var loads = 0
        var splits = 0
        val reads = ArrayList<PaypalActivityMapper.WindowRead>()
        var stoppedEarly = false
        while (queue.isNotEmpty()) {
            val window = queue.removeFirst()
            val tag = "paypal window ${loads + 1}/$total"
            if (loads > 0) delay(spec.betweenWindowsMs)
            loads++
            val landed = page.load(spec.urlFor(window)) ?: return FetchOutcome.Failed("Impossibile aprire le attività di PayPal. Controlla la connessione.")
            gate(landed)?.let { log("$tag: ${it.javaClass.simpleName}"); return it }
            val settled = when (val w = awaitList(page)) {
                is Wait.Gate -> { log("$tag: ${w.outcome.javaClass.simpleName}"); return w.outcome }
                is Wait.Ready -> w.snapshot
                Wait.Timeout -> null
            }
            if (settled == null) {
                log("$tag: list not loaded or not recognized (timeout)")
                if (loads == 1) return FetchOutcome.Failed(PaypalActivityMapper.NOT_LOADED)
                stoppedEarly = true
                break
            }
            val loaded = loadAll(page, settled)
            // A list that grew when scrolled is trusted to end where it stopped; one that sits at the page size and does not grow may be cut by the page.
            val atCap = loaded.grew == 0 && loaded.snapshot.rows.size >= spec.pageCap
            val halves = if (atCap && splits < spec.maxSplits) PaypalActivityMapper.halves(window) else null
            val snapshot = if (halves == null && (atCap || !loaded.exhausted)) loaded.snapshot.withMore() else loaded.snapshot
            val read = PaypalActivityMapper.read(snapshot, window, spec.labels, today())
            log("$tag: rows=${read.rows} valid=${read.valid} invalid=${read.invalid} pending=${read.pending} emptyConfirmed=${read.emptyConfirmed} more=${read.more}" +
                (if (snapshot.outside > 0) " outside=${snapshot.outside}" else "") +
                (if (loaded.grew > 0) " loaded=+${loaded.grew}" else "") +
                (if (!loaded.exhausted) " loadBound" else "") +
                (if (halves != null) " split" else if (atCap) " capped" else ""))
            if (halves != null) {
                splits++; total++
                queue.addFirst(halves.second); queue.addFirst(halves.first)
                continue
            }
            reads += read
            if (read.allInvalid) break
        }
        PaypalActivityMapper.verdict(reads)?.let { log("paypal read: failed (${reads.size} windows)"); return FetchOutcome.Failed(it) }
        val batch = PaypalActivityMapper.batch(reads, now(), stoppedEarly)
        return FetchOutcome.Fetched(withPlanPages(batch, reads, range, page))
    }

    /** What loading the rest of one window's list gave. [exhausted]: the list stopped growing (false: the step bound was reached while it still grew). */
    private class Loaded(val snapshot: PaypalActivityMapper.Snapshot, val grew: Int, val exhausted: Boolean)

    /**
     * Scrolls the settled list to its end and presses a visible "load more" control, step by step, until it stops growing. The
     * snapshot with the most rows wins (a list that drops rows it scrolled past is not trusted to shrink the read). A "load more"
     * control still shown when growth stops leaves [PaypalActivityMapper.Snapshot.more] true, so the window claims no coverage.
     */
    private suspend fun loadAll(page: PageAccess, first: PaypalActivityMapper.Snapshot): Loaded {
        var best = first
        var idle = 0
        var steps = 0
        while (steps < spec.maxLoadSteps && idle < spec.loadIdleSteps) {
            steps++
            page.evaluate(loadMoreScript(spec))
            delay(spec.loadStepMs)
            val s = PaypalActivityMapper.snapshot(page.evaluate(script(spec)))
            if (s != null && s.ready && s.rows.size > best.rows.size) { best = s; idle = 0 }
            else { idle++; if (s != null && s.ready && s.rows.size == best.rows.size) best = s }
        }
        return Loaded(best, best.rows.size - first.rows.size, exhausted = idle >= spec.loadIdleSteps)
    }

    /**
     * Visits the plan page of every plan the windows found (its row's link) and of every unfinished plan already in the archive
     * ([SyncRange.plans]), in the same hidden session, and puts what the pages state into the batch: the page's plan replaces the
     * row-made plan with the same id, and a plan only the archive knew is added so the merge refreshes it. A page that cannot be
     * read adds nothing (the plan stays as it was). The activity data above is valid whatever happens here.
     */
    private suspend fun withPlanPages(batch: ImportBatch, reads: List<PaypalActivityMapper.WindowRead>, range: SyncRange, page: PageAccess): ImportBatch {
        val seeds = reads.flatMap { it.plans }.distinctBy { it.key }
        val fromRows = seeds.mapNotNull { s -> s.link.takeIf { it.isNotBlank() }?.let { PlanTarget("paypal-web:${s.key}", it, s) } }
        val targets = fromRows + range.plans.map { PlanTarget(it.planId, it.link) }
        if (seeds.isNotEmpty() || range.plans.isNotEmpty()) {
            // Counts and masked path shapes only: why a plan page was or was not read.
            log("paypal plans: rowPlans=${seeds.size} withLink=${fromRows.size} archive=${range.plans.size}" +
                (if (targets.isNotEmpty()) " links=${targets.map { linkShape(it.link) }.distinct().take(3)}" else ""))
        }
        if (targets.isEmpty()) return batch
        val result = plansReader.read(page, targets)
        if (result.failed > 0) log("paypal plans: ${result.failed} of ${result.targets} pages not read")
        val warning = if (result.failed > 0) listOf(if (result.failed == 1) "1 pagina di piano a rate PayPal non letta." else "${result.failed} pagine di piani a rate PayPal non lette.") else emptyList()
        if (result.plans.isEmpty()) return batch.copy(warnings = batch.warnings + warning)
        val byId = result.plans.associateBy { it.id }
        // The page states the total, the count and the parts; the row keeps what only it knows (lender, merchant, purchase date, its evidence).
        val rowMade = batch.installmentPlans.map { row ->
            byId[row.id]?.let { page ->
                page.copy(lender = row.lender, merchant = row.merchant, purchaseDate = row.purchaseDate, totalCents = page.totalCents ?: row.totalCents,
                    count = page.count ?: row.count, evidence = row.evidence + page.evidence)
            } ?: row
        }
        val archiveOnly = result.plans.filter { p -> batch.installmentPlans.none { it.id == p.id } }
        return batch.copy(installmentPlans = rowMade + archiveOnly, warnings = batch.warnings + warning)
    }

    /** A plan link as a masked shape for the debug log: path segments holding a digit or a long token become ":id", query values are dropped. */
    internal fun linkShape(link: String): String {
        val path = link.substringBefore('#').substringBefore('?').removePrefix("https://www.paypal.com")
        val query = link.substringBefore('#').substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') }.sorted()
        val masked = path.split('/').joinToString("/") { seg -> if (seg.any { it.isDigit() } || seg.length >= 18) ":id" else seg }
        return masked.take(80) + if (query.isNotEmpty()) "?" + query.joinToString("&").take(60) else ""
    }

    private sealed interface Wait {
        class Ready(val snapshot: PaypalActivityMapper.Snapshot) : Wait
        class Gate(val outcome: FetchOutcome) : Wait
        data object Timeout : Wait
    }

    /** Polls the page until its list has settled ([PaypalActivityMapper.Snapshot.listState] the same for enough polls). */
    private suspend fun awaitList(page: PageAccess): Wait {
        val deadline = System.nanoTime() + spec.waitMs * 1_000_000
        var last: String? = null
        var streak = 0
        while (true) {
            gate(page.currentUrl())?.let { return Wait.Gate(it) }
            val s = PaypalActivityMapper.snapshot(page.evaluate(script(spec)))
            val state = s?.listState
            when {
                state == null -> { last = null; streak = 0 }
                state == last -> streak++
                else -> { last = state; streak = 1 }
            }
            if (s != null && streak >= spec.stablePolls) return Wait.Ready(s)
            if (System.nanoTime() > deadline) return Wait.Timeout
            delay(spec.pollMs)
        }
    }

    private fun gate(url: String?): FetchOutcome? = when (site.classify(url)) {
        SitePage.LOGIN, SitePage.SCA -> FetchOutcome.SignedOut
        SitePage.CHALLENGE -> FetchOutcome.Challenge
        SitePage.FOREIGN -> FetchOutcome.Failed("PayPal ha aperto una pagina esterna.")
        else -> null
    }

    companion object {
        /**
         * Scrolls the page and the list's scrollable ancestors to their end, then presses ONE visible control whose text is a
         * "load more" phrase (a button or role=button, or a link with no destination), next to the list. Evaluates to a JSON string
         * `{scrolled,pressed}` that the reader ignores. It reads no value and goes nowhere: a link with a destination is never followed.
         */
        fun loadMoreScript(spec: PaypalActivitySpec = RecordedSpec.PAYPAL): String {
            val s = spec.selectors
            val sel = JSONObject().put("row", s.row).put("list", s.list)
            val more = JSONArray(spec.labels.morePhrases.map(LoginMatcher::norm))
            return """
(function (S, MORE) {
  function norm(x) {
    x = String(x == null ? '' : x);
    try { x = x.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
    return x.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  }
  function has(hay, list) { var h = ' ' + hay + ' '; for (var i = 0; i < list.length; i++) if (list[i] && h.indexOf(' ' + list[i] + ' ') >= 0) return true; return false; }
  var first = S.list ? document.querySelector(S.list) : document.querySelector(S.row);
  for (var n = first; n; n = n.parentElement) {
    if (n.scrollHeight > n.clientHeight + 4 && /(auto|scroll)/.test(window.getComputedStyle(n).overflowY)) n.scrollTop = n.scrollHeight;
  }
  var se = document.scrollingElement || document.documentElement;
  window.scrollTo(0, se.scrollHeight);
  var pressed = false, scope = first ? (first.parentElement && first.parentElement.parentElement) || first.parentElement || document.body : null;
  var ctl = scope ? scope.querySelectorAll('button, a, [role="button"]') : [];
  for (var j = 0; j < ctl.length && j < 400; j++) {
    var c = ctl[j], href = c.tagName === 'A' ? String(c.getAttribute('href') || '') : '';
    if (c.tagName === 'A' && href && href.charAt(0) !== '#' && href.indexOf('javascript') !== 0) continue;
    if (c.disabled || !c.offsetParent) continue;
    if (has(norm(c.innerText || c.textContent), MORE)) { c.click(); pressed = true; break; }
  }
  return JSON.stringify({ scrolled: true, pressed: pressed });
})($sel, $more)
""".trimIndent()
        }

        /**
         * The read script: evaluates to a JSON string `{rows:[{h,n,a,d,x}],headers:[…],more,empty,ready,outside}`. Reads text only.
         *
         * Everything is read inside the LIST CONTAINER: [PaypalActivitySelectors.list] when known, else the closest element that
         * holds every activity tile and section header. Rows elsewhere on the page are not read (`outside` counts them, for the
         * log). `empty`: no rows, and PayPal's empty state inside that container ([PaypalActivitySelectors.emptyState], or one of
         * the empty-state phrases in its text); never the whole page, and never without a container. `ready`:
         * `document.readyState === 'complete'`.
         */
        fun script(spec: PaypalActivitySpec = RecordedSpec.PAYPAL): String {
            val s = spec.selectors
            val sel = JSONObject().put("row", s.row).put("scope", s.rowScope).put("header", s.header).put("n", s.counterparty)
                .put("a", s.amount).put("d", s.description).put("x", s.extra).put("list", s.list).put("empty", s.emptyState)
            val more = JSONArray(spec.labels.morePhrases.map(LoginMatcher::norm))
            val empty = JSONArray(spec.labels.emptyPhrases.map(LoginMatcher::norm))
            return """
(function (S, MORE, EMPTY) {
  function norm(s) {
    s = String(s == null ? '' : s);
    try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
    return s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  }
  function has(hay, list) { var h = ' ' + hay + ' '; for (var i = 0; i < list.length; i++) if (list[i] && h.indexOf(' ' + list[i] + ' ') >= 0) return true; return false; }
  function txt(n) { return n ? String(n.innerText || n.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 300) : ''; }
  function field(el, q) {
    var n = el.querySelector(q);
    if (!n && el.closest) { var sc = el.closest(S.scope); if (sc) n = sc.querySelector(q); }
    return txt(n);
  }
  var root = S.list ? document.querySelector(S.list) : null;
  if (!root) {
    var anchors = document.querySelectorAll(S.scope + ',' + S.header);
    if (anchors.length) {
      root = anchors[0].parentElement;
      for (var k = 1; root && k < anchors.length && k < 5000; k++) while (root && !root.contains(anchors[k])) root = root.parentElement;
    }
  }
  var rows = [], headers = [], h = '';
  if (root) {
    var nodes = root.querySelectorAll(S.header + ',' + S.row);
    for (var i = 0; i < nodes.length && i < 5000; i++) {
      var el = nodes[i];
      if (el.matches(S.header)) { h = txt(el).slice(0, 80); headers.push(h); continue; }
      var lk = el.closest ? el.closest('a[href]') : null; if (!lk) lk = el.querySelector('a[href]');
      rows.push({ h: h, n: field(el, S.n), a: field(el, S.a), d: field(el, S.d), x: field(el, S.x), l: lk ? String(lk.getAttribute('href') || '').slice(0, 400) : '' });
    }
  }
  var outside = Math.max(0, document.querySelectorAll(S.row).length - rows.length), empty = false;
  if (root && rows.length === 0) empty = (S.empty ? !!root.querySelector(S.empty) : false) || has(norm(String(root.innerText || '').slice(0, 20000)), EMPTY);
  var more = false, ctl = root ? root.querySelectorAll('button, a, [role="button"]') : [];
  for (var j = 0; j < ctl.length && j < 400; j++) if (has(norm(txt(ctl[j])), MORE)) { more = true; break; }
  if (!more && root && root.parentElement) {
    ctl = root.parentElement.querySelectorAll('button, a, [role="button"]');
    for (var m = 0; m < ctl.length && m < 400; m++) if (has(norm(txt(ctl[m])), MORE)) { more = true; break; }
  }
  return JSON.stringify({ v: 3, rows: rows, headers: headers, more: more, empty: empty, ready: document.readyState === 'complete', outside: outside });
})($sel, $more, $empty)
""".trimIndent()
        }
    }
}

/** Pure mapping of read rows to the import shapes, unit-tested on synthetic JSON. */
object PaypalActivityMapper {
    const val PI3_LENDER = "PayPal Paga in 3 rate"
    const val LONG_LENDER = "PayPal pagamento a rate"
    private const val ACCOUNT_LABEL = "PayPal"

    /** The run failed because the rows did not parse, or nothing was read and no empty list was confirmed. */
    const val FORMAT_FAILED = "Lettura di PayPal non riuscita: formato da aggiornare"
    /** The first window's list never loaded and settled. */
    const val NOT_LOADED = "Lettura di PayPal non riuscita: elenco non caricato o formato da aggiornare"

    /** [link]: the row's own link (its detail page), as written in the page; kept only for the rows that become plans. */
    class Row(val header: String, val counterparty: String, val amount: String, val description: String, val extra: String, val link: String = "")

    /** [empty]: the provider's empty state inside the list container (see [PaypalActivityReader.script]). [ready]: the document has loaded. */
    /** [outside]: rows on the page but outside the list container, not read (shape signal for the log). */
    class Snapshot(val rows: List<Row>, val headers: List<String>, val more: Boolean, val empty: Boolean, val ready: Boolean = true, val outside: Int = 0) {
        /**
         * What the list shows, for the stability check: the row count, or the confirmed empty state; null while the document is
         * loading or neither shows (headers alone, a skeleton, a page that is not the list).
         */
        val listState: String? get() = when {
            !ready -> null
            rows.isNotEmpty() -> "rows:${rows.size}"
            empty -> "empty"
            else -> null
        }

        /** The same list, marked as possibly holding rows that were not shown ([more]): the window then claims coverage only from its oldest row read. */
        fun withMore(): Snapshot = if (more) this else Snapshot(rows, headers, true, empty, ready, outside)
    }

    /** [result] as evaluateJavascript returns it (a JSON string holding the JSON), or the JSON itself. */
    fun snapshot(result: String?): Snapshot? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val inner = JSONTokener(result).nextValue().let { it as? String ?: result }
            val o = JSONObject(inner)
            val rows = o.optJSONArray("rows") ?: JSONArray()
            Snapshot(
                rows = (0 until minOf(rows.length(), 5000)).mapNotNull { i ->
                    rows.optJSONObject(i)?.let { Row(it.optString("h"), it.optString("n"), it.optString("a"), it.optString("d"), it.optString("x"), it.optString("l")) }
                },
                headers = (o.optJSONArray("headers") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
                more = o.optBoolean("more"),
                empty = o.optBoolean("empty"),
                ready = o.optBoolean("ready"),
                outside = o.optInt("outside").coerceAtLeast(0),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One accepted window. [rows]: every row on the list; [valid]: rows outside "In sospeso" whose date, amount and type parsed
     * (holds, plans and unknown kinds included); [invalid]: rows that did not parse; [pending]: rows under a pending header, not
     * parsed. [emptyConfirmed]: no rows and the empty state inside the list container.
     */
    class WindowRead(
        val window: SyncRange,
        val movements: List<ImportedMovement>,
        val plans: List<PlanSeed>,
        val pending: Int,
        val holds: Int,
        val unknown: Int,
        val invalid: Int,
        val assumedCurrency: Int,
        val more: Boolean,
        val earliest: LocalDate?,
        val rows: Int = 0,
        val valid: Int = 0,
        val emptyConfirmed: Boolean = false,
    ) {
        /** Rows were shown and none of them parsed: the page's format changed. */
        val allInvalid: Boolean get() = invalid > 0 && valid == 0
        /** Every movement of the window was read: no "load more" and no unreadable row. Only such windows give coverage. */
        val complete: Boolean get() = !more && invalid == 0
    }

    class PlanSeed(val key: String, val type: PaypalRowType, val date: LocalDate, val merchant: String, val cents: Long, val currency: String, val link: String = "")

    /** A row's date and PayPal type label, read from its description. */
    internal class Described(val date: LocalDate, val typeLabel: String)

    private const val LETTERS = "[A-Za-z\u00C0-\u017F]"
    /** Every space PayPal may print (NBSP, narrow NBSP, figure and thin spaces) becomes a plain space before matching. */
    private val SPACES = Regex("[\\s\u00A0\u2000-\u200A\u202F\u205F\u3000]+")
    /** A day and a month word anywhere in the text: "29 ott", "1 settembre", "29 ott. 2025", "29 ott 2025". */
    private val DAY_MONTH = Regex("(?<![0-9])(\\d{1,2})\\s*($LETTERS{3,12})\\.?(?:\\s+(\\d{4}))?(?![0-9])")
    /** "29/10", "29/10/2025", "29.10.2025" (a dotted form needs its year: "10.12" may be a time). */
    private val NUMERIC = Regex("(?<![0-9.,:/])(\\d{1,2})(?:/(\\d{1,2})(?:/(\\d{2,4}))?|\\.(\\d{1,2})\\.(\\d{2,4}))(?![0-9])")
    private val RELATIVE = Regex("(?<![A-Za-z])(oggi|ieri)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    /** Separators between a row's parts: "·", "•", "∙", "⋅", "|", or a dash with spaces around it. Never a comma ("Paga in 6, 12 o 24 rate"). */
    private val PARTS = Regex("\\s*(?:[\u00B7\u2022\u2219\u22C5|]|\\s[-\u2013\u2014]\\s)\\s*")
    private val EDGE = Regex("^[\\s.,;:\u00B7\u2022\u2219\u22C5|\\-\u2013\u2014]+|[\\s.,;:\u00B7\u2022\u2219\u22C5|\\-\u2013\u2014]+$")
    private val TIME = Regex("^(?:alle |ore |at )?\\d{1,2}[:.]\\d{2}(?: ?[ap]m)?$", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("^\\d{4}$")
    /** Words that only place the row in time ("ven", "venerdì", "Oggi"); never a type. Compared after [LoginMatcher.norm]. */
    private val TIME_WORDS = setOf(
        "lun", "mar", "mer", "gio", "ven", "sab", "dom", "lunedi", "martedi", "mercoledi", "giovedi", "venerdi", "sabato", "domenica",
        "oggi", "ieri", "mon", "tue", "wed", "thu", "fri", "sat", "sun", "today", "yesterday",
    )
    private val MONTH_HEADER = Regex("^([a-z]{3,12})\\s+(\\d{4})$")

    private class Found(val date: LocalDate, val before: String, val after: String)

    /**
     * The date and type of a row, TOLERANT of missing and extra parts: the date is searched anywhere in [text] (the row's
     * description), else in [extra] (its second line); the type is the first part after the date, else before it, else from the
     * other field, and may be blank (then [typeOf] gives UNKNOWN: never guessed). Extra parts (a time, a weekday, a funding
     * source after another "·") do not drop the row. Only a row with no recognizable date is unreadable (null). The year comes
     * from the row, its month [header] or [window].
     */
    internal fun describe(text: String, header: String, window: SyncRange, labels: PaypalActivityLabels, today: LocalDate, extra: String = ""): Described? {
        val d = text.replace(SPACES, " ").trim()
        val x = extra.replace(SPACES, " ").trim()
        val headerYear = MONTH_HEADER.find(LoginMatcher.norm(header))?.groupValues?.get(2)?.toIntOrNull()
        val inText = dateIn(d, headerYear, window, labels, today)
        val found = inText ?: dateIn(x, headerYear, window, labels, today) ?: return null
        val type = typeIn(found.after, labels) ?: typeIn(found.before, labels) ?: typeIn(if (inText != null) x else d, labels) ?: ""
        return Described(found.date, type)
    }

    private fun dateIn(s: String, headerYear: Int?, window: SyncRange, labels: PaypalActivityLabels, today: LocalDate): Found? {
        if (s.isEmpty()) return null
        for (m in DAY_MONTH.findAll(s)) {
            val key = LoginMatcher.norm(m.groupValues[2]).take(3)
            val month = labels.months.entries.firstOrNull { LoginMatcher.norm(it.key) == key }?.value ?: continue
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val date = dateOf(day, month, m.groupValues[3].toIntOrNull() ?: headerYear, window, today) ?: continue
            return Found(date, s.substring(0, m.range.first), s.substring(m.range.last + 1))
        }
        NUMERIC.find(s)?.let { m ->
            val g = m.groupValues
            val day = g[1].toIntOrNull()
            val month = (g[2].ifEmpty { g[4] }).toIntOrNull()
            val year = g[3].ifEmpty { g[5] }.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: headerYear
            if (day != null && month != null && month in 1..12) dateOf(day, month, year, window, today)?.let {
                return Found(it, s.substring(0, m.range.first), s.substring(m.range.last + 1))
            }
        }
        RELATIVE.find(s)?.let { m ->
            val date = if (m.groupValues[1].equals("oggi", ignoreCase = true)) today else today.minusDays(1)
            return Found(date, s.substring(0, m.range.first), s.substring(m.range.last + 1))
        }
        return null
    }

    /** The type among [s]'s parts: one that is an exact recorded label, else the first that is not a time, weekday or year. */
    private fun typeIn(s: String, labels: PaypalActivityLabels): String? {
        val parts = s.split(PARTS).map { it.replace(EDGE, "").trim() }
            .filter { it.isNotEmpty() && !TIME.matches(it) && !YEAR.matches(it) && LoginMatcher.norm(it) !in TIME_WORDS }
        if (parts.isEmpty()) return null
        val known = labels.types.keys.map(LoginMatcher::norm).toSet()
        return parts.firstOrNull { LoginMatcher.norm(it) in known } ?: parts.first()
    }

    private val HOLD_TEXT = Regex("\\b(autorizzazione|autorizzazioni|blocco|hold|authorization|svincolo|revoca)\\b")
    private val PLAN_TEXT = Regex("\\b(paga in \\d+|pay in \\d+|rate)\\b")
    private val REFUND_TEXT = Regex("\\b(rimborso|rimborsi|refund|storno|reversal|chargeback|restituzione|rettifica)\\b")
    private val FUNDING_TEXT = Regex("\\b(deposito|ricarica|aggiunta fondi|top up)\\b|trasferimento (bancario|da conto|da banca|dalla banca)")
    private val WITHDRAWAL_TEXT = Regex("\\b(prelievo|withdrawal)\\b|trasferimento (su|sul|verso) (il )?(conto )?banc")
    private val FEE_TEXT = Regex("\\b(commissione|commissioni|tariffa|fee|spese|canone)\\b")
    private val BONUS_TEXT = Regex("\\b(bonus|cashback|premio)\\b")
    private val PAYMENT_TEXT = Regex("\\b(pagamento|pagamenti|payment|acquisto|abbonamento|donazione|denaro inviato|denaro ricevuto|invio denaro)\\b")

    fun typeOf(label: String, labels: PaypalActivityLabels): PaypalRowType {
        val n = LoginMatcher.norm(label)
        labels.types.entries.firstOrNull { LoginMatcher.norm(it.key) == n }?.let { return it.value }
        return when {
            HOLD_TEXT.containsMatchIn(n) -> PaypalRowType.HOLD
            PLAN_TEXT.containsMatchIn(n) -> if (n.contains("in 3")) PaypalRowType.PLAN_IN_3 else PaypalRowType.PLAN_LONG
            REFUND_TEXT.containsMatchIn(n) -> PaypalRowType.REFUND
            FUNDING_TEXT.containsMatchIn(n) -> PaypalRowType.FUNDING
            WITHDRAWAL_TEXT.containsMatchIn(n) -> PaypalRowType.WITHDRAWAL
            FEE_TEXT.containsMatchIn(n) -> PaypalRowType.FEE
            BONUS_TEXT.containsMatchIn(n) -> PaypalRowType.BONUS
            PAYMENT_TEXT.containsMatchIn(n) -> PaypalRowType.PAYMENT
            else -> PaypalRowType.UNKNOWN
        }
    }

    /** Same rules as the CSV importer: holds are never spending, outflows of unknown or reversing kinds are not guessed. */
    private fun kindOf(type: PaypalRowType, cents: Long): ExternalKind? {
        val out = cents < 0
        return when (type) {
            PaypalRowType.PAYMENT -> if (out) ExternalKind.CARD_PAYMENT else ExternalKind.OTHER
            PaypalRowType.FEE -> if (out) ExternalKind.FEE else ExternalKind.OTHER
            PaypalRowType.FUNDING -> if (out) null else ExternalKind.DEPOSIT
            PaypalRowType.WITHDRAWAL -> if (out) ExternalKind.WITHDRAWAL else ExternalKind.OTHER
            PaypalRowType.BONUS -> if (out) null else ExternalKind.BONUS
            PaypalRowType.REFUND -> if (out) null else ExternalKind.OTHER
            PaypalRowType.UNKNOWN -> if (out) null else ExternalKind.OTHER
            PaypalRowType.HOLD, PaypalRowType.PLAN_IN_3, PaypalRowType.PLAN_LONG -> null
        }
    }

    /** [window] as its newer and older half (the older ends the day before the newer starts), or null when it is a single day. */
    fun halves(window: SyncRange): Pair<SyncRange, SyncRange>? {
        val days = window.days
        if (days < 2) return null
        val newerDays = (days + 1) / 2
        val newer = SyncRange(window.to.minusDays(newerDays - 1), window.to)
        return newer to SyncRange(window.from, newer.from.minusDays(1))
    }

    fun read(s: Snapshot, window: SyncRange, labels: PaypalActivityLabels, today: LocalDate): WindowRead {
        val pendingHeaders = labels.pendingHeaders.map(LoginMatcher::norm).toSet()
        val seen = HashMap<String, Int>()
        val movements = ArrayList<ImportedMovement>()
        val plans = ArrayList<PlanSeed>()
        var pending = 0; var holds = 0; var unknown = 0; var invalid = 0; var valid = 0; var assumed = 0
        var earliest: LocalDate? = null
        for (row in s.rows) {
            if (LoginMatcher.norm(row.header) in pendingHeaders) { pending++; continue }
            val described = describe(row.description, row.header, window, labels, today, row.extra)
            val cents = AmountText.parse(row.amount)?.let { runCatching { it.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact() }.getOrNull() }
            if (described == null || cents == null) { invalid++; continue }
            valid++
            val date = described.date
            val typeLabel = described.typeLabel
            earliest = if (earliest == null || date.isBefore(earliest)) date else earliest
            val currency = AmountText.currency(row.amount) ?: "EUR".also { assumed++ }
            val type = typeOf(typeLabel, labels)
            val counterparty = clean(row.counterparty)
            val key = "$date|${LoginMatcher.norm(counterparty)}|$cents|$currency|${LoginMatcher.norm(typeLabel)}"
            val n = seen.merge(key, 1, Int::plus)!!
            val id = sha256("$key|$n").take(32)
            when (type) {
                PaypalRowType.HOLD -> { holds++; continue }
                PaypalRowType.PLAN_IN_3, PaypalRowType.PLAN_LONG -> { plans += PlanSeed(id, type, date, counterparty, cents, currency, row.link); continue }
                else -> Unit
            }
            val kind = kindOf(type, cents) ?: run { unknown++; null } ?: continue
            val description = when (kind) {
                ExternalKind.CARD_PAYMENT -> counterparty.ifBlank { typeLabel }
                ExternalKind.DEPOSIT, ExternalKind.WITHDRAWAL, ExternalKind.FEE -> typeLabel.ifBlank { counterparty }
                else -> listOf(typeLabel, counterparty).filter { it.isNotBlank() }.joinToString(" · ")
            }.ifBlank { "Movimento PayPal" }.take(200)
            movements += ImportedMovement(
                externalId = "web:$id", date = date, signedCents = cents, currency = currency,
                description = description, kind = kind, rawType = typeLabel.take(80),
            )
        }
        return WindowRead(window, movements, plans, pending, holds, unknown, invalid, assumed, s.more, earliest,
            rows = s.rows.size, valid = valid, emptyConfirmed = s.rows.isEmpty() && s.empty)
    }

    /**
     * Why the read must fail instead of being saved, or null: a window whose rows all failed to parse, or a run with no row read
     * (valid or pending) and no empty list confirmed. A failed read claims no coverage and no freshness.
     */
    fun verdict(reads: List<WindowRead>): String? = when {
        reads.any { it.allInvalid } -> FORMAT_FAILED
        reads.sumOf { it.valid + it.pending } == 0 && reads.none { it.emptyConfirmed } -> FORMAT_FAILED
        else -> null
    }

    /** Day and month from the row; the year from the row or its month header, else the one that puts the date in the window. */
    internal fun dateOf(day: Int, month: Int, year: Int?, window: SyncRange, today: LocalDate): LocalDate? {
        fun at(y: Int) = try { LocalDate.of(y, month, day) } catch (_: DateTimeException) { null }
        if (year != null) return at(year)
        val lo = window.from.minusDays(3)
        val hi = window.to.plusDays(3)
        (window.to.year downTo window.from.year - 1).forEach { y -> at(y)?.let { if (!it.isBefore(lo) && !it.isAfter(hi)) return it } }
        // Outside the asked window: the latest such date that is not in the future.
        return at(today.year)?.let { if (it.isAfter(today)) at(today.year - 1) else it }
    }

    /**
     * The windows read (newest first) as one batch. Coverage is claimed only over the newest windows that were read completely
     * ([WindowRead.complete]); a window that may hold more rows than were shown is covered from its earliest row read; a window
     * with unreadable rows, or one never read ([stoppedEarly]), ends the claim. No complete window: no coverage at all.
     */
    fun batch(reads: List<WindowRead>, fetchedAt: Instant, stoppedEarly: Boolean): ImportBatch {
        val movements = reads.flatMap { it.movements }.distinctBy { it.externalId }.sortedByDescending { it.date }
        val plans = reads.flatMap { it.plans }.distinctBy { it.key }.map { plan(it, fetchedAt) }
        val complete = reads.takeWhile { it.complete }
        val partial = stoppedEarly || complete.size < reads.size
        val next = reads.getOrNull(complete.size)
        // A window that may hold more rows than shown is cut at its OLDEST end (the list is newest first): coverage reaches back to the
        // day after its oldest row read (that day's other rows may be among the hidden ones). Older than that nothing is claimed.
        // When that day is past the window's end (a single day, or the cut is on the window's newest day) nothing of this window is claimed.
        val cutFrom = next?.takeIf { it.more && it.invalid == 0 }?.let { n -> n.earliest?.plusDays(1)?.takeIf { !it.isAfter(n.window.to) } }
        val coverageFrom = cutFrom ?: complete.lastOrNull()?.window?.from
        val coverageTo = if (coverageFrom == null) null else reads.first().window.to
        fun n(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"
        val warnings = buildList {
            reads.sumOf { it.pending }.takeIf { it > 0 }?.let { add(n(it, "movimento in sospeso non importato.", "movimenti in sospeso non importati.")) }
            reads.sumOf { it.holds }.takeIf { it > 0 }?.let { add(n(it, "autorizzazione non conteggiata come spesa.", "autorizzazioni non conteggiate come spesa.")) }
            reads.sumOf { it.unknown }.takeIf { it > 0 }?.let { add(n(it, "uscita di tipo non riconosciuto non importata.", "uscite di tipo non riconosciuto non importate.")) }
            reads.sumOf { it.invalid }.takeIf { it > 0 }?.let { add(n(it, "riga non leggibile.", "righe non leggibili.")) }
            reads.sumOf { it.assumedCurrency }.takeIf { it > 0 }?.let { add(n(it, "importo senza valuta: considerato in euro.", "importi senza valuta: considerati in euro.")) }
            if (plans.isNotEmpty()) add(n(plans.size, "acquisto a rate registrato come piano, non come spesa.", "acquisti a rate registrati come piani, non come spesa."))
            if (partial) add(if (coverageFrom == null) "Elenco PayPal forse incompleto: nessun periodo dato per completo." else "Elenco PayPal forse incompleto: periodo coperto ridotto.")
        }
        return ImportBatch(
            provider = ImportProvider.PAYPAL,
            accountLabel = ACCOUNT_LABEL,
            fetchedAt = fetchedAt,
            coverageFrom = coverageFrom,
            coverageTo = coverageTo,
            movements = movements,
            warnings = warnings,
            installmentPlans = plans,
        )
    }

    /**
     * A Pay in 3 / Pay later purchase as a plan. The amount PayPal shows on the row is taken as the purchase total (PayPal's help: the
     * purchase shows at its full amount; not verified on a real plan row). Parts, due dates and amounts stay unknown until another
     * source (Gmail receipts) supplies them: missing is not zero.
     */
    private fun plan(seed: PlanSeed, fetchedAt: Instant): InstallmentPlan {
        val pi3 = seed.type == PaypalRowType.PLAN_IN_3
        val total = kotlin.math.abs(seed.cents).takeIf { it > 0 }
        val label = if (pi3) "Paga in 3 rate" else "Pagamento a rate"
        val summary = listOfNotNull(label, seed.merchant.takeIf { it.isNotBlank() }, total?.let { money(it, seed.currency) }).joinToString(" · ").take(200)
        return InstallmentPlan(
            id = "paypal-web:${seed.key}",
            lender = if (pi3) PI3_LENDER else LONG_LENDER,
            merchant = seed.merchant.takeIf { it.isNotBlank() }?.take(120),
            totalCents = total,
            currency = seed.currency,
            count = if (pi3) 3 else null,
            installments = emptyList(),
            purchaseDate = seed.date,
            source = InstallmentSource.PAYPAL,
            evidence = listOf(Evidence("paypal:web-${seed.key.take(24)}", seed.date, summary)),
            review = PlanReview.PENDING,
            extractedByAgent = false,
            updatedAt = fetchedAt.toString(),
        )
    }

    private fun money(cents: Long, currency: String) = "${cents / 100},${"%02d".format(cents % 100)} " + if (currency == "EUR") "€" else currency

    private val UNSAFE = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")
    private fun clean(s: String) = s.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim().take(200)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
