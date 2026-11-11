package xyz.grtlabs.pilastri.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.PositionKind
import java.time.LocalDate
import java.time.YearMonth

/** The screen facade: F24 commitments, first-job extra, combinations, conflicting duplicates. Synthetic amounts only. */
class RulesFacadeTest {
    private val rules = Shipped.rules

    private fun schedule(year: Int, ret: PreviousReturn?) = rules.f24Schedule(year, ret).associateBy { it.kind }

    @Test fun f24DatesComeFromTheRulesAndRollOffWeekends() {
        val y26 = schedule(2026, null)
        assertEquals(LocalDate.of(2026, 6, 30), y26.getValue(F24Kind.SALDO).due.value)
        assertEquals(RuleStatus.EXACT, y26.getValue(F24Kind.SALDO).due.status)
        assertEquals(2025, y26.getValue(F24Kind.SALDO).taxYear)
        assertEquals(LocalDate.of(2026, 7, 30), y26.getValue(F24Kind.ACCONTO_1).lateWithSurcharge!!.value)
        assertEquals(LocalDate.of(2026, 11, 30), y26.getValue(F24Kind.ACCONTO_2).due.value)
        y26.values.forEach { assertEquals(RuleStatus.ND, it.amount.status); assertNull(it.amount.value) }
        val y29 = schedule(2029, null)
        assertEquals(LocalDate.of(2029, 7, 2), y29.getValue(F24Kind.SALDO).due.value) // 30 June 2029 is a Saturday
        val y30 = schedule(2030, null)
        assertEquals(LocalDate.of(2030, 7, 1), y30.getValue(F24Kind.SALDO).due.value) // Sunday
        assertEquals(LocalDate.of(2030, 12, 2), y30.getValue(F24Kind.ACCONTO_2).due.value) // Saturday
    }

    @Test fun f24AmountsAreStimaFromThePreviousReturn() {
        val s = schedule(2026, PreviousReturn(2025, 100_000, 60_000, isaOrForfettario = false))
        assertEquals(40_000L, s.getValue(F24Kind.SALDO).amount.value)
        assertEquals(40_000L, s.getValue(F24Kind.ACCONTO_1).amount.value)
        assertEquals(60_000L, s.getValue(F24Kind.ACCONTO_2).amount.value)
        s.values.forEach { assertEquals(RuleStatus.STIMA, it.amount.status) }
        // below 257.52: one payment in November; at or below 51.65: no acconto
        schedule(2026, PreviousReturn(2025, 20_000, 0, false)).let {
            assertEquals(0L, it.getValue(F24Kind.ACCONTO_1).amount.value); assertEquals(20_000L, it.getValue(F24Kind.ACCONTO_2).amount.value)
        }
        schedule(2026, PreviousReturn(2025, 5_000, 0, false)).let {
            assertEquals(0L, it.getValue(F24Kind.ACCONTO_1).amount.value); assertEquals(0L, it.getValue(F24Kind.ACCONTO_2).amount.value)
            assertEquals(5_000L, it.getValue(F24Kind.SALDO).amount.value)
        }
        // ISA / forfettari: two equal instalments above 206
        schedule(2026, PreviousReturn(2025, 100_000, 0, true)).let {
            assertEquals(50_000L, it.getValue(F24Kind.ACCONTO_1).amount.value); assertEquals(50_000L, it.getValue(F24Kind.ACCONTO_2).amount.value)
        }
        // saldo at or below 12 EUR, or a credit, is not a payment
        assertEquals(0L, schedule(2026, PreviousReturn(2025, 100_000, 99_000, false)).getValue(F24Kind.SALDO).amount.value)
        assertEquals(0L, schedule(2026, PreviousReturn(2025, 100_000, 120_000, false)).getValue(F24Kind.SALDO).amount.value)
    }

    @Test fun f24AmountsNdWithoutTheRightReturn() {
        schedule(2026, PreviousReturn(2024, 100_000, 0, false)).values.forEach { assertEquals(RuleStatus.ND, it.amount.status) }
        schedule(2026, PreviousReturn(2025, 100_000, null, null)).let {
            assertEquals(RuleStatus.ND, it.getValue(F24Kind.SALDO).amount.status)
            assertEquals(RuleStatus.ND, it.getValue(F24Kind.ACCONTO_1).amount.status)
        }
    }

    @Test fun firstJobExtraIsNdWhileUnusedRoomIsIncerto() {
        val r = rules.firstJobExtraDeduction(2026)
        assertEquals(RuleStatus.ND, r.status)
        assertNull(r.value)
        assertTrue(r.sourceIds.contains("pc.deduction.firstjob.unused"))
        assertTrue(r.reason!!.contains("INCERTO"))
        assertEquals(RuleStatus.ND, rules.firstJobExtraDeduction(2025).status)
    }

    @Test fun irpefCombinationWithForfettarioIsNd() {
        val nd = rules.irpefGross(3_000_000, 2026, setOf(PositionKind.DIPENDENTE_PRIVATO, PositionKind.AUTONOMO_FORFETTARIO))
        assertEquals(RuleStatus.ND, nd.status)
        assertTrue(nd.reason!!.contains("combinazione"))
        assertEquals(RuleStatus.EXACT, rules.irpefGross(3_000_000, 2026, setOf(PositionKind.DIPENDENTE_PRIVATO, PositionKind.COLLABORATORE)).status)
    }

    @Test fun conflictingDuplicatesAndUnknownAmounts() {
        val p = YearMonth.of(2026, 4)
        val conflict = rules.deductionCeiling(2026, listOf(FundContribution("f", p, 1_000, 500), FundContribution("f", p, 1_200, 500)))
        assertEquals(RuleStatus.STIMA, conflict.deductible.status)
        assertEquals(1, conflict.duplicatesDropped)
        val unknown = rules.deductionCeiling(2026, listOf(FundContribution("f", p, 1_000, null)))
        assertEquals(RuleStatus.ND, unknown.deductible.status)
        assertEquals(RuleStatus.EXACT, unknown.ceiling.status)
        assertEquals(530_000L, unknown.ceiling.value)
    }
}
