package xyz.grtlabs.pilastri.ui.layout

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.grtlabs.pilastri.data.LocalArchive
import xyz.grtlabs.pilastri.debug.SampleArchive
import xyz.grtlabs.pilastri.ui.App
import xyz.grtlabs.pilastri.ui.AppRoot
import xyz.grtlabs.pilastri.ui.LocalInPane
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.Route
import xyz.grtlabs.pilastri.ui.RouteContent

/**
 * The JVM layout gate (AGENTS.md "The check is an algorithm, not an opinion"): renders every top-level screen and the main
 * detail screens with the synthetic sample archive at the open Fold and at the cover, reads the bounds of the tagged nodes
 * (`ui/Tags.kt`, unmerged tree) and applies the rules of `tools/consistency-check.mjs` and `tools/critique-rules.md`
 * ("Proposed checker rules (machine)"). One test per rule family; each failure lists rows `screen | rule | element | measured | expected`.
 * EXPECTED RED until every fix of the 2026-11-08 wave has landed. Never @Ignore it, never loosen a rule.
 *
 * Run: `bash tools/gw.sh :app:testDebugUnitTest --tests "*LayoutConsistencyTest*"`.
 */
@RunWith(RobolectricTestRunner::class)
abstract class LayoutConsistencyTest(private val layout: String, private val fontScale: Float = 1f, private val only: Set<String>? = null) {
    /** Report key: "fold", "phone", or "phone-fs2" (cover at system font scale 2.0, wave 2 rule M2). */
    private val tag = if (fontScale == 1f) layout else "$layout-fs${fontScale.toInt()}"
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private sealed interface Scn { val name: String; val top: Boolean }
    private class Top(val label: String) : Scn { override val name get() = "top-$label"; override val top get() = true }
    /** Pilastri with the PREVIOUS month chosen from the hero strip (CR-70/K8 at the open Fold: Mese beside must own the coverage of any month; the default month alone hid the 2026-11-09 12:13 duplicate). */
    private object PastMonth : Scn { override val name get() = "top-pilastri-past"; override val top get() = true }
    private class Detail(override val name: String, val route: () -> Route) : Scn { override val top get() = false }

    private val screens: List<Scn> = listOf(
        Top("pilastri"), *(if (layout == "fold") arrayOf(PastMonth) else emptyArray()), Top("spese"), Top("futuro"), Top("agente"), Top("impostazioni"),
        Detail("mese") { Route.Month(YearMonth.now().toString()) },
        Detail("pillar") { Route.Pillar(0) },
        Detail("previdenza") { Route.Pension },
        Detail("conti") { Route.Connections },
    )

    private fun shots(): List<Shot> = cache.getOrPut(tag) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Synthetic data only: the debug sample archive, written through the app's own storage.
        LocalArchive(ctx).save(SampleArchive.build(LocalDate.now()))
        val archive = LocalArchive(ctx).load()
        var scn by mutableStateOf<Scn>(screens[0])
        rule.setContent {
            PilastriTheme(false) {
                CompositionLocalProvider(LocalReduceMotion provides true, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        key(scn.name) {
                            when (val s = scn) {
                                is Top, PastMonth -> AppRoot()
                                is Detail -> {
                                    val scope = rememberCoroutineScope()
                                    val app = remember { App(ctx, LocalArchive(ctx), scope).also { it.archive = archive } }
                                    CompositionLocalProvider(LocalInPane provides (layout == "fold")) { RouteContent(app, s.route()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val out = ArrayList<Shot>()
        val notes = ArrayList<Dev>()
        var pilastriSig = ""
        val nav = StringBuilder()
        for (s in screens) {
            scn = s
            rule.waitForIdle()
            if (s === PastMonth) runCatching {
                val title = xyz.grtlabs.pilastri.ui.Fmt.monthTitle(YearMonth.from(LocalDate.now()).minusMonths(1))
                rule.onAllNodes(hasText(title, substring = true) and hasClickAction())[0].performClick()
            }.onFailure { notes += Dev("$tag-${s.name}", "navigation: could not choose the previous month", "pilastri", 0f, 1f, it.javaClass.simpleName) }
            if (s is Top && s.label != "pilastri") runCatching { navigate(s.label) }.onFailure { notes += Dev("$tag-${s.name}", "navigation: could not open the screen", s.label, 0f, 1f, it.javaClass.simpleName) }
            rule.mainClock.advanceTimeBy(2000)
            repeat(3) { rule.waitForIdle() }
            val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val merged = rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
            val d = rule.density
            val w = root.boundsInRoot.width / d.density; val h = root.boundsInRoot.height / d.density
            val nodes = collectNodes(root, d)
            out += Shot("$tag-${s.name}", layout, s.top, nodes, w, h, collectNodes(merged, d, onlyClickable = true))
            // A top screen that shows exactly the same texts as Pilastri was not opened (harness failure, reported as such).
            val sig = nodes.filter { it.text.isNotBlank() }.map { it.text }.toSortedSet().joinToString("|")
            if (s is Top) {
                if (s.label == "pilastri") pilastriSig = sig
                else if (sig == pilastriSig) notes += Dev("$tag-${s.name}", "navigation: the screen shows the same content as Pilastri (not opened)", s.label, 0f, 1f)
                nav.append("${s.name}: nodes=${nodes.size} titles=${nodes.filter { it.id == "page-title" }.map { it.text }} panes=${nodes.filter { it.id.startsWith("pane-") }.map { it.id }}\n")
            }
        }
        File("build/layout-consistency").apply { mkdirs() }.resolve("$tag-nav.txt").writeText(nav.toString())
        File("build/layout-consistency").resolve("$tag-nodes.txt").writeText(out.joinToString(NL) { dump(it) })
        navNotes[tag] = notes
        out
    }

    /** Opens a top-level screen: the clickable dock item / island button whose label or description is exactly [label], else one that contains it. */
    private fun navigate(label: String) {
        val text = label.replaceFirstChar { it.uppercase() }
        val exact = rule.onAllNodes(hasClickAction() and (hasContentDescription(text) or hasText(text)))
        val loose = rule.onAllNodes(hasClickAction() and (hasContentDescription(text, substring = true) or hasText(text, substring = true)))
        val target = if (exact.fetchSemanticsNodes().isNotEmpty()) exact else loose
        target[0].performClick()
    }

    private val NL = System.lineSeparator()
    private fun dump(sh: Shot): String {
        val b = StringBuilder("## ${sh.name} ${sh.width}x${sh.height}$NL")
        for (n in sh.nodes) b.append(n.id).append('|').append(n.text.take(30)).append('~').append(n.desc.take(20)).append('|').append("${r1(n.l)},${r1(n.t)},${r1(n.r)},${r1(n.b)}").append(NL)
        b.append("-- targets").append(NL)
        for (n in sh.targets) b.append(n.text.take(30)).append(n.desc.take(20)).append('|').append("${r1(n.l)},${r1(n.t)},${r1(n.r)},${r1(n.b)}").append(NL)
        return b.toString()
    }

    private fun family(prefix: String) {
        if (only != null && prefix !in only) return   // a class scoped to some families (open Fold at font 2.0: touch + clip + navigation)
        val all = try { Rules.check(shots()) + (navNotes[tag] ?: emptyList()) } catch (e: Throwable) { throw AssertionError("rule family '$prefix' at $tag could not be evaluated: ${e::class.simpleName}: ${e.message}", e) }
        File("build/layout-consistency").apply { mkdirs() }.resolve("$tag.md").writeText(
            "| screen | rule | element | measured | expected |\n|---|---|---|---|---|\n" + all.joinToString("\n") { it.row() })
        if (prefix == "duplicate" && Rules.lastDupReport.isNotEmpty()) { println("[$tag] " + Rules.lastDupReport); File("build/layout-consistency/$tag-dup-filters.txt").writeText(Rules.lastDupReport) }
        // At large text the attention row grows (heightIn min 56, wave 2 M2); the 56 dp equality of CR-59 holds at fontScale 1.0 only.
        val mine = all.filter { it.rule.startsWith(prefix) && !(fontScale > 1.3f && it.rule.startsWith("attention: attention-row height")) }
        if (mine.isNotEmpty()) fail("${mine.size} deviation(s) in rule family '$prefix' at $tag\n| screen | rule | element | measured | expected |\n" + mine.joinToString("\n") { it.row() })
    }

    @Test fun navigationReachesEveryScreen() = family("navigation")
    @Test fun cardTitleInsetAndHeight() = family("card-title")
    @Test fun cardHeaderRow() = family("card-header")
    @Test fun cardContentInsets() = family("card-inset")
    @Test fun pageHeaderAndFirstContent() = family("page-header")
    @Test fun cardEdgesPerPane() = family("card-edge")
    @Test fun labelsAlignWithTheirCard() = family("label")
    @Test fun chipHeight() = family("chip")
    @Test fun frameDockIslandGear() = family("frame")
    @Test fun panesSplitAndPopulated() = family("panes")
    @Test fun touchTargets48dp() = family("touch")
    @Test fun copyLength() = family("copy")
    @Test fun chartsHaveDescriptions() = family("chart")
    @Test fun onePrimaryFigurePerTopPage() = family("figure")
    @Test fun oneAccentCardPerScreenWithPrimaryFigure() = family("accent")
    @Test fun blocksOnTheSixteenGutter() = family("gutter")
    @Test fun blocksEightDpApart() = family("gap")
    @Test fun attentionRowIsOneTarget() = family("attention")
    @Test fun cardBodyPadding() = family("card-body")
    @Test fun textIsNotClipped() = family("clip")
    @Test fun neighbourPeekAtRest() = family("motion")
    /** CR-70 (K8): a date or period appears once per pane, on every screen (Mese and Spese included). */
    @Test fun noDuplicateDatesPerPane() = family("duplicate")
    /** CR-71 (H6): Mese and Spese each expose the shared `period-title`, same height, the largest header text. */
    @Test fun periodTitleOnEverySwipePage() = family("period")
    /** CR-72 (wave 8): no figure/currency pair joined by a breakable space. */
    @Test fun figureAndCurrencyNeverSplit() = family("text")

    companion object {
        private val cache = HashMap<String, List<Shot>>()
        private val navNotes = HashMap<String, List<Dev>>()
    }
}

/** Open Fold, two panes (the emulator's 2208x1768 at 420 dpi is about 840 x 673 dp). */
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class FoldLayoutConsistencyTest : LayoutConsistencyTest("fold")

/** Cover / phone, one pane with the centred dock. */
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PhoneLayoutConsistencyTest : LayoutConsistencyTest("phone")

// PHASE C by the user's decision 2026-11-09 (AGENTS.md Speed / Phases): both font-2.0 classes below are excluded from the default
// testDebugUnitTest by app/build.gradle.kts and run with `-PphaseC --tests "*FontScale2*"`. Recorded finding: font-2.0 stat-row reflow.
/**
 * Cover at the system's maximum font scale 2.0 (AGENTS.md "Large text must reflow or scroll; target at least 48dp"; K4 captured the
 * cover at 2.0). Same rules, own report `build/layout-consistency/phone-fs2.md`. Texts, rows, chips, pills and headers must grow and
 * wrap with the text; touch targets stay >= 48 dp; content keeps touching card-body.
 */
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PhoneFontScale2LayoutConsistencyTest : LayoutConsistencyTest("phone", 2.0f)

/**
 * Open Fold (840 dp) at font scale 2.0 (fix wave 3, owner T: CR-23 class). Scoped to the families that must hold at any text size:
 * `touch` (every clickable >= 48 x 48 dp), `clip` (no text cut or broken mid-word) and `navigation` (every screen opens). The other
 * families compare geometry across cards by majority, which at 2.0 on two panes legitimately differs by text growth alone
 * (card heights, header rows, peek slivers); they are asserted at font 1.0 by [FoldLayoutConsistencyTest]. Own report `fold-fs2.md`.
 */
@Config(sdk = [36], qualifiers = "w840dp-h673dp")
class FoldFontScale2LayoutConsistencyTest : LayoutConsistencyTest("fold", 2.0f, setOf("touch", "clip", "navigation"))
