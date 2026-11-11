package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.setup.ChoiceChip

/**
 * CR-83, layout stability: a state change (selected, checked, pressed, loading) never changes an element's size or moves its
 * siblings. [diff] is the generic comparison (the device add-on `design-method/scripts/addons/state-stability.mjs` does the same
 * over uiautomator dumps); the real shared chip is rendered in every state and must produce no difference. The must-flag
 * fixture is the 2026-11-09 "Studente" reflow: the selected chip grew by its check and pushed its siblings to other rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class StateStabilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    data class R(val l: Float, val t: Float, val r: Float, val b: Float)

    /** Differences between two states of the same group: any control whose bounds changed (size or position). */
    fun diff(before: Map<String, R>, after: Map<String, R>, tol: Float = 0.5f): List<String> =
        before.keys.filter { k -> val a = before[k]!!; val b = after[k] ?: return@filter true
            kotlin.math.abs(a.l - b.l) > tol || kotlin.math.abs(a.t - b.t) > tol || kotlin.math.abs(a.r - b.r) > tol || kotlin.math.abs(a.b - b.b) > tol }

    @Test fun mustFlag_studenteReflow() {
        val before = mapOf("Studente" to R(0f, 0f, 100f, 48f), "Dipendente pubblico" to R(108f, 0f, 300f, 48f), "Nessun reddito" to R(0f, 56f, 120f, 104f))
        val after = mapOf("Studente" to R(0f, 0f, 134f, 48f), "Dipendente pubblico" to R(0f, 56f, 192f, 104f), "Nessun reddito" to R(0f, 112f, 120f, 160f))
        assertEquals(listOf("Studente", "Dipendente pubblico", "Nessun reddito"), diff(before, after))
    }

    @OptIn(ExperimentalLayoutApi::class)
    private fun sharedChips(single: Boolean) {
        val labels = listOf("Studente", "Dipendente privato", "Dipendente pubblico", "Lavoratore in proprio", "Collaboratore", "Pensionato", "Nessun reddito")
        val bounds = HashMap<String, R>()
        var selected by mutableIntStateOf(-1)
        rule.setContent {
            PilastriTheme(false) {
                Box(Modifier.width(330.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEachIndexed { i, l ->
                            ChoiceChip(l, selected == i, { }, single = single,
                                modifier = Modifier.onGloballyPositioned { c -> c.boundsInRoot().let { bounds[l] = R(it.left, it.top, it.right, it.bottom) } })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val base = HashMap(bounds)
        for (i in labels.indices) {
            selected = i
            rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
            val d = diff(base, HashMap(bounds))
            assertTrue("CR-83: selecting '${labels[i]}' changed bounds of $d (single=$single)", d.isEmpty())
        }
    }

    @Test fun sharedChoiceChip_multi_keepsEveryBound() = sharedChips(false)
    @Test fun sharedChoiceChip_single_keepsEveryBound() = sharedChips(true)
}
