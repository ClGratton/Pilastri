package xyz.grtlabs.pilastri.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.sync.eb.EbPanelFailure
import xyz.grtlabs.pilastri.sync.eb.EbPanelResult
import xyz.grtlabs.pilastri.ui.PilastriTheme

/** polish3s: "Dopo" leaves one unit; the failure names its step; the confirming action sits right of its sibling (user, 2026-11-10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w344dp-h800dp")
class EbLaterAndWordingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun approving(vararg banks: String): EbFlowModel {
        var m = EbFlowRules.start(true).copy(chosen = banks.toList())
        m = EbFlowRules.banksChosen(m)
        return m
    }

    @Test fun laterOnABankWithOthersQueuedSkipsThatBankOnly() {
        val m = approving("Isybank", "Trade Republic")
        assertEquals(EbScreen.APPROVE, m.screen)
        val n = EbFlowRules.later(m)!!
        assertEquals(EbScreen.APPROVE, n.screen)
        assertEquals("Trade Republic", n.current)
        assertEquals(listOf("Trade Republic"), n.queue)
        assertTrue("the skipped bank stays reachable as pending", "Isybank" in EbFlowRules.pending(n))
        assertNull(n.failure)
    }

    @Test fun laterOnTheLastBankOrAnotherStepLeavesTheStepNotABank() {
        assertNull(EbFlowRules.later(approving("Isybank")))
        assertNull(EbFlowRules.later(EbFlowModel()))
    }

    @Test fun aFailureOfOursNamesItsStep() {
        val f = EbPanelResult.Failed(EbPanelFailure.INTERNAL)
        assertEquals("Errore di Pilastri nel collegamento dei conti", EbFlowRules.failureOf(f, "Isybank", EbScreen.APPROVE).message)
        assertEquals("Errore di Pilastri nella registrazione", EbFlowRules.failureOf(f, at = EbScreen.REGISTER).message)
        assertEquals("Errore di Pilastri nell'invio dell'e-mail", EbFlowRules.failureOf(f, at = EbScreen.EMAIL).message)
        assertEquals("Errore di Pilastri nell'apertura del link", EbFlowRules.failureOf(f, at = EbScreen.LINK).message)
    }

    @Test fun theConfirmingActionIsRightOfItsSibling() {
        rule.setContent {
            PilastriTheme(false) {
                Box(Modifier.width(344.dp)) {
                    StepScaffold(5, 7, null, null, "Titolo", null, false,
                        primary = DockAction("Riprova", onClick = {}), secondary = DockAction("Apri la pagina", onClick = {})) { }
                }
            }
        }
        rule.waitForIdle()
        val confirm = rule.onNodeWithTag("action-confirm-dock").fetchSemanticsNode().boundsInRoot
        val other = rule.onNodeWithTag("action-secondary-dock").fetchSemanticsNode().boundsInRoot
        assertTrue("confirm $confirm must be right of secondary $other", confirm.left >= other.right - 0.5f)
    }
}
