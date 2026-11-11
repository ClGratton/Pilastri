package xyz.grtlabs.pilastri.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reduced motion (AGENTS.md motion contract): "replaces travel with a short crossfade; it never removes the state change".
 * "Remove animations" is animator scale 0, where Compose finishes any animation in its first frame, so the crossfade has to
 * run on real time ([travelTo] / [RealTime]). A fake frame clock (16 ms per frame) stands in for the display.
 */
class ReducedMotionTest {
    private class Clock(private val probe: () -> Float) : MonotonicFrameClock {
        var frames = 0
        val samples = mutableListOf<Float>()
        private var t = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            frames++; t += 16_000_000L
            val r = onFrame(t)
            samples += probe()
            return r
        }
    }

    private val scaleZero = object : MotionDurationScale { override val scaleFactor = 0f }
    private val scaleOne = object : MotionDurationScale { override val scaleFactor = 1f }

    @Test fun reducedFadeIsAShortFixedTween() {
        val spec = Motion.reducedFade<Float>() as TweenSpec<Float>
        assertEquals(Motion.ReducedFadeMs, spec.durationMillis)
        assertEquals(0, spec.delay)
        assertTrue(Motion.ReducedFadeMs in 80..200)
    }

    @Test fun anOrdinaryTweenIsSkippedAtScaleZero() {
        // Documents why the fade cannot rely on the system scale: this is the defect.
        val a = Animatable(0f)
        val clock = Clock { a.value }
        runBlocking(clock + scaleZero) { a.animateTo(1f, Motion.reducedFade()) }
        assertTrue("frames=${clock.frames}", clock.frames <= 1)
        assertEquals(1f, a.value, 0f)
    }

    @Test fun reducedTravelIsAVisibleCrossfadeEvenAtScaleZero() {
        val a = Animatable(0f)
        val clock = Clock { a.value }
        runBlocking(clock + scaleZero) { a.travelTo(1f, reduce = true, spec = Motion.page) }
        assertEquals(1f, a.value, 0f)
        assertTrue("frames=${clock.frames}", clock.frames >= 5)
        assertTrue("blended frames: ${clock.samples}", clock.samples.count { it > 0.02f && it < 0.98f } >= 3)
        assertEquals(clock.samples.sorted(), clock.samples)
    }

    @Test fun reducedTravelCrossfadesBackDownToo() {
        val a = Animatable(1f)
        val clock = Clock { a.value }
        runBlocking(clock + scaleZero) { a.travelTo(0f, reduce = true, spec = Motion.page, initialVelocity = -3f) }
        assertEquals(0f, a.value, 0f)
        assertTrue("blended frames: ${clock.samples}", clock.samples.count { it > 0.02f && it < 0.98f } >= 3)
        assertEquals(clock.samples.sortedDescending(), clock.samples)
    }

    @Test fun reducedTravelTakesAboutTheTokenDuration() {
        val a = Animatable(0f)
        val clock = Clock { a.value }
        runBlocking(clock + scaleOne) { a.travelTo(1f, reduce = true, spec = Motion.page) }
        val ms = clock.frames * 16
        assertTrue("took ${ms}ms", ms in Motion.ReducedFadeMs..(Motion.ReducedFadeMs + 64))
    }

    @Test fun notReducedKeepsTheSpringAndLandsOnTheTarget() {
        val a = Animatable(0f)
        val clock = Clock { a.value }
        runBlocking(clock + scaleOne) { a.travelTo(1f, reduce = false, spec = Motion.page) }
        assertEquals(1f, a.value, 0.001f)
        assertTrue("frames=${clock.frames}", clock.frames > 8)
    }

    @Test fun theAgentVeilIsCompleteBeforeThePanelContentShows() {
        // Island.kt reveals the panel content from morph progress 0.3: the pane's title must be gone by then.
        assertTrue(Motion.AgentVeilUntil in 0.05f..0.3f)
    }
}
