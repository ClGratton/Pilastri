package xyz.grtlabs.pilastri.ai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme

/* AG-ERROR (Polish 3, 2026-11-10): the six causes, the one row, the one button, the automatic re-send. Synthetic text only. */
class AgentErrorTest {
    private val labels = WebAccount.entries.map { it.label }
    private fun infos(s: AssistantState) = s.entries.filterIsInstance<InfoEntry>()
    private fun pending(q: String = "Quanto ho speso?") = PendingSend(q, null, null)
    private fun asked(s: AssistantState, q: String = "Quanto ho speso?") { s.entries.add(UserEntry(q)) }

    @Test fun theSixCausesHaveTheirLineAndButton() {
        val want = listOf(
            Triple(SendCause.NO_NETWORK, "Nessuna connessione.", "Riprova"),
            Triple(SendCause.SESSION_ENDED, "Accesso a Claude scaduto.", "Accedi di nuovo"),
            Triple(SendCause.VERIFICATION, "Claude chiede una verifica.", "Verifica"),
            Triple(SendCause.PROVIDER_LIMIT, "Limite di messaggi di Claude raggiunto.", "Riprova"),
            Triple(SendCause.NO_ANSWER, "Claude non ha risposto.", "Riprova"),
            Triple(SendCause.NOT_SENT, "Messaggio non inviato.", "Riprova"),
        )
        assertEquals(SendCause.entries.size, want.size)
        for ((c, line, button) in want) { assertEquals(line, c.line("Claude")); assertEquals(button, c.button) }
    }

    @Test fun everyLineFitsInAbout45Characters() {
        for (c in SendCause.entries) for (l in labels) assertTrue("$c / $l: ${c.line(l)}", c.line(l).length <= 45)
    }

    @Test fun causesComeFromTransportStateOnly() {
        val none = TransportState()
        assertEquals(SendCause.NOT_SENT, SendCause.classify(none))
        assertEquals(SendCause.NO_NETWORK, SendCause.classify(none.copy(offline = true)))
        assertEquals(SendCause.SESSION_ENDED, SendCause.classify(none.copy(signInPath = true)))
        assertEquals(SendCause.SESSION_ENDED, SendCause.classify(none.copy(http = 403)))
        assertEquals(SendCause.VERIFICATION, SendCause.classify(none.copy(challenge = true)))
        assertEquals(SendCause.PROVIDER_LIMIT, SendCause.classify(none.copy(http = 429)))
        assertEquals(SendCause.NO_ANSWER, SendCause.classify(none.copy(http = 503)))
        assertEquals(SendCause.NO_ANSWER, SendCause.classify(none.copy(timedOut = true)))
        assertEquals(SendCause.NOT_SENT, SendCause.classify(none.copy(http = 404)))
    }

    @Test fun theQuestionStaysInTheThreadAndTheComposerStaysEmpty() {
        for (c in SendCause.entries) {
            val s = AssistantState(); asked(s)
            s.onUndelivered(pending(), c, "Claude", "why")
            assertEquals("", s.draft)
            assertEquals(listOf("Quanto ho speso?"), s.entries.filterIsInstance<UserEntry>().map { it.text })
            val row = infos(s).single()
            assertEquals(c.line("Claude"), row.text)
            assertEquals(listOf(InfoAction.FIX), row.actions)
            assertEquals(c, row.cause)
            assertFalse("the technical reason is not on screen", row.text.contains("why"))
            assertFalse(s.showPage)
        }
    }

    @Test fun twoFailuresNeverStackAndTheLastCauseWins() {
        val s = AssistantState(); asked(s)
        s.onUndelivered(pending(), SendCause.NO_NETWORK, "Claude", "a")
        s.onUndelivered(pending(), SendCause.NOT_SENT, "Claude", "b")
        assertEquals(1, infos(s).size)
        assertEquals(SendCause.NOT_SENT, infos(s)[0].cause)
        assertEquals(1, s.entries.filterIsInstance<UserEntry>().size)
    }

    @Test fun theButtonTakesTheRowAwayAndHandsBackTheSameQuestionOnce() {
        val s = AssistantState(); asked(s)
        val p = pending()
        s.onUndelivered(p, SendCause.NO_ANSWER, "Claude", "x")
        assertTrue(s.takePending() === p)
        assertTrue("the row is gone: the waiting state is the normal one", infos(s).isEmpty())
        assertEquals(1, s.entries.filterIsInstance<UserEntry>().size)
        assertNull("a second tap sends nothing", s.takePending())
    }

    @Test fun verificationClosesByItselfWithoutALineAndAsksForTheResend() {
        val s = AssistantState(); asked(s)
        s.onUndelivered(pending(), SendCause.VERIFICATION, "Claude", "x")
        s.showPage = true
        assertFalse(s.onChallenge(true))
        assertTrue(s.onChallenge(false))
        assertFalse(s.showPage)
        assertFalse("no 'Verifica completata' line", infos(s).any { it.text.contains("completata") })
        assertNotNull(s.takePending())
    }

    @Test fun anOpenPageWithoutAChallengeStaysAsItWas() {
        val s = AssistantState()
        s.showPage = true
        assertFalse(s.onChallenge(false)); assertTrue(s.showPage)
    }

    private fun run(answers: List<WebAsk>, confirmedSend: Boolean = false): Triple<WebAsk, List<Boolean>, Int> {
        val reads = mutableListOf<Boolean>(); var reloads = 0
        val r = runBlocking {
            deliverWithRetry(ask = { ro -> reads.add(ro); answers[(reads.size - 1).coerceAtMost(answers.size - 1)] }, canRead = { confirmedSend }, reload = { reloads++ }, pause = { })
        }
        return Triple(r, reads, reloads)
    }

    @Test fun oneAutomaticRetryWithAReloadWhenNothingWasSent() {
        val (r, reads, reloads) = run(listOf(WebAsk.NotDriven("composer", SendCause.NOT_SENT, false), WebAsk.Reply("ok", false)))
        assertTrue(r is WebAsk.Reply); assertEquals(listOf(false, false), reads); assertEquals(1, reloads)
    }

    @Test fun twoFailuresEndInTheRowOfTheLastCause() {
        val (r, reads, _) = run(listOf(WebAsk.NotDriven("a", SendCause.NO_NETWORK, false), WebAsk.NotDriven("b", SendCause.NOT_SENT, false)))
        assertEquals(2, reads.size); assertEquals(SendCause.NOT_SENT, r.cause)
    }

    @Test fun aConfirmedSendIsNeverSentTwice() {
        val (r, reads, reloads) = run(listOf(WebAsk.Failed("net", SendCause.NO_NETWORK, true), WebAsk.Reply("ok", false)), confirmedSend = true)
        assertTrue(r is WebAsk.Reply)
        assertEquals("the second pass only reads the answer", listOf(false, true), reads)
        assertEquals("no reload while the answer may still arrive", 0, reloads)
    }

    @Test fun causesWhereARetryCannotHelpAreNotRetried() {
        assertEquals(1, run(listOf(WebAsk.NeedsLogin)).second.size)
        assertEquals(1, run(listOf(WebAsk.NeedsUser)).second.size)
        assertEquals(1, run(listOf(WebAsk.Failed("429", SendCause.PROVIDER_LIMIT, true))).second.size)
        assertEquals("a six-minute wait is not repeated", 1, run(listOf(WebAsk.Failed("t", SendCause.NO_ANSWER, true)), confirmedSend = true).second.size)
        assertEquals(2, run(listOf(WebAsk.NotDriven("d", SendCause.NO_ANSWER, false), WebAsk.NotDriven("d", SendCause.NO_ANSWER, false))).second.size)
    }

    @Test fun everyOutcomeMapsToACauseOrNull() {
        assertEquals(SendCause.SESSION_ENDED, WebAsk.NeedsLogin.cause)
        assertEquals(SendCause.VERIFICATION, WebAsk.NeedsUser.cause)
        assertNull(WebAsk.Reply("x", false).cause)
    }

    @Test fun captureSampleShowsTheSessionEndedRow() {
        val s = AssistantState()
        s.prepareForCapture("nodrive")
        assertEquals("", s.draft)
        assertEquals(1, s.entries.filterIsInstance<UserEntry>().size)
        val row = infos(s).single()
        assertEquals(SendCause.SESSION_ENDED, row.cause)
        assertEquals("Accesso a Assistente di esempio scaduto.", row.text)
    }

    @Test fun theHandBackIsGoneFromTheSource() {
        val dir = "src/main/java/xyz/grtlabs/pilastri/ai/"
        for (f in listOf("AssistantPage.kt", "WebChat.kt", "WebPage.kt", "WebReply.kt")) {
            val t = java.io.File(dir + f).readText()
            for (gone in listOf("NOT_SENT_TEXT", "SHOW_PAGE", "Mostra la pagina", "returnQuestion", "AG-NODRIVE", "Controlla la pagina")) assertFalse("$f $gone", t.contains(gone))
        }
        val page = java.io.File(dir + "AssistantPage.kt").readText()
        assertEquals("the sign-in result and the cleared challenge both re-send", 2, Regex("resendFn[(][)]").findAll(page).count())
        assertTrue(page.contains("AG-ERROR"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class AgentErrorLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun check(w: Int) {
        val line = SendCause.SESSION_ENDED.line("ChatGPT")
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                PilastriTheme(false) {
                    Column(Modifier.width(w.dp)) {
                        InfoCard(InfoEntry(line, actions = listOf(InfoAction.FIX), cause = SendCause.SESSION_ENDED), {}, Modifier)
                    }
                }
            }
        }
        rule.waitForIdle()
        val text = rule.onNodeWithText(line, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = rule.onNodeWithText("Accedi di nuovo", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("error text and button label share the start edge at $w dp", text.left, button.left, 1f)
        assertTrue("the line is one line at $w dp", text.height < 40 * rule.activity.resources.displayMetrics.density)
    }

    @Test fun atCoverWidth() = check(379)
    @Test fun atOpenFoldPaneWidth() = check(309)
}
