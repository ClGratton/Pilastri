package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/*
 * Installment plans (2026-10-31): purchases paid in parts (PayPal Pay in 3, Klarna, Scalapay, a card's "pagamento a rate"…), learned from
 * Gmail receipts and PayPal activity. A plan is a forward commitment, never a movement: cash leaves through the bank or PayPal rows when each
 * part is collected, so spending counts each part once, when it is paid. Unknown amounts and dates stay null (missing is not zero).
 */

enum class InstallmentSource { GMAIL, PAYPAL, MANUAL, AGENT }

/** Review state of the whole plan, like an imported movement: nothing the connectors find is trusted until the user confirms it. */
enum class PlanReview { PENDING, CONFIRMED, IGNORED }

/** What the evidence says about one part. LATE is only set when the lender says so; a past DUE date alone stays DUE (we may have missed the receipt). */
enum class InstallmentState { DUE, PAID, LATE, CANCELLED }

data class Installment(
    val seq: Int,
    val dueDate: LocalDate?,
    val cents: Long?,
    val state: InstallmentState,
    val paidOn: LocalDate? = null,
    /** The bank or PayPal movement that paid it, when one was matched. */
    val movementId: String? = null,
) {
    init {
        require(seq in 1..120 && (cents == null || cents > 0))
        require(paidOn == null || state == InstallmentState.PAID)
        require(movementId == null || (movementId.isNotBlank() && movementId.length <= 200))
    }
}

/** Where a fact came from: "gmail:<message id>" or "paypal:<transaction id>", with a short summary. Mail bodies are never stored. */
data class Evidence(val ref: String, val date: LocalDate, val summary: String) {
    init { require(ref.matches(Regex("[a-z]{2,12}:[A-Za-z0-9._:@-]{1,180}")) && summary.isNotBlank() && summary.length <= 200) }
}

data class InstallmentPlan(
    /** Stable across rescans: "<lender key>:<lender's order/plan id>", or a hash of lender, merchant, purchase date and total when no id exists. */
    val id: String,
    /** "PayPal Paga in 3 rate", "Klarna", "Scalapay"… as the user would recognize it. */
    val lender: String,
    val merchant: String?,
    val totalCents: Long?,
    val currency: String,
    /** The number of parts the lender states, which can exceed the parts already seen. */
    val count: Int?,
    val installments: List<Installment>,
    val purchaseDate: LocalDate?,
    val categoryId: String? = null,
    val fundingAccountId: String? = null,
    val source: InstallmentSource,
    val evidence: List<Evidence>,
    val review: PlanReview,
    val extractedByAgent: Boolean,
    val updatedAt: String,
    /** Ids the same purchase has in other sources (a Gmail receipt and the PayPal row for one Pay in 3 purchase). */
    val aliases: List<String> = emptyList(),
) {
    init {
        require(aliases.size <= 10 && aliases.all { it.isNotBlank() && it.length <= 200 && it != id })
        require(id.isNotBlank() && id.length <= 200 && lender.isNotBlank() && lender.length <= 80 && (merchant?.length ?: 0) <= 120)
        require(totalCents == null || totalCents > 0)
        require(currency.length == 3 && (count == null || count in 1..120) && (count == null || installments.size <= count))
        require(installments.map { it.seq }.distinct().size == installments.size && installments.all { count == null || it.seq <= count })
        require(evidence.size <= 200 && evidence.map { it.ref }.distinct().size == evidence.size)
        require(updatedAt.isNotBlank() && updatedAt.length <= 50)
    }

    /** Parts not yet known to be paid. Unknown amounts are not counted; [remainingUnknown] says how many. */
    val open: List<Installment> get() = installments.filter { it.state == InstallmentState.DUE || it.state == InstallmentState.LATE }
    val remainingCents: Long get() = open.sumOf { it.cents ?: 0L }
    val remainingUnknown: Int get() = open.count { it.cents == null } + ((count ?: installments.size) - installments.size)
    val active: Boolean get() = review != PlanReview.IGNORED && (open.isNotEmpty() || (count != null && installments.size < count))

    /** True when PayPal's own plan page was read for this plan: its total, count and parts come from the provider, not from the activity row. */
    val fromPlanPage: Boolean get() = evidence.any { it.ref.startsWith(PLAN_PAGE_REF) }
    /** The token of PayPal's plan page recorded with the plan (the part of the evidence ref after [PLAN_PAGE_REF]), or null. */
    val planPageToken: String? get() = evidence.firstOrNull { it.ref.startsWith(PLAN_PAGE_REF) }?.ref?.removePrefix(PLAN_PAGE_REF)?.takeIf { it.isNotBlank() }
}

/** Evidence ref of a plan read from PayPal's plan page: `paypal:plan-<token>`. One per plan; a newer read replaces it (date = read time). */
const val PLAN_PAGE_REF = "paypal:plan-"

/**
 * The link of a PayPal plan page (path and query, as the activity row gave it) kept in the plan's evidence ref, so a later sync can read
 * the page again without finding the row: base64url, at most 180 characters (the evidence ref limit). No host, never a full URL.
 */
object PlanPageLink {
    private const val MAX_TOKEN = 180 - 12

    fun encode(link: String): String? {
        val path = link.trim().let { l -> if (l.startsWith("https://www.paypal.com/")) l.removePrefix("https://www.paypal.com") else l }
        if (!path.startsWith("/") || path.startsWith("//") || path.any { it.isISOControl() || it.isWhitespace() }) return null
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
        return token.takeIf { it.length <= MAX_TOKEN }
    }

    /** The path and query, or null when [token] is not one of ours. */
    fun decode(token: String): String? = try {
        String(java.util.Base64.getUrlDecoder().decode(token), Charsets.UTF_8).takeIf { it.startsWith("/") && !it.startsWith("//") }
    } catch (_: IllegalArgumentException) { null }
}

/** Sync bookkeeping for a source that is not an account (Gmail), or the installment side of one that is (PayPal). Never tokens. */
data class InstallmentSync(val source: InstallmentSource, val lastScanAt: String?, val coverageFrom: LocalDate?, val coverageTo: LocalDate?, val lastResult: String?) {
    init { require((lastResult?.length ?: 0) <= 500) }
}

/**
 * A payment seen in a receipt ("hai pagato la rata 2 di 3"); [seq] null when the receipt does not number it.
 * [byAgent] when an AI read the receipt: such a fact never changes a plan the user has confirmed (it needs a tap).
 */
data class InstallmentPaymentSeen(val planId: String, val seq: Int?, val paidOn: LocalDate, val cents: Long?, val evidence: Evidence, val byAgent: Boolean = false)

data class InstallmentMerge(val archive: Archive, val added: Int, val updated: Int)

/**
 * Adds or refreshes plans found by a connector. Existing plans keep the user's review, category and funding account; a part already PAID
 * stays PAID; new evidence is appended. Plans found again after the user ignored them stay ignored.
 */
fun Archive.withInstallmentPlans(found: List<InstallmentPlan>, at: String): InstallmentMerge {
    var added = 0; var updated = 0
    val byId = installments.associateBy { it.id }.toMutableMap()
    val claimed = HashSet<String>()
    found.forEach { incoming ->
        // Likeness only links a plan that has not already absorbed another source's purchase, once per call.
        val match = byId.values.firstOrNull { incoming.id in it.aliases } ?: byId[incoming.id]
            ?: byId.values.filter { it.aliases.isEmpty() && it.id !in claimed && it.samePurchaseAs(incoming) }.singleOrNull()?.also { claimed += it.id }
        // The same purchase seen by another source keeps the first id; the other source's id becomes an alias.
        val f = if (match != null && match.id != incoming.id) incoming.copy(id = match.id) else incoming
        val old = match
        if (old == null) { byId[f.id] = f.copy(review = PlanReview.PENDING, updatedAt = at); added++; return@forEach }
        // What an AI read from a mail never changes a plan the user confirmed without a tap.
        if (old.review == PlanReview.CONFIRMED && f.extractedByAgent) return@forEach
        // PayPal's own plan page is the provider's word on the total, the count and each part; a failed read never reaches this point.
        val provider = f.fromPlanPage
        val parts = (old.installments.map { it.seq } + f.installments.map { it.seq }).distinct().sorted().map { seq ->
            val o = old.installments.firstOrNull { it.seq == seq }; val n = f.installments.firstOrNull { it.seq == seq }
            when {
                o == null -> n!!
                n == null -> o
                // Already paid stays paid; the provider adds the real due date and amount it states.
                o.state == InstallmentState.PAID -> if (provider) o.copy(dueDate = n.dueDate ?: o.dueDate, cents = o.cents ?: n.cents, paidOn = o.paidOn ?: n.paidOn) else o
                else -> {
                    val sameSource = f.source == old.source
                    n.copy(
                        dueDate = if (sameSource) n.dueDate ?: o.dueDate else o.dueDate ?: n.dueDate,
                        cents = if (sameSource) n.cents ?: o.cents else o.cents ?: n.cents,
                        state = if (o.state == InstallmentState.LATE && n.state == InstallmentState.DUE) InstallmentState.LATE else n.state,
                        movementId = o.movementId ?: n.movementId,
                    )
                }
            }
        }
        val merged = old.copy(
            merchant = old.merchant ?: f.merchant,
            totalCents = if (provider) f.totalCents ?: old.totalCents else old.totalCents ?: f.totalCents,
            count = (if (provider && f.count != null) maxOf(f.count, parts.size, parts.maxOfOrNull { it.seq } ?: 0)
                else maxOf(old.count ?: 0, f.count ?: 0, parts.size, parts.maxOfOrNull { it.seq } ?: 0)).takeIf { it > 0 },
            installments = parts, purchaseDate = old.purchaseDate ?: f.purchaseDate,
            // The plan-page evidence is replaced by the newer read (its date is the read time); other evidence is only ever added.
            evidence = (old.evidence.filter { e -> !(provider && e.ref.startsWith(PLAN_PAGE_REF)) } +
                f.evidence.filter { e -> e.ref.startsWith(PLAN_PAGE_REF) || old.evidence.none { it.ref == e.ref } }).takeLast(200),
            extractedByAgent = old.extractedByAgent && f.extractedByAgent,
            aliases = (old.aliases + listOfNotNull(incoming.id.takeIf { it != old.id }) + f.aliases).distinct().filter { it != old.id }.take(10),
        )
        if (merged != old) { byId[f.id] = merged.copy(updatedAt = at); updated++ }
    }
    return InstallmentMerge(copy(installments = byId.values.toList()).also { ArchiveCodec.encode(it) }, added, updated)
}

/** Marks a part paid from a receipt: the numbered part, else the earliest open part with the same amount, else the earliest open part. */
fun Archive.withInstallmentPaid(seen: InstallmentPaymentSeen, at: String): Archive {
    val plan = installments.firstOrNull { it.id == seen.planId } ?: installments.firstOrNull { seen.planId in it.aliases } ?: return this
    if (seen.byAgent && plan.review == PlanReview.CONFIRMED) return this
    // A receipt without a part number applies once: the same payment (date and amount) already recorded is not a second part.
    if (seen.seq == null && plan.installments.any { it.state == InstallmentState.PAID && it.paidOn == seen.paidOn && (seen.cents == null || it.cents == seen.cents) }) return this
    val target = plan.installments.firstOrNull { it.seq == seen.seq }
        ?: plan.open.filter { seen.cents != null && it.cents == seen.cents }.minByOrNull { it.seq }
        ?: plan.open.minByOrNull { it.seq }
    val parts = if (target != null) plan.installments.map { if (it.seq == target.seq) it.copy(state = InstallmentState.PAID, paidOn = seen.paidOn, cents = it.cents ?: seen.cents) else it }
        else if (seen.seq != null && (plan.count == null || seen.seq <= plan.count)) (plan.installments + Installment(seen.seq, null, seen.cents, InstallmentState.PAID, seen.paidOn)).sortedBy { it.seq }
        else return this
    val evidence = if (plan.evidence.any { it.ref == seen.evidence.ref }) plan.evidence else (plan.evidence + seen.evidence).takeLast(200)
    return withInstallmentPlan(plan.copy(installments = parts, evidence = evidence, updatedAt = at))
}

/** The user's (or a confirmed assistant change's) edit of one plan: review, category, funding account, corrected parts. */
fun Archive.withInstallmentPlan(plan: InstallmentPlan): Archive {
    require(installments.any { it.id == plan.id }) { "Piano a rate non trovato" }
    return copy(installments = installments.map { if (it.id == plan.id) plan else it }).also { ArchiveCodec.encode(it) }
}

fun Archive.withInstallmentSync(sync: InstallmentSync): Archive =
    copy(installmentSync = installmentSync.filter { it.source != sync.source } + sync).also { ArchiveCodec.encode(it) }

private const val WEB_PLAN_PREFIX = "paypal-web:"
private const val PI3_PARTS = 3
/** Pay in 3 collects a part at the purchase and then about every 30 days (seen: +0, +31, +61/62). */
private const val PI3_PART_SPACING_DAYS = 30.4375
private const val PI3_EARLY_DAYS = 3
private const val PI3_LATE_DAYS = 5
private fun payeeKey(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
private val PI3_PAYEE = payeeKey("Paga in 3 rate")

private class PartClaim(val plan: InstallmentPlan, val seq: Int, val rows: List<ExternalMovement>)

/**
 * Marks the parts of PayPal Pay in 3 plans that PayPal's own activity shows as collected (the 2026-11-01 "0 rate pagate" bug).
 *
 * The activity list has two kinds of rows for one purchase: the purchase (type "Paga in 3 rate", merchant, total), read as a plan with
 * no parts, and one outflow per collected part whose payee is "Paga in 3 rate". Nothing linked the two, so every plan stayed at zero.
 * A part is matched only by evidence, all of it required:
 *  - an unlinked PayPal outflow in euro whose payee is "Paga in 3 rate";
 *  - an amount of floor or ceiling of total/3, the part's exact share (remainder cents on the first parts) winning when several fit;
 *  - a date about (seq - 1) x 30.4 days after the purchase (3 days early to 5 days late).
 * Rows several plans could claim, and slots with differing candidate rows, stay unmatched (the part stays unpaid) unless identical
 * rows (same date and amount) are at least as many as the slots claiming them. One row pays one part. A matched part records the
 * charged amount, the date, the movement and an evidence line. Plans the user ignored, parts already PAID, plans with another
 * part count (Pay later in 6/12/24 states no per-part amount) and rows already linked are never touched. Safe to repeat.
 */
fun Archive.withPaypalInstallmentPayments(at: String): Archive {
    val linked = installments.flatMap { p -> p.installments.mapNotNull { it.movementId } }.toSet()
    val payments = externalMovements.filter {
        it.provider == ImportProvider.PAYPAL && it.signedCents < 0 && it.currency == "EUR" && it.id !in linked && payeeKey(it.description) == PI3_PAYEE
    }
    if (payments.isEmpty()) return this
    val claims = installments.filter { p ->
        p.review != PlanReview.IGNORED && p.count == PI3_PARTS && p.totalCents != null && p.purchaseDate != null &&
            (p.id.startsWith(WEB_PLAN_PREFIX) || p.aliases.any { it.startsWith(WEB_PLAN_PREFIX) })
    }.flatMap { p ->
        val total = p.totalCents!!
        val low = total / PI3_PARTS
        val high = (total + PI3_PARTS - 1) / PI3_PARTS
        (1..PI3_PARTS).mapNotNull { seq ->
            val existing = p.installments.firstOrNull { it.seq == seq }
            if (existing != null && existing.state != InstallmentState.DUE && existing.state != InstallmentState.LATE) return@mapNotNull null
            // A part PayPal's own plan page describes is the provider's word; the guess only fills parts that page did not mention.
            if (existing != null && p.fromPlanPage) return@mapNotNull null
            val expected = PI3_PART_SPACING_DAYS * (seq - 1)
            val near = payments.filter { m ->
                val offset = ChronoUnit.DAYS.between(p.purchaseDate, m.date)
                -m.signedCents in low..high && offset >= expected - PI3_EARLY_DAYS && offset <= expected + PI3_LATE_DAYS
            }
            // The remainder cents go to the first parts (seen: 3 plans with remainder 2 charged high, high, low), so the exact share wins.
            val share = low + if (seq <= total % PI3_PARTS) 1 else 0
            val rows = near.filter { -it.signedCents == share }.ifEmpty { near }
            if (rows.isEmpty()) null else PartClaim(p, seq, rows)
        }
    }
    // A slot whose candidates differ cannot say which one paid it.
    fun signature(m: ExternalMovement) = m.date to m.signedCents
    val single = claims.filter { c -> c.rows.map(::signature).distinct().size == 1 }
    val assigned = ArrayList<Pair<PartClaim, ExternalMovement>>()
    single.groupBy { signature(it.rows.first()) }.forEach { (sig, group) ->
        val pool = payments.filter { signature(it) == sig }.sortedBy { it.id }
        val ordered = group.sortedWith(compareBy({ it.plan.id }, { it.seq }))
        if (pool.size >= ordered.size) ordered.forEachIndexed { i, c -> assigned += c to pool[i] }
    }
    if (assigned.isEmpty()) return this
    val byPlan = assigned.groupBy { it.first.plan.id }
    val next = installments.map { p ->
        val mine = byPlan[p.id] ?: return@map p
        var parts = p.installments
        var evidence = p.evidence
        mine.forEach { (claim, m) ->
            val existing = parts.firstOrNull { it.seq == claim.seq }
            val paid = Installment(claim.seq, existing?.dueDate ?: m.date, -m.signedCents, InstallmentState.PAID, paidOn = m.date, movementId = m.id)
            parts = (parts.filter { it.seq != claim.seq } + paid).sortedBy { it.seq }
            val ref = "paypal:" + m.id.substringAfter(':')
            if (evidence.none { it.ref == ref }) evidence = (evidence + Evidence(ref, m.date, "Rata ${claim.seq} di $PI3_PARTS pagata (movimento PayPal)")).takeLast(200)
        }
        p.copy(installments = parts, evidence = evidence, updatedAt = at)
    }
    return copy(installments = next).also { ArchiveCodec.encode(it) }
}

/**
 * Links the parts PayPal's plan page shows as paid to the activity outflow that paid them, when it is clearly the same row:
 *  - an unlinked PayPal outflow in euro for exactly the part's amount, dated the day the page says it was paid (one day either side);
 *  - whose payee is "Paga in 3 rate" or names the plan's merchant.
 * A part links only when exactly one row fits it and no other part fits that row; one row pays one part. Parts the page shows as paid
 * stay paid with no link when nothing fits (the provider's evidence stands). Plans the user ignored and parts without an amount or a
 * paid date are never touched. Safe to repeat.
 */
fun Archive.withPaypalPlanPartMovements(at: String): Archive {
    val linked = installments.flatMap { p -> p.installments.mapNotNull { it.movementId } }.toSet()
    val outflows = externalMovements.filter { it.provider == ImportProvider.PAYPAL && it.signedCents < 0 && it.currency == "EUR" && it.id !in linked }
    if (outflows.isEmpty()) return this
    class Slot(val plan: InstallmentPlan, val part: Installment, val rows: List<ExternalMovement>)
    val slots = installments.filter { it.review != PlanReview.IGNORED && it.fromPlanPage && it.currency == "EUR" }.flatMap { p ->
        p.installments.filter { it.state == InstallmentState.PAID && it.movementId == null && it.cents != null && it.paidOn != null }.map { part ->
            val merchant = p.merchant?.let(::merchantKey).orEmpty()
            Slot(p, part, outflows.filter { m ->
                val key = merchantKey(m.description)
                -m.signedCents == part.cents && kotlin.math.abs(ChronoUnit.DAYS.between(part.paidOn, m.date)) <= 1 &&
                    (payeeKey(m.description) == PI3_PAYEE || (merchant.isNotEmpty() && key.isNotEmpty() && (merchant.contains(key) || key.contains(merchant))))
            })
        }
    }
    val unique = slots.filter { it.rows.size == 1 }
    val taken = unique.groupBy { it.rows.single().id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    // A row that also fits a slot with several candidates is not exclusive either.
    val contested = slots.filter { it.rows.size > 1 }.flatMap { s -> s.rows.map { it.id } }.toSet()
    val links = taken.filterKeys { it !in contested }
    if (links.isEmpty()) return this
    val byPlan = links.entries.groupBy({ it.value.plan.id }, { it.key to it.value.part.seq })
    val next = installments.map { p ->
        val mine = byPlan[p.id] ?: return@map p
        var evidence = p.evidence
        val parts = p.installments.map { part ->
            val rowId = mine.firstOrNull { it.second == part.seq }?.first ?: return@map part
            val m = outflows.first { it.id == rowId }
            val ref = "paypal:" + m.id.substringAfter(':')
            if (evidence.none { it.ref == ref }) evidence = (evidence + Evidence(ref, m.date, "Rata ${part.seq} pagata (movimento PayPal)")).takeLast(200)
            part.copy(movementId = m.id)
        }
        p.copy(installments = parts, evidence = evidence, updatedAt = at)
    }
    return copy(installments = next).also { ArchiveCodec.encode(it) }
}

/**
 * Two sources describe one purchase when the lender is the same, both state the same total, the purchase dates are at most three
 * days apart and, where both name the merchant, the names agree. Anything less certain stays a separate plan for the user to judge.
 */
internal fun InstallmentPlan.samePurchaseAs(other: InstallmentPlan): Boolean {
    if (source == other.source || totalCents == null || totalCents != other.totalCents || purchaseDate == null || other.purchaseDate == null) return false
    if (kotlin.math.abs(purchaseDate.toEpochDay() - other.purchaseDate.toEpochDay()) > 3) return false
    if (lenderKey(lender) != lenderKey(other.lender)) return false
    val a = merchant?.let(::merchantKey).orEmpty(); val b = other.merchant?.let(::merchantKey).orEmpty()
    return a.isEmpty() || b.isEmpty() || a.contains(b) || b.contains(a)
}

private fun lenderKey(lender: String): String = lender.lowercase().replace(Regex("[^a-z]"), " ").trim().substringBefore(' ')

data class DueInstallment(val plan: InstallmentPlan, val part: Installment)

/** Open parts with a due date in [from]..[to], earliest first, from plans the user has not ignored. Pending plans are included and flagged by their review. */
fun Archive.dueInstallments(from: LocalDate, to: LocalDate): List<DueInstallment> =
    installments.filter { it.review != PlanReview.IGNORED }
        .flatMap { p -> p.open.filter { it.dueDate != null && !it.dueDate.isBefore(from) && !it.dueDate.isAfter(to) }.map { DueInstallment(p, it) } }
        .sortedWith(compareBy({ it.part.dueDate }, { it.plan.id }))

/** What a month already owes to installment plans, in euro; [unknown] counts parts due that month with no stated amount or in another currency. */
data class MonthCommitment(val month: YearMonth, val cents: Long, val parts: Int, val unknown: Int)

fun Archive.installmentCommitment(month: YearMonth): MonthCommitment {
    val due = dueInstallments(month.atDay(1), month.atEndOfMonth())
    val euro = due.filter { it.plan.currency == "EUR" && it.part.cents != null }
    return MonthCommitment(month, euro.sumOf { it.part.cents!! }, due.size, due.size - euro.size)
}

internal object InstallmentCodec {
    private fun JSONObject.str(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.long(k: String): Long? = if (isNull(k)) null else getString(k).also { require(it.matches(Regex("0|[1-9][0-9]{0,17}"))) }.toLong()
    private fun JSONObject.int(k: String): Int? = if (isNull(k)) null else getInt(k)
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
    private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> { require(length() <= 10_000); return (0 until length()).map { f(getJSONObject(it)) } }

    fun encodePlans(plans: List<InstallmentPlan>): JSONArray = JSONArray().apply { plans.forEach { p -> put(JSONObject()
        .put("id", p.id).put("lender", p.lender).put("merchant", p.merchant.orNull()).put("totalCents", p.totalCents?.toString().orNull())
        .put("currency", p.currency).put("count", p.count.orNull()).put("purchaseDate", p.purchaseDate?.toString().orNull())
        .put("categoryId", p.categoryId.orNull()).put("fundingAccountId", p.fundingAccountId.orNull()).put("source", p.source.name)
        .put("review", p.review.name).put("extractedByAgent", p.extractedByAgent).put("updatedAt", p.updatedAt)
        .put("aliases", JSONArray().apply { p.aliases.forEach { put(it) } })
        .put("installments", JSONArray().apply { p.installments.forEach { i -> put(JSONObject().put("seq", i.seq).put("dueDate", i.dueDate?.toString().orNull())
            .put("cents", i.cents?.toString().orNull()).put("state", i.state.name).put("paidOn", i.paidOn?.toString().orNull()).put("movementId", i.movementId.orNull())) } })
        .put("evidence", JSONArray().apply { p.evidence.forEach { e -> put(JSONObject().put("ref", e.ref).put("date", e.date.toString()).put("summary", e.summary)) } })) } }

    fun decodePlans(a: JSONArray): List<InstallmentPlan> = a.map { o -> InstallmentPlan(
        o.getString("id"), o.getString("lender"), o.str("merchant"), o.long("totalCents"), o.getString("currency"), o.int("count"),
        o.getJSONArray("installments").map { i -> Installment(i.getInt("seq"), i.str("dueDate")?.let(LocalDate::parse), i.long("cents"),
            InstallmentState.valueOf(i.getString("state")), i.str("paidOn")?.let(LocalDate::parse), i.str("movementId")) },
        o.str("purchaseDate")?.let(LocalDate::parse), o.str("categoryId"), o.str("fundingAccountId"), InstallmentSource.valueOf(o.getString("source")),
        o.getJSONArray("evidence").map { e -> Evidence(e.getString("ref"), LocalDate.parse(e.getString("date")), e.getString("summary")) },
        PlanReview.valueOf(o.getString("review")), o.getBoolean("extractedByAgent"), o.getString("updatedAt"),
        o.optJSONArray("aliases")?.let { a -> require(a.length() <= 10); (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    ) }

    fun encodeSync(s: List<InstallmentSync>): JSONArray = JSONArray().apply { s.forEach { put(JSONObject().put("source", it.source.name).put("lastScanAt", it.lastScanAt.orNull())
        .put("coverageFrom", it.coverageFrom?.toString().orNull()).put("coverageTo", it.coverageTo?.toString().orNull()).put("lastResult", it.lastResult.orNull())) } }

    fun decodeSync(a: JSONArray): List<InstallmentSync> = a.map { InstallmentSync(InstallmentSource.valueOf(it.getString("source")), it.str("lastScanAt"),
        it.str("coverageFrom")?.let(LocalDate::parse), it.str("coverageTo")?.let(LocalDate::parse), it.str("lastResult")) }

    fun validate(a: Archive) {
        require(a.installments.size <= 10_000 && a.installments.map { it.id }.distinct().size == a.installments.size) { "Piano a rate duplicato" }
        val allIds = a.installments.flatMap { listOf(it.id) + it.aliases }
        require(allIds.distinct().size == allIds.size) { "Piano a rate duplicato" }
        val categoryIds = a.planning.categories.map { it.id }.toSet()
        val accountIds = a.accounts.map { it.id }.toSet()
        val movementIds = a.externalMovements.map { it.id }.toSet()
        a.installments.forEach { p ->
            require(p.categoryId == null || p.categoryId in categoryIds) { "Categoria del piano a rate non disponibile" }
            require(p.fundingAccountId == null || p.fundingAccountId in accountIds) { "Conto del piano a rate non disponibile" }
            require(p.installments.all { it.movementId == null || it.movementId in movementIds }) { "Movimento della rata non disponibile" }
        }
        require(a.installmentSync.map { it.source }.distinct().size == a.installmentSync.size) { "Sincronizzazione rate duplicata" }
    }
}
