package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.SyncBar

/** F4: the sync bar is exactly 2 dp high at the card's top edge: it never overlaps the subtitle above, and appearing moves nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class SyncBarTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun check(widthDp: Dp) {
        val on = mutableStateOf(false)
        rule.setContent {
            PilastriTheme(false) {
                Column(Modifier.width(widthDp)) {
                    Text("Dati di esempio", Modifier.testTag("sub"))
                    Box(Modifier.height(100.dp).testTag("card")) { androidx.compose.foundation.layout.Spacer(Modifier.height(100.dp).width(widthDp)); if (on.value) SyncBar() }
                }
            }
        }
        rule.waitForIdle()
        val sub = rule.onNode(hasTestTag("sub"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card0 = rule.onNode(hasTestTag("card"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        on.value = true; rule.waitForIdle()
        val bar = rule.onNode(hasContentDescription("Aggiornamento in corso"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sub1 = rule.onNode(hasTestTag("sub"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card1 = rule.onNode(hasTestTag("card"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the bar (${bar.top}..${bar.bottom}) overlaps the subtitle (bottom ${sub.bottom})", bar.top >= sub.bottom - 0.5f)
        assertEquals("the bar is 2 dp high", 2f * rule.density.density, bar.height, 0.6f)
        assertEquals("appearing moved the subtitle", sub, sub1); assertEquals("appearing moved the card", card0, card1)
    }

    @Test fun coverWidth() = check(347.dp)
    @Test fun openFoldWidth() = check(357.dp)
}
