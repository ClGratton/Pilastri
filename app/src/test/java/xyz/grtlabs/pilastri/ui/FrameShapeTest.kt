package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-N2 (Council 3 §1): the frame is chosen by shape as well as size, with hysteresis (ON at w > h+24, OFF at w < h−24 dp). */
class FrameShapeTest {
    private fun layout(w: Float, h: Float, was: Boolean = false) = appLayout(w, h, null, null, false, was)
    private fun two(w: Float, h: Float, was: Boolean = false) = layout(w, h, was) is AppLayout.TwoPane

    @Test fun theOpenFoldIsLandscapeShapedAndSplits() {
        assertTrue("832×750 is 82 dp past the ON edge", two(832f, 750f))
        assertTrue(landscapeShaped(832f, 750f, wasLandscape = false))
        assertTrue(appLayout(830f, 750f, 415f, 0f, false) is AppLayout.TwoPane)
    }

    @Test fun portraitAndSquareShapesNeverSplitFromScratch() {
        assertEquals("portrait tablet", AppLayout.RailSingle, layout(800f, 1200f))
        assertEquals("portrait tablet, even if it was split before", AppLayout.RailSingle, layout(800f, 1200f, was = true))
        assertEquals("square, no history", AppLayout.RailSingle, layout(800f, 800f))
        assertEquals("cover screen stays compact", AppLayout.Compact, layout(360f, 800f, was = true))
    }

    @Test fun insideTheBandThePreviousFrameHolds() {
        assertEquals(AppLayout.RailSingle, layout(780f, 770f, was = false))
        assertTrue(two(780f, 770f, was = true))
        assertTrue("just inside the ON edge (w = h+24) still holds", !two(774f, 750f, was = false) && two(774f, 750f, was = true))
        assertTrue("just past the ON edge", two(774.5f, 750f, was = false))
        assertTrue("just inside the OFF edge (w = h−24)", two(760f, 784f, was = true))
        assertFalse("just past the OFF edge", two(759.5f, 784f, was = true))
    }

    @Test fun landscapeShapedIsPureAndSymmetric() {
        assertTrue(landscapeShaped(1000f, 500f, false))
        assertFalse(landscapeShaped(500f, 1000f, true))
        assertTrue(landscapeShaped(500f, 500f, true))
        assertFalse(landscapeShaped(500f, 500f, false))
        assertEquals(LandscapeEnterDp, LandscapeLeaveDp, 0f)
    }

    @Test fun sweepingTheWidthFlipsOncePerDirectionAtDifferentWidths() {
        val h = 900f
        var was = false; var upAt = -1f; var flips = 0
        for (w in 800..1000) {
            val now = two(w.toFloat(), h, was)
            if (now != was) { flips++; if (now) upAt = w.toFloat() }
            was = now
        }
        assertEquals(1, flips); assertEquals("ON at the first width past h+24", 925f, upAt, 0f)
        var downAt = -1f; flips = 0
        for (w in 1000 downTo 800) {
            val now = two(w.toFloat(), h, was)
            if (now != was) { flips++; if (!now) downAt = w.toFloat() }
            was = now
        }
        assertEquals(1, flips); assertEquals("OFF at the first width past h−24", 875f, downAt, 0f)
        assertTrue("hysteresis: the two edges differ", upAt > downAt)
    }

    @Test fun aWindowJitteringAroundSquareDoesNotFlip() {
        for (start in listOf(true, false)) {
            var was = start
            for (i in 0 until 50) {
                val w = 900f + if (i % 2 == 0) 6f else -6f
                was = two(w, 900f, was)
                assertEquals("jitter of ±6 dp keeps the starting frame", start, was)
            }
        }
    }
}
