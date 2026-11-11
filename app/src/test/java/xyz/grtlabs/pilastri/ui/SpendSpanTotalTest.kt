package xyz.grtlabs.pilastri.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.grtlabs.pilastri.data.SpendWindows
import xyz.grtlabs.pilastri.data.spendLedger
import xyz.grtlabs.pilastri.debug.SampleArchive
import xyz.grtlabs.pilastri.ui.screens.SpendSpan

/** Polish 3 (owner F1): SpendSpan is total. An index outside 0 until count has no window, month, label or data and never throws (it was `require(k >= 0)` in SpendWindows.window). */
class SpendSpanTotalTest {
    private val today = LocalDate.now()
    private val ledger = SampleArchive.build(today).spendLedger()
    private val t = SpendWindows.anchor(today, ledger.cover)

    @Test fun outOfRangeGivesNothingAndDoesNotThrow() {
        val span = SpendSpan(ledger, t, 3, 0)
        for (k in listOf(-5, -1, 3, 4, 99)) {
            assertNull("window $k", span.window(k)); assertNull("month $k", span.month(k)); assertFalse("data $k", span.hasData(k)); assertEquals("label $k", "—", span.label(k))
        }
        // The stale-index case of the crash: the settled index of the old count (4) against the new single window.
        val single = SpendSpan(ledger, t, 1, 0)
        assertEquals("—", single.label(single.kOf(3)))
        assertEquals("Ultimo mese", single.label(single.kOf(0)))
    }

    @Test fun inRangeStillWorks() {
        val span = SpendSpan(ledger, t, 3, 0)
        for (k in 0..2) { assertNotNull(span.window(k)); assertNotNull(span.month(k)) }
        assertEquals("Ultimo mese", span.label(0))
    }

    @Test fun withoutAnAnchorEverythingIsEmpty() {
        val span = SpendSpan(ledger, null, 3, 0)
        assertNull(span.window(0)); assertEquals("—", span.label(0)); assertFalse(span.hasData(0))
    }
}
