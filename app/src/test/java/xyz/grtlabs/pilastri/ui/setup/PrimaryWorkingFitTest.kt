package xyz.grtlabs.pilastri.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.PilastriTheme

/**
 * A primary disabled by running work says what runs and shows a circular loader at its right (user, 2026-11-10: "È il continua che
 * deve diventare accesso in corso con a destra caricamento circolare"). On a 344 dp wide screen at font 1.0 the longest working
 * label shows whole (no ellipsis), and the working state never changes the primary's size or position (CR-83).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w344dp-h800dp")
class PrimaryWorkingFitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun workingLabelsFitOn344dpWithoutEllipsisAndTheButtonDoesNotMove() {
        val labels = EbScreen.values().map { ebWorkingLabel(it) }.distinct() + "Salvataggio"
        var label by mutableStateOf("Continua")
        var working by mutableStateOf(false)
        rule.setContent {
            PilastriTheme(false) {
                Box(Modifier.width(344.dp)) {
                    StepScaffold(1, 5, null, null, "Titolo", null, working,
                        primary = DockAction(label, enabled = false, working = working, onClick = {}), secondary = DockAction("Dopo", onClick = {})) { }
                }
            }
        }
        rule.waitForIdle()
        fun bounds() = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        val idle = bounds()
        for (l in labels) {
            label = l; working = true
            rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
            val results = ArrayList<TextLayoutResult>()
            rule.onNodeWithText(l, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("no layout for '$l'", results.isNotEmpty())
            // Robolectric measures glyphs as 1 px each, so overflow flags are meaningless here; the real layout fact is the width the Text
            // was offered. Need (Roboto Flex 16sp, longest label "Accesso in corso", about 7.6 em): about 122-125 dp.
            val offered = results[0].layoutInput.constraints.maxWidth
            println("PRIMARY-FIT '$l' offered=${offered}dp primary=${bounds()}")
            assertTrue("'$l': the text is offered only ${offered}dp at 344 dp", offered >= 128)
            assertEquals("'$l' is one line", 1, results[0].lineCount)
            rule.onNodeWithTag("progress_primary", useUnmergedTree = true).assertIsDisplayed()
        }
        label = "Continua"; working = false
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        val b = bounds()
        assertEquals("primary moved or resized (width)", idle.width, b.width, 0.5f)
        assertEquals("primary moved or resized (top)", idle.top, b.top, 0.5f)
        assertEquals("primary moved or resized (left)", idle.left, b.left, 0.5f)
    }
}
