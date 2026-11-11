package xyz.grtlabs.pilastri.sync.auto

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.isybank.IsybankConnector
import xyz.grtlabs.pilastri.sync.paypal.PaypalConnector
import xyz.grtlabs.pilastri.sync.tr.TrBridgeScript
import xyz.grtlabs.pilastri.sync.tr.TrConfig
import xyz.grtlabs.pilastri.sync.tr.TrConnector
import xyz.grtlabs.pilastri.sync.tr.TrError
import xyz.grtlabs.pilastri.sync.tr.TrSync
import xyz.grtlabs.pilastri.sync.tr.TrUrls
import xyz.grtlabs.pilastri.sync.tr.TrWebBridge

/** How a visible provider page (the activities) ended, for the host. */
sealed interface InteractiveOutcome {
    /**
     * The session is signed in: the page closes and the data is read in the background ([AutoSyncCoordinator.onSignedIn]; the
     * provider state shows Syncing, then Fresh or Failed). [started]: a background run is going now (false: no archive host yet;
     * it starts with the app's next foreground sync).
     */
    data class SignedIn(val started: Boolean) : InteractiveOutcome
    /** The data was applied through the host: nothing to preview. */
    data object Applied : InteractiveOutcome
    /** No host was attached (process restored into the activity): the existing preview route with an [xyz.grtlabs.pilastri.sync.ImportHandoff] token. */
    data class Preview(val token: String) : InteractiveOutcome
    data class NeedsUser(val reason: AttentionReason) : InteractiveOutcome
    data class Failed(val message: String) : InteractiveOutcome
    data object Cancelled : InteractiveOutcome
}

/**
 * Entry point for the host and the connector activities. Everything else in this package is reachable from here.
 *
 * Host (MainActivity / app view model):
 *  - `AutoSyncKit.runAll(context, host)` on app start and in every `onStart` (foreground); debounced to 15 minutes per provider.
 *  - `AutoSyncKit.state(context, provider)` per provider for the screens ([ProviderSyncState]).
 *  - `AutoSyncKit.interactiveIntent(context, provider)` for a [ProviderSyncState.NeedsUser] that needs the page, and for Collegamenti.
 *  - `AutoSyncKit.disconnect(context, provider)`; the "Ricorda l'accesso" switch through [vault].
 *
 * Activities (PayPal, isybank): see [WebLoginDriver] for the WebView hooks, then `AutoSyncKit.newAutoSync(...).signIn(range, driver)`
 * (the visible page is only for signing in) and `AutoSyncKit.finishInteractive(...)` with the result; the data is read in the
 * background by [WebRunner].
 */
object AutoSyncKit {
    private val ROME: ZoneId = ZoneId.of("Europe/Rome")
    @Volatile private var instance: AutoSyncCoordinator? = null

    fun today(): LocalDate = LocalDate.now(ROME)

    fun coordinator(context: Context): AutoSyncCoordinator = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            SyncLog.init(app)
            val vault = vault(app)
            AutoSyncCoordinator(
                runners = listOf(
                    WebRunner(app, ImportProvider.PAYPAL, vault),
                    WebRunner(app, ImportProvider.ISYBANK, vault),
                    TrRunner(app),
                ),
                store = PrefsSecretStore(app.getSharedPreferences("pilastri_autosync", Context.MODE_PRIVATE)),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                today = ::today,
            ).also { instance = it }
        }
    }

    /**
     * App start and every return to the foreground (and after a sign-in, with force). [context] is normally the main activity:
     * it becomes the window that hosts the background pages ([HiddenWebHost]).
     */
    fun runAll(context: Context, host: AutoSyncHost, force: Boolean = false): List<Job> {
        restoreSessions(context)
        val c = coordinator(context)
        HiddenWebHost.install(context)
        c.attach(host)
        return c.runAll(force)
    }

    /** The same with two lambdas instead of a host object. */
    fun runAll(
        context: Context,
        lastCovered: (ImportProvider) -> LocalDate?,
        commit: suspend (ImportBatch) -> Unit,
        force: Boolean = false,
    ): List<Job> = runAll(context, object : AutoSyncHost {
        override fun lastCovered(provider: ImportProvider) = lastCovered(provider)
        override suspend fun commit(batch: ImportBatch) = commit(batch)
    }, force)

    fun state(context: Context, provider: ImportProvider): StateFlow<ProviderSyncState> = coordinator(context).state(provider)

    private val sessionsRestored = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Once per process, before any provider page loads (background run or visible page): re-sets the session cookies the process
     * restart dropped, from the encrypted snapshot. Idempotent; called from every entry point.
     */
    fun restoreSessions(context: Context) {
        if (!sessionsRestored.compareAndSet(false, true)) return
        val snapshots = SessionSnapshots.createOrNull(context) ?: return
        listOf(ProviderSites.PAYPAL, ProviderSites.ISYBANK).filter { it.persistSession }.forEach {
            runCatching { SessionCookiePersistence.restore(it, AndroidCookieJar(), snapshots, SyncLog::d) }
        }
    }

    /** Null when the Keystore is unusable: then do not offer "Ricorda l'accesso". */
    fun vault(context: Context): CredentialVault? = CredentialVault.createOrNull(context)

    /** The page the person uses for sign-in, 2FA codes and checks. */
    fun interactiveIntent(context: Context, provider: ImportProvider): Intent? = when (provider.also { restoreSessions(context) }) {
        ImportProvider.PAYPAL -> PaypalConnector.webIntent(context)
        ImportProvider.ISYBANK -> IsybankConnector.webIntent(context)
        ImportProvider.TRADE_REPUBLIC -> TrConnector.loginIntent(context)
    }

    /** TR's own login activity returned LoggedIn (or any sign-in finished outside [finishInteractive]): sync now. */
    fun onSignedIn(context: Context, provider: ImportProvider): Job? = coordinator(context).onSignedIn(provider)

    /** Forget everything about the provider's access: background runs, cookies, the saved sign-in and the freshness. */
    fun disconnect(context: Context, provider: ImportProvider) {
        val c = coordinator(context)
        c.unlink(provider)
        vault(context)?.forget(provider)
        SessionSnapshots.createOrNull(context)?.forget(provider)
        when (provider) {
            ImportProvider.PAYPAL -> PaypalConnector.disconnect(context)
            ImportProvider.ISYBANK -> IsybankConnector.disconnect(context)
            ImportProvider.TRADE_REPUBLIC -> TrConnector.disconnect(context)
        }
    }

    /** The fetcher for a provider. [userAgent]: the WebView's (the session's) user agent. */
    fun fetcher(provider: ImportProvider, userAgent: String?): SessionFetcher? = when (provider) {
        ImportProvider.PAYPAL -> PaypalActivityReader(today = ::today)
        ImportProvider.ISYBANK -> ProviderSites.ISYBANK.let { HttpSessionFetcher(it, RecordedSpec.ISYBANK, OkHttpSessionHttp(), WebViewSessionCookies(it), userAgent) }
        ImportProvider.TRADE_REPUBLIC -> null
    }

    /** For an activity: the orchestrator over its visible WebView (pass the WebView's user agent). */
    fun newAutoSync(context: Context, provider: ImportProvider, userAgent: String?): AutoSync? =
        fetcher(provider, userAgent)?.let { AutoSync(it, vault(context)) }

    /** For an activity: the driver of its visible WebView. Call [WebLoginDriver.install] before the first load. */
    fun driver(web: WebView, provider: ImportProvider, surface: WebLoginDriver.Surface): WebLoginDriver? =
        ProviderSites.forProvider(provider)?.let { WebLoginDriver(web, it, surface) }

    /** The range an activity asks for: from the last covered date in the archive (or a year back). */
    fun range(lastCovered: LocalDate?): SyncRange = SyncRange.incremental(today(), lastCovered)

    /**
     * Call when an activity's [AutoSync.signIn] ends (the activity calls [AutoSyncCoordinator.cancel] when it opens, so no
     * background run shares the session meanwhile). SignedIn: linked, and the background read starts now. Synced (read over HTTP
     * only): applied through the host. NeedsUser/Failed: recorded in the provider state.
     */
    suspend fun finishInteractive(context: Context, provider: ImportProvider, result: AutoSyncResult): InteractiveOutcome {
        val c = coordinator(context)
        return when (result) {
            is AutoSyncResult.SignedIn -> {
                CookieManager.getInstance().flush()
                ProviderSites.forProvider(provider)?.let { SessionCookiePersistence.persist(it, AndroidCookieJar(), SyncLog::d, snapshots = SessionSnapshots.createOrNull(context)) }
                val job = c.onSignedIn(provider)
                SyncLog.d("${provider.name} visible page: signed in, background read ${if (job != null) "started" else "waits for the app"}")
                InteractiveOutcome.SignedIn(job != null)
            }
            is AutoSyncResult.Synced -> if (c.deliver(provider, result.batch)) InteractiveOutcome.Applied else InteractiveOutcome.Preview(result.handoffToken())
            is AutoSyncResult.NeedsUser -> { c.report(provider, result); InteractiveOutcome.NeedsUser(result.reason) }
            is AutoSyncResult.Failed -> { c.report(provider, result); InteractiveOutcome.Failed(result.message) }
            AutoSyncResult.Cancelled -> { c.report(provider, result); InteractiveOutcome.Cancelled }
        }
    }
}

/**
 * PayPal and isybank in the background: cookies alone when the fetcher allows, else an offscreen WebView with the autofill. The
 * offscreen WebView is attached out of sight under the main screen ([HiddenWebHost]) so the provider page runs as in a browser;
 * in debug builds it is open to DevTools (chrome://inspect) and each step logs one [SyncLog] line.
 */
internal class WebRunner(
    private val context: Context,
    override val provider: ImportProvider,
    private val vault: CredentialVault?,
) : ProviderRunner {
    private val site = ProviderSites.forProvider(provider)!!
    override val label: String get() = site.label

    private companion object {
        /** True once per process: the next run is the first since the process (re)started. */
        val restoreUnchecked = java.util.concurrent.atomic.AtomicBoolean(true)
    }

    override fun hasAccess(): Boolean = vault?.has(provider) == true

    private fun agent(): String? = runCatching { WebLoginDriver.mobileAgent(WebSettings.getDefaultUserAgent(context)) }.getOrNull()

    override suspend fun keepAlive() {
        if (provider != ImportProvider.PAYPAL) return
        withContext(Dispatchers.IO) { keepAlive(site, RecordedSpec.PAYPAL.keepAliveUrl, OkHttpSessionHttp(), WebViewSessionCookies(site), agent()) }
    }

    override suspend fun sync(range: SyncRange, onProgress: (AutoSyncProgress) -> Unit): AutoSyncResult {
        AutoSyncKit.restoreSessions(context)
        val fetcher = AutoSyncKit.fetcher(provider, agent()) ?: return AutoSyncResult.Failed("Fornitore non gestito.")
        val auto = AutoSync(fetcher, vault)
        // The first run of this process after a restored session: say afterwards whether PayPal's server still honoured it.
        val checkRestore = site.persistSession && restoreUnchecked.getAndSet(false)
        val result = syncInner(range, onProgress, fetcher, auto)
        if (checkRestore) SessionCookiePersistence.logRestore(provider.name, result is AutoSyncResult.NeedsUser, SyncLog::d)
        if (site.persistSession && result is AutoSyncResult.Synced) SessionCookiePersistence.persist(site, AndroidCookieJar(), SyncLog::d, snapshots = SessionSnapshots.createOrNull(context))
        return result
    }

    private suspend fun syncInner(range: SyncRange, onProgress: (AutoSyncProgress) -> Unit, fetcher: SessionFetcher, auto: AutoSync): AutoSyncResult {
        if (!fetcher.configured) return AutoSyncResult.Failed("${site.label}: $NOT_CONFIGURED")
        if (!fetcher.needsPage) {
            val quick = auto.syncWithSession(range)
            // Only a saved sign-in is worth a hidden page; without one the person has to sign in anyway.
            if (quick !is AutoSyncResult.NeedsUser || quick.reason != AttentionReason.SIGN_IN || vault?.loadForAutoSignIn(provider) == null) return quick
        }
        return withContext(Dispatchers.Main) {
            // Debuggable apps already allow it; said explicitly so the background page is listed in chrome://inspect.
            val web = WebLoginDriver.offscreenWebView(context)
            val driver = WebLoginDriver(web, site, surface = null)
            web.webViewClient = driver.client()
            driver.install()
            val attached = HiddenWebHost.attach(web)
            SyncLog.d("${provider.name} background page: start (window ${if (attached) "attached" else "not attached yet"})")
            try {
                auto.run(range, driver, onProgress)
            } finally {
                SyncLog.d("${provider.name} background page: end (was attached=${HiddenWebHost.isAttached(web)})")
                driver.dispose()
                HiddenWebHost.detach(web)
                web.stopLoading()
                web.destroy()
                CookieManager.getInstance().flush()
            }
        }
    }
}

/**
 * Trade Republic in the background, with TR's own relay ([TrWebBridge], [TrBridgeScript], [TrSync]) in an offscreen WebView set up
 * like [xyz.grtlabs.pilastri.sync.tr.TrLoginActivity]'s sync mode (desktop agent). No autofill: a signed-out session ends in
 * NeedsUser(SIGN_IN) and TR's login activity handles it.
 */
internal class TrRunner(private val context: Context) : ProviderRunner {
    override val provider = ImportProvider.TRADE_REPUBLIC
    override val label = "Trade Republic"

    override fun hasAccess(): Boolean = runCatching { TrConnector.hasSession(context) }.getOrDefault(false)

    @SuppressLint("JavascriptInterface")
    override suspend fun sync(range: SyncRange, onProgress: (AutoSyncProgress) -> Unit): AutoSyncResult = withContext(Dispatchers.Main) {
        val web = WebLoginDriver.offscreenWebView(context, desktop = true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val current = WebSettingsCompat.getUserAgentMetadata(web.settings)
            WebSettingsCompat.setUserAgentMetadata(web.settings, androidx.webkit.UserAgentMetadata.Builder(current)
                .setMobile(false).setPlatform("Linux").setPlatformVersion("").setModel("").build())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
        val token = UUID.randomUUID().toString()
        val bridge = TrWebBridge(web, token)
        val finished = Channel<String?>(Channel.CONFLATED)
        web.addJavascriptInterface(bridge, TrBridgeScript.INTERFACE)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !TrUrls.isAllowedNavigation(request.url.toString())
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit
            override fun onPageFinished(view: WebView, url: String?) { CookieManager.getInstance().flush(); finished.trySend(url) }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) finished.trySend(null) }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { finished.trySend(null); return true }
        }
        try {
            onProgress(AutoSyncProgress.CheckingSession)
            web.loadUrl(TrUrls.APP_URL)
            val url = withTimeoutOrNull(30_000) { finished.receive() } ?: return@withContext AutoSyncResult.Failed("Impossibile raggiungere Trade Republic. Controlla la connessione.")
            if (signedOut(url)) return@withContext AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN)
            // Let the page's own scripts (WAF token, redirect to the login when signed out) finish, as the activity does.
            delay(1_500)
            if (signedOut(web.url)) return@withContext AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN)
            web.evaluateJavascript(TrBridgeScript.source(token), null)
            onProgress(AutoSyncProgress.Reading)
            val sinceDays = (ChronoUnit.DAYS.between(range.from, range.to) + 1).toInt().coerceIn(1, 3660)
            val batch = withContext(Dispatchers.Default) { TrSync(bridge, TrConfig(sinceDays = sinceDays)).run() }
            AutoSyncResult.Synced(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TrError.SessionExpired) {
            AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN)
        } catch (e: TrError) {
            AutoSyncResult.Failed(e.message ?: "Sincronizzazione con Trade Republic non riuscita.")
        } catch (_: Exception) {
            AutoSyncResult.Failed("Sincronizzazione con Trade Republic non riuscita.")
        } finally {
            bridge.shutdown()
            web.removeJavascriptInterface(TrBridgeScript.INTERFACE)
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }

    private fun signedOut(url: String?) = when (TrUrls.classify(url)) {
        TrUrls.Page.LOGIN, TrUrls.Page.PRE_LOGIN -> true
        else -> false
    }
}
