package xyz.grtlabs.pilastri.sync.gmail

import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.data.Evidence
import xyz.grtlabs.pilastri.data.Installment
import xyz.grtlabs.pilastri.data.InstallmentPaymentSeen
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview

/**
 * Turns the facts read from many mails into plans and paid notices. Facts are applied in date order, so a reminder followed by a payment
 * leaves the part PAID. Plan ids are stable across rescans: "<lender>:<order id>" when a mail gives one, else a hash of what identifies the
 * purchase (lender, merchant, purchase date, total). The scan never touches the archive; the host merges the result.
 *
 * Scan windows are read independently, so a mail often concerns a plan found in an earlier window. The host passes the plans it already has
 * as `known`: they are seeded as drafts (matched by lender key and order id, by alias, or, for a numberless paid notice or reminder, by
 * merchant and amount), so a cancellation closes their open parts, a paid notice without a part number lands on one of their open parts,
 * and nothing creates a stub next to them. A mail whose evidence the known plan already holds is not applied a second time, so a rescan
 * never undoes a correction the user made after it. A paid notice or reminder that states no part number and matches no plan stays
 * unmatched (counted): it never invents "part 1" of an empty plan.
 */
internal object PlanAssembler {
    data class Output(val plans: List<InstallmentPlan>, val payments: List<InstallmentPaymentSeen>, val unmatched: Int, val invalid: Int)

    private class PartDraft(var seq: Int, var due: LocalDate?, var cents: Long?, var state: InstallmentState, var paidOn: LocalDate?)

    private class Draft(val id: String, val lenderKeys: Set<String>, var label: String, val seeded: Boolean = false) {
        var merchant: String? = null
        var total: Long? = null
        var count: Int? = null
        var purchaseDate: LocalDate? = null
        var allAgent = true
        val parts = ArrayList<PartDraft>()
        val evidence = LinkedHashMap<String, Evidence>()
        val open get() = parts.filter { it.state == InstallmentState.DUE || it.state == InstallmentState.LATE }

        /** A seeded draft (a plan the archive already has) is only reported back when a mail of this scan changed it. */
        var touched = !seeded
        /** Evidence the archive's plan already holds: those mails were applied before and are not applied again. */
        var knownRefs: Set<String> = emptySet()
        /** Every id the archive's plan answers to, lower case, in both its own spelling and the "<lender>:<order id>" spelling. */
        var matchKeys: Set<String> = emptySet()
        /** Its own id and aliases as stored (lower case): a key outside this set matched through the other spelling and is worth remembering as an alias. */
        var exactKeys: Set<String> = emptySet()
        var currency = "EUR"
        /** The Gmail-spelled id that matched a plan with another id, so the archive can remember it as an alias. */
        var newAlias: String? = null
    }

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /** The lender key a plan id carries: "klarna:KL-1" and "paypal-pi3:7AB…" (the PayPal export's spelling) are "klarna" and "paypal". */
    private fun lenderKeyOfId(id: String) = id.substringBefore(':').substringBefore('-').lowercase()

    private fun gmailSpelling(id: String) = lenderKeyOfId(id) + ":" + id.substringAfter(':', "").lowercase()

    private fun seed(p: InstallmentPlan): Draft {
        val ids = listOf(p.id) + p.aliases
        return Draft(p.id, ids.map(::lenderKeyOfId).toSet(), p.lender, seeded = true).also { d ->
            d.merchant = p.merchant; d.total = p.totalCents; d.count = p.count; d.purchaseDate = p.purchaseDate; d.currency = p.currency
            p.installments.forEach { d.parts += PartDraft(it.seq, it.dueDate, it.cents, it.state, it.paidOn) }
            d.knownRefs = p.evidence.map { it.ref }.toSet()
            d.matchKeys = ids.flatMap { listOf(it.lowercase(), gmailSpelling(it)) }.toSet()
            d.exactKeys = ids.map { it.lowercase() }.toSet()
        }
    }

    private fun merchantsMatch(a: String?, b: String?): Boolean {
        val x = norm(a); val y = norm(b)
        return x.length >= 3 && y.length >= 3 && (x.contains(y) || y.contains(x))
    }

    /** "<lender>:h<20 hex>" from the fields that identify a purchase. Same inputs, same id, on every rescan. */
    internal fun hashId(lenderKey: String, vararg seed: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.joinToString("|") { it?.toString().orEmpty() }.toByteArray(Charsets.UTF_8))
        return "$lenderKey:h" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun build(facts: List<MailFact>, updatedAt: String, known: List<InstallmentPlan> = emptyList()): Output {
        val drafts = LinkedHashMap<String, Draft>()
        known.forEach { drafts.putIfAbsent(it.id, seed(it)) }
        val payments = LinkedHashMap<String, InstallmentPaymentSeen>()
        var unmatched = 0

        fun candidates(f: MailFact): List<Draft> {
            // A cancellation names its order: a merchant alone never closes a plan the archive already holds (it could be another order).
            val sameLender = drafts.values.filter { f.lender.key in it.lenderKeys && !(f.kind == FactKind.CANCELLED && it.seeded) }
            val byMerchant = sameLender.filter { f.merchant != null && it.merchant != null && merchantsMatch(f.merchant, it.merchant) }
            if (byMerchant.isNotEmpty()) return byMerchant
            val wanted = f.parts.mapNotNull { it.cents }
            val unknownMerchant = sameLender.filter { f.merchant == null || it.merchant == null }
            if (wanted.isEmpty()) return unknownMerchant
            return unknownMerchant.filter { d -> d.open.any { it.cents in wanted } || (d.total != null && d.total in wanted) || d.parts.any { it.cents in wanted } }
        }

        /** The plan the archive holds under this "<lender>:<order id>", directly or through an alias or the PayPal export's spelling. */
        fun findKnown(key: String): Draft? {
            val wanted = key.lowercase()
            return drafts.values.firstOrNull { it.seeded && wanted in it.matchKeys }?.also { d -> if (wanted !in d.exactKeys && key.length <= 200) d.newAlias = key }
        }

        fun newDraft(id: String, f: MailFact) = drafts.getOrPut(id) { Draft(id, setOf(f.lender.key), f.lenderLabel) }

        fun resolve(f: MailFact): Draft? {
            // A paid notice or reminder that numbers no part cannot say which part it is about: with no plan to attach to it is unmatched, not "part 1" of a new stub.
            val numberless = (f.kind == FactKind.PAID || f.kind == FactKind.REMINDER) && f.parts.none { it.seq != null }
            if (f.orderKey != null) {
                val key = "${f.lender.key}:${f.orderKey}"
                drafts[key]?.let { return it }
                findKnown(key)?.let { return it }
                return if (numberless) null else newDraft(key, f)
            }
            // A confirmation is a new purchase: it never attaches to another plan of the same lender and merchant.
            var found = if (f.kind == FactKind.PLAN) emptyList() else candidates(f)
            if (found.size > 1) {
                val wanted = f.parts.mapNotNull { it.cents }
                found = found.filter { d -> d.open.any { it.cents in wanted } }.ifEmpty { found }
            }
            if (found.size == 1) return found[0]
            if (found.size > 1) return null // ambiguous: better unmatched than attached to the wrong purchase
            return when (f.kind) {
                FactKind.PLAN -> newDraft(hashId(f.lender.key, norm(f.merchant), f.purchaseDate ?: f.date, f.totalCents), f)
                FactKind.PAID, FactKind.REMINDER -> {
                    val cents = f.parts.firstOrNull()?.cents
                    if (numberless || (cents == null && f.merchant == null)) null
                    else newDraft(hashId(f.lender.key, "stub", norm(f.merchant), cents, f.count), f)
                }
                FactKind.CANCELLED -> null
            }
        }

        fun nextFreeSeq(d: Draft): Int {
            var seq = 1
            while (d.parts.any { it.seq == seq }) seq++
            return seq
        }

        fun place(d: Draft, seq: Int, due: LocalDate?, cents: Long?, state: InstallmentState, paidOn: LocalDate?): PartDraft? {
            if (seq !in 1..120) return null
            val known = d.count
            if (known != null && seq > known) d.count = seq
            return PartDraft(seq, due, cents, state, paidOn).also { d.parts += it }
        }

        fun update(part: PartDraft, p: FactPart, mailDate: LocalDate) {
            if (part.state == InstallmentState.PAID) { part.cents = part.cents ?: p.cents; return }
            part.due = p.due ?: part.due
            part.cents = p.cents ?: part.cents
            when {
                p.paid -> { part.state = InstallmentState.PAID; part.paidOn = p.paidOn ?: mailDate }
                p.late && part.state != InstallmentState.CANCELLED -> part.state = InstallmentState.LATE
            }
        }

        fun upsert(d: Draft, p: FactPart, mailDate: LocalDate): PartDraft? {
            val state = when { p.paid -> InstallmentState.PAID; p.late -> InstallmentState.LATE; else -> InstallmentState.DUE }
            val paidOn = if (p.paid) (p.paidOn ?: mailDate) else null
            val existing = when {
                p.seq != null -> d.parts.firstOrNull { it.seq == p.seq }
                p.paid -> d.parts.firstOrNull { it.state == InstallmentState.PAID && it.paidOn == paidOn && (p.cents == null || it.cents == null || it.cents == p.cents) }
                    ?: d.open.filter { p.cents != null && it.cents == p.cents }.minByOrNull { it.seq }
                    ?: d.open.minByOrNull { it.seq }
                else -> d.parts.firstOrNull { p.due != null && it.due == p.due }
                    ?: d.open.filter { it.due == null && (p.cents == null || it.cents == p.cents) }.minByOrNull { it.seq }
            }
            if (existing != null) { update(existing, p, mailDate); return existing }
            if (p.seq == null && p.paid) return null // a payment that numbers no part and fits no open part is not "the next free part"
            val seq = p.seq ?: nextFreeSeq(d)
            val known = d.count
            if (p.seq == null && known != null && seq > known) return null // more parts than the plan has: not this plan
            return place(d, seq, p.due, p.cents, state, paidOn)
        }

        for (f in facts.sortedWith(compareBy({ it.date }, { it.msgId }))) {
            val d = resolve(f)
            if (d == null) { unmatched++; continue }
            val ref = "gmail:${f.msgId}"
            if (ref in d.knownRefs) continue // this mail was applied when the archive's plan recorded it: a rescan neither repeats nor undoes what followed
            val wasTouched = d.touched
            val wasAllAgent = d.allAgent
            d.touched = true
            d.allAgent = d.allAgent && f.byAgent
            if (f.kind == FactKind.PLAN) d.label = f.lenderLabel
            val addsEvidence = f.summary.isNotBlank() && ref.matches(Regex("[a-z]{2,12}:[A-Za-z0-9._:@-]{1,180}")) && ref !in d.evidence && d.evidence.size < 200
            if (addsEvidence) d.evidence[ref] = Evidence(ref, f.date, f.summary.take(200))
            var matched = true
            when (f.kind) {
                FactKind.PLAN -> {
                    d.merchant = d.merchant ?: f.merchant
                    d.total = d.total ?: f.totalCents
                    f.count?.let { c -> d.count = maxOf(d.count ?: 0, c) }
                    d.purchaseDate = d.purchaseDate ?: f.purchaseDate
                    for (p in f.parts) upsert(d, p, f.date)?.let { part ->
                        if (p.paid) emit(payments, d, part, d.evidence[ref], f.byAgent)
                    }
                }
                FactKind.REMINDER -> {
                    d.merchant = d.merchant ?: f.merchant
                    f.count?.let { c -> d.count = maxOf(d.count ?: 0, c) }
                    matched = false
                    for (p in f.parts) if (upsert(d, p, f.date) != null) matched = true
                }
                FactKind.PAID -> {
                    d.merchant = d.merchant ?: f.merchant
                    f.count?.let { c -> d.count = maxOf(d.count ?: 0, c) }
                    matched = false
                    for (p in f.parts) upsert(d, p, f.date)?.let { part -> matched = true; emit(payments, d, part, d.evidence[ref], f.byAgent) }
                }
                FactKind.CANCELLED -> if (!f.partial) d.parts.filter { it.state != InstallmentState.PAID }.forEach { it.state = InstallmentState.CANCELLED }
            }
            if (!matched) {
                // It fitted no part: the plan does not cite it, so a later scan (with more of the plan known) still reads it.
                unmatched++
                d.touched = wasTouched
                d.allAgent = wasAllAgent
                if (addsEvidence) d.evidence.remove(ref)
            }
        }

        var invalid = 0
        val plans = ArrayList<InstallmentPlan>()
        for (d in drafts.values.sortedBy { it.id }) {
            if (d.seeded && !d.touched) continue // the archive's plan, unchanged by this scan
            if (d.count == null && d.parts.isEmpty()) continue // only a cancellation or note: nothing to record
            val parts = d.parts.sortedBy { it.seq }
            val count = d.count?.let { maxOf(it, parts.size, parts.maxOfOrNull { p -> p.seq } ?: 0) }
            try {
                plans += InstallmentPlan(
                    id = d.id.take(200), lender = d.label.take(80), merchant = d.merchant?.take(120), totalCents = d.total, currency = d.currency, count = count,
                    installments = parts.map { Installment(it.seq, it.due, it.cents, it.state, it.paidOn.takeIf { _ -> it.state == InstallmentState.PAID }) },
                    purchaseDate = d.purchaseDate, source = InstallmentSource.GMAIL, evidence = d.evidence.values.toList(),
                    review = PlanReview.PENDING, extractedByAgent = d.allAgent, updatedAt = updatedAt, aliases = listOfNotNull(d.newAlias),
                )
            } catch (_: IllegalArgumentException) {
                invalid++
            }
        }
        val kept = plans.map { it.id }.toSet()
        return Output(plans, payments.values.filter { it.planId in kept }, unmatched, invalid)
    }

    private fun emit(out: MutableMap<String, InstallmentPaymentSeen>, d: Draft, part: PartDraft, evidence: Evidence?, byAgent: Boolean) {
        if (part.state != InstallmentState.PAID || evidence == null) return
        val paidOn = part.paidOn ?: return
        out.putIfAbsent("${d.id}#${part.seq}", InstallmentPaymentSeen(d.id, part.seq, paidOn, part.cents, evidence, byAgent))
    }
}
