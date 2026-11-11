package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

/** The shared strip's drawn travel follows Xtraordinary's curve however the finger's travel arrives (one jump or many samples), and haptics never crash. */
class StripResistanceGestureTest {
    /** Xtraordinary MagneticSwipeConfig.displayedProgress, restated (0.42 / 0.08 / 0.48), resistance 1. */
    private fun reference(signed: Float): Float {
        val p = abs(signed)
        if (p <= 0.08f) return signed
        val c = (p - 0.08f) / (1f - 0.08f)
        return signed.sign * minOf(0.08f + 0.48f * sin(c * (PI.toFloat() / 2f)), p)
    }

    private fun drawn(travel: Float, steps: Int): Float {
        val g = PeriodGesture(3f, 0, 9)
        repeat(steps) { g.drag(travel / steps) }
        return g.displayed(1f) - g.start
    }

    @Test fun drawnTravelEqualsTheReferenceCurveForOneBigDeltaAndManySmallOnes() {
        for (t in listOf(0.25f, 0.40f)) for (steps in listOf(1, 3, 60)) {
            assertEquals("travel $t in $steps steps", reference(t), drawn(t, steps), 0.02f)
            assertEquals("travel -$t in $steps steps", reference(-t), drawn(-t, steps), 0.02f)
        }
        // Past the commit point the blend tween releases the resistance (strip blend 0): the page is the finger.
        val g = PeriodGesture(3f, 0, 9); g.drag(0.60f)
        assertEquals(0.60f, g.displayed(0f) - g.start, 0.02f)
        println("drawn fraction at 0.25 = ${drawn(0.25f, 1)}, at 0.60 resisted = ${drawn(0.60f, 1)}")
    }

    @Test fun resistedTravelIsAlwaysBehindTheFinger() {
        for (t in listOf(0.1f, 0.25f, 0.4f, 0.8f)) assertTrue(abs(drawn(t, 1)) < t)
    }

    @Test fun manifestDeclaresVibrateWheneverTheSourceUsesAVibrator() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/AndroidManifest.xml").exists() }
        val uses = File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }
            .any { f -> f.readText().let { "Vibrator" in it || "VibrationEffect" in it } }
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        if (uses) assertTrue("Vibrator is used: AndroidManifest.xml must declare android.permission.VIBRATE", "android.permission.VIBRATE" in manifest)
    }
}
