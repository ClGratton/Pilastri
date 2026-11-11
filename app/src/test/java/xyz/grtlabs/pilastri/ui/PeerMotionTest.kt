package xyz.grtlabs.pilastri.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fix wave 2, F2a ("motion cuts"). Each check advances the main clock and asserts an INTERMEDIATE frame, so a swap that
 * happens in one frame (a cut) fails: peers travel and cross, the Agente panel's content is present while it expands,
 * and the left hero's month content fades through instead of switching at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PeerMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val coords = HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>()
    private fun left(tag: String) = coords.getValue(tag).positionInRoot().x

    private fun peers(start: Tab?, to: Tab?, expectSign: Int) {
        var target by mutableStateOf(start)
        rule.setContent {
            AnimatedContent(target, Modifier.fillMaxSize(), transitionSpec = peerTransition(false, ::peerOrder), label = "t") { t ->
                val tag = "page-${t?.name ?: "AGENTE"}"
                Box(Modifier.fillMaxSize().testTag(tag).onGloballyPositioned { coords[tag] = it })
            }
        }
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        val old = "page-${start?.name ?: "AGENTE"}"; val new = "page-${to?.name ?: "AGENTE"}"
        rule.runOnIdle { target = to; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(50)
        // Both pages are in the tree and the arriving one is somewhere between its start offset and rest.
        rule.onNodeWithTag(old).assertExists()
        rule.onNodeWithTag(new).assertExists()
        val mid = left(new)
        assertTrue("the arriving peer is mid-travel at 50 ms (left=$mid)", mid * expectSign > 0f)
        assertTrue("it has not arrived yet", mid * expectSign < rule.onNodeWithTag(new).fetchSemanticsNode().boundsInRoot.width / 10f)
        rule.mainClock.advanceTimeBy(3000)
        rule.onNodeWithTag(old).assertDoesNotExist()
        assertEquals(0f, left(new), 0.5f)
    }

    @Test fun coverPilastriToAgenteTravelsAndCrossesInsteadOfSwapping() = peers(Tab.PILASTRI, null, +1)
    @Test fun coverAgenteToFuturoTravelsTheOtherWay() = peers(null, Tab.FUTURO, -1)
    @Test fun coverSpeseToFuturoTravelsForward() = peers(Tab.SPESE, Tab.FUTURO, +1)
    @Test fun coverFuturoToPilastriTravelsBack() = peers(Tab.FUTURO, Tab.PILASTRI, -1)

    @Test fun everyPeerPairUsesTheDockOrder() {
        val order = listOf<Tab?>(Tab.PILASTRI, Tab.SPESE, Tab.FUTURO, null).map(::peerOrder)
        assertEquals(order.sorted(), order)
        assertEquals(4, order.toSet().size)
    }

    @Test fun agentContentIsPresentFromTheFirstFramesOfTheMorphAndFullBeforeItEnds() {
        assertTrue(agentContentAlpha(0.05f) > 0f)
        assertTrue(agentContentAlpha(0.1f) in 0.05f..0.95f)
        assertEquals(1f, agentContentAlpha(0.4f), 0f)
        assertEquals(1f, agentContentAlpha(1f), 0f)
        assertEquals(0f, agentContentAlpha(0f), 0f)
        var last = -1f
        for (i in 0..20) { val a = agentContentAlpha(i / 20f); assertTrue(a >= last); last = a }
        // The veil over the pane beneath is already complete when the content is.
        assertTrue(agentContentAlpha(Motion.AgentVeilUntil) <= 1f && Motion.AgentVeilUntil < 0.4f)
    }

    @Test fun heroMonthContentFadesThroughWithoutSlidingWhenTheRightPaneChangesTheMonth() {
        var sel by mutableStateOf(2)
        var strip: PeriodState? = null
        rule.setContent { strip = rememberPeriodState(sel, 5, fadeOutside = true) }
        val fade = strip!!.fade
        rule.waitForIdle(); rule.mainClock.autoAdvance = false
        rule.runOnIdle { sel = 3; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        val fades = ArrayList<Float>(); val positions = ArrayList<Float>()
        repeat(240) { rule.mainClock.advanceTimeBy(4); fades += fade.value; positions += strip!!.position.value }
        assertTrue("an intermediate frame exists (a fade, not a cut): $fades pos=$positions", fades.any { it in 0.05f..0.95f })
        assertTrue("it dips before the month changes: ${fades.min()}", fades.min() < 0.1f)
        assertEquals(1f, fades.last(), 0.001f)
        assertEquals(3f, positions.last(), 0f)
        assertEquals(3, strip!!.settled)
        // Stationary: the strip is never drawn between two months, so nothing slides in the left pane.
        assertTrue("positions $positions", positions.all { it == 2f || it == 3f })
        val at = positions.indexOf(3f)
        assertTrue("the month changes only once the old one is faded out (${fades[at]})", fades[at] < 0.1f)
    }
}
