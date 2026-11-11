package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PeriodLabels
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.rememberPeriodState

/**
 * CR-69 (F2b). The period label row of a MIDDLE month (both neighbours) at every width and font scale up to 2.0: the previous, current and next names never
 * overlap and are never ellipsized (the "Ottob|Novembre" defect). Widths include cells far narrower than the app's so the shedding path (neighbour suffix,
 * abbreviation, current suffix, neighbour names) runs even though Robolectric's glyph widths are small. Also the positive control of the overlap rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class PeriodLabelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val names = listOf("Agosto", "Settembre", "Ottobre", "Novembre · dati al 6 nov", "Dicembre")

    private fun render(widths: List<Int>, scales: List<Float>, selected: List<Int>, content: @Composable (Int) -> Unit): List<Triple<Int, Float, List<N>>> {
        var idx by mutableIntStateOf(0)
        val combos = selected.flatMap { sel -> widths.flatMap { w -> scales.map { s -> Triple(sel, w, s) } } }
        rule.setContent {
            val (sel, w, s) = combos[idx]
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalDensity provides Density(LocalDensity.current.density, s)) {
                    key(idx) { Box(Modifier.width(w.dp).semantics { testTagsAsResourceId = true }) { content(sel) } }
                }
            }
        }
        val out = ArrayList<Triple<Int, Float, List<N>>>()
        for (i in combos.indices) {
            idx = i
            rule.waitForIdle(); rule.mainClock.advanceTimeBy(500)
            out += Triple(combos[i].second, combos[i].third, collectNodes(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), rule.density))
        }
        return out
    }

    @Test fun aMiddleMonthsLabelsNeverOverlapOrEllipsize() {
        val rows = ArrayList<Dev>()
        // 2 = Ottobre (next is the running month with its suffix), 3 = Novembre (current carries the suffix); widths 100..150 dp force the shedding path.
        val shots = render(listOf(100, 150, 260, 411, 840), listOf(1.0f, 2.0f), listOf(2, 3)) { s ->
            val state = rememberPeriodState(s, names.size)
            PeriodLabels(state, { names[it] }, {}, {}, Type.smallStrong, Color.Black, gutter = 16.dp, wide = false)
        }
        run {
            for ((w, sc, nodes) in shots) {
                val name = "@${w}dp x$sc"
                val labels = nodes.filter { it.id.startsWith("period-label-") }
                assertTrue("$name: the current label exists", labels.any { it.id == "period-label-cur" })
                rows += Rules.textOverlap(Shot(name, "phone", false, nodes.filter { it.text.isNotBlank() && it.id.startsWith("period-label-") }.map { it.copy(fs = 14f) }, 4000f, 4000f))
                rows += Rules.textClip(Shot(name, "phone", false, nodes, w.toFloat(), 4000f))
            }
        }
        File("build/layout-consistency").apply { mkdirs() }.resolve("period-labels.md").writeText(rows.joinToString("\n") { it.row() })
        if (rows.isNotEmpty()) fail("${rows.size} label deviation(s)\n" + rows.joinToString("\n") { it.row() })
    }

    /** Positive control: two texts laid over each other MUST be reported; texts that sit apart must not. */
    @Test fun positiveControlOverlappingTextsAreReported() {
        val shots = render(listOf(300), listOf(1.0f), listOf(0)) {
            Box(Modifier.fillMaxWidth()) {
                Text("Ottobre")
                Text("Novembre · dati", Modifier.offset(x = 3.dp))
            }
        }
        val owners = shots[0].third.filter { it.text.isNotBlank() }.map { it.copy(fs = 14f) }
        val rows = Rules.textOverlap(Shot("control", "phone", false, owners, 300f, 673f))
        assertTrue("overlap rule lost its teeth (owners: $owners)", rows.isNotEmpty())
        val apart = owners.map { if (it.text.startsWith("Nov")) it.copy(l = it.l + 100f, r = it.r + 100f) else it }
        val quiet = Rules.textOverlap(Shot("control", "phone", false, apart, 300f, 673f))
        assertTrue("separated texts must be quiet: $quiet", quiet.isEmpty())
    }
}
