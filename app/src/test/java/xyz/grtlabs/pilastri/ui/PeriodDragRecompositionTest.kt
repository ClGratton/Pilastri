package xyz.grtlabs.pilastri.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 6 P2 (R2-M4): a held drag moves the pager by layer translation only. Neither the swipe's page content nor the period labels
 * recompose per drag frame (the offset is read in layout / layer blocks; composition sees only the rounded page), and one page moves
 * with the finger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PeriodDragRecompositionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun heldDragDoesNotRecomposePageContent() {
        var pageComposes = 0
        var labelComposes = 0
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(5, 10)
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        PeriodSwipe(strip, {}, gutter = 16.dp, wide = false) { i ->
                            SideEffect { pageComposes++ }
                            Box(Modifier.fillMaxWidth().height(120.dp).background(Color.Gray))
                        }
                        SideEffect { labelComposes++ }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val before = pageComposes
        fun curRight() = rule.onAllNodes(hasTestTag("period-page-cur"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.right
        val right0 = curRight()
        val vp = rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0]
        vp.performTouchInput { down(center); moveBy(Offset(-width * 0.05f, 0f)) }
        repeat(6) {
            rule.mainClock.advanceTimeBy(16)
            vp.performTouchInput { moveBy(Offset(-width * 0.03f, 0f)) }
        }
        rule.mainClock.advanceTimeBy(16)
        // Total drag ~23% of a page: below the rounding threshold, so composition sees no change at all.
        assertEquals("page content recomposed during a held drag", before, pageComposes)
        assertTrue("composed at least once", before >= 1)
        assertTrue("the page followed the finger (${right0 - curRight()} px)", right0 - curRight() > 20f)
        vp.performTouchInput { up() }
    }
}
