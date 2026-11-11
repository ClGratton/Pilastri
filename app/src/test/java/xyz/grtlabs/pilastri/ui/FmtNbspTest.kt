package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 7: a figure and its currency symbol never split across lines: U+00A0 between them, never a plain space next to the euro sign. */
class FmtNbspTest {
    private val nbsp = " "

    private fun joined(s: String) {
        assertTrue("no NBSP before the euro sign in '$s'", s.endsWith(nbsp + "€"))
        assertFalse("plain space next to the euro sign in '$s'", s.contains(" €") || s.contains("€ "))
    }

    @Test fun wholeEurosJoinFigureAndSymbol() {
        listOf(0L, -4_500L, 2_463_000L, -123_456_789L, 49L).forEach { joined(Fmt.eur(it)) }
        assertEquals("24.630$nbsp€", Fmt.eur(2_463_000L))
        assertEquals("−45$nbsp€", Fmt.eur(-4_500L))
        assertEquals("0$nbsp€", Fmt.eur(0L))
    }

    @Test fun centsAndSignedJoinFigureAndSymbol() {
        listOf(0L, -4_550L, 2_463_012L, 5L).forEach { joined(Fmt.eurCents(it)); joined(Fmt.signed(it)); joined(Fmt.signedCents(it)) }
        assertEquals("24.630,12$nbsp€", Fmt.eurCents(2_463_012L))
        assertEquals("+441$nbsp€", Fmt.signed(44_100L))
    }
}
