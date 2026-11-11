package xyz.grtlabs.pilastri.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards the DEVICE checker (tools/consistency-check.mjs, tools/viewport-point.mjs) with real emulator dumps (tools/fixtures/m1, K4 capture 2026-11-09).
 * The JVM engine never produced these false rows, so only a test on the script itself can keep them away. Skipped when `node` is not installed.
 *  1. Middle month on the open Fold (two panes, both with period labels): labels travel 1:1 with their page; no CO-MOTION / CR-68 row. The old
 *     loop paired a label with the nearest same-id label of the moving dump, which belongs to the other pane once the page has left the tree.
 *  2. A clickable cut by the window top (page scrolled under it, 45 dp visible of a 48 dp target) is not a CR-23 row.
 *  3. A label moved by another amount than its page IS reported (positive control: the check is not vacuous).
 *  4. A held-drag start outside every period-viewport of the rest dump is refused (exit 3): the K4 cover drag at y 1250 missed the strip that was scrolled to y 895-1056.
 */
class CheckerFixturesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/consistency-check.mjs").exists() }
    private val fixtures = File(root, "tools/fixtures/m1")

    private fun node(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("node") + args).directory(root).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        return p.waitFor() to text
    }

    private fun hasNode() = runCatching { node("--version").first == 0 }.getOrDefault(false)

    private fun rowsOf(dir: File, vararg needles: String): List<String> {
        val (_, out) = node("tools/consistency-check.mjs", dir.absolutePath, "--density", "2.625")
        return out.lines().filter { l -> l.startsWith("|") && needles.any { l.contains(it) } }
    }

    @Test fun middleMonthLabelsTravelWithTheirPageOnTheFold() {
        assumeTrue(hasNode())
        val rows = rowsOf(fixtures, "CO-MOTION", "(CR-68)")
        assertTrue("labels must move like their page, got: $rows", rows.isEmpty())
    }

    @Test fun aClickableCutByTheWindowTopIsNotATouchTargetRow() {
        assumeTrue(hasNode())
        val rows = rowsOf(fixtures, "(CR-23)")
        assertTrue("target cut by the window top reported: $rows", rows.isEmpty())
    }

    @Test fun aLabelThatMovesLessThanItsPageIsReported() {
        assumeTrue(hasNode())
        val tmp = File(root, "app/build/checker-fixture-control").apply { deleteRecursively(); mkdirs() }
        File(fixtures, "fold-pilastri-mid-rest.xml").copyTo(File(tmp, "fold-pilastri-mid-rest.xml"))
        val moving = File(fixtures, "fold-pilastri-mid-moving.xml").readText()
        // period-label-cur of the left hero: 98 px wide at x 74 in the real dump; put it 40 px away from where its page took it.
        val broken = moving.replace("bounds=\"[74,1086][172,1120]\"", "bounds=\"[114,1086][212,1120]\"")
        assertTrue("fixture changed", broken != moving)
        File(tmp, "fold-pilastri-mid-moving.xml").writeText(broken)
        val rows = rowsOf(tmp, "period label moves like its page (CR-68)")
        assertEquals("exactly the broken label: $rows", 1, rows.size)
    }

    // ---- Wave 3 C (tools/fixtures/w3, 09d capture of the 01:38 build). Each artefact is NOT a row; the matching real defect still IS one. ----
    private val w3 = File(root, "tools/fixtures/w3")
    private fun control(vararg names: String): File = File(root, "app/build/checker-fixture-w3").apply { deleteRecursively(); mkdirs() }.also { d -> names.forEach { File(w3, it).copyTo(File(d, it)) } }

    @Test fun w3ArtefactsAreNotRows() {
        assumeTrue(hasNode())
        // font-scale-2 dumps form their own group and keep the ordering-only CR-56; a card passing under the dock is cut; a full-width pill is content; a chip cut by the top is
        // not measured; a card sliding past the pane edge is displaced like its title (clipped edge ignored).
        val rows = rowsOf(w3, "CO-MOTION", "card-inset bottom", "card-body: inset bottom", "chip height", "page-header page-title", "page-header page-subtitle", "(CR-56)", "card-title top inset", "card-title height")
        assertTrue("tool artefacts reported: $rows", rows.isEmpty())
    }

    @Test fun w3AScrolledPageHeaderIsOneCaptureRow() {
        assumeTrue(hasNode())
        val rows = rowsOf(w3, "capture: page header cut")
        assertEquals("only phone-fs2-pilastri was captured scrolled: $rows", 1, rows.size)
        assertTrue(rows.single().contains("phone-fs2-pilastri"))
    }

    @Test fun w3ATitleThatMovesLessThanItsCardIsReported() {
        assumeTrue(hasNode())
        val d = control("fold-spese-rest.xml")
        val moving = File(w3, "fold-spese-moving.xml").readText()
        val broken = moving.replace("bounds=\"[296,532][547,575]\"", "bounds=\"[336,532][587,575]\"")   // "Ritmo di spesa" 40 px (15 dp) off its card
        assertTrue("fixture changed", broken != moving)
        File(d, "fold-spese-moving.xml").writeText(broken)
        val rows = rowsOf(d, "CO-MOTION card-title")
        assertEquals("exactly the displaced title: $rows", 1, rows.size)
    }

    @Test fun w3ARealBottomInsetBelowAPillIsStillReported() {
        assumeTrue(hasNode())
        val d = control()
        val src = File(w3, "phone-setup-0-empty.xml").readText()
        val broken = src.replace("bounds=\"[42,1092][1038,1554]\"", "bounds=\"[42,1092][1038,1640]\"")   // Configurazione card 33 dp taller than its content
        assertTrue("fixture changed", broken != src)
        File(d, "phone-setup-0-empty.xml").writeText(broken)
        assertEquals(1, rowsOf(d, "card-inset bottom").size)
    }

    @Test fun w3ARealChipOfAnotherHeightIsStillReported() {
        assumeTrue(hasNode())
        val d = control()
        File(w3, "phone-impostazioni-altriconti.xml").copyTo(File(d, "phone-impostazioni-altriconti-b.xml"))   // a second, unchanged chip: the majority
        val src = File(w3, "phone-impostazioni-altriconti.xml").readText()
        val broken = src.replace("bounds=\"[787,1425][1001,1478]\"", "bounds=\"[787,1425][1001,1520]\"")   // "non collegato" chip 36 dp instead of 20
        assertTrue("fixture changed", broken != src)
        File(d, "phone-impostazioni-altriconti.xml").writeText(broken)
        assertEquals(1, rowsOf(d, "chip height").size)
    }

    @Test fun aHeldDragOutsideEveryPagerIsRefused() {
        assumeTrue(hasNode())
        val rest = File(fixtures, "phone-pilastri-rest.xml").absolutePath
        // The rest dump has its hero strip at y 895-1056 (page scrolled); the K4 drag started at y 1250.
        assertEquals(3, node("tools/viewport-point.mjs", rest, "check", "900", "1250").first)
        assertEquals(0, node("tools/viewport-point.mjs", rest, "check", "900", "976").first)
        val (code, auto) = node("tools/viewport-point.mjs", rest, "auto", "0")
        assertEquals(0, code)
        assertTrue("auto point inside the strip: $auto", auto.trim().split(" ")[1].toInt() in 895..1056)
    }
}
