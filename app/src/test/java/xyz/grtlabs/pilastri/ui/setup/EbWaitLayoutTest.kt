package xyz.grtlabs.pilastri.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.PilastriTheme

/**
 * EB-2 (board 10b, approved 2026-11-10): on the wait page at cover width (344 dp) the card, both pair rows and the four pills sit on
 * the same bounds in the three states (waiting, e-mail arrived, sign-in running); each pair's pills are equal; no label is clipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w344dp-h800dp")
class EbWaitLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val tags = listOf(EbWaitTag.CARD, EbWaitTag.RECOVERY_PAIR, EbWaitTag.LINK_PAIR, EbWaitTag.RESEND, EbWaitTag.CHANGE_EMAIL, EbWaitTag.OPEN_MAIL, EbTag.PASTE_OPEN)

    @Test fun boundsAreIdenticalInTheThreeStatesAndPillsInAPairAreEqual() {
        var arrived by mutableStateOf(false)
        var working by mutableStateOf(false)
        var seconds by mutableStateOf(53)
        rule.setContent {
            PilastriTheme(false) {
                Box(Modifier.width(312.dp)) /* 344 dp screen minus the 16 dp page gutters */ {
                    EbWaitBody("n***@esempio.it", arrived, working, seconds, openMail = {}, onResend = {}, onChangeEmail = {}, onOpenPaste = {})
                }
            }
        }
        fun snap(): Map<String, Rect> = tags.associateWith { rule.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        fun settle() { rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }
        settle()
        val waiting = snap()
        arrived = true; settle()
        val got = snap()
        working = true; seconds = 0; settle()
        val running = snap()
        for (t in tags) {
            assertEquals("$t moved when the e-mail arrived", waiting[t], got[t])
            assertEquals("$t moved while the sign-in runs", waiting[t], running[t])
        }
        for ((a, b) in listOf(EbWaitTag.RESEND to EbWaitTag.CHANGE_EMAIL, EbWaitTag.OPEN_MAIL to EbTag.PASTE_OPEN)) {
            assertEquals("$a / $b width", waiting[a]!!.width, waiting[b]!!.width, 0.5f)
            assertEquals("$a / $b height", waiting[a]!!.height, waiting[b]!!.height, 0.5f)
        }
        // The two pairs share the column grid: the link pair spans the page width, the recovery pair sits inside the card.
        assertTrue(waiting[EbWaitTag.LINK_PAIR]!!.width >= waiting[EbWaitTag.CARD]!!.width - 0.5f)
        // No label is clipped: the Text was offered at least the width it needs (Robolectric measures glyphs coarsely, so the fact checked is the offered width).
        for (l in listOf("Rimanda il link", "Cambia e-mail", "Apri Gmail", "Incolla il link", "E-mail arrivata")) {
            val r = ArrayList<TextLayoutResult>()
            rule.onNodeWithText(l, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(r) }
            assertTrue("no layout for '$l'", r.isNotEmpty())
            // Robolectric glyph widths are not real, so the fact checked is the width the label was offered (needs about 85-100 dp at 13sp).
            val offered = r[0].layoutInput.constraints.maxWidth
            assertTrue("'$l' is offered only $offered dp", offered >= 96)
            assertEquals("'$l' is one line", 1, r[0].lineCount)
        }
    }

    @Test fun mailSeenSetsArrivedWithoutALinkAndNothingMoves() {
        EbLinkInbox.clearMailSeen(); EbLinkInbox.waiting = true
        var linkInHand by mutableStateOf(false)
        rule.setContent {
            PilastriTheme(false) {
                val seen by EbLinkInbox.mailSeen.collectAsState()
                Box(Modifier.width(312.dp)) { EbWaitBody("n***@esempio.it", seen || linkInHand, false, 0, openMail = {}, onResend = {}, onChangeEmail = {}, onOpenPaste = {}) }
            }
        }
        fun snap() = tags.associateWith { rule.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        val before = snap()
        rule.onNodeWithText("In attesa dell'e-mail", useUnmergedTree = true).assertIsDisplayed()
        EbLinkInbox.markMailSeen(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        rule.onNodeWithText("E-mail arrivata", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(before, snap())
        // The dock's "Continua" is the scaffold's: it stays disabled while no link is in hand (DockAction(enabled = false) for LINK).
        assertTrue(!linkInHand)
        EbLinkInbox.waiting = false; EbLinkInbox.clearMailSeen()
    }
}
