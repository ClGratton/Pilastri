package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import java.math.BigDecimal
import java.time.YearMonth
import xyz.grtlabs.pilastri.domain.Money

/**
 * Only explicit reported net payments are mapped; assumptions and missing balances are not. Any `confirmed.netPaid<Year><Month>`
 * key is read (the month is in the key, not in code), so the file's own dated entries decide what is imported.
 */
object Prefill {
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val NET_PAID = Regex("netPaid([0-9]{4})(${MONTHS.joinToString("|")})")

    fun parse(bytes: ByteArray): List<PlanItem> {
        require(bytes.size <= 1_000_000)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("schemaVersion") == "2026-10-27.v1" && root.getString("defaultCurrency") == "EUR")
        val confirmed = root.getJSONObject("confirmed")
        return confirmed.keys().asSequence().mapNotNull { key ->
            NET_PAID.matchEntire(key)?.let { m -> key to YearMonth.of(m.groupValues[1].toInt(), MONTHS.indexOf(m.groupValues[2]) + 1) }
        }.sortedBy { it.second }.mapNotNull { (key, month) ->
            if (confirmed.isNull(key)) null else {
                val raw = confirmed.get(key)
                require(raw is Number)
                val cents = BigDecimal(raw.toString()).movePointRight(2).longValueExact()
                // The id keeps the v13 shape so a file imported before stays deduplicated.
                PlanItem("prefill-${month.year}-${month.monthValue}-salary", month, "Netto percepito", PlanKind.INCOME,
                    PlanStatus.ACTUAL, Money(cents), "JSON precompilato · dichiarato")
            }
        }.toList()
    }
}
