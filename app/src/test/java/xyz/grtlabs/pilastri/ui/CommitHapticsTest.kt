package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitHapticsTest {
    private class Fake : HapticSink {
        val calls = mutableListOf<String>()
        val scales = mutableListOf<Float>()
        override fun commitPoint() { calls += "commit" }
        override fun thresholdOut() { calls += "out" }
        override fun dragStart() { calls += "start" }
        override fun distanceTick(scale: Float) { calls += "tick"; scales += scale }
        override fun settle() { calls += "settle" }
    }

    private fun drag(vararg f: Float): Pair<Fake, CommitHaptics> {
        val s = Fake(); val c = CommitHaptics(s, 0.42f); c.start(); f.forEach { c.progress(it) }; return s to c
    }

    @Test fun startThenTicksRiseInScale() {
        val (s, _) = drag(*FloatArray(40) { (it + 1) * 0.01f }) // up to 0.40, below the threshold
        assertEquals("start", s.calls.first())
        assertTrue(s.scales.size >= 4)
        assertEquals(s.scales.sorted(), s.scales)
        assertTrue(s.scales.first() >= Hx.TickScaleFar && s.scales.last() <= Hx.TickScaleNear)
        assertTrue(s.scales.last() > s.scales.first())
        assertTrue("commit" !in s.calls)
    }

    @Test fun crossingOutFiresCommitOnce() {
        val (s, _) = drag(0.10f, 0.30f, 0.41f, 0.43f, 0.44f, 0.45f, 0.43f, 0.46f)
        assertEquals(1, s.calls.count { it == "commit" })
        assertEquals(0, s.calls.count { it == "out" })
    }

    @Test fun jitterBeyondThresholdDoesNotDoubleFire() {
        val s = Fake(); val c = CommitHaptics(s, 0.42f); c.start()
        c.progress(0.43f); (0 until 20).forEach { c.progress(if (it % 2 == 0) 0.44f else 0.425f) }
        assertEquals(listOf("start", "commit"), s.calls)
    }

    @Test fun noTicksRightAfterCrossingForTheQuietSamples() {
        val s = Fake(); val c = CommitHaptics(s, 0.42f); c.start()
        c.progress(0.40f); c.progress(0.43f); c.progress(0.41f)
        // crossed out then back within the quiet window: both snaps, no distance tick between or after
        assertEquals(listOf("start", "commit", "out"), s.calls.filter { it != "tick" }.let { l -> l })
        assertEquals(0, s.calls.drop(s.calls.indexOf("out")).count { it == "tick" })
    }

    @Test fun crossingBackFiresThresholdOutOnce() {
        val (s, _) = drag(0.10f, 0.45f, 0.30f, 0.28f)
        assertEquals(1, s.calls.count { it == "commit" })
        assertEquals(1, s.calls.count { it == "out" })
        assertTrue(s.calls.indexOf("out") > s.calls.indexOf("commit"))
    }

    @Test fun slowCrossSnapsOnceAtTheCrossingAndReleaseIsSilent() {
        val (s, c) = drag(0.10f, 0.30f, 0.45f, 0.48f); val n = s.calls.size
        c.release(committed = true)
        assertEquals(n, s.calls.size)
        assertEquals(1, s.calls.count { it == "commit" })
    }

    @Test fun flingWithoutLiveCrossingSnapsOnceAtRelease() {
        val (s, c) = drag(0.10f, 0.20f); c.release(committed = true)
        assertEquals("commit", s.calls.last()); assertEquals(1, s.calls.count { it == "commit" }); assertEquals(0, s.calls.count { it == "settle" })
    }

    @Test fun crossAndComeBackThenReleaseUncommittedIsSilentAfterTheWayBackSnap() {
        val (s, c) = drag(0.10f, 0.45f, 0.20f); val n = s.calls.size
        c.release(committed = false)
        assertEquals(n, s.calls.size); assertEquals(1, s.calls.count { it == "out" })
    }

    @Test fun cancelBeyondTheThresholdPlaysTheWayBackSnapOnce() {
        val (s, c) = drag(0.10f, 0.45f); c.cancel(); c.cancel()
        assertEquals(1, s.calls.count { it == "out" }); assertEquals("out", s.calls.last())
    }

    @Test fun cancelBelowTheThresholdIsSilent() {
        val (s, c) = drag(0.05f); val n = s.calls.size; c.cancel()
        assertEquals(n, s.calls.size)
    }

    @Test fun settleIsItsOwnEventAndNeverPartOfRelease() {
        val (s, c) = drag(0.10f, 0.45f); c.release(committed = true); assertEquals(0, s.calls.count { it == "settle" })
        c.settled(); assertEquals("settle", s.calls.last())
    }
}
