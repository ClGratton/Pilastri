package xyz.grtlabs.pilastri.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme

/*
 * Wave 17: the paste page of the Enable Banking sign-in link step. Synthetic data only (a zero oobCode, example addresses). The
 * flow runs through its capture seam, so nothing reaches Enable Banking or a WebView page; the fake clipboard counts every read.
 */

private const val LINK_OK = "https://enablebanking.com/__/auth/action?mode=signIn&oobCode=TEST&apiKey=TEST"
private const val SENT_AT = 1_000_000L

private class FakeClip(var info: ClipInfo? = null, var content: String? = null) : ClipboardSource {
    var describes = 0
    var textReads = 0
    override fun describe(): ClipInfo? { describes++; return info }
    override fun text(): CharSequence? { textReads++; return content }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class EbPasteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val links = mutableListOf<String>()

    private fun start(clip: FakeClip) {
        val m = EbFlowModel(stack = listOf(EbStep(EbScreen.EMAIL), EbStep(EbScreen.LINK)), sentAt = SENT_AT)
        rule.setContent {
            CompositionLocalProvider(LocalClipboardSource provides clip, LocalReduceMotion provides true) {
                PilastriTheme(false) { EbSetupFlow(EbBankAccess(rule.activity), emptyList(), onBack = {}, onFinished = { _, _ -> }, initial = m, onLink = { links += it }) }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun openPaste() { rule.onNodeWithTag(EbTag.PASTE_OPEN).performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }

    @Test fun waitScreenOffersPasteFromTheFirstFrameAndOpeningItReadsNothing() {
        val clip = FakeClip(ClipInfo(true, SENT_AT - 60_000), LINK_OK)
        start(clip)
        rule.onNodeWithTag(EbTag.WAIT).assertExists()
        rule.onNodeWithTag(EbTag.PASTE_OPEN).assertExists().assertIsEnabled()
        val describesBefore = clip.describes
        openPaste()
        rule.onNodeWithTag(EbTag.FIELD).assertExists()
        rule.onNodeWithTag(EbTag.BUTTON).assertExists()
        rule.onNodeWithTag(EbTag.OUTCOME).assertExists()
        assertEquals("opening the paste page must not read the clipboard text", 0, clip.textReads)
        assertEquals("nor even ask it about its clip", describesBefore, clip.describes)
        assertTrue(links.isEmpty())
    }

    private fun pasteAndCheckStable(clip: FakeClip, expect: String) {
        val before = listOf(EbTag.FIELD, EbTag.BUTTON, EbTag.OUTCOME).map { bounds(it) }
        rule.onNodeWithTag(EbTag.BUTTON).performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        val after = listOf(EbTag.FIELD, EbTag.BUTTON, EbTag.OUTCOME).map { bounds(it) }
        assertEquals("showing the outcome moved the field, the button or the line", before, after)
        rule.onNodeWithText(expect, substring = true).assertExists()
        assertEquals("exactly the pressed Incolla reads the text", 1, clip.textReads)
    }

    @Test fun incollaEmptyClipboardSaysWhatToDo() {
        val clip = FakeClip(null, null)
        start(clip); openPaste()
        pasteAndCheckStable(clip, PasteRules.EMPTY)
        assertTrue(links.isEmpty())
    }

    @Test fun incollaWrongTextKeepsItInTheFieldAndSaysSo() {
        val clip = FakeClip(ClipInfo(true, SENT_AT - 60_000), "ciao")
        start(clip); openPaste()
        pasteAndCheckStable(clip, PasteRules.WRONG)
        rule.onNodeWithTag(EbTag.FIELD).assert(hasText("ciao"))
        assertTrue(links.isEmpty())
    }

    @Test fun incollaValidLinkIsShownConfirmedAndTaken() {
        val clip = FakeClip(ClipInfo(true, SENT_AT - 60_000), LINK_OK)
        start(clip); openPaste()
        pasteAndCheckStable(clip, PasteRules.VALID)
        assertEquals(listOf(LINK_OK), links)
    }

    private fun resumeCycle() {
        val sc = rule.activityRule.scenario
        sc.moveToState(Lifecycle.State.STARTED); rule.waitForIdle()
        sc.moveToState(Lifecycle.State.RESUMED); rule.waitForIdle(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
    }

    @Test fun resumeReadsOnlyAClipNewerThanTheRequest() {
        val clip = FakeClip(ClipInfo(true, SENT_AT - 60_000), LINK_OK)   // copied before this e-mail was requested
        start(clip)
        resumeCycle()
        assertEquals("an older clip is never read", 0, clip.textReads)
        assertTrue(links.isEmpty())
        clip.info = ClipInfo(false, SENT_AT + 1)                          // newer but not text
        resumeCycle()
        assertEquals(0, clip.textReads)
        clip.info = ClipInfo(true, SENT_AT + 1_000)                       // newer text: this is the link
        resumeCycle()
        assertTrue(clip.textReads >= 1)
        assertEquals(listOf(LINK_OK), links)
    }

    @Test fun freshLinkRuleIsPure() {
        val clip = FakeClip(ClipInfo(true, 10), LINK_OK)
        assertNull(PasteRules.freshLink(clip, null))
        assertNull(PasteRules.freshLink(clip, 10))
        assertEquals(0, clip.textReads)
        assertEquals(LINK_OK, PasteRules.freshLink(clip, 9))
        assertFalse(PasteRules.classify("   ") is PasteResult.Valid)
    }
}
