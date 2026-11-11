package xyz.grtlabs.pilastri.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.util.Base64
import xyz.grtlabs.pilastri.domain.*

data class IndexPoint(val month: YearMonth, val value: BigDecimal, val source: String) {
    init { require(value.signum() > 0 && value.scale() <= 8 && value.precision() <= 15 && source.isNotBlank()) }
}
/** Closed code enum. Archives before v14 stored fund codes here; they migrate to PENSION_FUND with [Account.legacyType]. */
enum class AccountType { GENERAL, PENSION_FUND }
data class Account(
    val id: String, val name: String, val pillar: Int, val source: String, val type: AccountType? = null,
    /** The [PensionFund] that describes a PENSION_FUND account; null while it is not described. */
    val fundId: String? = null,
    /** The raw type an archive before v14 stored (for example a fund code), kept verbatim forever; never set for new accounts. */
    val legacyType: String? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && pillar in 1..4 && source.isNotBlank())
        require(type != AccountType.PENSION_FUND || pillar == 4) { "Un fondo pensione deve appartenere al pilastro 4." }
        require(fundId == null || (type == AccountType.PENSION_FUND && fundId.isNotBlank() && fundId.length <= 120)) { "Fondo del conto non valido" }
        require(legacyType == null || (legacyType.isNotBlank() && legacyType.length <= 60))
    }
}
data class AuditEntry(val at: String, val kind: String, val previous: String)
enum class PlanKind { INCOME, EXPENSE }
enum class PlanStatus { ACTUAL, SCENARIO }
data class PlanItem(val id: String, val month: YearMonth, val label: String, val kind: PlanKind, val status: PlanStatus, val amount: Money, val source: String) {
    init { require(id.isNotBlank() && label.isNotBlank() && source.isNotBlank() && amount.cents >= 0) }
}
data class Archive(val events: List<LedgerEvent> = emptyList(), val valuations: List<Valuation> = emptyList(), val indices: List<IndexPoint> = emptyList(), val audit: List<AuditEntry> = emptyList(), val plan: List<PlanItem> = emptyList(), val accounts: List<Account> = emptyList(), val prefillFacts: PrefillFacts? = null, val accountValuations: List<AccountValuation> = emptyList(), val instruments: List<Instrument> = emptyList(), val trades: List<Trade> = emptyList(), val transfers: List<AccountTransfer> = emptyList(), val projections: List<SavedProjection> = emptyList(), val performanceAttestations: List<PerformanceAttestation> = emptyList(), val externalMovements: List<ExternalMovement> = emptyList(), val connections: List<Connection> = emptyList(),
    val planning: Planning = Planning(), val pension: PensionData = PensionData(), val holdings: List<Holding> = emptyList(),
    val installments: List<InstallmentPlan> = emptyList(), val installmentSync: List<InstallmentSync> = emptyList(),
    // Archive v14 (Council 4): positions and funds are typed; the rule-pack collections are kept verbatim until typed.
    val positions: List<IncomePosition> = emptyList(), val funds: List<PensionFund> = emptyList(),
    val rulesData: RulesData = RulesData(), val unknownProviderRows: UnknownProviderRows = UnknownProviderRows())

/** Idempotently adds reported prefill payments and merges non-monetary facts without replacing reported values. */
fun Archive.withPrefillImport(items: List<PlanItem>, facts: PrefillFacts): Archive {
    val additions = items.filter { candidate -> plan.none { it.id == candidate.id } }
    val mergedFacts = prefillFacts?.mergeNewReports(facts) ?: facts
    return copy(plan = plan + additions, prefillFacts = mergedFacts)
}

/** Corrections preserve the prior record and never replace a different snapshot or event. */
fun Archive.correctValuation(original: Valuation, replacement: Valuation, at: String): Archive {
    require(valuations.count { it == original } == 1) { "La registrazione originale non è più disponibile." }
    require(valuations.none { it != original && it.pillar == replacement.pillar && it.date == replacement.date }) {
        "Esiste già un valore per quel giorno e pilastro."
    }
    if (original == replacement) return this
    return copy(valuations = valuations.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica valutazione", original.toString()))
}

fun Archive.correctContribution(original: LedgerEvent, replacement: LedgerEvent, at: String): Archive {
    require(events.count { it == original } == 1) { "La registrazione originale non è più disponibile." }
    require(replacement.id == original.id && replacement.type == original.type) { "Identità o tipo del movimento non validi." }
    if (original == replacement) return this
    return copy(events = events.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica movimento", original.toString()))
}

fun Archive.correctInstrument(original: Instrument, replacement: Instrument, at: String): Archive {
    require(instruments.count { it == original } == 1) { "Lo strumento originale non è più disponibile." }
    require(replacement.id == original.id) { "L'identità dello strumento deve rimanere invariata." }
    if (original == replacement) return this
    return copy(instruments = instruments.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica strumento", original.toString()))
}

fun Archive.correctTrade(original: Trade, replacement: Trade, at: String): Archive {
    require(trades.count { it == original } == 1) { "L'operazione originale non è più disponibile." }
    require(replacement.id == original.id) { "L'identità dell'operazione deve rimanere invariata." }
    if (original == replacement) return this
    return copy(trades = trades.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica operazione", original.toString()))
}

fun Archive.correctTransfer(original: AccountTransfer, replacement: AccountTransfer, at: String): Archive {
    require(transfers.count { it == original } == 1) { "Il trasferimento originale non è più disponibile." }
    require(replacement.id == original.id) { "L'identità del trasferimento deve rimanere invariata." }
    if (original == replacement) return this
    return copy(transfers = transfers.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica trasferimento", original.toString()))
}

/** Replaces a saved hypothetical scenario without changing its stable identity. */
fun Archive.correctProjection(original: SavedProjection, replacement: SavedProjection, at: String): Archive {
    require(projections.count { it == original } == 1) { "Lo scenario originale non è più disponibile." }
    require(replacement.id == original.id) { "L'identità dello scenario deve rimanere invariata." }
    if (original == replacement) return this
    return copy(projections = projections.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica scenario", original.toString()))
}

/** Adds a new scenario without replacing an existing record with the same stable id. */
fun Archive.withProjection(projection: SavedProjection): Archive {
    require(projections.none { it.id == projection.id }) { "Esiste già uno scenario con questo identificativo." }
    require(projections.size < SavedProjection.MAX_COUNT) { "Hai raggiunto il limite degli scenari salvati." }
    return copy(projections = projections + projection)
}

/** Removes one saved scenario while retaining its previous assumptions in the audit. */
fun Archive.withoutProjection(id: String): Archive {
    val removed = projections.singleOrNull { it.id == id } ?: throw IllegalArgumentException("Lo scenario non è più disponibile.")
    return copy(projections = projections.filterNot { it.id == id },
        audit = audit + AuditEntry(java.time.Instant.now().toString(), "rimozione scenario", removed.toString()))
}

/** Replaces a review while preserving its identity and the previous review in the audit. */
fun Archive.correctPerformanceAttestation(original: PerformanceAttestation, replacement: PerformanceAttestation, at: String): Archive {
    require(performanceAttestations.count { it == original } == 1) { "La conferma originale non è più disponibile." }
    require(replacement.id == original.id) { "L'identità della conferma deve rimanere invariata." }
    if (original == replacement) return this
    return copy(performanceAttestations = performanceAttestations.map { if (it == original) replacement else it },
        audit = audit + AuditEntry(at, "rettifica verifica performance", original.toString()))
}

/** Removes a review while retaining its prior record in the audit. */
fun Archive.withoutPerformanceAttestation(id: String): Archive {
    val removed = performanceAttestations.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("La conferma non è più disponibile.")
    return copy(performanceAttestations = performanceAttestations.filterNot { it.id == id },
        audit = audit + AuditEntry(java.time.Instant.now().toString(), "rimozione verifica performance", removed.toString()))
}

/** Refuses to remove a registry entry while any saved record still refers to it. */
fun Archive.withoutAccount(id: String): Archive {
    require(accounts.any { it.id == id }) { "Il conto non è più disponibile." }
    require(events.none { it.accountId == id } && accountValuations.none { it.accountId == id } && trades.none { it.accountId == id } && transfers.none { it.fromAccountId == id || it.toAccountId == id } && externalMovements.none { it.accountId == id } && connections.none { it.accountId == id || it.securitiesAccountId == id } && installments.none { it.fundingAccountId == id } && planning.incomeRules.none { it.accountId == id } && !unknownProviderRows.refersToAccount(id)) { "Il conto è usato da registrazioni e non può essere rimosso." }
    return copy(accounts = accounts.filterNot { it.id == id })
}

/** Referenced instruments remain in the registry until their trade history is removed. */
fun Archive.withoutInstrument(id: String): Archive {
    val removed = instruments.singleOrNull { it.id == id } ?: throw IllegalArgumentException("Lo strumento non è più disponibile.")
    require(trades.none { it.instrumentId == id }) { "Lo strumento è usato nello storico e non può essere rimosso." }
    return copy(instruments = instruments.filterNot { it.id == id },
        audit = audit + AuditEntry(java.time.Instant.now().toString(), "rimozione strumento", removed.toString()))
}

fun Archive.withoutTrade(id: String): Archive {
    val removed = trades.singleOrNull { it.id == id } ?: throw IllegalArgumentException("L'operazione non è più disponibile.")
    return copy(trades = trades.filterNot { it.id == id },
        audit = audit + AuditEntry(java.time.Instant.now().toString(), "rimozione operazione", removed.toString()))
}

fun Archive.withoutTransfer(id: String): Archive {
    val removed = transfers.singleOrNull { it.id == id } ?: throw IllegalArgumentException("Il trasferimento non è più disponibile.")
    return copy(transfers = transfers.filterNot { it.id == id },
        audit = audit + AuditEntry(java.time.Instant.now().toString(), "rimozione trasferimento", removed.toString()))
}

/** Updates a user's account classification while preserving stable identity and referenced scope. */
fun Archive.updateAccount(replacement: Account): Archive {
    val original = accounts.singleOrNull { it.id == replacement.id }
        ?: throw IllegalArgumentException("Il conto non è più disponibile.")
    if (original.pillar != replacement.pillar) {
        require(events.none { it.accountId == original.id } && accountValuations.none { it.accountId == original.id } &&
            trades.none { it.accountId == original.id } && transfers.none { it.fromAccountId == original.id || it.toAccountId == original.id } &&
            externalMovements.none { it.accountId == original.id && it.status == ExternalStatus.APPLIED }) {
            "Il pilastro non può cambiare dopo aver associato registrazioni al conto."
        }
    }
    // The legacy code is kept forever; a fund link exists only on a PENSION_FUND account.
    val kept = replacement.copy(legacyType = original.legacyType,
        fundId = if (replacement.type == AccountType.PENSION_FUND) replacement.fundId else null)
    return copy(accounts = accounts.map { if (it.id == original.id) kept else it })
}

enum class PensionFundValueUnavailableReason { NO_TYPED_ACCOUNT, MULTIPLE_TYPED_ACCOUNTS, NO_DATED_VALUE }
sealed interface PensionFundValueResolution {
    data class Available(val account: Account, val valuation: AccountValuation) : PensionFundValueResolution
    data class Unavailable(val reason: PensionFundValueUnavailableReason) : PensionFundValueResolution
}

/**
 * Returns a dated fund value only when one PENSION_FUND account migrated from [legacyType] is unambiguous. Compatibility for the
 * pre-Council-4 Previdenza cards; new code reads funds through [Account.fundId].
 */
fun Archive.pensionFundValue(legacyType: String): PensionFundValueResolution {
    require(legacyType in LegacyAccountType.FUND_CODES)
    val matches = accounts.filter { it.type == AccountType.PENSION_FUND && it.legacyType == legacyType && it.pillar == 4 }
    if (matches.isEmpty()) return PensionFundValueResolution.Unavailable(PensionFundValueUnavailableReason.NO_TYPED_ACCOUNT)
    if (matches.size != 1) return PensionFundValueResolution.Unavailable(PensionFundValueUnavailableReason.MULTIPLE_TYPED_ACCOUNTS)
    val account = matches.single()
    val latest = accountValuations.filter { it.accountId == account.id }.maxByOrNull { it.date }
        ?: return PensionFundValueResolution.Unavailable(PensionFundValueUnavailableReason.NO_DATED_VALUE)
    return PensionFundValueResolution.Available(account, latest)
}

/** January 2026 is an explicit reference month, not a full-year 2026 average. */
fun realContributions(data: Archive, pillar: Int? = null): Money? {
    val events = data.events.filter { it.type == EventType.PERSONAL_CONTRIBUTION && (pillar == null || it.pillar == pillar) }
    if (events.isEmpty()) return null
    val index = data.indices.associateBy { it.month }
    val base = index[YearMonth.of(2026, 1)]?.value ?: return null
    val values = events.map { event -> Finance.inBaseYear(event.amount, base, index[YearMonth.from(event.date)]?.value) ?: return null }
    return try { Money(values.fold(0L) { sum, value -> Math.addExact(sum, value.cents) }) } catch (_: ArithmeticException) { null }
}

/** Portable UTF-8 archive. The checksum detects accidental corruption, not tampering. */
object ArchiveCodec {
    private const val MAX_BYTES = 5_000_000
    const val CURRENT_VERSION = 14
    private val V14_KEYS = listOf("positions", "funds", "incomeRules") + RulesData.KEYS
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun cents(obj: JSONObject, key: String = "cents"): Long {
        val raw = obj.get(key)
        require(raw is Number && raw.toString().matches(Regex("[0-9]+"))) { "Centesimi non validi" }
        return raw.toString().toLong()
    }

    fun encode(data: Archive): ByteArray {
        validate(data)
        val payload = JSONObject().put("events", JSONArray().apply { data.events.forEach { e -> put(JSONObject()
            .put("id", e.id).put("date", e.date.toString()).put("pillar", e.pillar).put("type", e.type.name)
            .put("cents", e.amount.cents).put("currency", e.amount.currency).put("confidence", e.confidence.name)
            .put("source", e.source).put("transferPair", e.transferPair ?: JSONObject.NULL)
            .put("accountId", e.accountId ?: JSONObject.NULL)) } })
            .put("valuations", JSONArray().apply { data.valuations.forEach { v -> put(JSONObject()
                .put("date", v.date.toString()).put("pillar", v.pillar).put("cents", v.amount.cents)
                .put("currency", v.amount.currency).put("confidence", v.confidence.name).put("source", v.source)) } })
            .put("indices", JSONArray().apply { data.indices.forEach { p -> put(JSONObject()
                .put("month", p.month.toString()).put("value", p.value.toPlainString()).put("source", p.source)) } })
            .put("audit", JSONArray().apply { data.audit.forEach { a -> put(JSONObject()
                .put("at", a.at).put("kind", a.kind).put("previous", a.previous)) } })
            .put("plan", JSONArray().apply { data.plan.forEach { p -> put(JSONObject()
                .put("id", p.id).put("month", p.month.toString()).put("label", p.label).put("kind", p.kind.name)
                .put("status", p.status.name).put("cents", p.amount.cents).put("currency", p.amount.currency).put("source", p.source)) } })
            .put("accounts", JSONArray().apply { data.accounts.forEach { account -> put(JSONObject()
                .put("id", account.id).put("name", account.name).put("pillar", account.pillar).put("source", account.source)
                .put("type", account.type?.name ?: JSONObject.NULL).put("fundId", account.fundId ?: JSONObject.NULL)
                .put("legacyType", account.legacyType ?: JSONObject.NULL)) } })
            .put("prefillFacts", data.prefillFacts?.toJson() ?: JSONObject.NULL)
            .put("accountValuations", JSONArray().apply { data.accountValuations.forEach { value -> put(JSONObject()
                .put("accountId", value.accountId).put("date", value.date.toString()).put("cents", value.amount.cents)
                .put("currency", value.amount.currency).put("source", value.source).put("confidence", value.confidence.name)) } })
            .put("instruments", JSONArray().apply { data.instruments.forEach { instrument -> put(JSONObject()
                .put("id", instrument.id).put("name", instrument.name).put("identifier", instrument.identifier ?: JSONObject.NULL)
                .put("source", instrument.source)) } })
            .put("trades", JSONArray().apply { data.trades.forEach { trade -> put(JSONObject()
                .put("id", trade.id).put("accountId", trade.accountId).put("instrumentId", trade.instrumentId)
                .put("date", trade.date.toString()).put("side", trade.side.name).put("quantity", trade.quantity.toPlainString())
                .put("grossCents", trade.grossAmount.cents).put("grossCurrency", trade.grossAmount.currency)
                .put("feeCents", trade.fees.cents).put("feeCurrency", trade.fees.currency).put("source", trade.source)) } })
            .put("transfers", JSONArray().apply { data.transfers.forEach { transfer -> put(JSONObject()
                .put("id", transfer.id).put("date", transfer.date.toString()).put("fromAccountId", transfer.fromAccountId)
                .put("toAccountId", transfer.toAccountId).put("cents", transfer.amount.cents)
                .put("currency", transfer.amount.currency).put("source", transfer.source)) } })
            .put("projections", JSONArray().apply { data.projections.forEach { projection -> put(JSONObject()
                .put("id", projection.id).put("name", projection.name)
                .put("assumptions", JSONObject()
                    .put("startYear", projection.assumptions.startYear)
                    .put("endYear", projection.assumptions.endYear)
                    .put("initialWealthCents", projection.assumptions.initialWealthCents.toString())
                    .put("annualPersonalAdditionCents", projection.assumptions.annualPersonalAdditionCents.toString())
                    .put("annualEmployerAdditionCents", projection.assumptions.annualEmployerAdditionCents.toString())
                    .put("annualNominalGrowthPercent", projection.assumptions.annualNominalGrowthPercent.toPlainString())
                    .put("annualInflationPercent", projection.assumptions.annualInflationPercent.toPlainString())
                    .put("priceReference", projection.assumptions.priceReference.name))
                .put("source", projection.source).put("savedAt", projection.savedAt)) } })
            .put("performanceAttestations", JSONArray().apply { data.performanceAttestations.forEach { item -> put(JSONObject()
                .put("id", item.id).put("pillar", item.pillar).put("start", item.start.toString()).put("end", item.end.toString())
                .put("source", item.source).put("reviewedAt", item.reviewedAt).put("inputFingerprint", item.inputFingerprint)
                .put("externalFlowsComplete", item.externalFlowsComplete).put("valuationRolesConfirmed", item.valuationRolesConfirmed)) } })
            .put("externalMovements", JSONArray().apply { data.externalMovements.forEach { m -> put(JSONObject()
                .put("id", m.id).put("providerId", m.provider.id).put("accountId", m.accountId).put("date", m.date.toString())
                .put("signedCents", m.signedCents.toString()).put("currency", m.currency).put("description", m.description)
                .put("kind", m.kind.name).put("rawType", m.rawType ?: JSONObject.NULL).put("isin", m.isin ?: JSONObject.NULL)
                .put("quantity", m.quantity?.toPlainString() ?: JSONObject.NULL).put("status", m.status.name)
                .put("linkedRecordId", m.linkedRecordId ?: JSONObject.NULL).put("importedAt", m.importedAt)
                .put("category", m.category ?: JSONObject.NULL).put("categoryId", m.categoryId ?: JSONObject.NULL)
                .put("categorySource", m.categorySource?.name ?: JSONObject.NULL).put("merchantKey", m.merchantKey ?: JSONObject.NULL)
                .put("role", m.role?.name ?: JSONObject.NULL)) }
                data.unknownProviderRows.externalMovements.forEach { put(it.toJsonObject()) } })
            .put("connections", JSONArray().apply { data.connections.forEach { c -> put(JSONObject()
                .put("providerId", c.provider.id).put("accountId", c.accountId).put("lastSyncAt", c.lastSyncAt ?: JSONObject.NULL)
                .put("coverageFrom", c.coverageFrom?.toString() ?: JSONObject.NULL).put("coverageTo", c.coverageTo?.toString() ?: JSONObject.NULL)
                .put("lastResult", c.lastResult ?: JSONObject.NULL).put("securitiesAccountId", c.securitiesAccountId ?: JSONObject.NULL)) }
                data.unknownProviderRows.connections.forEach { put(it.toJsonObject()) } })
            .put("planning", PlanningCodec.encodePlanning(data.planning, data.unknownProviderRows.providerCategories))
            .put("positions", PositionCodec.encode(data.positions))
            .put("funds", FundCodec.encode(data.funds))
            .put("incomeRules", PlanningCodec.encodeIncomeRules(data.planning.incomeRules))
            .apply { data.rulesData.byKey().forEach { (key, raw) -> put(key, raw.toJsonArray()) } }
            .put("pension", PlanningCodec.encodePension(data.pension))
            .put("holdings", PlanningCodec.encodeHoldings(data.holdings))
            .put("installments", InstallmentCodec.encodePlans(data.installments))
            .put("installmentSync", InstallmentCodec.encodeSync(data.installmentSync))
            .toString().toByteArray(Charsets.UTF_8)
        // Version 2 added accounts, version 3 reported prefill facts, version 4 event account IDs,
        // version 5 account valuations, version 6 account classification, version 7 cash flows,
        // version 8 trade journal, version 9 account transfers, version 10 saved projection scenarios,
        // version 11 projection price references and reviewed performance-window attestations,
        // version 12 reviewed provider imports and connection bookkeeping (never credentials),
        // version 13 installment plans from mail/PayPal evidence and their scan bookkeeping,
        // version 14 (Council 4) positions, funds, income rules, PENSION_FUND accounts with fundId/legacyType, string providerId,
        // and the rule-pack collections kept verbatim (packVersions, personalParams, snapshots, payslipLineClasses).
        val envelope = JSONObject().put("format", "pilastri-archive").put("version", CURRENT_VERSION)
            .put("sha256", digest(payload)).put("payload", Base64.getEncoder().encodeToString(payload))
            .toString().toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_BYTES) { "Archivio troppo grande" }
        return envelope
    }

    fun decode(bytes: ByteArray): Archive {
        require(bytes.size <= MAX_BYTES) { "Archivio troppo grande" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == "pilastri-archive") { "Formato o versione non supportati" }
        val version = root.getInt("version")
        require(version in 1..CURRENT_VERSION) { "Formato o versione non supportati" }
        val payload = Base64.getDecoder().decode(root.getString("payload"))
        require(digest(payload) == root.getString("sha256")) { "Controllo integrità non riuscito" }
        val data = JSONObject(payload.toString(Charsets.UTF_8))
        if (version == 1) require(!data.has("accounts")) { "Formato o versione non supportati" }
        if (version < 3) require(!data.has("prefillFacts")) { "Formato o versione non supportati" }
        if (version < 5) require(!data.has("accountValuations")) { "Formato o versione non supportati" }
        if (version < 8) require(!data.has("instruments") && !data.has("trades")) { "Formato o versione non supportati" }
        if (version < 9) require(!data.has("transfers")) { "Formato o versione non supportati" }
        if (version < 10) require(!data.has("projections")) { "Formato o versione non supportati" }
        if (version < 11) require(!data.has("performanceAttestations")) { "Formato o versione non supportati" }
        if (version < 12) require(listOf("externalMovements", "connections", "planning", "pension", "holdings").none(data::has)) { "Formato o versione non supportati" }
        if (version < 13) require(listOf("installments", "installmentSync").none(data::has)) { "Formato o versione non supportati" }
        if (version < 14) require(V14_KEYS.none(data::has)) { "Formato o versione non supportati" }
        else require(V14_KEYS.all(data::has)) { "Formato o versione non supportati" }
        val entries = data.getJSONArray("events")
        val values = data.getJSONArray("valuations")
        val indices = data.optJSONArray("indices") ?: JSONArray()
        val audit = data.optJSONArray("audit") ?: JSONArray()
        val plan = data.optJSONArray("plan") ?: JSONArray()
        val accounts = if (version == 1) JSONArray() else data.getJSONArray("accounts")
        if (version >= 5) require(data.has("accountValuations")) { "Formato o versione non supportati" }
        val accountValues = if (version >= 5) data.getJSONArray("accountValuations") else JSONArray()
        if (version >= 8) require(data.has("instruments") && data.has("trades")) { "Formato o versione non supportati" }
        val instrumentsJson = if (version >= 8) data.getJSONArray("instruments") else JSONArray()
        val tradesJson = if (version >= 8) data.getJSONArray("trades") else JSONArray()
        if (version >= 9) require(data.has("transfers")) { "Formato o versione non supportati" }
        val transfersJson = if (version >= 9) data.getJSONArray("transfers") else JSONArray()
        if (version >= 10) require(data.has("projections")) { "Formato o versione non supportati" }
        val projectionsJson = if (version >= 10) data.getJSONArray("projections") else JSONArray()
        if (version >= 11) require(data.has("performanceAttestations")) { "Formato o versione non supportati" }
        val attestationsJson = if (version >= 11) data.getJSONArray("performanceAttestations") else JSONArray()
        if (version >= 12) require(listOf("externalMovements", "connections", "planning", "pension", "holdings").all(data::has)) { "Formato o versione non supportati" }
        if (version >= 13) require(listOf("installments", "installmentSync").all(data::has)) { "Formato o versione non supportati" }
        val externalJson = if (version >= 12) data.getJSONArray("externalMovements") else JSONArray()
        val connectionsJson = if (version >= 12) data.getJSONArray("connections") else JSONArray()
        require(externalJson.length() <= 100_000 && connectionsJson.length() <= 1000)
        val prefillFacts = if (version >= 3) {
            require(data.has("prefillFacts")) { "Formato o versione non supportati" }
            if (data.isNull("prefillFacts")) null else PrefillFacts.fromJson(data.getJSONObject("prefillFacts"))
        } else null
        require(entries.length() <= 100_000 && values.length() <= 100_000 && indices.length() <= 3000 && audit.length() <= 100_000 && plan.length() <= 100_000)
        require(accounts.length() <= 100_000 && accountValues.length() <= 100_000 && instrumentsJson.length() <= 100_000 && tradesJson.length() <= 100_000 && transfersJson.length() <= 100_000)
        require(projectionsJson.length() <= SavedProjection.MAX_COUNT) { "Troppi scenari salvati" }
        require(attestationsJson.length() <= PerformanceAttestation.MAX_COUNT) { "Troppe verifiche performance" }
        val events = (0 until entries.length()).map { i -> entries.getJSONObject(i).let { e ->
            if (version >= 4) require(e.has("accountId")) { "Formato o versione non supportati" }
            else require(!e.has("accountId")) { "Formato o versione non supportati" }
            val type = EventType.valueOf(e.getString("type"))
            if (version < 7) require(type == EventType.PERSONAL_CONTRIBUTION || type == EventType.EMPLOYER_CONTRIBUTION) {
                "Tipo di movimento non supportato in questa versione dell'archivio"
            }
            LedgerEvent(e.getString("id"), LocalDate.parse(e.getString("date")), e.getInt("pillar"), type,
                Money(cents(e), e.getString("currency")), Confidence.valueOf(e.getString("confidence")), e.getString("source"),
                if (e.isNull("transferPair")) null else e.getString("transferPair"),
                if (version >= 4 && !e.isNull("accountId")) e.getString("accountId") else null)
        } }
        val valuations = (0 until values.length()).map { i -> values.getJSONObject(i).let { v -> Valuation(
            LocalDate.parse(v.getString("date")), v.getInt("pillar"), Money(cents(v), v.getString("currency")),
            v.getString("source"), Confidence.valueOf(v.getString("confidence"))) } }
        val points = (0 until indices.length()).map { i -> indices.getJSONObject(i).let { p -> IndexPoint(
            YearMonth.parse(p.getString("month")), BigDecimal(p.getString("value")), p.getString("source")) } }
        val changes = (0 until audit.length()).map { i -> audit.getJSONObject(i).let { a -> AuditEntry(a.getString("at"), a.getString("kind"), a.getString("previous")) } }
        val planItems = (0 until plan.length()).map { i -> plan.getJSONObject(i).let { p -> PlanItem(
            p.getString("id"), YearMonth.parse(p.getString("month")), p.getString("label"), PlanKind.valueOf(p.getString("kind")),
            PlanStatus.valueOf(p.getString("status")), Money(cents(p), p.getString("currency")), p.getString("source")) } }
        val accountItems = (0 until accounts.length()).map { i -> accounts.getJSONObject(i).let { a ->
            if (version >= 6) require(a.has("type")) { "Formato o versione non supportati" }
            else require(!a.has("type")) { "Formato o versione non supportati" }
            if (version >= 14) require(a.has("fundId") && a.has("legacyType")) { "Formato o versione non supportati" }
            else require(!a.has("fundId") && !a.has("legacyType")) { "Formato o versione non supportati" }
            val rawType = if (version >= 6 && !a.isNull("type")) a.getString("type") else null
            if (version >= 14) Account(a.getString("id"), a.getString("name"), a.getInt("pillar"), a.getString("source"),
                rawType?.let(AccountType::valueOf), if (a.isNull("fundId")) null else a.getString("fundId"),
                if (a.isNull("legacyType")) null else a.getString("legacyType"))
            else {
                // v6–v13: a stored fund code becomes PENSION_FUND and stays verbatim in legacyType; ArchiveMigration adds the fund.
                val legacyFund = rawType != null && rawType in LegacyAccountType.FUND_CODES
                require(rawType == null || legacyFund || rawType == AccountType.GENERAL.name) { "Formato o versione non supportati" }
                Account(a.getString("id"), a.getString("name"), a.getInt("pillar"), a.getString("source"),
                    if (legacyFund) AccountType.PENSION_FUND else rawType?.let(AccountType::valueOf), legacyType = if (legacyFund) rawType else null)
            }
        } }
        val accountValuationItems = (0 until accountValues.length()).map { i -> accountValues.getJSONObject(i).let { value -> AccountValuation(
            value.getString("accountId"), LocalDate.parse(value.getString("date")),
            Money(cents(value), value.getString("currency")), value.getString("source"), Confidence.valueOf(value.getString("confidence"))) } }
        val instruments = (0 until instrumentsJson.length()).map { i -> instrumentsJson.getJSONObject(i).let { item -> Instrument(
            item.getString("id"), item.getString("name"), if (item.isNull("identifier")) null else item.getString("identifier"), item.getString("source")) } }
        val trades = (0 until tradesJson.length()).map { i -> tradesJson.getJSONObject(i).let { item -> Trade(
            item.getString("id"), item.getString("accountId"), item.getString("instrumentId"), LocalDate.parse(item.getString("date")),
            TradeSide.valueOf(item.getString("side")), BigDecimal(item.getString("quantity")),
            Money(cents(item, "grossCents"), item.getString("grossCurrency")), Money(cents(item, "feeCents"), item.getString("feeCurrency")), item.getString("source")) } }
        val transfers = (0 until transfersJson.length()).map { i -> transfersJson.getJSONObject(i).let { item -> AccountTransfer(
            item.getString("id"), LocalDate.parse(item.getString("date")), item.getString("fromAccountId"), item.getString("toAccountId"),
            Money(cents(item), item.getString("currency")), item.getString("source")) } }
        val projections = (0 until projectionsJson.length()).map { i -> projectionsJson.getJSONObject(i).let { item ->
            val assumptionsJson = item.getJSONObject("assumptions")
            SavedProjection(
                id = item.getString("id"),
                name = item.getString("name"),
                assumptions = ProjectionAssumptions(
                    startYear = exactInt(assumptionsJson, "startYear"),
                    endYear = exactInt(assumptionsJson, "endYear"),
                    initialWealthCents = exactCentsString(assumptionsJson, "initialWealthCents"),
                    annualPersonalAdditionCents = exactCentsString(assumptionsJson, "annualPersonalAdditionCents"),
                    annualEmployerAdditionCents = exactCentsString(assumptionsJson, "annualEmployerAdditionCents"),
                    annualNominalGrowthPercent = exactRate(assumptionsJson, "annualNominalGrowthPercent"),
                    annualInflationPercent = exactRate(assumptionsJson, "annualInflationPercent"),
                    priceReference = if (version >= 11) {
                        require(assumptionsJson.has("priceReference")) { "Formato o versione non supportati" }
                        ProjectionPriceReference.valueOf(assumptionsJson.getString("priceReference"))
                    } else {
                        require(!assumptionsJson.has("priceReference")) { "Formato o versione non supportati" }
                        ProjectionPriceReference.END_2026_LEGACY
                    },
                ),
                source = item.getString("source"),
                savedAt = item.getString("savedAt"),
            )
        } }
        val attestations = (0 until attestationsJson.length()).map { i -> attestationsJson.getJSONObject(i).let { item ->
            PerformanceAttestation(item.getString("id"), item.getInt("pillar"), LocalDate.parse(item.getString("start")),
                LocalDate.parse(item.getString("end")), item.getString("source"), item.getString("reviewedAt"),
                item.getString("inputFingerprint"), item.getBoolean("externalFlowsComplete"), item.getBoolean("valuationRolesConfirmed"))
        } }
        fun JSONObject.nullable(key: String) = if (isNull(key)) null else getString(key)
        // v12–v13 stored the provider under "provider", v14 under "providerId". An id this version does not know is kept verbatim.
        val providerKey = if (version >= 14) "providerId" else "provider"
        val unknownMovements = mutableListOf<RawJsonObject>()
        val unknownConnections = mutableListOf<RawJsonObject>()
        val unknownCategories = mutableListOf<RawJsonObject>()
        // An unknown provider's row from a v12–v13 file is kept with only its key renamed to the v14 "providerId".
        fun keep(o: JSONObject): RawJsonObject = RawJsonObject.of(if (version >= 14) o else JSONObject(o.toString()).apply { put("providerId", remove("provider")) })
        val external = (0 until externalJson.length()).mapNotNull { i -> externalJson.getJSONObject(i).let { m ->
            val provider = ImportProvider.byId(m.getString(providerKey))
                ?: return@mapNotNull null.also { unknownMovements += keep(m) }
            val signed = m.getString("signedCents")
            require(signed.matches(Regex("-?(?:0|[1-9][0-9]{0,17})"))) { "Importo importato non valido" }
            ExternalMovement(m.getString("id"), provider, m.getString("accountId"),
                LocalDate.parse(m.getString("date")), signed.toLong(), m.getString("currency"), m.getString("description"),
                ExternalKind.valueOf(m.getString("kind")), m.nullable("rawType"), m.nullable("isin"),
                m.nullable("quantity")?.let(::BigDecimal), ExternalStatus.valueOf(m.getString("status")),
                m.nullable("linkedRecordId"), m.getString("importedAt"), m.nullable("category"), m.nullable("categoryId"),
                m.nullable("categorySource")?.let(CategorySource::valueOf), m.nullable("merchantKey"), m.nullable("role")?.let(MovementRole::valueOf))
        } }
        val connectionItems = (0 until connectionsJson.length()).mapNotNull { i -> connectionsJson.getJSONObject(i).let { c ->
            val provider = ImportProvider.byId(c.getString(providerKey))
                ?: return@mapNotNull null.also { unknownConnections += keep(c) }
            Connection(provider, c.getString("accountId"), c.nullable("lastSyncAt"),
                c.nullable("coverageFrom")?.let(LocalDate::parse), c.nullable("coverageTo")?.let(LocalDate::parse), c.nullable("lastResult"),
                if (c.has("securitiesAccountId")) c.nullable("securitiesAccountId") else null)
        } }
        val planningBase = if (version >= 12) PlanningCodec.decodePlanning(data.getJSONObject("planning"), version, unknownCategories) else Planning()
        // v12–v13 kept salary rules inside planning; v14 stores income rules at the top level.
        val planning = if (version >= 14) planningBase.copy(incomeRules = PlanningCodec.decodeIncomeRules(data.getJSONArray("incomeRules"))) else planningBase
        val pension = if (version >= 12) PlanningCodec.decodePension(data.getJSONObject("pension")) else PensionData()
        val holdings = if (version >= 12) PlanningCodec.decodeHoldings(data.getJSONArray("holdings")) else emptyList()
        val installments = if (version >= 13) InstallmentCodec.decodePlans(data.getJSONArray("installments")) else emptyList()
        val installmentSync = if (version >= 13) InstallmentCodec.decodeSync(data.getJSONArray("installmentSync")) else emptyList()
        val positions = if (version >= 14) PositionCodec.decode(data.getJSONArray("positions")) else emptyList()
        val funds = if (version >= 14) FundCodec.decode(data.getJSONArray("funds")) else emptyList()
        val rulesData = if (version >= 14) RulesData(RawJsonArray.of(data.getJSONArray("packVersions")), RawJsonArray.of(data.getJSONArray("personalParams")),
            RawJsonArray.of(data.getJSONArray("snapshots")), RawJsonArray.of(data.getJSONArray("payslipLineClasses"))) else RulesData()
        val decoded = Archive(events, valuations, points, changes, planItems, accountItems, prefillFacts, accountValuationItems, instruments, trades, transfers, projections, attestations, external, connectionItems,
            planning, pension, holdings, installments, installmentSync, positions, funds, rulesData,
            UnknownProviderRows(unknownMovements, unknownConnections, unknownCategories))
        return (if (version < 14) ArchiveMigration.toV14(decoded) else decoded).also(::validate)
    }

    private fun exactInt(obj: JSONObject, key: String): Int {
        val raw = obj.get(key)
        require(raw is Number && raw.toString().matches(Regex("0|[1-9][0-9]*"))) { "Anno non valido" }
        return raw.toString().toInt()
    }

    private fun exactCentsString(obj: JSONObject, key: String): Long {
        val raw = obj.get(key)
        require(raw is String && raw.matches(Regex("0|[1-9][0-9]*"))) { "Importo scenario non valido" }
        return raw.toLong()
    }

    private fun exactRate(obj: JSONObject, key: String): BigDecimal {
        val raw = obj.get(key)
        require(raw is String && raw.length <= 32 && raw.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?"))) { "Percentuale scenario non valida" }
        return BigDecimal(raw)
    }

    private fun validate(data: Archive) {
        require(data.events.size <= 100_000 && data.valuations.size <= 100_000 && data.indices.size <= 3000 && data.audit.size <= 100_000 && data.plan.size <= 100_000 && data.accounts.size <= 100_000 && data.accountValuations.size <= 100_000 && data.instruments.size <= 100_000 && data.trades.size <= 100_000 && data.transfers.size <= 100_000 && data.projections.size <= SavedProjection.MAX_COUNT && data.performanceAttestations.size <= PerformanceAttestation.MAX_COUNT)
        require(data.audit.all { it.at.length <= 50 && it.kind.length <= 50 && it.previous.length <= 2000 })
        require(data.events.map { it.id }.distinct().size == data.events.size) { "ID movimento duplicato" }
        require(data.valuations.map { it.pillar to it.date }.distinct().size == data.valuations.size) { "Valutazione duplicata" }
        require(data.accountValuations.map { it.accountId to it.date }.distinct().size == data.accountValuations.size) { "Valutazione conto duplicata" }
        require(data.indices.map { it.month }.distinct().size == data.indices.size) { "Indice mensile duplicato" }
        require(data.plan.map { it.id }.distinct().size == data.plan.size)
        require(data.accounts.map { it.id }.distinct().size == data.accounts.size) { "ID account duplicato" }
        require(data.accounts.all { it.id.length <= 100 && it.name.length <= 200 && it.source.length <= 500 })
        require(data.instruments.map { it.id }.distinct().size == data.instruments.size) { "ID strumento duplicato" }
        require(data.instruments.mapNotNull { it.identifier?.trim()?.lowercase() }.distinct().size == data.instruments.count { it.identifier != null }) { "Identificativo strumento duplicato" }
        require(data.trades.map { it.id }.distinct().size == data.trades.size) { "ID operazione duplicato" }
        require(data.transfers.map { it.id }.distinct().size == data.transfers.size) { "ID trasferimento duplicato" }
        require(data.projections.map { it.id }.distinct().size == data.projections.size) { "ID scenario duplicato" }
        require(data.performanceAttestations.map { it.id }.distinct().size == data.performanceAttestations.size) { "ID verifica performance duplicato" }
        require(data.performanceAttestations.map { Triple(it.pillar, it.start, it.end) }.distinct().size == data.performanceAttestations.size) { "Finestra performance duplicata" }
        val accountsById = data.accounts.associateBy { it.id }
        val instrumentsById = data.instruments.associateBy { it.id }
        data.transfers.forEach { transfer ->
            require(accountsById.containsKey(transfer.fromAccountId)) { "Conto di origine del trasferimento non disponibile" }
            require(accountsById.containsKey(transfer.toAccountId)) { "Conto di destinazione del trasferimento non disponibile" }
            require(!transfer.date.isAfter(LocalDate.now())) { "La data del trasferimento non può essere futura" }
        }
        data.instruments.forEach { require(it.id.length <= 100 && it.name.length <= 200 && (it.identifier?.length ?: 0) <= 100 && it.source.length <= 500) }
        data.trades.forEach { trade ->
            val account = accountsById[trade.accountId] ?: throw IllegalArgumentException("Conto dell'operazione non disponibile")
            require(account.pillar in 1..4)
            require(instrumentsById.containsKey(trade.instrumentId)) { "Strumento dell'operazione non disponibile" }
            require(trade.source.length <= 500 && !trade.date.isAfter(LocalDate.now())) { "Operazione non valida" }
        }
        data.events.filter { it.accountId != null }.forEach { event ->
            val account = accountsById[event.accountId] ?: throw IllegalArgumentException("Conto associato al movimento non disponibile")
            require(account.pillar == event.pillar) { "Il conto deve appartenere allo stesso pilastro del movimento" }
        }
        data.accountValuations.forEach { value ->
            require(value.accountId.length <= 100 && value.source.length <= 500) { "Valutazione conto non valida" }
            require(accountsById.containsKey(value.accountId)) { "Valutazione associata a un conto non disponibile" }
            require(!value.date.isAfter(LocalDate.now())) { "La data della valutazione conto non può essere futura" }
        }
        data.prefillFacts?.validate()
        require(data.plan.all { it.id.length <= 100 && it.label.length <= 200 && it.source.length <= 500 && it.month.year in 2026..2085 && (it.status == PlanStatus.SCENARIO || !it.month.isAfter(YearMonth.now())) })
        data.plan.fold(0L) { acc, item -> Math.addExact(acc, item.amount.cents) }
        require(data.indices.map { it.source.trim() }.distinct().size <= 1) { "Gli indici devono appartenere alla stessa serie e base statistica" }
        require(data.indices.all { it.source.length <= 500 && !it.month.isAfter(YearMonth.now()) })
        // LedgerEvent.TRANSFER remains unsupported; account-to-account movements use sourced AccountTransfer records.
        require(data.events.none { it.type == EventType.TRANSFER }) { "Trasferimenti non ancora supportati" }
        require(data.events.all { it.source.length <= 500 && it.id.length <= 100 && !it.date.isAfter(LocalDate.now()) })
        require(data.valuations.all { it.source.length <= 500 && !it.date.isAfter(LocalDate.now()) })
        require(data.events.none { it.type == EventType.EMPLOYER_CONTRIBUTION && it.pillar != 4 })
        data.events.forEach {
            require(it.type in setOf(
                EventType.PERSONAL_CONTRIBUTION,
                EventType.EMPLOYER_CONTRIBUTION,
                EventType.WITHDRAWAL,
                EventType.INCOME,
                EventType.FEE
            )) { "Tipo di movimento non supportato" }
        }
        data.events.fold(0L) { acc, item -> Math.addExact(acc, item.amount.cents) }
        data.valuations.fold(0L) { acc, item -> Math.addExact(acc, item.amount.cents) }
        data.trades.fold(0L) { acc, item -> Math.addExact(acc, item.grossAmount.cents) }
        data.trades.fold(0L) { acc, item -> Math.addExact(acc, item.fees.cents) }
        data.trades.forEach { Math.addExact(it.grossAmount.cents, it.fees.cents) }
        data.transfers.fold(0L) { acc, transfer -> Math.addExact(acc, transfer.amount.cents) }
        require(data.externalMovements.size <= 100_000 && data.connections.size <= 1000)
        require(data.externalMovements.map { it.id }.distinct().size == data.externalMovements.size) { "Movimento importato duplicato" }
        require(data.connections.map { it.provider to it.accountId }.distinct().size == data.connections.size) { "Collegamento duplicato" }
        val ledgerIds = data.events.map { it.id }.toSet() + data.trades.map { it.id } + data.transfers.map { it.id }
        data.externalMovements.forEach { m ->
            require(accountsById.containsKey(m.accountId)) { "Movimento importato associato a un conto non disponibile" }
            require(!m.date.isAfter(LocalDate.now()) && m.currency == "EUR") { "Movimento importato non valido" }
            require(m.linkedRecordId == null || m.linkedRecordId in ledgerIds) { "Registrazione collegata all'importazione non disponibile" }
        }
        data.connections.forEach { require(accountsById.containsKey(it.accountId) && (it.securitiesAccountId == null || accountsById.containsKey(it.securitiesAccountId))) { "Collegamento associato a un conto non disponibile" } }
        PlanningCodec.validate(data)
        InstallmentCodec.validate(data)
        FundCodec.validate(data)
        require(data.positions.size <= 1000 && data.funds.size <= 1000) { "Troppe posizioni o fondi" }
        require(data.unknownProviderRows.externalMovements.size + data.externalMovements.size <= 100_000 &&
            data.unknownProviderRows.connections.size + data.connections.size <= 1000) { "Troppi movimenti importati" }
    }
}

/** App-private storage. The archive is not encrypted; Android's app sandbox is the protection. */
class LocalArchive(context: Context) {
    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, "portfolio-v1.json"))
    private fun candidates() = listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"))
    private val snapshot = UpdateSnapshot(dir, candidates(), updateId(context), prefsMarker(context), log = { android.util.Log.w("Pilastri", it) })
    fun load(): Archive {
        snapshot.ensure() // before any write; later calls are no-ops
        return if (candidates().none { it.exists() }) Archive() else ArchiveCodec.decode(file.openRead().use { it.readBytes() })
    }
    fun save(data: Archive) {
        snapshot.ensure() // the first write in a process waits for the snapshot attempt (success or failure)
        val bytes = ArchiveCodec.encode(data) // validate completely before touching existing data
        // Before the first v14 write over an older file, keep that file untouched beside it (never overwritten, never deleted).
        if (!currentOnDisk) PremigrationCopy.preserve(dir, candidates().firstOrNull { it.exists() }?.readBytes())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (t: Throwable) { file.failWrite(stream); throw t }
        currentOnDisk = true
    }
    /** True once this instance has written the current version, so later saves skip re-reading the old file. */
    @Volatile private var currentOnDisk = false
    companion object {
        const val REPLACED_PREFIX = "replaced-"
        const val KEPT_REPLACED_COPIES = 3
        @Suppress("DEPRECATION")
        private fun updateId(context: Context): String = try {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            "${p.lastUpdateTime}-${p.longVersionCode}"
        } catch (_: Throwable) { "unknown" }
        private fun prefsMarker(context: Context) = object : SnapshotMarker {
            private val prefs = context.getSharedPreferences("archive_snapshot", Context.MODE_PRIVATE)
            override fun get(): String? = prefs.getString("last_update", null)
            override fun set(value: String) { check(prefs.edit().putString("last_update", value).commit()) { "marker non scritto" } }
        }
    }
    /**
     * Before a replace-restore: copies the current archive files beside them as `replaced-<millis>-<name>` (flushed to disk), keeping
     * the newest [KEPT_REPLACED_COPIES] sets; older sets are deleted, the unreadable-* copies are never touched. Throws when a copy fails,
     * so the caller refuses the restore and the current data stays as it is.
     */
    fun keepCopyBeforeReplace(now: Long = System.currentTimeMillis()) {
        val parent = file.baseFile.parentFile ?: return
        candidates().filter { it.exists() }.forEach { old ->
            val copy = File(parent, "$REPLACED_PREFIX$now-${old.name}")
            old.inputStream().use { source -> copy.outputStream().use { target -> source.copyTo(target); target.fd.sync() } }
        }
        val sets = parent.listFiles { f -> f.name.startsWith(REPLACED_PREFIX) }.orEmpty().groupBy { it.name.removePrefix(REPLACED_PREFIX).substringBefore('-') }
        sets.keys.sortedByDescending { it.toLongOrNull() ?: 0L }.drop(KEPT_REPLACED_COPIES).forEach { k -> sets.getValue(k).forEach { it.delete() } }
    }
    fun restoreAfterReadFailure(data: Archive) {
        ArchiveCodec.encode(data) // never touch the old file before the candidate validates
        candidates().filter { it.exists() }.forEach { old ->
            val copy = File(file.baseFile.parentFile, "unreadable-${java.util.UUID.randomUUID()}-${old.name}")
            old.inputStream().use { source -> copy.outputStream().use { target -> source.copyTo(target); target.fd.sync() } }
        }
        save(data)
    }
}
