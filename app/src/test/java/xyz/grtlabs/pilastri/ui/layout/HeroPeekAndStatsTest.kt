package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.data.MonthValueChange
import xyz.grtlabs.pilastri.data.MonthVersamenti
import xyz.grtlabs.pilastri.data.VersamentoKind
import xyz.grtlabs.pilastri.data.VersamentoRow
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.PeriodSwipe
import xyz.grtlabs.pilastri.ui.PeriodLabels
import xyz.grtlabs.pilastri.ui.AccentCard
import xyz.grtlabs.pilastri.ui.CardColumn
import xyz.grtlabs.pilastri.ui.Card
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.rememberPeriodState
import xyz.grtlabs.pilastri.ui.screens.MonthFigures
import java.time.LocalDate
import java.time.YearMonth

/**
 * Polish 2 (owner P), synthetic content only. P1/P4: in the hero month strip the neighbour's drawn surface (`period-page-surface`) is visible for
 * at least the peek (40 dp open Fold, 28 dp cover; user rule CR-01: "si deve vedere ancora più"). P2: the two stat labels of a month page stay on
 * one line at Fold and cover widths, also with a five-digit variation and "tuo · 12 versamenti" (font 1.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class HeroPeekAndStatsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val gutter = 16.dp
    private val change = MonthValueChange(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 31), 1_234_567L, 4)
    private fun versamenti(n: Int) = MonthVersamenti(YearMonth.of(2026, 10), List(n) { VersamentoRow(LocalDate.of(2026, 10, 1 + it), "Pilastro", null, null, 10_000L, VersamentoKind.TUO) })

    /** The hero strip as Home builds it: card column [column] wide, [wide] Fold or cover geometry, the tonal surface on every page. */
    private fun setHero(column: Dp, wide: Boolean, vers: MonthVersamenti = versamenti(12)) {
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(5, 10)
                    Box(Modifier.width(column)) {
                        PeriodSwipe(strip, {}, gutter = gutter, inset = Motion.periodInset(wide), wide = wide, surface = Color(0x1FFFFFFF)) { _ ->
                            MonthFigures(change, vers, true, null, Modifier, null)
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
    }

    private fun peekDp(tag: String): Float {
        val d = rule.density.density
        val vp = rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val s = rule.onAllNodes(hasTestTag("period-page-surface"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("a drawn surface on prev, cur and next", 3, s.size)
        val cur = rule.onAllNodes(hasTestTag("period-page-cur"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        // The neighbour surface that sits left of the current page: visible part = what lies inside the viewport.
        val prev = s.single { it.right <= cur.left + 1f }
        return (minOf(prev.right, vp.right) - maxOf(prev.left, vp.left)) / d
    }

    @Test fun heroNeighbourSurfaceIsVisibleForThePeekOnTheOpenFold() {
        setHero(357.dp, wide = true)
        val w = peekDp("prev")
        assertTrue("fold: the prev month's drawn surface is $w dp wide in the slot (>= 40)", w >= 40f - 1f)
    }

    @Test fun heroNeighbourSurfaceIsVisibleForThePeekOnTheCover() {
        setHero(347.dp, wide = false)
        val w = peekDp("prev")
        assertTrue("cover: the prev month's drawn surface is $w dp wide in the slot (>= 28)", w >= 28f - 1f)
    }

    /**
     * P2: the two stat cells of a month page are split by content, not 50/50: the first ("Variazione del mese · valore") is as wide as its label,
     * the second takes the rest, and both stay inside the page. Robolectric measures text by character count (no real font), so this checks the split
     * and the containment, not pixel wrapping: wrap, clip and overlap of the labels are gated on real emulator dumps (`consistency-check`, clip-edge).
     */
    private fun assertSplitByContent(column: Dp, wide: Boolean, vers: MonthVersamenti) {
        setHero(column, wide, vers)
        val cur = rule.onAllNodes(hasTestTag("period-page-cur"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val labels = rule.onAllNodes(hasTestTag("stat-label"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            .filter { it.width > 0f && it.left >= cur.left && it.right <= cur.right + 0.5f }
        assertEquals("both stat labels of the current page are inside it", 2, labels.size)
        val (first, second) = labels.sortedBy { it.left }
        assertTrue("the first cell is sized by its label (${first.width} px), not half the page: label 28 characters vs 16", first.width > second.width * 1.3f)
        assertTrue("the second cell stays inside the page", second.right <= cur.right - Motion.PeriodSurfacePad.value * rule.density.density + 0.5f)
    }

    @Test fun statCellsAreSplitByContentOnTheOpenFold() = assertSplitByContent(357.dp, true, versamenti(12))
    @Test fun statCellsAreSplitByContentOnTheCover() = assertSplitByContent(347.dp, false, versamenti(12))
    @Test fun statCellsAreSplitByContentWithOneVersamento() = assertSplitByContent(357.dp, true, versamenti(1))

    /** I1 / CR-02: with the tile on, the edge month label's text starts on the figures' left line (page left + PeriodSurfacePad), not on the page edge. */
    private fun assertLabelOnContentEdge(column: Dp, wide: Boolean) {
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(5, 10)
                    Column(Modifier.width(column)) {
                        PeriodLabels(strip, { "Mese $it" }, {}, null, TextStyle(), Color.Black, edgeInset = Motion.PeriodSurfacePad, gutter = gutter, inset = Motion.periodInset(wide), wide = wide)
                        PeriodSwipe(strip, {}, gutter = gutter, inset = Motion.periodInset(wide), wide = wide, surface = Color(0x1FFFFFFF)) { _ ->
                            MonthFigures(change, versamenti(12), true, null, Modifier, null)
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val d = rule.density.density
        val label = rule.onAllNodes(hasTestTag("period-label-prev"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val label2 = rule.onAllNodes(hasTestTag("period-label-next"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val cur = rule.onAllNodes(hasTestTag("period-page-cur"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val fig = rule.onAllNodes(hasTestTag("stat-label"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.left >= cur.left }.minOf { it.left }
        assertEquals("left edge label starts on the figures' left line", fig / d, label.left / d, 1f)
        assertEquals("right edge label ends on the content's right line", (cur.right - Motion.PeriodSurfacePad.value * d) / d, label2.right / d, 1f)
    }

    @Test fun edgeLabelsFollowTheTileContentEdgeOnTheOpenFold() = assertLabelOnContentEdge(357.dp, true)
    @Test fun edgeLabelsFollowTheTileContentEdgeOnTheCover() = assertLabelOnContentEdge(347.dp, false)

    /** I2: a strip whose pages are plain cards (Mese, Spese, Contributions) exposes a drawn `period-page-surface` per page at least the peek wide in its slot. */
    private fun assertCardStripSurface(column: Dp, wide: Boolean, minDp: Float, shellCard: Boolean, accent: Boolean = false) {
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(5, 10)
                    Box(Modifier.width(column)) {
                        PeriodSwipe(strip, {}, gutter = gutter, inset = Motion.periodInset(wide), wide = wide) { i ->
                            if (accent) AccentCard { Text("Pagina $i") } else if (shellCard) CardColumn { Text("Pagina $i") } else Card { Text("Pagina $i") }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val d = rule.density.density
        val vp = rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val cur = rule.onAllNodes(hasTestTag("period-page-cur"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val s = rule.onAllNodes(hasTestTag("period-page-surface"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("a tagged surface on prev, cur and next", 3, s.size)
        val prev = s.single { it.right <= cur.left + 1f }
        val w = (minOf(prev.right, vp.right) - maxOf(prev.left, vp.left)) / d
        assertTrue("the neighbour card surface is $w dp wide in the slot (>= $minDp)", w >= minDp - 1f)
    }

    @Test fun cardStripSurfaceOpenFoldColumn() = assertCardStripSurface(357.dp, true, 40f, true)
    @Test fun cardStripSurfaceCoverColumn() = assertCardStripSurface(347.dp, false, 28f, true)
    @Test fun plainCardStripSurfaceOpenFold() = assertCardStripSurface(357.dp, true, 40f, false)
    @Test fun plainCardStripSurfaceCover() = assertCardStripSurface(347.dp, false, 28f, false)
    @Test fun accentCardStripSurfaceOpenFold() = assertCardStripSurface(357.dp, true, 40f, true, accent = true)
    @Test fun accentCardStripSurfaceCover() = assertCardStripSurface(347.dp, false, 28f, true, accent = true)
}
