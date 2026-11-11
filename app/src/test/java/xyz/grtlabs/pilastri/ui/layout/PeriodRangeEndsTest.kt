package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.Card
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.PageSpec
import xyz.grtlabs.pilastri.ui.PeriodState
import xyz.grtlabs.pilastri.ui.PeriodSwipe
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.rememberPeriodState

/**
 * G-11 range ends (user 2026-11-10: "Qua perche cosi stretto?", "va bene modifica schermata"), synthetic content only. On a side with no drawn neighbour
 * the page's edge sits on the content margin (the caller's `gutter`), page width unchanged; with a neighbour on both sides the page is centred as
 * before (CR-01 peek 28 dp cover / 40 dp open Fold); a single page spans margin to margin; the shift is part of the swipe travel.
 * All numbers in dp, read from the real `period-page-surface` nodes of [PeriodSwipe].
 */
abstract class PeriodRangeEndsBase(private val screen: Dp, private val wide: Boolean) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val margin = PageSpec.Gutter
    private val inset get() = Motion.periodInset(wide)
    private val peek get() = Motion.periodPeek(wide).value
    private val shift get() = (inset - margin).value
    private val d get() = rule.density.density
    private val tag get() = if (wide) "fold" else "cover"
    /** The page width at the centred alignment: the viewport (screen) minus the inset each side. */
    private val centredWidth get() = screen.value - 2 * inset.value

    private fun set(start: Int, count: Int, hasData: (Int) -> Boolean = { true }, surface: Color? = null, gutter: Dp = margin): PeriodState {
        var st: PeriodState? = null
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(start, count)
                    st = strip
                    Box(Modifier.width(screen)) {
                        Box(Modifier.padding(horizontal = gutter)) {
                            PeriodSwipe(strip, {}, hasData = hasData, gutter = gutter, inset = inset, wide = wide, surface = surface) { i -> Card { Text("Pagina $i") } }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        return st!!
    }

    private fun move(st: PeriodState, p: Float) {
        rule.runOnIdle { runBlocking { st.position.snapTo(p) } }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    /** The page surfaces in dp (root coordinates), left to right. */
    private fun surfaces(): List<Rect> = rule.onAllNodes(hasTestTag("period-page-surface"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        .map { Rect(it.left / d, it.top / d, it.right / d, it.bottom / d) }.sortedBy { it.left }

    @Test fun newestPeriodReachesTheRightMargin() {
        set(4, 5)
        val s = surfaces(); assertEquals(2, s.size)
        val cur = s[1]; val prev = s[0]
        assertEquals("newest: the current page's right edge is the content right edge", screen.value - margin.value, cur.right, 0.5f)
        assertEquals("page width unchanged", centredWidth, cur.width, 0.5f)
        assertEquals("previous edge visible = peek + (gutter - margin)", peek + shift, prev.right, 0.5f)
        println("RANGE-ENDS $tag newest: cur=${cur.left}..${cur.right} width=${cur.width} prevVisible=${prev.right}")
    }

    @Test fun middlePeriodStaysCentredWithBothPeeks() {
        set(2, 5)
        val s = surfaces(); assertEquals(3, s.size)
        val cur = s[1]
        assertEquals(inset.value, cur.left, 0.5f)
        assertEquals(screen.value - inset.value, cur.right, 0.5f)
        assertEquals("previous visible as before (CR-01)", peek, s[0].right, 0.5f)
        assertEquals("next visible as before (CR-01)", peek, screen.value - s[2].left, 0.5f)
        println("RANGE-ENDS $tag middle: cur=${cur.left}..${cur.right} width=${cur.width} peeks=${s[0].right}/${screen.value - s[2].left}")
    }

    @Test fun oldestPeriodReachesTheLeftMargin() {
        set(0, 5)
        val s = surfaces(); assertEquals(2, s.size)
        val cur = s[0]; val next = s[1]
        assertEquals(margin.value, cur.left, 0.5f)
        assertEquals(centredWidth, cur.width, 0.5f)
        assertEquals("next edge visible = peek + (gutter - margin)", peek + shift, screen.value - next.left, 0.5f)
    }

    @Test fun singlePageSpansMarginToMargin() {
        set(0, 1)
        val s = surfaces(); assertEquals(1, s.size)
        assertEquals(margin.value, s[0].left, 0.5f)
        assertEquals(screen.value - margin.value, s[0].right, 0.5f)
    }

    @Test fun shiftIsContinuousAcrossTheLastSegment() {
        val st = set(4, 5)
        // Page 3's RIGHT edge is tracked (its left edge is clipped by the viewport once it leaves); it is the left-most page once position >= 3.5.
        val right = ArrayList<Float>(); var w3 = 0f; var w4 = 0f
        for (p in listOf(3f, 3.5f, 4f)) {
            move(st, p)
            val s = surfaces()
            right += (if (p == 3f) s[1] else s[0]).right
            if (p == 3f) w3 = s[1].width
            if (p == 4f) w4 = s[1].width
        }
        println("RANGE-ENDS $tag continuity page3 right=$right width(p=3)=$w3 width(p=4)=$w4")
        assertTrue("monotonic: $right", right[0] > right[1] && right[1] > right[2])
        assertEquals("halfway at 3.5", (right[0] + right[2]) / 2f, right[1], 0.5f)
        assertEquals("page width constant across the travel", w3, w4, 0.5f)
    }

    @Test fun neighbourWithoutDataKeepsTheEndRuleWithoutAJump() {
        // Page 3 has no data (hidden at rest): page 2 has a drawn left neighbour only, so it takes the right margin; revealing page 3 interpolates, no jump.
        val st = set(2, 5, hasData = { it != 3 })
        val right = ArrayList<Float>()
        for (p in listOf(2f, 2.5f, 3f)) {
            move(st, p)
            val s = surfaces()
            right += (if (p == 2f) s[1] else s[0]).right
        }
        println("RANGE-ENDS $tag hidden-neighbour page2 right=$right")
        assertEquals("page 2 rests on the right margin", screen.value - margin.value, right[0], 0.5f)
        assertEquals("halfway at 2.5", (right[0] + right[2]) / 2f, right[1], 0.5f)
    }

    @Test fun newestWithAHiddenPreviousStillReachesTheRightMargin() {
        // Polish 3 B: the previous month has no data (hidden at rest), so no neighbour is drawn: the newest page keeps the right margin, never centred between two empty slots.
        set(4, 5, hasData = { it != 3 })
        val cur = surfaces().maxByOrNull { it.right }!!
        assertEquals(screen.value - margin.value, cur.right, 0.5f)
        assertEquals(centredWidth, cur.width, 0.5f)
    }

    @Test fun oldestWithAHiddenNextStillReachesTheLeftMargin() {
        set(0, 5, hasData = { it != 1 })
        val cur = surfaces().minByOrNull { it.left }!!
        assertEquals(margin.value, cur.left, 0.5f)
    }

    @Test fun heroCallerShapeGetsTheRule() {
        set(4, 5, surface = Color(0x1FFFFFFF))
        val s = surfaces(); assertEquals(2, s.size)
        assertEquals(screen.value - margin.value, s[1].right, 0.5f)
    }

    @Test fun speseStripCallerShapeGetsTheRule() {
        // Spese: gutter = PageSpec.gutter(wide), inset = periodInset(wide), plain card pages.
        set(4, 5, gutter = PageSpec.gutter(wide))
        val s = surfaces(); assertEquals(screen.value - PageSpec.gutter(wide).value, s[1].right, 0.5f)
    }

    @Test fun contributionsCallerShapeGetsTheRule() {
        // Versamenti: three pillars, the last one selected.
        set(2, 3, gutter = PageSpec.Gutter)
        val s = surfaces(); assertEquals(screen.value - PageSpec.Gutter.value, s[1].right, 0.5f)
    }

    // ---- Mese hero / Pilastri-home hero / Versamenti: several strips with different geometry share ONE PeriodState; the rule holds on each, in every path ----

    private var selected: androidx.compose.runtime.MutableIntState? = null

    /** Two strips on one state, as on Mese and the Pilastri home: the hero (tonal tile, peek inset) and a strip with no peek (inset = gutter, like MonthSwipe peeking=false). */
    private fun setShared(start: Int, count: Int, hasData: (Int) -> Boolean = { true }): PeriodState {
        var st: PeriodState? = null
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val sel = remember { mutableIntStateOf(start) }
                    selected = sel
                    val strip = rememberPeriodState(sel.intValue, count)
                    st = strip
                    Box(Modifier.width(screen)) {
                        Column(Modifier.padding(horizontal = margin)) {
                            Box(Modifier.testTag("hero")) { PeriodSwipe(strip, { sel.intValue = it }, hasData = hasData, gutter = margin, inset = inset, wide = wide, surface = Color(0x1FFFFFFF)) { i -> Card { Text("Hero $i") } } }
                            Box(Modifier.testTag("flat")) { PeriodSwipe(strip, {}, hasData = hasData, gutter = margin, inset = margin, wide = wide, primary = false) { i -> Card { Text("Flat $i") } } }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        return st!!
    }

    private fun strip(tag: String): List<Rect> = rule.onAllNodes(hasTestTag("period-page-surface") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.boundsInRoot }.map { Rect(it.left / d, it.top / d, it.right / d, it.bottom / d) }.sortedBy { it.left }

    private fun select(i: Int) {
        rule.runOnIdle { selected!!.intValue = i }
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
    }

    private fun assertEndRules(label: String, k: Int, last: Int) {
        val hero = strip("hero"); val cur = hero.first { it.left >= -1f && it.right <= screen.value + 1f && it.width > centredWidth - 1f }
        when (k) {
            last -> assertEquals("$label hero at newest on the right margin", screen.value - margin.value, cur.right, 0.5f)
            0 -> assertEquals("$label hero at oldest on the left margin", margin.value, cur.left, 0.5f)
            else -> { assertEquals("$label hero in the middle is centred (left)", inset.value, cur.left, 0.5f); assertEquals("$label hero in the middle is centred (right)", screen.value - inset.value, cur.right, 0.5f) }
        }
    }

    @Test fun sharedStateHeroKeepsTheRuleAtNewestMiddleOldest() {
        val st = setShared(4, 5)
        assertEndRules("first composition", 4, 4)
        select(2); assertEndRules("middle", 2, 4)
        select(4); assertEndRules("back to newest after a programmatic selection", 4, 4)
        select(0); assertEndRules("oldest", 0, 4)
        select(4); assertEndRules("newest again", 4, 4)
        assertEquals(4, st.settled)
    }

    @Test fun sharedStateKeepsEachStripsOwnGeometry() {
        setShared(4, 5)
        val hero = strip("hero").first { it.right > screen.value - margin.value - 1f && it.width > 100f }
        val flat = strip("flat").first { it.right > screen.value - margin.value - 1f && it.width > 100f }
        assertEquals("hero page right edge", screen.value - margin.value, hero.right, 0.5f)
        assertEquals("peekless strip right edge", screen.value - margin.value, flat.right, 0.5f)
        assertEquals("hero page width unchanged", centredWidth, hero.width, 0.5f)
        assertEquals("peekless strip spans margin to margin", screen.value - 2 * margin.value, flat.width, 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PeriodRangeEndsCoverTest : PeriodRangeEndsBase(411.dp, false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class PeriodRangeEndsFoldTest : PeriodRangeEndsBase(416.dp, true)
