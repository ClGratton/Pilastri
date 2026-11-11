package xyz.grtlabs.pilastri.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RM-2: reduced motion app-wide. The window recomposer's scale ([AppMotionScale]) turns "Remove animations" (system scale 0)
 * into real time, so a tween is no longer skipped, and the [Motion] tokens branch on [Motion.reduced]: what travels snaps,
 * what is a progress runs the short fade, color and alpha stay as they are, and normal motion is the same specs as before.
 */
class ReducedMotionTokensTest {
    private var saved = 1f
    @Before fun save() { saved = Motion.platformScale }
    @After fun restore() { Motion.platformScale = saved }

    private class Clock : MonotonicFrameClock {
        var frames = 0
        private var t = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { frames++; t += 16_000_000L; return onFrame(t) }
    }

    private fun springOf(s: Any?) = s as SpringSpec<*>

    @Test fun reducedFlagFollowsTheSystemScale() {
        Motion.platformScale = 0f; assertTrue(Motion.reduced)
        Motion.platformScale = 0.5f; assertFalse(Motion.reduced)
        Motion.platformScale = 1f; assertFalse(Motion.reduced)
    }

    @Test fun reducedTravelTokensSnap() {
        Motion.platformScale = 0f
        val travelling = mapOf<String, Any>(
            "spatial" to Motion.spatial<Float>(), "selector" to Motion.selector<Float>(), "fill" to Motion.fill<Float>(),
            "chartUpdate" to Motion.chartUpdate<Float>(), "release" to Motion.release<Float>(), "press" to Motion.press<Float>(),
            "periodSettle" to Motion.periodSettle<Float>(), "chartLine" to Motion.chartLine, "spatialOffset" to Motion.spatialOffset(),
            "pageOffset" to Motion.pageOffset(), "travel(spring)" to Motion.travel(spring<Float>()),
        )
        travelling.forEach { (name, spec) -> assertTrue("$name should snap under reduced motion, was $spec", spec is SnapSpec<*>) }
    }

    @Test fun reducedProgressTokensAreTheShortFade() {
        Motion.platformScale = 0f
        listOf<Pair<String, Any>>("page" to Motion.page, "sibling" to Motion.sibling, "morph" to Motion.morph).forEach { (name, spec) ->
            assertTrue("$name should be a tween, was $spec", spec is TweenSpec<*>)
            assertEquals(name, Motion.ReducedFadeMs, (spec as TweenSpec<*>).durationMillis)
        }
    }

    @Test fun colorAndAlphaAreTheSameInBothModes() {
        Motion.platformScale = 0f
        val reduced = springOf(Motion.effects<Float>())
        Motion.platformScale = 1f
        val normal = springOf(Motion.effects<Float>())
        assertEquals(1400f, normal.stiffness, 0f); assertEquals(1f, normal.dampingRatio, 0f)
        assertEquals(normal.stiffness, reduced.stiffness, 0f); assertEquals(normal.dampingRatio, reduced.dampingRatio, 0f)
    }

    @Test fun normalMotionSpecsAreUnchanged() {
        Motion.platformScale = 1f
        fun check(name: String, spec: Any?, damping: Float, stiffness: Float) {
            val s = springOf(spec)
            assertEquals("$name damping", damping, s.dampingRatio, 0.0001f); assertEquals("$name stiffness", stiffness, s.stiffness, 0.01f)
        }
        check("page", Motion.page, 0.96f, 380f); check("pageOffset", Motion.pageOffset(), 0.96f, 380f)
        check("spatial", Motion.spatial<Float>(), 0.78f, 420f); check("spatialOffset", Motion.spatialOffset(), 0.86f, 420f)
        check("sibling", Motion.sibling, 0.94f, 330f); check("morph", Motion.morph, 0.9f, 300f)
        check("selector", Motion.selector<Float>(), 0.62f, 520f); check("fill", Motion.fill<Float>(), 0.72f, 170f)
        check("chartLine", Motion.chartLine, 0.9f, 170f); check("chartUpdate", Motion.chartUpdate<Float>(), 0.78f, 420f)
        check("release", Motion.release<Float>(), 0.5f, 700f); check("periodSettle", Motion.periodSettle<Float>(), 0.78f, 600f)
        check("press", Motion.press<Float>(), 0.62f, Spring.StiffnessMediumLow * 2.6f)
        // A faster system setting still softens the springs by the same factor as before (floor 0.3).
        Motion.platformScale = 0.5f
        check("spatial at 0.5", Motion.spatial<Float>(), 0.78f, 210f)
        Motion.platformScale = 0.1f
        check("spatial at 0.1", Motion.spatial<Float>(), 0.78f, 126f)
    }

    @Test fun travelPassesTheSpecThroughWhenNotReduced() {
        Motion.platformScale = 1f
        val s = spring<Float>()
        assertSame(s, Motion.travel(s))
    }

    @Test fun appScaleMapsOnlyZeroToOne() {
        assertEquals(1f, AppMotionScale.effective(0f), 0f)
        assertEquals(0.5f, AppMotionScale.effective(0.5f), 0f)
        assertEquals(1f, AppMotionScale.effective(1f), 0f)
        assertEquals(2f, AppMotionScale.effective(2f), 0f)
        Motion.platformScale = 0f; assertEquals(1f, AppMotionScale().scaleFactor, 0f)
        Motion.platformScale = 0.5f; assertEquals(0.5f, AppMotionScale().scaleFactor, 0f)
    }

    @Test fun aTweenIsNotSkippedUnderTheReducedOverride() {
        Motion.platformScale = 0f
        val a = Animatable(0f)
        val clock = Clock()
        runBlocking(clock + AppMotionScale()) { a.animateTo(1f, Motion.reducedFade()) }
        assertEquals(1f, a.value, 0f)
        val ms = clock.frames * 16
        assertTrue("took ${ms}ms in ${clock.frames} frames", clock.frames >= 5 && ms in Motion.ReducedFadeMs..(Motion.ReducedFadeMs + 64))
    }

    @Test fun aTravelSpecUnderTheOverrideEndsAtOnceInsteadOfTravelling() {
        Motion.platformScale = 0f
        val a = Animatable(0f)
        val clock = Clock()
        runBlocking(clock + AppMotionScale()) { a.animateTo(1f, Motion.spatial()) }
        assertEquals(1f, a.value, 0f)
        assertTrue("frames=${clock.frames}", clock.frames <= 2)
    }

    @Test fun normalMotionRunsAsBefore() {
        Motion.platformScale = 1f
        val a = Animatable(0f)
        val clock = Clock()
        runBlocking(clock + AppMotionScale()) { a.animateTo(1f, Motion.spatial()) }
        assertEquals(1f, a.value, 0.01f)
        assertTrue("frames=${clock.frames}", clock.frames >= 8)
    }

    @Test fun reducedSwapFadesAndTakesTheNewSizeAtOnce() {
        val t = reducedSwap()
        val size = t.sizeTransform!!.createAnimationSpec(IntSize(10, 10), IntSize(40, 80))
        assertTrue("size spec $size", size is SnapSpec<*>)
        assertTrue(t.sizeTransform!!.clip)
    }
}
