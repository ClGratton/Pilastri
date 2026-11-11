package xyz.grtlabs.pilastri.ui.layout

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.data.LocalArchive
import xyz.grtlabs.pilastri.debug.SampleArchive
import xyz.grtlabs.pilastri.ui.App
import xyz.grtlabs.pilastri.ui.AppRoot
import xyz.grtlabs.pilastri.ui.LocalInPane
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.Route
import xyz.grtlabs.pilastri.ui.RouteContent

/**
 * Wave 2 rules 3 and 4 (tools/critique-rules.md CR-67, CR-68), measured on a HELD drag: finger down + move, no release, on the pager of the
 * Pilastri hero, Mese and the Spese month pager (`period-viewport`). Rest snapshot: neighbour peek = gutter - gap (8 dp, K2 wave 6; the G-11 narrowing is removed). Moving snapshot:
 * every `period-label-*` moves by the same dx as its `period-page-*` (+-1 dp); a moving snapshot identical to rest fails.
 * Writes `build/layout-consistency/<layout>-motion.md`. Run: `bash tools/gw.sh :app:testDebugUnitTest --tests "*PeriodMotionTest*"`.
 */
@RunWith(RobolectricTestRunner::class)
abstract class PeriodMotionTest(private val layout: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private sealed interface Scn { val name: String }
    private class Top(val label: String, val mid: Boolean = false) : Scn { override val name get() = "top-$label" + if (mid) "-mid" else "" }
    private class Detail(override val name: String, val route: () -> Route) : Scn

    @Test fun heldDragMovesLabelsWithTheirPages() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalArchive(ctx).save(SampleArchive.build(LocalDate.now()))
        val archive = LocalArchive(ctx).load()
        // F2b: the "-mid" scenarios step to the PREVIOUS month first, so the strip shows a MIDDLE month with both neighbours (rest peek on both sides, held drag moves both labels with their pages, no label overlap).
        val screens = listOf<Scn>(Top("pilastri"), Detail("mese") { Route.Month(YearMonth.now().toString()) }, Top("spese"),
            Top("pilastri", true), Detail("mese-mid") { Route.Month(YearMonth.now().minusMonths(1).toString()) }, Top("spese", true))
        var scn by mutableStateOf<Scn>(screens[0])
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        key(scn.name) {
                            when (val s = scn) {
                                is Top -> AppRoot()
                                is Detail -> {
                                    val scope = rememberCoroutineScope()
                                    val app = remember { App(ctx, LocalArchive(ctx), scope).also { it.archive = archive; it.twoPane = layout == "fold" } }
                                    CompositionLocalProvider(LocalInPane provides (layout == "fold")) { RouteContent(app, s.route()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val dev = ArrayList<Dev>()
        for (s in screens) {
            val name = "$layout-${s.name}"
            scn = s
            rule.waitForIdle()
            if (s is Top && s.label != "pilastri") runCatching {
                val text = s.label.replaceFirstChar { it.uppercase() }
                val all = rule.onAllNodes(hasClickAction() and (hasContentDescription(text) or hasText(text)))
                val loose = rule.onAllNodes(hasClickAction() and (hasContentDescription(text, substring = true) or hasText(text, substring = true)))
                (if (all.fetchSemanticsNodes().isNotEmpty()) all else loose)[0].performClick()
            }
            rule.mainClock.advanceTimeBy(2000)
            repeat(3) { rule.waitForIdle() }
            if (s is Top && s.mid && s.label != "spese") {
                // App.load() reads the archive on Dispatchers.IO, which the Compose test clock does not track: under a loaded full-suite run the strip can still be empty after waitForIdle.
                // Wait (real time, bounded) for the loaded strip to show its previous-month label; the assertion below stays unchanged.
                runCatching { rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("period-label-prev"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                rule.mainClock.advanceTimeBy(500)
                repeat(3) { rule.waitForIdle() }
            }
            if (s is Top && s.mid) {
                val prev = rule.onAllNodes(hasTestTag("period-label-prev"), useUnmergedTree = true)
                if (prev.fetchSemanticsNodes().isEmpty()) {
                    // Spese shows a single window in the harness archive (no neighbour label): nothing to step to there (open point in F2b.md); elsewhere a missing label is a deviation.
                    if (s.label != "spese") dev += Dev(name, "motion: no previous-month label to step to a middle month (F2b)", "period-label-prev", 0f, 1f)
                    continue
                }
                prev[0].performClick()
                rule.mainClock.advanceTimeBy(2000)
                repeat(3) { rule.waitForIdle() }
            }
            val d = rule.density
            val w = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.let { it.width / d.density }
            val h = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.let { it.height / d.density }
            val rest = collectNodes(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), d)
            dev += Rules.peek(Shot(name, layout, s is Top, rest, w, h))
            dev += Rules.textOverlap(Shot(name, layout, s is Top, rest, w, h))
            if (s.name.endsWith("-mid") && listOf("period-page-prev", "period-page-next").any { id -> rest.none { it.id == id } }) dev += Dev(name, "motion: a middle month must show both neighbour pages (F2b)", "period-page-prev/next", 0f, 1f)
            val vp = rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)
            if (vp.fetchSemanticsNodes().isEmpty()) { dev += Dev(name, "motion: screen has no period-viewport to drag (CR-68)", "period-viewport", 0f, 1f); continue }
            // The period title is measured after a short first segment (40 dp: past the touch slop, shorter than the title); the rest of the first 12% follows.
            vp[0].performTouchInput {
                down(center)
                moveBy(Offset(-40f * density, 0f))
            }
            rule.mainClock.advanceTimeBy(48)
            val short = collectNodes(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), d)
            rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].performTouchInput { moveBy(Offset(-(width * 0.12f - 40f * density), 0f)) }
            rule.mainClock.advanceTimeBy(48)
            rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].performTouchInput { moveBy(Offset(-width * 0.12f, 0f)) }
            rule.mainClock.advanceTimeBy(48)
            val moving = collectNodes(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), d)
            File("build/layout-consistency").apply { mkdirs() }.resolve("$name-period-nodes.txt").writeText(listOf("REST" to rest, "MOVING" to moving).joinToString("\n") { (k, l) -> k + "\n" + l.filter { it.id.startsWith("period-") }.joinToString("\n") { "  " + it.id + " l=" + r1(it.l) + " t=" + r1(it.t) + " r=" + r1(it.r) + " b=" + r1(it.b) + " " + it.text.take(16) } })
            dev += Rules.pair(name, layout, rest, moving, short)
            rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].performTouchInput { up() }
            rule.mainClock.advanceTimeBy(1000)
        }
        File("build/layout-consistency").apply { mkdirs() }.resolve("$layout-motion.md").writeText(
            "| screen | rule | element | measured | expected |\n|---|---|---|---|---|\n" + dev.joinToString("\n") { it.row() })
        if (dev.isNotEmpty()) fail("${dev.size} deviation(s) in the held-drag motion rules at $layout\n| screen | rule | element | measured | expected |\n" + dev.joinToString("\n") { it.row() })
    }
}

@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class FoldPeriodMotionTest : PeriodMotionTest("fold")

@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PhonePeriodMotionTest : PeriodMotionTest("phone")
