package xyz.grtlabs.pilastri.ui

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.grtlabs.pilastri.ai.AssistantState
import xyz.grtlabs.pilastri.ai.AutoCategorizer
import xyz.grtlabs.pilastri.ai.CategoryRepairChange
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.sync.ConnectorResult
import xyz.grtlabs.pilastri.sync.auto.AutoSyncHost
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.auto.CoverageRepair
import xyz.grtlabs.pilastri.sync.auto.ProviderSyncState

/** A transient message at the bottom of the screen, with at most one action. */
data class ToastMsg(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

/** An import waiting for the user's confirmation (preview, then account choice). */
data class PendingImport(val batch: ImportBatch)

data class PendingPrefill(val items: List<PlanItem>, val facts: PrefillFacts)

/** What the root registers with the Android result APIs; screens only call these. */
class Launchers(
    val exportBackup: () -> Unit = {},
    val restoreBackup: () -> Unit = {},
    val importPrefill: () -> Unit = {},
    /** The provider's own page ([AutoSyncKit.interactiveIntent]): the first link, or a step the sync needs from the person. */
    val signIn: (ImportProvider) -> Unit = {},
    val isybankFile: () -> Unit = {},
    val paypalFile: () -> Unit = {},
    val gmailScan: () -> Unit = {},
    val dictate: ((String) -> Unit) -> Unit = {},
)

/**
 * The single owner of the archive in the UI: loading, serialized saves (one at a time, validated before anything is
 * written), navigation and transient messages. Screens read [archive] and call [commit].
 */
@Stable
class App(private val context: Context, private val storage: LocalArchive, val scope: CoroutineScope) {
    var archive by mutableStateOf<Archive?>(null)
    var loadError by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var nav by mutableStateOf(NavState())
    var spendMonth by mutableStateOf(YearMonth.now())
    /** Capture seam only (CaptureHook route "spese-single"): the Spese strip shows one window, as for a first month. Always false in production. */
    internal var captureSingleWindow by mutableStateOf(false)
    var toast by mutableStateOf<ToastMsg?>(null)
    var busy by mutableStateOf(false)
    var twoPane by mutableStateOf(false)
    /**
     * The Agente panel: over the right pane on the open Fold, a full page on one pane. Agente is an action, never a
     * section: the island's pill stays on [NavState.tab] while it is open, and folding or unfolding keeps it open.
     */
    var agentOpen by mutableStateOf(false)
        private set
    /** The panel's morph progress 0..1, read only in draw and layout (the panel rect, the left-pane tint). */
    val agentMorph = androidx.compose.animation.core.Animatable(0f)
    /** What to do once the reverse morph has settled (Impostazioni tapped while the panel was open). */
    private var afterAgentClose: (() -> Unit)? = null
    var preview by mutableStateOf<PendingImport?>(null)
    var restoreCandidate by mutableStateOf<Archive?>(null)
    var prefillCandidate by mutableStateOf<PendingPrefill?>(null)
    var notice by mutableStateOf<Pair<String, String>?>(null)
    var gmailBusy by mutableStateOf(false)
    var launchers by mutableStateOf(Launchers())
    val assistant = AssistantState()
    var hx: Hx? = null
    val dictationAvailable: Boolean by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }
    private val mutex = Mutex()

    val today: LocalDate get() = LocalDate.now()

    /** The read models of the top destinations for the current archive version, filled off the main thread by AppRoot. */
    internal var homeModel by mutableStateOf<xyz.grtlabs.pilastri.ui.screens.HomeModel?>(null)
    /** The model of [a] for today; computed inline only when the background one is not ready yet (then it is the same code, just late). */
    internal fun home(a: Archive): xyz.grtlabs.pilastri.ui.screens.HomeModel =
        homeModel?.takeIf { it.source === a && it.today == today } ?: xyz.grtlabs.pilastri.ui.screens.HomeModel(a, today)

    suspend fun load() {
        try { archive = withContext(Dispatchers.IO) { storage.load() } }
        catch (_: Exception) { loadError = "Impossibile leggere i dati locali. Non verranno sovrascritti: puoi ripristinare un backup." }
        loaded = true
        maintainCategories()
    }

    /**
     * Once per load (2026-11-01 category rule): removes the payment-method "categories" the assistant created, as one quiet
     * Agente line with "Annulla" (CategoryRepair), and seeds the generic default categories in a set-up archive that has
     * none. Both are idempotent; a failure changes nothing and stays silent.
     */
    private suspend fun maintainCategories() {
        val current = archive ?: return
        if (current.accounts.isEmpty()) return
        val repair = try { withContext(Dispatchers.Default) { CategoryRepairChange.prepare(current) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val seed = current.planning.categories.none { it.kind == CategoryKind.SPEND }
        if (repair == null && !seed) return
        val before = archive?.externalMovements?.filter { it.categorySource == CategorySource.AGENT }?.map { it.id }?.toSet().orEmpty()
        val ok = try {
            commitNow(quiet = true) { latest -> (repair?.applyTo(latest) ?: latest).withDefaultSpendCategories(Instant.now().toString()) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!ok || repair == null) return
        val after = archive ?: return
        val reset = after.externalMovements.filter { it.id in before && it.categoryId == null }.map { it.id }
        if (reset.isEmpty()) return
        assistant.showAuto(repair, reset.size, repair.summary)
        // Back in the automatic run's queue, except installment parts (never sent).
        val parts = after.installmentPaymentIds()
        runCatching { AutoCategorizer.forget(context, reset.filterNot { it in parts }) }
    }

    /**
     * Applies [transform] to the latest archive and saves it atomically. A transform that throws is refused with its
     * message (unless [quiet]); a storage failure keeps the previous data. Writes are serialized.
     */
    suspend fun commitNow(quiet: Boolean = false, recover: Boolean = false, keepCopy: Boolean = false, transform: (Archive) -> Archive): Boolean = mutex.withLock {
        val current = archive ?: if (recover) Archive() else return false
        val next = try { transform(current) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (!quiet) { toast = ToastMsg(e.message ?: "Modifica non valida."); hx?.reject() }
            if (quiet) throw e
            return false
        }
        if (next == current && !recover) return true
        busy = true
        try {
            withContext(Dispatchers.IO) { if (keepCopy) storage.keepCopyBeforeReplace(); if (recover) storage.restoreAfterReadFailure(next) else storage.save(next) }
            archive = next; loadError = null
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            toast = ToastMsg("Salvataggio non riuscito. I dati precedenti sono conservati."); hx?.reject()
            false
        } finally { busy = false }
    }

    /** Fire-and-forget commit with a confirmation message and the "confirm" haptic on success. */
    fun commit(done: String? = null, then: (() -> Unit)? = null, transform: (Archive) -> Archive) {
        scope.launch {
            if (commitNow(transform = transform)) { hx?.confirm(); if (done != null) toast = ToastMsg(done); then?.invoke() }
        }
    }

    /** The assistant's commit: refusals are thrown back so its own card shows why. */
    val aiCommit: suspend ((Archive) -> Archive) -> Boolean = { t -> try { commitNow(quiet = true, transform = t) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw e } }

    fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) { toast = ToastMsg(text, action, onAction) }

    /* Navigation */
    fun go(route: Route) { nav = nav.push(route) }
    fun back() { nav = nav.back() ?: nav }
    fun tab(t: Tab) {
        if (agentOpen) closeAgent()
        if (nav.tab != t) { hx?.tick(); nav = nav.select(t) }
    }

    fun openAgent() { afterAgentClose = null; agentOpen = true }
    /** Closes the panel (the reverse morph); [then] runs once it has settled, or now when it is already closed. */
    fun closeAgent(then: (() -> Unit)? = null) {
        if (!agentOpen) { then?.invoke(); return }
        afterAgentClose = then
        agentOpen = false
    }
    fun toggleAgent() { if (agentOpen) closeAgent() else openAgent() }
    /** Called by the panel host when the reverse morph has settled. */
    fun agentClosed() { val next = afterAgentClose; afterAgentClose = null; next?.invoke() }

    /** Impostazioni's own corner button: closes the Agente panel first, then pushes Impostazioni like any detail. */
    fun openSettings() = closeAgent { nav = nav.openSettings() }

    /** Folding and unfolding keep one logical place: the same stack, and the Agente panel stays open as a panel or a page. */
    fun onPanes(two: Boolean) { twoPane = two }

    /** From the left pane on two panes: the first level is replaced in place; on one pane it is pushed. */
    fun select(route: Route) {
        nav = when {
            !twoPane -> nav.push(route)
            nav.depth.isEmpty() -> nav.push(route)
            nav.depth.size == 1 && nav.depth.first() == route -> nav
            else -> nav.reset(route)
        }
    }

    /** Opens a route in another tab (for example the review queue from the home feed on two panes keeps Pilastri). */
    fun openIn(t: Tab, route: Route?) {
        val n = nav.select(t)
        nav = if (route == null) n else if (n.depth.lastOrNull() == route) n else n.push(route)
    }

    /** Hands a question to the assistant and switches to Agente. */
    fun ask(question: String, contextLabel: String) {
        assistant.draft = question
        assistant.draftContext = contextLabel
        openAgent()
    }

    /* Connectors */
    fun onConnector(provider: ImportProvider, result: ConnectorResult) {
        when (result) {
            // A file (picker or a download captured on the provider's page): the preview, as before.
            is ConnectorResult.Imported -> preview = PendingImport(result.batch)
            // Signing in is never the end of the flow: the sync starts at once (AGENTS.md: sync is automatic and invisible).
            ConnectorResult.LoggedIn -> if (provider == ImportProvider.TRADE_REPUBLIC) {
                lastAdded.remove(provider)
                AutoSyncKit.onSignedIn(context, provider).reportWhenDone(provider)
            }
            ConnectorResult.SessionExpired -> {
                commit { it.withConnectionResult(provider, "Sessione scaduta") }
                say("Sessione ${provider.label} scaduta.", "Ricollega") { signIn(provider) }
            }
            is ConnectorResult.Failed -> notice = "${provider.label}: importazione non riuscita" to result.message
            ConnectorResult.Cancelled -> {}
        }
    }

    /** "Ricollega" / "Sincronizza" on other screens: the provider's page when the person is needed, otherwise a sync now. */
    fun reconnect(provider: ImportProvider) {
        val needsPage = (AutoSyncKit.state(context, provider).value as? ProviderSyncState.NeedsUser)?.needsPage == true
        if (needsPage || !AutoSyncKit.coordinator(context).isLinked(provider)) signIn(provider) else refresh(provider)
    }

    /* Automatic sync (AGENTS.md: connectors work on their own; sync is automatic and invisible) */

    /** New movements saved by the last automatic sync per provider; read once, only after a run the person started. */
    private val lastAdded = ConcurrentHashMap<ImportProvider, Int>()

    /** The provider's own page: the first link, or the step the sync needs from the person (sign-in, code, check). */
    fun signIn(provider: ImportProvider) {
        lastAdded.remove(provider)
        launchers.signIn(provider)
    }

    /** "Aggiorna": a sync now, past the 15-minute pause. */
    fun refresh(provider: ImportProvider) {
        lastAdded.remove(provider)
        AutoSyncKit.coordinator(context).runOne(provider, force = true).reportWhenDone(provider)
    }

    private fun Job?.reportWhenDone(provider: ImportProvider) {
        this?.invokeOnCompletion { scope.launch { reportSynced(provider) } }
    }

    /**
     * After a run the person started (the provider page closed after saving, "Aggiorna", TR's sign-in): one message with
     * what arrived. Nothing when nothing was saved; the provider's state line in Collegamenti then says why.
     */
    fun reportSynced(provider: ImportProvider) {
        val added = lastAdded.remove(provider) ?: return
        if (added > 0) say("${provider.label}: $added nuovi movimenti da rivedere", "Rivedi") { go(Route.Review) }
        else say("${provider.label}: nessun movimento nuovo")
    }

    /**
     * The automatic sync's save ([ArchiveSyncHost.commit]): at once, with no preview, into the provider's account (created on
     * the first sync, see [applySyncedBatch]). Movements still land in "Da rivedere". It is never cancelled half-way, so the
     * archive in memory and on disk cannot diverge. Throws (Italian) when nothing was saved.
     */
    suspend fun applySynced(batch: ImportBatch) = withContext(NonCancellable) {
        var added = 0
        val ok = commitNow(quiet = true) { cur -> applySyncedBatch(cur, batch, nowStamp()).also { added = it.added }.archive }
        if (!ok) throw IllegalStateException(if (archive == null) "Dati locali non leggibili: movimenti non salvati." else "Dati letti ma non salvati.")
        lastAdded[batch.provider] = added
        // Slice 1b: with a linked assistant, the new spending is categorized after the save, never inside it. It runs on the
        // assistant's scope and writes through the App bound at that moment (an activity recreation replaces this one).
        if (added > 0) AutoCategorizer.afterSync(context, assistant.scope,
            archive = { ArchiveSyncHost.bound()?.archive },
            commit = { t -> ArchiveSyncHost.bound()?.aiCommit?.invoke(t) ?: false },
            show = { change, count -> ArchiveSyncHost.bound()?.assistant?.showAuto(change, count) })
    }

    fun loadSample() {
        val build = SampleHook.build ?: return
        scope.launch {
            val ok = try { commitNow(recover = archive == null) { current -> require(current.isEmpty) { "Ci sono già dati: gli esempi si caricano solo su un archivio vuoto." }; build(LocalDate.now()) } }
                catch (e: Exception) { say(e.message ?: "Dati di esempio non disponibili."); false }
            if (ok) { hx?.confirm(); say("Caricati i dati di esempio (sintetici).") }
        }
    }

    fun clearSample() = commit("Dati di esempio rimossi.") { current -> require(current.isSample) { "L'archivio non contiene solo dati di esempio." }; Archive() }

    fun nowStamp(): String = Instant.now().toString()
}

/**
 * The archive side of the automatic sync, process-wide ([AutoSyncKit.runAll]). Batches are saved through the [App] that is
 * current when they arrive: an activity recreation makes a new one, and a run that outlives the old one must not write
 * through it. [AppRoot] binds the App once its archive is loaded; a batch arriving in between waits for that.
 */
object ArchiveSyncHost : AutoSyncHost {
    private val current = MutableStateFlow<App?>(null)

    fun bind(app: App) { current.value = app }
    fun unbind(app: App) { current.compareAndSet(app, null) }
    /** The App batches are saved through now (the automatic categorization writes through it too). */
    internal fun bound(): App? = current.value

    /**
     * App start (once the archive is loaded) and every return to the foreground; each provider at most every 15 minutes.
     * Nothing runs while the local archive is unreadable: what arrived could not be saved.
     */
    fun foreground(context: Context) {
        val app = current.value ?: return
        if (app.archive == null) return
        if (!repairEmptyPaypalCoverage(context, app)) AutoSyncKit.runAll(context, this)
    }

    /**
     * ONE-TIME REPAIR (2026-10-31, [CoverageRepair]): a PayPal sync that read nothing claimed a year of coverage. Before the
     * first sync of this version, such a claim is cleared in the archive and the freshness shown is forgotten, so the next sync
     * reads the whole year again. True when the repair was started; the sync then runs once it is saved. Never runs twice.
     */
    private fun repairEmptyPaypalCoverage(context: Context, app: App): Boolean {
        val sync = AutoSyncKit.coordinator(context)
        if (sync.repairDone(CoverageRepair.PAYPAL_EMPTY)) return false
        val archive = app.archive ?: return false
        if (!CoverageRepair.needed(archive)) { sync.markRepairDone(CoverageRepair.PAYPAL_EMPTY); return false }
        app.scope.launch {
            val saved = try { app.commitNow(quiet = true) { CoverageRepair.repaired(it) } } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (saved) {
                sync.forgetFreshness(ImportProvider.PAYPAL)
                sync.markRepairDone(CoverageRepair.PAYPAL_EMPTY)
            }
            AutoSyncKit.runAll(context, this@ArchiveSyncHost)
        }
        return true
    }

    override fun lastCovered(provider: ImportProvider): LocalDate? =
        current.value?.archive?.connections?.filter { it.provider == provider }?.mapNotNull { it.coverageTo }?.maxOrNull()

    /**
     * PayPal installment plans to read from their own plan page: the unfinished ones (an open part, parts not all seen, or the page not
     * read yet) the user has not ignored. A plan with a recorded link is read again from it; one without waits for its row to be found
     * (the activity list is then read back to its purchase date, [xyz.grtlabs.pilastri.sync.auto.AutoSyncCoordinator]).
     */
    override fun knownPlans(provider: ImportProvider): xyz.grtlabs.pilastri.sync.auto.PlanTargets {
        if (provider != ImportProvider.PAYPAL) return xyz.grtlabs.pilastri.sync.auto.PlanTargets.NONE
        val plans = current.value?.archive?.installments.orEmpty().filter { p ->
            p.review != PlanReview.IGNORED && p.currency == "EUR" &&
                (p.id.startsWith("paypal-web:") || p.aliases.any { it.startsWith("paypal-web:") } || p.planPageToken != null) &&
                (!p.fromPlanPage || p.active || p.count == null)
        }
        val refs = plans.mapNotNull { p -> p.planPageToken?.let(PlanPageLink::decode)?.let { xyz.grtlabs.pilastri.sync.auto.PlanRef(p.id, it) } }
        val missing = plans.filter { it.planPageToken == null }.mapNotNull { it.purchaseDate }.minOrNull()
        return xyz.grtlabs.pilastri.sync.auto.PlanTargets(refs, missing)
    }

    override suspend fun commit(batch: ImportBatch) {
        // A recreated activity loads its archive within a second or two; longer means the app is gone for now.
        val app = withTimeoutOrNull(5_000) { current.filterNotNull().first() }
            ?: throw IllegalStateException("Dati letti ma non salvati: riapri l'app.")
        app.applySynced(batch)
    }
}

const val TR_CASH_ACCOUNT = "Trade Republic · liquidità"
const val TR_SECURITIES_ACCOUNT = "Trade Republic · titoli"

/**
 * Where an automatic sync lands, without a question (the sync is invisible):
 *  1. the account the provider's connection already uses (its securities account comes with it);
 *  2. otherwise an account the person already made for the provider (so its balance is not counted twice): for PayPal and
 *     isybank one named after it, exactly or the only one containing the name (PayPal: in Liquidità only); for Trade
 *     Republic the accounts whose name contains it (one holds everything; with two, the Lungo termine one takes the securities);
 *  3. otherwise new accounts: PayPal and isybank in Liquidità; Trade Republic's cash in Emergenze and its securities in
 *     Lungo termine.
 * Pure, so it is unit-tested.
 */
internal fun applySyncedBatch(archive: Archive, batch: ImportBatch, at: String): ImportOutcome {
    val p = batch.provider
    val known = archive.connections.filter { c -> c.provider == p && archive.accounts.any { it.id == c.accountId } }.maxByOrNull { it.lastSyncAt ?: "" }
    if (known != null) return archive.withImportBatch(batch, known.accountId, at)
    var arch = archive
    fun create(name: String, pillar: Int): String = arch.withNewAccount(name, pillar, "${p.label} · collegamento").also { arch = it.first }.second
    return when (p) {
        ImportProvider.TRADE_REPUBLIC -> {
            val named = arch.accounts.filter { it.name.contains(p.label, ignoreCase = true) }
            when (named.size) {
                0 -> { val cash = create(TR_CASH_ACCOUNT, 2); val securities = create(TR_SECURITIES_ACCOUNT, 4); arch.withImportBatch(batch, cash, at, securities) }
                1 -> arch.withImportBatch(batch, named[0].id, at)
                else -> {
                    val cash = named.firstOrNull { it.name.equals(TR_CASH_ACCOUNT, true) } ?: named.firstOrNull { it.pillar != 4 } ?: named[0]
                    val securities = named.firstOrNull { it.id != cash.id && (it.name.equals(TR_SECURITIES_ACCOUNT, true) || it.pillar == 4) }
                    arch.withImportBatch(batch, cash.id, at, securities?.id)
                }
            }
        }
        ImportProvider.PAYPAL, ImportProvider.ISYBANK -> {
            // PayPal only in Liquidità, as in the import preview (its spending counts only there).
            val pool = if (p == ImportProvider.PAYPAL) arch.accounts.filter { it.pillar == 1 } else arch.accounts
            val existing = pool.firstOrNull { it.name.equals(p.label, ignoreCase = true) }
                ?: pool.filter { it.name.contains(p.label, ignoreCase = true) }.singleOrNull()
            // The target first: create() replaces [arch], which must be read after it.
            val target = existing?.id ?: create(p.label, 1)
            arch.withImportBatch(batch, target, at)
        }
    }
}
