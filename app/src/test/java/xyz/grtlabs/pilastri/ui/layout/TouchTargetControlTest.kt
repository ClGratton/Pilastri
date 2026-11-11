package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.pressable

/**
 * Positive control of the CR-23 touch rule (fix wave 3, owner T): a raw 40 dp `clickable` MUST be reported by the same
 * engine rule the layout gate uses (`Rules.check`, family "touch"), and a 48 dp `pressable` box MUST NOT (a 48 dp pressable), at font 1.0 and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class TouchTargetControlTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(fontScale: Float): Shot {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column {
                    Box(Modifier.size(100.dp))   // keeps the fixtures off the window top (the engine skips targets cut by it)
                    Box(Modifier.size(40.dp).clickable { }.semantics { contentDescription = "raw40" })
                    Box(Modifier.size(100.dp))
                    // a pressable of 48 dp (the shared component: touch box never below 48; screens give it heightIn/size >= 48)
                    Box(Modifier.semantics { contentDescription = "ok48" }.pressable(RectangleShape, Color.Black, onClick = { }).size(48.dp))
                }
            }
        }
        rule.waitForIdle()
        val d = rule.density
        val merged = rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
        return Shot("control", "phone", false, emptyList(), 411f, 891f, collectNodes(merged, d, onlyClickable = true))
    }

    private fun touchRows(fs: Float): List<Dev> = Rules.check(listOf(shot(fs))).filter { it.rule.startsWith("touch") }

    private fun control(fs: Float) {
        val rows = touchRows(fs).map { it.el }
        assertTrue("font $fs: the raw 40 dp clickable must be flagged (rows: $rows)", rows.any { it.contains("raw40") })
        assertTrue("font $fs: a 48 dp pressable must not be flagged (rows: $rows)", rows.none { it.contains("ok48") })
    }

    @Test fun rawFortyDpClickableIsReportedAtFont1() = control(1f)
    @Test fun rawFortyDpClickableIsReportedAtFont2() = control(2f)

    @Test fun pressableBoxIs48dp() {
        val t = shot(1f).targets.first { it.desc == "ok48" }
        assertEquals("ok48 width", 48f, t.w, 0.6f)
        assertEquals("ok48 height", 48f, t.h, 0.6f)
    }
}
