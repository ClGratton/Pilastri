package xyz.grtlabs.pilastri.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.ui.AccentCard
import xyz.grtlabs.pilastri.ui.ActionRow
import xyz.grtlabs.pilastri.ui.AccentStat
import xyz.grtlabs.pilastri.ui.AttentionRow
import xyz.grtlabs.pilastri.ui.CardColumn
import xyz.grtlabs.pilastri.ui.CardHeader
import xyz.grtlabs.pilastri.ui.CardTrailingText
import xyz.grtlabs.pilastri.ui.Ic
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PageHeader
import xyz.grtlabs.pilastri.ui.PillButton
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.Tag
import xyz.grtlabs.pilastri.ui.screens.Segmented

/**
 * Wave 2 rule 2 (CR-66) over the SHARED components: at 840 dp and 411 dp wide, at font scale 1.0 and 2.0 (the app does not cap fontScale; 2.0 is
 * Android 14's maximum), with the longest real labels in the app, no text may overflow, be ellipsized inside a pill / chip / clickable / label, or be
 * wider than its pill / chip / card. Add a component here when it is added to the kit; add a label here when a screen gets a longer one.
 * `ActionRow` (wave 2 kit) joins the list when it lands. Run: `bash tools/gw.sh :app:testDebugUnitTest --tests "*TextClipTest*"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class TextClipTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Case(val name: String, val content: @Composable () -> Unit)

    private val labels = listOf("È un trasferimento", "È una spesa", "Salta", "Ultimo mese", "29 ott 2026", "Impostazioni")

    private val cases = listOf(
        Case("PillButton long") { PillButton("È un trasferimento", {}) },
        Case("PillButton pair") { Row { PillButton("È una spesa", {}); PillButton("Salta", {}) } },
        Case("PillButton pair long") { Row { PillButton("È un trasferimento", {}); PillButton("Ultimo mese", {}) } },
        Case("Tag chips") { Row { Tag("Ultimo mese"); Tag("da verificare"); Tag("29 ott") } },
        Case("ActionRow") { ActionRow { PillButton("È un trasferimento", {}); PillButton("È una spesa", {}); PillButton("Salta", {}) } },
        Case("Segmented") { Segmented(listOf("Ultimo mese", "Trimestre", "Anno"), 0, {}) },
        Case("CardHeader + trailing") {
            CardColumn { CardHeader("Flusso delle entrate", trailing = { CardTrailingText("Ultimo mese") }) }
        },
        Case("CardHeader long trailing") {
            CardColumn { CardHeader("Versamenti", trailing = { CardTrailingText("29 ott 2026") }) }
        },
        Case("AccentStat") {
            AccentCard { AccentStat("Spesa del mese", "12.345,67 €", primary = true); AccentStat("Entrate", "2.100,00 €") }
        },
        Case("AttentionRow") { AttentionRow(Ic.search, "Da controllare · 12", "è un trasferimento · 1.234,56 €", {}) },
        Case("PageHeader") { PageHeader("Impostazioni", "Aggiornato il 29 ott 2026") },
        // Wave 6 P3 (CR-66, L1): the long real subtitle of Mese with a back slot and a trailing slot; above font scale 1.3 it wraps, never ellipsizes.
        Case("PageHeader long subtitle + slots") {
            PageHeader("Novembre 2026", "dati al 6 nov · Dati di esempio",
                leading = { androidx.compose.foundation.layout.Spacer(Modifier.width(48.dp).height(48.dp)) },
                trailing = { androidx.compose.foundation.layout.Spacer(Modifier.width(48.dp).height(48.dp)) })
        },
    )

    /** Renders every case at every width and font scale and returns the CR-66 rows (the production rule, [Rules.textClip]). */
    private fun measure(list: List<Case>, widths: List<Int>, scales: List<Float>, overlap: Boolean = false): List<Dev> {
        var idx by mutableIntStateOf(0)
        val combos = list.indices.flatMap { c -> widths.flatMap { w -> scales.map { s -> Triple(c, w, s) } } }
        rule.setContent {
            val (c, w, s) = combos[idx]
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalDensity provides Density(LocalDensity.current.density, s)) {
                    key(idx) { Box(Modifier.width(w.dp).semantics { testTagsAsResourceId = true }) { Column { list[c].content() } } }
                }
            }
        }
        val dev = ArrayList<Dev>()
        for (i in combos.indices) {
            idx = i
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(500)
            val d = rule.density
            val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val nodes = collectNodes(root, d)
            val (c, w, s) = combos[i]
            val merged = collectNodes(rule.onRoot(useUnmergedTree = false).fetchSemanticsNode(), d, onlyClickable = true)
            val shot = Shot("${list[c].name} @${w}dp x${s}", "fold", false, nodes, root.boundsInRoot.width / d.density, root.boundsInRoot.height / d.density, merged)
            dev += Rules.textClip(shot) + (if (overlap) Rules.textOverlap(shot) else emptyList())
        }
        return dev
    }

    @Test fun noTextIsClippedInTheSharedComponents() {
        val dev = measure(cases, listOf(840, 411), listOf(1.0f, 2.0f))
        val table = listOf("| component | rule | element | measured | expected |", "|---|---|---|---|---|").plus(dev.map { it.row() }).joinToString("\n")
        File("build/layout-consistency").apply { mkdirs() }.resolve("text-clip.md").writeText(table)
        if (dev.isNotEmpty()) fail("${dev.size} clipped text finding(s) in the shared components (labels: ${labels.joinToString()})\n" + table)
    }

    /** Positive control (the rule keeps its teeth): a label forced into a cell narrower than its text MUST be reported. */
    @Test fun positiveControlNarrowCellIsReported() {
        val forced = listOf(
            Case("control Salta in 34 dp") { Box(Modifier.width(34.dp)) { PillButton("Salta", {}) } },
            Case("control È un trasferimento in 44 dp") { Box(Modifier.width(44.dp)) { PillButton("È un trasferimento", {}) } },
        )
        val dev = measure(forced, listOf(840), listOf(1.0f, 2.0f))
        for (c in forced) if (dev.none { it.screen.startsWith(c.name) })
            fail("positive control not reported: ${c.name} (CR-66 lost its teeth). rows: ${dev.joinToString { it.screen + "|" + it.rule + "|" + it.el }}")
    }

    /**
     * Font-scale 2.0 control (wave 2 M2): the old fixed-height pattern (two lines pinned into an 80 dp box). At 1.0 the lines fit; at 2.0 they
     * overlap, and CR-69 (texts overlap) MUST report it only at 2.0. Keeps the cover-at-2.0 harness honest: it detects rows that do not grow.
     */
    @Test fun positiveControlFixedHeightRowOverlapsOnlyAtLargeText() {
        val pinned = listOf(Case("control fixed 80 dp row") {
            Box(Modifier.width(300.dp).height(80.dp)) {
                androidx.compose.material3.Text("Ottobre", Modifier.align(androidx.compose.ui.Alignment.TopStart))
                androidx.compose.material3.Text("Novembre", Modifier.align(androidx.compose.ui.Alignment.BottomStart))
            }
        })
        val dev = measure(pinned, listOf(411), listOf(1.0f, 2.0f), overlap = true)
        if (dev.none { it.screen.endsWith("x2.0") && it.rule.contains("CR-69") }) fail("fixed-height control not reported at 2.0: ${dev.joinToString { it.row() }}")
        if (dev.any { it.screen.endsWith("x1.0") }) fail("fixed-height control reported at 1.0: ${dev.joinToString { it.row() }}")
    }

    /** Negative control: plain text in a roomy box produces no row. */
    @Test fun negativeControlNormalTextIsQuiet() {
        val ok = listOf(Case("control normal") { Column { PillButton("Salta", {}); Tag("Ultimo mese") } })
        val dev = measure(ok, listOf(840, 411), listOf(1.0f))
        if (dev.isNotEmpty()) fail("negative control reported rows: ${dev.joinToString { it.row() }}")
    }

    /**
     * Wave 7 (R1-01): the shared period title is ONE line at font scale 2.0, 411 dp wide, with the longest month name and its caret: one
     * text node, taller than one line would be, and not clipped.
     */
    @Test fun periodTitleStaysOnOneLineAtLargeText() {
        val list = listOf("Settembre", "Dicembre", "Novembre").map { m ->
            Case("period $m") { xyz.grtlabs.pilastri.ui.PeriodTitle(m, caret = true) }
        }
        val dev = measure(list, listOf(411), listOf(2.0f))
        if (dev.isNotEmpty()) fail("period title clipped: ${dev.joinToString { it.row() }}")
    }

    @Test fun periodTitleIsOneLineNode() {
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalDensity provides Density(LocalDensity.current.density, 2.0f)) {
                    Box(Modifier.width(411.dp)) { xyz.grtlabs.pilastri.ui.PeriodTitle("Settembre", caret = true) }
                }
            }
        }
        rule.waitForIdle()
        val texts = collectNodes(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), rule.density).filter { it.text == "Settembre" }
        if (texts.isEmpty()) fail("no 'Settembre' node")
        // One line at 2.0 is 26 sp x 2 = 52 dp; a second line would be about 104 dp. (The unmerged tree may hold the text and its tagged parent.)
        for (node in texts) {
            if (node.h > 78f) fail("period title is taller than one line: ${node.h} dp")
            if (node.ell) fail("period title is ellipsized at 411 dp")
        }
    }
}
