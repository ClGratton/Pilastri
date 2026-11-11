package xyz.grtlabs.pilastri.sync.auto

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider

class AutoSyncCoordinatorTest {
    private class FakeRunner(override val provider: ImportProvider, var access: Boolean = false) : ProviderRunner {
        override val label = provider.label
        var syncs = 0
        var keepAlives = 0
        var ranges = ArrayList<SyncRange>()
        var gate: CompletableDeferred<Unit>? = null
        var before: ((AutoSyncProgress) -> Unit) -> Unit = {}
        var result: () -> AutoSyncResult = { AutoSyncResult.Synced(T.batch(provider)) }
        override fun hasAccess() = access
        override suspend fun keepAlive() { keepAlives++ }
        override suspend fun sync(range: SyncRange, onProgress: (AutoSyncProgress) -> Unit): AutoSyncResult {
            syncs++
            ranges += range
            before(onProgress)
            gate?.await()
            return result()
        }
    }

    private class Host : AutoSyncHost {
        val committed = ArrayList<ImportBatch>()
        var covered: LocalDate? = null
        var fail: String? = null
        override fun lastCovered(provider: ImportProvider) = covered
        override suspend fun commit(batch: ImportBatch) { fail?.let { throw IllegalStateException(it) }; committed += batch }
    }

    private var clock = Instant.parse("2026-10-31T09:00:00Z")
    private val store = MemorySecretStore()
    private val paypal = FakeRunner(ImportProvider.PAYPAL)
    private val isy = FakeRunner(ImportProvider.ISYBANK)
    private val host = Host()

    private fun coordinator(scope: CoroutineScope) =
        AutoSyncCoordinator(listOf(paypal, isy), store, scope, now = { clock }, today = { T.today }).also { it.attach(host) }

    @Test fun onlyConnectedProvidersSyncAndTheResultIsAppliedAtOnce() = runBlocking {
        val c = coordinator(this)
        paypal.access = true
        c.runAll().joinAll()
        assertEquals(1, paypal.syncs)
        assertEquals(0, isy.syncs)
        assertEquals(listOf(ImportProvider.PAYPAL), host.committed.map { it.provider })
        assertEquals(ProviderSyncState.Fresh(T.today, clock), c.state(ImportProvider.PAYPAL).value)
        assertEquals(ProviderSyncState.Idle, c.state(ImportProvider.ISYBANK).value)
        // First sync: a year back; later ones from the last covered date with a week of overlap.
        assertEquals(LocalDate.of(2025, 11, 1), paypal.ranges[0].from)
    }

    @Test fun runsAreDebouncedUnlessForcedAndKeepTheSessionWarm() = runBlocking {
        val c = coordinator(this)
        paypal.access = true
        c.runAll().joinAll()
        clock = clock.plusSeconds(10 * 60)
        assertTrue(c.runAll().isEmpty())
        yield()
        assertEquals(1, paypal.syncs)
        assertEquals(1, paypal.keepAlives)
        c.runAll(force = true).joinAll()
        assertEquals(2, paypal.syncs)
        clock = clock.plusSeconds(16 * 60)
        host.covered = LocalDate.of(2026, 10, 30)
        c.runAll().joinAll()
        assertEquals(3, paypal.syncs)
        assertEquals(LocalDate.of(2026, 10, 23), paypal.ranges.last().from)
    }

    @Test fun oneRunAtATimeAndSyncingShowsTheLastFreshness() = runBlocking {
        val c = coordinator(this)
        paypal.access = true
        c.runAll().joinAll()
        val fresh = c.state(ImportProvider.PAYPAL).value as ProviderSyncState.Fresh
        paypal.gate = CompletableDeferred()
        val job = c.runOne(ImportProvider.PAYPAL, force = true)!!
        yield()
        assertEquals(ProviderSyncState.Syncing(fresh), c.state(ImportProvider.PAYPAL).value)
        assertTrue(c.runOne(ImportProvider.PAYPAL, force = true) === job)
        paypal.gate!!.complete(Unit)
        job.join()
        assertEquals(2, paypal.syncs)
    }

    @Test fun aPushApprovalShowsOneLineWhileTheRunContinues() = runBlocking {
        val c = coordinator(this)
        isy.access = true
        isy.gate = CompletableDeferred()
        isy.before = { progress -> progress(AutoSyncProgress.Waiting(AttentionReason.SCA_PUSH)) }
        val job = c.runOne(ImportProvider.ISYBANK)!!
        yield()
        val waiting = c.state(ImportProvider.ISYBANK).value as ProviderSyncState.NeedsUser
        assertEquals(UserAction.APPROVE_2FA, waiting.action)
        assertEquals("Approva nell'app isybank", waiting.line)
        assertEquals(false, waiting.needsPage)
        isy.gate!!.complete(Unit)
        job.join()
        assertTrue(c.state(ImportProvider.ISYBANK).value is ProviderSyncState.Fresh)
    }

    @Test fun needsUserAndFailuresAreStatesNotSilence() = runBlocking {
        val c = coordinator(this)
        paypal.access = true
        paypal.result = { AutoSyncResult.NeedsUser(AttentionReason.SIGN_IN) }
        c.runAll().joinAll()
        val s = c.state(ImportProvider.PAYPAL).value as ProviderSyncState.NeedsUser
        assertEquals(UserAction.SIGN_IN_AGAIN, s.action)
        assertTrue(s.needsPage)
        assertEquals("Accedi di nuovo a PayPal", s.line)

        paypal.result = { AutoSyncResult.Synced(T.batch(ImportProvider.PAYPAL)) }
        host.fail = "Archivio non salvabile."
        c.runAll(force = true).joinAll()
        assertEquals(ProviderSyncState.Failed("Archivio non salvabile.", null), c.state(ImportProvider.PAYPAL).value)

        host.fail = null
        paypal.result = { throw IllegalStateException("boom") }
        c.runAll(force = true).joinAll()
        assertEquals("Sincronizzazione con PayPal non riuscita.", (c.state(ImportProvider.PAYPAL).value as ProviderSyncState.Failed).message)
    }

    @Test fun noHostNoRunAndDisconnectReturnsToIdle() = runBlocking {
        val c = AutoSyncCoordinator(listOf(paypal, isy), store, this, now = { clock }, today = { T.today })
        paypal.access = true
        assertNull(c.runOne(ImportProvider.PAYPAL))
        c.attach(host)
        c.runAll().joinAll()
        c.unlink(ImportProvider.PAYPAL)
        assertEquals(ProviderSyncState.Idle, c.state(ImportProvider.PAYPAL).value)
        paypal.access = false
        assertNull(c.runOne(ImportProvider.PAYPAL, force = true))
    }

    @Test fun aSignInLinksAndSyncsAtOnceAndFreshnessSurvivesARestart() = runBlocking {
        val c = coordinator(this)
        c.onSignedIn(ImportProvider.ISYBANK)!!.join()
        assertEquals(1, isy.syncs)
        assertTrue(c.isLinked(ImportProvider.ISYBANK))
        val restarted = AutoSyncCoordinator(listOf(paypal, isy), store, this, now = { clock }, today = { T.today })
        assertEquals(ProviderSyncState.Fresh(T.today, clock), restarted.state(ImportProvider.ISYBANK).value)
    }

    @Test fun aSignInFromTheVisiblePageStartsTheBackgroundReadAndClearsNothingElse() = runBlocking {
        val c = coordinator(this)
        c.onSignedIn(ImportProvider.PAYPAL)!!.join()
        assertEquals(1, paypal.syncs)
        assertEquals(ProviderSyncState.Fresh(T.today, clock), c.state(ImportProvider.PAYPAL).value)
        // A SignedIn reported for a page leaves a running indicator as it was before the run.
        c.report(ImportProvider.PAYPAL, AutoSyncResult.SignedIn())
        assertEquals(ProviderSyncState.Fresh(T.today, clock), c.state(ImportProvider.PAYPAL).value)
    }

    @Test fun forgettingTheFreshnessClearsTheShownDateAndItsStoreOnce() = runBlocking {
        val c = coordinator(this)
        paypal.access = true
        c.runAll().joinAll()
        assertTrue(c.state(ImportProvider.PAYPAL).value is ProviderSyncState.Fresh)
        c.forgetFreshness(ImportProvider.PAYPAL)
        assertEquals(ProviderSyncState.Idle, c.state(ImportProvider.PAYPAL).value)
        assertNull(store.get("fresh.to.PAYPAL"))
        val restarted = AutoSyncCoordinator(listOf(paypal, isy), store, this, now = { clock }, today = { T.today })
        assertEquals(ProviderSyncState.Idle, restarted.state(ImportProvider.PAYPAL).value)
        assertTrue(restarted.isLinked(ImportProvider.PAYPAL))

        assertEquals(false, c.repairDone(CoverageRepair.PAYPAL_EMPTY))
        c.markRepairDone(CoverageRepair.PAYPAL_EMPTY)
        assertEquals(true, restarted.repairDone(CoverageRepair.PAYPAL_EMPTY))
    }

    @Test fun aBatchFromTheVisiblePageIsAppliedThroughTheHost() = runBlocking {
        val c = coordinator(this)
        assertTrue(c.deliver(ImportProvider.PAYPAL, T.batch(ImportProvider.PAYPAL, to = LocalDate.of(2026, 10, 30))))
        assertEquals(ProviderSyncState.Fresh(LocalDate.of(2026, 10, 30), clock), c.state(ImportProvider.PAYPAL).value)
        assertTrue(c.isLinked(ImportProvider.PAYPAL))
        c.report(ImportProvider.PAYPAL, AutoSyncResult.NeedsUser(AttentionReason.CHALLENGE))
        val s = c.state(ImportProvider.PAYPAL).value as ProviderSyncState.NeedsUser
        assertEquals(UserAction.CHALLENGE, s.action)
        assertEquals(LocalDate.of(2026, 10, 30), s.last!!.coverageTo)
    }
}
