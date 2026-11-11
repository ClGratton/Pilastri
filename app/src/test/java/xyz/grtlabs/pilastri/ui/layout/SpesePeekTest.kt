package xyz.grtlabs.pilastri.ui.layout

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.data.LocalArchive
import xyz.grtlabs.pilastri.debug.SampleArchive
import xyz.grtlabs.pilastri.ui.App
import xyz.grtlabs.pilastri.ui.Card
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.PeriodSwipe
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.rememberPeriodState
import xyz.grtlabs.pilastri.ui.screens.SpendingLeft
import xyz.grtlabs.pilastri.ui.screens.SpendingScreen

/**
 * Polish 2 (owner G), synthetic data only. CR-01 (the user's "Idem in spese quella scorrevole"): on the real Spese strips over the sample archive the
 * previous window is there at rest, its surface showing at least 28 dp (cover) / 40 dp (open Fold) inside the viewport. The sample's PayPal feed once
 * started 30 days ago, which made the covered range start inside the current window and left Spese a single period (no previous page, peek unproven).
 */
abstract class SpesePeekBase(private val wide: Boolean, private val peekDp: Float) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun rects(tag: String): List<Rect> = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    @Test fun everySpeseStripShowsThePreviousWindowAtRest() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalArchive(ctx).save(SampleArchive.build(LocalDate.now()))
        val archive = LocalArchive(ctx).load()
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val scope = rememberCoroutineScope()
                    val app = remember { App(ctx, LocalArchive(ctx), scope).also { it.archive = archive } }
                    Box(Modifier.width(if (wide) 416.dp else 411.dp).semantics { testTagsAsResourceId = true }) {
                        if (wide) SpendingLeft(app) else SpendingScreen(app)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2000); repeat(3) { rule.waitForIdle() }
        val d = rule.density.density
        val viewports = rects("period-viewport")
        assertTrue("Spese draws its three period strips (got ${viewports.size})", viewports.size >= 3)
        assertEquals("a previous page on every strip", viewports.size, rects("period-page-prev").size)
        val cur = rects("period-page-cur"); val surfaces = rects("period-page-surface")
        for (vp in viewports) {
            val c = cur.single { it.center.y in vp.top..vp.bottom && it.center.x in vp.left..vp.right }
            val prev = surfaces.filter { it.center.y in vp.top..vp.bottom && it.right <= c.left + 1f }
            assertTrue("a previous-window surface inside viewport $vp", prev.isNotEmpty())
            val shown = prev.maxOf { (minOf(it.right, vp.right) - maxOf(it.left, vp.left)) / d }
            assertTrue("the previous window shows $shown dp, the rule is $peekDp dp (CR-01)", shown >= peekDp - 0.5f)
            // G-11 range ends: the newest window has no next page, so its surface reaches the content margin (right edge = width - 16 dp), and the previous edge grows by (gutter - margin).
            if (rects("period-page-next").none { it.center.y in vp.top..vp.bottom }) {
                val curSurface = surfaces.filter { it.center.y in vp.top..vp.bottom && it.center.x in c.left..c.right }.maxByOrNull { it.width }!!
                val width = if (wide) 416f else 411f
                assertEquals("newest Spese window reaches the right content margin", width - 16f, curSurface.right / d, 0.75f)
                assertEquals("previous edge = peek + (gutter - margin)", peekDp + (Motion.periodInset(wide).value - 16f), shown, 0.75f)
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class SpesePeekCoverTest : SpesePeekBase(false, 28f)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class SpesePeekFoldTest : SpesePeekBase(true, 40f)

/** A neighbour period without data is drawn at alpha 0, so it exposes no `period-page-surface` at rest; one with data does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class HiddenNeighbourSurfaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun surfaces(hasData: (Int) -> Boolean): List<Rect> {
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val strip = rememberPeriodState(1, 3)
                    Box(Modifier.width(380.dp)) {
                        PeriodSwipe(strip, {}, hasData = hasData, gutter = 16.dp, inset = Motion.periodInset(false)) { i -> Card { Text("periodo $i") } }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        return rule.onAllNodes(hasTestTag("period-page-surface"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
    }

    @Test fun neighbourWithDataExposesItsSurface() = assertEquals(3, surfaces { true }.size)

    @Test fun neighbourWithoutDataExposesNone() {
        val s = surfaces { it != 0 }
        assertEquals("current and next only; the hidden previous page has no surface node", 2, s.size)
    }
}
