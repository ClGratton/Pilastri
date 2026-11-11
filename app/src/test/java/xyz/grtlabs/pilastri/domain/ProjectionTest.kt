package xyz.grtlabs.pilastri.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ProjectionTest {
    @Test fun annualAdditionsArriveAfterGrowthAndStaySeparated() {
        val result = calculateProjection(
            ProjectionAssumptions(2026, 2027, 10_000, 2_000, 3_000, BigDecimal("10"), BigDecimal("10")),
        ) as ProjectionOutcome.Available

        val first = result.years[0]
        assertEquals(16_000L, first.endingWealthNominalCents)
        assertEquals(14_545L, first.endingWealth2026Cents)
        assertEquals(2_000L, first.cumulativePersonalNominalCents)
        assertEquals(1_818L, first.cumulativePersonal2026Cents)
        assertEquals(3_000L, first.cumulativeEmployerNominalCents)
        assertEquals(2_727L, first.cumulativeEmployer2026Cents)

        val second = result.years[1]
        assertEquals(22_600L, second.endingWealthNominalCents)
        assertEquals(18_678L, second.endingWealth2026Cents)
        assertEquals(4_000L, second.cumulativePersonalNominalCents)
        assertEquals(3_471L, second.cumulativePersonal2026Cents)
        assertEquals(6_000L, second.cumulativeEmployerNominalCents)
        assertEquals(5_206L, second.cumulativeEmployer2026Cents)
    }

    @Test fun oneHundredPercentLossBoundaryIsRejectedButNegativeGrowthIsModeled() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionAssumptions(2026, 2026, 0, 0, 0, BigDecimal("-100"), BigDecimal("0"))
        }
        val result = calculateProjection(
            ProjectionAssumptions(2026, 2026, 10_000, 2_000, 0, BigDecimal("-50"), BigDecimal("0")),
        ) as ProjectionOutcome.Available
        assertEquals(7_000L, result.years.single().endingWealthNominalCents)
    }

    @Test fun inflationUsesElapsedYearsFromExplicit2026Reference() {
        val result = calculateProjection(
            ProjectionAssumptions(2027, 2027, 11_000, 1_100, 2_200, BigDecimal("0"), BigDecimal("10")),
        ) as ProjectionOutcome.Available
        val year = result.years.single()
        assertEquals(14_300L, year.endingWealthNominalCents)
        assertEquals(11_818L, year.endingWealth2026Cents)
        assertEquals(1_100L, year.cumulativePersonalNominalCents)
        assertEquals(909L, year.cumulativePersonal2026Cents)
        assertEquals(1_818L, year.cumulativeEmployer2026Cents)
    }

    @Test fun modeledEnd2026ContributionsAreDeflatedToStart2026() {
        val result = calculateProjection(
            ProjectionAssumptions(2026, 2026, 0, 12_345, 67_890, BigDecimal("0"), BigDecimal("10")),
        ) as ProjectionOutcome.Available
        val year = result.years.single()

        assertEquals(11_223L, year.cumulativePersonal2026Cents)
        assertEquals(61_718L, year.cumulativeEmployer2026Cents)
    }

    @Test fun legacyEnd2026ReferenceKeepsItsOriginalInterpretation() {
        val result = calculateProjection(ProjectionAssumptions(2026, 2026, 0, 12_345, 67_890,
            BigDecimal.ZERO, BigDecimal("10"), ProjectionPriceReference.END_2026_LEGACY)) as ProjectionOutcome.Available
        assertEquals(12_345L, result.years.single().cumulativePersonal2026Cents)
        assertEquals(67_890L, result.years.single().cumulativeEmployer2026Cents)
    }

    @Test fun scenarioRatePrecisionAndMagnitudeAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionAssumptions(2026, 2026, 0, 0, 0, BigDecimal("1000.000001"), BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionAssumptions(2026, 2026, 0, 0, 0, BigDecimal("1000.0000001"), BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionAssumptions(2026, 2026, 0, 0, 0, BigDecimal.ZERO, BigDecimal("-100.0000001"))
        }
    }

    @Test fun rejectsInvalidHorizonAndReturnsUnavailableOnCentsOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionAssumptions(2025, 2026, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionAssumptions(2027, 2026, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO)
        }
        val outcome = calculateProjection(
            ProjectionAssumptions(2026, 2026, Long.MAX_VALUE, 0, 0, BigDecimal("1"), BigDecimal.ZERO),
        )
        assertEquals(ProjectionOutcome.UnavailableOverflow, outcome)
    }

    @Test fun calculatesOnlyRequestedYearsWithinTheSupportedHorizon() {
        val result = calculateProjection(
            ProjectionAssumptions(2040, 2042, 0, 100, 200, BigDecimal.ZERO, BigDecimal.ZERO),
        ) as ProjectionOutcome.Available
        assertEquals(listOf(2040, 2041, 2042), result.years.map { it.year })
        assertTrue(result.years.all { it.endingWealthNominalCents == 300L * (it.year - 2039) })
    }
}
