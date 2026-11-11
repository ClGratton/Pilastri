package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.BuildConfig
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbPanel
import xyz.grtlabs.pilastri.sync.eb.EbProductionConfig

/*
 * The bank's return through the site's page and the App Link (wave bank-browser-return, A1). A fake bank access keeps one pending
 * authorization (bank + state) like the encrypted client state does; the handler is the real BankReturnHandler. Synthetic data only.
 */
class BankReturnTest {
    private companion object {
        const val BANK = "Banca Esempio"
        const val STATE = "S-TEST-1"
        val aspsp = EbAspsp(BANK, "IT", null, listOf("personal"), emptyList(), emptyList(), false)
        fun ret(query: String) = BankReturn.ADDRESS + "?" + query
    }

    private class FakeAccess(var pendingState: String? = STATE, var bank: String? = BANK) : BankAccess {
        override val signUpUrl = "https://enablebanking.com/sign-in/"
        override val redirectUrl = BankReturn.ADDRESS
        var starts = 0
        var completes = 0
        override suspend fun registered() = true
        override suspend fun hasSession(bank: String) = false
        override suspend fun publicKeyPem() = "TEST"
        override suspend fun verifyRegistration(applicationId: String) = true
        override suspend fun banks() = listOf(aspsp)
        override suspend fun startLink(bank: EbAspsp): String { starts++; return "https://banca.esempio.it/consent" }
        override fun isRedirect(url: String) = BankReturn.isReturn(url)
        override suspend fun completeLink(url: String, bank: EbAspsp): Boolean { completes++; return !url.contains("error=") }
        override suspend fun accountCount(bank: String) = 2
        private fun stateOf(url: String) = url.substringAfter("state=", "").substringBefore("&")
        override suspend fun matchesPending(url: String) = pendingState != null && BankReturn.isReturn(url) && stateOf(url) == pendingState
        override suspend fun pendingBank() = bank
    }

    private class MemStore : ReturnStore {
        var v: BankReturnResult? = null
        override fun load() = v
        override fun save(r: BankReturnResult?) { v = r }
    }

    private fun atStep6() = EbFlowRules.accountsLinked(EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK)))
    private fun runner(a: BankAccess) = EbSetupRunner(EbPanel(FakeEbPage(), { 0L }, { }), a)

    @Test fun theReturnAddressIsTheSiteAddressFromTheOneConfigValue() {
        assertEquals("https://${BuildConfig.SITE_HOST}/ritorno-banca.html", BankReturn.ADDRESS)
        assertEquals(BankReturn.ADDRESS, EbBankAccess.REDIRECT_URL)
        assertTrue(BuildConfig.SITE_HOST.isNotBlank() && BuildConfig.SITE_HOST.none { it == '/' || it == ':' })
        assertTrue(BankReturn.ADDRESS.startsWith("https://"))
        // sent to the provider at the application's creation
        val p = EbProductionConfig.PILASTRI.payload("PEM", EbBankAccess.REDIRECT_URL)
        assertEquals(BankReturn.ADDRESS, p.getJSONArray("redirect_urls").getString(0))
        assertEquals(1, p.getJSONArray("redirect_urls").length())
    }

    @Test fun aMatchingStateCompletesTheLinkAndPublishesTheOutcome() = runBlocking {
        val a = FakeAccess(); val inbox = BankReturnInbox(MemStore())
        val r = BankReturnHandler(a, inbox).handle(ret("code=CODE-1&state=$STATE"), "applink")
        assertEquals(BankReturnResult(BANK, true, 2), r)
        assertEquals(1, a.completes)
        assertEquals(r, inbox.pending.value)
    }

    @Test fun aMismatchedOrUnknownStateOrAddressIsIgnored() = runBlocking {
        val a = FakeAccess(); val inbox = BankReturnInbox(MemStore()); val h = BankReturnHandler(a, inbox)
        assertNull(h.handle(ret("code=C&state=OTHER"), "applink"))
        assertNull(h.handle(ret("code=C"), "intent"))
        assertNull(h.handle("https://${BuildConfig.SITE_HOST}/altra-pagina.html?code=C&state=$STATE", "applink"))
        assertNull(h.handle("http://${BuildConfig.SITE_HOST}/ritorno-banca.html?code=C&state=$STATE", "applink"))
        a.pendingState = null   // no authorization pending at all
        assertNull(h.handle(ret("code=C&state=$STATE"), "applink"))
        assertEquals(0, a.completes)
        assertNull(inbox.pending.value)
    }

    @Test fun aReturnWithAnErrorIsAFailureOfTheStepNotASuccess() = runBlocking {
        val a = FakeAccess(); val inbox = BankReturnInbox(MemStore())
        val r = BankReturnHandler(a, inbox).handle(ret("error=access_denied&state=$STATE"), "applink")!!
        assertFalse(r.ok)
        val m = runner(a).consentResult(r.bank, r.ok, r.accounts)(atStep6())
        assertEquals(EbFailKind.SCA_DENIED, m.failure?.kind)
        assertEquals(EbScreen.CONFIRM, m.screen)
    }

    @Test fun afterProcessDeathTheStoredStepCompletesWithTheStoredReturn() = runBlocking {
        val a = FakeAccess(); val store = MemStore()
        // process 1: the authorization starts, the model is stored, the process dies in the browser
        val (url, t) = runner(a).beginConsent(BANK, aspsp, ConsentSessions(), "button")
        assertNotNull(url)
        val stored = EbFlowCodec.encode(t(EbFlowRules.working(atStep6())))
        // process 2: the return cold-starts the activity; the handler completes the link before any screen exists
        BankReturnHandler(a, BankReturnInbox(store)).handle(ret("code=C&state=$STATE"), "applink")
        val inbox = BankReturnInbox(store)                       // a new inbox reads what the dead process left
        val r = inbox.pending.value!!
        val restored = EbFlowCodec.decode(stored)!!              // the flow restores on the step with its named failure
        assertEquals(EbScreen.CONFIRM, restored.screen)
        val done = runner(a).consentResult(r.bank, r.ok, r.accounts)(restored)
        assertNull(done.failure)
        assertEquals(EbScreen.LINKED, done.screen)
        assertEquals(mapOf(BANK to 2), done.linked)
        inbox.consume(r)
        assertNull(store.v)
    }

    @Test fun backInPilastriWithoutAReturnKeepsTheStepAndStartsNoSecondAuthorization() = runBlocking {
        val a = FakeAccess(); val sessions = ConsentSessions()
        val (url, t) = runner(a).beginConsent(BANK, aspsp, sessions, "button")
        val m = t(EbFlowRules.working(atStep6()))
        // onResume without a return runs nothing; "Apri la banca" asks for the authorization again and gets the same address
        val (again, t2) = runner(a).beginConsent(BANK, aspsp, sessions, "button")
        val m2 = t2(EbFlowRules.working(m))
        assertEquals(url, again)
        assertEquals(1, a.starts)
        assertEquals(EbScreen.CONFIRM, m2.screen)
        assertNull(m2.failure)
        assertTrue(m2.consentOpen)
        assertEquals(ActionOutcome.Working("In attesa"), ebOutcome(m2.copy(working = false)))
    }

    @Test fun theBankStepsOpenTheBrowserAndNoInAppProviderPageRemains() {
        fun src(p: String) = File("src/main/java/xyz/grtlabs/pilastri/$p").readText()
        assertFalse(src("ui/setup/SetupWeb.kt").contains("WebView"))
        assertTrue(src("ui/setup/EbSetupScreens.kt").contains("BankBrowser.open"))
        assertTrue(src("ui/setup/SetupFlow.kt").contains("BankBrowser.open"))
        assertFalse((src("ui/setup/EbSetupScreens.kt") + src("ui/setup/SetupFlow.kt")).contains("BankConsentPage"))
        // routing names no provider, bank or tracker host, and nothing is cleartext
        val code = src("ui/setup/BankReturn.kt")
        listOf("enablebanking", "adjust.com", "traderepublic", "tilisy", "http://").forEach { assertFalse(it, code.contains(it)) }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:autoVerify=\"true\"") && manifest.contains("android:host=\"\${siteHost}\"") && manifest.contains("android:path=\"/ritorno-banca.html\""))
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
    }
}

/** A linked result that arrives for a bank step restored as failed (process killed at the bank, restore ran after the return) completes the step. */
class BankReturnRestoredFailedTest {
    private val bank = "Banca Alfa"
    private fun failedModel() = SetupRules.consentFailed(
        SetupModel(entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.CONSENSI)), chosenBanks = listOf(bank), links = mapOf(bank to BankLink.IN_CORSO)), bank)

    @org.junit.Test fun linkedResultCompletesAFailedBankStep() {
        val m = failedModel()
        org.junit.Assert.assertEquals(BankLink.NON_RIUSCITA, m.links[bank])
        org.junit.Assert.assertTrue(BankSteps.takesReturn(m.links[bank], linked = true))
        val done = BankSteps.applyComplete(m, bank, true)
        org.junit.Assert.assertEquals(BankLink.COLLEGATA, done.links[bank])
        org.junit.Assert.assertNull(done.failedBank)
    }

    @org.junit.Test fun aFailedResultDoesNotReopenAFailedStepAndInCorsoTakesBoth() {
        org.junit.Assert.assertFalse(BankSteps.takesReturn(BankLink.NON_RIUSCITA, linked = false))
        org.junit.Assert.assertTrue(BankSteps.takesReturn(BankLink.IN_CORSO, linked = false))
        org.junit.Assert.assertFalse(BankSteps.takesReturn(BankLink.COLLEGATA, linked = true))
    }
}
