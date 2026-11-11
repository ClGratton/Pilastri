package xyz.grtlabs.pilastri.sync.auto

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.Connection
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ExternalMovement
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.PlanReview

/**
 * The state the Fold was left in (2026-10-31, synthetic ids): a year of PayPal coverage, claimed first with no PayPal movement
 * and then by windows of exactly 30 rows that the page may have cut. v3 clears every PayPal claim made before the fix, once.
 */
class CoverageRepairTest {
    private val from = LocalDate.of(2025, 11, 1)
    private val to = LocalDate.of(2026, 10, 31)
    private fun paypal(result: String?, f: LocalDate? = from, t: LocalDate? = to) =
        Connection(ImportProvider.PAYPAL, "conto-sintetico", "2026-10-31T09:00:00Z", f, t, result)
    private val isybank = Connection(ImportProvider.ISYBANK, "isy-sintetico", "2026-10-30T09:00:00Z", from, to, "0 nuovi, 3 già presenti")
    private fun movement(provider: ImportProvider) = ExternalMovement(
        "${provider.name}:web:sintetico", provider, "conto-sintetico", LocalDate.of(2026, 10, 2), -100, "EUR", "Sintetico",
        ExternalKind.CARD_PAYMENT, null, null, null, ExternalStatus.PENDING, null, "2026-10-31T09:00:00Z",
    )

    @Test fun anEmptyPaypalYearLosesItsCoverageAndNothingElseChanges() {
        val bad = Archive(connections = listOf(paypal("0 nuovi, 0 già presenti"), isybank), externalMovements = listOf(movement(ImportProvider.ISYBANK)))
        assertTrue(CoverageRepair.needed(bad))
        val fixed = CoverageRepair.repaired(bad)
        val c = fixed.connections.first { it.provider == ImportProvider.PAYPAL }
        assertNull(c.coverageFrom)
        assertNull(c.coverageTo)
        assertEquals("0 nuovi, 0 già presenti", c.lastResult)
        assertEquals(isybank, fixed.connections.first { it.provider == ImportProvider.ISYBANK })
        assertEquals(bad.externalMovements, fixed.externalMovements)
        assertFalse(CoverageRepair.needed(fixed))
    }

    @Test fun aPaypalClaimBackedByRowsIsClearedToo_becauseTheWindowMayHaveBeenCut() {
        // The Fold: 30 rows in a 90-day window were stored and the whole window was claimed. The rows cannot say what the page hid.
        val withMovement = Archive(connections = listOf(paypal("30 nuovi, 0 già presenti"), isybank), externalMovements = listOf(movement(ImportProvider.PAYPAL), movement(ImportProvider.ISYBANK)))
        assertTrue(CoverageRepair.needed(withMovement))
        val fixed = CoverageRepair.repaired(withMovement)
        assertNull(fixed.connections.first { it.provider == ImportProvider.PAYPAL }.coverageFrom)
        assertEquals(withMovement.externalMovements, fixed.externalMovements)
        assertEquals(isybank, fixed.connections.first { it.provider == ImportProvider.ISYBANK })
        val withPlan = Archive(connections = listOf(paypal("1 nuovo")), installments = listOf(plan(LocalDate.of(2026, 9, 3))))
        val fixedPlan = CoverageRepair.repaired(withPlan)
        assertNull(fixedPlan.connections.single().coverageTo)
        assertEquals(withPlan.installments, fixedPlan.installments)
    }

    @Test fun nothingToClearMeansTheSameArchive() {
        val noClaim = Archive(connections = listOf(paypal("0 nuovi, 0 già presenti", null, null)), externalMovements = listOf(movement(ImportProvider.PAYPAL)))
        assertFalse(CoverageRepair.needed(noClaim))
        assertSame(noClaim, CoverageRepair.repaired(noClaim))
        val others = Archive(connections = listOf(isybank))
        assertFalse(CoverageRepair.needed(others))
        assertSame(others, CoverageRepair.repaired(others))
        assertFalse(CoverageRepair.needed(Archive()))
    }

    @Test fun theRuleIsTheClaimNotTheResultText() {
        for (result in listOf("4 nuovi, 0 già presenti", null, "Lettura di PayPal non riuscita")) {
            val fixed = CoverageRepair.repaired(Archive(connections = listOf(paypal(result))))
            assertNull(fixed.connections.single().coverageFrom)
            assertNull(fixed.connections.single().coverageTo)
            assertEquals(result, fixed.connections.single().lastResult)
        }
    }

    @Test fun anOpenEndedClaimIsClearedToo() {
        val fromOnly = paypal("x", f = from, t = null)
        assertTrue(CoverageRepair.needed(Archive(connections = listOf(fromOnly))))
        assertNull(CoverageRepair.repaired(Archive(connections = listOf(fromOnly))).connections.single().coverageFrom)
    }

    @Test fun everyPaypalConnectionIsClearedAndRepairingTwiceChangesNothing() {
        val a = paypal("3 nuovi").copy(accountId = "conto-a")
        val b = paypal("0 nuovi").copy(accountId = "conto-b", coverageFrom = LocalDate.of(2023, 1, 1), coverageTo = LocalDate.of(2023, 12, 31))
        val fixed = CoverageRepair.repaired(Archive(connections = listOf(a, b, isybank)))
        assertNull(fixed.connections[0].coverageFrom)
        assertNull(fixed.connections[1].coverageFrom)
        assertEquals(isybank, fixed.connections[2])
        assertSame(fixed, CoverageRepair.repaired(fixed))
    }

    @Test fun theMarkerKeyIsNewSoTheHostRunsTheRepairAgain() {
        assertEquals("paypal-unbacked-coverage-v3", CoverageRepair.PAYPAL_EMPTY)
    }

    private fun plan(date: LocalDate) = InstallmentPlan(
        id = "paypal-web:sintetico", lender = "PayPal Paga in 3 rate", merchant = null, totalCents = 9000, currency = "EUR", count = 3,
        installments = emptyList(), purchaseDate = date, source = InstallmentSource.PAYPAL, evidence = emptyList(),
        review = PlanReview.PENDING, extractedByAgent = false, updatedAt = "2026-10-31T09:00:00Z",
    )
}
