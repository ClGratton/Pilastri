package xyz.grtlabs.pilastri.sync.auto

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import xyz.grtlabs.pilastri.data.Evidence
import xyz.grtlabs.pilastri.data.Installment
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PLAN_PAGE_REF
import xyz.grtlabs.pilastri.data.PlanPageLink
import xyz.grtlabs.pilastri.data.PlanReview

/**
 * Words of PayPal's plan page, compared after [LoginMatcher.norm]. UNVERIFIED until the page is recorded on the Fold (the activity
 * list's words were recorded; these are Italian PayPal wording the page is expected to use). A page whose words do not match is
 * "not recognized": nothing is stored for it.
 */
data class PaypalPlanLabels(
    val paid: List<String> = listOf("pagata", "pagato", "saldata", "saldato"),
    val due: List<String> = listOf("da pagare", "in programma", "programmata", "programmato", "prossima", "in scadenza", "in arrivo", "non pagata", "scade"),
    val late: List<String> = listOf("in ritardo", "scaduta", "scaduto", "pagamento non riuscito"),
    val total: List<String> = listOf("totale", "importo totale", "totale acquisto", "totale dell acquisto", "prezzo totale", "importo dell acquisto"),
    val months: Map<String, Int> = PaypalActivityLabels().months,
)

/**
 * How the plan pages are read. UNVERIFIED shape: the rows of the schedule are the page's list items / table rows / items whose
 * test id names an installment ([rowSelector]); the recorded shape replaces this.
 */
data class PaypalPlanSpec(
    /** Plan pages read per run at most (a person opens a few, not all, in a row). */
    val maxPlans: Int = 24,
    val waitMs: Long = 20_000,
    val pollMs: Long = 500,
    val stablePolls: Int = 2,
    /** Pause between plan pages, the same human pacing as between activity windows. */
    val betweenPagesMs: Long = 800,
    val rowSelector: String = "li, tr, [role=listitem], [data-testid*=installment i], [data-testid*=rata i], [data-testid*=payment-row i]",
    val labels: PaypalPlanLabels = PaypalPlanLabels(),
) {
    init { require(maxPlans in 1..100 && stablePolls >= 1) }
}

/** Pure parsing of a plan page's text into a plan, unit-tested on synthetic pages. Nothing is guessed: any doubt is "not recognized". */
object PaypalPlanMapper {
    /** What the page script returns. Text only. */
    class Snapshot(
        val ready: Boolean,
        /** The text of each schedule row (list item, table row, installment item) that holds an amount. */
        val rows: List<String>,
        /** The page's text lines (main content), in order. */
        val lines: List<String>,
        /** Number of elements with a data-testid, for the log when the page is not recognized. */
        val items: Int = 0,
        /** The distinct data-testid names with their counts ("name:3,other:1"), for the debug log only (UI identifiers, never values). */
        val testids: String = "",
    ) {
        val hasContent: Boolean get() = lines.size >= 3
        /** Changes while the page is still drawing. */
        val signature: String get() = "${rows.size}|${lines.size}|${lines.joinToString("\n").hashCode()}"
    }

    class PartRead(val seq: Int, val cents: Long, val date: LocalDate, val paid: Boolean, val late: Boolean)

    /** [count]: the number of parts the page states. [totalCents]: the purchase total the page states. Both null when the page does not say. */
    class PlanPage(val totalCents: Long?, val count: Int?, val parts: List<PartRead>)

    /**
     * The read script: evaluates to a JSON string `{v,ready,rows:[…],lines:[…],items}`. Reads text only; never clicks.
     * Rows are the innermost elements matching [PaypalPlanSpec.rowSelector] that show an amount; lines come from `main`, else the body.
     */
    fun script(spec: PaypalPlanSpec = PaypalPlanSpec()): String {
        val sel = JSONObject().put("row", spec.rowSelector)
        return """
(function (S) {
  function txt(n) { return n ? String(n.innerText || n.textContent || '').replace(/[ \t  ]+/g, ' ').trim() : ''; }
  var rows = [], all = document.querySelectorAll(S.row);
  for (var i = 0; i < all.length && i < 400; i++) {
    var el = all[i];
    if (el.querySelector(S.row)) continue;
    var t = txt(el).replace(/\s*\n\s*/g, ' · ').slice(0, 300);
    if (/\d,\d\d/.test(t)) rows.push(t);
  }
  var root = document.querySelector('main') || document.body, lines = [];
  var raw = root ? String(root.innerText || '').split('\n') : [];
  for (var j = 0; j < raw.length && lines.length < 400; j++) { var l = raw[j].replace(/[ \t  ]+/g, ' ').trim(); if (l) lines.push(l.slice(0, 200)); }
  var ids = {}, order = [], tn = document.querySelectorAll('[data-testid]');
  for (var k = 0; k < tn.length && k < 2000; k++) { var id = String(tn[k].getAttribute('data-testid')).slice(0, 60); if (!(id in ids)) { ids[id] = 0; order.push(id); } ids[id]++; }
  var tsum = order.slice(0, 40).map(function (n) { return n + ':' + ids[n]; }).join(',');
  return JSON.stringify({ v: 2, ready: document.readyState === 'complete', rows: rows, lines: lines, items: tn.length, testids: tsum });
})($sel)
""".trimIndent()
    }

    fun snapshot(result: String?): Snapshot? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            val inner = JSONTokener(result).nextValue().let { it as? String ?: result }
            val o = JSONObject(inner)
            fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until minOf(a.length(), 400)).map { a.optString(it) }
            Snapshot(o.optBoolean("ready"), strings(o.optJSONArray("rows")), strings(o.optJSONArray("lines")), o.optInt("items").coerceAtLeast(0), o.optString("testids").take(1500))
        } catch (_: Exception) {
            null
        }
    }

    private val SPACES = Regex("[\\s  -   　]+")
    private val AMOUNT = Regex("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+|\\d+),(\\d{2})(?!\\d)")
    private val DAY_MONTH = Regex("(?<![0-9])(\\d{1,2})\\s*([A-Za-zÀ-ſ]{3,12})\\.?(?:\\s+(\\d{4}))?(?![0-9])")
    private val NUMERIC = Regex("(?<![0-9.,:/])(\\d{1,2})[/.](\\d{1,2})[/.](\\d{2,4})(?![0-9])")
    private val ORDINALS = mapOf(
        "prima" to 1, "primo" to 1, "seconda" to 2, "secondo" to 2, "terza" to 3, "terzo" to 3, "quarta" to 4, "quinta" to 5, "sesta" to 6,
        "settima" to 7, "ottava" to 8, "nona" to 9, "decima" to 10, "undicesima" to 11, "dodicesima" to 12,
    )
    private val RATA_N_OF_M = Regex("\\brata (\\d{1,3}) di (\\d{1,3})\\b")
    private val RATA_N = Regex("\\brata (\\d{1,3})\\b|\\b(\\d{1,3}) ?(?:a|o)? rata\\b")
    private val COUNT_RATE = Regex("\\b(\\d{1,3}) rate\\b")
    private const val WINDOW_DAYS = 120L

    /** Euro amounts in [text] as cents (every number with a decimal comma and two decimals). */
    internal fun amounts(text: String): List<Long> = AMOUNT.findAll(text).mapNotNull { m ->
        AmountText.parse(m.value)?.let { runCatching { it.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact() }.getOrNull() }
    }.toList()

    /** The first date in [text]: a year is used when written, else the year that puts the date within ~half a year of today (else none). */
    internal fun dateIn(text: String, labels: PaypalPlanLabels, today: LocalDate): LocalDate? {
        NUMERIC.find(text)?.let { m ->
            val day = m.groupValues[1].toIntOrNull(); val month = m.groupValues[2].toIntOrNull()
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            if (day != null && month != null && year != null) return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        for (m in DAY_MONTH.findAll(text)) {
            val key = LoginMatcher.norm(m.groupValues[2]).take(3)
            val month = labels.months.entries.firstOrNull { LoginMatcher.norm(it.key) == key }?.value ?: continue
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val written = m.groupValues[3].toIntOrNull()
            fun at(y: Int) = try { LocalDate.of(y, month, day) } catch (_: DateTimeException) { null }
            if (written != null) return at(written)
            return (today.year - 1..today.year + 1).mapNotNull(::at).minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(today, it)) }
                ?.takeIf { kotlin.math.abs(ChronoUnit.DAYS.between(today, it)) <= WINDOW_DAYS }
        }
        return null
    }

    private fun mentions(norm: String, words: List<String>): Boolean {
        val h = " $norm "
        return words.any { w -> LoginMatcher.norm(w).let { it.isNotEmpty() && h.contains(" $it ") } }
    }

    /**
     * The plan page as a plan, or null when it is not recognized. Strict by design: a part row needs ONE amount, a date and a status
     * word; part numbers are all explicit or all absent; the numbers must agree (no part beyond the stated count; the parts add up to
     * the total when the whole schedule is listed). Rows without an amount are headings and are skipped.
     */
    fun parse(s: Snapshot, labels: PaypalPlanLabels, today: LocalDate): PlanPage? {
        val candidates = s.rows.map { it.replace(SPACES, " ").trim() }.filter { amounts(it).isNotEmpty() }
        if (candidates.isEmpty()) return null
        class Raw(val seq: Int?, val cents: Long, val date: LocalDate, val paid: Boolean, val late: Boolean, val of: Int?)
        val raws = ArrayList<Raw>()
        for (text in candidates) {
            val n = LoginMatcher.norm(text)
            val money = amounts(text)
            if (money.size != 1 || money[0] <= 0) return null
            val date = dateIn(text, labels, today) ?: return null
            val paid = mentions(n, labels.paid); val late = mentions(n, labels.late); val upcoming = mentions(n, labels.due)
            // Exactly one status: "pagata" and "da pagare" together, or none, is a row we do not understand.
            if ((if (paid) 1 else 0) + (if (late || upcoming) 1 else 0) != 1) return null
            val of = RATA_N_OF_M.find(n)
            val seq = of?.groupValues?.get(1)?.toIntOrNull()
                ?: RATA_N.find(n)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
                ?: ORDINALS.entries.firstOrNull { (w, _) -> " $n ".contains(" $w ") }?.value
            raws += Raw(seq, money[0], date, paid, late && !paid, of?.groupValues?.get(2)?.toIntOrNull())
        }
        val numbered = raws.count { it.seq != null }
        if (numbered != 0 && numbered != raws.size) return null
        val parts = raws.mapIndexed { i, r -> PartRead(r.seq ?: (i + 1), r.cents, r.date, r.paid, r.late) }
        if (parts.map { it.seq }.distinct().size != parts.size || parts.any { it.seq !in 1..120 }) return null
        val counts = raws.mapNotNull { it.of }.distinct()
        val lineCounts = s.lines.mapNotNull { COUNT_RATE.find(LoginMatcher.norm(it))?.groupValues?.get(1)?.toIntOrNull() }.filter { it in 2..120 }.distinct()
        val count = when {
            counts.size == 1 -> counts[0]
            counts.size > 1 -> return null
            lineCounts.size == 1 -> lineCounts[0]
            else -> null
        }
        if (count != null && (count !in 1..120 || parts.any { it.seq > count })) return null
        val total = totalOf(s.lines, labels)
        if (count != null && parts.size == count && total != null && parts.sumOf { it.cents } != total) return null
        return PlanPage(total, count, parts.sortedBy { it.seq })
    }

    /** The purchase total: an amount on a line that is only a total label, or on the line after it (or after a label with the amount). */
    private fun totalOf(lines: List<String>, labels: PaypalPlanLabels): Long? {
        val found = LinkedHashSet<Long>()
        val keys = labels.total.map(LoginMatcher::norm)
        for ((i, line) in lines.withIndex()) {
            val n = LoginMatcher.norm(line.replace(AMOUNT, " ").replace("€", " "))
            if (n !in keys) continue
            (amounts(line).firstOrNull() ?: lines.getOrNull(i + 1)?.takeIf { amounts(it).size == 1 && LoginMatcher.norm(it.replace(AMOUNT, " ").replace("€", " ")).isEmpty() }?.let { amounts(it).first() })
                ?.takeIf { it > 0 }?.let { found += it }
        }
        return found.singleOrNull()
    }

    private val WORDS = setOf(
        "rata", "rate", "di", "totale", "pagamento", "pagamenti", "importo", "scadenza", "ordine", "acquisto", "piano", "stato", "paga", "ora",
        "anticipo", "dettagli", "prossima", "prossimo", "rimanente", "residuo", "interessi", "data", "rimborso", "in", "a", "da", "il", "la", "e",
    )

    /**
     * A line of the page with every value masked, for the debug log: digits become 9, and a word that is not one of the plan page's
     * label words, a month or a status word becomes a run of a's. Amounts, dates, names and ids never appear; the label words, the
     * punctuation and the layout do. "Rata 2 di 3 · 12,34 € · Pagata" -> "rata 9 di 9 · 99,99 € · pagata".
     */
    internal fun shape(line: String, labels: PaypalPlanLabels = PaypalPlanLabels()): String {
        val known = HashSet<String>(WORDS)
        (labels.paid + labels.due + labels.late + labels.total).forEach { w -> w.split(' ').filter { it.isNotEmpty() }.forEach { known += LoginMatcher.norm(it) } }
        labels.months.keys.forEach { known += LoginMatcher.norm(it) }
        val out = StringBuilder()
        var i = 0
        val t = line.replace(SPACES, " ").trim().take(120)
        while (i < t.length) {
            val c = t[i]
            if (c.isLetter()) {
                var j = i; while (j < t.length && t[j].isLetter()) j++
                val w = LoginMatcher.norm(t.substring(i, j))
                out.append(if (w in known) t.substring(i, j).lowercase() else "a".repeat(minOf(j - i, 3)))
                i = j
            } else { out.append(if (c.isDigit()) '9' else c); i++ }
        }
        return out.toString().replace(Regex("9{2,}"), "99").take(100)
    }

    /** Debug-log lines describing a snapshot's layout with all values masked (see [shape]); at most [limit] lines. */
    internal fun shapeLines(s: Snapshot, labels: PaypalPlanLabels = PaypalPlanLabels(), limit: Int = 40): List<String> =
        s.lines.take(limit).mapIndexed { i, l -> "L$i ${shape(l, labels)}" } + s.rows.take(12).mapIndexed { i, r -> "R$i ${shape(r, labels)}" }

    /** The plan the page states, with the id the target names. Evidence: "PayPal, pagina del piano" dated at the read. */
    fun plan(page: PlanPage, target: PlanTarget, readAt: Instant): InstallmentPlan {
        val seed = target.seed
        val pi3 = seed?.type == PaypalRowType.PLAN_IN_3 || (seed == null && page.count == 3)
        val token = PlanPageLink.encode(target.link) ?: sha256(target.link).take(24)
        val readDate = readAt.atZone(java.time.ZoneId.of("Europe/Rome")).toLocalDate()
        return InstallmentPlan(
            id = target.planId,
            lender = if (pi3) PaypalActivityMapper.PI3_LENDER else PaypalActivityMapper.LONG_LENDER,
            merchant = seed?.merchant?.takeIf { it.isNotBlank() }?.take(120),
            totalCents = page.totalCents,
            currency = "EUR",
            count = page.count,
            installments = page.parts.map { p ->
                Installment(
                    seq = p.seq, dueDate = if (p.paid) null else p.date, cents = p.cents,
                    state = when { p.paid -> InstallmentState.PAID; p.late -> InstallmentState.LATE; else -> InstallmentState.DUE },
                    paidOn = if (p.paid) p.date else null,
                )
            },
            purchaseDate = seed?.date,
            source = InstallmentSource.PAYPAL,
            evidence = listOf(Evidence("$PLAN_PAGE_REF$token", readDate, "PayPal, pagina del piano")),
            review = PlanReview.PENDING,
            extractedByAgent = false,
            updatedAt = readAt.toString(),
        )
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
