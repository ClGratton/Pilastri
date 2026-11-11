package xyz.grtlabs.pilastri.ui

import java.time.LocalDate
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.debug.SampleArchive
import org.junit.Test
import xyz.grtlabs.pilastri.ui.screens.flowMonths

/** Timing probe (2026-11-09): the synchronous work Pilastri's first composition does, on the synthetic archive scaled up. Prints ms; asserts nothing. */
class PilastriFirstCompositionCostTest {
    private fun scaled(base: Archive, k: Int): Archive {
        val rows = base.externalMovements
        val more = (1..k).flatMap { n -> rows.map { it.copy(id = it.id + "#$n", date = it.date.minusDays((n % 300).toLong())) } }
        return base.copy(externalMovements = rows + more)
    }
    private fun <T> t(name: String, f: () -> T) {
        repeat(3) { f() } // warm
        val n = 5; val s = System.nanoTime(); repeat(n) { f() }
        println("COST %-28s %8.2f ms".format(name, (System.nanoTime() - s) / 1e6 / n))
    }
    @Test fun cost() {
        val today = LocalDate.now()
        for (k in listOf(0, 20)) {
            val a = scaled(SampleArchive.build(today), k)
            println("COST --- movements=${a.externalMovements.size} events=${a.events.size} valuations=${a.valuations.size} accValuations=${a.accountValuations.size}")
            t("attivitaSum") { a.attivitaSum() }
            t("realCard") { a.realCard() }
            t("freshness") { a.freshness(today) }
            t("targetFor x4 (PillarColumns)") { (1..4).forEach { a.targetFor(it, today) } }
            t("pillarValueDate x4") { (1..4).forEach { a.pillarValueDate(it, today) } }
            t("attivitaLine (chart)") { a.attivitaLine(today) }
            t("dataMonths") { a.dataMonths(today) }
            t("flowMonths") { a.flowMonths(today, a.liquidityCoverage()) }
            t("monthValueChange(m)") { a.monthValueChange(java.time.YearMonth.from(today)) }
            t("monthVersamenti(m)") { a.monthVersamenti(java.time.YearMonth.from(today)) }
            t("briefing (Relogin)") { a.briefing(today) }
            t("incomeArrival") { a.incomeArrival(today) }
            t("spendLedger (Spese)") { a.spendLedger() }
        }
    }

    @Test fun rulesCost() {
        val d = java.io.File("src/main/assets/rules")
        val pack = java.io.File(d, "law-2026.json").readText(); val snap = java.io.File(d, "snapshots-2026.json").readText()
        println("COST rules json bytes=${pack.length + snap.length}")
        val s0 = System.nanoTime(); val r = xyz.grtlabs.pilastri.domain.rules.Rules.fromJson(pack, snap)
        println("COST Rules.fromJson (cold, 1st call)  %.2f ms".format((System.nanoTime() - s0) / 1e6))
        t("Rules.fromJson (warm)") { xyz.grtlabs.pilastri.domain.rules.Rules.fromJson(pack, snap) }
        val a = SampleArchive.build(LocalDate.now())
        val y = LocalDate.now().year
        t("deductionCeiling") { r.deductionCeiling(y, a.fundContributions(y)) }
    }
}
