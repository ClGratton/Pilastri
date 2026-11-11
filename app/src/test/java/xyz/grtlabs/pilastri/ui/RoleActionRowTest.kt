package xyz.grtlabs.pilastri.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** polish3s: RoleActionRow places confirm rightmost whatever order the slots are written in, and tags both roles itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w344dp-h800dp")
class RoleActionRowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmIsRightmostAndTagged() {
        rule.setContent {
            PilastriTheme(false) {
                Box(Modifier.width(344.dp)) {
                    RoleActionRow("t", spacing = 8.dp, equalWidth = true,
                        confirm = { m -> PillButton("Conferma", {}, m, kind = Btn.PRIMARY) },
                        secondary = { m -> PillButton("Non è mio", {}, m, kind = Btn.OUTLINE) })
                }
            }
        }
        rule.waitForIdle()
        val c = rule.onNodeWithTag("action-confirm-t").fetchSemanticsNode().boundsInRoot
        val s = rule.onNodeWithTag("action-secondary-t").fetchSemanticsNode().boundsInRoot
        assertTrue("confirm $c must be right of secondary $s", c.left >= s.right - 0.5f)
    }
}
