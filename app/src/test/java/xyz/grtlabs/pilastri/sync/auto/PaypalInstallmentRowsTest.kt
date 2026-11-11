package xyz.grtlabs.pilastri.sync.auto

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.paidCount
import xyz.grtlabs.pilastri.data.withImportBatch

/**
 * End to end on SYNTHETIC rows shaped like the recorded PayPal list (2026-11-01 bug: every Pay in 3 plan showed "0 rate pagate").
 * The purchase is a row of type "Paga in 3 rate" naming the merchant; each collected part is an outflow to the payee "Paga in 3 rate"
 * typed "Pagamento". Names, amounts and dates invented.
 */
class PaypalInstallmentRowsTest {
    private val labels = PaypalActivityLabels()
    private val window = SyncRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31))
    private val pp = Account("pp", "PayPal sintetico", 2, "fixture")

    private fun r(h: String, n: String, a: String, d: String) = JSONObject().put("h", h).put("n", n).put("a", a).put("d", d).put("x", "")
    private fun read(vararg rows: JSONObject): PaypalActivityMapper.WindowRead {
        val json = JSONObject().put("v", 3).put("rows", JSONArray(rows.toList())).put("headers", JSONArray(rows.map { it.getString("h") }.distinct()))
            .put("more", false).put("empty", false).put("ready", true).toString()
        return PaypalActivityMapper.read(PaypalActivityMapper.snapshot(JSONObject.quote(json))!!, window, labels, T.today)
    }

    private val rows = arrayOf(
        r("ott 2026", "Paga in 3 rate", "−100,00 €", "4 ott · Pagamento"),
        r("set 2026", "Paga in 3 rate", "−100,00 €", "3 set · Pagamento"),
        r("ago 2026", "Paga in 3 rate", "−100,00 €", "3 ago · Pagamento"),
        r("ago 2026", "Elettronica Sintetica", "−300,00 €", "3 ago · Paga in 3 rate"),
        r("ago 2026", "Negozio Sintetico", "−100,00 €", "3 ago · Pagamento"),
    )

    @Test fun collectedPartsOfAPayIn3PlanAreCountedAsPaidAfterTheSync() {
        val batch = PaypalActivityMapper.batch(listOf(read(*rows)), T.fetchedAt, stoppedEarly = false)
        assertEquals(1, batch.installmentPlans.size)
        assertTrue(batch.installmentPlans.single().installments.isEmpty())
        assertEquals(4, batch.movements.size)

        val archive = Archive(accounts = listOf(pp)).withImportBatch(batch, pp.id, "2026-10-31T09:00:01Z").archive
        val plan = archive.installments.single()
        assertEquals(3, plan.paidCount())
        assertEquals(3, plan.installments.size)
        assertTrue(plan.installments.all { it.state == InstallmentState.PAID && it.cents == 10_000L && it.movementId != null })
        assertEquals(0, plan.remainingCents)
        // The unrelated payment to a merchant is never taken as a part.
        assertEquals(1, archive.externalMovements.count { it.description == "Negozio Sintetico" })
        assertTrue(plan.installments.none { i -> archive.externalMovements.single { it.id == i.movementId }.description == "Negozio Sintetico" })

        // The next sync reads the same list: nothing changes.
        val again = archive.withImportBatch(batch, pp.id, "2026-11-01T09:00:00Z").archive
        assertEquals(archive.installments, again.installments)
    }

    @Test fun aPlanWhoseFirstPartOnlyIsInTheListShowsOnePaid() {
        val batch = PaypalActivityMapper.batch(listOf(read(rows[2], rows[3])), T.fetchedAt, stoppedEarly = false)
        val plan = Archive(accounts = listOf(pp)).withImportBatch(batch, pp.id, "2026-10-31T09:00:01Z").archive.installments.single()
        assertEquals(1, plan.paidCount())
        assertEquals(2, plan.remainingUnknown)
    }
}
