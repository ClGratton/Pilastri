package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbPage
import xyz.grtlabs.pilastri.sync.eb.EbPanel
import xyz.grtlabs.pilastri.sync.eb.EbPanelFailure
import xyz.grtlabs.pilastri.sync.eb.EbPanelResult
import xyz.grtlabs.pilastri.sync.eb.EbPanelRules
import xyz.grtlabs.pilastri.sync.eb.EbPanelScripts
import xyz.grtlabs.pilastri.sync.eb.EbProductionConfig
import xyz.grtlabs.pilastri.sync.eb.EbSheet

/*
 * Wave 16: the native Enable Banking setup (EB-1 … EB-7) with a fake page bridge. Synthetic data only: nome@esempio.it,
 * "Banca Esempio", a zero app id. Nothing here calls Enable Banking.
 */

private const val EMAIL = "nome@esempio.it"
private const val APP = "00000000-0000-4000-8000-000000000001"
private const val BANK = "Banca Esempio"
private const val LINK = "https://enablebanking.com/__/auth/action?mode=signIn&oobCode=TEST&apiKey=TEST"
private const val BANK_URL = "https://banca.esempio.it/authorize?state=TEST"

/** The page: answers per script (the last answer sticks), the address after each load, and what was shown. */
class FakeEbPage : EbPage {
    val loads = mutableListOf<String>()
    val shows = mutableListOf<EbSheet?>()
    val calls = mutableListOf<Pair<String, JSONObject>>()
    private val answers = mutableMapOf<String, ArrayDeque<JSONObject?>>()
    val navigations = mutableMapOf<String, List<String>>()
    private var urls = ArrayDeque<String>()
    private var current: String? = null

    fun on(script: String, vararg a: JSONObject?) { answers[script] = ArrayDeque(a.toList()) }
    fun states(vararg s: String) = on("STATE", *s.map { JSONObject().put("st", it) }.toTypedArray())

    private fun name(body: String) = when (body) {
        EbPanelScripts.STATE -> "STATE"; EbPanelScripts.SUBMIT_EMAIL -> "SUBMIT_EMAIL"; EbPanelScripts.REGISTER -> "REGISTER"
        EbPanelScripts.ASPSPS -> "ASPSPS"; EbPanelScripts.STATUS -> "STATUS"; EbPanelScripts.LINK -> "LINK"; EbPanelScripts.LEGAL_LINKS -> "LEGAL"
        else -> "?"
    }

    override suspend fun load(url: String) { loads += url; current = url; urls = ArrayDeque(navigations[url].orEmpty()) }
    override suspend fun run(body: String, args: JSONObject): JSONObject? {
        val n = name(body); calls += n to args
        val q = answers[n] ?: return null
        return if (q.size > 1) q.removeFirst() else q.firstOrNull()
    }
    override fun url(): String? = if (urls.isEmpty()) current else if (urls.size > 1) urls.removeFirst() else urls.first()
    override fun show(sheet: EbSheet?, title: String?) { shows += sheet }
}

class FakeEbAccess : BankAccess {
    override val signUpUrl = "https://enablebanking.com/sign-in/"
    override val redirectUrl = "https://localhost/pilastri/eb"
    var keyOk = true; var completeOk = true; var session = false
    var stored: String? = null
    override suspend fun registered() = stored != null
    override suspend fun hasSession(bank: String) = session
    override suspend fun publicKeyPem() = if (keyOk) "-----BEGIN PUBLIC KEY-----\nTEST\n-----END PUBLIC KEY-----" else null
    override suspend fun verifyRegistration(applicationId: String) = true
    override suspend fun banks() = listOf(EbAspsp(BANK, "IT", null, listOf("personal"), emptyList(), emptyList(), false))
    override suspend fun startLink(bank: EbAspsp) = "https://banca.esempio.it/consent"
    override fun isRedirect(url: String) = url.startsWith(redirectUrl)
    override suspend fun completeLink(url: String, bank: EbAspsp) = completeOk
    override suspend fun applicationId() = stored
    override suspend fun storeRegistration(applicationId: String): Boolean { stored = applicationId; return true }
    var accounts: Int? = 2
    override suspend fun accountCount(bank: String) = accounts
}

class EbSetupFlowTest {
    private var t = 0L
    private val page = FakeEbPage()
    private val access = FakeEbAccess()
    private fun panel() = EbPanel(page, { t }, { t += it })
    private fun runner(config: EbProductionConfig = EbProductionConfig.PILASTRI) = EbSetupRunner(panel(), access, config) { 1_000L }
    private fun o(vararg kv: Pair<String, Any?>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    private fun EbFlowModel.step(tr: (EbFlowModel) -> EbFlowModel) = tr(EbFlowRules.working(this))

    private suspend fun signedInAndRegistered(r: EbSetupRunner): EbFlowModel {
        page.states("loading", "form", "sent"); page.on("SUBMIT_EMAIL", o("st" to "submitted"))
        var m = EbFlowRules.start(false).step(r.sendEmail(EMAIL))
        assertEquals(EbScreen.LINK, m.screen)
        page.states("loading", "in")
        m = m.step(r.openLink(LINK, EMAIL))
        assertEquals(EbScreen.REGISTER, m.screen)
        page.on("REGISTER", o("appId" to APP, "active" to false, "reused" to false))
        return m.step(r.register(EMAIL))
    }

    @Test fun theWholeFlowRunsEb1ToEb7() = runBlocking {
        val r = runner()
        var m = signedInAndRegistered(r)
        assertEquals(EbScreen.BANKS, m.screen)
        assertEquals(APP, access.stored)
        assertEquals(setOf(EbCheck.SIGNED_IN, EbCheck.KEY, EbCheck.REGISTERED), m.checks)

        page.on("ASPSPS", o("banks" to JSONArray().put(o("name" to BANK, "psu" to JSONArray().put("personal").put("business")))))
        val banks = r.banks()!!
        assertEquals(listOf(BANK), banks.map { it.name })
        m = EbFlowRules.banksChosen(EbFlowRules.toggle(m, BANK))
        assertEquals(EbStep(EbScreen.APPROVE, BANK), m.top)

        page.on("STATUS", o("active" to false, "linked" to 0), o("active" to true, "linked" to 2))
        page.on("LINK", o("url" to BANK_URL))
        page.navigations[BANK_URL] = listOf(BANK_URL, "https://enablebanking.com/api/auth_redirect?code=TEST", EbPanelRules.PANEL)
        m = m.step(r.linkAccounts(BANK, "personal"))
        assertEquals(EbStep(EbScreen.CONFIRM, BANK), m.top)
        assertTrue(EbCheck.ACTIVATED in m.checks)
        assertEquals(listOf(EbSheet.BANK, null), page.shows)
        val link = page.calls.last { it.first == "LINK" }.second
        assertEquals(EbPanelRules.LINK_REDIRECT, link.getString("redirect"))
        assertEquals(APP, link.getString("appId"))

        val (url, tr) = r.beginConsent(BANK, banks.first())
        assertEquals("https://banca.esempio.it/consent", url)
        m = tr(m)
        m = m.step(r.completeConsent(BANK, "https://localhost/pilastri/eb?code=TEST&state=TEST", banks.first()))
        assertEquals(EbScreen.LINKED, m.screen)
        assertEquals(mapOf(BANK to 2), m.linked)
        assertEquals(emptyList<String>(), EbFlowRules.pending(m))
        assertEquals((1..7).toList(), EbScreen.entries.map { it.number })
    }

    @Test fun registrationSendsTheProductionConfigAndTheEmailOnlyInsideThePage() = runBlocking {
        signedInAndRegistered(runner())
        val args = page.calls.last { it.first == "REGISTER" }.second
        val p = args.getJSONObject("payload")
        assertEquals("Pilastri", p.getString("name"))
        assertEquals("PRODUCTION", p.getString("environment"))
        assertEquals("https://pilastri.grtlabs.xyz/privacy.html", p.getString("privacy_url"))
        assertEquals("https://pilastri.grtlabs.xyz/termini.html", p.getString("terms_url"))
        assertEquals("Pilastri — app personale di finanza, uso privato del titolare dei conti", p.getString("description"))
        assertTrue(p.getString("certificate").startsWith("-----BEGIN PUBLIC KEY-----"))
        assertEquals(listOf("https://localhost/pilastri/eb"), (0 until p.getJSONArray("redirect_urls").length()).map { p.getJSONArray("redirect_urls").getString(it) })
        assertFalse("gdpr_email is added by the page from ARGS.email", p.has("gdpr_email"))
        assertEquals(EMAIL, args.getString("email"))
        assertTrue(EbPanelScripts.REGISTER.contains("p.gdpr_email=ARGS.email"))
        assertTrue(EbProductionConfig.PILASTRI.complete)
    }

    @Test fun aKnownApplicationIsReused() = runBlocking {
        access.stored = APP
        signedInAndRegistered(runner())
        assertEquals(APP, page.calls.last { it.first == "REGISTER" }.second.getString("appId"))
    }

    @Test fun theFormNeverAppearingIsEnableBankingNonRisponde() = runBlocking {
        page.states("loading")
        val m = EbFlowRules.start(false).step(runner().sendEmail(EMAIL))
        assertEquals(EbFailure(EbFailKind.UNREACHABLE), m.failure)
        assertEquals("Enable Banking non risponde", m.failure!!.message)
        assertEquals("Riprova" to "Apri la pagina", m.failure!!.kind.primary to m.failure!!.kind.secondary)
        assertTrue(t >= EbPanel.FORM_TIMEOUT_MS)
    }

    @Test fun aRateLimitWaitsBeforeRiprova() = runBlocking {
        page.states("loading", "form", "rate"); page.on("SUBMIT_EMAIL", o("st" to "submitted"))
        val m = EbFlowRules.start(false).step(runner().sendEmail(EMAIL))
        assertEquals(EbFailKind.RATE_LIMIT, m.failure!!.kind)
        assertEquals("Troppi invii: riprova tra qualche minuto", m.failure!!.message)
        assertFalse(EbFlowRules.canRetry(m, 1_000L + 60_000L))
        assertTrue(EbFlowRules.canRetry(m, 1_000L + EbFlowRules.RATE_WAIT_MS))
    }

    @Test fun aVisibleChallengeShowsThePageAndItsTimeIsNotCounted() = runBlocking {
        page.states(*(listOf("loading") + List(200) { "captcha" } + listOf("form", "sent")).toTypedArray())
        page.on("SUBMIT_EMAIL", o("st" to "submitted"))
        val m = EbFlowRules.start(false).step(runner().sendEmail(EMAIL))
        assertEquals(EbScreen.LINK, m.screen)
        assertEquals(listOf(EbSheet.CHALLENGE, null), page.shows)
        assertTrue("the person's 50 s did not time the form out", t > EbPanel.FORM_TIMEOUT_MS)
    }

    @Test fun anMfaCodeShowsThePage() = runBlocking {
        page.states("loading", "mfa", "mfa", "in")
        val m = EbFlowModel(stack = listOf(EbStep(EbScreen.EMAIL), EbStep(EbScreen.LINK))).step(runner().openLink(LINK, EMAIL))
        assertEquals(EbScreen.REGISTER, m.screen)
        assertEquals(listOf(EbSheet.MFA, null), page.shows)
    }

    @Test fun aLinkThatIsNotTheSignInLinkOrIsExpiredIsIlLinkNonValePiu() = runBlocking {
        val at = EbFlowModel(stack = listOf(EbStep(EbScreen.EMAIL), EbStep(EbScreen.LINK)))
        var m = at.step(runner().openLink("https://enablebanking.com/cp", EMAIL))
        assertEquals(EbFailKind.LINK_INVALID, m.failure!!.kind)
        assertTrue(page.loads.isEmpty())
        page.states("loading", "invalid")
        m = at.step(runner().openLink(LINK, EMAIL))
        assertEquals("Il link non vale più", m.failure!!.message)
        assertEquals("Invia un nuovo link", m.failure!!.kind.primary)
        val again = EbFlowRules.newLink(m)
        assertEquals(EbScreen.EMAIL, again.screen)
        assertNull(again.failure)
    }

    @Test fun firebaseUiAskingToConfirmTheEmailIsFilledByTheApp() = runBlocking {
        page.states("loading", "confirm", "loading", "in"); page.on("SUBMIT_EMAIL", o("st" to "submitted"))
        val m = EbFlowModel(stack = listOf(EbStep(EbScreen.EMAIL), EbStep(EbScreen.LINK))).step(runner().openLink(LINK, EMAIL))
        assertEquals(EbScreen.REGISTER, m.screen)
        assertEquals(EMAIL, page.calls.last { it.first == "SUBMIT_EMAIL" }.second.getString("email"))
    }

    @Test fun aRefusedRegistrationShowsAShortReasonWithoutEmailOrToken() = runBlocking {
        page.states("loading", "form", "sent"); page.on("SUBMIT_EMAIL", o("st" to "submitted"))
        var m = EbFlowRules.start(false).step(runner().sendEmail(EMAIL))
        page.states("loading", "in"); m = m.step(runner().openLink(LINK, EMAIL))
        page.on("REGISTER", o("code" to "refused", "reason" to "Invalid certificate for nome@esempio.it abcdefghijklmnopqrstuvwxyz0123456789ABCD"))
        m = m.step(runner().register(EMAIL))
        assertEquals(EbFailKind.REFUSED, m.failure!!.kind)
        val msg = m.failure!!.message
        assertTrue(msg, msg.startsWith("Enable Banking ha rifiutato la registrazione: Invalid certificate for"))
        assertFalse(msg, msg.contains("@") || msg.contains("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("Riprova" to "Apri la pagina", m.failure!!.kind.primary to m.failure!!.kind.secondary)
        assertNull(access.stored)
    }

    @Test fun anHttpErrorAnswerNamesTheStatusNotUnreachable() = runBlocking {
        page.states("loading", "form", "sent"); page.on("SUBMIT_EMAIL", o("st" to "submitted"))
        var m = EbFlowRules.start(false).step(runner().sendEmail(EMAIL))
        page.states("loading", "in"); m = m.step(runner().openLink(LINK, EMAIL))
        page.on("REGISTER", o("code" to "http", "http" to 500, "ct" to "text/html"))
        m = m.step(runner().register(EMAIL))
        assertEquals(EbFailure(EbFailKind.HTTP_ERROR, status = 500), m.failure)
        assertEquals("Enable Banking ha risposto con un errore (500)", m.failure!!.message)
        assertEquals("Riprova" to "Apri la pagina", m.failure!!.kind.primary to m.failure!!.kind.secondary)
    }

    @Test fun theListRequestTreats404AsEmptyAndRetries429And5xxOnceBeforeAnyCreate() {
        val js = EbPanelScripts.REGISTER
        assertTrue(js.contains("r.status===404) return {list:[]"))
        assertTrue(js.contains("(r.status===429||r.status>=500)&&n++<1"))
        assertTrue("the create request is reached only through apps()", js.indexOf("var l=await apps(); if(!l.list) return l;") < js.indexOf("method:'POST'"))
    }

    @Test fun aSignedInStateOnAnotherPathIsStillLoadingAndTheScriptRunsOnlyOnThePanel() = runBlocking {
        // the sign-in page of the previous document still answers "in" while the panel is loading (Fold 16:36)
        page.on("STATE", o("st" to "in", "p" to "/sign-in/"), o("st" to "in", "p" to "/sign-in/"), o("st" to "in", "p" to "/cp/applications"))
        page.on("REGISTER", o("appId" to APP, "active" to false, "reused" to false))
        val r = panel().register(null, "CERT", "https://localhost/pilastri/eb", EMAIL, EbProductionConfig.PILASTRI)
        assertTrue(r is EbPanelResult.Ok)
        assertEquals(1, page.calls.count { it.first == "REGISTER" })
        // every STATE read before the script was answered on /sign-in/ at most twice: the script came after the third (panel) read
        assertEquals(3, page.calls.take(page.calls.indexOfFirst { it.first == "REGISTER" }).count { it.first == "STATE" })
    }

    @Test fun aLostScriptResultRunsOnceMoreAndTwiceIsOursNotTheProviders() = runBlocking {
        page.states("in")
        page.on("REGISTER", o("code" to "gone"), o("appId" to APP, "active" to false, "reused" to false))
        val ok = panel().register(null, "CERT", "https://localhost/pilastri/eb", EMAIL, EbProductionConfig.PILASTRI)
        assertTrue(ok is EbPanelResult.Ok)
        assertEquals(2, page.calls.count { it.first == "REGISTER" })
        page.calls.clear()
        page.on("REGISTER", o("code" to "gone"))
        val lost = panel().register(null, "CERT", "https://localhost/pilastri/eb", EMAIL, EbProductionConfig.PILASTRI)
        assertEquals(EbPanelFailure.INTERNAL, (lost as EbPanelResult.Failed).failure)
        assertEquals(2, page.calls.count { it.first == "REGISTER" })
        assertEquals(EbFailKind.INTERNAL, EbFlowModel().step(runner().register(EMAIL)).failure!!.kind)
    }

    @Test fun aLostSessionAsksForANewLinkAndResumesTheBank() = runBlocking {
        access.stored = APP
        page.states("loading", "form")
        var m = EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK))
        m = m.step(runner().linkAccounts(BANK, "personal"))
        assertEquals(EbFailure(EbFailKind.EXPIRED), m.failure)
        assertEquals("Accesso a Enable Banking scaduto", m.failure!!.message)
        m = EbFlowRules.newLink(m)
        assertEquals(EbStep(EbScreen.EMAIL, round = 1), m.top)
        m = EbFlowRules.registered(EbFlowRules.signedIn(EbFlowRules.emailSent(m, 0)), active = true)
        assertEquals(EbStep(EbScreen.APPROVE, BANK, 1), m.top)
        // a 401 from the API is the same row
        page.states("in"); page.on("REGISTER", o("code" to "auth"))
        assertEquals(EbFailKind.EXPIRED, EbFlowModel().step(runner().register(EMAIL)).failure!!.kind)
        // the bank list falls back when the session is gone
        page.states("loading", "form")
        assertNull(runner().banks())
    }

    @Test fun aLinkingFailureOrCancelIsBancaNonHaCollegatoIConti() = runBlocking {
        access.stored = APP
        page.states("in")
        page.on("STATUS", o("active" to false, "linked" to 0))
        page.on("LINK", o("url" to BANK_URL))
        page.navigations[BANK_URL] = listOf(BANK_URL, "${EbPanelRules.PANEL}#error=accounts_linking_failure")
        var m = EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK))
        m = m.step(runner().linkAccounts(BANK, "personal"))
        assertEquals(EbFailure(EbFailKind.NOT_LINKED, BANK), m.failure)
        assertEquals("Banca Esempio non ha collegato i conti", m.failure!!.message)
        assertEquals("Riprova" to "Altra banca", m.failure!!.kind.primary to m.failure!!.kind.secondary)
        m = EbFlowRules.chooseAnother(m)
        assertEquals(EbStep(EbScreen.BANKS, round = 1), m.top)
        assertFalse(BANK in m.chosen)
        // link_accounts itself refused: the same row
        page.on("LINK", o("code" to "refused", "reason" to "x"))
        val again = EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK)).step(runner().linkAccounts(BANK, "personal"))
        assertEquals(EbFailure(EbFailKind.NOT_LINKED, BANK), again.failure)
    }

    @Test fun activeWithoutNewAccountsIsNessunContoCollegato() = runBlocking {
        access.stored = APP
        page.states("in")
        page.on("STATUS", o("active" to false, "linked" to 0), o("active" to true, "linked" to 0))   // linked == 0 after a clean return
        page.on("LINK", o("url" to BANK_URL))
        page.navigations[BANK_URL] = listOf(BANK_URL, EbPanelRules.PANEL)
        val m = EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK)).step(runner().linkAccounts(BANK, "personal"))
        assertEquals(EbFailure(EbFailKind.NO_ACCOUNTS, BANK), m.failure)
        assertEquals("Nessun conto collegato da Banca Esempio", m.failure!!.message)
        assertEquals("Riprova", m.failure!!.kind.primary)
    }

    @Test fun aFailedSessionScaLetsTheBankWait() = runBlocking {
        access.completeOk = false
        var m = EbFlowRules.accountsLinked(EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK)))
        assertEquals(EbStep(EbScreen.CONFIRM, BANK), m.top)
        m = m.step(runner().completeConsent(BANK, "https://localhost/pilastri/eb?error=access_denied", null))
        assertEquals(EbFailure(EbFailKind.SCA_DENIED, BANK), m.failure)
        assertEquals("Banca Esempio: accesso ai movimenti non autorizzato", m.failure!!.message)
        assertEquals("Riprova" to "Dopo", m.failure!!.kind.primary to m.failure!!.kind.secondary)
        m = EbFlowRules.skipBank(m)
        assertEquals(EbScreen.LINKED, m.screen)
        assertEquals(listOf(BANK), EbFlowRules.pending(m))
    }

    @Test fun anAlreadyStoredSessionCountsAsLinked() = runBlocking {
        access.session = true
        val m = EbFlowRules.accountsLinked(EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK)))
        val (url, tr) = runner().beginConsent(BANK, null)
        assertNull(url)
        assertEquals(mapOf(BANK to 2), tr(m).linked)
    }

    @Test fun aMissingKeyAndAnEmptyConfigFailHonestly() = runBlocking {
        access.keyOk = false
        assertEquals(EbFailure(EbFailKind.KEY), EbFlowRules.start(false).step(runner().sendEmail(EMAIL)).failure)
        access.keyOk = true
        val empty = EbProductionConfig("Pilastri", "", "", "")
        assertFalse(empty.complete)
        val m = EbFlowModel().step(runner(empty).register(EMAIL))
        assertEquals("Pagine privacy/termini mancanti", m.failure!!.message)
        assertTrue("nothing was sent", page.calls.none { it.first == "REGISTER" })
    }

    @Test fun anotherBankRepeatsEb4ToEb6WithoutANewLink() {
        val m = EbFlowModel(stack = listOf(EbStep(EbScreen.BANKS), EbStep(EbScreen.LINKED)), linked = mapOf(BANK to 2), asked = listOf(BANK))
        val again = EbFlowRules.addAnother(m)
        assertEquals(EbStep(EbScreen.BANKS, round = 1), again.top)
        assertTrue(again.chosen.isEmpty())
        val next = EbFlowRules.banksChosen(EbFlowRules.toggle(again, "Banca Demo"))
        assertEquals(EbStep(EbScreen.APPROVE, "Banca Demo", 1), next.top)
        assertTrue(next.stack.none { it.screen == EbScreen.EMAIL || it.screen == EbScreen.LINK })
    }

    @Test fun eb2OffersRimandaAfterSixtySeconds() {
        val m = EbFlowRules.emailSent(EbFlowModel(), 0L)
        assertFalse(EbFlowRules.canResend(m, 15_000L))
        assertEquals(45L, EbFlowRules.resendIn(m, 15_000L))
        assertTrue(EbFlowRules.canResend(m, 60_000L))
        assertEquals("n•••@esempio.it", EbPanelRules.maskEmail(EMAIL))
        assertEquals(EbScreen.EMAIL, EbFlowRules.newLink(m).screen)
    }

    @Test fun usageTypeIsPersonalUnlessSelfEmployedAndOffered() {
        assertEquals(listOf("personal"), EbFlowRules.psuOptions(false, listOf("personal", "business")))
        assertEquals(listOf("personal", "business"), EbFlowRules.psuOptions(true, listOf("personal", "business")))
        assertEquals(listOf("personal"), EbFlowRules.psuOptions(true, listOf("personal")))
        assertEquals(listOf("business"), EbFlowRules.psuOptions(false, listOf("business")))
        assertEquals(listOf("personal"), EbFlowRules.psuOptions(false, emptyList()))
    }

    @Test fun theBankReturnCountsOnlyAfterLeavingEnableBanking() {
        assertNull(EbPanelRules.bankReturn(EbPanelRules.PANEL, left = false))
        assertNull(EbPanelRules.bankReturn("https://enablebanking.com/api/auth_redirect?code=x", left = true))
        assertEquals(true, EbPanelRules.bankReturn(EbPanelRules.PANEL, left = true))
        assertEquals(false, EbPanelRules.bankReturn("${EbPanelRules.PANEL}#error=accounts_linking_failure", left = true))
        assertTrue(EbPanelRules.isSignInLink(LINK))
        assertFalse(EbPanelRules.isSignInLink("https://evil.example/__/auth/action?mode=signIn"))
        assertFalse(EbPanelRules.isSignInLink("https://enablebanking.com/__/auth/action?mode=resetPassword"))
    }

    @Test fun setupTakesTheFlowsResultAndGoesToProfilo() {
        val m = SetupRules.ebDone(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), linked = listOf(BANK), pending = listOf("Banca Demo"))
        assertEquals(SetupEntry.Step(SetupStep.PROFILO), m.top)
        assertEquals(mapOf(BANK to BankLink.COLLEGATA, "Banca Demo" to BankLink.DA_APPROVARE), m.links)
        assertEquals(listOf("Banca Demo"), SetupRules.pendingBanks(m))
        // a bank-only run ("Collega un conto") ends on the same reducer
        val run = SetupRules.ebDone(SetupRules.bankFlow(null), listOf(BANK), emptyList())
        assertTrue(SetupRules.bankRunEnded(run))
    }

    /** Fold, 2026-11-10 14:05: a page call that never answers must not hold the flow past its bound (G-16). */
    @Test fun aPageCallThatNeverAnswersEndsInANamedFailure() = runBlocking {
        val hung = object : EbPage by page { override suspend fun run(body: String, args: JSONObject): JSONObject? = kotlinx.coroutines.awaitCancellation() }
        val r = kotlinx.coroutines.withTimeoutOrNull(20_000) { EbPanel(hung, { t }, { t += it }, callBoundMs = 2).completeLink(LINK, EMAIL) }
        assertEquals(EbPanelResult.Failed(EbPanelFailure.UNREACHABLE), r)
        var first = true
        val once = object : EbPage by page { override suspend fun run(body: String, args: JSONObject): JSONObject? = if (first) { first = false; kotlinx.coroutines.awaitCancellation() } else page.run(body, args) }
        page.states("in")
        assertTrue(EbPanel(once, { t }, { t += it }, callBoundMs = 2).completeLink(LINK, EMAIL) is EbPanelResult.Ok)
    }

    @Test fun everyActionHasAnOutcome() {
        assertTrue(ebOutcome(EbFlowRules.working(EbFlowModel())) is ActionOutcome.Working)
        assertEquals(ActionOutcome.Failed("Il link non vale più"), ebOutcome(EbFlowRules.fail(EbFlowModel(), EbFailure(EbFailKind.LINK_INVALID))))
        assertEquals(ActionOutcome.Idle, ebOutcome(EbFlowModel()))
    }

    private fun atApprove() = EbFlowRules.banksChosen(EbFlowRules.toggle(EbFlowRules.start(true), BANK))

    @Test fun statusMappingAndTheOneAlreadyLinkedDecisionSkipTheSecondLinking() = runBlocking {
        access.stored = APP
        page.states("in")
        page.on("STATUS", o("active" to true, "linked" to 1, "here" to 0, "keys" to JSONArray().put("uid").put("aspsp")))
        val st = (panel().status(APP, BANK) as EbPanelResult.Ok).value
        assertEquals(listOf("uid", "aspsp"), st.keys); assertEquals(0, st.here); assertEquals(BANK, page.calls.last { it.first == "STATUS" }.second.getString("bank"))
        // the one rule
        fun s(a: Boolean, l: Int, h: Int) = xyz.grtlabs.pilastri.sync.eb.EbAppStatus(a, l, h)
        assertTrue(EbFlowRules.alreadyLinked(s(true, 1, 1), true)); assertTrue(EbFlowRules.alreadyLinked(s(true, 1, 0), false))
        assertFalse(EbFlowRules.alreadyLinked(s(true, 1, 0), true)); assertFalse(EbFlowRules.alreadyLinked(s(false, 2, 2), false)); assertFalse(EbFlowRules.alreadyLinked(s(true, 0, 0), false))
        // the flow: straight to step 6, no link_accounts, no bank page
        val m = atApprove().step(runner().linkAccounts(BANK, "personal"))
        assertEquals(EbStep(EbScreen.CONFIRM, BANK), m.top)
        assertTrue(page.calls.none { it.first == "LINK" }); assertTrue(page.loads.none { it.contains("banca.esempio") }); assertTrue(page.shows.isEmpty())
    }

    @Test fun aReturnWithTheSameLinkedCountIsLinked() = runBlocking {
        access.stored = APP; access.session = true   // a bank session exists, none of the accounts mention this bank: no skip
        page.states("in")
        page.on("STATUS", o("active" to true, "linked" to 1, "here" to 0))   // before and after: not strictly more
        page.on("LINK", o("url" to BANK_URL))
        page.navigations[BANK_URL] = listOf(BANK_URL, EbPanelRules.PANEL)
        val m = atApprove().step(runner().linkAccounts(BANK, "personal"))
        assertEquals(EbStep(EbScreen.CONFIRM, BANK), m.top)
        assertTrue(page.calls.any { it.first == "LINK" })
    }

    @Test fun zeroAccountsAfterASkipGoBackToStep5OnceWithTheSkipOff() = runBlocking {
        access.accounts = 0
        val at6 = EbFlowRules.accountsLinked(atApprove())
        val back = at6.step(runner().completeConsent(BANK, "https://localhost/pilastri/eb?code=TEST&state=TEST", null))
        assertEquals(EbStep(EbScreen.APPROVE, BANK), back.top); assertNull(back.failure); assertFalse(back.working); assertEquals(setOf(BANK), back.noSkip)
        // the skip is off for this run: the status says "already linked" but the bank is opened
        access.stored = APP; page.states("in"); page.on("STATUS", o("active" to true, "linked" to 1, "here" to 1)); page.on("LINK", o("url" to BANK_URL))
        page.navigations[BANK_URL] = listOf(BANK_URL, EbPanelRules.PANEL)
        assertEquals(EbScreen.CONFIRM, back.step(runner().linkAccounts(BANK, "personal", skip = BANK !in back.noSkip)).screen)
        assertTrue(page.calls.any { it.first == "LINK" })
        // a second time it is a named failure, never a loop
        val again = EbFlowRules.accountsLinked(back).step(runner().completeConsent(BANK, "https://localhost/pilastri/eb?code=TEST&state=TEST", null))
        assertEquals(EbFailure(EbFailKind.NO_ACCOUNTS, BANK), again.failure)
    }

    @Test fun theRedirectClaimIsLoggedAsShapesAndDecidesTheOutcome() {
        val reg = "https://localhost/pilastri/eb"
        fun parse(u: String) = xyz.grtlabs.pilastri.sync.eb.EbRedirect.parse(u, reg, "ST")
        val wrong = "$reg?code=ABCDEFGH&state=OTHER"; val denied = "$reg?error=access_denied&state=ST"; val good = "$reg?code=ABCDEFGH&state=ST"
        assertEquals("claim matched=yes code=yes len=8 error=no state=no pending=yes", xyz.grtlabs.pilastri.sync.eb.EbLog.claim(wrong, reg, "ST"))
        assertEquals("claim matched=yes code=no error=access_denied state=yes pending=yes", xyz.grtlabs.pilastri.sync.eb.EbLog.claim(denied, reg, "ST"))
        assertEquals("claim matched=yes code=yes len=8 error=no state=yes pending=yes", xyz.grtlabs.pilastri.sync.eb.EbLog.claim(good, reg, "ST"))
        assertEquals("claim matched=no code=no error=no state=no pending=no", xyz.grtlabs.pilastri.sync.eb.EbLog.claim("https://x.example/a", reg, null))
        assertEquals(xyz.grtlabs.pilastri.sync.eb.EbRedirect.Result.Invalid, parse(wrong))
        assertEquals(xyz.grtlabs.pilastri.sync.eb.EbRedirect.Result.Denied("access_denied"), parse(denied))
        assertEquals(xyz.grtlabs.pilastri.sync.eb.EbRedirect.Result.Code("ABCDEFGH"), parse(good))
        assertFalse("the code value never reaches the line", "ABCDEFGH" in xyz.grtlabs.pilastri.sync.eb.EbLog.claim(good, reg, "ST") + xyz.grtlabs.pilastri.sync.eb.EbLog.shape(good))
        assertEquals("localhost/pilastri/eb", xyz.grtlabs.pilastri.sync.eb.EbLog.shape(good))
    }
}

/** Source rules: no logging on the EB path, the token never leaves the page, no e-mail in the flow state, never "Request activation". */
class EbSetupSourceTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private val files = listOf("sync/eb/EbPanel.kt", "sync/eb/WebViewEbPage.kt", "ui/setup/EbSetupModel.kt", "ui/setup/EbSetupScreens.kt")

    @Test fun nothingOnTheEbSetupPathLogs() {
        val bad = files.flatMap { n -> File(root, n).readLines().withIndex().filter { (_, l) -> Regex("""\b(Log\.[vdiwe]|println|Timber\.|Logger\.|logOutcome|console\.log)""").containsMatchIn(l) }.map { (i, _) -> "$n:${i + 1}" } }
        assertTrue("No logging on the EB setup path: $bad", bad.isEmpty())
    }

    @Test fun noScriptReturnsTheToken() {
        val scripts = listOf(EbPanelScripts.STATE, EbPanelScripts.SUBMIT_EMAIL, EbPanelScripts.LEGAL_LINKS, EbPanelScripts.REGISTER, EbPanelScripts.ASPSPS, EbPanelScripts.STATUS, EbPanelScripts.LINK)
        scripts.forEach { s ->
            s.lines().filter { "return" in it }.forEach { l ->
                assertFalse("returns the token: $l", Regex("""return\s*\{[^}]*(\bT\b|\bH\b|idToken|token|ARGS\.email)""").containsMatchIn(l))
            }
            assertFalse("never the organisations' activation request", s.contains("PUT") || s.contains("billingAccountId"))
        }
        assertFalse(File(root, "sync/eb/EbPanel.kt").readText().contains("Request activation\")"))
    }

    @Test fun theFlowStateHoldsNoEmail() {
        assertTrue(EbFlowModel::class.java.declaredFields.none { it.name.contains("mail", ignoreCase = true) })
        assertFalse(EbFlowRules.emailSent(EbFlowModel(), 0).toString().contains("@"))
    }
}
