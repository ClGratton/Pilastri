package xyz.grtlabs.pilastri.ui.charts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.data.SeriesPoint
import xyz.grtlabs.pilastri.data.Window
import xyz.grtlabs.pilastri.data.BarState
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme
import androidx.compose.runtime.CompositionLocalProvider

/** Canvas-drawn chart labels are exposed as their own text nodes (CR-70 needs them to see duplicated facts). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w420dp-h800dp")
class ChartCanvasSemanticsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun texts(): List<String> {
        rule.waitForIdle()
        return rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            val out = ArrayList<String>()
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
                n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }
                n.children.forEach { walk(it) }
            }
            walk(root); out
        }
    }

    private val window = Window(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31))
    private val points = listOf(
        SeriesPoint(LocalDate.of(2026, 1, 5), 100_000), SeriesPoint(LocalDate.of(2026, 5, 5), 180_000), SeriesPoint(LocalDate.of(2026, 10, 5), 250_000),
    )

    /**
     * The labels come from the label pass (data + size), not from a draw: a rendered DatedLineChart exposes its axis labels and its
     * end label as text nodes in the semantics tree although nothing has been drawn (Robolectric never runs the draw here).
     */
    @Test fun datedLineLabelsAreInTheSemanticsTreeWithoutDrawing() {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                PilastriTheme(false) { Box(Modifier.width(380.dp)) { DatedLineChart(points, window, "Attivita", endLabel = "2.500 €", height = 150.dp) } }
            }
        }
        val t = texts()
        assertTrue("end label missing in $t; pass error ${ChartLabels.lastError?.stackTraceToString()?.take(900)}", "2.500 €" in t)
        assertTrue("axis labels missing in $t", t.any { it == "0" || it.contains("€") || it.endsWith("k") })
        assertTrue("month ticks missing in $t", t.any { it.startsWith("nov") || it.startsWith("gen") || it.startsWith("ott") || it.startsWith("lug") })
    }

    @Test fun monthBarsLabelsAreInTheSemanticsTreeWithoutDrawing() {
        val slots = (1..6).map { BarSlot(YearMonth.of(2026, it), listOf(10_000L * it), BarState.DATA) }
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                PilastriTheme(false) { Box(Modifier.width(380.dp)) { MonthBarsChart(slots, listOf(androidx.compose.ui.graphics.Color.Blue), "Versato", height = 96.dp) } }
            }
        }
        val t = texts()
        assertTrue("0 axis label missing in $t", "0" in t)
        assertTrue("month labels missing in $t", t.count { it.length in 3..6 && it != "0" } >= 2)
    }

    /** The label pass leaves no global state behind and an empty chart records nothing. */
    @Test fun collectLabelsLeavesNoStateBehind() {
        val d = androidx.compose.ui.unit.Density(2f)
        assertTrue(collectLabels(d, androidx.compose.ui.unit.IntSize(400, 200)) { }.isEmpty())
        assertTrue(!ChartLabels.settledPass)
    }
}
