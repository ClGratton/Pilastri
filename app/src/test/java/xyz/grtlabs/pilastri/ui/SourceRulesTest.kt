package xyz.grtlabs.pilastri.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE rules of `tools/critique-rules.md` that bounds cannot see (CR-57, CR-58, CR-59 outline, CR-60, CR-61, CR-62 radius).
 * Part of the layout gate; expected red until the 2026-11-08 fix wave has landed. Never @Ignore it, never loosen a rule.
 * Colour rules are source scans (there is no debug `fill` semantics); each failure lists `file:line` offenders.
 */
class SourceRulesTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private val ui = File(root, "ui")
    private fun kt(dir: File) = dir.walkTopDown().filter { it.extension == "kt" }.toList()
    private fun lines(f: File) = f.readLines()
    private fun at(f: File, i: Int) = "${f.relativeTo(root).path.replace('\\', '/')}:${i + 1}"
    private fun code(l: String) = l.substringBefore("//")
    private val screens get() = kt(File(ui, "screens")) + kt(File(ui, "setup"))
    private fun uiFile(rel: String) = File(ui, rel).takeIf { it.exists() }?.readText().orEmpty()

    // ---- CR-57: text styles come only from the Tokens table -------------------------------------------------------------------------
    /** Font sizes (sp) of the role rows of DESIGN.md "## Tokens" (column 2 starts with the size; "hero on the home: 30" adds 30). */
    private fun tokenSizes(): Set<Float> {
        val d = File("DESIGN.md").takeIf { it.exists() } ?: File("../DESIGN.md").takeIf { it.exists() } ?: File("../../DESIGN.md")
        val sec = d.readText().substringAfter("## Tokens").substringBefore("\n## ")
        val out = HashSet<Float>()
        for (row in sec.lines().filter { it.startsWith("|") && !it.startsWith("|---") }) {
            val col = row.split("|").getOrNull(2).orEmpty().trim()
            Regex("^(\\d+(?:\\.\\d+)?)\\b").find(col)?.let { out += it.groupValues[1].toFloat() }
            Regex("home: (\\d+)").find(col)?.let { out += it.groupValues[1].toFloat() }
        }
        return out
    }

    @Test fun cr57_noRawFontSizeInScreens() {
        val bad = screens.flatMap { f -> lines(f).mapIndexedNotNull { i, l -> if (Regex("fontSize\\s*=\\s*[\\d.]+\\.sp|\\.copy\\(\\s*fontSize|fontSize\\s*\\*").containsMatchIn(code(l))) at(f, i) else null } }
        assertTrue("CR-57: raw fontSize in ui/screens/** or ui/setup/** (${bad.size}); use a `Type` style: $bad", bad.isEmpty())
    }

    @Test fun cr57_everyTypeStyleUsedInScreensIsInTheTokensTable() {
        val sizes = tokenSizes()
        assertTrue("CR-57: no role rows parsed from DESIGN.md ## Tokens", sizes.isNotEmpty())
        val kit = uiFile("Kit.kt").substringAfter("object Type {").substringBefore("\n}")
        // style name -> font size in sp, from `val name = st(Weight, 22.sp, ...)` (and the aliases `val a = b`)
        val size = HashMap<String, Float>()
        for (l in kit.lines()) {
            Regex("val (\\w+) = st\\(\\w+, ([\\d.]+)\\.sp").find(l)?.let { size[it.groupValues[1]] = it.groupValues[2].toFloat() }
            Regex("val (\\w+) = (\\w+)\\s*$").find(l)?.let { m -> size[m.groupValues[2]]?.let { size[m.groupValues[1]] = it } }
        }
        val bad = ArrayList<String>()
        for (f in screens) lines(f).forEachIndexed { i, l ->
            for (m in Regex("\\bType\\.([a-z]\\w*)").findAll(code(l))) {
                val s = size[m.groupValues[1]] ?: continue
                if (s !in sizes) bad += "${at(f, i)} Type.${m.groupValues[1]} (${s}sp)"
            }
        }
        assertTrue("CR-57: Type styles whose size is not a role of DESIGN.md ## Tokens ${sizes.sorted()} (${bad.size}); add the role to the table first, or use a listed style: ${bad.take(40)}", bad.isEmpty())
    }

    // ---- CR-58: content rows white + outline, tint only on selected / icon-disc -------------------------------------------------------
    @Test fun cr58_cardContainerIsWhiteWithTheOutline() {
        val kit = uiFile("Kit.kt")
        assertTrue("CR-58: Pal.card must be white (Color.White) and cardLine #E8DFF1", kit.contains("card = Color.White") && kit.contains("cardLine = Color(0xFFE8DFF1)"))
        val card = Regex("fun Modifier\\.card\\([^\\n]*\\n(?:[^\\n]*\\n){0,8}").find(kit)?.value.orEmpty()
        assertTrue("CR-58: Modifier.card must draw a 1 dp border in pal.cardLine", card.contains("border") && card.contains("cardLine"))
    }

    @Test fun cr58_noTintedCardFillInScreensExceptSelectedOrIconDisc() {
        val bad = screens.flatMap { f ->
            val ls = lines(f)
            ls.mapIndexedNotNull { i, l ->
                val c = code(l)
                val tinted = Regex("\\.card\\(\\s*pal\\s*,\\s*color\\s*=\\s*(?!pal\\.card\\b)[^)]*\\)").containsMatchIn(c) || Regex("\\.card\\([^)]*(lilacBg|accentTile|pal\\.chip|pal\\.inset)").containsMatchIn(c)
                val ok = (maxOf(0, i - 3)..minOf(ls.size - 1, i + 3)).any { Regex("selected|SELECTED|ICON_DISC|iconDisc", RegexOption.IGNORE_CASE).containsMatchIn(ls[it]) }
                if (tinted && !ok) at(f, i) else null
            }
        }
        assertTrue("CR-58: content cards with a tint fill that is not a selected state or an icon disc (${bad.size}): $bad", bad.isEmpty())
    }

    // ---- CR-59: attention row outline (source part) ------------------------------------------------------------------------------------
    @Test fun cr59_attentionRowIsOutlinedWithTheLilacLineAndAChevron() {
        val src = uiFile("CardKit.kt") + uiFile("Kit.kt")
        val i = src.indexOf("fun AttentionRow")
        assertTrue("CR-59: no shared AttentionRow in CardKit.kt / Kit.kt", i >= 0)
        if (i >= 0) {
            val body = src.substring(i, minOf(src.length, i + 2500))
            assertTrue("CR-59: AttentionRow must use a 2 dp border in accentLine (#CFBDFE) and a chevron", body.contains("2.dp") && body.contains("accentLine") && Regex("chevron|ChevronRight|KeyboardArrowRight", RegexOption.IGNORE_CASE).containsMatchIn(body))
            assertTrue("CR-59: AttentionRow must carry UiTag.ATTENTION_ROW", body.contains("UiTag.ATTENTION_ROW"))
        }
    }

    // ---- CR-60: warning colour only on `over` ------------------------------------------------------------------------------------------
    @Test fun cr60_warningColourOnlyNextToTheOverTag() {
        val warn = Regex("pal\\.warn(Bg|Ink|OnAccent|Dot)|warnContainer|onWarnContainer|colorScheme\\.error|MaterialTheme\\.colorScheme\\.errorContainer")
        val bad = kt(ui).filter { it.name !in setOf("Kit.kt", "Theme.kt", "Tags.kt") }.flatMap { f ->
            val ls = lines(f)
            ls.mapIndexedNotNull { i, l -> if (warn.containsMatchIn(code(l)) && (maxOf(0, i - 8)..minOf(ls.size - 1, i + 8)).none { ls[it].contains("UiTag.OVER") }) at(f, i) else null }
        }
        assertTrue("CR-60: warning colour used without a UiTag.OVER within 8 lines (${bad.size}); tag the over-pace / over-budget node or use the neutral role: $bad", bad.isEmpty())
    }

    // ---- CR-61: accent fill only inside the shared AccentCard --------------------------------------------------------------------------
    @Test fun cr61_accentFillOnlyInsideAccentCard() {
        val fill = Regex("(background|containerColor|drawRect|color)\\s*[(=]\\s*(pal\\.accent|Color\\(0xFF4D3D75\\))(?![\\w])")
        val hosts = kt(ui).filter { it.readText().contains("fun AccentCard(") }
        val bad = kt(ui).filter { it.name != "Theme.kt" && it.name != "Kit.kt" && it !in hosts }.flatMap { f ->
            lines(f).mapIndexedNotNull { i, l -> if (fill.containsMatchIn(code(l)) || code(l).contains("0xFF4D3D75")) at(f, i) else null }
        }
        assertTrue("CR-61: no shared `fun AccentCard(` found", hosts.isNotEmpty())
        assertTrue("CR-61: accent fill (pal.accent / #4D3D75) outside AccentCard (${bad.size}): $bad", bad.isEmpty())
    }

    // ---- CR-62: radius 24 (card) / 26 (accent card) ------------------------------------------------------------------------------------
    @Test fun cr62_cardRadius24AndAccentCardRadius26() {
        val ck = uiFile("CardKit.kt"); val kit = uiFile("Kit.kt")
        assertTrue("CR-62: AccentCardShape must be RoundedCornerShape(26.dp)", Regex("AccentCardShape\\s*=\\s*[\\w.]*RoundedCornerShape\\(26\\.dp\\)").containsMatchIn(ck))
        val card = Regex("fun Modifier\\.card\\([^\\n]*\\n(?:[^\\n]*\\n){0,8}").find(kit)?.value.orEmpty()
        assertTrue("CR-62: Modifier.card must clip/shape to 24 dp (CardShape = RoundedCornerShape(24.dp))", card.contains("24.dp") || Regex("CardShape\\s*=\\s*RoundedCornerShape\\(24\\.dp\\)").containsMatchIn(kit + ck))
        val stray = kt(File(ui, "screens")).flatMap { f -> lines(f).mapIndexedNotNull { i, l -> if (Regex("RoundedCornerShape\\((24|26|28|30|32)\\.dp\\)").containsMatchIn(code(l))) at(f, i) else null } }
        assertTrue("CR-62: card-sized radius literals in screens (use the shared card shapes) (${stray.size}): $stray", stray.isEmpty())
    }

    // ---- CR-23 class (fix wave 3, owner T): every clickable is >= 48 x 48 dp to touch ----------------------------------
    /**
     * A raw `.clickable(` / `.combinedClickable(` / `.selectable(` / `.toggleable(` is a touch box that follows its content. The only
     * place allowed to declare one is `Modifier.pressable` (ui/Press.kt), which puts `minimumInteractiveComponentSize()` (48 x 48 dp,
     * grows with text) in front of the clickable; every screen goes through `pressable`. Allowlist (file -> reason), commented:
     *  - components/EditorPanel.kt `EditorChoice`: its own `heightIn(min = 48.dp)` is on the same modifier chain, inside a Surface.
     *  - components/ExpandingChoice.kt: `heightIn(min = maxOf(optionHeight, 48.dp))` is on the same chain.
     */
    @Test fun cr23_everyClickableGuaranteesA48dpTouchBox() {
        val allow = mapOf("components/EditorPanel.kt" to "heightIn(min = 48.dp)", "components/ExpandingChoice.kt" to "48.dp")
        val raw = Regex("\\.(clickable|combinedClickable|selectable|toggleable|triStateToggleable)\\(")
        val press = File(ui, "Press.kt").readText()
        assertTrue("CR-23: Modifier.pressable must apply minimumInteractiveComponentSize() before its clickable",
            press.indexOf("minimumInteractiveComponentSize()") in 0 until press.indexOf("Modifier.clickable("))
        val bad = kt(root).filter { it.name != "Press.kt" }.flatMap { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val text = f.readText()
            lines(f).mapIndexedNotNull { i, l -> if (raw.containsMatchIn(code(l)) && !(allow[rel]?.let { text.contains(it) } ?: false)) at(f, i) else null }
        }
        assertTrue("CR-23: raw clickable modifier outside Modifier.pressable (use pressable, or allowlist with a reason) (${bad.size}): $bad", bad.isEmpty())
    }

    /** CR-23: the shared 48 dp expansion of `pressable` relies on the platform minimum; nobody may switch it off. */
    @Test fun cr23_noTouchBoxOptOut() {
        val bad = kt(root).flatMap { f -> lines(f).mapIndexedNotNull { i, l -> if (Regex("LocalMinimumInteractiveComponentSize\\s+provides\\s+(Dp\\.Unspecified|0\\.dp)").containsMatchIn(code(l))) at(f, i) else null } }
        assertTrue("CR-23: LocalMinimumInteractiveComponentSize must not be switched off (${bad.size}): $bad", bad.isEmpty())
    }

    /**
     * CR-71 (H6): the period title is ONE shared component. Only PageHeader.kt applies `UiTag.PERIOD_TITLE`; Month.kt reaches it through `PeriodPageHeader(` (which calls `PeriodTitle(`),
     * Spese.kt calls `PeriodTitle(` directly. Nobody else tags a node `period-title` by hand.
     */
    @Test fun cr71_periodTitleIsOneSharedComponent() {
        val tagRe = Regex("UiTag\\.PERIOD_TITLE|\"period-title\"")
        val users = kt(root).filter { f -> f.name != "Tags.kt" && tagRe.containsMatchIn(lines(f).joinToString("\n") { code(it) }) }.map { it.relativeTo(root).path.replace('\\', '/') }
        assertTrue("CR-71: only ui/PageHeader.kt may apply UiTag.PERIOD_TITLE (found in $users)", users == listOf("ui/PageHeader.kt"))
        val page = lines(File(ui, "PageHeader.kt")).joinToString("\n") { code(it) }
        assertTrue("CR-71: PeriodPageHeader must render through PeriodTitle(", Regex("fun PeriodPageHeader[\\s\\S]*?PeriodTitle\\(").containsMatchIn(page))
        val month = File(ui, "screens/Month.kt").readText(); val spese = File(ui, "screens/Spese.kt").readText()
        assertTrue("CR-71: Month.kt must use the shared period title (PeriodPageHeader( or PeriodTitle()", month.contains("PeriodPageHeader(") || month.contains("PeriodTitle("))
        assertTrue("CR-71: Spese.kt must call PeriodTitle(", spese.contains("PeriodTitle("))
    }

    // ---- Layout: a Measurable is measured at most once per MeasureScope ("measure() may not be called multiple times") --------------------
    /** Heuristic, per `Layout(`/`SubcomposeLayout` block (next 40 lines): `measurables.map/mapIndexed/forEach { ..measure(..) }` more than once over the whole list, or one named receiver measured twice. Exceptions: none. */
    @Test fun measurableMeasuredOnceInLayoutBlocks() {
        val bad = ArrayList<String>()
        val whole = Regex("""measurables\.(map|mapIndexed|forEach)\b.*\.measure\(""")
        val named = Regex("""\b(\w+)\.measure\(Constraints""")
        for (f in kt(root)) {
            val ls = lines(f)
            for (i in ls.indices) {
                if (!Regex("""\bLayout\(|SubcomposeLayout""").containsMatchIn(code(ls[i]))) continue
                val blk = (i until minOf(ls.size, i + 40)).map { code(ls[it]) }
                val wholeCount = blk.count { whole.containsMatchIn(it) }
                val receivers = blk.flatMap { l -> named.findAll(l).map { it.groupValues[1] }.filter { it != "it" && it != "m" && it != "measurable" }.toList() }
                if (wholeCount > 1 || receivers.groupBy { it }.any { it.value.size > 1 }) bad += at(f, i)
            }
        }
        assertTrue("a Measurable measured twice in one layout block (${bad.size}); use intrinsics or one measure: $bad", bad.isEmpty())
    }
}
