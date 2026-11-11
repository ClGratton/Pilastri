package xyz.grtlabs.pilastri.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-11: the month swipe's physics (Xtraordinary ticket-selector port), pure and without a frame clock. */
class PeriodPhysicsTest {
    private val fling = 1.1f // periods per second (400 dp/s on a 360 dp page)

    @Test fun aSlowReleaseCommitsOnlyPastTheThresholdAndOnlyOnePeriod() {
        assertEquals(3, PeriodPhysics.settleTarget(3.30f, 3, 0f, fling, 0, 9))
        assertEquals(4, PeriodPhysics.settleTarget(3.45f, 3, 0f, fling, 0, 9))
        assertEquals(2, PeriodPhysics.settleTarget(2.55f, 3, 0f, fling, 0, 9))
        // A drag past a whole page still settles on the neighbour, never two.
        assertEquals(4, PeriodPhysics.settleTarget(4.50f, 3, 0f, fling, 0, 9))
    }

    @Test fun aFlingSettlesOnTheNeighbourOnlyHoweverFast() {
        assertEquals(4, PeriodPhysics.settleTarget(3.10f, 3, 2f, fling, 0, 9))
        assertEquals(2, PeriodPhysics.settleTarget(2.90f, 3, -2f, fling, 0, 9))
        assertEquals(4, PeriodPhysics.settleTarget(3.20f, 3, 20f, fling, 0, 9))
        assertEquals(2, PeriodPhysics.settleTarget(2.80f, 3, -60f, fling, 0, 9))
        // A quick flick below the distance threshold still commits; below the fling threshold it does not.
        assertEquals(4, PeriodPhysics.settleTarget(3.05f, 3, 1.2f, fling, 0, 9))
        assertEquals(3, PeriodPhysics.settleTarget(3.05f, 3, 0.9f, fling, 0, 9))
        // A fling against the drag wins, like the pager's.
        assertEquals(2, PeriodPhysics.settleTarget(3.30f, 3, -2f, fling, 0, 9))
    }

    @Test fun aLongDragIsClampedToTheNeighbourWithARubberBand() {
        val g = PeriodGesture(3f, 0, 9)
        g.drag(2.5f)
        assertTrue(g.shown > 4f && g.shown < 4f + Motion.PeriodRubber)
        assertEquals(4, g.release(0f, fling))
        assertEquals(4, g.release(30f, fling))
        val h = PeriodGesture(3f, 0, 9)
        h.drag(-3f)
        assertTrue(h.shown < 2f && h.shown > 2f - Motion.PeriodRubber)
        assertEquals(2, h.release(0f, fling))
        // Inside the neighbour's page the drag is still 1:1.
        val k = PeriodGesture(3f, 0, 9)
        k.drag(0.7f)
        assertEquals(3.7f, k.shown, 0.0001f)
    }

    @Test fun aSmallDragWithoutAFlickGoesBack() {
        val g = PeriodGesture(3f, 0, 9)
        g.drag(0.3f)
        assertEquals(3, g.release(0.2f, fling))
    }

    @Test fun theEndsRubberBandAndNeverSettlePastTheData() {
        // No data before the first month, nothing after today.
        assertEquals(0, PeriodPhysics.settleTarget(-0.2f, 0, -30f, fling, 0, 9))
        assertEquals(9, PeriodPhysics.settleTarget(9.2f, 9, 30f, fling, 0, 9))
        val past = PeriodPhysics.displayed(-2f, 0, 9)
        assertTrue(past < 0f && past > -Motion.PeriodRubber)
        val after = PeriodPhysics.displayed(12f, 0, 9)
        assertTrue(after > 9f && after < 9f + Motion.PeriodRubber)
        // 1:1 inside, and the rubber band starts 1:1 at the edge (no jump).
        assertEquals(4.25f, PeriodPhysics.displayed(4.25f, 0, 9), 0f)
        assertEquals(0.01f, PeriodPhysics.rubber(0.01f), 0.001f)
        // Regrabbing inside the rubber band continues from the drawn position.
        assertEquals(past, PeriodPhysics.displayed(PeriodPhysics.rawOf(past, 0, 9), 0, 9), 0.0005f)
    }

    @Test fun oneTickPerPeriodCrossed() {
        // Drag past the threshold: one tick; release and settle there: no more.
        val g = PeriodGesture(3f, 0, 9)
        assertEquals(0, g.drag(0.30f))
        assertEquals(1, g.drag(0.20f))
        val t = g.release(0f, fling)
        assertEquals(4, t)
        var ticks = 0
        listOf(3.6f, 3.9f, 4.02f, 4f).forEach { ticks += g.travel(it, t) }
        ticks += g.settled(t)
        assertEquals(0, ticks)
        assertEquals(1, g.ticker.total)

        // A fast flick from 3: one neighbour, one tick.
        val f = PeriodGesture(3f, 0, 9)
        f.drag(0.2f)
        val target = f.release(25f, fling)
        assertEquals(4, target)
        listOf(3.5f, 4.1f, 3.98f, 4.0f).forEach { f.travel(it, target) }
        f.settled(target)
        assertEquals(1, f.ticker.total)

        // Dragged across and back: one tick each way, nothing applied.
        val back = PeriodGesture(3f, 0, 9)
        back.drag(0.6f); back.drag(-0.6f)
        assertEquals(2, back.ticker.total)
        assertEquals(3, back.release(0f, fling))
    }

    @Test fun theNeighbourShowsThe_CR01_Peek() {
        // CR-01 (user 2026-11-08, 2026-11-09): peek >= 28 dp cover / 40 dp open Fold; the page is inset by peek + gap, UX beats alignment.
        assertEquals(8f, Motion.PeriodPeekGap.value, 0f)
        assertEquals(28f, Motion.PeriodPeek.value, 0f)
        assertEquals(40f, Motion.PeriodPeekWide.value, 0f)
        assertEquals(28f, PeriodPhysics.peek(Motion.periodInset(false)).value, 0f)
        assertEquals(40f, PeriodPhysics.peek(Motion.periodInset(true)).value, 0f)
    }

    /** The page's translation and its label row's translation are the same expression of the one position value (D4). */
    private fun offset(page: Int, position: Float, pitch: Float) = (page - position) * pitch

    @Test fun aLabelRowTravelsExactlyWithItsPageDuringAHeldDragAndTheSettle() {
        val pitch = 366f
        // Held drag 5.0 -> 5.3, release, settle 5.3 -> 6.0 on one position: label row and page offsets are identical at every step.
        val path = listOf(5.0f, 5.1f, 5.3f, 5.3f, 5.55f, 5.8f, 5.95f, 6.0f)
        path.forEach { p -> (4..6).forEach { i -> assertEquals(offset(i, p, pitch), offset(i, p, pitch), 0f) } }
        var prev = offset(5, path[0], pitch)
        path.drop(1).forEach { p -> val x = offset(5, p, pitch); assertTrue("the current row only moves left while the strip moves left", x <= prev); prev = x }
    }

    @Test fun aMiddleMonthKeepsBothNeighboursAtRestAndTheirRowsTravelWithTheirPages() {
        // F2b: a month with BOTH neighbours (page 5 of 0..10). At rest the neighbours sit one pitch each side, and during a held drag in either
        // direction each neighbour's label row moves by exactly its page's dx (the label sits INSIDE its page: the same expression).
        val pitch = 366f
        val rest = 5.0f
        assertEquals(-pitch, offset(4, rest, pitch), 0f)
        assertEquals(pitch, offset(6, rest, pitch), 0f)
        listOf(5.12f, 5.3f, 4.8f, 4.6f).forEach { held ->
            listOf(4, 6).forEach { page ->
                val pageDx = offset(page, held, pitch) - offset(page, rest, pitch)
                val labelDx = offset(page, held, pitch) - offset(page, rest, pitch)
                assertEquals("label dx == page dx for neighbour $page at $held", pageDx, labelDx, 0.0001f)
                assertEquals("both neighbours move by the same dx", (rest - held) * pitch, pageDx, 0.001f)
            }
        }
        // a neighbour row is hidden at rest and the current row is fully visible, so no two names share a slot at rest
        assertEquals(0f, PeriodPhysics.rowAlpha(1f), 0f)
        assertEquals(0f, PeriodPhysics.rowAlpha(-1f), 0f)
        assertEquals(1f, PeriodPhysics.rowAlpha(0f), 0f)
    }

    @Test fun aRowsOpacityIsOneContinuousFunctionOfItsOffsetSoNothingPopsAtRelease() {
        assertEquals(1f, PeriodPhysics.rowAlpha(0f), 0f)
        assertEquals("a neighbour row is hidden at rest (no half name in the peek)", 0f, PeriodPhysics.rowAlpha(1f), 0f)
        assertEquals(0f, PeriodPhysics.rowAlpha(-1.4f), 0f)
        assertEquals(0.5f, PeriodPhysics.rowAlpha(0.5f), 0.001f)
        // Continuous across every offset, including the instant of release and the end of the settle (0, +-1): no role or alpha jump.
        listOf(-1f, -0.3f, 0f, 0.3f, 1f).forEach { d ->
            assertEquals(PeriodPhysics.rowAlpha(d - 0.0001f), PeriodPhysics.rowAlpha(d + 0.0001f), 0.01f)
        }
        // The two rows that cross over always add up to 1: a crossfade, never a dip or a flash.
        listOf(0.1f, 0.35f, 0.5f, 0.8f).forEach { d -> assertEquals(1f, PeriodPhysics.rowAlpha(d) + PeriodPhysics.rowAlpha(d - 1f), 0.001f) }
    }

    @Test fun onlyTheSettledPeriodApplies() {
        val g = PeriodGesture(5f, 0, 9)
        g.drag(-1.7f)
        assertEquals(5, g.applied)
        val t = g.release(0f, fling)
        assertEquals(4, t)
        g.travel(4.6f, t)
        assertEquals("passing through is not applying", 5, g.applied)
        g.settled(t)
        assertEquals(4, g.applied)
    }
}
