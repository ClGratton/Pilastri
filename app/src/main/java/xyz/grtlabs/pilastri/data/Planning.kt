package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

/* Council 2026-10-28 data model: spending categories, budgets, goals, pillar targets, salary rules, holdings, pensions. */

enum class CategoryKind { SPEND, INCOME, NEUTRAL }
enum class Essential { ESSENTIAL, DISCRETIONARY, UNSET }
enum class CategorySource { OVERRIDE, RULE, PROVIDER, AGENT }
enum class MovementRole { SPEND, SALARY, INCOME, OWN_TRANSFER, FUND_CONTRIBUTION, TAX, FEE }
enum class RuleOrigin { USER, AGENT }

data class Category(val id: String, val name: String, val kind: CategoryKind, val essential: Essential = Essential.UNSET, val parentId: String? = null) {
    init { require(id.isNotBlank() && id.length <= 60 && name.isNotBlank() && name.length <= 60 && (parentId == null || parentId != id)) }
}

/** Matches on a normalized merchant key or, failing that, on description text; the most specific, highest priority rule wins. */
data class CategoryRule(
    val id: String, val pattern: String, val categoryId: String, val origin: RuleOrigin,
    val accountId: String? = null, val minCents: Long? = null, val maxCents: Long? = null, val priority: Int = 0,
) {
    init {
        require(id.isNotBlank() && id.length <= 100 && pattern.isNotBlank() && pattern.length <= 120)
        require(minCents == null || maxCents == null || minCents <= maxCents)
    }
    fun matches(m: ExternalMovement): Boolean {
        if (accountId != null && accountId != m.accountId) return false
        val cents = kotlin.math.abs(m.signedCents)
        if (minCents != null && cents < minCents) return false
        if (maxCents != null && cents > maxCents) return false
        val needle = merchantKey(pattern)
        return (m.merchantKey ?: merchantKey(m.description)).contains(needle)
    }
}

/** The provider's own label mapped onto the user's taxonomy. */
data class ProviderCategory(val provider: ImportProvider, val rawLabel: String, val categoryId: String) {
    init { require(rawLabel.isNotBlank() && rawLabel.length <= 60) }
}

data class Budget(val categoryId: String, val monthlyCents: Long, val effectiveFrom: YearMonth) { init { require(monthlyCents >= 0) } }

data class Goal(val id: String, val name: String, val targetCents: Long, val targetDate: LocalDate?, val accountId: String? = null, val earmarkCents: Long? = null) {
    init { require(id.isNotBlank() && name.isNotBlank() && name.length <= 80 && targetCents > 0 && (earmarkCents == null || earmarkCents >= 0)) }
}

enum class TargetKind { BUFFER_CENTS, MONTHS }
/** A pillar's own target; [userSet] false means the labelled default ("predefinito"). */
data class PillarTarget(val pillar: Int, val kind: TargetKind, val cents: Long? = null, val months: BigDecimal? = null, val userSet: Boolean) {
    init {
        require(pillar in 1..3)
        require(if (kind == TargetKind.BUFFER_CENTS) cents != null && cents > 0 && months == null else months != null && months.signum() > 0 && cents == null)
    }
}

/**
 * A user-confirmed description of a recurring income credit (Council 4 d; generalises the v13 salary rule). [title] names the card
 * as the person's income is called (Stipendio, Pensione, Compenso, Accredito); [positionId] links the position it comes from when
 * known. Only confirmed rules classify income; the matching is the v13 salary matching, unchanged.
 */
data class IncomeRule(
    val id: String, val title: String, val accountId: String, val payerPattern: String, val dayFrom: Int, val dayTo: Int,
    val minCents: Long, val maxCents: Long, val confirmedAt: String?, val positionId: String? = null,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && title.length <= 40 && payerPattern.isNotBlank() && dayFrom in 1..31 && dayTo in 1..31 && minCents in 1..maxCents)
        require(positionId == null || (positionId.isNotBlank() && positionId.length <= 120))
    }
    fun matches(m: ExternalMovement): Boolean = confirmedAt != null && m.accountId == accountId && m.signedCents in minCents..maxCents &&
        m.date.dayOfMonth.let { d -> if (dayFrom <= dayTo) d in dayFrom..dayTo else d >= dayFrom || d <= dayTo } &&
        merchantKey(m.description).contains(merchantKey(payerPattern))

    companion object {
        /** The title a v12–v13 salary rule migrates with: the collection it was stored in stated "salary". */
        const val LEGACY_SALARY_TITLE = "Stipendio"
    }
}

data class Planning(
    val categories: List<Category> = emptyList(),
    val rules: List<CategoryRule> = emptyList(),
    val providerCategories: List<ProviderCategory> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val targets: List<PillarTarget> = emptyList(),
    val incomeRules: List<IncomeRule> = emptyList(),
) {
    /** Read-only compatibility for screens written before income rules (ui/screens/Pillar.kt); new code reads [incomeRules]. */
    val salaryRules: List<IncomeRule> get() = incomeRules
}

/** A provider's dated position in one instrument; it is not a trade and not a contribution. */
data class Holding(val accountId: String, val isin: String, val name: String, val quantity: BigDecimal, val priceCents: Long?, val date: LocalDate, val source: String) {
    init { require(accountId.isNotBlank() && isin.matches(Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")) && quantity.signum() >= 0 && (priceCents == null || priceCents >= 0)) }
}

enum class FundRegime { PUBLIC, PRIVATE, UNKNOWN }

/** One dated pension statement. Employer and TFR stay null when the document does not state them: never computed. */
data class PensionStatement(
    val id: String, val accountId: String, val positionDate: LocalDate, val positionCents: Long,
    val ownCents: Long?, val employerCents: Long?, val tfrCents: Long?, val costsCents: Long?, val taxesCents: Long?,
    val periodFrom: LocalDate?, val periodTo: LocalDate?, val documentHash: String?, val source: String,
    val extractedByAgent: Boolean, val confirmedAt: String?,
) {
    init {
        require(id.isNotBlank() && accountId.isNotBlank() && positionCents >= 0 && source.isNotBlank() && source.length <= 500)
        listOf(ownCents, employerCents, tfrCents, costsCents, taxesCents).forEach { require(it == null || it >= 0) }
        require(documentHash == null || documentHash.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Fund facts that change which rules apply. An elected rate is not a payment. */
data class FundProfile(val accountId: String, val regime: FundRegime, val tfrElection: String?, val effectiveFrom: LocalDate?, val electedRatePercent: BigDecimal?, val source: String) {
    init { require(accountId.isNotBlank() && source.isNotBlank() && (electedRatePercent == null || electedRatePercent.signum() >= 0)) }
}

data class DeductionYear(val taxYear: Int, val ceilingCents: Long, val source: String, val sourceDate: LocalDate) {
    init { require(taxYear in 2000..2100 && ceilingCents > 0 && source.isNotBlank()) }
}

/** INPS's own simulator output, always a scenario. */
data class InpsEstimate(val asOf: LocalDate, val monthlyNetCents: Long, val retirementDate: LocalDate?, val assumptions: String, val source: String) {
    init { require(monthlyNetCents >= 0 && assumptions.length <= 1000 && source.isNotBlank()) }
}

data class PensionData(
    val statements: List<PensionStatement> = emptyList(),
    val profiles: List<FundProfile> = emptyList(),
    val deductionYears: List<DeductionYear> = emptyList(),
    val inps: List<InpsEstimate> = emptyList(),
)

/** Lower-case words without digits, punctuation or card/terminal noise: stable enough to recognize a merchant across months. */
fun merchantKey(text: String): String = text.lowercase()
    .replace(Regex("\\b(pagamento|pos|carta|card|n\\.?|del|data|ora|op|bonifico|sepa|addebito)\\b"), " ")
    .replace(Regex("[^a-zàèéìòù ]"), " ")
    .split(' ').filter { it.length >= 3 }.take(4).joinToString(" ")

/** Category precedence (Council): user override > user rule > provider map > agent suggestion. */
fun Planning.categorize(m: ExternalMovement): Pair<String?, CategorySource?> {
    if (m.categorySource == CategorySource.OVERRIDE && m.categoryId != null) return m.categoryId to CategorySource.OVERRIDE
    rules.filter { it.origin == RuleOrigin.USER && it.matches(m) }.maxWithOrNull(compareBy({ it.priority }, { it.pattern.length }))
        ?.let { return it.categoryId to CategorySource.RULE }
    m.category?.let { raw -> providerCategories.firstOrNull { it.provider == m.provider && it.rawLabel.equals(raw, ignoreCase = true) } }
        ?.let { return it.categoryId to CategorySource.PROVIDER }
    rules.filter { it.origin == RuleOrigin.AGENT && it.matches(m) }.maxByOrNull { it.priority }
        ?.let { return it.categoryId to CategorySource.AGENT }
    return null to null
}

internal object PlanningCodec {
    private fun JSONObject.str(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.long(k: String): Long? = if (isNull(k)) null else getString(k).also { require(it.matches(Regex("-?(0|[1-9][0-9]{0,17})"))) }.toLong()
    private fun Long?.js(): Any = this?.toString() ?: JSONObject.NULL
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
    private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> { require(length() <= 100_000); return (0 until length()).map { f(getJSONObject(it)) } }

    /** v14 planning: provider categories under "providerId" (unknown ones written back verbatim); income rules live at the top level. */
    fun encodePlanning(p: Planning, unknownCategories: List<RawJsonObject> = emptyList()): JSONObject = JSONObject()
        .put("categories", JSONArray().apply { p.categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name).put("essential", it.essential.name).put("parentId", it.parentId.orNull())) } })
        .put("rules", JSONArray().apply { p.rules.forEach { put(JSONObject().put("id", it.id).put("pattern", it.pattern).put("categoryId", it.categoryId).put("origin", it.origin.name).put("accountId", it.accountId.orNull()).put("minCents", it.minCents.js()).put("maxCents", it.maxCents.js()).put("priority", it.priority)) } })
        .put("providerCategories", JSONArray().apply { p.providerCategories.forEach { put(JSONObject().put("providerId", it.provider.id).put("rawLabel", it.rawLabel).put("categoryId", it.categoryId)) }
            unknownCategories.forEach { put(it.toJsonObject()) } })
        .put("budgets", JSONArray().apply { p.budgets.forEach { put(JSONObject().put("categoryId", it.categoryId).put("monthlyCents", it.monthlyCents.toString()).put("effectiveFrom", it.effectiveFrom.toString())) } })
        .put("goals", JSONArray().apply { p.goals.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("targetCents", it.targetCents.toString()).put("targetDate", it.targetDate?.toString().orNull()).put("accountId", it.accountId.orNull()).put("earmarkCents", it.earmarkCents.js())) } })
        .put("targets", JSONArray().apply { p.targets.forEach { put(JSONObject().put("pillar", it.pillar).put("kind", it.kind.name).put("cents", it.cents.js()).put("months", it.months?.toPlainString().orNull()).put("userSet", it.userSet)) } })

    fun encodeIncomeRules(rules: List<IncomeRule>): JSONArray = JSONArray().apply { rules.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("accountId", it.accountId).put("payerPattern", it.payerPattern).put("dayFrom", it.dayFrom).put("dayTo", it.dayTo).put("minCents", it.minCents.toString()).put("maxCents", it.maxCents.toString()).put("confirmedAt", it.confirmedAt.orNull()).put("positionId", it.positionId.orNull())) } }

    fun decodeIncomeRules(a: JSONArray): List<IncomeRule> = a.map { IncomeRule(it.getString("id"), it.getString("title"), it.getString("accountId"), it.getString("payerPattern"), it.getInt("dayFrom"), it.getInt("dayTo"), it.long("minCents")!!, it.long("maxCents")!!, it.str("confirmedAt"), it.str("positionId")) }

    /**
     * Planning as stored by v12+. Before v14 the provider key was "provider" and salary rules lived here (they become income rules
     * titled [IncomeRule.LEGACY_SALARY_TITLE], every other field unchanged). Provider categories of an unknown provider go to [unknown].
     */
    fun decodePlanning(o: JSONObject, version: Int, unknown: MutableList<RawJsonObject>): Planning = Planning(
        categories = o.getJSONArray("categories").map { Category(it.getString("id"), it.getString("name"), CategoryKind.valueOf(it.getString("kind")), Essential.valueOf(it.getString("essential")), it.str("parentId")) },
        rules = o.getJSONArray("rules").map { CategoryRule(it.getString("id"), it.getString("pattern"), it.getString("categoryId"), RuleOrigin.valueOf(it.getString("origin")), it.str("accountId"), it.long("minCents"), it.long("maxCents"), it.getInt("priority")) },
        providerCategories = o.getJSONArray("providerCategories").map { it }.mapNotNull {
            val provider = ImportProvider.byId(it.getString(if (version >= 14) "providerId" else "provider"))
            if (provider == null) { unknown += RawJsonObject.of(if (version >= 14) it else JSONObject(it.toString()).apply { put("providerId", remove("provider")) }); null } else ProviderCategory(provider, it.getString("rawLabel"), it.getString("categoryId"))
        },
        budgets = o.getJSONArray("budgets").map { Budget(it.getString("categoryId"), it.long("monthlyCents")!!, YearMonth.parse(it.getString("effectiveFrom"))) },
        goals = o.getJSONArray("goals").map { Goal(it.getString("id"), it.getString("name"), it.long("targetCents")!!, it.str("targetDate")?.let(LocalDate::parse), it.str("accountId"), it.long("earmarkCents")) },
        targets = o.getJSONArray("targets").map { PillarTarget(it.getInt("pillar"), TargetKind.valueOf(it.getString("kind")), it.long("cents"), it.str("months")?.let(::BigDecimal), it.getBoolean("userSet")) },
        incomeRules = if (version >= 14) { require(!o.has("salaryRules")) { "Formato o versione non supportati" }; emptyList() }
            else o.getJSONArray("salaryRules").map { IncomeRule(it.getString("id"), IncomeRule.LEGACY_SALARY_TITLE, it.getString("accountId"), it.getString("payerPattern"), it.getInt("dayFrom"), it.getInt("dayTo"), it.long("minCents")!!, it.long("maxCents")!!, it.str("confirmedAt")) },
    )

    fun encodePension(p: PensionData): JSONObject = JSONObject()
        .put("statements", JSONArray().apply { p.statements.forEach { put(JSONObject().put("id", it.id).put("accountId", it.accountId).put("positionDate", it.positionDate.toString()).put("positionCents", it.positionCents.toString())
            .put("ownCents", it.ownCents.js()).put("employerCents", it.employerCents.js()).put("tfrCents", it.tfrCents.js()).put("costsCents", it.costsCents.js()).put("taxesCents", it.taxesCents.js())
            .put("periodFrom", it.periodFrom?.toString().orNull()).put("periodTo", it.periodTo?.toString().orNull()).put("documentHash", it.documentHash.orNull()).put("source", it.source)
            .put("extractedByAgent", it.extractedByAgent).put("confirmedAt", it.confirmedAt.orNull())) } })
        .put("profiles", JSONArray().apply { p.profiles.forEach { put(JSONObject().put("accountId", it.accountId).put("regime", it.regime.name).put("tfrElection", it.tfrElection.orNull()).put("effectiveFrom", it.effectiveFrom?.toString().orNull()).put("electedRatePercent", it.electedRatePercent?.toPlainString().orNull()).put("source", it.source)) } })
        .put("deductionYears", JSONArray().apply { p.deductionYears.forEach { put(JSONObject().put("taxYear", it.taxYear).put("ceilingCents", it.ceilingCents.toString()).put("source", it.source).put("sourceDate", it.sourceDate.toString())) } })
        .put("inps", JSONArray().apply { p.inps.forEach { put(JSONObject().put("asOf", it.asOf.toString()).put("monthlyNetCents", it.monthlyNetCents.toString()).put("retirementDate", it.retirementDate?.toString().orNull()).put("assumptions", it.assumptions).put("source", it.source)) } })

    fun decodePension(o: JSONObject): PensionData = PensionData(
        statements = o.getJSONArray("statements").map { PensionStatement(it.getString("id"), it.getString("accountId"), LocalDate.parse(it.getString("positionDate")), it.long("positionCents")!!,
            it.long("ownCents"), it.long("employerCents"), it.long("tfrCents"), it.long("costsCents"), it.long("taxesCents"),
            it.str("periodFrom")?.let(LocalDate::parse), it.str("periodTo")?.let(LocalDate::parse), it.str("documentHash"), it.getString("source"),
            it.getBoolean("extractedByAgent"), it.str("confirmedAt")) },
        profiles = o.getJSONArray("profiles").map { FundProfile(it.getString("accountId"), FundRegime.valueOf(it.getString("regime")), it.str("tfrElection"), it.str("effectiveFrom")?.let(LocalDate::parse), it.str("electedRatePercent")?.let(::BigDecimal), it.getString("source")) },
        deductionYears = o.getJSONArray("deductionYears").map { DeductionYear(it.getInt("taxYear"), it.long("ceilingCents")!!, it.getString("source"), LocalDate.parse(it.getString("sourceDate"))) },
        inps = o.getJSONArray("inps").map { InpsEstimate(LocalDate.parse(it.getString("asOf")), it.long("monthlyNetCents")!!, it.str("retirementDate")?.let(LocalDate::parse), it.getString("assumptions"), it.getString("source")) },
    )

    fun encodeHoldings(h: List<Holding>): JSONArray = JSONArray().apply { h.forEach { put(JSONObject().put("accountId", it.accountId).put("isin", it.isin).put("name", it.name).put("quantity", it.quantity.toPlainString()).put("priceCents", it.priceCents.js()).put("date", it.date.toString()).put("source", it.source)) } }
    fun decodeHoldings(a: JSONArray): List<Holding> = a.map { Holding(it.getString("accountId"), it.getString("isin"), it.getString("name"), BigDecimal(it.getString("quantity")), it.long("priceCents"), LocalDate.parse(it.getString("date")), it.getString("source")) }

    /** Referential integrity for the planning, pension and holding records. */
    fun validate(a: Archive) {
        val p = a.planning
        val accountIds = a.accounts.map { it.id }.toSet()
        val categoryIds = p.categories.map { it.id }.toSet()
        require(categoryIds.size == p.categories.size) { "Categoria duplicata" }
        require(p.categories.all { it.parentId == null || it.parentId in categoryIds }) { "Categoria madre non disponibile" }
        require(p.rules.map { it.id }.distinct().size == p.rules.size && p.rules.all { it.categoryId in categoryIds && (it.accountId == null || it.accountId in accountIds) }) { "Regola di categoria non valida" }
        require(p.providerCategories.all { it.categoryId in categoryIds } && p.providerCategories.map { it.provider to it.rawLabel.lowercase() }.distinct().size == p.providerCategories.size) { "Mappa categorie non valida" }
        require(p.budgets.all { it.categoryId in categoryIds } && p.budgets.map { it.categoryId to it.effectiveFrom }.distinct().size == p.budgets.size) { "Budget non valido" }
        require(p.goals.map { it.id }.distinct().size == p.goals.size && p.goals.all { it.accountId == null || it.accountId in accountIds }) { "Obiettivo non valido" }
        require(p.targets.map { it.pillar }.distinct().size == p.targets.size) { "Obiettivo di pilastro duplicato" }
        val positionIds = a.positions.map { it.id }.toSet()
        require(p.incomeRules.map { it.id }.distinct().size == p.incomeRules.size && p.incomeRules.all { it.accountId in accountIds && (it.positionId == null || it.positionId in positionIds) }) { "Regola delle entrate non valida" }
        require(a.externalMovements.all { it.categoryId == null || it.categoryId in categoryIds }) { "Categoria del movimento non disponibile" }
        require(a.holdings.all { it.accountId in accountIds } && a.holdings.map { Triple(it.accountId, it.isin, it.date) }.distinct().size == a.holdings.size) { "Posizione non valida" }
        val pension = a.pension
        require(pension.statements.map { it.id }.distinct().size == pension.statements.size && pension.statements.all { it.accountId in accountIds && !it.positionDate.isAfter(LocalDate.now()) }) { "Estratto previdenza non valido" }
        require(pension.profiles.all { it.accountId in accountIds } && pension.profiles.map { it.accountId }.distinct().size == pension.profiles.size) { "Profilo fondo non valido" }
        require(pension.deductionYears.map { it.taxYear }.distinct().size == pension.deductionYears.size) { "Anno fiscale duplicato" }
    }
}
