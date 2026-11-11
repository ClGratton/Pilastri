package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.grtlabs.pilastri.data.*
import java.time.LocalDate

/** 3a-01 window line states (Council 5 a) and the charts' round scale. Synthetic data only. */
class SpeseCopyTest {
    private val f = CategoryFixtures
    private val today = LocalDate.parse("2026-11-01")

    private fun view(from: String, to: String, k: Int = 0): SpendWindowView? = Archive(
        accounts = listOf(f.paypal), externalMovements = listOf(f.mv("m", "Negozio", -1_000, "2026-10-21")),
        connections = listOf(Connection(ImportProvider.PAYPAL, f.paypal.id, null, LocalDate.parse(from), LocalDate.parse(to), null)),
    ).spendWindowView(today, k)

    /** One range per period: the shown start is the first covered day when the data starts after the window's start. */
    @Test fun windowRangeIsClampedToTheCoveredDays() {
        assertEquals(LocalDate.parse("2026-10-02"), view("2026-01-01", "2026-11-01")!!.window.shownFrom)
        assertEquals(LocalDate.parse("2026-10-20"), view("2026-10-20", "2026-11-01")!!.window.shownFrom)
        assertEquals(LocalDate.parse("2026-11-01"), view("2026-10-20", "2026-11-01")!!.window.to)
    }

    @Test fun scalesRoundUp() {
        assertEquals(100L, niceTop(0L))
        assertEquals(200_000L, niceTop(170_000L))
        assertEquals(250_000L, niceTop(200_001L))
        assertEquals(1_000_000L, niceTop(600_000L))
    }
}
