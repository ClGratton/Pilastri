package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.domain.*

/* Derived views for the redone interface (Council 1 and 2, 2026-10-28). Pure functions over the archive: nothing here is stored. */

/** One amount put into a pillar: a personal contribution from outside, or an own transfer arriving from another pillar. */
data class Inflow(val date: LocalDate, val pillar: Int, val cents: Long, val fromPillar: Int?, val recordId: String)

/**
 * Gross money put into each pillar, not netted against withdrawals. A transfer counts where it arrives; a chain
 * P1 → P2 → P4 is counted once in the total, because only transfers leaving P1 (where salary lands) and personal
 * contributions from outside enter it.
 */
fun Archive.inflows(): List<Inflow> {
    val pillarOf = accounts.associate { it.id to it.pillar }
    val own = events.filter { it.type == EventType.PERSONAL_CONTRIBUTION }.map { Inflow(it.date, it.pillar, it.amount.cents, null, it.id) }
    val moved = transfers.mapNotNull { t ->
        val from = pillarOf[t.fromAccountId] ?: return@mapNotNull null
        val to = pillarOf[t.toAccountId] ?: return@mapNotNull null
        if (from == to) null else Inflow(t.date, to, t.amount.cents, from, t.id)
    }
    // A purchase booked on a broker's securities account but paid from its cash account in another pillar (TR cash -> PAC).
    val cashLeg = externalMovements.filter { it.status == ExternalStatus.APPLIED }.associate { it.linkedRecordId to it.accountId }
    val funded = trades.filter { it.side == TradeSide.BUY }.mapNotNull { t ->
        val from = cashLeg[t.id]?.let(pillarOf::get) ?: return@mapNotNull null
        val to = pillarOf[t.accountId] ?: return@mapNotNull null
        if (from == to) null else Inflow(t.date, to, t.grossAmount.cents, from, t.id)
    }
    return own + moved + funded
}

fun Archive.versatoInto(pillar: Int): List<Inflow> = inflows().filter { it.pillar == pillar }

/** The total counts outside money once: personal contributions plus transfers that leave the liquidity pillar. */
fun Archive.versatoTotal(): List<Inflow> = inflows().filter { (it.fromPillar == null && it.pillar != 1) || it.fromPillar == 1 }

/**
 * The real-euro card (Council 2 ruling): the headline is the indexed part in January-2026 euros; later months wait for
 * ISTAT and stay nominal on their own line; older months without a compatible index are counted, never guessed.
 */
data class RealCard(
    val indexedRealCents: Long?, val indexedCount: Int, val totalCount: Int, val indexedThrough: YearMonth?,
    val awaiting: List<Pair<YearMonth, Long>>, val unindexedHistoricalCount: Int, val nominalTotalCents: Long,
    val employerCents: Long?,
)

fun Archive.realCard(flows: List<Inflow> = versatoTotal()): RealCard {
    val index = indices.associateBy { it.month }
    val base = index[YearMonth.of(2026, 1)]?.value
    val lastIndexed = indices.maxOfOrNull { it.month }
    var real = 0L; var indexed = 0; var historical = 0
    val awaiting = sortedMapOf<YearMonth, Long>()
    flows.forEach { f ->
        val month = YearMonth.from(f.date)
        val point = index[month]?.value
        val value = if (base != null && point != null) Finance.inBaseYear(Money(f.cents), base, point) else null
        when {
            value != null -> { real = Math.addExact(real, value.cents); indexed++ }
            lastIndexed != null && month.isAfter(lastIndexed) -> awaiting[month] = Math.addExact(awaiting[month] ?: 0L, f.cents)
            lastIndexed == null && month == YearMonth.from(LocalDate.now()) -> awaiting[month] = Math.addExact(awaiting[month] ?: 0L, f.cents)
            else -> historical++
        }
    }
    val employer = events.filter { it.type == EventType.EMPLOYER_CONTRIBUTION }.takeIf { it.isNotEmpty() }?.fold(0L) { s, e -> Math.addExact(s, e.amount.cents) }
    return RealCard(if (indexed > 0) real else null, indexed, flows.size, lastIndexed, awaiting.toList(), historical,
        flows.fold(0L) { s, f -> Math.addExact(s, f.cents) }, employer)
}

/**
 * A pillar's current value: its own reported/documented snapshot, or the sum of every account in the pillar when each
 * has a dated provider or reported valuation. A pillar with an account lacking a value is unknown, never partial.
 */
sealed interface PillarValue {
    data class Snapshot(val cents: Long, val date: LocalDate, val source: String) : PillarValue
    data class Composed(val cents: Long, val oldest: LocalDate, val newest: LocalDate, val parts: List<Pair<Account, AccountValuation>>) : PillarValue
    data class Unknown(val reason: String) : PillarValue
}

fun Archive.pillarValue(pillar: Int, asOf: LocalDate? = null): PillarValue {
    // [asOf]: the value as it was known on that day (records up to it); null = today, every record.
    fun upTo(d: LocalDate) = asOf == null || !d.isAfter(asOf)
    val snapshot = valuations.filter { it.pillar == pillar && Finance.isActual(it) && upTo(it.date) }.maxByOrNull { it.date }
        ?.let { PillarValue.Snapshot(it.amount.cents, it.date, it.source) }
    val inPillar = accounts.filter { it.pillar == pillar }
    val parts = inPillar.map { a -> a to accountValuations.filter { it.accountId == a.id && upTo(it.date) }.maxByOrNull { it.date } }
    val composed = if (inPillar.isNotEmpty() && parts.all { it.second != null }) parts.map { it.first to it.second!! }.let { ps ->
        PillarValue.Composed(ps.fold(0L) { s, (_, v) -> Math.addExact(s, v.amount.cents) }, ps.minOf { it.second.date }, ps.maxOf { it.second.date }, ps)
    } else null
    return when {
        composed != null && snapshot != null -> if (snapshot.date.isAfter(composed.newest)) snapshot else composed
        composed != null -> composed
        snapshot != null -> snapshot
        inPillar.isNotEmpty() -> PillarValue.Unknown(parts.first { it.second == null }.first.name + ": nessun valore")
        else -> PillarValue.Unknown("Nessun valore registrato")
    }
}

fun PillarValue.centsOrNull() = when (this) { is PillarValue.Snapshot -> cents; is PillarValue.Composed -> cents; is PillarValue.Unknown -> null }
fun PillarValue.oldestDate() = when (this) { is PillarValue.Snapshot -> date; is PillarValue.Composed -> oldest; is PillarValue.Unknown -> null }

/** "Attività": the sum of four known pillars, labelled with its oldest component date; otherwise which pillars are missing. */
data class AssetsTotal(val cents: Long?, val known: Int, val oldest: LocalDate?, val missing: List<Int>)

fun Archive.assetsTotal(): AssetsTotal {
    val values = (1..4).associateWith { pillarValue(it) }
    val missing = values.filter { it.value is PillarValue.Unknown }.keys.toList()
    val oldest = values.values.mapNotNull { it.oldestDate() }.minOrNull()
    val cents = if (missing.isEmpty()) values.values.fold(0L) { s, v -> Math.addExact(s, v.centsOrNull()!!) } else null
    return AssetsTotal(cents, 4 - missing.size, oldest, missing)
}

/** Month spending per Council 2 definitions; [coverageTo] is the last date the bank data covers. */
data class MonthSpending(
    val month: YearMonth, val entrateCents: Long, val spesaCents: Long, val byCategory: Map<String?, Long>,
    val pending: Int, val coverageTo: LocalDate?,
    /** The latest start among the sources: the month is partial before it. */
    val coverageFrom: LocalDate? = null,
) {
    val risparmiatoCents get() = entrateCents - spesaCents
    /** Savings rate = (Entrate − Spesa) ÷ Entrate; null without income. */
    val savingsRate: Double? get() = if (entrateCents > 0) risparmiatoCents.toDouble() / entrateCents else null
}

private val payoutOnlyProviders = setOf(ImportProvider.PAYPAL, ImportProvider.TRADE_REPUBLIC)

private val refundText = Regex("(?i)\\b(rimbors\\w*|refund\\w*|storn\\w*|reversal|chargeback|restituzion\\w*|annullament\\w*)\\b|CARD_(TR_)?REFUND")

/** A money-back row (PayPal refund, card refund, bank "storno"): imported as OTHER, never income. */
fun ExternalMovement.isRefund(): Boolean = signedCents > 0 && kind == ExternalKind.OTHER && (refundText.containsMatchIn(rawType.orEmpty()) || refundText.containsMatchIn(description))

private val notSpending = setOf(MovementRole.OWN_TRANSFER, MovementRole.FUND_CONTRIBUTION, MovementRole.SALARY, MovementRole.INCOME)

fun Archive.monthSpending(month: YearMonth): MonthSpending {
    val liquidity = accounts.filter { it.pillar == 1 }.map { it.id }.toSet()
    val inMonth = externalMovements.filter { it.accountId in liquidity && YearMonth.from(it.date) == month }
    val transferIds = transfers.map { it.id }.toSet()
    // A suggested own-account pair (bank "PAYPAL" debit ↔ PayPal funding) is not spending even before review: the purchase is the PayPal row.
    val suggestedTransfers = transferPairs().keys
    val counted = inMonth.filter { it.status != ExternalStatus.IGNORED && it.linkedRecordId !in transferIds && it.id !in suggestedTransfers && it.kind !in setOf(ExternalKind.BUY, ExternalKind.SELL) }
    val salary = planning.incomeRules
    val entrate = counted.filter { it.signedCents > 0 && (it.role == MovementRole.SALARY || it.role == MovementRole.INCOME || salary.any { r -> r.matches(it) }) }.sumOf { it.signedCents }
    // PayPal and Trade Republic only pay out to the user's own bank account: such a withdrawal is never spending.
    val spend = counted.filter { it.signedCents < 0 && it.role !in notSpending && !(it.kind == ExternalKind.WITHDRAWAL && it.provider in payoutOnlyProviders) }
    // Installment parts count in their purchase's category (InstallmentCategories.kt).
    val parts = installmentPaymentStates()
    fun categoryOf(m: ExternalMovement) = spendCategoryOf(m, parts)
    // A refund nets against the purchase's category (the latest same-account outflow of at least that amount in the last 90 days).
    val refunds = counted.filter { it.isRefund() }.map { r ->
        val purchase = externalMovements.filter { it.accountId == r.accountId && it.signedCents < 0 && -it.signedCents >= r.signedCents && !it.date.isAfter(r.date) && !it.date.isBefore(r.date.minusDays(90)) }
            .maxByOrNull { it.date }
        (r.categoryId ?: purchase?.let(::categoryOf)) to r.signedCents
    }
    val gross = spend.groupBy(::categoryOf).mapValues { (_, rows) -> -rows.sumOf { it.signedCents } }
    val byCategory = (gross.keys + refunds.map { it.first }).associateWith { k -> ((gross[k] ?: 0L) - refunds.filter { it.first == k }.sumOf { it.second }).coerceAtLeast(0L) }
        .filterValues { it > 0 }
    // Complete only up to the earliest end among the liquidity sources, and only from the latest start.
    val feeds = connections.filter { it.accountId in liquidity }
    val coverage = feeds.mapNotNull { it.coverageTo }.minOrNull()
    val coverageFrom = feeds.mapNotNull { it.coverageFrom }.maxOrNull()
    return MonthSpending(month, entrate, byCategory.values.sum(), byCategory, inMonth.count { it.status == ExternalStatus.PENDING }, coverage, coverageFrom)
}

/** Deterministic briefing items, ranked; the UI shows at most three after the status line. */
enum class BriefingKind { SALARY, REVIEW, INSTALLMENT_DUE, INSTALLMENT_REVIEW, SESSION_EXPIRED, STALE_VALUE, STALE_BANK, DEDUCTION }
data class BriefingItem(val kind: BriefingKind, val key: String, val cents: Long? = null, val date: LocalDate? = null, val subject: String? = null, val count: Int? = null,
    /** The item comes from a plan the user has not confirmed yet ("da confermare"). */
    val pendingReview: Boolean = false)

fun Archive.briefing(today: LocalDate = LocalDate.now()): List<BriefingItem> {
    val items = mutableListOf<BriefingItem>()
    externalMovements.filter { m -> m.signedCents > 0 && ChronoUnit.DAYS.between(m.date, today) in 0..5 && (m.role == MovementRole.SALARY || planning.incomeRules.any { it.matches(m) }) }
        .maxByOrNull { it.date }?.let { items += BriefingItem(BriefingKind.SALARY, "salary:${it.id}", cents = it.signedCents, date = it.date) }
    val pending = externalMovements.count { it.status == ExternalStatus.PENDING }
    if (pending > 0) items += BriefingItem(BriefingKind.REVIEW, "review", count = pending)
    // Installment parts due in the next week, one card per part; plans still to confirm, one card for all.
    dueInstallments(today, today.plusDays(7)).forEach { d ->
        items += BriefingItem(BriefingKind.INSTALLMENT_DUE, "rata:${d.plan.id}:${d.part.seq}", cents = d.part.cents, date = d.part.dueDate, subject = d.plan.merchant ?: d.plan.lender, count = d.part.seq,
            pendingReview = d.plan.review == PlanReview.PENDING)
    }
    val plansToReview = installments.count { it.review == PlanReview.PENDING }
    if (plansToReview > 0) items += BriefingItem(BriefingKind.INSTALLMENT_REVIEW, "rate:review", count = plansToReview)
    connections.filter { it.lastResult?.contains("scadut", ignoreCase = true) == true }
        .forEach { items += BriefingItem(BriefingKind.SESSION_EXPIRED, "session:${it.provider}", subject = it.provider.label) }
    accounts.forEach { a ->
        val last = accountValuations.filter { it.accountId == a.id }.maxByOrNull { it.date } ?: return@forEach
        if (ChronoUnit.DAYS.between(last.date, today) > 35) items += BriefingItem(BriefingKind.STALE_VALUE, "stale:${a.id}", date = last.date, subject = a.name)
    }
    connections.filter { it.provider == ImportProvider.ISYBANK }.forEach { c ->
        val to = c.coverageTo ?: return@forEach
        if (ChronoUnit.DAYS.between(to, today) > 35) items += BriefingItem(BriefingKind.STALE_BANK, "bank:${c.accountId}", date = to, subject = ImportProvider.ISYBANK.label)
    }
    if (today.monthValue >= 9) pension.deductionYears.firstOrNull { it.taxYear == today.year }
        ?.let { items += BriefingItem(BriefingKind.DEDUCTION, "deduction:${it.taxYear}", cents = it.ceilingCents, subject = it.source) }
    return items.sortedBy { it.kind.ordinal }
}

/**
 * The review queue (Council 1 A5, Council 2 M4): pending movements grouped by suggested effect with count and total.
 * Possible transfers, possible duplicates of a manual record, and anything without a suggestion are listed one by one
 * and never offered to "Applica" as a group.
 */
data class ReviewGroup(val decision: ExternalDecision?, val label: String, val movements: List<ExternalMovement>, val bulk: Boolean) {
    val count get() = movements.size
    /** A transfer pair moves its amount once. */
    val totalCents get() = if (decision is ExternalDecision.AsTransfer) movements.maxOf { kotlin.math.abs(it.signedCents) } else movements.sumOf { kotlin.math.abs(it.signedCents) }
}

fun Archive.reviewQueue(): List<ReviewGroup> {
    val pending = externalMovements.filter { it.status == ExternalStatus.PENDING }
    val pairs = transferPairs()
    val duplicates = pending.filter { m ->
        events.any { e -> e.accountId == m.accountId && e.amount.cents == kotlin.math.abs(m.signedCents) && kotlin.math.abs(e.date.toEpochDay() - m.date.toEpochDay()) <= 3 && !e.id.startsWith("x:") }
    }.map { it.id }.toSet()
    val single = mutableListOf<ReviewGroup>()
    val grouped = LinkedHashMap<String, MutableList<ExternalMovement>>()
    val decisions = HashMap<String, ExternalDecision?>()
    pending.forEach { m ->
        val (decision, wait) = suggestedDecisionFor(m, pairs)
        when {
            m.id in duplicates -> single += ReviewGroup(decision, "Possibile duplicato", listOf(m), bulk = false)
            decision is ExternalDecision.AsTransfer -> { if (pairs[m.id]?.let { cp -> cp < m.id && pending.any { it.id == cp } } != true) single += ReviewGroup(decision, "Possibile trasferimento tra tuoi conti", listOfNotNull(m, externalMovements.firstOrNull { it.id == decision.counterpartId }), bulk = false) }
            wait == SuggestionWait.AWAITING_BANK_EXPORT -> grouped.getOrPut("wait") { mutableListOf() }.add(m).also { decisions["wait"] = null }
            decision == null -> grouped.getOrPut("none") { mutableListOf() }.add(m).also { decisions["none"] = null }
            else -> { val key = decision.toString(); grouped.getOrPut(key) { mutableListOf() }.add(m); decisions[key] = decision }
        }
    }
    val groups = grouped.map { (key, rows) ->
        val label = when (key) { "wait" -> "In attesa dell'export isybank"; "none" -> "Da classificare"; else -> describe(decisions[key]) }
        ReviewGroup(decisions[key], label, rows, bulk = decisions[key] != null && decisions[key] !is ExternalDecision.AsTrade)
    }
    return groups + single
}

private fun describe(d: ExternalDecision?): String = when (d) {
    is ExternalDecision.AsEvent -> when (d.type) {
        EventType.PERSONAL_CONTRIBUTION -> "Versamento tuo"
        EventType.EMPLOYER_CONTRIBUTION -> "Contributo datore"
        EventType.WITHDRAWAL -> "Uscita"
        EventType.INCOME -> "Provento"
        EventType.FEE -> "Costo o imposta"
        else -> "Movimento"
    }
    is ExternalDecision.AsTrade -> if (d.side == TradeSide.BUY) "Acquisto titoli" else "Vendita titoli"
    is ExternalDecision.AsTransfer -> "Trasferimento tra tuoi conti"
    ExternalDecision.Ignore -> "Da ignorare"
    null -> "Da classificare"
}

/**
 * Spese "Da controllare · N" (Council 3 §5): only uncategorized pending items that are possible duplicates, possible own
 * transfers (a pair counts once), or that the assistant held back as ambiguous ([heldAmbiguous], by movement id). What the
 * assistant categorized ("suggerita") and what it has not looked at yet are not counted.
 */
data class ToCheck(val duplicates: Int, val transfers: Int, val ambiguous: Int) {
    val count get() = duplicates + transfers + ambiguous
}

fun Archive.toCheck(heldAmbiguous: (String) -> Boolean = { false }): ToCheck {
    val q = reviewQueue()
    fun open(m: ExternalMovement) = m.status == ExternalStatus.PENDING && m.categoryId == null
    val dups = q.filter { it.label == "Possibile duplicato" && it.movements.any(::open) }
    val transfers = q.filter { it.label != "Possibile duplicato" && it.decision is ExternalDecision.AsTransfer && it.movements.any(::open) }
    val grouped = (dups + transfers).flatMap { g -> g.movements.map { it.id } }.toSet() + transferPairs().keys
    val ambiguous = externalMovements.count { open(it) && it.signedCents < 0 && it.id !in grouped && planning.categorize(it).first == null && heldAmbiguous(it.id) }
    return ToCheck(dups.size, transfers.size, ambiguous)
}

/** A confirmed recurring income credit that just arrived: the rule titles the card (Stipendio, Pensione, Compenso, Accredito). */
data class IncomeArrival(val rule: IncomeRule, val movement: ExternalMovement)

/**
 * Council 4 d: the income card fires only for a confirmed recurring [IncomeRule] matching a credit in its window, within the
 * last [days] days; one at a time (the latest). A credit no rule matches (invoices, one-offs) never opens a card.
 */
fun Archive.incomeArrival(today: LocalDate, days: Long = 5): IncomeArrival? =
    externalMovements.asSequence()
        .filter { it.signedCents > 0 && it.status != ExternalStatus.IGNORED && ChronoUnit.DAYS.between(it.date, today) in 0..days }
        .mapNotNull { m -> planning.incomeRules.firstOrNull { it.confirmedAt != null && it.matches(m) }?.let { IncomeArrival(it, m) } }
        .maxByOrNull { it.movement.date }

/**
 * Complementary-pension contributions of [year] per (fund, month) for the deduction ceiling: worker and employer apart, TFR
 * never. A side with no record that month is null (not known), so the person-year total reads n/d rather than a partial sum.
 */
fun Archive.fundContributions(year: Int): List<xyz.grtlabs.pilastri.domain.rules.FundContribution> =
    accounts.filter { it.type == AccountType.PENSION_FUND || it.fundId != null }.flatMap { acc ->
        events.filter { it.accountId == acc.id && it.date.year == year && (it.type == EventType.PERSONAL_CONTRIBUTION || it.type == EventType.EMPLOYER_CONTRIBUTION) }
            .groupBy { YearMonth.from(it.date) }.map { (m, rows) ->
                fun sum(t: EventType) = rows.filter { it.type == t }.takeIf { it.isNotEmpty() }?.sumOf { it.amount.cents }
                xyz.grtlabs.pilastri.domain.rules.FundContribution(acc.fundId ?: "account:${acc.id}", m, sum(EventType.PERSONAL_CONTRIBUTION), sum(EventType.EMPLOYER_CONTRIBUTION))
            }
    }
