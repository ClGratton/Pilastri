package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rubber band and the arming point of the pull-to-refresh (lazy-UX 16). */
class PullToRefreshMathTest {
    private val limit = 96f
    private val threshold = 56f

    @Test fun noPullNoDistance() {
        assertEquals(0f, pullDistance(0f, limit), 0f)
        assertEquals(0f, pullDistance(-40f, limit), 0f)
        assertEquals(0f, pullDistance(40f, 0f), 0f)
    }

    @Test fun theBandStartsAtTheFingerAndNeverReachesTheLimit() {
        assertEquals(1f, pullDistance(1f, limit), 0.02f)
        var last = 0f
        for (raw in listOf(10f, 50f, 100f, 400f, 4_000f, 1_000_000f)) {
            val d = pullDistance(raw, limit)
            assertTrue("grows with the pull", d > last)
            assertTrue("stays under the limit", d < limit)
            last = d
        }
        assertEquals(limit / 2, pullDistance(limit, limit), 0.001f)
    }

    @Test fun armsOnlyAfterAClearPull() {
        assertFalse(pullArmed(0f, threshold, limit))
        assertFalse(pullArmed(100f, threshold, limit))
        assertTrue(pullArmed(140f, threshold, limit))
        assertTrue(pullArmed(10_000f, threshold, limit))
    }

    @Test fun armingIsMonotone() {
        var armed = false
        for (raw in 0..400 step 5) {
            val now = pullArmed(raw.toFloat(), threshold, limit)
            assertTrue("once armed, a longer pull stays armed", !armed || now)
            armed = now
        }
        assertTrue(armed)
    }
}
