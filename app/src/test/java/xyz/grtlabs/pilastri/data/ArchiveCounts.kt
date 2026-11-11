package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.domain.EventType

/**
 * Counts only, never values, names, ids, dates or labels: what a stored payload holds ("before") and what the decoded and migrated
 * archive holds ("after"). Shared by the synthetic migration test and the real-archive dry run.
 */
internal object ArchiveCounts {
    /** Collections that exist before v14; their before and after counts must be equal. */
    val PRESERVED = listOf("accounts", "events") + EventType.entries.map { "events.${it.name}" } + listOf(
        "valuations", "indices", "audit", "plan", "prefillFacts.reported", "accountValuations", "instruments", "trades", "transfers",
        "projections", "performanceAttestations", "externalMovements", "connections", "planning.categories", "planning.rules",
        "planning.providerCategories", "planning.budgets", "planning.goals", "planning.targets", "pension.statements",
        "pension.profiles", "pension.deductionYears", "pension.inps", "holdings", "installments", "installmentSync",
        "salaryOrIncomeRules")
    val ADDED = listOf("positions", "funds", "legacyFundAccounts") + RulesData.KEYS

    private fun JSONObject.len(k: String): Int = optJSONArray(k)?.length() ?: 0
    private fun JSONObject.sub(k: String): JSONObject = optJSONObject(k) ?: JSONObject()
    private fun reported(o: JSONObject?): Int =
        if (o == null) 0 else o.keys().asSequence().count { o.getJSONObject(it).optString("status") == "REPORTED" }

    fun before(payload: JSONObject): Map<String, Int> {
        val events = payload.optJSONArray("events") ?: JSONArray()
        val types = (0 until events.length()).map { events.getJSONObject(it).getString("type") }
        val accounts = payload.optJSONArray("accounts") ?: JSONArray()
        val planning = payload.sub("planning")
        val pension = payload.sub("pension")
        return linkedMapOf("accounts" to accounts.length(), "events" to events.length()) +
            EventType.entries.associate { t -> "events.${t.name}" to types.count { it == t.name } } + linkedMapOf(
            "valuations" to payload.len("valuations"), "indices" to payload.len("indices"), "audit" to payload.len("audit"),
            "plan" to payload.len("plan"), "prefillFacts.reported" to reported(payload.optJSONObject("prefillFacts")),
            "accountValuations" to payload.len("accountValuations"), "instruments" to payload.len("instruments"),
            "trades" to payload.len("trades"), "transfers" to payload.len("transfers"), "projections" to payload.len("projections"),
            "performanceAttestations" to payload.len("performanceAttestations"), "externalMovements" to payload.len("externalMovements"),
            "connections" to payload.len("connections"), "planning.categories" to planning.len("categories"),
            "planning.rules" to planning.len("rules"), "planning.providerCategories" to planning.len("providerCategories"),
            "planning.budgets" to planning.len("budgets"), "planning.goals" to planning.len("goals"), "planning.targets" to planning.len("targets"),
            "pension.statements" to pension.len("statements"), "pension.profiles" to pension.len("profiles"),
            "pension.deductionYears" to pension.len("deductionYears"), "pension.inps" to pension.len("inps"),
            "holdings" to payload.len("holdings"), "installments" to payload.len("installments"), "installmentSync" to payload.len("installmentSync"),
            "salaryOrIncomeRules" to (if (payload.has("incomeRules")) payload.len("incomeRules") else planning.len("salaryRules")),
            "positions" to payload.len("positions"), "funds" to payload.len("funds"),
            "legacyFundAccounts" to (0 until accounts.length()).count { accounts.getJSONObject(it).optString("type") in LegacyAccountType.FUND_CODES ||
                accounts.getJSONObject(it).optString("legacyType") in LegacyAccountType.FUND_CODES },
        ) + RulesData.KEYS.associateWith { payload.len(it) }
    }

    fun after(a: Archive): Map<String, Int> = linkedMapOf("accounts" to a.accounts.size, "events" to a.events.size) +
        EventType.entries.associate { t -> "events.${t.name}" to a.events.count { it.type == t } } + linkedMapOf(
        "valuations" to a.valuations.size, "indices" to a.indices.size, "audit" to a.audit.size, "plan" to a.plan.size,
        "prefillFacts.reported" to reported(a.prefillFacts?.toJson()), "accountValuations" to a.accountValuations.size,
        "instruments" to a.instruments.size, "trades" to a.trades.size, "transfers" to a.transfers.size, "projections" to a.projections.size,
        "performanceAttestations" to a.performanceAttestations.size,
        "externalMovements" to a.externalMovements.size + a.unknownProviderRows.externalMovements.size,
        "connections" to a.connections.size + a.unknownProviderRows.connections.size,
        "planning.categories" to a.planning.categories.size, "planning.rules" to a.planning.rules.size,
        "planning.providerCategories" to a.planning.providerCategories.size + a.unknownProviderRows.providerCategories.size,
        "planning.budgets" to a.planning.budgets.size, "planning.goals" to a.planning.goals.size, "planning.targets" to a.planning.targets.size,
        "pension.statements" to a.pension.statements.size, "pension.profiles" to a.pension.profiles.size,
        "pension.deductionYears" to a.pension.deductionYears.size, "pension.inps" to a.pension.inps.size,
        "holdings" to a.holdings.size, "installments" to a.installments.size, "installmentSync" to a.installmentSync.size,
        "salaryOrIncomeRules" to a.planning.incomeRules.size, "positions" to a.positions.size, "funds" to a.funds.size,
        "legacyFundAccounts" to a.accounts.count { it.legacyType in LegacyAccountType.FUND_CODES },
    ) + a.rulesData.byKey().mapValues { it.value.size }

    /** One line per collection: `name before after`. */
    fun lines(before: Map<String, Int>, after: Map<String, Int>): List<String> =
        (PRESERVED + ADDED).map { "$it ${before[it]} ${after[it]}" }
}
