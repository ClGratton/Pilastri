package xyz.grtlabs.pilastri.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class FinanceTest {
    private val day = LocalDate.of(2026, 1, 1)
    @Test fun employerAndPersonalContributionsStaySeparate() {
        val rows = listOf(LedgerEvent("p", day, 4, EventType.PERSONAL_CONTRIBUTION, Money(10000), Confidence.USER_REPORTED, "user"),
            LedgerEvent("e", day, 4, EventType.EMPLOYER_CONTRIBUTION, Money(3000), Confidence.USER_REPORTED, "payroll"))
        assertEquals(10000L, Finance.ownContributions(rows, 4).cents)
        assertEquals(3000L, Finance.employerContributions(rows, 4).cents)
    }
    @Test fun totalRequiresEveryPillar() {
        val partial = (1..3).map { Valuation(day, it, Money(500), "statement", Confidence.DOCUMENT) }
        assertNull(Finance.completeTotal(partial))
        assertEquals(2000L, Finance.completeTotal(partial + Valuation(day, 4, Money(500), "statement", Confidence.DOCUMENT))?.cents)
    }
    @Test fun realValueUsesBaseDividedByDatedIndex() {
        assertEquals(10000L, Finance.inBaseYear(Money(12000), BigDecimal("100"), BigDecimal("120"))?.cents)
        assertNull(Finance.inBaseYear(Money(12000), BigDecimal("100"), null))
        assertNull(Finance.inBaseYear(Money(12000), BigDecimal("100"), BigDecimal.ZERO))
    }
    @Test fun rawChangeNeedsExactStartAndEndSnapshots() {
        val today = LocalDate.of(2026, 4, 10)
        val values = listOf(Valuation(today, 1, Money(12000), "statement", Confidence.DOCUMENT),
            Valuation(today.minusMonths(1), 1, Money(10000), "statement", Confidence.DOCUMENT))
        assertEquals(2000L, Finance.valueChange(values, 1, "Mese")?.cents)
        assertNull(Finance.valueChange(values, 1, "Giorno"))
        assertNull(Finance.valueChange(values, 2, "Mese"))
    }
}
