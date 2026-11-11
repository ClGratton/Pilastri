package xyz.grtlabs.pilastri.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-84, no launch flash: the start destination is resolved before the first composition is shown. Source side: the splash
 * condition is wired in MainActivity and SetupFrame settles it after composing the decided layer; the app is never the first
 * visible layer of an empty archive. [flashes] is the generic frame check (device side: frames captured from process start; any
 * frame showing a destination other than the settled one is a deviation); the must-flag fixture is the 2026-11-09 home frame
 * before setup.
 */
class StartGateTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private fun src(rel: String) = File(root, rel).readText()

    fun flashes(frames: List<String>, settled: String): List<Int> = frames.mapIndexedNotNull { i, f -> if (f != settled && f != "splash") i else null }

    @Test fun mustFlag_homeFrameBeforeSetup() {
        assertEquals(listOf(1, 2), flashes(listOf("splash", "home", "home", "setup", "setup"), "setup"))
    }
    @Test fun splashThenSetup_isClean() { assertTrue(flashes(listOf("splash", "splash", "setup", "setup"), "setup").isEmpty()) }

    @Test fun splashConditionIsWired() {
        assertTrue("MainActivity must keep the splash until StartGate settles", Regex("""setKeepOnScreenCondition\s*\{[^}]*StartGate\.keepSplash\(\)""").containsMatchIn(src("MainActivity.kt")))
        val frame = src("ui/setup/SetupFrame.kt")
        assertTrue("SetupFrame must settle the gate after deciding", frame.contains("StartGate.settle()"))
        val decide = frame.substringAfter("LaunchedEffect(app.loaded)").substringBefore("StartGate.settle()")
        assertTrue("first run on an empty archive must start with the SETUP layer alone, not crossfade over home", decide.contains("layers = listOf(Layer.SETUP)"))
    }

    @Test fun appRootDoesNotComposeHomeWhileUnknown() {
        val route = src("ui/AppRoot.kt").substringAfter("fun RouteContent").substringBefore("when (route)")
        assertTrue("RouteContent must show the loading/recovery page while the archive is unknown", route.contains("archive == null") && route.contains("LoadingOrRecovery"))
    }
}
