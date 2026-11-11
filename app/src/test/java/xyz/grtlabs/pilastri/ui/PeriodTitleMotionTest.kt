package xyz.grtlabs.pilastri.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.data.LocalArchive
import xyz.grtlabs.pilastri.debug.SampleArchive

/** Wave 6 P1 (M1): on a held drag of Mese's pager the `period-title` moves with `period-page-cur` (same direction, +-1 dp); one title at rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PeriodTitleMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun periodTitleTravelsWithItsPage() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalArchive(ctx).save(SampleArchive.build(LocalDate.now()))
        val archive = LocalArchive(ctx).load()
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        val scope = rememberCoroutineScope()
                        val app = remember { App(ctx, LocalArchive(ctx), scope).also { it.archive = archive } }
                        RouteContent(app, Route.Month(YearMonth.now().minusMonths(1).toString()))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2000)
        repeat(3) { rule.waitForIdle() }
        fun left(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.right } // right edge: the left one is clamped by the clips
        val d = rule.density.density
        val title0 = left("period-title"); val page0 = left("period-page-cur")
        assertEquals("exactly one period-title at rest", 1, title0.size)
        rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].performTouchInput { down(center); moveBy(Offset(-width * 0.04f, 0f)) }
        rule.mainClock.advanceTimeBy(48)
        rule.onAllNodes(hasTestTag("period-viewport"), useUnmergedTree = true)[0].performTouchInput { moveBy(Offset(-width * 0.04f, 0f)) }
        rule.mainClock.advanceTimeBy(48)
        val title1 = left("period-title"); val page1 = left("period-page-cur")
        val dPage = (page1[0] - page0[0]) / d
        val dTitle = (title1[0] - title0[0]) / d
        assertTrue("page moved ($dPage dp)", abs(dPage) > 10f)
        assertTrue("period-title moved $dTitle dp, page $dPage dp", abs(dTitle - dPage) <= 1f)
    }
}
