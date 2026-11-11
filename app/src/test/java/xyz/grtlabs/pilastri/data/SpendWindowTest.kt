package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Council 5 (the Spese window): bounds, partition, coverage, rule e, pace, signal, derived budget, sparkline, installment parts. Synthetic data only. */
class SpendWindowTest {
    private val f = CategoryFixtures
    private val home = f.spend("c:casa", "Casa")
    private val fun_ = f.spend("c:svago", "Svago")
    private val tax = f.spend("c:tasse", "Tasse")
    private val today = LocalDate.parse("2026-11-01")
    private fun d(s: String) = LocalDate.parse(s)

    private fun archive(
        movements: List<ExternalMovement>, from: String = "2026-01-01", to: String = "2026-11-01",
        budgets: List<Budget> = emptyList(), rules: List<IncomeRule> = emptyList(), plans: List<InstallmentPlan> = emptyList(),
    ) = Archive(
        accounts = listOf(f.paypal), externalMovements = movements, installments = plans,
        connections = listOf(Connection(ImportProvider.PAYPAL, f.paypal.id, null, d(from), d(to), null)),
        planning = Planning(categories = listOf(home, fun_, tax), budgets = budgets, incomeRules = rules),
    ).also { ArchiveCodec.encode(it) }

    private fun out(id: String, cents: Long, date: String, cat: String? = home.id) = f.mv(id, "Negozio $id", -cents, date, cat, CategorySource.OVERRIDE)
    private fun rule(from: Int = 21, to: Int = 25) = IncomeRule("r1", "Stipendio", f.paypal.id, "Datore sintetico", from, to, 100_000, 300_000, "2026-01-01T00:00:00Z")
    private fun salary(id: String, date: String) = f.mv(id, "Datore sintetico", 180_000, date)

    @Test fun windowBoundsFollowMonthEnds() {
        fun w(t: String, k: Int) = SpendWindows.window(d(t), k, null).let { it.from.toString() + ".." + it.to + "/" + it.days }
        assertEquals("2026-10-02..2026-11-01/31", w("2026-11-01", 0))
        assertEquals("2026-03-01..2026-03-31/31", w("2026-03-31", 0))
        assertEquals("2026-02-01..2026-02-28/28", w("2026-03-31", 1))
        assertEquals("2026-01-01..2026-01-31/31", w("2026-03-31", 2))
        assertEquals("2024-01-31..2024-02-29/30", w("2024-03-30", 1))
        assertEquals("2026-04-01..2026-04-30/30", w("2026-05-31", 1))
        assertEquals("2024-03-01..2024-03-30/30", w("2024-03-30", 0))
    }

    @Test fun windowsPartitionTheTimelineWithoutGapOrOverlap() {
        listOf("2026-11-01", "2026-03-31", "2024-02-29", "2026-05-30", "2026-01-31", "2026-12-31").forEach { t ->
            val ws = (0..40).map { SpendWindows.window(d(t), it, null) }
            ws.zipWithNext().forEach { (later, earlier) -> assertEquals("$t k=${later.k}", later.from, earlier.to.plusDays(1)) }
            ws.forEach { assertTrue("$t ${it.days}", it.days in 28..31) }
            var day = ws.last().from
            while (!day.isAfter(d(t))) { assertEquals("$t $day", 1, ws.count { day in it }); day = day.plusDays(1) }
        }
    }

    @Test fun windowCountAndIndexReachTheFirstData() {
        assertEquals(1, SpendWindows.count(d("2026-11-01"), d("2026-10-02")))
        assertEquals(2, SpendWindows.count(d("2026-11-01"), d("2026-10-01")))
        assertEquals(1, SpendWindows.count(d("2026-11-01"), null))
        assertEquals(0, SpendWindows.indexFor(d("2026-11-01"), YearMonth.of(2026, 12)))
        assertEquals(2, SpendWindows.indexFor(d("2026-11-01"), YearMonth.of(2026, 9)))
        assertEquals(YearMonth.of(2026, 9), SpendWindows.monthOf(d("2026-11-01"), 2))
    }

    @Test fun coveredDaysPartialAndStale() {
        val a = archive(listOf(out("m1", 1_000, "2026-10-15"), out("m2", 2_000, "2026-10-21")), from = "2026-10-20", to = "2026-10-25")
        val v = a.spendWindowView(today, 0)!!
        assertEquals(d("2026-10-25"), v.t)
        assertEquals(d("2026-09-26"), v.window.from)
        assertEquals(6, v.window.covered)
        assertTrue(v.window.partial)
        assertTrue(v.stale)
        // A day before the coverage is a gap: the 15 Oct row is not counted, never a partial month read as full.
        assertEquals(2_000L, v.spentCents)
        assertTrue(v.pace.take(v.window.dayOf(d("2026-10-20")) - 1).all { it == null })
        assertEquals(2_000L, v.pace.last())
    }

    @Test fun uncoveredDaysAreNeverZero() {
        val a = archive(listOf(out("m1", 1_000, "2026-10-21")), from = "2026-10-20")
        val v = a.spendWindowView(today, 1)!!
        assertEquals(0, v.window.covered)
        assertNull(v.spentCents)
        assertTrue(v.pace.all { it == null })
        assertNull(v.income.cents)
        assertNull(v.savedCents)
        assertNull(archive(emptyList()).copy(connections = emptyList()).spendWindowView(today, 0))
    }

    @Test fun incomeWithARuleIsCompleteOnlyWithExactlyOneCredit() {
        val spend = out("s", 50_000, "2026-10-10")
        val one = archive(listOf(spend, salary("in1", "2026-10-23")), rules = listOf(rule())).spendWindowView(today, 0)!!
        assertEquals(180_000L, one.income.cents)
        assertEquals(d("2026-10-23"), one.income.matched.single().second)
        assertEquals(130_000L, one.savedCents)
        val two = archive(listOf(spend, salary("in1", "2026-10-21"), salary("in2", "2026-10-25")), rules = listOf(rule())).spendWindowView(today, 0)!!
        assertNull(two.income.cents)
        assertNull(two.savedCents)
        assertEquals(2, (two.income.causes.single() as IncomeCause.Credits).count)
        val none = archive(listOf(spend, salary("in0", "2026-09-23")), rules = listOf(rule())).spendWindowView(today, 0)!!
        assertNull(none.income.cents)
        val e = none.income.causes.single() as IncomeCause.Expected
        assertEquals(d("2026-11-21") to d("2026-11-25"), e.from to e.to)
        assertEquals(50_000L, none.spentCents)
    }

    @Test fun incomeWithoutRulesIsTheClassifiedCreditsOrNothing() {
        val credit = f.mv("c1", "Fattura sintetica", 40_000, "2026-10-12").copy(role = MovementRole.INCOME)
        val v = archive(listOf(credit, out("s", 10_000, "2026-10-10"))).spendWindowView(today, 0)!!
        assertEquals(40_000L, v.income.cents)
        assertEquals(30_000L, v.savedCents)
        val student = archive(listOf(out("s", 10_000, "2026-10-10"))).spendWindowView(today, 0)!!
        assertNull(student.income.cents)
        assertNull(student.savedCents)
        assertEquals(10_000L, student.spentCents)
    }

    @Test fun expectedRangeWrapsAroundTheMonthEnd() {
        val r = rule(28, 2)
        assertEquals(d("2026-11-28") to d("2026-12-02"), expectedRange(r, d("2026-11-10")))
        assertEquals(d("2026-10-28") to d("2026-11-02"), expectedRange(r, d("2026-11-01")))
        assertEquals(d("2027-02-28") to d("2027-02-28"), expectedRange(rule(30, 31), d("2027-02-10")))
    }

    @Test fun signalNeedsFourteenDaysAFreshSyncAndHysteresis() {
        val budgets = listOf(Budget(home.id, 31_000, YearMonth.of(2026, 1)))
        fun view(spent: Long, from: String = "2026-01-01", to: String = "2026-11-01", wasOn: Boolean? = null) =
            archive(listOf(out("s", spent, "2026-10-27")), from, to, budgets).spendWindowView(today, 0, wasOn = { wasOn })!!
        assertTrue(view(34_200).over)
        assertEquals(10, view(34_200).overPercent)
        assertFalse(view(33_000).over)
        assertTrue(view(33_000, wasOn = true).over)
        assertFalse(view(32_000, wasOn = true).over)
        // c = 13 (20 Oct – 1 Nov): silent; c = 14: on, against 14/31 of the budget.
        assertFalse(view(30_000, from = "2026-10-20").over)
        assertTrue(view(30_000, from = "2026-10-19").over)
        // Last synced day 28 Oct on 1 Nov: older than 3 days, silent; 29 Oct still fresh.
        assertFalse(view(34_200, to = "2026-10-28").over)
        assertTrue(view(34_200, to = "2026-10-29").over)
        // Earlier windows never read the cache.
        assertFalse(archive(listOf(out("s", 33_000, "2026-09-30")), budgets = budgets).spendWindowView(today, 1, wasOn = { true })!!.over)
    }

    @Test fun signalComparesLikeWithLike() {
        val budgets = listOf(Budget(home.id, 31_000, YearMonth.of(2026, 1)))
        val v = archive(listOf(out("s", 30_000, "2026-10-30"), out("f24", 500_000, "2026-10-16", tax.id)), budgets = budgets).spendWindowView(today, 0)!!
        assertFalse(v.over)
        assertEquals(530_000L, v.spentCents)
        assertEquals(30_000L, v.budgetedSpentCents)
        assertEquals(31_000L, v.budget!!.cents)
        assertFalse(v.categories.first { it.id == tax.id }.over)
        assertNull(v.categories.first { it.id == tax.id }.budget)
    }

    @Test fun categoriesFlagWithTheSameRuleAndComeFirst() {
        val budgets = listOf(Budget(home.id, 31_000, YearMonth.of(2026, 1)), Budget(fun_.id, 31_000, YearMonth.of(2026, 1)))
        val v = archive(listOf(out("a", 30_000, "2026-10-20"), out("b", 40_000, "2026-10-21", fun_.id)), budgets = budgets).spendWindowView(today, 0)!!
        assertEquals(fun_.id, v.categories.first().id)
        assertTrue(v.categories.first().over)
        assertFalse(v.categories.first { it.id == home.id }.over)
        assertTrue(v.over)
        assertEquals(listOf(fun_.id), v.trim.map { it.id })
        assertEquals(9_000L, v.categories.first().overPaceCents)
    }

    @Test fun budgetEffectiveFromAppliesToWindowsEndingInOrAfterItsMonth() {
        val budgets = listOf(Budget(home.id, 20_000, YearMonth.of(2026, 1)), Budget(home.id, 50_000, YearMonth.of(2026, 11)))
        val a = archive(listOf(out("s", 1_000, "2026-10-10")), budgets = budgets)
        assertEquals(50_000L, a.spendWindowView(today, 0)!!.budget!!.cents)
        assertEquals(20_000L, a.spendWindowView(today, 1)!!.budget!!.cents)
    }

    @Test fun derivedBudgetIsTheMedianOfThreeFullWindows() {
        val rows = listOf(out("k1", 10_000, "2026-09-15"), out("k2", 30_000, "2026-08-15"), out("k3", 20_000, "2026-07-15"), out("k0", 5_000, "2026-10-15"))
        val v = archive(rows, from = "2026-06-01").spendWindowView(today, 0)!!
        assertEquals(WindowBudget(20_000, true), v.budget)
        assertEquals(WindowBudget(20_000, true), v.categories.first { it.id == home.id }.budget)
        // The third window back (2 Jul – 1 Aug) is partial: no estimate.
        assertNull(archive(rows, from = "2026-07-10").spendWindowView(today, 0)!!.budget)
        assertNull(derivedBudget(listOf(0L, 0L, 50_000L)))
        assertEquals(3L, derivedBudget(listOf(5L, 1L, 3L)))
    }

    @Test fun sparklineUsesOnlyFullyCoveredWindowsAndNeedsThreePoints() {
        val rows = listOf(out("k0", 1_000, "2026-10-15"), out("k1", 2_000, "2026-09-15"), out("k2", 3_000, "2026-08-15"))
        val line = archive(rows, from = "2026-08-01").spendWindowView(today, 0)!!.categories.first { it.id == home.id }
        assertEquals(listOf(null, null, null, 3_000L, 2_000L, 1_000L), line.spark)
        assertTrue(line.sparkShown)
        assertEquals(3_000L, line.sparkMax)
        val short = archive(rows, from = "2026-08-03").spendWindowView(today, 0)!!.categories.first { it.id == home.id }
        assertEquals(listOf(null, null, null, null, 2_000L, 1_000L), short.spark)
        assertFalse(short.sparkShown)
        assertNull(short.sparkMax)
    }

    @Test fun refundsNetIntoTheirCategoryAndPaceStaysCumulative() {
        val buy = out("buy", 10_000, "2026-10-05")
        val refund = f.mv("ref", "Rimborso negozio", 4_000, "2026-10-08").copy(kind = ExternalKind.OTHER)
        val v = archive(listOf(buy, refund)).spendWindowView(today, 0)!!
        assertEquals(6_000L, v.spentCents)
        assertEquals(10_000L, v.pace[v.window.dayOf(d("2026-10-05")) - 1])
        assertEquals(6_000L, v.pace.last())
    }

    @Test fun installmentPartsCountOnThePaidDayInTheirPurchasesCategory() {
        val p1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000, "2026-10-10")
        val classified = archive(listOf(p1), plans = listOf(f.plan("paypal-web:k1", "Negozio sintetico", listOf(f.part(1, p1.id, "2026-10-10")), categoryId = fun_.id)))
        val v = classified.spendWindowView(today, 0)!!
        assertEquals(1_000L, v.categories.first { it.id == fun_.id }.spentCents)
        val day = v.window.dayOf(d("2026-10-10"))
        assertEquals(0L, v.pace[day - 2])
        assertEquals(1_000L, v.pace[day - 1])
        val open = archive(listOf(p1), plans = listOf(f.plan("paypal-web:k2", "Negozio sintetico", listOf(f.part(1, p1.id, "2026-10-10")))))
        val u = open.spendWindowView(today, 0)!!
        assertEquals(1_000L, u.categories.single { it.id == null }.spentCents)
        assertTrue(open.installmentPaymentState(p1.id) is InstallmentPaymentState.ToClassify)
        assertEquals(listOf(p1.id), open.spendLedger().movements(u.window, null).map { it.movement.id })
    }

    @Test fun likelyCategoriesPreferTheMerchantsHistory() {
        val past = f.mv("old", "Bar sintetico", -500, "2026-09-01", fun_.id, CategorySource.OVERRIDE)
        val now = f.mv("new", "Bar sintetico", -700, "2026-10-01")
        val a = archive(listOf(past, now, out("x1", 100, "2026-09-02"), out("x2", 100, "2026-09-03")))
        assertEquals(listOf(fun_.id, home.id, tax.id), a.likelyCategories(now))
    }

    @Test fun rateOutlookKeepsPendingApartAndStopsAtTheLastPart() {
        fun due(seq: Int, date: String, cents: Long) = Installment(seq, d(date), cents, InstallmentState.DUE)
        val confirmed = f.plan("p1", "Negozio uno", listOf(due(1, "2026-12-10", 1_000), due(2, "2027-01-10", 1_000), due(3, "2027-02-10", 1_000)))
        val pending = f.plan("p2", "Negozio due", listOf(due(1, "2026-12-15", 500))).copy(review = PlanReview.PENDING, count = 1)
        val r = archive(emptyList(), plans = listOf(confirmed, pending)).rateOutlook(today)
        assertEquals(YearMonth.of(2026, 12), r.months.first().month)
        assertEquals(12, r.months.size)
        assertEquals(2, r.lastKnown)
        assertEquals(3_000L, r.next6)
        assertEquals(500L, r.next6Pending)
        assertEquals(YearMonth.of(2027, 2), r.plans.first { it.plan.id == "p1" }.until)
        assertEquals(0, r.hueOf("p1"))
    }
}
