package xyz.grtlabs.pilastri.ui.charts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Polish 2, Q2 (R1 item 5): the "n/d" note never sits on a bar, and the legend keeps a clear gap from the plot. Geometry only, synthetic. */
class ChartClearanceTest {
    private fun bars(n: Int, slotW: Float, barW: Float, dataTop: Float, base: Float, ndTop: Float): List<ChartRect> =
        (0 until n).map { i ->
            val cx = 54f + slotW * (i + 0.5f)
            ChartRect(cx - barW / 2, if (i == n - 1) ndTop else dataTop, cx + barW / 2, base)
        }

    @Test fun markerAtTheRightEdgeIsLiftedClearOfTheNeighbouringBars() {
        // Pillar detail shape: a dashed n/d slot in the last (right-most) column, data bars taller than the dashed slot.
        val canvasW = 400f; val base = 96f
        val all = bars(12, (canvasW - 54f) / 12f, 14f, dataTop = 30f, base = base, ndTop = 52f)
        val nd = all.last(); val cx = (nd.l + nd.r) / 2
        val m = ndMarkerRect(cx, 22f, 11f, canvasW, all, 3f)
        all.forEach { assertFalse("marker $m intersects bar $it", m.intersects(it)) }
        assertTrue("marker stays inside the canvas", m.l >= 0f && m.r <= canvasW && m.t >= 0f)
    }

    @Test fun markerCentredOnAnIsolatedSlotSitsJustAboveIt() {
        val slot = ChartRect(100f, 50f, 114f, 96f)
        val m = ndMarkerRect(107f, 22f, 11f, 400f, listOf(slot), 3f)
        assertTrue(m.b <= slot.t - 3f + 0.01f)
        assertTrue(Math.abs((m.l + m.r) / 2 - 107f) < 0.01f)
    }

    @Test fun legendKeepsAClearGapFromTheAxisLabelsAroundIt() {
        assertTrue("ChartLegendGap must be at least the 8 dp block gap", ChartLegendGap.value >= 8f)
    }
}
