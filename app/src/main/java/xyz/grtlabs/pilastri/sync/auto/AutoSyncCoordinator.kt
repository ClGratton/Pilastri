package xyz.grtlabs.pilastri.sync.auto

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider

/**
 * What a screen shows for one provider. Never "signed in but not synced": a linked provider is [Fresh], [Syncing] (a thin
 * indicator over the last freshness), [NeedsUser] (one line and an action) or [Failed]. [Idle] means not connected.
 */
sealed interface ProviderSyncState {
    data object Idle : ProviderSyncState
    /** [coverageTo]: data until this date ("dati fino al 31 ott"); null when the provider returned no dated rows yet. */
    data class Fresh(val coverageTo: LocalDate?, val syncedAt: Instant) : ProviderSyncState
    data class Syncing(val last: Fresh?) : ProviderSyncState
    data class NeedsUser(val action: UserAction, val reason: AttentionReason, val line: String, val last: Fresh?) : ProviderSyncState {
        /** True when the provider's page must be opened (sign in, a code, a check); false for an approval in the provider's app. */
        val needsPage: Boolean get() = reason.needsPage
    }
    data class Failed(val message: String, val last: Fresh?) : ProviderSyncState
}

/** The archive side, supplied by the host (MainActivity / the app view model). */
interface AutoSyncHost {
    /** The last date already covered for [provider] in the archive, or null before the first sync. */
    fun lastCovered(provider: ImportProvider): LocalDate?

    /**
     * Applies [batch] at once: `withImportBatch` on the provider's account, creating it on the first sync with its default pillar
     * (PayPal: pillar 1). Movements land PENDING as always. Throw (Italian message) when it cannot be saved.
     */
    suspend fun commit(batch: ImportBatch)

    /** The installment plans of [provider] to read again, and from when the activity list must be read to find the plans' links; none by default. */
    fun knownPlans(provider: ImportProvider): PlanTargets = PlanTargets.NONE
}

/**
 * [refs]: plans whose plan page link is known. [needLinksSince]: the earliest purchase date of an unfinished plan with no link yet; the
 * activity list is read back to there (once a day) to find the link on the plan's own row.
 */
data class PlanTargets(val refs: List<PlanRef>, val needLinksSince: LocalDate?) {
    companion object { val NONE = PlanTargets(emptyList(), null) }
}

/** One provider's way to sync in the background. */
interface ProviderRunner {
    val provider: ImportProvider
    val label: String
    /** There is something to sync with besides a recorded sign-in: a saved login or a session cookie. */
    fun hasAccess(): Boolean
    suspend fun sync(range: SyncRange, onProgress: (AutoSyncProgress) -> Unit): AutoSyncResult
    /** Cheap request that keeps the session warm between syncs; default none. */
    suspend fun keepAlive() {}
}

/**
 * Syncs every connected provider by itself: right after a sign-in, on app start and on every return to the foreground. At most one
 * run per provider at a time, and no more often than [minIntervalMs] unless forced. Results are applied through [AutoSyncHost.commit]
 * with no preview; the per-provider [state] is what screens show.
 *
 * Bookkeeping in [store] (app-private, never the archive): whether a provider was connected, and the last freshness. No secrets.
 */
class AutoSyncCoordinator(
    runners: List<ProviderRunner>,
    private val store: SecretStore,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val today: () -> LocalDate = LocalDate::now,
    private val minIntervalMs: Long = 15 * 60_000L,
    private val firstDays: Int = 365,
) {
    private val runners = runners.associateBy { it.provider }
    private val flows = runners.associate { it.provider to MutableStateFlow(initial(it.provider)) }
    private val jobs = HashMap<ImportProvider, Job>()
    private val attempts = HashMap<ImportProvider, Instant>()
    @Volatile private var host: AutoSyncHost? = null

    val providers: Set<ImportProvider> get() = runners.keys

    fun state(provider: ImportProvider): StateFlow<ProviderSyncState> =
        (flows[provider] ?: MutableStateFlow(ProviderSyncState.Idle)).asStateFlow()

    fun attach(host: AutoSyncHost) { this.host = host }
    fun detach(host: AutoSyncHost) { if (this.host === host) this.host = null }

    fun isLinked(provider: ImportProvider): Boolean =
        store.get(linkedKey(provider)) == "1" || runners[provider]?.hasAccess() == true

    /** App start, foreground, or a pull to refresh ([force]). Returns the runs started. */
    fun runAll(force: Boolean = false): List<Job> = runners.keys.mapNotNull { runOne(it, force) }

    /** Starts [provider]'s sync unless it is running, not connected, without a host, or synced less than [minIntervalMs] ago. */
    fun runOne(provider: ImportProvider, force: Boolean = false): Job? {
        val runner = runners[provider] ?: return null
        val h = host ?: return null
        synchronized(jobs) {
            jobs[provider]?.takeIf { it.isActive }?.let { return it }
            if (!isLinked(provider)) return null
            val last = attempts[provider]
            if (!force && last != null && now().toEpochMilli() - last.toEpochMilli() < minIntervalMs) {
                scope.launch { runCatching { runner.keepAlive() } }
                return null
            }
            attempts[provider] = now()
            val job = scope.launch { execute(runner, h) }
            jobs[provider] = job
            return job
        }
    }

    /** The person opened the provider's page by hand: stop the background run so two pages do not share the session. */
    fun cancel(provider: ImportProvider) {
        synchronized(jobs) { jobs.remove(provider)?.cancel() }
    }

    /** A sign-in completed (Collegamenti or a NeedsUser action): connected from now on, synced at once. */
    fun onSignedIn(provider: ImportProvider): Job? {
        store.put(linkedKey(provider), "1")
        return runOne(provider, force = true)
    }

    /** Disconnect: no more syncs, no freshness shown. The caller also clears cookies and the saved sign-in. */
    fun unlink(provider: ImportProvider) {
        cancel(provider)
        store.remove(linkedKey(provider)); store.remove(freshToKey(provider)); store.remove(freshAtKey(provider))
        synchronized(jobs) { attempts.remove(provider) }
        flows[provider]?.value = ProviderSyncState.Idle
    }

    /**
     * Applies a batch read by a visible page (the first sign-in from Collegamenti syncs there and then hands the result here).
     * False when there is no host to apply it or the save failed (the state then says why).
     */
    suspend fun deliver(provider: ImportProvider, batch: ImportBatch): Boolean {
        val h = host ?: return false
        store.put(linkedKey(provider), "1")
        // A visible page just synced: the foreground run that follows on onStart must not repeat it.
        attempts[provider] = now()
        return apply(provider, batch, h)
    }

    /** The state a NeedsUser result from a visible page leaves behind. */
    fun report(provider: ImportProvider, result: AutoSyncResult) {
        val flow = flows[provider] ?: return
        val last = lastFresh(provider)
        when (result) {
            is AutoSyncResult.NeedsUser -> flow.value = needsUser(provider, result.reason, last)
            is AutoSyncResult.Failed -> flow.value = ProviderSyncState.Failed(result.message, last)
            // SignedIn only ends a visible page (its read follows through onSignedIn); a background run never returns it.
            AutoSyncResult.Cancelled, is AutoSyncResult.SignedIn -> if (flow.value is ProviderSyncState.Syncing) flow.value = last ?: ProviderSyncState.Idle
            is AutoSyncResult.Synced -> Unit
        }
    }

    /**
     * Forgets the freshness shown for [provider] ("dati fino al …"), for a repair of a coverage that was claimed wrongly. The
     * next successful sync sets it again.
     */
    fun forgetFreshness(provider: ImportProvider) {
        store.remove(freshToKey(provider)); store.remove(freshAtKey(provider))
        val flow = flows[provider] ?: return
        flow.value = when (val s = flow.value) {
            is ProviderSyncState.Fresh -> ProviderSyncState.Idle
            is ProviderSyncState.Syncing -> ProviderSyncState.Syncing(null)
            is ProviderSyncState.NeedsUser -> s.copy(last = null)
            is ProviderSyncState.Failed -> s.copy(last = null)
            ProviderSyncState.Idle -> s
        }
    }

    /** One-time repairs of the app's own sync state: true once [id] has been applied (or found unnecessary). */
    fun repairDone(id: String): Boolean = store.get(repairKey(id)) == "1"
    fun markRepairDone(id: String) = store.put(repairKey(id), "1")

    private suspend fun execute(runner: ProviderRunner, h: AutoSyncHost) {
        val p = runner.provider
        val flow = flows.getValue(p)
        val last = lastFresh(p)
        flow.value = ProviderSyncState.Syncing(last)
        val result = try {
            val targets = h.knownPlans(p)
            var range = SyncRange.incremental(today(), h.lastCovered(p), firstDays)
            // Plans with no link yet: read the activity list back to their purchase, at most once a day, to find the link on their row.
            val since = targets.needLinksSince
            val floor = today().minusDays(firstDays - 1L)
            if (since != null && store.get(linksKey(p)) != today().toString()) {
                val from = since.minusDays(3).let { if (it.isBefore(floor)) floor else it }
                if (from.isBefore(range.from)) { range = SyncRange(from, range.to); store.put(linksKey(p), today().toString()) }
            }
            range = range.copy(plans = targets.refs)
            runner.sync(range) { progress ->
                flow.value = if (progress is AutoSyncProgress.Waiting) needsUser(p, progress.reason, last) else ProviderSyncState.Syncing(last)
            }
        } catch (e: CancellationException) {
            flow.value = last ?: ProviderSyncState.Idle
            throw e
        } catch (_: Exception) {
            AutoSyncResult.Failed("Sincronizzazione con ${runner.label} non riuscita.")
        }
        SyncLog.d("${p.name} background run: " + when (result) {
            is AutoSyncResult.Synced -> "Synced movements=${result.batch.movements.size} plans=${result.batch.installmentPlans.size} coverage=${result.batch.coverageTo != null}"
            is AutoSyncResult.NeedsUser -> "NeedsUser ${result.reason}"
            else -> result.javaClass.simpleName
        })
        when (result) {
            is AutoSyncResult.Synced -> apply(p, result.batch, h)
            else -> report(p, result)
        }
    }

    private suspend fun apply(provider: ImportProvider, batch: ImportBatch, h: AutoSyncHost): Boolean {
        val flow = flows[provider] ?: return false
        val last = lastFresh(provider)
        try {
            h.commit(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flow.value = ProviderSyncState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Dati letti ma non salvati.", last)
            return false
        }
        val to = listOfNotNull(batch.coverageTo, last?.coverageTo).maxOrNull()
        val fresh = ProviderSyncState.Fresh(to, now())
        if (to != null) store.put(freshToKey(provider), to.toString()) else store.remove(freshToKey(provider))
        store.put(freshAtKey(provider), fresh.syncedAt.toString())
        flow.value = fresh
        return true
    }

    private fun needsUser(p: ImportProvider, reason: AttentionReason, last: ProviderSyncState.Fresh?) =
        ProviderSyncState.NeedsUser(reason.action, reason, reason.line(runners[p]?.label ?: p.label), last)

    private fun lastFresh(p: ImportProvider): ProviderSyncState.Fresh? {
        val at = store.get(freshAtKey(p))?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return ProviderSyncState.Fresh(store.get(freshToKey(p))?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, at)
    }

    private fun initial(p: ImportProvider): ProviderSyncState = lastFresh(p) ?: ProviderSyncState.Idle

    private fun linkedKey(p: ImportProvider) = "linked.${p.name}"
    private fun linksKey(p: ImportProvider) = "plan.links.${p.name}"
    private fun freshToKey(p: ImportProvider) = "fresh.to.${p.name}"
    private fun freshAtKey(p: ImportProvider) = "fresh.at.${p.name}"
    private fun repairKey(id: String) = "repair.$id"
}
