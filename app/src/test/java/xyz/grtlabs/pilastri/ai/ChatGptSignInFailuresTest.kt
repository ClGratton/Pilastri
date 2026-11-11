package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.ui.setup.ActionOutcome

/** The documented ChatGPT sign-in failures: whose error each one is, and that the log line carries names and lengths only. */
class ChatGptSignInFailuresTest {
    @Test fun refusalsAreOpenAisNotPilastris() {
        assertTrue(ChatGptSignIn.isRefusal(403, "subscription_sharing_user_not_eligible"))
        assertTrue(ChatGptSignIn.isRefusal(400, "chatpass_v2_unsupported"))
        assertTrue(ChatGptSignIn.isRefusal(403, null))
        assertFalse(ChatGptSignIn.isRefusal(400, "invalid_grant"))
    }

    @Test fun logShapeHasNamesAndLengthsOnly() {
        assertEquals("code#4,state#3", ChatGptSignIn.queryShape("code=abcd&state=xyz"))
        assertEquals("other#1", ChatGptSignIn.queryShape("Bad-Name=v"))
        assertEquals("other", ChatGptSignIn.safeName("Access denied: me@x.it"))
        assertEquals("access_denied", ChatGptSignIn.safeName("access_denied"))
    }

    @Test fun rowShowsWhoseErrorAndTheWaitHasItsOwnState() {
        assertTrue(ActionOutcome.ofChatGpt(true, null) is ActionOutcome.Working)
        assertEquals(ActionOutcome.Failed("Accesso a ChatGPT annullato"), ActionOutcome.ofChatGpt(false, ChatGptSignIn.Outcome.Declined("access_denied")))
        val refused = ActionOutcome.ofChatGpt(false, ChatGptSignIn.Outcome.Refused("subscription_sharing_user_not_eligible")) as ActionOutcome.Failed
        assertTrue(refused.reason.startsWith("OpenAI non accetta"))
        assertTrue((ActionOutcome.ofChatGpt(false, ChatGptSignIn.Outcome.Failed("x")) as ActionOutcome.Failed).reason.startsWith("Accesso a ChatGPT non riuscito"))
        assertTrue((ActionOutcome.ofChatGpt(false, ChatGptSignIn.Outcome.Failed("porta locale occupata", ours = true)) as ActionOutcome.Failed).reason.startsWith("Errore di Pilastri"))
        assertEquals(ActionOutcome.Failed("ChatGPT non ha mandato l'esito a Pilastri"), ActionOutcome.ofChatGpt(false, ChatGptSignIn.Outcome.NoReturn))
        assertEquals(ActionOutcome.Idle, ActionOutcome.ofChatGpt(false, null))
    }

    /** K28: a wait lost with the process (its loopback socket dies with it) is a named failure once, never a silent return to the start. */
    @Test fun waitLostWithTheProcessIsANamedFailureOnce() {
        val store = MemoryStore()
        assertFalse(ChatGptSignIn.lostPending(store, runningNow = false))
        ChatGptSignIn.markPending(store, true)
        assertFalse(ChatGptSignIn.lostPending(store, runningNow = true))
        assertTrue(ChatGptSignIn.lostPending(store, runningNow = false))
        assertFalse(ChatGptSignIn.lostPending(store, runningNow = false))
        ChatGptSignIn.markPending(store, true); ChatGptSignIn.markPending(store, false)
        assertFalse(ChatGptSignIn.lostPending(store, runningNow = false))
    }
}
