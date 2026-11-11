package xyz.grtlabs.pilastri.ai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * AG-REPLY and AG-ERROR (see AgentErrorTest) (Polish 3, 2026-11-10). Synthetic text only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class AssistantReplyRowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Europe/Rome")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun timeFormatter() {
        val now = ms(2026, 11, 10, 18, 0)
        assertEquals("09:05", replyTimeLabel(ms(2026, 11, 10, 9, 5), now, zone))
        assertEquals("23:59", replyTimeLabel(ms(2026, 11, 10, 23, 59), now, zone))
        assertEquals("9 nov · 14:30", replyTimeLabel(ms(2026, 11, 9, 14, 30), now, zone))
        assertNull(replyTimeLabel(null, now, zone))
    }

    /** Two composers one above the other, empty and with a short question, at [widthDp]. */
    private fun composers(widthDp: Int) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                PilastriTheme(false) {
                    Column(Modifier.width(widthDp.dp)) {
                        for ((tag, text) in listOf("empty" to "", "asked" to "Quanto ho speso?")) {
                            Column(Modifier.testTag(tag).fillMaxWidth()) {
                                Composer(text, {}, "contesto: archivio · Claude (account)", enabled = true, canSend = text.isNotEmpty(), onSend = {}, onDictate = {}, attachOpen = false, onAttach = {})
                            }
                        }
                        AiButton("Riprova", onClick = {})
                        Text("Scrivi", Modifier.testTag("one_line_ref"), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun checkComposer(w: Int) {
        val density = rule.activity.resources.displayMetrics.density
        composers(w)
        val hint = rule.onNodeWithText(COMPOSER_HINT, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("hint on one line at $w dp: ${hint.height} vs ${bounds("one_line_ref").height}", hint.height <= bounds("one_line_ref").height * 1.1f)
        assertTrue("the hint is whole at $w dp", hint.width <= (w - 187) * density)
        assertEquals("a short question does not change the composer height at $w dp", bounds("empty").height, bounds("asked").height, 0.5f)
        val field = rule.onNode(androidx.compose.ui.test.hasContentDescription("Domanda per l'agente") and androidx.compose.ui.test.hasText("Quanto ho speso?")).fetchSemanticsNode().boundsInRoot
        val ctxs = rule.onAllNodesWithText(xyz.grtlabs.pilastri.ui.bindSeparators("contesto: archivio · Claude (account)"), useUnmergedTree = true)
        val ctx = ctxs[1].fetchSemanticsNode().boundsInRoot
        assertTrue("text and context line do not intersect at $w dp", field.bottom <= ctx.top + 0.5f)
        assertTrue("context line whole at $w dp", ctx.left >= field.left - 0.5f && ctx.right <= bounds("asked").right + 0.5f)
        for (d in listOf("Allega foto o file", "Detta", "Invia", "Riprova")) {
            val b = rule.onAllNodesWithContentDescription(d)[0].fetchSemanticsNode().boundsInRoot
            assertTrue("$d is at least 48 dp at $w dp: ${b.height / density}", b.height >= 48 * density - 0.5f)
        }
        assertTrue("the text field is at least 48 dp tall at $w dp: ${field.height / density}", field.height >= 48 * density - 0.5f)
    }

    @Test fun composerAtCoverWidth() = checkComposer(379)
    @Test fun composerAtOpenFoldPaneWidth() = checkComposer(309)

    private var copied: String? = null
    private fun show(at: Long?, text: String = "Hai speso **100** €.") {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true, LocalReplyCopy provides { copied = it }) {
                PilastriTheme(false) {
                    Column(Modifier.testTag("reply_host").fillMaxWidth()) {
                        Text(text, Modifier.testTag("reply_text"))
                        ReplyMetaRow(text, at)
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun rowShowsTimeAtTheReplyStartEdgeAndA48dpCopyTarget() {
        show(System.currentTimeMillis())
        rule.onNodeWithTag(ReplyMetaTag.TIME).assertExists()
        assertEquals(bounds("reply_text").left, bounds(ReplyMetaTag.TIME).left, 0.5f)
        val density = rule.activity.resources.displayMetrics.density
        val copy = bounds(ReplyMetaTag.COPY)
        assertTrue(copy.width >= 48 * density - 0.5f && copy.height >= 48 * density - 0.5f)
        val node = rule.onNodeWithTag(ReplyMetaTag.COPY).fetchSemanticsNode()
        assertEquals(listOf("Copia la risposta"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        // the 48 dp target is 48 dp and sits 14 dp beyond the row's end edge: its 20 dp glyph ends on the reply text's end edge
        assertEquals(48 * density, copy.width, 0.5f)
        assertEquals(bounds("reply_host").right + 14 * density, copy.right, 0.5f)
    }

    @Test fun missingTimeShowsNoTimeButKeepsTheCopyButton() {
        show(null)
        rule.onNodeWithTag(ReplyMetaTag.TIME).assertDoesNotExist()
        rule.onNodeWithTag(ReplyMetaTag.COPY).assertExists()
    }

    @Test fun tapCopiesTheReplyTextConfirmsInPlaceAndMovesNothing() {
        show(System.currentTimeMillis(), "Risposta di prova")
        val rowBefore = bounds(ReplyMetaTag.COPY); val timeBefore = bounds(ReplyMetaTag.TIME)
        rule.onNodeWithTag(ReplyMetaTag.COPY).performClick(); rule.waitForIdle()
        assertEquals("Risposta di prova", copied)
        val state = rule.onNodeWithTag(ReplyMetaTag.COPY).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Copiata", state)
        assertEquals(rowBefore, bounds(ReplyMetaTag.COPY)); assertEquals(timeBefore, bounds(ReplyMetaTag.TIME))
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        assertEquals("", rule.onNodeWithTag(ReplyMetaTag.COPY).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(rowBefore, bounds(ReplyMetaTag.COPY))
    }

    @Test fun onlyAssistantRepliesCarryTheRow() {
        val src = java.io.File("src/main/java/xyz/grtlabs/pilastri/ai/AssistantPage.kt").readText().replace("\r\n", "\n")
        assertEquals("exactly one use of the row, in AssistantCard", 1, Regex("ReplyMetaRow[(]markdownLite").findAll(src).count())
        val card = src.substringAfter("private fun AssistantCard(").substringBefore("\n}\n")
        assertTrue(card.contains("ReplyMetaRow("))
        assertTrue("a reply with no text has no row", card.contains("NO_REPLY_TEXT"))
        assertTrue("the row closes the card", card.indexOf("ReplyMetaRow(") > card.indexOf("entry.tainted"))
        for (other in listOf("private fun UserBubble(", "private fun InfoCard(", "private fun ChangeCard(")) {
            val body = src.substringAfter(other, "").substringBefore("\n}\n")
            assertFalse(other, body.contains("ReplyMetaRow"))
        }
    }

    @Test fun theHandoffIsGone() {
        val page = java.io.File("src/main/java/xyz/grtlabs/pilastri/ai/AssistantPage.kt").readText()
        for (gone in listOf("HANDOFF", "Copia la domanda", "Incolla la risposta", "ai_paste", "AiHandoff", "openPaste")) assertFalse(gone, page.contains(gone))
        assertFalse(java.io.File("src/main/java/xyz/grtlabs/pilastri/ai/WebChat.kt").readText().contains("object WebHandoff"))
    }
}

