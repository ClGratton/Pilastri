package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Board 5a-06: "<provider> sta rispondendo…" clears when a run ends, on success, error and cancel. */
class RunStatusTest {
    @Test fun showsTheStageWhileRunningAndClearsOnSuccess() = runBlocking {
        val status = RunStatus()
        val result = status.track {
            status.report("Claude sta rispondendo…")
            assertTrue(status.running)
            assertEquals("Claude sta rispondendo…", status.stage)
            "ok"
        }
        assertEquals("ok", result)
        assertFalse(status.running)
        assertNull(status.stage)
    }

    @Test fun clearsOnError() = runBlocking {
        val status = RunStatus()
        try {
            status.track {
                status.report("ChatGPT sta rispondendo…")
                throw IllegalStateException("boom")
            }
            org.junit.Assert.fail("the error must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertFalse(status.running)
        assertNull(status.stage)
    }

    @Test fun clearsOnCancel() = runBlocking {
        val status = RunStatus()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            status.track {
                status.report("Claude sta rispondendo…")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertTrue(status.running)
        job.cancelAndJoin()
        assertFalse(status.running)
        assertNull(status.stage)
    }

    @Test fun clearsWhenTheBlockThrowsACancellation() = runBlocking {
        val status = RunStatus()
        val failed = async { runCatching { status.track { status.report("x"); throw CancellationException("stop") } } }
        assertTrue(failed.await().exceptionOrNull() is CancellationException)
        assertFalse(status.running)
        assertNull(status.stage)
    }

    @Test fun aStageReportedAfterTheEndIsDropped() = runBlocking {
        val status = RunStatus()
        status.track { status.report("Apro Claude…") }
        status.report("Claude sta rispondendo…") // a late callback of the provider page
        assertNull(status.stage)
        assertFalse(status.running)
    }

    @Test fun aNewRunStartsWithoutTheOldStage() = runBlocking {
        val status = RunStatus()
        status.track { status.report("old") }
        status.begin()
        assertTrue(status.running)
        assertNull(status.stage)
        status.end()
        assertFalse(status.running)
    }
}
