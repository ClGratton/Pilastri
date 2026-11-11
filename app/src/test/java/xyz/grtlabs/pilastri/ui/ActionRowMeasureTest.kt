package xyz.grtlabs.pilastri.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Crash 2026-11-09 (plan:scalapay-sc91b4c2d7): ActionRow(equalWidth) measured its children twice. Wrapping text must lay out without throwing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ActionRowMeasureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun equalWidthWithWrappingTextDoesNotThrow() {
        rule.setContent {
            ActionRow(Modifier.width(300.dp), equalWidth = true) {
                Text("Segna come pagata la rata di novembre")
                Text("Annulla")
            }
        }
        rule.waitForIdle()
    }

    @Test fun equalWidthShortLabelsAndEndDoNotThrow() {
        rule.setContent {
            ActionRow(Modifier.width(300.dp), equalWidth = true, end = true) { Text("Sì"); Text("No"); Text("Forse") }
        }
        rule.waitForIdle()
    }
}
