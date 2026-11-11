package xyz.grtlabs.pilastri.sync.auto

import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportProvider

/** State transitions of the orchestrator with a fake page; synthetic login values only. */
class AutoSyncTest {
    private val range = SyncRange(LocalDate.of(2026, 10, 1), T.today)
    private val timing = AutoSyncTiming(stallMs = 300, userWaitMs = 600, settleMs = 5, maxProbes = 6)
    private val vault = CredentialVault(MemorySecretStore(), SoftwareCipher())
    private val P = ImportProvider.ISYBANK
    private val signedIn = "https://www.isybank.com/app/home"
    private val login = ProviderSites.ISYBANK.loginUrl

    private fun saveLogin() { vault.setRemember(P, true); vault.save(P, T.USER, T.PASSWORD) }

    private fun run(fetcher: FakeFetcher, driver: FakeDriver, progress: MutableList<AutoSyncProgress> = ArrayList()) =
        runBlocking { AutoSync(fetcher, vault, timing).run(range, driver) { progress += it } }

    @Test fun aLiveSessionSyncsWithoutOpeningAnyPage() {
        val fetcher = FakeFetcher(signedIn = true)
        val driver = FakeDriver()
        val out = run(fetcher, driver)
        assertTrue(out is AutoSyncResult.Synced)
        assertTrue(driver.opened.isEmpty() && driver.fills.isEmpty() && driver.reveals.isEmpty())
        assertTrue(runBlocking { AutoSync(fetcher, vault, timing).syncWithSession(range) } is AutoSyncResult.Synced)
    }

    @Test fun anExpiredSessionSignsInHiddenWithTheSavedLogin() {
        saveLogin()
        val fetcher = FakeFetcher()
        val driver = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { fetcher.signedIn = true; listOf(LoginEvent.Quiet, LoginEvent.Page(signedIn)) }
        }
        val progress = ArrayList<AutoSyncProgress>()
        val out = run(fetcher, driver, progress)
        assertTrue(out is AutoSyncResult.Synced)
        assertEquals(listOf(login), driver.opened)
        assertEquals(listOf(LoginStep.BOTH), driver.fills)
        assertEquals(T.USER to T.PASSWORD, driver.filledValues.single())
        assertTrue(driver.reveals.isEmpty())
        assertTrue(AutoSyncProgress.SigningIn in progress && AutoSyncProgress.Reading in progress)
        assertFalse((out as AutoSyncResult.Synced).rememberedLogin)
    }

    @Test fun twoStepSignInThenPushApprovalWaitsCoveredAndContinues() {
        saveLogin()
        val fetcher = FakeFetcher()
        val driver = FakeDriver()
        driver.onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.USERNAME)) }
        driver.onFill = { step ->
            if (step == LoginStep.USERNAME) listOf(LoginEvent.Form(LoginStep.PASSWORD))
            else {
                // The bank sends a push; the person approves it in the bank's app a moment later.
                Thread { Thread.sleep(80); fetcher.signedIn = true; driver.send(LoginEvent.Page(signedIn)) }.start()
                listOf(LoginEvent.Sca(ScaKind.PUSH))
            }
        }
        val progress = ArrayList<AutoSyncProgress>()
        val out = run(fetcher, driver, progress)
        assertTrue(out is AutoSyncResult.Synced)
        assertEquals(listOf(LoginStep.USERNAME, LoginStep.PASSWORD), driver.fills)
        assertTrue(driver.reveals.isEmpty())
        assertTrue(AutoSyncProgress.Waiting(AttentionReason.SCA_PUSH) in progress)
    }

    @Test fun aRefusedPasswordIsTriedOnceThenMarkedAndShown() {
        saveLogin()
        val fetcher = FakeFetcher()
        val driver = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { listOf(LoginEvent.Page(login), LoginEvent.Form(LoginStep.BOTH)) }
        }
        // The person then signs in by hand on the shown page.
        Thread { Thread.sleep(100); fetcher.signedIn = true; driver.send(LoginEvent.Page(signedIn)) }.start()
        val out = run(fetcher, driver)
        assertTrue(out is AutoSyncResult.Synced)
        assertEquals(listOf(LoginStep.BOTH), driver.fills)
        assertEquals(listOf(AttentionReason.CREDENTIALS_REJECTED), driver.reveals)
        assertTrue(vault.isRejected(P))
        // Remember is on and the saved login was refused: the hand-typed values may be captured.
        assertEquals(true, driver.captures.last())
    }

    @Test fun offscreenARefusalOrAnErrorEndsWithoutRetrying() {
        saveLogin()
        val again = FakeDriver(canReveal = false).apply {
            onOpen = { listOf(LoginEvent.Form(LoginStep.PASSWORD)) }
            onFill = { listOf(LoginEvent.Form(LoginStep.PASSWORD)) }
        }
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.CREDENTIALS_REJECTED), run(FakeFetcher(), again))
        assertEquals(1, again.fills.size)
        // A refused login is never filled again on a later run.
        val later = FakeDriver(canReveal = false).apply { onOpen = { listOf(LoginEvent.Form(LoginStep.BOTH)) } }
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN), run(FakeFetcher(), later))
        assertTrue(later.fills.isEmpty())

        vault.save(P, T.USER, "Altra-sintetica-789")
        val error = FakeDriver(canReveal = false).apply {
            onOpen = { listOf(LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { listOf(LoginEvent.LoginError) }
        }
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.CREDENTIALS_REJECTED), run(FakeFetcher(), error))
    }

    @Test fun challengesAndCodesAreShownNeverSolved() {
        saveLogin()
        val challenge = FakeDriver(canReveal = false).apply { onOpen = { listOf(LoginEvent.Challenge) } }
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.CHALLENGE), run(FakeFetcher(), challenge))
        assertTrue(challenge.fills.isEmpty())

        val fetcher = FakeFetcher()
        val code = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { listOf(LoginEvent.Sca(ScaKind.CODE)) }
        }
        Thread { Thread.sleep(100); fetcher.signedIn = true; code.send(LoginEvent.Page(signedIn)) }.start()
        assertTrue(run(fetcher, code) is AutoSyncResult.Synced)
        assertEquals(listOf(AttentionReason.SCA_CODE), code.reveals)
        assertTrue(code.covers >= 1)

        val offscreenCode = FakeDriver(canReveal = false).apply {
            onOpen = { listOf(LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { listOf(LoginEvent.Sca(ScaKind.CODE)) }
        }
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.SCA_CODE), run(FakeFetcher(), offscreenCode))
    }

    @Test fun withoutASavedLoginThePageIsShownOrTheRunStops() {
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN), run(FakeFetcher(), FakeDriver(canReveal = false)))
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN), runBlocking { AutoSync(FakeFetcher(), vault, timing).syncWithSession(range) })
    }

    @Test fun aHandTypedLoginIsKeptOnlyAfterItWorksAndOnlyWithRemember() {
        vault.setRemember(P, true)
        val fetcher = FakeFetcher()
        val driver = FakeDriver().apply { onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.USERNAME)) } }
        Thread {
            Thread.sleep(60)
            driver.send(LoginEvent.Captured(T.USER, null), LoginEvent.Form(LoginStep.PASSWORD), LoginEvent.Captured(null, T.PASSWORD))
            Thread.sleep(40)
            fetcher.signedIn = true
            driver.send(LoginEvent.Page(signedIn))
        }.start()
        val out = run(fetcher, driver) as AutoSyncResult.Synced
        assertTrue(out.rememberedLogin)
        assertEquals(listOf(AttentionReason.SIGN_IN), driver.reveals)
        assertEquals(true, driver.captures.first())
        assertTrue(driver.fills.isEmpty())
        assertEquals(T.PASSWORD, vault.load(P)!!.password)

        // Same flow with the switch off: nothing is stored.
        val off = CredentialVault(MemorySecretStore(), SoftwareCipher())
        val f2 = FakeFetcher()
        val d2 = FakeDriver().apply { onOpen = { listOf(LoginEvent.Captured(T.USER, T.PASSWORD), LoginEvent.Page(signedIn)) } }
        val r2 = runBlocking { AutoSync(f2.also { it.signedIn = false }, off, timing).run(range, d2.also { Thread { Thread.sleep(30); f2.signedIn = true; d2.send(LoginEvent.Page(signedIn)) }.start() }) }
        assertFalse((r2 as AutoSyncResult.Synced).rememberedLogin)
        assertFalse(off.has(P))
        assertEquals(false, d2.captures.first())
    }

    @Test fun aCoveredPageThatStallsIsShownOrReported() {
        saveLogin()
        val shown = FakeDriver().apply { onOpen = { listOf(LoginEvent.Page(it)) } }
        val out = run(FakeFetcher(), shown)
        assertEquals(listOf(AttentionReason.STALLED), shown.reveals)
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.STALLED), out)
        assertEquals(AutoSyncResult.NeedsUser(AttentionReason.STALLED), run(FakeFetcher(), FakeDriver(canReveal = false)))
    }

    @Test fun signedInButRefusedDataMeansTheFormatIsOutdated() {
        saveLogin()
        val fetcher = FakeFetcher(needsPage = true, navigates = true, site = ProviderSites.PAYPAL, whenSignedInButRefused = true)
        val driver = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { fetcher.signedIn = true; listOf(LoginEvent.Page("https://www.paypal.com/myaccount/summary")) }
        }
        vault.setRemember(ImportProvider.PAYPAL, true)
        vault.save(ImportProvider.PAYPAL, T.USER, T.PASSWORD)
        val out = run(fetcher, driver) as AutoSyncResult.Failed
        assertTrue(out.message.contains("formato da aggiornare"))
        // A navigating fetcher is only asked to navigate on a signed-in page, never in the middle of the sign-in.
        assertEquals(listOf(true, true), fetcher.navigations)
    }

    @Test fun closedNotConfiguredAndBrokenAreDistinct() {
        saveLogin()
        val closed = FakeDriver().apply { onOpen = { listOf(LoginEvent.Closed) } }
        assertEquals(AutoSyncResult.Cancelled, run(FakeFetcher(), closed))
        val none = run(FakeFetcher(configured = false), FakeDriver()) as AutoSyncResult.Failed
        assertTrue(none.message.contains("formato non ancora configurato"))
        val broken = FakeDriver().apply { onOpen = { listOf(LoginEvent.Broken("rotto")) } }
        assertEquals(AutoSyncResult.Failed("rotto"), run(FakeFetcher(), broken))
    }

    // ---- the visible page: sign-in only (AGENTS.md: the provider page is visible only for signing in, 2FA or a challenge) ----

    private val pp = ImportProvider.PAYPAL
    private val account = "https://www.paypal.com/myaccount/summary"
    private fun paypalReader() = FakeFetcher(site = ProviderSites.PAYPAL, needsPage = true, navigates = true)
    /** A settle long enough that two events sent together are both handled before it ends. */
    private val signInTiming = timing.copy(settleMs = 40)
    private fun signIn(fetcher: FakeFetcher, driver: FakeDriver, progress: MutableList<AutoSyncProgress> = ArrayList()) =
        runBlocking { AutoSync(fetcher, vault, signInTiming).signIn(range, driver) { progress += it } }

    @Test fun aSavedLoginSignsInBehindTheCoverAndNothingIsReadOnThePage() {
        vault.setRemember(pp, true); vault.save(pp, T.USER, T.PASSWORD)
        val fetcher = paypalReader()
        val driver = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { listOf(LoginEvent.Quiet, LoginEvent.Page(account)) }
        }
        assertEquals(AutoSyncResult.SignedIn(false), signIn(fetcher, driver))
        assertEquals(0, fetcher.calls)
        assertEquals(listOf(ProviderSites.PAYPAL.loginUrl), driver.opened)
        assertTrue(driver.loads.isEmpty())
        assertTrue(driver.reveals.isEmpty())
        assertTrue(driver.covers >= 1)
    }

    @Test fun aLiveSessionSentOnToTheAccountPageIsNeverShown() {
        val fetcher = paypalReader()
        val driver = FakeDriver().apply { onOpen = { listOf(LoginEvent.Page(account)) } }
        val progress = ArrayList<AutoSyncProgress>()
        assertEquals(AutoSyncResult.SignedIn(false), signIn(fetcher, driver, progress))
        assertTrue(driver.reveals.isEmpty())
        assertEquals(0, fetcher.calls)
        assertFalse(AutoSyncProgress.Reading in progress)
    }

    @Test fun aHandSignInIsShownThenCoveredAndRememberedWhenAsked() {
        vault.setRemember(pp, true)
        val driver = FakeDriver().apply { onOpen = { listOf(LoginEvent.Page(it)) } }
        Thread {
            Thread.sleep(60)
            driver.send(LoginEvent.Captured(T.USER, T.PASSWORD))
            Thread.sleep(30)
            driver.send(LoginEvent.Page(account))
        }.start()
        assertEquals(AutoSyncResult.SignedIn(true), signIn(paypalReader(), driver))
        assertEquals(listOf(AttentionReason.SIGN_IN), driver.reveals)
        assertTrue(driver.covers >= 1)
        assertEquals(T.PASSWORD, vault.load(pp)!!.password)
    }

    @Test fun aStepAfterTheAccountPageIsShownAgainAndOnlyASettledAccountPageCounts() {
        val driver = FakeDriver().apply { onOpen = { listOf(LoginEvent.Page(it)) } }
        Thread {
            Thread.sleep(60)
            // The account page flashes, then PayPal asks for a 2FA step on its sign-in flow.
            driver.send(LoginEvent.Page(account), LoginEvent.Page("https://www.paypal.com/authflow/twofactor"))
            Thread.sleep(60)
            driver.send(LoginEvent.Page(account))
        }.start()
        assertEquals(AutoSyncResult.SignedIn(false), signIn(paypalReader(), driver))
        assertEquals(listOf(AttentionReason.SIGN_IN, AttentionReason.SIGN_IN), driver.reveals)
        assertTrue(driver.covers >= 2)
    }

    @Test fun afterAnEarlierEndASignedInPageIsConfirmedWithoutReopeningTheSignIn() {
        val driver = FakeDriver().apply { url = account }
        assertEquals(AutoSyncResult.SignedIn(false), signIn(paypalReader(), driver))
        assertTrue(driver.opened.isEmpty())
    }

    @Test fun aCookieFetcherOnTheVisiblePageReadsOverHttpAndNeverNavigates() {
        val live = FakeFetcher(signedIn = true)
        val idle = FakeDriver()
        assertTrue(signIn(live, idle) is AutoSyncResult.Synced)
        assertTrue(idle.opened.isEmpty())
        assertEquals(listOf(false), live.navigations)

        saveLogin()
        val fetcher = FakeFetcher()
        val driver = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { fetcher.signedIn = true; listOf(LoginEvent.Page(signedIn)) }
        }
        assertTrue(signIn(fetcher, driver) is AutoSyncResult.Synced)
        assertTrue(fetcher.navigations.none { it })
        assertTrue(driver.loads.isEmpty())
    }

    @Test fun theRealHttpFetcherSeesTheSessionTheSignInCreated() {
        saveLogin()
        val url = "https://www.isybank.com/synthetic/export"
        val spec = HttpFetchSpec("GET", "$url?dal={from}", response = ResponseKind.JSON,
            json = JsonMapping(items = "", id = "id", date = "d", amount = "a", accountLabel = "isybank", returnsWholeRange = true))
        val jar = HashMap<String, String>()
        val cookies = object : SessionCookies {
            override fun header(url: String) = jar[java.net.URI(url).host]
            override fun value(url: String, name: String): String? = null
            override fun store(url: String, setCookie: List<String>) = Unit
        }
        val http = FakeHttp().apply {
            on("$url?dal=2026-10-01") { call ->
                if (call.header("Cookie") == "SESSION=new") FakeHttp.ok("""[{"id":"x1","d":"2026-10-02","a":"-1,00"}]""".toByteArray(), "application/json")
                else FakeHttp.status(401)
            }
        }
        val fetcher = HttpSessionFetcher(ProviderSites.ISYBANK, spec, http, cookies, null, { T.fetchedAt }, Dispatchers.Unconfined)
        val driver = FakeDriver().apply {
            onOpen = { listOf(LoginEvent.Page(it), LoginEvent.Form(LoginStep.BOTH)) }
            onFill = { jar["www.isybank.com"] = "SESSION=new"; listOf(LoginEvent.Page(signedIn)) }
        }
        val out = runBlocking { AutoSync(fetcher, vault, timing).run(range, driver) } as AutoSyncResult.Synced
        assertEquals(listOf(-100L), out.batch.movements.map { it.signedCents })
        assertEquals(range.from, out.batch.coverageFrom)
        assertEquals(listOf(null, "SESSION=new"), http.calls.map { it.header("Cookie") })
    }
}
