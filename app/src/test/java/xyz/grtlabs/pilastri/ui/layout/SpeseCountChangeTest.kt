package xyz.grtlabs.pilastri.ui.layout

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.LocalArchive
import xyz.grtlabs.pilastri.data.SpendWindows
import xyz.grtlabs.pilastri.data.spendLedger
import xyz.grtlabs.pilastri.data.windowCount
import xyz.grtlabs.pilastri.debug.SampleArchive
import xyz.grtlabs.pilastri.ui.App
import xyz.grtlabs.pilastri.ui.Fmt
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.screens.SpendingLeft
import xyz.grtlabs.pilastri.ui.screens.SpendingScreen

/**
 * Polish 3 (owner F1), synthetic data only. The number of periods changes while Spese is composed (the capture seam `captureSingleWindow`, a sync that
 * extends the coverage, an account removed): no index outside 0 until count may reach a period computation, in any frame. The crash was the strip's
 * semantics block reading the old settled index against the new count (`SpendWindows.window` with k < 0), so the semantics tree is fetched after every frame.
 */
abstract class SpeseCountChangeBase(private val wide: Boolean) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var app: App

    private fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + n.children.flatMap { texts(it) }

    /** The text of the tagged period title (the tag sits on a row; its text is in a child). */
    private fun title(): String? = rule.onAllNodes(hasTestTag("period-title"), useUnmergedTree = true).fetchSemanticsNodes().flatMap { texts(it) }.firstOrNull()

    /** Frame by frame: every frame builds the semantics tree (where the crash was). */
    private fun frames(n: Int) { rule.mainClock.autoAdvance = false; repeat(n) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle(); title() }; rule.mainClock.autoAdvance = true }

    private fun expectedTitle(a: Archive, single: Boolean): String {
        val l = a.spendLedger(); val t = SpendWindows.anchor(app.today, l.cover)!!
        val count = if (single) 1 else l.windowCount(app.today)
        val k = SpendWindows.indexFor(t, app.spendMonth).coerceAtMost(count - 1)
        val w = SpendWindows.window(t, k, l.cover.from)
        return Fmt.day(w.shownFrom) + " – " + Fmt.day(w.to)
    }

    private fun show(a: Archive) {
        LocalArchive(ctx).save(a)
        val loaded = LocalArchive(ctx).load()
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides false) {
                    val scope = rememberCoroutineScope()
                    app = remember { App(ctx, LocalArchive(ctx), scope).also { it.archive = loaded } }
                    Box(Modifier.width(if (wide) 416.dp else 411.dp).semantics { testTagsAsResourceId = true }) { if (wide) SpendingLeft(app) else SpendingScreen(app) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2000); repeat(3) { rule.waitForIdle() }
    }

    @Test fun seamNToOneToNKeepsTheStripConsistent() {
        val a = SampleArchive.build(LocalDate.now())
        show(a)
        val n = a.spendLedger().windowCount(app.today); assertTrue("the sample has several windows ($n)", n > 1)
        assertEquals(expectedTitle(a, false), title())
        // The oldest window selected, so the settled index is the largest the shrunken strip can be asked about.
        rule.runOnIdle { app.spendMonth = SpendWindows.monthOf(SpendWindows.anchor(app.today, a.spendLedger().cover)!!, n - 1) }
        frames(120); assertEquals(expectedTitle(a, false), title())
        rule.runOnIdle { app.captureSingleWindow = true }
        frames(120); assertEquals("single window: the title is the one window's range", expectedTitle(a, true), title())
        rule.runOnIdle { app.captureSingleWindow = false }
        frames(120); assertEquals(expectedTitle(a, false), title())
    }

    @Test fun growingFromOneWindowWithTheOldestSelectedKeepsTheStripConsistent() {
        // Growth 1 -> N with a selection that only exists in the grown strip (the sample's coverage cannot be extended by one window in a unit test, so the seam grows it).
        val a = SampleArchive.build(LocalDate.now())
        show(a)
        val n = a.spendLedger().windowCount(app.today)
        rule.runOnIdle { app.captureSingleWindow = true; app.spendMonth = SpendWindows.monthOf(SpendWindows.anchor(app.today, a.spendLedger().cover)!!, n - 1) }
        frames(120); assertEquals(expectedTitle(a, true), title())
        rule.runOnIdle { app.captureSingleWindow = false }
        frames(120); assertEquals("grown 1 to $n", expectedTitle(a, false), title())
        rule.runOnIdle { app.captureSingleWindow = true }
        frames(120); assertEquals("shrunk $n to 1", expectedTitle(a, true), title())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class SpeseCountChangeCoverTest : SpeseCountChangeBase(false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class SpeseCountChangeFoldTest : SpeseCountChangeBase(true)
