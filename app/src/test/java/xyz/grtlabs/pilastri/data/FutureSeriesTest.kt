package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.domain.*

/** Futuro read models (board 4a, G3 O2-F): the fan, the horizon, the composition and the per-year bars. Synthetic data only. */
class FutureSeriesTest {
    private val today = LocalDate.of(2026, 10, 27)
    private val p4a = Account("p4a", "Fondo A", 4, "fixture")
    private val p4b = Account("p4b", "Broker B", 4, "fixture")
    private val p4c = Account("p4c", "Conto C", 4, "fixture")

    private fun v(a: Account, d: LocalDate, euros: Long) = AccountValuation(a.id, d, Money(euros * 100), "fixture", Confidence.DOCUMENT)
    private fun own(id: String, d: LocalDate, euros: Long) = LedgerEvent(id, d, 4, EventType.PERSONAL_CONTRIBUTION, Money(euros * 100), Confidence.DOCUMENT, "fixture", accountId = p4a.id)
    private fun idx(y: Int, m: Int, value: String) = IndexPoint(YearMonth.of(y, m), BigDecimal(value), "NIC")
    private fun flow(id: String, d: LocalDate, euros: Long) = Inflow(d, 4, euros * 100, null, id)

    /* ---------- The fan ---------- */

    @Test fun fanMatchesTheBoardNumbers() {
        // Board 4a: 24.271 € now, 3.000 € a year, 4% real median, 2–6% band; rounded to 1.000 € in the table.
        val fan = buildFan(today, 2_427_100, 300_000, 30)!!
        assertEquals(31, fan.median.size)
        assertEquals(7_194_531L, fan.paths.getValue(4)[10])
        assertEquals(14_251_495L, fan.paths.getValue(4)[20])
        assertEquals(24_697_526L, fan.paths.getValue(4)[30])
        assertEquals(16_566_784L, fan.paths.getValue(2)[30])
        assertEquals(37_657_488L, fan.paths.getValue(6)[30])
        val e = fan.end
        assertEquals(2056, e.year)
        assertEquals(listOf(247_000L, 166_000L, 377_000L).map { it * 100 }, listOf(roundStima(e.median), roundStima(e.low), roundStima(e.high)))
        val r10 = fan.row(10); val r20 = fan.row(20)
        assertEquals(listOf(72_000L, 62_000L, 83_000L).map { it * 100 }, listOf(roundStima(r10.median), roundStima(r10.low), roundStima(r10.high)))
        assertEquals(listOf(143_000L, 109_000L, 188_000L).map { it * 100 }, listOf(roundStima(r20.median), roundStima(r20.low), roundStima(r20.high)))
    }

    @Test fun ratesNeverCrossAndStartEveryPathAtTheStartValue() {
        val fan = buildFan(today, 1_000_000, 120_000, 25)!!
        assertEquals(listOf(2, 3, 4, 5, 6), fan.paths.keys.toList())
        fan.paths.values.forEach { assertEquals(26, it.size); assertEquals(1_000_000L, it[0]) }
        for (i in 1..25) FanRates.zipWithNext().forEach { (lo, hi) -> assertTrue(fan.paths.getValue(lo)[i] < fan.paths.getValue(hi)[i]) }
    }

    @Test fun anUnknownContributionGrowsTheStartAloneNeverAZeroLabel() {
        val unknown = buildFan(today, 1_000_000, null, 10)!!
        val zero = buildFan(today, 1_000_000, 0, 10)!!
        assertEquals(zero.paths, unknown.paths)
        assertNull(unknown.yearlyCents)
        assertEquals(1_000_000L, unknown.median[0])
        assertTrue(unknown.median[10] > 1_000_000L)
    }

    @Test fun anOverflowIsNoFanNotACrash() {
        assertNull(projectPath(Long.MAX_VALUE / 2, 0, 6, 40))
        assertNull(buildFan(today, Long.MAX_VALUE / 2, 0, 40))
    }

    @Test fun milestonesAreEveryTenYearsThenTheHorizon() {
        assertEquals(listOf(10, 20, 30), milestoneOffsets(30))
        assertEquals(listOf(10, 20, 25), milestoneOffsets(25))
        assertEquals(listOf(10, 20, 30, 40), milestoneOffsets(40))
        assertEquals(listOf(7), milestoneOffsets(7))
        assertEquals(listOf(10), milestoneOffsets(10))
        assertEquals(emptyList<Int>(), milestoneOffsets(0))
    }

    @Test fun aStimaIsRoundedToWhatItCanClaim() {
        assertEquals(24_700_000L, roundStima(24_697_526))   // 246.975 € -> 247.000 €
        assertEquals(6_200_000L, roundStima(6_243_539))     // 62.435 € -> 62.000 €
        assertEquals(950_000L, roundStima(950_000))         // below 10.000 € the unit is 100 €
        assertEquals(120_000L, roundStima(123_456))
        assertEquals(0L, roundStima(0))
    }

    /* ---------- Horizon ---------- */

    @Test fun theHorizonComesFromTheRetirementDateOrTheStatedDefault() {
        val d = scenarioHorizon(today, null)
        assertEquals(Triple(30, 2056, null), Triple(d.years, d.endYear, d.retirement))
        val r = LocalDate.of(2051, 3, 1)
        val h = scenarioHorizon(today, r)
        assertEquals(Triple(25, 2051, r), Triple(h.years, h.endYear, h.retirement))
        // Already retired, retiring this year, or beyond the 60-year cap: no horizon in the data, so the default.
        listOf(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2100, 1, 1)).forEach {
            val x = scenarioHorizon(today, it)
            assertEquals(DefaultHorizonYears, x.years)
            assertNull(x.retirement)
        }
    }

    @Test fun theRetirementDateIsTheLatestEstimates() {
        val old = InpsEstimate(LocalDate.of(2025, 1, 1), 100_000, LocalDate.of(2060, 1, 1), "", "fixture")
        val new = InpsEstimate(LocalDate.of(2026, 5, 1), 100_000, LocalDate.of(2058, 1, 1), "", "fixture")
        assertEquals(new.retirementDate, Archive(pension = PensionData(inps = listOf(old, new))).retirementDate())
        assertNull(Archive().retirementDate())
        assertNull(Archive(pension = PensionData(inps = listOf(InpsEstimate(LocalDate.of(2026, 5, 1), 1, null, "", "fixture")))).retirementDate())
    }

    /* ---------- Yearly contribution ---------- */

    @Test fun theYearlyContributionIsTheAverageOverTheMonthsSinceTheFirst() {
        val flows = listOf(LocalDate.of(2023, 10, 27), LocalDate.of(2024, 10, 27), LocalDate.of(2025, 10, 27)).map { SeriesPoint(it, 1_000_000) }
        assertEquals(1_000_000L, averageYearlyContribution(flows, today))   // 3 x 10.000 € over 36 months
        // A history shorter than a year is not annualised upwards.
        assertEquals(600_000L, averageYearlyContribution(listOf(SeriesPoint(LocalDate.of(2026, 7, 27), 600_000)), today))
        assertNull(averageYearlyContribution(emptyList(), today))
    }

    /* ---------- Composition and freshness ---------- */

    @Test fun aSourceWithoutAValueStaysAsAnNdSlotAndFreshnessCountsIt() {
        val a = Archive(accounts = listOf(p4a, p4b, p4c), accountValuations = listOf(v(p4a, LocalDate.of(2026, 10, 27), 20_000), v(p4b, LocalDate.of(2026, 9, 18), 4_000)))
        val s = a.longTermSources()
        assertEquals(listOf("Fondo A", "Broker B", "Conto C"), s.map { it.name })
        assertEquals(listOf<Long?>(2_000_000, 400_000, null), s.map { it.cents })
        assertTrue(s[1].stale(today)); assertFalse(s[0].stale(today)); assertFalse(s[2].stale(today))
        val f = longTermFreshness(s, today)
        assertEquals(LocalDate.of(2026, 10, 27), f.asOf)
        assertEquals(2, f.known); assertEquals(3, f.of)
        assertEquals(StaleItem("Broker B", LocalDate.of(2026, 9, 18)), f.stale)
        assertFalse(f.complete)
        assertNull(a.longTermNow().nominalCents)   // a pillar with an account lacking a value is unknown, never partial
    }

    @Test fun allSourcesKnownAndCurrentIsComplete() {
        val a = Archive(accounts = listOf(p4a, p4b), accountValuations = listOf(v(p4a, today, 20_000), v(p4b, today.minusDays(3), 4_000)))
        val f = longTermFreshness(a.longTermSources(), today)
        assertTrue(f.complete)
        assertEquals(2, f.of)
        assertEquals(2_400_000L, a.longTermNow().nominalCents)
    }

    @Test fun noSourceAtAllReadsNoValueNotZero() {
        val f = longTermFreshness(Archive().longTermSources(), today)
        assertNull(f.asOf); assertEquals(0, f.known); assertEquals(1, f.of); assertFalse(f.complete)
    }

    @Test fun aSourceInTheRealBasisNeedsAnIndexOtherwiseNd() {
        val def = Deflator(listOf(idx(2026, 1, "100"), idx(2026, 10, "110")))
        val dated = SourceSlice("Fondo", 2_200_000, LocalDate.of(2026, 10, 27))
        assertEquals(2_200_000L, dated.inBasis(false, def))
        assertEquals(2_000_000L, dated.inBasis(true, def))
        assertNull(SourceSlice("Fondo", 2_200_000, LocalDate.of(2026, 3, 1)).inBasis(true, def))
        assertNull(SourceSlice("Vuoto", null, null).inBasis(false, def))
    }

    /* ---------- Money put in per year ---------- */

    @Test fun yearsRunFromTheFirstContributionAndThisYearIsToDate() {
        val flows = listOf(flow("a", LocalDate.of(2024, 3, 5), 1_000), flow("b", LocalDate.of(2024, 9, 5), 500), flow("c", LocalDate.of(2026, 2, 5), 700))
        val bars = yearlyBars(flows, today, real = false, def = Deflator(emptyList()), coverage = Coverage(null, null))
        assertEquals(listOf(2024, 2025, 2026), bars.map { it.year })
        assertEquals(listOf<Long?>(150_000, null, 70_000), bars.map { it.cents })
        assertEquals(listOf(BarState.DATA, BarState.ND, BarState.DATA), bars.map { it.state })   // 2025 unknown: n/d, not a zero bar
        assertEquals(listOf(false, false, true), bars.map { it.toDate })
    }

    @Test fun aYearWithNoRecordIsNoneOnlyWhenTheDataCoversEveryEndedMonth() {
        val flows = listOf(flow("a", LocalDate.of(2024, 3, 5), 1_000), flow("c", LocalDate.of(2026, 2, 5), 700))
        val covered = yearlyBars(flows, today, false, Deflator(emptyList()), Coverage(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 10, 1)))
        assertEquals(BarState.NONE, covered[1].state)
        val partial = yearlyBars(flows, today, false, Deflator(emptyList()), Coverage(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 10, 1)))
        assertEquals(BarState.ND, partial[1].state)
        assertTrue(yearlyBars(emptyList(), today, false, Deflator(emptyList()), Coverage(null, null)).isEmpty())
    }

    @Test fun inEuroGen2026AContributionWithoutAnIndexIsCountedNotGuessed() {
        val def = Deflator(listOf(idx(2026, 1, "100"), idx(2024, 3, "90")))
        val flows = listOf(flow("a", LocalDate.of(2024, 3, 5), 1_000), flow("b", LocalDate.of(2024, 5, 5), 500), flow("c", LocalDate.of(2025, 4, 5), 300))
        val bars = yearlyBars(flows, today, real = true, def = def, coverage = Coverage(null, null))
        val y24 = bars.first { it.year == 2024 }
        assertEquals(111_111L, y24.cents)          // 1.000 € x 100 / 90; the May contribution has no index
        assertEquals(2, y24.count); assertEquals(1, y24.indexed)
        val y25 = bars.first { it.year == 2025 }
        assertNull(y25.cents); assertEquals(BarState.ND, y25.state); assertEquals(0, y25.indexed)   // nothing indexable: n/d
        assertEquals(150_000L, yearlyBars(flows, today, false, def, Coverage(null, null)).first { it.year == 2024 }.cents)
    }

    /* ---------- The whole scenario ---------- */

    private fun scenarioArchive(withIndex: Boolean = true, valueDate: LocalDate = today, inps: List<InpsEstimate> = emptyList()) = Archive(
        accounts = listOf(p4a),
        accountValuations = listOf(v(p4a, valueDate, 24_630)),
        events = listOf(own("c1", LocalDate.of(2023, 10, 27), 3_000), own("c2", LocalDate.of(2024, 10, 27), 3_000), own("c3", LocalDate.of(2025, 10, 27), 3_000)),
        indices = if (withIndex) listOf(idx(2026, 1, "100"), idx(2023, 10, "100"), idx(2024, 10, "100"), idx(2025, 10, "100"), idx(2026, 10, "110"), idx(2026, 8, "110")) else emptyList(),
        pension = PensionData(inps = inps),
    )

    @Test fun theScenarioStartsFromTodaysRealValueWithTheAverageContribution() {
        val s = scenarioArchive().futureScenario(today)
        assertEquals(today, s.startDate)
        assertEquals(2_239_091L, s.startReal)       // 24.630 € in euro of January 2026 at index 110
        assertEquals(300_000L, s.yearlyReal)        // 9.000 € over 36 months
        assertEquals(LocalDate.of(2023, 10, 27), s.yearlySince)
        assertEquals(Horizon(30, 2056, null), s.horizon)
        assertEquals(projectPath(2_239_091, 300_000, 4, 30), s.fan!!.median)
    }

    @Test fun withoutACompatibleIndexThereIsNoFanNeverANominalOneRelabelled() {
        val s = scenarioArchive(withIndex = false).futureScenario(today)
        assertNull(s.startReal); assertNull(s.yearlyReal); assertNull(s.fan)
        assertEquals(24_630_00L, Archive(accounts = listOf(p4a), accountValuations = listOf(v(p4a, today, 24_630))).longTermNow().nominalCents)
    }

    @Test fun aDatedValueStartsTheScenarioAtItsOwnDate() {
        val d = LocalDate.of(2026, 8, 1)   // 87 days old: past the 35-day line
        val s = scenarioArchive(valueDate = d).futureScenario(today)
        assertEquals(d, s.startDate)
        assertEquals(d.year + 30, s.horizon.endYear)
    }

    @Test fun theHorizonFollowsTheRetirementDateOfThePersonsEstimate() {
        val est = InpsEstimate(LocalDate.of(2026, 5, 1), 150_000, LocalDate.of(2051, 3, 1), "", "fixture")
        val s = scenarioArchive(inps = listOf(est)).futureScenario(today)
        assertEquals(25, s.horizon.years)
        assertEquals(2051, s.horizon.endYear)
        assertNotNull(s.fan)
        assertEquals(26, s.fan!!.median.size)
    }
}
