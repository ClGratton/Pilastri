package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.PeriodLabels
import xyz.grtlabs.pilastri.ui.PeriodSwipe
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.rememberPeriodState

/**
 * Polish 3 (owner C7), CR-02 + M1 (CR-68): in a hero-shaped strip (tonal surface on every page, labels with the same edge inset as Home) the labels of the
 * current page sit on the page's lines at rest (prev.left = page.left, next.right = page.right, cur centred on the page) and move by the page's dx while held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PeriodLabelAlignTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val gutter = 16.dp

    private fun set(column: Dp, wide: Boolean, count: Int, start: Int) {
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(count, start)
                    Box(Modifier.width(column)) {
                        Column {
                            PeriodLabels(strip, { "Mese $it" }, {}, null, TextStyle(), Color.Black, edgeInset = Motion.PeriodSurfacePad, gutter = gutter, inset = Motion.periodInset(wide), wide = wide)
                            PeriodSwipe(strip, {}, gutter = gutter, inset = Motion.periodInset(wide), wide = wide, surface = Color(0x1FFFFFFF)) { _ -> Text("figure") }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
    }

    private fun b(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot



    private fun assertHeld(column: Dp, wide: Boolean, start: Int, dxDp: Float) {
        set(column, wide, 5, start)
        val d = rule.density.density
        val p0 = b("period-page-cur"); val l0 = b("period-label-cur")
        rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].performTouchInput { down(center); moveBy(Offset(dxDp * d, 0f)) }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        val p1 = rule.onAllNodes(hasTestTag("period-page-cur"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: return
        val l1 = rule.onAllNodes(hasTestTag("period-label-cur"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: return
        val pdx = (p1.left - p0.left) / d; val ldx = (l1.left - l0.left) / d
        assertEquals("held ($start, $dxDp dp): label-cur moves by the page's dx (page $pdx)", pdx, ldx, 1f)
    }
    @Test fun heldRangeEndCover() = assertHeld(347.dp, false, 4, 40f)
    @Test fun heldCentredCover() = assertHeld(347.dp, false, 2, -80f)
    @Test fun heldRangeEndFold() = assertHeld(357.dp, true, 4, 60f)
}
