package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.YearMonth

/** Human-reviewable golden cases in `src/test/resources/golden`, hand-computed from VERIFIED rows only. */
class GoldenTest {
    private fun golden(name: String) = JSONObject(javaClass.getResource("/golden/$name")!!.readText())
    private fun cents(s: String) = BigDecimal(s).movePointRight(2).longValueExact()
    private fun JSONObject.centsOrNull(k: String) = if (isNull(k)) null else cents(getString(k))

    @Test fun irpefTy2026AndBracketBoundaries() {
        val g = golden("irpef-2026.json")
        val cases = g.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val r = Shipped.rules.irpefGross(cents(c.getString("taxable")), c.getInt("year"))
            val label = c.toString()
            assertEquals(label, RuleStatus.valueOf(c.getString("status")), r.status)
            assertEquals(label, c.centsOrNull("expected"), r.value)
            if (r.status == RuleStatus.EXACT) assertTrue(label, r.sourceIds.containsAll(listOf("irpef.gross", "irpef.bracket.2.rate")))
        }
        assertEquals(1_370_000L, Shipped.rules.irpefGross(5_000_000, 2026).value)
    }

    @Test fun deductionCeilingPerPersonYear() {
        val g = golden("deduction-ceiling-2026.json")
        val ceilingRow = Shipped.pack.at(g.getString("ceilingRow"), java.time.LocalDate.of(2026, 12, 31))!!
        assertEquals(g.getString("sourceUrl"), ceilingRow.sourceUrl)
        val cases = g.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val arr = c.getJSONArray("contributions")
            val list = (0 until arr.length()).map { j ->
                val o = arr.getJSONObject(j)
                FundContribution(o.getString("fundId"), YearMonth.parse(o.getString("period")), cents(o.getString("worker")), cents(o.getString("employer")),
                    if (o.has("tfr")) cents(o.getString("tfr")) else null)
            }
            val r = Shipped.rules.deductionCeiling(c.getInt("year"), list)
            val label = c.getString("name")
            assertEquals(label, cents(c.getString("ceiling")), r.ceiling.value)
            assertEquals(label, RuleStatus.EXACT, r.ceiling.status)
            assertTrue(label, r.ceiling.sourceIds.contains("pc.deduction.ceiling"))
            assertTrue(label, r.ceiling.caveats.contains(g.getString("caveat")) || c.getInt("year") < 2026)
            assertEquals(label, c.centsOrNull("counted"), r.counted.value)
            assertEquals(label, c.centsOrNull("deductible"), r.deductible.value)
            assertEquals(label, c.centsOrNull("excess"), r.excess.value)
            assertEquals(label, RuleStatus.valueOf(c.getString("status")), r.deductible.status)
            assertEquals(label, c.getInt("dropped"), r.duplicatesDropped)
            if (r.deductible.status == RuleStatus.ND) assertNull(r.excess.value)
        }
    }
}
