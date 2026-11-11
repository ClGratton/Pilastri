package xyz.grtlabs.pilastri.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The period strip's REAL call pair (PeriodSwipe.onDragEnd): release(moved) now, settled() from settleTo's onArrive, before the kick and the selection callback. */
class StripHapticSequenceTest {
    private class Fake : HapticSink {
        val calls = mutableListOf<String>()
        override fun commitPoint() { calls += "commit" }
        override fun thresholdOut() { calls += "out" }
        override fun dragStart() { calls += "start" }
        override fun distanceTick(scale: Float) { calls += "tick" }
        override fun settle() { calls += "settle" }
    }

    private fun run(target: Int, travelPages: Float, events: MutableList<String>): Fake {
        val fake = Fake(); val h = CommitHaptics(fake, Motion.PeriodThreshold)
        val state = PeriodState(3, 8)
        val g = PeriodGesture(3f, 0, 7)
        h.start(); g.drag(travelPages); h.progress(g.fingerTravel)
        val moved = target != g.start
        h.release(committed = moved)
        events += fake.calls.map { "h:$it" }
        runBlocking { state.settleTo(target, 0f, true, g, {}, { events += "selected" }, onArrive = { h.settled(); events += "arrive" }) }
        return fake
    }

    @Test fun flingThatNeverCrossedSnapsAtReleaseThenSettlesBeforeSelection() {
        val ev = mutableListOf<String>()
        val f = run(4, 0.2f, ev)
        assertEquals(1, f.calls.count { it == "commit" }); assertEquals(1, f.calls.count { it == "settle" })
        assertEquals(listOf("h:start", "h:commit", "arrive", "selected"), ev.filter { !it.startsWith("h:tick") })
        assertEquals("settle", f.calls.last())
    }

    @Test fun slowCrossSnapsLiveAndSettlesOnce() {
        val ev = mutableListOf<String>()
        val f = run(4, 0.5f, ev)
        assertEquals(1, f.calls.count { it == "commit" }); assertEquals(1, f.calls.count { it == "settle" })
        assertEquals(f.calls.indexOf("commit") < f.calls.indexOf("settle"), true)
    }

    @Test fun returnToTheStartPeriodHasNoSettleTick() {
        val ev = mutableListOf<String>()
        val f = run(3, 0.1f, ev)
        assertEquals(0, f.calls.count { it == "settle" }); assertEquals(0, f.calls.count { it == "commit" })
    }
}
