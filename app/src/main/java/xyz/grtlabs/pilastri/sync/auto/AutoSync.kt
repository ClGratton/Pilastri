package xyz.grtlabs.pilastri.sync.auto

import kotlinx.coroutines.withTimeoutOrNull
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.sync.ImportHandoff

/** The three things a person may have to do. */
enum class UserAction { APPROVE_2FA, SIGN_IN_AGAIN, CHALLENGE }

/** Why the sync stopped for the person. [needsPage]: the provider's page must be shown (a push approval happens in another app). */
enum class AttentionReason(val action: UserAction, val needsPage: Boolean) {
    SIGN_IN(UserAction.SIGN_IN_AGAIN, true),
    CREDENTIALS_REJECTED(UserAction.SIGN_IN_AGAIN, true),
    STALLED(UserAction.SIGN_IN_AGAIN, true),
    SCA_PUSH(UserAction.APPROVE_2FA, false),
    SCA_CODE(UserAction.APPROVE_2FA, true),
    SCA_CHOICE(UserAction.APPROVE_2FA, true),
    CHALLENGE(UserAction.CHALLENGE, true);

    /** One short line for the screen. */
    fun line(label: String): String = when (this) {
        SIGN_IN, STALLED -> "Accedi di nuovo a $label"
        CREDENTIALS_REJECTED -> "$label ha rifiutato l'accesso salvato: accedi di nuovo"
        SCA_PUSH -> "Approva nell'app $label"
        SCA_CODE -> "Inserisci il codice ricevuto"
        SCA_CHOICE -> "Scegli come confermare l'accesso"
        CHALLENGE -> "$label chiede una verifica"
    }
}

/** Progress for the cover screen (activity) or the per-provider state (background). */
sealed interface AutoSyncProgress {
    data object CheckingSession : AutoSyncProgress
    data object SigningIn : AutoSyncProgress
    /** The person must act: approve in the provider's app, or use the page that is now shown. */
    data class Waiting(val reason: AttentionReason) : AutoSyncProgress
    data object Reading : AutoSyncProgress
}

sealed interface AutoSyncResult {
    /** [rememberedLogin]: a sign-in typed by hand was saved because "Ricorda l'accesso" is on. */
    class Synced(val batch: ImportBatch, val rememberedLogin: Boolean = false) : AutoSyncResult {
        /** For the preview route of the existing activities: the batch stays in memory, only the token travels. */
        fun handoffToken(): String = ImportHandoff.put(batch)
    }
    /**
     * Visible page only ([AutoSync.signIn]): the session is signed in and nothing was read on the page. The data is read in the
     * background ([AutoSyncCoordinator.onSignedIn]). [rememberedLogin] as in [Synced].
     */
    data class SignedIn(val rememberedLogin: Boolean = false) : AutoSyncResult
    data class NeedsUser(val reason: AttentionReason) : AutoSyncResult
    data class Failed(val message: String) : AutoSyncResult
    data object Cancelled : AutoSyncResult
}

/** What the provider page did, as the driver reports it. */
sealed interface LoginEvent {
    /** A main-frame page finished loading, or a single-page app changed its URL. */
    data class Page(val url: String) : LoginEvent
    data class Form(val step: LoginStep) : LoginEvent
    data class Sca(val kind: ScaKind) : LoginEvent
    data object Challenge : LoginEvent
    /** The page shows a sign-in error after a fill. */
    data object LoginError : LoginEvent
    /** Page activity without a decision (the script said hello, nothing, stale or submitted). */
    data object Quiet : LoginEvent
    class Captured(val username: String?, val password: String?) : LoginEvent {
        override fun toString() = "Captured(***)"
    }
    /** The person closed the page. */
    data object Closed : LoginEvent
    data class Broken(val message: String) : LoginEvent
}

/** A WebView holding the provider session, visible behind a cover ([canReveal]) or offscreen. Implemented by [WebLoginDriver]. */
interface LoginDriver : PageAccess {
    val canReveal: Boolean
    /** Starts loading [url] (a provider page) and returns; events follow. */
    fun open(url: String)
    suspend fun nextEvent(): LoginEvent
    /** Fills [step] with [login] and presses the form's own button. */
    fun fill(step: LoginStep, login: SavedLogin)
    /** Report typed values at submit (only while "Ricorda l'accesso" is on and nothing is saved yet). */
    fun setCapture(on: Boolean)
    fun reveal(reason: AttentionReason)
    fun cover()
}

/** Timings of [AutoSync.run]. */
data class AutoSyncTiming(
    /** Covered page with no event for this long: shown to the person (or NeedsUser when it cannot be shown). */
    val stallMs: Long = 25_000,
    /** How long a shown page or a push approval may wait for the person. */
    val userWaitMs: Long = 5 * 60_000,
    /** A signed-in-looking page must stay put this long before the data is read. */
    val settleMs: Long = 1_500,
    val maxProbes: Int = 12,
)

/**
 * One provider's automatic sync:
 *
 *  1. try the stored session (the WebView cookie jar, private to the app);
 *  2. signed out: open the sign-in page. With a saved sign-in the page stays covered and [LoginAutofill] fills it, each form step
 *     at most once; without one the page is shown (or, offscreen, [AttentionReason.SIGN_IN] is returned);
 *  3. 2FA: a push approval keeps the cover and waits; a code or a choice shows the provider's own page; a bot check is shown and
 *     never touched;
 *  4. once a signed-in page has settled, read the data with the [SessionFetcher] and return it.
 *
 * A saved sign-in that the provider refuses (the form comes back, or an error shows after the fill) is marked rejected in the
 * vault and never retried automatically, so the app cannot lock the account by repeating a wrong password.
 *
 * Background periodic sync (not built): it would need WorkManager, a fetch path that works without a window (the HTTP fetcher with
 * cookies does; the PayPal reader and TR need an offscreen WebView in a worker, which is fragile), a Keystore key usable while the
 * phone is locked (true today), a notification for every 2FA or sign-in (bank web sessions are short, so most background runs would
 * end there), and Doze/standby limits. Today the sync runs when the app starts or returns to the foreground ([AutoSyncCoordinator]).
 */
class AutoSync(
    private val fetcher: SessionFetcher,
    private val vault: CredentialVault?,
    private val timing: AutoSyncTiming = AutoSyncTiming(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val site: ProviderSite get() = fetcher.site
    private val provider get() = site.provider

    /** Without a WebView: only for fetchers that need no page. Signed out gives NeedsUser(SIGN_IN). */
    suspend fun syncWithSession(range: SyncRange): AutoSyncResult {
        if (fetcher.needsPage) return AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN)
        return when (val r = fetcher.fetch(range, null, mayNavigate = false)) {
            is FetchOutcome.Fetched -> AutoSyncResult.Synced(r.batch)
            FetchOutcome.SignedOut, FetchOutcome.NotReady -> AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN)
            FetchOutcome.Challenge -> AutoSyncResult.NeedsUser(AttentionReason.CHALLENGE)
            is FetchOutcome.Failed -> AutoSyncResult.Failed(r.message)
            is FetchOutcome.NotConfigured -> AutoSyncResult.Failed(r.message)
        }
    }

    /** The background run (offscreen [driver]): check the session, sign in hidden when a saved login allows, read the data. */
    suspend fun run(range: SyncRange, driver: LoginDriver, onProgress: (AutoSyncProgress) -> Unit = {}): AutoSyncResult {
        if (!fetcher.configured) return AutoSyncResult.Failed("${site.label}: $NOT_CONFIGURED")
        onProgress(AutoSyncProgress.CheckingSession)
        when (val first = fetcher.fetch(range, driver, mayNavigate = true)) {
            is FetchOutcome.Fetched -> return AutoSyncResult.Synced(first.batch)
            is FetchOutcome.Failed -> return AutoSyncResult.Failed(first.message)
            is FetchOutcome.NotConfigured -> return AutoSyncResult.Failed(first.message)
            FetchOutcome.Challenge -> return Flow(range, driver, onProgress, onChallenge = true, signInOnly = false).run()
            FetchOutcome.SignedOut, FetchOutcome.NotReady -> Unit
        }
        return Flow(range, driver, onProgress, onChallenge = false, signInOnly = false).run()
    }

    /**
     * The visible page (the first link from Collegamenti, or a step the sync needs from the person): ONLY until the session is
     * signed in. The page loads nothing but the provider's sign-in page (a signed-in session is expected to be sent on to its
     * account page) and the steps the provider itself leads to; it is shown only for a sign-in, a 2FA step or a check.
     *
     * Ends with [AutoSyncResult.SignedIn] once a signed-in page has settled: the caller closes the page and the coordinator reads
     * the data in the background. A fetcher that reads with cookies alone, without navigating ([SessionFetcher.navigates] false),
     * may answer at once from the stored session or a provider page the person reached: then [AutoSyncResult.Synced], read over
     * HTTP, with the page never navigated for it.
     */
    suspend fun signIn(range: SyncRange, driver: LoginDriver, onProgress: (AutoSyncProgress) -> Unit = {}): AutoSyncResult {
        if (!fetcher.configured) return AutoSyncResult.Failed("${site.label}: $NOT_CONFIGURED")
        onProgress(AutoSyncProgress.CheckingSession)
        if (!fetcher.navigates) {
            when (val first = fetcher.fetch(range, driver, mayNavigate = false)) {
                is FetchOutcome.Fetched -> return AutoSyncResult.Synced(first.batch)
                is FetchOutcome.Failed -> return AutoSyncResult.Failed(first.message)
                is FetchOutcome.NotConfigured -> return AutoSyncResult.Failed(first.message)
                // A check seen only over HTTP: the page decides once it shows the provider's own sign-in.
                FetchOutcome.Challenge, FetchOutcome.SignedOut, FetchOutcome.NotReady -> Unit
            }
        }
        return Flow(range, driver, onProgress, onChallenge = false, signInOnly = true).run()
    }

    private inner class Flow(
        val range: SyncRange,
        val driver: LoginDriver,
        val onProgress: (AutoSyncProgress) -> Unit,
        val onChallenge: Boolean,
        /** [signIn]: end at a settled signed-in page instead of reading; never ask the fetcher to navigate. */
        val signInOnly: Boolean,
    ) {
        var login: SavedLogin? = vault?.loadForAutoSignIn(provider)
        val remember = vault?.isRemembered(provider) == true
        val filled = HashSet<LoginStep>()
        var waiting: AttentionReason? = null
        var push = false
        var since = clockMs()
        var waitStart = clockMs()
        var probeAt: Long? = null
        var probeOnSignedIn = false
        var probes = 0
        var capturedUser: String? = null
        var capturedPassword: String? = null

        suspend fun run(): AutoSyncResult {
            driver.setCapture(remember && login == null)
            if (onChallenge) {
                // The page in front of the person is the bot check: leave it there.
                attention(AttentionReason.CHALLENGE)?.let { return it }
            } else if (signInOnly) {
                when (site.classify(driver.currentUrl())) {
                    // The person signed in by hand after an earlier end: confirm the page settles there.
                    SitePage.SIGNED_IN -> settleSignedIn()
                    SitePage.LOGIN -> if (login == null) attention(AttentionReason.SIGN_IN)?.let { return it }
                    // The sign-in page only. Without a saved login it is shown when it turns out to be a sign-in form (a
                    // signed-in session is sent on to the account page and never shown).
                    else -> driver.open(site.loginUrl)
                }
            } else {
                if (site.classify(driver.currentUrl()) != SitePage.LOGIN) driver.open(site.loginUrl)
                if (login == null) attention(AttentionReason.SIGN_IN)?.let { return it }
            }
            while (true) {
                val now = clockMs()
                val limit = if (waiting != null || push) waitStart + timing.userWaitMs else since + timing.stallMs
                val until = minOf(limit, probeAt ?: Long.MAX_VALUE)
                val event = if (until <= now) null else withTimeoutOrNull(until - now) { driver.nextEvent() }
                if (event == null) {
                    val at = probeAt
                    if (at != null && clockMs() >= at) {
                        probeAt = null
                        probe()?.let { return it }
                        continue
                    }
                    if (clockMs() < limit) continue
                    if (waiting == null && !push) {
                        attention(AttentionReason.STALLED)?.let { return it }
                        continue
                    }
                    return AutoSyncResult.NeedsUser(waiting ?: AttentionReason.SCA_PUSH)
                }
                since = clockMs()
                handle(event)?.let { return it }
            }
        }

        /** Shows the page for [reason]; offscreen, ends the run with NeedsUser. */
        fun attention(reason: AttentionReason): AutoSyncResult? {
            if (!driver.canReveal) return AutoSyncResult.NeedsUser(reason)
            if (waiting != reason) {
                waiting = reason
                waitStart = clockMs()
                driver.reveal(reason)
            }
            onProgress(AutoSyncProgress.Waiting(reason))
            return null
        }

        suspend fun handle(e: LoginEvent): AutoSyncResult? {
            when (e) {
                is LoginEvent.Page -> when (site.classify(e.url)) {
                    SitePage.LOGIN -> {
                        probeAt = null
                        if (login == null && waiting == null) return attention(AttentionReason.SIGN_IN)
                    }
                    SitePage.CHALLENGE -> { probeAt = null; return attention(AttentionReason.CHALLENGE) }
                    SitePage.SCA -> probeAt = null
                    SitePage.SIGNED_IN -> if (signInOnly) settleSignedIn() else { probeAt = clockMs() + timing.settleMs; probeOnSignedIn = true }
                    SitePage.PROVIDER_OTHER -> if (!fetcher.navigates) { probeAt = clockMs() + timing.settleMs; probeOnSignedIn = false }
                    SitePage.FOREIGN -> Unit
                }
                is LoginEvent.Form -> {
                    probeAt = null
                    val saved = login
                    if (waiting != null) return null
                    if (saved == null) return attention(AttentionReason.SIGN_IN)
                    if (repeats(e.step)) return rejected()
                    filled += e.step
                    onProgress(AutoSyncProgress.SigningIn)
                    driver.fill(e.step, saved)
                }
                LoginEvent.LoginError -> if (login != null && filled.isNotEmpty() && waiting == null) return rejected()
                is LoginEvent.Sca -> {
                    probeAt = null
                    when (e.kind) {
                        ScaKind.PUSH -> {
                            if (!push) { push = true; if (waiting == null) waitStart = clockMs() }
                            onProgress(AutoSyncProgress.Waiting(AttentionReason.SCA_PUSH))
                        }
                        ScaKind.CODE -> if (waiting == null || waiting == AttentionReason.STALLED) return attention(AttentionReason.SCA_CODE)
                        ScaKind.CHOICE -> if (waiting == null || waiting == AttentionReason.STALLED) return attention(AttentionReason.SCA_CHOICE)
                    }
                }
                LoginEvent.Challenge -> { probeAt = null; return attention(AttentionReason.CHALLENGE) }
                is LoginEvent.Captured -> {
                    e.username?.let { capturedUser = it }
                    e.password?.let { capturedPassword = it }
                }
                LoginEvent.Quiet -> Unit
                LoginEvent.Closed -> return AutoSyncResult.Cancelled
                is LoginEvent.Broken -> return AutoSyncResult.Failed(e.message)
            }
            return null
        }

        /** The same fields asked again after they were filled and submitted: the provider refused them. */
        fun repeats(step: LoginStep): Boolean = when (step) {
            LoginStep.USERNAME -> LoginStep.USERNAME in filled || LoginStep.BOTH in filled
            LoginStep.PASSWORD -> LoginStep.PASSWORD in filled || LoginStep.BOTH in filled
            LoginStep.BOTH -> LoginStep.BOTH in filled || LoginStep.PASSWORD in filled
        }

        fun rejected(): AutoSyncResult? {
            vault?.markRejected(provider)
            login = null
            driver.setCapture(remember)
            return attention(AttentionReason.CREDENTIALS_REJECTED)
        }

        /**
         * Visible page: a signed-in page appeared. It goes behind the cover at once (the person never watches the account page),
         * and a later sign-in, 2FA or check page can still be shown ([waiting] is cleared). It counts once it has settled.
         */
        fun settleSignedIn() {
            waiting = null
            since = clockMs()
            driver.cover()
            onProgress(AutoSyncProgress.SigningIn)
            probeAt = clockMs() + timing.settleMs
            probeOnSignedIn = true
        }

        suspend fun probe(): AutoSyncResult? {
            if (++probes > timing.maxProbes) return AutoSyncResult.Failed("Accesso a ${site.label} non confermato. Riprova.")
            if (signInOnly && probeOnSignedIn) {
                // Only a page still signed in after the settle counts (the provider may have moved on to a 2FA step).
                if (site.classify(driver.currentUrl()) != SitePage.SIGNED_IN) return null
                driver.cover()
                return AutoSyncResult.SignedIn(rememberCaptured())
            }
            onProgress(AutoSyncProgress.Reading)
            return when (val r = fetcher.fetch(range, driver, mayNavigate = probeOnSignedIn)) {
                is FetchOutcome.Fetched -> {
                    driver.cover()
                    AutoSyncResult.Synced(r.batch, rememberCaptured())
                }
                // Signed in by the page's own account, yet the data refuses the session: the recorded request is out of date.
                FetchOutcome.SignedOut -> if (probeOnSignedIn) AutoSyncResult.Failed("Accesso a ${site.label} riuscito, ma i dati non sono leggibili: formato da aggiornare.") else null
                FetchOutcome.NotReady -> null
                FetchOutcome.Challenge -> attention(AttentionReason.CHALLENGE)
                is FetchOutcome.Failed -> AutoSyncResult.Failed(r.message)
                is FetchOutcome.NotConfigured -> AutoSyncResult.Failed(r.message)
            }
        }

        /** Keeps a sign-in typed by hand, only now that it has worked and only with "Ricorda l'accesso" on. */
        fun rememberCaptured(): Boolean {
            val v = vault ?: return false
            val user = capturedUser
            val password = capturedPassword
            if (!remember || login != null || user == null || password == null) return false
            return try { v.save(provider, user, password); true } catch (_: Exception) { false } finally { capturedPassword = null }
        }
    }
}
