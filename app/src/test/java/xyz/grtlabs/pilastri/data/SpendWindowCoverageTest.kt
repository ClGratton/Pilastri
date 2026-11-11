package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATA-CONTRACT "SpendWindow": coverage is per window, so the "parziale" marker follows the selected window, never a neighbour. */
class SpendWindowCoverageTest {
    private val t = LocalDate.parse("2026-11-09")
    private val coverFrom = LocalDate.parse("2026-10-20") // inside the live window (10 ott - 9 nov): partial

    @Test fun liveWindowIsPartialFromTheCoverageStart() {
        val w = SpendWindows.window(t, 0, coverFrom)
        assertTrue(w.partial)
        assertEquals(coverFrom, w.coveredFrom)
    }

    @Test fun earlierWindowFullyCoveredIsNotPartial() {
        val w = SpendWindows.window(t, 1, LocalDate.parse("2026-08-01"))
        assertFalse(w.partial)
        assertTrue(w.full)
    }

    @Test fun windowBeforeTheCoverageHasNoneAndIsNotPartial() {
        val w = SpendWindows.window(t, 1, coverFrom) // 10 set - 9 ott ends before the coverage starts
        assertNull(w.coveredFrom)
        assertFalse(w.partial)
    }
}
