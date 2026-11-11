package xyz.grtlabs.pilastri.sync

import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.auto.AttentionReason
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.auto.AutoSyncProgress
import xyz.grtlabs.pilastri.sync.auto.AutoSyncResult
import xyz.grtlabs.pilastri.sync.auto.InteractiveOutcome
import xyz.grtlabs.pilastri.sync.auto.ProviderSites
import xyz.grtlabs.pilastri.sync.auto.SitePage
import xyz.grtlabs.pilastri.sync.auto.WebLoginDriver
import xyz.grtlabs.pilastri.ui.ArchiveSyncHost
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.fadeThrough

/**
 * The sign-in inside a View-based provider page (PayPal, isybank): a progress cover over the WebView, the
 * [WebLoginDriver.Surface] that shows the page only when the person must act (sign-in, 2FA, a check), and the run itself
 * ([AutoSyncKit], [xyz.grtlabs.pilastri.sync.auto.AutoSync.signIn]). The page is never used to read data: once the session is
 * signed in the run ends with [InteractiveOutcome.SignedIn], the activity closes with its page-out slide, and the data is read
 * in the background offscreen (AGENTS.md: the provider page is visible only for signing in, 2FA or a challenge).
 *
 * The activity owns its WebView, WebViewClient, host allowlist and download capture. It forwards the client callbacks here
 * ([onPageStarted], [onPageFinished], [onUrlChanged], [onLoadFailed], [onBroken]), calls [install] before anything loads,
 * [start] once, [close] on Back and [dispose] in onDestroy, and receives the end of the run in [onDone]:
 * [InteractiveOutcome.SignedIn] / Applied / Preview / Cancelled end the page; NeedsUser and Failed stay on it (shown here, with
 * the page usable by hand, its download capture as the fallback).
 *
 * When the sync cannot read the provider yet ([automatic] false: the request format is not recorded), the page is the manual
 * route of before: the sign-in page with [manualHint], nothing injected.
 */
class ProviderSyncPage(
    private val activity: ComponentActivity,
    private val provider: ImportProvider,
    private val chrome: WebChrome,
    private val web: WebView,
    private val manualHint: WebStatus,
    private val onDone: (InteractiveOutcome) -> Unit,
) : WebLoginDriver.Surface {
    private val site = ProviderSites.forProvider(provider) ?: error("Fornitore senza pagina di accesso.")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var driver: WebLoginDriver? = null
    private var job: Job? = null
    private var ended: InteractiveOutcome? = null

    val automatic: Boolean = isAutomatic(provider)

    /** The cover hides the page while the app checks the session or signs in by itself, and once the session is signed in. */
    var covered by mutableStateOf(automatic); private set
    private var step by mutableStateOf("Controllo dell'accesso")

    val running: Boolean get() = job?.isActive == true

    /** The cover, to stack over the WebView (same size). It takes touches only while shown. */
    fun coverView(): View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { WebChromeTheme { Cover() } }
    }

    /** Before anything loads: the driver's bridge and sign-in script for the provider's exact login origins. */
    fun install() {
        if (!automatic || driver != null) return
        driver = AutoSyncKit.driver(web, provider, this)!!.also { it.install() }
        hideWebFromAccessibility(true)
    }

    /** Runs the sign-in over this page (again, after a NeedsUser end, when the person has signed in by hand). */
    fun start() {
        val d = driver
        if (d == null) {
            // Manual route: the provider's sign-in page; a download the person starts there is captured by the activity.
            covered = false
            chrome.hint = manualHint
            web.loadUrl(site.loginUrl)
            return
        }
        if (running) return
        ended = null
        chrome.status = null
        chrome.closeLabel = "Annulla"
        chrome.closeCancels = true
        job = scope.launch {
            val result = try {
                AutoSyncKit.newAutoSync(activity, provider, web.settings.userAgentString)
                    ?.signIn(AutoSyncKit.range(ArchiveSyncHost.lastCovered(provider)), d) { render(it) }
                    ?: AutoSyncResult.Failed("Lettura automatica di ${site.label} non disponibile.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AutoSyncResult.Failed("Sincronizzazione con ${site.label} non riuscita.")
            }
            // Saving must not stop half-way if the page closes meanwhile.
            val outcome = withContext(NonCancellable) { AutoSyncKit.finishInteractive(activity, provider, result) }
            end(outcome)
        }
    }

    /** Back or the bar's cross: the run ends as Cancelled; the activity closes at once. */
    fun close() { driver?.onClosed() }

    /** A download the person started takes over: the run stops without an outcome, and the page stays in view. */
    fun stop() {
        job?.cancel()
        uncover()
        chrome.closeCancels = false
        chrome.closeLabel = "Torna a Pilastri"
    }

    /** onDestroy, before the WebView is destroyed. */
    fun dispose() {
        scope.cancel()
        driver?.dispose()
        driver = null
    }

    // ---- WebViewClient, forwarded by the activity (main thread) ----

    fun onPageStarted(url: String?) { driver?.onPageStarted(url) }

    fun onPageFinished(url: String?) {
        driver?.onPageFinished(url)
        resumeAfterManualSignIn(url)
    }

    fun onUrlChanged(url: String?) {
        driver?.onUrlChanged(url)
        resumeAfterManualSignIn(url)
    }

    fun onLoadFailed() { driver?.onLoadFailed() }

    fun onBroken() { driver?.onBroken() }

    /** The run gave up waiting for the person (NeedsUser), who then signed in on the page anyway: read the data now. */
    private fun resumeAfterManualSignIn(url: String?) {
        if (!running && ended is InteractiveOutcome.NeedsUser && site.classify(url) == SitePage.SIGNED_IN) start()
    }

    // ---- the run ----

    private fun render(p: AutoSyncProgress) {
        when (p) {
            AutoSyncProgress.CheckingSession -> step = "Controllo dell'accesso"
            AutoSyncProgress.SigningIn -> step = "Accesso in corso"
            AutoSyncProgress.Reading -> step = "Lettura dei movimenti"
            // An approval in the provider's app keeps the cover; a step on the page is shown by reveal().
            is AutoSyncProgress.Waiting -> if (!p.reason.needsPage) {
                val line = p.reason.line(site.label)
                step = line
                if (!covered) chrome.status = WebStatus.busy(line)
            }
        }
    }

    private fun end(outcome: InteractiveOutcome) {
        ended = outcome
        when (outcome) {
            is InteractiveOutcome.NeedsUser -> stay(outcome.reason.line(site.label))
            is InteractiveOutcome.Failed -> stay(outcome.message)
            // The page stays covered while it slides out; the reading goes on in the app.
            is InteractiveOutcome.SignedIn -> { cover(); step = "Accesso riuscito" }
            else -> Unit
        }
        onDone(outcome)
    }

    /** The run ended without data: say why, and leave the page to the person (download capture still works). */
    private fun stay(line: String) {
        uncover()
        chrome.closeCancels = false
        chrome.closeLabel = "Torna a Pilastri"
        chrome.status = WebStatus.blocked(line)
    }

    // ---- WebLoginDriver.Surface (main thread) ----

    override fun reveal(reason: AttentionReason) {
        uncover()
        chrome.status = when (reason) {
            AttentionReason.SIGN_IN -> WebStatus.hint("Accedi a ${site.label}")
            AttentionReason.CREDENTIALS_REJECTED, AttentionReason.CHALLENGE -> WebStatus.blocked(reason.line(site.label))
            AttentionReason.SCA_PUSH -> WebStatus.busy(reason.line(site.label))
            else -> WebStatus.hint(reason.line(site.label))
        }
    }

    override fun cover() {
        covered = true
        chrome.status = null
        hideWebFromAccessibility(true)
    }

    private fun uncover() {
        covered = false
        hideWebFromAccessibility(false)
    }

    private fun hideWebFromAccessibility(hide: Boolean) {
        web.importantForAccessibility = if (hide) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    @Composable
    private fun Cover() {
        val pal = LocalPal.current
        val reduce = LocalReduceMotion.current
        AnimatedVisibility(covered, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects()), label = "sync-cover") {
            // Surface swallows touches, so the covered page cannot be tapped by accident.
            Surface(Modifier.fillMaxSize(), color = pal.bg) {
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(step, transitionSpec = fadeThrough(reduce), label = "sync-step") { s ->
                            Text(s, style = Type.title, color = pal.ink, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = pal.fill, trackColor = pal.track,
                            strokeCap = StrokeCap.Round, gapSize = 0.dp)
                    }
                }
            }
        }
    }

    companion object {
        /** True when the automatic sync can read [provider] (its request format is recorded). */
        fun isAutomatic(provider: ImportProvider): Boolean = AutoSyncKit.fetcher(provider, null)?.configured == true
    }
}
