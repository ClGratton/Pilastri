package xyz.grtlabs.pilastri.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 8 (owner J). CR-68 / M1 on the whole period header group (user 2026-11-08: "se cose appartengono allo stesso elemento, quasi sempre avranno lo stesso movimento") and
 * CR-72 (figure and currency never split by a breakable space). Positive controls: the bad trees MUST be flagged, the good twins MUST NOT. Pure engine, no Compose.
 */
class PeriodHeaderGroupTest {
    private fun n(id: String, text: String, l: Float, t: Float, r: Float, b: Float) = N(id, text, l, t, r, b)

    // wave 7 phone-mese-mid (output/consistency-2026-11-09h/phone-mese-mid-{rest,moving}.xml), px as dp
    private fun frame(titleR: Float, curL: Float, curR: Float, vararg extra: N) = listOf(
        n("page-header", "", 42f, 84f, 1038f, 210f), n("period-title", "ottobre", 189f, 89f, titleR, 157f),
        n("period-viewport", "", 0f, 378f, 1080f, 1535f), n("period-page-cur", "", curL, 378f, curR, 1535f)) + extra

    private val subRest = n("page-subtitle", "1 – 31 ott", 189f, 157f, 670f, 192f)
    private val rest = frame(452f, 42f, 1038f, subRest)
    private fun rows(moving: List<N>, rest: List<N> = this.rest) = Rules.pair("phone-mese-mid", "phone", rest, moving)

    @Test fun wave7PairSubtitleStillIsFlagged() {
        val r = rows(frame(257f, 0f, 843f, subRest))
        assertTrue("page-subtitle unchanged while the title moved -195 (rows: $r)", r.any { it.rule == "motion: period header moves like its page (CR-68, M1)" && it.el.startsWith("page-subtitle") })
        assertTrue("period-title itself moves with its page (rows: $r)", r.none { it.el.startsWith("period-title") })
    }

    @Test fun goodTwinSubtitleAndChipMovedByTheSameDxIsClean() {
        val chipRest = n("period-chip", "giorno 9", 900f, 100f, 1000f, 140f)
        val r = rows(frame(257f, 0f, 843f, n("page-subtitle", "1 – 31 ott", -6f, 157f, 475f, 192f), n("period-chip", "giorno 9", 705f, 100f, 805f, 140f)),
            rest = frame(452f, 42f, 1038f, subRest, chipRest))
        // subtitle l 189 -> -6 and r 670 -> 475 is dx -195 on both edges
        assertTrue("good twin must be clean (rows: $r)", r.isEmpty())
    }

    @Test fun chipStillWhileTheTitleMovesIsFlagged() {
        val chip = n("period-chip", "giorno 9", 900f, 100f, 1000f, 140f)
        val r = rows(frame(257f, 0f, 843f, n("page-subtitle", "1 – 31 ott", -6f, 157f, 475f, 192f), chip), rest = frame(452f, 42f, 1038f, subRest, chip))
        assertEquals(r.toString(), 1, r.count { it.rule == "motion: period header moves like its page (CR-68, M1)" && it.el.startsWith("period-chip") })
        assertTrue("subtitle moved correctly: not flagged (rows: $r)", r.none { it.el.startsWith("page-subtitle") })
    }

    @Test fun memberAbsentWhileMovingIsAnExplicitCaptureRow() {
        val r = rows(frame(257f, 0f, 843f))
        assertTrue("page-subtitle present at rest, gone while moving (rows: $r)", r.any { it.rule.startsWith("capture: page-subtitle absent") })
    }

    // ---- CR-72 ----
    private fun cur(text: String) = Shot("phone-x", "phone", true, listOf(n("page-content", "", 0f, 0f, 400f, 800f), n("", text, 10f, 10f, 100f, 30f)), 400f, 800f)
    private fun cr72(text: String) = Rules.check(listOf(cur(text))).filter { it.rule.contains("CR-72") }

    @Test fun breakableSpaceBetweenFigureAndCurrencyIsFlagged() {
        assertEquals(1, cr72("441 €").size)
        assertEquals(1, cr72("€ 441").size)
        assertEquals(1, cr72("dal saldo 12,50 $").size)
    }

    @Test fun noBreakSpacesAreClean() {
        assertTrue(cr72("441 €").isEmpty())
        assertTrue(cr72("441 €").isEmpty())
        assertTrue(cr72("€ 441").isEmpty())
        assertTrue(cr72("441€").isEmpty())
    }
}
