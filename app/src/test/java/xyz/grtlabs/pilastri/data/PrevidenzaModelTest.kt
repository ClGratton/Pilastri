package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import xyz.grtlabs.pilastri.domain.rules.FundContribution

/** Synthetic values only: the Previdenza page logic (board 4b) has no person-specific data. */
class PrevidenzaModelTest {
    private fun c(fund: String, month: Int, worker: Long?, employer: Long?, year: Int = 2026) = FundContribution(fund, YearMonth.of(year, month), worker, employer)

    private fun position(kind: PositionKind, regime: Sourced<Coded<SeveranceRegime>> = Sourced.nd()) =
        IncomePosition("p-${kind.name}", coded(kind), severanceRegime = regime)

    private fun fund(form: FundFormType? = null, employerPct: Sourced<BigDecimal> = Sourced.nd()) =
        PensionFund("f", Sourced.declared("Fondo prova"), formType = form?.let { Sourced.declared(coded(it)) } ?: Sourced.nd(), employerPct = employerPct)

    @Test
    fun barHasOneSegmentPerFundAndPartyAndTheRoomUpToTheCeiling() {
        val bar = deductionBar(
            listOf(c("f1", 1, 100_000, 40_000), c("f1", 2, 100_000, 40_000), c("f2", 1, 50_000, 0)),
            2026, 530_000, mapOf("f1" to "Uno", "f2" to "Due"),
        )
        assertEquals(
            listOf(
                DeductionSegment("f1", "Uno", DeductionParty.TUO, 200_000), DeductionSegment("f1", "Uno", DeductionParty.DATORE, 80_000),
                DeductionSegment("f2", "Due", DeductionParty.TUO, 50_000),
            ),
            bar.segments,
        )
        assertEquals(330_000L, bar.countedCents)
        assertEquals(200_000L, bar.roomCents)
        assertNull(bar.excessCents)
        assertEquals(600_000L, bar.scaleCents)
        assertEquals(0, bar.unknownParts)
    }

    @Test
    fun overTheCeilingTheRoomIsZeroAndTheExcessIsNamed() {
        val bar = deductionBar(listOf(c("f1", 1, 500_000, 200_000)), 2026, 530_000)
        assertEquals(700_000L, bar.countedCents)
        assertEquals(0L, bar.roomCents)
        assertEquals(170_000L, bar.excessCents)
        assertEquals(700_000L, bar.scaleCents)
        assertEquals("Fondo", bar.segments.first().fundName)
    }

    @Test
    fun anUnknownPartMakesTheTotalAndTheRoomUnknownNotZero() {
        val bar = deductionBar(listOf(c("f1", 1, 100_000, null), c("f1", 2, 100_000, 10_000)), 2026, 530_000)
        assertNull(bar.countedCents)
        assertNull(bar.roomCents)
        assertNull(bar.excessCents)
        assertEquals(1, bar.unknownParts)
        assertEquals(listOf(200_000L, 10_000L), bar.segments.map { it.cents })
    }

    @Test
    fun noRecordOfTheYearIsUnknownAndOtherYearsAreIgnored() {
        val bar = deductionBar(listOf(c("f1", 1, 100_000, 0, year = 2025)), 2026, 530_000)
        assertNull(bar.countedCents)
        assertNull(bar.roomCents)
        assertEquals(emptyList<DeductionSegment>(), bar.segments)
        assertEquals(600_000L, bar.scaleCents)
    }

    @Test
    fun duplicateFundMonthRecordsCountOnce() {
        val bar = deductionBar(listOf(c("f1", 1, 100_000, 0), c("f1", 1, 100_000, 0)), 2026, 530_000)
        assertEquals(100_000L, bar.countedCents)
    }

    @Test
    fun aMissingCeilingLeavesTheRoomUnknown() {
        val bar = deductionBar(listOf(c("f1", 1, 150_000, 0)), 2026, null)
        assertEquals(150_000L, bar.countedCents)
        assertNull(bar.roomCents)
        assertNull(bar.excessCents)
        assertEquals(200_000L, bar.scaleCents)
    }

    @Test
    fun scaleRoundsUpToWholeThousands() {
        assertEquals(100_000L, deductionScale(0))
        assertEquals(100_000L, deductionScale(100_000))
        assertEquals(200_000L, deductionScale(100_001))
        assertEquals(600_000L, deductionScale(530_000))
    }

    @Test
    fun regimeLineNamesTheOriginOnlyForAKnownRegime() {
        assertEquals("Regime TFR · documentato", regimeLine(Sourced.document(coded(SeveranceRegime.TFR), "d", "cedolino", LocalDate.of(2026, 9, 1))))
        assertEquals("Regime TFS · calcolato", regimeLine(Sourced.calculated(coded(SeveranceRegime.TFS))))
        assertEquals("Regime TFR · dichiarato", regimeLine(Sourced.declared(coded(SeveranceRegime.TFR))))
        assertEquals("Regime n/d", regimeLine(Sourced.nd()))
    }

    @Test
    fun sourceDocumentNameNeedsADocumentOrigin() {
        val short = { m: YearMonth -> if (m.monthValue == 9) "set" else "x" }
        assertEquals("cedolino set 2026", sourceDocLabel(Origin.DOCUMENTO, "cedolino", LocalDate.of(2026, 9, 15), short))
        assertEquals("documento set 2026", sourceDocLabel(Origin.DOCUMENTO, null, LocalDate.of(2026, 9, 15), short))
        assertEquals("cedolino", sourceDocLabel(Origin.DOCUMENTO, "cedolino", null, short))
        assertNull(sourceDocLabel(Origin.DICHIARATO, "cedolino", LocalDate.of(2026, 9, 15), short))
        assertNull(sourceDocLabel(Origin.ND, null, null, short))
    }

    @Test
    fun cardRegimeListsOnlyStatedRegimes() {
        assertEquals("n/d", portfolioRegime(emptyList()))
        assertEquals("n/d", portfolioRegime(listOf(position(PositionKind.DIPENDENTE_PRIVATO))))
        assertEquals("TFR", portfolioRegime(listOf(position(PositionKind.DIPENDENTE_PUBBLICO, Sourced.declared(coded(SeveranceRegime.TFR))))))
        assertEquals("TFR · TFS", portfolioRegime(listOf(
            position(PositionKind.DIPENDENTE_PUBBLICO, Sourced.declared(coded(SeveranceRegime.TFS))),
            position(PositionKind.DIPENDENTE_PRIVATO, Sourced.declared(coded(SeveranceRegime.TFR))),
        )))
    }

    @Test
    fun blocksFollowThePositionKind() {
        val negoziale = listOf(fund(FundFormType.NEGOZIALE))
        val aperto = listOf(fund(FundFormType.APERTO))
        assertEquals(listOf(BlockPart.TFR_DESTINATION, BlockPart.FUNDS), blockParts(PositionKind.DIPENDENTE_PRIVATO, negoziale))
        assertEquals(listOf(BlockPart.REGIME, BlockPart.FUNDS, BlockPart.TFR_FIGURATIVO), blockParts(PositionKind.DIPENDENTE_PUBBLICO, negoziale))
        assertEquals(listOf(BlockPart.REGIME, BlockPart.FUNDS), blockParts(PositionKind.DIPENDENTE_PUBBLICO, aperto))
        assertEquals(listOf(BlockPart.REGIME, BlockPart.FUNDS), blockParts(PositionKind.DIPENDENTE_PUBBLICO, emptyList()))
        listOf(PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO, PositionKind.COLLABORATORE).forEach {
            assertEquals(listOf(BlockPart.GESTIONE, BlockPart.CONTRIBUTIONS_BY_GESTIONE), blockParts(it, negoziale))
        }
        assertEquals(listOf(BlockPart.PENSION_SLIP), blockParts(PositionKind.PENSIONATO, negoziale))
        assertEquals(emptyList<BlockPart>(), blockParts(PositionKind.STUDENTE, negoziale))
        assertEquals(emptyList<BlockPart>(), blockParts(PositionKind.NESSUN_REDDITO, negoziale))
    }

    @Test
    fun onlyPositionsWithABlockAreListed() {
        val all = PositionKind.entries.map { position(it) }
        assertEquals(
            PositionKind.entries.filter { it != PositionKind.STUDENTE && it != PositionKind.NESSUN_REDDITO },
            previdenzaBlocks(all).map { it.kind.known },
        )
    }

    @Test
    fun employerReadsNessunoOnlyWhenAttested() {
        assertEquals(PartyFigure.Amount(41_200), employerFigure(41_200, fund()))
        assertEquals(PartyFigure.Amount(0), employerFigure(0, fund(employerPct = Sourced.declared(BigDecimal.ZERO))))
        assertEquals(PartyFigure.Nessuno, employerFigure(null, fund(employerPct = Sourced.declared(BigDecimal.ZERO))))
        assertEquals(PartyFigure.Nd, employerFigure(null, fund(employerPct = Sourced.declared(BigDecimal("2.5")))))
        assertEquals(PartyFigure.Nd, employerFigure(null, fund()))
        assertEquals(PartyFigure.Nd, employerFigure(null, null))
        assertEquals(PartyFigure.Nd, ownFigure(null))
        assertEquals(PartyFigure.Amount(113_000), ownFigure(113_000))
    }

    @Test
    fun aFundDateIsFreshForFiveWeeksThenDatato() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(FundStamp(today.minusDays(35), false), fundStamp(today.minusDays(35), today))
        assertEquals(FundStamp(today.minusDays(36), true), fundStamp(today.minusDays(36), today))
        assertNull(fundStamp(null, today))
    }
}
