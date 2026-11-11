package xyz.grtlabs.pilastri.sync.auto

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import xyz.grtlabs.pilastri.data.InstallmentPlan

/** Where one plan page is read from and which plan it belongs to. */
class PlanTarget(
    /** The plan's id in the archive; for a plan made by an activity row, the id the activity mapper gave it. */
    val planId: String,
    /** The page's link: a path and query ("/myaccount/..."), or an https://www.paypal.com URL, as the row or the stored plan gave it. */
    val link: String,
    /** The row-made plan this page completes, when the target comes from the activity list (its merchant, purchase date and total stay as fallbacks). */
    val seed: PaypalActivityMapper.PlanSeed? = null,
)

/** What reading the plan pages gave; counts only, for the log. [stop]: the session ended or a check appeared, the remaining pages were not read. */
class PlanReadResult(
    val plans: List<InstallmentPlan>,
    val targets: Int,
    val failed: Int,
    val partsPaid: Int,
    val partsDue: Int,
    val stop: FetchOutcome? = null,
) {
    val read: Int get() = plans.size
}

/**
 * Reads PayPal's installment plan pages in the same hidden session as the activity list, one page at a time with the same human
 * pacing. A page that does not load, is not the plan page, or does not parse is skipped and counted: it adds no plan, so the plan
 * already in the archive is neither wiped nor downgraded, and no more is claimed than was read. Only a sign-in or a check page
 * stops the whole read (the activity list was already read by then and stays valid).
 */
class PaypalPlansReader(
    private val spec: PaypalPlanSpec = PaypalPlanSpec(),
    private val site: ProviderSite = ProviderSites.PAYPAL,
    private val now: () -> Instant = Instant::now,
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = SyncLog::d,
) {
    suspend fun read(page: PageAccess, targets: List<PlanTarget>): PlanReadResult {
        val picked = targets.distinctBy { normalize(it.link) ?: it.link }.take(spec.maxPlans)
        val plans = ArrayList<InstallmentPlan>()
        var failed = 0
        var stop: FetchOutcome? = null
        for ((index, target) in picked.withIndex()) {
            val tag = "paypal plan ${index + 1}/${picked.size}"
            val url = absolute(target.link)
            if (url == null) { failed++; log("$tag: link refused"); continue }
            if (index > 0) delay(spec.betweenPagesMs)
            val landed = page.load(url)
            if (landed == null) { failed++; log("$tag: page not loaded"); continue }
            val blocked = gate(landed)
            if (blocked != null) { stop = blocked; log("$tag: ${blocked.javaClass.simpleName}"); break }
            when (val w = await(page)) {
                is Wait.Gate -> { stop = w.outcome; log("$tag: ${w.outcome.javaClass.simpleName}"); break }
                Wait.Timeout -> { failed++; log("$tag: page not loaded or not recognized (timeout)"); logShape(tag, lastSnapshot); continue }
                is Wait.Ready -> {
                    val parsed = PaypalPlanMapper.parse(w.snapshot, spec.labels, today())
                    if (parsed == null) {
                        failed++
                        log("$tag: not recognized (testids=${w.snapshot.items} lines=${w.snapshot.lines.size} rows=${w.snapshot.rows.size})")
                        logShape(tag, w.snapshot)
                        continue
                    }
                    val plan = PaypalPlanMapper.plan(parsed, target, now())
                    plans += plan
                    if (index == 0) logShape(tag, w.snapshot)
                    log("$tag: read parts=${parsed.parts.size} paid=${parsed.parts.count { it.paid }} due=${parsed.parts.count { !it.paid }} count=${parsed.count != null} total=${parsed.totalCents != null}")
                }
            }
        }
        val paid = plans.sumOf { p -> p.installments.count { it.state == xyz.grtlabs.pilastri.data.InstallmentState.PAID } }
        val due = plans.sumOf { p -> p.installments.count { it.state != xyz.grtlabs.pilastri.data.InstallmentState.PAID } }
        log("paypal plans: targets=${picked.size} read=${plans.size} failed=$failed paid=$paid due=$due" + if (stop != null) " stopped=${stop!!.javaClass.simpleName}" else "")
        return PlanReadResult(plans, picked.size, failed, paid, due, stop)
    }

    /** The last snapshot polled for the page being read, for the debug log when it never settles. */
    private var lastSnapshot: PaypalPlanMapper.Snapshot? = null

    /**
     * Debug-log only ([SyncLog] is off in release): the layout of the page with every value masked ([PaypalPlanMapper.shape]) and the
     * test ids with their counts, so a page that is not recognized can be understood from the log without recording it by hand.
     */
    private fun logShape(tag: String, s: PaypalPlanMapper.Snapshot?) {
        if (s == null) return
        if (s.testids.isNotEmpty()) log("$tag: testids ${s.testids.take(900)}")
        PaypalPlanMapper.shapeLines(s, spec.labels).forEach { log("$tag: $it") }
    }

    private sealed interface Wait {
        class Ready(val snapshot: PaypalPlanMapper.Snapshot) : Wait
        class Gate(val outcome: FetchOutcome) : Wait
        data object Timeout : Wait
    }

    /** Polls the page until it has loaded and its content has stopped changing for [PaypalPlanSpec.stablePolls] polls. */
    private suspend fun await(page: PageAccess): Wait {
        lastSnapshot = null
        val deadline = System.nanoTime() + spec.waitMs * 1_000_000
        var last: String? = null
        var streak = 0
        while (true) {
            gate(page.currentUrl())?.let { return Wait.Gate(it) }
            val s = PaypalPlanMapper.snapshot(page.evaluate(PaypalPlanMapper.script(spec)))
            if (s != null) lastSnapshot = s
            val state = s?.takeIf { it.ready && it.hasContent }?.signature
            when {
                state == null -> { last = null; streak = 0 }
                state == last -> streak++
                else -> { last = state; streak = 1 }
            }
            if (s != null && state != null && streak >= spec.stablePolls) return Wait.Ready(s)
            if (System.nanoTime() > deadline) return Wait.Timeout
            delay(spec.pollMs)
        }
    }

    /** Sign-in and 2FA pages end the read; a check page too. Any other provider page is read, a foreign one is skipped by [absolute] and [await]. */
    private fun gate(url: String?): FetchOutcome? = when (site.classify(url)) {
        SitePage.LOGIN, SitePage.SCA -> FetchOutcome.SignedOut
        SitePage.CHALLENGE -> FetchOutcome.Challenge
        else -> null
    }

    /** https://www.paypal.com/<path>: the link made absolute; null for another host, a protocol-relative link, or an action-like path. */
    internal fun absolute(link: String): String? {
        val l = link.trim()
        val url = when {
            l.startsWith("https://") -> l
            l.startsWith("/") && !l.startsWith("//") -> "https://www.paypal.com$l"
            else -> return null
        }
        if (!site.isProviderUrl(url)) return null
        val path = ProviderSite.parse(url)?.rawPath?.lowercase() ?: return null
        return if (DENIED.containsMatchIn(path)) null else url
    }

    private fun normalize(link: String): String? = absolute(link)

    private companion object {
        /** A plan page is only ever read. A path that names an action (paying, cancelling, changing, disputing) is never opened. */
        val DENIED = Regex("(^|[/_-])(cancel|payoff|pay-?now|paynow|pay-?early|checkout|authori[sz]e|disconnect|remove|delete|dispute|refund|send|withdraw|transfer|change|edit|update)([/_-]|$)")
    }
}
