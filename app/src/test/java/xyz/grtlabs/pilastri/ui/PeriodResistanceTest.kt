package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The strip's Xtraordinary resistance (MagneticSwipe.displayedProgress, 0.08 free / 0.48 resisted) and that one gesture still moves one month. */
class PeriodResistanceTest {
    // The reference formula, written out independently of the implementation.
    private fun reference(p: Float, resistance: Float): Float {
        if (p <= 0.08f) return p
        val c = (p - 0.08f) / (1f - 0.08f)
        val resisted = minOf(0.08f + 0.48f * sin(c * (PI.toFloat() / 2f)), p)
        return p + (resisted - p) * resistance
    }

    @Test fun theCurveMatchesTheReferenceAndLagsTheFingerBelowTheThreshold() {
        listOf(0f, 0.1f, 0.2f, 0.3f, 0.41f, 0.43f, 0.6f).forEach { f ->
            assertEquals("at $f", reference(f, 1f), PeriodPhysics.displayedProgress(f, 1f), 1e-4f)
            assertEquals("mirrored at -$f", -reference(f, 1f), PeriodPhysics.displayedProgress(-f, 1f), 1e-4f)
            assertEquals("released at $f", f, PeriodPhysics.displayedProgress(f, 0f), 1e-6f)
        }
        listOf(0.1f, 0.2f, 0.3f, 0.41f).forEach { assertTrue(PeriodPhysics.displayedProgress(it, 1f) < it) }
        assertEquals(0.08f, PeriodPhysics.displayedProgress(0.08f, 1f), 1e-6f) // the free travel is the finger
    }

    @Test fun theGestureDrawsTheResistedPositionAndStillCommitsOneMonthAtTheThreshold() {
        val g = PeriodGesture(3f, 0, 9)
        g.drag(0.30f)
        assertTrue(g.displayed(1f) < 3f + 0.30f)
        assertEquals(3f + reference(0.30f, 1f), g.displayed(1f), 1e-4f)
        assertEquals(3, g.release(0f, 1.1f)) // short of 0.42: back
        g.drag(0.13f) // 0.43
        assertEquals(4, g.release(0f, 1.1f))
        assertEquals(3f + 0.43f, g.displayed(0f), 1e-4f) // resistance released: the page is at the finger
        val f = PeriodGesture(3f, 0, 9)
        f.drag(0.05f)
        assertEquals(4, f.release(1.2f, 1.1f)) // a fling of at least 400 dp/s commits the neighbour only
        val far = PeriodGesture(3f, 0, 9)
        far.drag(2.5f)
        assertEquals(4, far.release(0f, 1.1f))
    }
}
