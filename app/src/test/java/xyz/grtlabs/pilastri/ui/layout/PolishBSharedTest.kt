package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.StackHost

/** Polish 2026-11-10 (owner B): two shared-component rules. Synthetic content only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PolishBSharedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Task 3: a page covered by another at rest is not in the semantics tree (merged tree = what uiautomator and TalkBack read). */
    @Test fun coveredStackPageLeavesTheSemanticsTree() {
        rule.setContent {
            PilastriTheme(false) { CompositionLocalProvider(LocalReduceMotion provides true) {
                StackHost(listOf("a", "b"), onBack = {}, keyOf = { it }, modifier = Modifier.fillMaxSize()) { p -> Text("pagina-$p") }
            } }
        }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        rule.onAllNodesWithText("pagina-a").assertCountEquals(0)
        rule.onAllNodesWithText("pagina-b").assertCountEquals(1)
    }
}
