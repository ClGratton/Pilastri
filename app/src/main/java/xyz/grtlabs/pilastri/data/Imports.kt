package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.*

/**
 * The providers this app version has connectors for. The archive stores a string `providerId` (v14), so a record from a provider
 * a newer version adds survives here verbatim ([UnknownProviderRows]) instead of being dropped.
 */
enum class ImportProvider(val label: String) {
    TRADE_REPUBLIC("Trade Republic"), ISYBANK("isybank"), PAYPAL("PayPal");

    /** The stored `providerId`: the constant name, as archives up to v13 stored it, so existing ids never change. */
    val id: String get() = name

    companion object {
        fun byId(id: String): ImportProvider? = entries.firstOrNull { it.id == id }
    }
}

/** What the provider says a movement is; a hint for review, never an accounting decision by itself. */
enum class ExternalKind { DEPOSIT, WITHDRAWAL, CARD_PAYMENT, BUY, SELL, DIVIDEND, INTEREST, TAX, FEE, BONUS, OTHER }

enum class ExternalStatus { PENDING, APPLIED, IGNORED }

/**
 * A provider movement kept as imported. It affects contributions, flows or trades only after it is
 * applied, and then through the ordinary ledger record named by [linkedRecordId].
 */
data class ExternalMovement(
    val id: String,
    val provider: ImportProvider,
    val accountId: String,
    val date: LocalDate,
    val signedCents: Long,
    val currency: String,
    val description: String,
    val kind: ExternalKind,
    val rawType: String?,
    val isin: String?,
    val quantity: BigDecimal?,
    val status: ExternalStatus,
    val linkedRecordId: String?,
    val importedAt: String,
    /** Spending/income category: the provider's own label at import, later the user's or the assistant's choice. */
    val category: String? = null,
    /** The user's taxonomy, with where the choice came from (precedence in Planning.categorize). */
    val categoryId: String? = null,
    val categorySource: CategorySource? = null,
    val merchantKey: String? = null,
    val role: MovementRole? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 200 && accountId.isNotBlank() && accountId.length <= 100)
        require((categoryId == null) == (categorySource == null) && (merchantKey?.length ?: 0) <= 120)
        require(currency.length == 3 && description.length <= 500 && (rawType?.length ?: 0) <= 100)
        require(isin == null || isin.matches(Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")))
        require(quantity == null || (quantity.signum() > 0 && quantity.scale() <= 12))
        require((status == ExternalStatus.APPLIED) == (linkedRecordId != null))
        require(importedAt.isNotBlank() && importedAt.length <= 50)
        require(category == null || (category.isNotBlank() && category.length <= 60))
    }
}

/** Sync bookkeeping only. Sessions, cookies and passwords never enter the archive or backups. */
data class Connection(
    val provider: ImportProvider,
    val accountId: String,
    val lastSyncAt: String?,
    val coverageFrom: LocalDate?,
    val coverageTo: LocalDate?,
    val lastResult: String?,
    /** A broker holding cash and securities apart (Council 2 AccountSleeve): [accountId] holds the cash, this account the securities. */
    val securitiesAccountId: String? = null,
) {
    init { require(accountId.isNotBlank() && (lastResult?.length ?: 0) <= 500 && securitiesAccountId != accountId) }
}

/** One provider-side movement, normalized by a connector before review. */
data class ImportedMovement(
    val externalId: String,
    val date: LocalDate,
    val signedCents: Long,
    val currency: String,
    val description: String,
    val kind: ExternalKind,
    val rawType: String? = null,
    val isin: String? = null,
    val quantity: BigDecimal? = null,
    /** The provider's own category label when it has one (isybank "categoria", card merchant category). */
    val category: String? = null,
)

data class ImportedBalance(val date: LocalDate, val cents: Long)

/** One priced position as the provider reports it; [priceCents] null when no quote was read. */
data class ImportedPosition(val isin: String, val name: String, val quantity: BigDecimal, val priceCents: Long?)

/** What a connector hands over. It is shown in a preview and written only after confirmation. */
data class ImportBatch(
    val provider: ImportProvider,
    val accountLabel: String,
    val fetchedAt: Instant,
    val coverageFrom: LocalDate?,
    val coverageTo: LocalDate?,
    val movements: List<ImportedMovement>,
    /** Account value reported by the provider (cash plus securities where the provider includes them). */
    val balance: ImportedBalance? = null,
    val warnings: List<String> = emptyList(),
    /** The same value split by sleeve, each reported only when complete; used when the cash and securities are separate accounts. */
    val cashBalance: ImportedBalance? = null,
    val securitiesBalance: ImportedBalance? = null,
    val positions: List<ImportedPosition> = emptyList(),
    /** Installment plans the provider shows (PayPal Pay in 3), merged with the movements in the same save. */
    val installmentPlans: List<InstallmentPlan> = emptyList(),
    val installmentPayments: List<InstallmentPaymentSeen> = emptyList(),
)

data class ImportOutcome(val archive: Archive, val added: Int, val alreadyPresent: Int, val skippedForeign: Int, val valuationAdded: Boolean, val plansAdded: Int = 0, val plansUpdated: Int = 0)

fun externalId(provider: ImportProvider, externalId: String) = "${provider.name}:$externalId"

/**
 * Adds new provider movements as PENDING, keeping earlier review decisions untouched, and records the
 * provider balance as a dated document valuation unless a different record already holds that date.
 *
 * With a [securitiesAccountId] (or one remembered from an earlier sync), the cash balance goes to [accountId] and the
 * securities balance and positions to that account; trades reviewed later are booked there too.
 */
fun Archive.withImportBatch(batch: ImportBatch, accountId: String, at: String = Instant.now().toString(), securitiesAccountId: String? = null): ImportOutcome {
    val account = accounts.singleOrNull { it.id == accountId } ?: throw IllegalArgumentException("Il conto non è più disponibile.")
    val securitiesId = securitiesAccountId ?: connections.firstOrNull { it.provider == batch.provider && it.accountId == account.id }?.securitiesAccountId
    require(securitiesId == null || (securitiesId != account.id && accounts.any { it.id == securitiesId })) { "Il conto titoli non è disponibile." }
    val known = externalMovements.associateBy { it.id }
    var present = 0
    var foreign = 0
    val additions = batch.movements.mapNotNull { m ->
        val id = externalId(batch.provider, m.externalId)
        val existing = known[id]
        when {
            existing != null && existing.accountId == account.id -> { present++; null }
            existing != null -> { foreign++; null }
            m.date.isAfter(LocalDate.now()) || m.currency != "EUR" || m.signedCents == 0L -> { foreign++; null }
            else -> ExternalMovement(id, batch.provider, account.id, m.date, m.signedCents, m.currency,
                m.description.take(500), m.kind, m.rawType?.take(100), m.isin, m.quantity, ExternalStatus.PENDING, null, at,
                m.category?.trim()?.take(60)?.ifBlank { null }, merchantKey = merchantKey(m.description).take(120).ifBlank { null })
        }
    }.distinctBy { it.id }
    val source = "${batch.provider.label} · sincronizzazione ${batch.fetchedAt}"
    var valuations = accountValuations
    var written = false
    fun record(targetId: String, value: ImportedBalance?) {
        val balance = value?.takeIf { it.cents >= 0 && !it.date.isAfter(LocalDate.now()) } ?: return
        val sameDay = valuations.firstOrNull { it.accountId == targetId && it.date == balance.date }
        if (sameDay != null && !sameDay.source.startsWith(batch.provider.label + " · ")) return
        valuations = valuations.filterNot { it == sameDay } + AccountValuation(targetId, balance.date, Money(balance.cents), source.take(500), Confidence.DOCUMENT)
        written = true
    }
    if (securitiesId == null) record(account.id, batch.balance) else { record(account.id, batch.cashBalance); record(securitiesId, batch.securitiesBalance) }
    val holdingAccount = securitiesId ?: account.id
    val positionDate = (batch.securitiesBalance ?: batch.balance)?.date ?: batch.fetchedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val newHoldings = batch.positions.filter { it.isin.matches(Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")) && it.quantity.signum() > 0 }.distinctBy { it.isin }
        .map { Holding(holdingAccount, it.isin, it.name.take(200), it.quantity, it.priceCents?.takeIf { c -> c >= 0 }, positionDate, source.take(500)) }
    val holdingsNext = if (newHoldings.isEmpty()) holdings else holdings.filterNot { it.accountId == holdingAccount && it.date == positionDate } + newHoldings
    val previous = connections.firstOrNull { it.provider == batch.provider && it.accountId == account.id }
    val (coverageFrom, coverageTo) = mergeCoverage(previous?.coverageFrom, previous?.coverageTo, batch.coverageFrom, batch.coverageTo)
    val connection = Connection(batch.provider, account.id, at, coverageFrom, coverageTo,
        "${additions.size} nuovi, $present già presenti" + if (foreign > 0) ", $foreign esclusi" else "", securitiesId)
    val withMovements = copy(
        externalMovements = externalMovements + additions,
        accountValuations = valuations,
        holdings = holdingsNext,
        connections = connections.filterNot { it.provider == batch.provider && it.accountId == account.id } + connection,
    )
    val plans = withMovements.withInstallmentPlans(batch.installmentPlans, at)
    val paidFromSeen = batch.installmentPayments.fold(plans.archive) { a, seen -> a.withInstallmentPaid(seen, at) }
    // PayPal's activity shows each collected Pay in 3 part as its own outflow, apart from the purchase row that became the plan.
    val next = if (batch.provider == ImportProvider.PAYPAL) paidFromSeen.withPaypalPlanPartMovements(at).withPaypalInstallmentPayments(at) else paidFromSeen
    return ImportOutcome(next, additions.size, present, foreign, written, plans.added, plans.updated)
}

/**
 * Coverage is a claim that every movement in the window is present. Windows that overlap or touch become one; a disjoint
 * import (an older, shorter file) never moves the claim backwards, so the more recent window is kept.
 */
internal fun mergeCoverage(oldFrom: LocalDate?, oldTo: LocalDate?, newFrom: LocalDate?, newTo: LocalDate?): Pair<LocalDate?, LocalDate?> = when {
    oldFrom == null || oldTo == null -> newFrom to newTo
    newFrom == null || newTo == null -> oldFrom to oldTo
    !newFrom.isAfter(oldTo.plusDays(1)) && !oldFrom.isAfter(newTo.plusDays(1)) -> minOf(oldFrom, newFrom) to maxOf(oldTo, newTo)
    oldTo.isAfter(newTo) -> oldFrom to oldTo
    else -> newFrom to newTo
}

/** How a reviewed movement enters the ledger. */
sealed interface ExternalDecision {
    data class AsEvent(val type: EventType) : ExternalDecision
    data class AsTrade(val side: TradeSide, val instrumentName: String) : ExternalDecision
    /** Money moved between two of the user's own accounts: one AccountTransfer, never a contribution. */
    data class AsTransfer(val counterpartId: String) : ExternalDecision
    data object Ignore : ExternalDecision
}

/**
 * The default reading of a provider kind; OTHER always waits for the user. A deposit has no default here:
 * salary is income, not a contribution, and money from the user's own accounts is a transfer (Council 2026-10-28).
 * Use [suggestedDecisionFor], which knows the other connections.
 */
fun ExternalMovement.suggestedDecision(): ExternalDecision? = when (kind) {
    ExternalKind.DEPOSIT -> null
    ExternalKind.WITHDRAWAL, ExternalKind.CARD_PAYMENT -> if (signedCents < 0) ExternalDecision.AsEvent(EventType.WITHDRAWAL) else null
    ExternalKind.BUY -> if (isin != null && quantity != null) ExternalDecision.AsTrade(TradeSide.BUY, description) else null
    ExternalKind.SELL -> if (isin != null && quantity != null) ExternalDecision.AsTrade(TradeSide.SELL, description) else null
    ExternalKind.DIVIDEND, ExternalKind.INTEREST, ExternalKind.BONUS -> if (signedCents > 0) ExternalDecision.AsEvent(EventType.INCOME) else null
    ExternalKind.TAX, ExternalKind.FEE -> if (signedCents < 0) ExternalDecision.AsEvent(EventType.FEE) else null
    ExternalKind.OTHER -> null
}

/** Applies one review decision; the created record carries the provider source and the movement's stable id. */
fun Archive.withExternalDecision(movementId: String, decision: ExternalDecision, at: String = Instant.now().toString()): Archive {
    val movement = externalMovements.singleOrNull { it.id == movementId } ?: throw IllegalArgumentException("Il movimento importato non è più disponibile.")
    require(movement.status == ExternalStatus.PENDING) { "Il movimento è già stato revisionato." }
    val account = accounts.singleOrNull { it.id == movement.accountId } ?: throw IllegalArgumentException("Il conto non è più disponibile.")
    val source = "${movement.provider.label} · ${movement.description}".take(500)
    val amount = Money(Math.absExact(movement.signedCents))
    return when (decision) {
        ExternalDecision.Ignore -> replaceExternal(movement.copy(status = ExternalStatus.IGNORED))
        is ExternalDecision.AsEvent -> {
            require(decision.type != EventType.EMPLOYER_CONTRIBUTION || account.pillar == 4) { "Il contributo del datore appartiene al pilastro 4." }
            val inflowTypes = setOf(EventType.PERSONAL_CONTRIBUTION, EventType.EMPLOYER_CONTRIBUTION, EventType.INCOME)
            val outflowTypes = setOf(EventType.WITHDRAWAL, EventType.FEE)
            require(!(decision.type in inflowTypes && movement.signedCents < 0) && !(decision.type in outflowTypes && movement.signedCents > 0)) {
                "Il tipo scelto non corrisponde al segno del movimento."
            }
            val event = LedgerEvent("x:${movement.id}", movement.date, account.pillar, decision.type, amount, Confidence.DOCUMENT, source, accountId = account.id)
            require(events.none { it.id == event.id }) { "Il movimento è già registrato." }
            copy(events = events + event).replaceExternal(movement.copy(status = ExternalStatus.APPLIED, linkedRecordId = event.id))
        }
        is ExternalDecision.AsTransfer -> {
            val other = externalMovements.singleOrNull { it.id == decision.counterpartId } ?: throw IllegalArgumentException("Il movimento corrispondente non è più disponibile.")
            val reviewedAsEvent = other.status == ExternalStatus.APPLIED && events.any { it.id == other.linkedRecordId }
            if (reviewedAsEvent && other.accountId != movement.accountId) {
                // The counterpart was applied alone (an "Uscita" or a "Versamento"): undo that, then record the transfer.
                return withExternalReopened(other.id, at).withExternalDecision(movementId, decision, at)
            }
            require(other.status == ExternalStatus.PENDING && other.accountId != movement.accountId) { "Il movimento corrispondente non è utilizzabile." }
            require(other.signedCents == -movement.signedCents) { "Gli importi dei due movimenti non coincidono." }
            val outgoing = if (movement.signedCents < 0) movement else other
            val incoming = if (movement.signedCents < 0) other else movement
            val transfer = AccountTransfer("x:${outgoing.id}", minOf(outgoing.date, incoming.date), outgoing.accountId, incoming.accountId, amount,
                "${outgoing.provider.label} → ${incoming.provider.label} · importazione".take(500))
            require(transfers.none { it.id == transfer.id }) { "Il trasferimento è già registrato." }
            copy(transfers = transfers + transfer)
                .replaceExternal(movement.copy(status = ExternalStatus.APPLIED, linkedRecordId = transfer.id))
                .replaceExternal(other.copy(status = ExternalStatus.APPLIED, linkedRecordId = transfer.id))
        }
        is ExternalDecision.AsTrade -> {
            val isin = movement.isin ?: throw IllegalArgumentException("Serve l'ISIN per registrare un'operazione.")
            val quantity = movement.quantity ?: throw IllegalArgumentException("Serve la quantità per registrare un'operazione.")
            val existing = instruments.firstOrNull { it.identifier.equals(isin, ignoreCase = true) }
            val instrument = existing ?: Instrument("i:$isin", decision.instrumentName.ifBlank { isin }.take(200), isin, source)
            // A broker with separate sleeves books the trade on its securities account; the cash leg stays on the imported row.
            val tradeAccount = connections.firstOrNull { it.provider == movement.provider && it.accountId == account.id }?.securitiesAccountId ?: account.id
            val trade = Trade("x:${movement.id}", tradeAccount, instrument.id, movement.date, decision.side, quantity, amount, Money(0), source)
            require(trades.none { it.id == trade.id }) { "L'operazione è già registrata." }
            copy(instruments = if (existing == null) instruments + instrument else instruments, trades = trades + trade)
                .replaceExternal(movement.copy(status = ExternalStatus.APPLIED, linkedRecordId = trade.id))
        }
    }.let { it.copy(audit = it.audit + AuditEntry(at.take(50), "revisione importazione", movement.toString().take(2000))) }
}

/** Reopens a reviewed movement and removes the ledger record it created. */
fun Archive.withExternalReopened(movementId: String, at: String = Instant.now().toString()): Archive {
    val movement = externalMovements.singleOrNull { it.id == movementId } ?: throw IllegalArgumentException("Il movimento importato non è più disponibile.")
    require(movement.status != ExternalStatus.PENDING) { "Il movimento è già da revisionare." }
    val linked = movement.linkedRecordId
    val withoutRecord = if (linked == null) this else copy(events = events.filterNot { it.id == linked }, trades = trades.filterNot { it.id == linked },
        transfers = transfers.filterNot { it.id == linked })
    // A transfer links two movements: both reopen together.
    val partners = externalMovements.filter { it.id != movement.id && linked != null && it.linkedRecordId == linked }
    return partners.fold(withoutRecord.replaceExternal(movement.copy(status = ExternalStatus.PENDING, linkedRecordId = null))) { acc, partner ->
        acc.replaceExternal(partner.copy(status = ExternalStatus.PENDING, linkedRecordId = null))
    }
        .let { it.copy(audit = it.audit + AuditEntry(at.take(50), "riapertura importazione", movement.toString().take(2000))) }
}

private fun Archive.replaceExternal(replacement: ExternalMovement) =
    copy(externalMovements = externalMovements.map { if (it.id == replacement.id) replacement else it })

/**
 * Pending movements that look like the two sides of one transfer between the user's own accounts: opposite exact amounts
 * on different accounts within [days] days. Each movement pairs at most once, nearest date first. A suggestion only.
 */
fun Archive.transferPairs(days: Long = 4): Map<String, String> {
    // The incoming side is always a deposit (never a refund or a payment from someone else). Either side may already have been
    // reviewed as a plain outflow or contribution (bank imported and applied first): the pair is still offered, and applying it
    // reopens that review. At least one side is still pending.
    val eventType = events.associate { it.id to it.type }
    fun reviewedAs(m: ExternalMovement, vararg types: EventType) = m.status == ExternalStatus.APPLIED && m.linkedRecordId?.let(eventType::get) in types
    // An applied outflow the user filed under a category ("Spesa") is decided: it never pairs again until the review is reopened.
    fun answeredSpesa(m: ExternalMovement) = m.status == ExternalStatus.APPLIED && m.categorySource == CategorySource.OVERRIDE
    val outs = externalMovements.filter { it.signedCents < 0 && it.kind !in nonTransferKinds && !answeredSpesa(it) &&
        (it.status == ExternalStatus.PENDING || reviewedAs(it, EventType.WITHDRAWAL)) }
    val insByAmount = externalMovements.filter { it.signedCents > 0 && it.kind == ExternalKind.DEPOSIT &&
        (it.status == ExternalStatus.PENDING || reviewedAs(it, EventType.PERSONAL_CONTRIBUTION, EventType.INCOME)) }.groupBy { it.signedCents }
    val used = HashSet<String>()
    val pairs = LinkedHashMap<String, String>()
    outs.sortedWith(compareBy({ it.status != ExternalStatus.PENDING }, { it.date })).forEach { out ->
        if (out.id in used) return@forEach
        val match = insByAmount[-out.signedCents].orEmpty().asSequence()
            .filter { it.id !in used && it.accountId != out.accountId && (it.status == ExternalStatus.PENDING || out.status == ExternalStatus.PENDING) }
            .filter { kotlin.math.abs(it.date.toEpochDay() - out.date.toEpochDay()) <= days }
            .minByOrNull { kotlin.math.abs(it.date.toEpochDay() - out.date.toEpochDay()) }
        if (match != null) { used += out.id; used += match.id; pairs[out.id] = match.id; pairs[match.id] = out.id }
    }
    return pairs
}

private val nonTransferKinds = setOf(ExternalKind.BUY, ExternalKind.SELL, ExternalKind.FEE, ExternalKind.TAX)

/** Recategorizes a movement without touching its review decision. */
fun Archive.withExternalCategory(movementId: String, category: String?): Archive {
    val movement = externalMovements.singleOrNull { it.id == movementId } ?: throw IllegalArgumentException("Il movimento importato non è più disponibile.")
    return replaceExternal(movement.copy(category = category?.trim()?.take(60)?.ifBlank { null }))
}

enum class SuggestionWait { NONE, AWAITING_BANK_EXPORT }

/**
 * Archive-aware suggestion. An inflow paired with an outflow on another own account is a transfer. An unpaired broker
 * deposit waits until the bank export covers its date; only then is it proposed as the user's own contribution from
 * outside. Bank-side credits (salary, refunds) never default to a contribution.
 */
fun Archive.suggestedDecisionFor(movement: ExternalMovement, pairs: Map<String, String> = transferPairs()): Pair<ExternalDecision?, SuggestionWait> {
    pairs[movement.id]?.let { return ExternalDecision.AsTransfer(it) to SuggestionWait.NONE }
    if (movement.kind != ExternalKind.DEPOSIT || movement.signedCents <= 0) return movement.suggestedDecision() to SuggestionWait.NONE
    if (movement.provider != ImportProvider.TRADE_REPUBLIC) return null to SuggestionWait.NONE
    val bankCovered = connections.any {
        it.provider == ImportProvider.ISYBANK && it.coverageFrom != null && it.coverageTo != null &&
            !it.coverageFrom.isAfter(movement.date) && !it.coverageTo.isBefore(movement.date.plusDays(4))
    }
    return if (bankCovered) ExternalDecision.AsEvent(EventType.PERSONAL_CONTRIBUTION) to SuggestionWait.NONE else null to SuggestionWait.AWAITING_BANK_EXPORT
}
