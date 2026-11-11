package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.domain.*
import xyz.grtlabs.pilastri.domain.rules.DeductionCeiling
import xyz.grtlabs.pilastri.domain.rules.RuleStatus
import xyz.grtlabs.pilastri.domain.rules.RuleValue
import xyz.grtlabs.pilastri.ui.charts.freshnessText

/** Series and month read models for Pilastri, Mese and the pillar detail (G3 rebuild O1). Synthetic data only. */
class SeriesTest {
    private val today = LocalDate.of(2026, 10, 27)
    private val p1 = Account("p1", "Conto", 1, "fixture")
    private val p2 = Account("p2", "Deposito", 2, "fixture")
    private val p3 = Account("p3", "Salvadanaio", 3, "fixture")
    private val p4 = Account("p4", "Fondo", 4, "fixture")
    private val all = listOf(p1, p2, p3, p4)

    private fun v(a: Account, d: LocalDate, euros: Long) = AccountValuation(a.id, d, Money(euros * 100), "fixture", Confidence.DOCUMENT)
    private fun ev(id: String, d: LocalDate, pillar: Int, t: EventType, euros: Long, acc: String? = null) =
        LedgerEvent(id, d, pillar, t, Money(euros * 100), Confidence.DOCUMENT, "fixture", accountId = acc)

    @Test fun aMonthWithoutAValueIsAGapNeverZero() {
        // Values in Jun, Jul and Oct only: Aug and Sep are missing, so the line breaks there and nothing is 0.
        val dates = listOf(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 10, 20))
        val a = Archive(accounts = listOf(p4), accountValuations = dates.mapIndexed { i, d -> v(p4, d, 1000L + i) })
        val line = a.pillarLine(4, today)
        assertEquals(dates, line.map { it.date })
        assertTrue(line.none { it.cents == 0L })
        val runs = runsOf(line)
        assertEquals(2, runs.size)
        assertEquals(listOf(dates[0], dates[1]), runs[0].map { it.date })
    }

    @Test fun attivitaLineNeedsAllFourPillarsAndIsOpenWhenTodayIsPartial() {
        val sep = LocalDate.of(2026, 9, 25); val oct = LocalDate.of(2026, 10, 25)
        // P3 has a September value only and a second account without any value now: today it is n/d.
        val p3b = Account("p3b", "Nuovo", 3, "fixture")
        val vals = listOf(p1, p2, p4).flatMap { listOf(v(it, sep, 100), v(it, oct, 110)) } + v(p3, sep, 50)
        val a = Archive(accounts = all, accountValuations = vals)
        val line = a.attivitaLine(today)
        assertEquals(listOf(SeriesPoint(oct, (110 * 3 + 50) * 100L)), line.points.filter { it.date == oct })
        assertFalse(line.partial)

        val partial = Archive(accounts = all + p3b, accountValuations = vals)
        val pl = partial.attivitaLine(today)
        assertTrue(pl.partial)
        assertTrue("no total without all four pillars", pl.points.isEmpty())
        // The accent card still shows the partial sum, labelled with its coverage.
        val sum = partial.attivitaSum()
        assertEquals(3, sum.known)
        assertEquals(110 * 3 * 100L, sum.cents)
        assertNull(Archive().attivitaSum().cents)
    }

    @Test fun columnDatesSayRangeOldAndLastKnown() {
        val a = Archive(accounts = listOf(p4, p4.copy(id = "p4b", name = "Altro fondo")), accountValuations = listOf(v(p4, LocalDate.of(2026, 9, 18), 10), AccountValuation("p4b", today, Money(100), "fixture", Confidence.DOCUMENT)))
        assertEquals(ValueDate.Range(LocalDate.of(2026, 9, 18), today), a.pillarValueDate(4, today))
        val old = Archive(accounts = listOf(p2), accountValuations = listOf(v(p2, LocalDate.of(2026, 8, 1), 10)))
        assertEquals(ValueDate.Old(LocalDate.of(2026, 8, 1), true), old.pillarValueDate(2, today))
        val lost = Archive(accounts = listOf(p3, Account("x", "Nuovo", 3, "fixture")), valuations = listOf(Valuation(LocalDate.of(2026, 10, 12), 3, Money(500), "fixture", Confidence.DOCUMENT)))
        // Today the composed value is unknown (one account has no value) but the snapshot of 12 Oct was known: "dato del 12 ott".
        assertTrue(lost.pillarValueDate(3, today) is ValueDate.At || lost.pillarValueDate(3, today) is ValueDate.Old)
        assertEquals(ValueDate.None, Archive(accounts = listOf(p3)).pillarValueDate(3, today))
    }

    @Test fun monthValueChangeCountsThePillarsKnownAtBothEnds() {
        val start = LocalDate.of(2026, 9, 25); val end = LocalDate.of(2026, 10, 25)
        val vals = listOf(p1, p2, p4).flatMap { listOf(v(it, start, 100), v(it, end, 120)) } + v(p3, end, 70)
        val c = Archive(accounts = all, accountValuations = vals).monthValueChange()!!
        assertEquals(start, c.from); assertEquals(end, c.to)
        assertEquals(3, c.counted)
        assertEquals(3 * 20 * 100L, c.cents)
    }

    @Test fun theTwoMonthFiguresAreNeverNetted() {
        val start = LocalDate.of(2026, 9, 25); val end = LocalDate.of(2026, 10, 25)
        val vals = all.flatMap { listOf(v(it, start, 100), v(it, end, 100)) }
        val transfer = AccountTransfer("t1", LocalDate.of(2026, 10, 23), p1.id, p2.id, Money(150_00), "fixture")
        val own = ev("e1", LocalDate.of(2026, 10, 1), 4, EventType.PERSONAL_CONTRIBUTION, 150, p4.id)
        val employer = ev("e2", LocalDate.of(2026, 10, 23), 4, EventType.EMPLOYER_CONTRIBUTION, 41, p4.id)
        val pending = ExternalMovement("m1", ImportProvider.ISYBANK, p1.id, LocalDate.of(2026, 10, 23), -150_00, "EUR", "Giroconto", ExternalKind.OTHER, null, null, null,
            ExternalStatus.PENDING, null, "2026-10-24T00:00:00Z", role = MovementRole.OWN_TRANSFER)
        val a = Archive(accounts = all, accountValuations = vals, transfers = listOf(transfer), events = listOf(own, employer), externalMovements = listOf(pending))
        // Values unchanged: the change is 0 € of value, whatever was put in.
        assertEquals(0L, a.monthValueChange()!!.cents)
        val m = a.monthVersamenti(YearMonth.of(2026, 10))
        assertEquals(300_00L, m.tuoCents)
        assertEquals(2, m.tuoCount)
        assertEquals(3, m.tuoTotalCount)
        assertEquals(41_00L, m.employerCents)
        assertEquals(listOf(VersamentoKind.DA_VERIFICARE), m.rows.filter { it.cents == 150_00L && it.kind != VersamentoKind.TUO }.map { it.kind })
        assertEquals("Conto", m.rows.first { it.name.startsWith("Emergenze") }.from)
        // No employer record that month: n/d, never 0.
        assertNull(a.monthVersamenti(YearMonth.of(2026, 9)).employerCents)
        assertEquals(0, a.monthVersamenti(YearMonth.of(2026, 9)).rows.size)
    }

    @Test fun deflationIsNdWithoutAnIndex() {
        val src = "ISTAT NIC"
        val d = Deflator(listOf(IndexPoint(YearMonth.of(2026, 1), BigDecimal("100"), src), IndexPoint(YearMonth.of(2025, 11), BigDecimal("98"), src)))
        assertEquals(10_204L, d.real(10_000, YearMonth.of(2025, 11)))
        assertNull(d.real(10_000, YearMonth.of(2026, 10)))
        assertNull(Deflator(emptyList()).real(10_000, YearMonth.of(2025, 11)))
        // A different series is never mixed with the base.
        assertNull(Deflator(listOf(IndexPoint(YearMonth.of(2026, 1), BigDecimal("100"), src), IndexPoint(YearMonth.of(2025, 11), BigDecimal("98"), "altra serie"))).real(10_000, YearMonth.of(2025, 11)))
        assertEquals(BarState.ND, d.real(MonthBar(YearMonth.of(2026, 10), 150_00, 41_00, BarState.DATA)).state)
        assertEquals(BarState.DATA, d.real(MonthBar(YearMonth.of(2025, 11), 150_00, 41_00, BarState.DATA)).state)
        assertEquals(BarState.NONE, d.real(MonthBar(YearMonth.of(2026, 10), 0, 0, BarState.NONE)).state)
    }

    @Test fun contributionBarsKeepTuoAndDatoreApartAndMarkUnknownMonths() {
        val conn = Connection(ImportProvider.ISYBANK, p1.id, null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 27), null)
        val events = listOf(ev("a", LocalDate.of(2026, 4, 10), 4, EventType.PERSONAL_CONTRIBUTION, 150, p4.id), ev("b", LocalDate.of(2026, 4, 23), 4, EventType.EMPLOYER_CONTRIBUTION, 41, p4.id))
        val bars = Archive(accounts = all, events = events, connections = listOf(conn)).pillarContributionBars(4, today)
        assertEquals(12, bars.size)
        val apr = bars.first { it.month == YearMonth.of(2026, 4) }
        assertEquals(BarState.DATA, apr.state); assertEquals(150_00L, apr.tuoCents); assertEquals(41_00L, apr.datoreCents)
        assertEquals(BarState.ND, bars.first { it.month == YearMonth.of(2026, 3) }.state)   // before the first record
        assertEquals(BarState.NONE, bars.first { it.month == YearMonth.of(2026, 5) }.state) // covered, nothing paid
        assertEquals(BarState.ND, bars.first { it.month == YearMonth.of(2026, 10) }.state)  // the month is not over
    }

    @Test fun scalesAreRound() {
        assertEquals(Scale(3_000_000, 4_000_000), niceScale(3_262_000, 3_777_000))
        val flat = niceScale(2_463_000, 2_463_000)
        assertTrue(flat.lo <= 2_463_000 && flat.hi >= 2_463_000 && flat.hi - flat.lo >= 2_463_000 / 25)
        assertEquals(20_000L, niceCeil(19_100))
        assertEquals(100L, niceCeil(1))
    }

    @Test fun deductionRoomOnlyFromVerifiedRules() {
        fun rv(v: Long, s: RuleStatus) = RuleValue(v, s, listOf("x"))
        val exact = DeductionCeiling(2026, rv(5_300_00, RuleStatus.EXACT), rv(1_960_00, RuleStatus.EXACT), rv(1_960_00, RuleStatus.EXACT), rv(0, RuleStatus.EXACT), 0)
        assertEquals(3_340_00L, verifiedDeductionRoom(exact))
        assertNull(verifiedDeductionRoom(exact.copy(ceiling = rv(5_300_00, RuleStatus.STIMA))))
        assertNull(verifiedDeductionRoom(exact.copy(counted = RuleValue.nd("manca il datore"))))
        assertNull(verifiedDeductionRoom(null))
        assertTrue(deductionSeason(LocalDate.of(2026, 9, 1)))
        assertFalse(deductionSeason(LocalDate.of(2026, 8, 31)))
    }

    @Test fun freshnessLineSaysDateCoverageAndStaleness() {
        assertEquals("Aggiornato al 27 ott · 4/4 con valore", freshnessText(today, 4, 4))
        assertEquals("Aggiornato al 27 ott · 3/3 con valore · Fondo datato 18 set", freshnessText(today, 3, 3, StaleItem("Fondo", LocalDate.of(2026, 9, 18))))
        assertEquals("Nessun valore · 0/4 con valore", freshnessText(null, 0, 4))
    }

    /* ---------- 1a-20: the month row and Mese open on the latest month with data; a month without data is never 0 ---------- */

    @Test fun timelineRunsFromTheFirstToTheLatestMonthWithDataAndSkipsAnEmptyCurrentMonth() {
        val nov1 = LocalDate.of(2026, 11, 1)
        val data = setOf(YearMonth.of(2026, 6), YearMonth.of(2026, 8), YearMonth.of(2026, 10))
        val t = monthTimeline(data, nov1)
        // July and September stay on the strip (shown as "—"), November is not there until it has data.
        assertEquals((6..10).map { YearMonth.of(2026, it) }, t)
        assertEquals(YearMonth.of(2026, 10), shownMonth(null, t, nov1))
        // Once November has data it is the latest, and a chosen month off the strip falls back to it.
        val withNov = monthTimeline(data + YearMonth.of(2026, 11), nov1)
        assertEquals(YearMonth.of(2026, 11), shownMonth(null, withNov, nov1))
        assertEquals(YearMonth.of(2026, 7), shownMonth("2026-07", withNov, nov1))
        assertEquals(YearMonth.of(2026, 11), shownMonth("2025-01", withNov, nov1))
        // Nothing after today, nothing at all → empty (the screens then show today's month as "—").
        assertEquals(emptyList<YearMonth>(), monthTimeline(setOf(YearMonth.of(2026, 12)), nov1))
        assertEquals(YearMonth.of(2026, 11), shownMonth(null, emptyList(), nov1))
    }

    @Test fun dataMonthsComeFromRecordsAndCoverageNeverFromTheCalendar() {
        val nov1 = LocalDate.of(2026, 11, 1)
        val conn = Connection(ImportProvider.ISYBANK, p1.id, null, LocalDate.of(2026, 8, 15), LocalDate.of(2026, 10, 31), null)
        val a = Archive(accounts = all, accountValuations = listOf(v(p4, LocalDate.of(2026, 5, 20), 1000)), connections = listOf(conn))
        val months = a.dataMonths(nov1)
        assertEquals(setOf(YearMonth.of(2026, 5), YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)), months)
        val t = monthTimeline(months, nov1)
        assertEquals(YearMonth.of(2026, 10), t.last())
        // June has no data: the month row and Mese show "—", never 0 €.
        assertFalse(YearMonth.of(2026, 6) in months)
        assertTrue(YearMonth.of(2026, 6) in t)
        assertNull(a.monthValueChange(YearMonth.of(2026, 6)))
        assertEquals(0, a.monthVersamenti(YearMonth.of(2026, 6)).tuoTotalCount)
    }

    @Test fun coverageLabelNamesThePartOfTheMonthTheDataCovers() {
        val nov1 = LocalDate.of(2026, 11, 1)
        val running = Coverage(LocalDate.of(2026, 3, 1), nov1)
        assertEquals("dati al 1 nov", coverageLabel(YearMonth.of(2026, 11), running, nov1, "nov"))
        assertNull(coverageLabel(YearMonth.of(2026, 10), running, nov1, "ott"))
        assertEquals("dati dal 15 al 31 ago", coverageLabel(YearMonth.of(2026, 8), Coverage(LocalDate.of(2026, 8, 15), nov1), nov1, "ago"))
        assertEquals("nessun dato", coverageLabel(YearMonth.of(2026, 2), running, nov1, "feb"))
    }

    @Test fun monthValueChangeStaysInsideItsMonth() {
        val a = Archive(accounts = listOf(p4), accountValuations = listOf(
            v(p4, LocalDate.of(2026, 9, 20), 1000), v(p4, LocalDate.of(2026, 10, 20), 1100),
        ))
        val oct = a.monthValueChange(YearMonth.of(2026, 10))!!
        assertEquals(100_00L, oct.cents)
        assertEquals(LocalDate.of(2026, 10, 20), oct.to)
        // November has no record date of its own: n/d, not October's change carried over.
        assertNull(a.monthValueChange(YearMonth.of(2026, 11)))
    }
}

class CoverageLabelRuleTest {
    private val today = LocalDate.of(2026, 11, 6)
    @Test fun coverageStartingOnTheFirstReadsDatiAl() {
        assertEquals("dati al 6 nov", coverageLabel(YearMonth.of(2026, 11), Coverage(LocalDate.of(2026, 3, 1), today), today, "nov"))
        assertEquals("dati al 20 ott", coverageLabel(YearMonth.of(2026, 10), Coverage(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 20)), today, "ott"))
        assertEquals("dati dal 10 al 31 ott", coverageLabel(YearMonth.of(2026, 10), Coverage(LocalDate.of(2026, 10, 10), today), today, "ott"))
        assertEquals("dati al 31 ott", coverageLabel(YearMonth.of(2026, 10), Coverage(LocalDate.of(2026, 3, 1), today), today, "ott", fullAsEnd = true))
    }
    /** Must-flag: no month, start day or end day ever yields "dati dal 1 al". */
    @Test fun noLabelEverReadsDatiDal1Al() {
        for (from in 1..28) for (to in from..31) for (m in 9..11) {
            val ym = YearMonth.of(2026, m)
            val l = coverageLabel(ym, Coverage(LocalDate.of(2026, m, from).let { if (from == 1) it else it }, ym.atDay(minOf(to, ym.lengthOfMonth()))), today, "x", true)
            assertTrue("$l", l == null || !l.startsWith("dati dal 1 al"))
        }
    }
}
