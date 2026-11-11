package xyz.grtlabs.pilastri.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.charts.FanChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.SeriesPoint
import xyz.grtlabs.pilastri.data.Window
import xyz.grtlabs.pilastri.ui.charts.ChartEdge
import xyz.grtlabs.pilastri.ui.charts.coverageDescription
import xyz.grtlabs.pilastri.ui.charts.labelStride

/** D-18/U-20: axis labels never touch; CR-43/G-06: every dated line says its range, last value and coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w420dp-h800dp")
class ChartLabelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Settled labels: with normal motion the fan's line has not trimmed in yet and nothing has been drawn, but its end values are
     * already in the semantics tree (computed from the data, not from an animation frame or a draw).
     */
    @Test fun fanEndLabelsAreSettledSemanticsBeforeAnyAnimationFrame() {
        rule.setContent {
            PilastriTheme(false) {
                Box(Modifier.width(380.dp)) {
                    FanChart(2026, listOf(1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L), null, null, "Scenario", height = 188.dp)
                }
            }
        }
        rule.waitForIdle()
        val out = ArrayList<String>()
        fun walk(n: SemanticsNode) { n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }; n.children.forEach { walk(it) } }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("year labels missing in $out", out.contains("2026") && out.contains("2029"))
        assertTrue("end value label missing in $out", out.any { it.contains("25") })
    }

    @Test fun wideSlotsLabelEverySlot() = assertEquals(1, labelStride(slot = 40f, widest = 20f))
    @Test fun narrowSlotsSkipAlternateLabels() {
        assertEquals(2, labelStride(slot = 16f, widest = 20f))
        assertEquals(3, labelStride(slot = 10f, widest = 20f))
    }
    @Test fun neverBelowOne() = assertEquals(1, labelStride(slot = 0f, widest = 20f))

    /** D-03: the label row's bottom is the canvas bottom, the top label starts at the canvas top, the axis sits between them. */
    @Test fun lastLabelRowEndsAtTheCanvasBottomAndTopLabelStartsAtTheTop() {
        for (h in listOf(96f, 108f, 120f, 150f)) for (lh in listOf(12f, 14f, 18f)) {
            val labelTop = ChartEdge.labelTop(h, lh)
            assertEquals("label row bottom == canvas bottom", h, labelTop + lh, 0.001f)
            val base = ChartEdge.axisBase(h, lh, 4f)
            assertTrue("axis above the label row", base + 4f <= labelTop + 0.001f)
            assertEquals("top y label (centred on its line) starts at 0", 0f, ChartEdge.plotTop(lh) - lh / 2f, 0.001f)
            assertTrue("room for a plot", ChartEdge.fits(h, lh, 4f))
        }
        assertEquals("a point dot taller than the label keeps its whole extent inside", 5.3f, ChartEdge.plotTop(10f, 5.3f), 0.001f)
    }

    /** N-03/U-20: month labels of 14 px slots never touch; one label per fitting slot, the last always shown. */
    @Test fun monthLabelsNeverTouch() {
        val widest = 20f
        for (slot in listOf(8f, 12f, 16f, 22f, 30f)) {
            val stride = labelStride(slot, widest)
            assertTrue("slot $slot: neighbouring labels ${stride * slot}px apart need >= $widest", stride * slot >= widest)
        }
    }

    private val w = Window(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31))
    @Test fun singlePointStatesWhereDataStarts() {
        val d = coverageDescription(listOf(SeriesPoint(LocalDate.of(2026, 10, 12), 100_000)), w)
        assertTrue(d, d.contains("dati dal 12") && d.contains("un solo punto"))
    }
    @Test fun emptyAndFullCoverage() {
        assertTrue(coverageDescription(emptyList(), w).contains("nessun dato"))
        val d = coverageDescription(listOf(SeriesPoint(LocalDate.of(2026, 1, 5), 100_000), SeriesPoint(LocalDate.of(2026, 10, 5), 250_000)), w)
        assertTrue(d, d.contains("2 punti") && d.contains("ultimo"))
    }
}
