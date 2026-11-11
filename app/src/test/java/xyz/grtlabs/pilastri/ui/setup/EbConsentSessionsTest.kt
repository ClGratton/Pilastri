package xyz.grtlabs.pilastri.ui.setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbPanel

/*
 * One authorization per bank at a time: a fake bank access that counts the starts, and the flow's own rules (ConsentSessions,
 * EbSetupRunner.beginConsent, EbFlowCodec). The authorization opens in the person's browser (BankBrowser), so nothing here has a page.
 * Synthetic data only; nothing reaches a bank. Return handling is in BankReturnTest.
 */
class EbConsentSessionsTest {
    private class CountingAccess : BankAccess {
        override val signUpUrl = "https://enablebanking.com/sign-in/"
        override val redirectUrl = BankReturn.ADDRESS
        var starts = 0
        override suspend fun registered() = true
        override suspend fun hasSession(bank: String) = false
        override suspend fun publicKeyPem() = "TEST"
        override suspend fun verifyRegistration(applicationId: String) = true
        override suspend fun banks() = listOf(aspsp)
        override suspend fun startLink(bank: EbAspsp): String { starts++; return "https://banca.esempio.it/consent?n=$starts" }
        override fun isRedirect(url: String) = BankReturn.isReturn(url)
        override suspend fun completeLink(url: String, bank: EbAspsp) = true
        override suspend fun accountCount(bank: String) = 1
    }

    private companion object {
        const val BANK = "Banca Esempio"
        val aspsp = EbAspsp(BANK, "IT", null, listOf("personal"), emptyList(), emptyList(), false)
    }

    private val access = CountingAccess()
    private var sessions = ConsentSessions()
    private fun runner() = EbSetupRunner(EbPanel(FakeEbPage(), { 0L }, { }), access)
    private fun atStep6() = EbFlowRules.accountsLinked(EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK)))

    /** begin as the screen does it: the transform is applied to the model, the address (if any) is returned. */
    private fun begin(m: EbFlowModel, cause: String, s: ConsentSessions = sessions): Pair<String?, EbFlowModel> = runBlocking {
        val (url, t) = runner().beginConsent(BANK, aspsp, s, cause)
        url to t(EbFlowRules.working(m))
    }

    @Test fun theButtonPressedAgainReopensTheSameAddressAndStartsNothing() {
        val (url, m) = begin(atStep6(), "button")
        assertTrue(m.consentOpen)
        assertNull(m.failure)
        val (again, m2) = begin(m, "button")
        assertEquals(1, access.starts)
        assertEquals(url, again)
        assertTrue(m2.consentOpen)
        assertNull("coming back without a return is not a failure", m2.failure)
        assertEquals(EbScreen.CONFIRM, m2.screen)
    }

    @Test fun theEffectRunningAgainStartsNothing() {
        val (url, m) = begin(atStep6(), "effect")
        val (again, _) = begin(m, "effect")
        assertEquals(1, access.starts)
        assertEquals(url, again)
        // a begin still running is not doubled either
        val other = ConsentSessions()
        assertTrue(other.claim(BANK, "effect") is ConsentClaim.Go)
        assertTrue(other.claim(BANK, "button") is ConsentClaim.Busy)
    }

    @Test fun aModelEncodedAndDecodedInTheSameProcessStartsNothing() {
        val (url, m) = begin(atStep6(), "button")
        val back = EbFlowCodec.decode(EbFlowCodec.encode(m))!!
        // the decode alone shows the named failure (no authorization is trusted from stored state) and starts nothing
        assertEquals(EbFailKind.SCA_DENIED, back.failure?.kind)
        assertEquals(1, access.starts)
        // its retry in this process finds the live authorization: same address, no second start
        val (again, _) = begin(back, "restore")
        assertEquals(1, access.starts)
        assertEquals(url, again)
    }

    @Test fun theOutcomeReleasesTheHolderAndAFreshBeginStartsAgain() {
        val (_, m) = begin(atStep6(), "button")
        sessions.finish(BANK, "outcome")
        assertFalse(sessions.inFlight(BANK))
        begin(m, "retry")
        assertEquals(2, access.starts)
        assertNotNull(sessions.liveFor(BANK))
    }

    @Test fun afterProcessDeathTheBankShowsTheNamedFailureAndNothingStartsByItself() {
        val (_, m) = begin(atStep6(), "button")
        val stored = EbFlowCodec.encode(m)
        sessions = ConsentSessions()   // a new process
        val restored = EbFlowCodec.decode(stored)!!
        assertEquals(EbScreen.CONFIRM, restored.screen)
        assertEquals(EbFailKind.SCA_DENIED, restored.failure?.kind)
        assertEquals("Riprova", restored.failure?.kind?.primary)
        assertFalse(restored.consentOpen)
        assertFalse(sessions.inFlight(BANK))
        assertEquals("only the first process started one", 1, access.starts)
        val (url, _) = begin(restored, "retry")
        assertNotNull(url)
        assertEquals(2, access.starts)
    }

    @Test fun anAuthorizationOlderThanItsBoundEnds() {
        var t = 0L
        val s = ConsentSessions(now = { t })
        s.opened(BANK, "https://banca.esempio.it/consent")
        assertTrue(s.inFlight(BANK))
        t += ConsentSessions.MAX_AGE_MS + 1
        assertFalse(s.inFlight(BANK))
    }

    @Test fun resumedWithoutAReturnAfterTheGraceIsANamedFailureWithRiprovaOnTheRight() {
        val (_, m) = begin(atStep6(), "button")
        val n = EbFlowRules.noReturn(m)
        assertFalse(n.consentOpen)
        assertEquals(EbFailKind.NO_RETURN, n.failure?.kind)
        assertEquals("La banca non ha mandato l'esito a Pilastri", n.failure?.message)
        assertEquals("Riprova", EbFailKind.NO_RETURN.primary)
        assertEquals("Dopo", EbFailKind.NO_RETURN.secondary)
        assertEquals(EbFlowRules.noReturn(n), n)   // nothing to fail once the wait ended
    }

    @Test fun aReturnInsideTheGraceCompletesTheStepAndNeverShowsTheFailure() {
        val (_, m) = begin(atStep6(), "button")
        // the return (38 ms after the resume) lands before the grace check: the check finds the wait over and changes nothing
        val done = runner().consentResult(BANK, true, 2)(m)
        assertEquals(done, EbFlowRules.noReturn(done))
        assertNull(done.failure)
        assertEquals(2, done.linked[BANK])
    }

    @Test fun riprovaAfterNoReturnEndsTheOldAuthorizationAndStartsANewOne() {
        val (url1, m) = begin(atStep6(), "button")
        val n = EbFlowRules.noReturn(m)
        sessions.finish(BANK, "restarted")   // what the screen does for Riprova in this state
        val (url2, m2) = begin(n, "retry")
        assertEquals(2, access.starts)
        assertTrue(url1 != url2)
        assertTrue(m2.consentOpen)
        assertNull(m2.failure)
    }
}
