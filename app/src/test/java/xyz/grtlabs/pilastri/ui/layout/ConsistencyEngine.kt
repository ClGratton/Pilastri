package xyz.grtlabs.pilastri.ui.layout

import java.io.File
import org.json.JSONArray

/**
 * Pure port of `tools/consistency-check.mjs` plus the machine rules of `tools/critique-rules.md` that bounds can measure.
 * Same groups, expected value = the most common value, tolerance +-1 dp, exceptions from `tools/consistency-exceptions.json`.
 * Android-free; [collectNodes] feeds it.
 */
data class N(val id: String, val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val clickable: Boolean = false, val desc: String = "", val fs: Float = 0f, val ov: Boolean = false, val ell: Boolean = false, val echo: Boolean = false) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
}

/** [nodes] = the UNMERGED tree (every tagged / texted node); [targets] = clickable nodes of the MERGED tree (what the user touches). */
data class Shot(val name: String, val layout: String, val top: Boolean, val nodes: List<N>, val width: Float, val height: Float, val targets: List<N> = emptyList())

data class Dev(val screen: String, val rule: String, val el: String, val value: Float, val expected: Float, val note: String = "") {
    /** One REPORT.md style row: screen | rule | element | measured | expected. */
    fun row() = "| $screen | $rule | ${el.replace("|", "/")} | ${r1(value)} dp | ${r1(expected)} dp |" + if (note.isEmpty()) "" else " $note"
}

fun r1(v: Float) = Math.round(v * 10f) / 10f

object Rules {
    const val TOL = 1f

    /** Rule families: one test each. The first word of a rule name picks the family. */
    val families = listOf("card-title", "card-header", "card-inset", "page-header", "card-edge", "label", "chip", "frame", "panes", "touch", "copy", "chart", "figure", "accent", "gutter", "gap", "attention", "card-body", "clip", "motion", "duplicate", "period", "text")

    /** DESIGN.md "## Tokens": values the table fixes are asserted against these, never against the majority. */
    const val CARD_INSET = 14f      // CardKit.CardInset (content to card edge, all sides)
    const val PEEK_FOLD = 40f       // CR-01, user rule 2026-11-09 (tools/critique-rules.md)
    const val PEEK_COVER = 28f
    const val GUTTER = 16f          // page padding, sides
    const val GAP = 8f              // between top-level blocks
    const val ATTENTION_H = 56f     // attention row height


    class Exc(val rule: String, val screen: String, val text: String)

    fun exceptions(): List<Exc> {
        val f = File("../tools/consistency-exceptions.json").takeIf { it.exists() } ?: File("tools/consistency-exceptions.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { val o = a.getJSONObject(it); Exc(o.optString("rule"), o.optString("screen"), o.optString("text")) }
    }

    private fun inside(a: N, c: N) = a.l >= c.l - .5f && a.t >= c.t - .5f && a.r <= c.r + .5f && a.b <= c.b + .5f
    private fun area(n: N) = n.w * n.h

    private class M(val rule: String, val layout: String, val screen: String, val el: String, val value: Float, val group: String)

    fun expectedOf(vals: List<Float>): Float {
        var best = 0f; var bestN = -1
        for (v in vals.sorted()) { val n = vals.count { Math.abs(it - v) <= TOL }; if (n > bestN) { best = v; bestN = n } }
        return best
    }

    /** All deviations over [shots] (one layout class per call; the groups span every screen of it), exceptions already removed. */
    fun check(shots: List<Shot>): List<Dev> = checkVisible(shots.map { sp(it) }.map { it.copy(nodes = visibleNodes(it)) }) + excuse(currencySplit(shots))

    /** CR-72 (wave 8, port of tools/consistency-check.mjs checkCurrencySplit): the RAW text (before [sp]) of a visible node must not join a digit and a currency symbol by U+0020. */
    private val CUR_SPLIT = Regex("\\d +[€\$£]|[€\$£] +\\d")
    fun currencySplit(shots: List<Shot>): List<Dev> {
        val out = ArrayList<Dev>()
        for (s in shots) {
            val seen = HashSet<String>()
            for (n in visibleNodes(s)) {
                if (!CUR_SPLIT.containsMatchIn(n.text) || !seen.add("${n.l}|${n.t}|${n.r}|${n.b}|${n.text}")) continue
                out += Dev(s.name, "text: a figure and its currency symbol are never split by a breakable space (CR-72)", n.text.ifEmpty { n.id }.take(28), 1f, 0f, "use U+00A0 between figure and symbol")
            }
        }
        return out
    }

    /** Wave 7 normalisation (not a loosening; parity with tools/consistency-check.mjs): U+00A0, U+202F, U+2007 are read as U+0020 in every text match, because the app joins
     *  figure and currency with U+00A0 while tools/duplicate-filters.json is written with plain spaces. */
    private val UNI_SP = Regex("[   ]")
    fun sp(s: Shot): Shot = s.copy(nodes = s.nodes.map { it.copy(text = it.text.replace(UNI_SP, " "), desc = it.desc.replace(UNI_SP, " ")) })

    /** Wave 7 loosening (wrong by construction, parity with tools/): above font scale 1.3 (shot names "-fs2-") G-12 lets the page subtitle wrap, so its box height is
     *  compared per line (box / round(box / smallest subtitle box of those shots)). page-title and period-title stay one line (K2) and keep the box height. */
    private fun bigFont(s: Shot) = (Regex("-fs(\\d+)").find(s.name)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f) > 1.3f

    /**
     * Measurement artefact: a pane keeps the previous page composed underneath the new one (back reveals it). Semantics lists nodes in draw order, so
     * every node that precedes a LATER `page-content` of another title, inside that page's pane and bounds, belongs to the covered layer and is not
     * visible: it is dropped. Same page twice (nested tag, same title) is not a layer.
     */
    fun visibleNodes(s: Shot): List<N> {
        val pcs = s.nodes.withIndex().filter { it.value.id == "page-content" }
        if (pcs.size < 2) return s.nodes
        val covered = HashSet<Int>()
        for ((i, pc) in pcs) for ((j, later) in pcs) {
            if (j <= i || later.text == pc.text || later.text.isBlank()) continue
            val sameX = Math.abs(later.l - pc.l) < 2f && later.cx > pc.l && later.cx < pc.r + 1f
            if (!sameX) continue
            for (k in i until j) { val n = s.nodes[k]; if (!n.id.startsWith("pane-") && n.cx >= later.l && n.cx <= later.r + 1f) covered += k }
        }
        return s.nodes.filterIndexed { k, _ -> k !in covered }
    }

    private fun checkVisible(shots: List<Shot>): List<Dev> {
        val m = ArrayList<M>()
        fun add(rule: String, s: Shot, n: N, v: Float, group: String = "") { m += M(rule, s.layout, s.name, n.text.take(28), v, group) }
        val direct = ArrayList<Dev>()
        val dupF = dupFilters(); val dupHits = LinkedHashMap<DupFilter, DupHit>()
        fun tok(rule: String, s: Shot, n: N, v: Float, expected: Float, note: String = "") { if (Math.abs(v - expected) > TOL) direct += Dev(s.name, rule, n.text.ifEmpty { n.id }.take(28), v, expected, note) }
        val subLine = shots.filter { bigFont(it) }.flatMap { s -> s.nodes.filter { it.id == "page-subtitle" && it.h > 0f } }.minOfOrNull { it.h }
        fun headerH(s: Shot, n: N): Float = if (n.id == "page-subtitle" && subLine != null && bigFont(s)) n.h / Math.max(1, Math.round(n.h / subLine)) else n.h
        for (s in shots) {
            val by = { id: String -> s.nodes.filter { it.id == id } }
            val allCards = by("card"); val widest = allCards.maxOfOrNull { it.w } ?: 0f
            val cards = allCards.filter { it.w >= widest * 0.4f }
            // K2 wave 6: the G-11 narrowing is removed; cards inside a period viewport sit on the gutter line like every other card.
            val enclosing = { n: N -> cards.filter { it !== n && inside(n, it) }.minByOrNull { area(it) } }
            val pane = { n: N -> if (s.layout == "fold") (if (n.cx < s.width / 2) "L" else "R") else "P" }
            val trails = by("card-trailing")
            // boundsInRoot is clipped by the scroll viewport: a card that ends at the bottom of the screen is cut, its lower rows are not measurable.
            val frameN = s.nodes.filter { it.id == "dock" || it.id == "island" || it.id == "gear" }
            val clipped = { c: N -> c.b >= s.height - 0.5f || frameN.any { x -> Math.abs(c.b - x.t) < 1f && c.l < x.r && c.r > x.l } }   // cut by the viewport or by the dock / island over its bottom edge
            for (t in by("card-title")) {
                val c = enclosing(t) ?: continue
                if (clipped(c)) continue
                add("card-title top inset", s, t, t.t - c.t); add("card-title left inset", s, t, t.l - c.l); add("card-title height", s, t, t.h)
                add("card-header: title centre below card top", s, t, t.cy - c.t)
                trails.firstOrNull { enclosing(it) === c && Math.abs(it.cy - t.cy) < 40 }?.let { add("card-header: trailing centre minus title centre", s, it, it.cy - t.cy, "zero") }
            }
            val chrome = s.nodes.filter { it.id == "dock" || it.id == "island" || it.id == "gear" }   // the floating frame overlays cards: not card content
            for (c in cards) {
                // Last visible CONTENT: texted / described nodes and `chart` nodes (their own bounds are the visible chart). Not the card's own
                // boxes (full-height merged containers), not `content-row` / `accent-card` boxes (the text inside them is the content).
                // A texted node with the SAME bounds as its full-width clickable row is that row (merged label), not content.
                val rowsAll = s.targets.filter { it.w >= c.w * 0.9f && it.h < c.h * 0.9f && inside(it, c) }
                val raw = s.nodes.filter { n -> rowsAll.none { r -> Math.abs(r.l - n.l) < 1f && Math.abs(r.r - n.r) < 1f && Math.abs(r.t - n.t) < 1f && Math.abs(r.b - n.b) < 1f } }.filter { it !== c && it.id != "card" && it.id != "content-row" && it.id != "accent-card" && it.id != "attention-row" && it.id != "dock-item" && inside(it, c) && chrome.none { ch -> inside(it, ch) } &&
                    (it.id == "chart" || it.id == "icon-disc" || it.text.isNotBlank() || (it.desc.isNotBlank() && it.w < c.w * 0.9f)) && (it.id == "chart" || it.id == "icon-disc" || it.h < c.h * 0.9f) }
                // Measure the OUTERMOST content child: text inside a chip / pill / button is represented by that control's box (merged clickable narrower than the
                // card); text inside a full-width clickable row is a row whose own vertical padding is the row's, so it only counts for left/right.
                val boxes = s.nodes.filter { (it.id == "chip" || it.id == "pill") && it !== c && inside(it, c) }   // merged targets are touch-sized (expanded to 48 dp), not the visible box
                val rowTargets = s.targets.filter { it.w >= c.w * 0.9f && it.h < c.h * 0.9f && inside(it, c) }
                val kids = raw.map { k -> boxes.filter { inside(k, it) }.maxByOrNull { area(it) } ?: k }
                if (kids.isEmpty()) continue
                val bottomKids = raw.filter { k -> rowTargets.none { inside(k, it) } }.map { k -> boxes.filter { inside(k, it) }.maxByOrNull { area(it) } ?: k }
                val narrow = kids.filter { it.w < c.w * 0.98f }.ifEmpty { kids }
                // Right inset: only content that spans the card (chart, trailing text, wide rows); short left-aligned text says nothing about it.
                // Wave 2 fix (item 6): a merely wide text box (wrapped text, >= half the card) does not span the card; padded content's real right edge is the card-body (CR-65).
                val wide = narrow.filter { it.id == "chart" || it.id == "card-trailing" || c.r - it.r < 30f }
                val bot = bottomKids.maxOfOrNull { it.b }
                if (!clipped(c) && bot != null) tok("card-inset bottom (last content to card bottom)", s, bottomKids.first { it.b == bot }, c.b - bot, CARD_INSET)
                // Measurement artefact: a card cut by the viewport bottom keeps only a sliver of its first row in the capture; the sliver may hold just the
                // right-aligned value (its label is below the cut), which is not the leftmost content. Nodes cut by the viewport do not measure the left inset.
                val sideKids = narrow.filter { !(it.b >= s.height - 0.5f && clipped(c)) }
                if (sideKids.isNotEmpty()) { val lft = sideKids.minOf { it.l }; tok("card-inset left (content to card)", s, sideKids.first { it.l == lft }, lft - c.l, CARD_INSET) }
                if (wide.isNotEmpty()) { val rgt = wide.maxOf { it.r }; tok("card-inset right (content to card)", s, wide.first { it.r == rgt }, c.r - rgt, CARD_INSET) }
            }
            for (n in by("setup-title")) { add("page-header setup-title y", s, n, n.t, "setup"); add("page-header setup-title height", s, n, n.h, "setup") }   // R2a: the Setup title (board 8a) is its own role
            for (id in listOf("page-title", "page-subtitle")) for (n in by(id)) {
                val g = if (s.top) "top" + pane(n) else "any"   // on two panes each pane carries its own page header
                add("page-header $id y", s, n, n.t, g); add("page-header $id height", s, n, headerH(s, n), "any")   // per line for a wrapped fs2 subtitle (wave 7); majority: text boxes under Robolectric are not the Tokens line heights
                if (s.top) add("page-header $id x (top-level pages)", s, n, n.l, g)
            }
            for (c in cards) if (enclosing(c) == null) add("card-edge right (per pane)", s, c, c.r, s.name + pane(c))
            direct += blocks(s)
            direct += cardBody(s, clipped)
            direct += textClip(s)
            direct += textOverlap(s)
            direct += peek(s)
            direct += duplicates(s, dupF, dupHits)
            direct += periodTitle(s) { rule, n, v -> add(rule, s, n, v, "any") }
            // The card under the label = nearest one overlapping it horizontally; a full-width label is in neither pane, two half-width cards share its row and the left one names it.
            val below = { n: N -> cards.filter { it.t >= n.b - 1 && Math.min(it.r, n.r) - Math.max(it.l, n.l) > 1f && enclosing(it) == null }.sortedWith(compareBy({ it.t }, { it.l })).firstOrNull() }
            // A neighbour label peeks into the strip: only a few dp of its text are inside the pager viewport, its position is the pager geometry, not an alignment.
            for (n in by("period-label-prev").filter { it.w >= 16f }) below(n)?.let { add("label period prev left minus card left", s, n, n.l - it.l, "zero") }
            for (n in by("period-label-next").filter { it.w >= 16f }) below(n)?.let { add("label period next right minus card right", s, n, n.r - it.r, "zero") }
            for (n in by("section-label")) below(n)?.let { add("label section left minus card left", s, n, n.l - it.l, "zero") }
            for (n in by("chip")) if (n.b < s.height - 0.5f) add("chip height", s, n, n.h)   // a chip cut by the viewport bottom is not measurable
            if (s.top) {
                // CR-05: first content starts at the same top on the five top-level pages (proposed tag page-content).
                val pc = by("page-content")
                if (pc.isEmpty()) direct += Dev(s.name, "page-header: tag page-content missing (CR-05 first content top)", "-", -1f, 0f, "request tag")
                else add("page-header content top (CR-05)", s, pc.first(), pc.first().t, "top")
                // CR-40: exactly one primary figure per top-level page, in the upper 40 % (proposed tag primary-figure).
                val pf = by("primary-figure")
                val half = s.width / 2
                val nPane = if (s.layout == "fold") maxOf(pf.count { it.cx < half }, pf.count { it.cx >= half }) else pf.size
                val base = s.name.substringAfter("top-", "")
                if (nPane > 1 || (pf.isEmpty() && base in accentScreens)) direct += Dev(s.name, "figure: one primary-figure per pane on the board screens, at most one elsewhere (CR-40)", "count", pf.size.toFloat(), 1f, if (pf.isEmpty()) "request tag primary-figure" else "")
                else if (pf.isNotEmpty() && pf.minOf { it.t } > s.height * 0.4f) direct += Dev(s.name, "figure: primary-figure top within 40 % of height (CR-40)", pf[0].text.take(28), pf.minOf { it.t }, s.height * 0.4f)
            }
            direct += accentAndFigures(s, by)
            direct += attention(s, by)
            if (s.top) direct += frame(s)   // the frame (dock / island / gear) is only around the top-level screens in these captures
            direct += panes(s, by)
            // CR-23: every clickable >= 48 x 48 dp. CR-33: no text longer than 70 chars on a working screen. CR-43: every chart has a description.
            // CR-23: merged clickable nodes only (the user's real targets), deduped by bounds + label.
            for (n in s.targets.distinctBy { listOf(r1(it.l), r1(it.t), r1(it.r), r1(it.b), it.text.ifEmpty { it.desc }) }.filter { it.w >= 1f && it.h >= 1f })
                if (!(n.b >= s.height - 0.5f && n.h < 48f) /* cut by the viewport bottom: not a real target size */ && !(n.t <= 0.5f && n.h < 48f) /* cut by the window top (scrolled under it): same */ &&
                    !((n.w < 48f - TOL || n.h < 48f - TOL) && s.nodes.any { x -> (x.id == "dock" || x.id == "island" || x.id == "gear") && n.l < x.r + 0.5f && n.r > x.l - 0.5f && n.t < x.b + 0.5f && n.b > x.t - 0.5f && (Math.abs(n.b - x.t) < 1f || Math.abs(n.t - x.b) < 1f || Math.abs(n.r - x.l) < 1f || Math.abs(n.l - x.r) < 1f) }) /* trimmed by the dock / island overlay */ && (n.w < 48f - TOL || n.h < 48f - TOL)) direct += Dev(s.name, "touch: clickable at least 48 x 48 dp (CR-23)", (n.text.ifEmpty { n.desc }.ifEmpty { n.id.ifEmpty { "(unnamed)" } }).take(28), Math.min(n.w, n.h), 48f)
            for (n in s.nodes) if (n.text.length > 70) direct += Dev(s.name, "copy: text longer than 70 chars on a working screen (CR-33)", n.text.take(28), n.text.length.toFloat(), 70f)
            for (n in by("chart")) if (n.desc.isBlank()) direct += Dev(s.name, "chart: chart without contentDescription (CR-43)", n.id, 0f, 1f)
        }
        lastDupReport = if (dupF.isEmpty()) "" else "Duplicate filters applied (CR-70):\n" + dupF.joinToString("\n") { f -> val h = dupHits[f]; "  screen=${f.screen} id=${f.id.ifEmpty { "*" }} text=${f.text.ifEmpty { "." }} silenced=${h?.n ?: 0} screens=${h?.screens?.joinToString(",") ?: "-"} reason=${f.reason}" }
        val groups = m.groupBy { listOf(it.rule, it.layout, it.group).joinToString("|") }
        val dev = ArrayList<Dev>()
        for ((_, ms) in groups) {
            val exp = if (ms[0].group == "zero") 0f else expectedOf(ms.map { it.value })
            for (x in ms) if (Math.abs(x.value - exp) > TOL) dev += Dev(x.screen, x.rule, x.el, x.value, exp)
        }
        dev += direct
        val ex = exceptions()
        return dev.filter { d -> ex.none { (it.rule.isEmpty() || d.rule.contains(it.rule)) && (it.screen.isEmpty() || d.screen.contains(it.screen)) && (it.text.isEmpty() || d.el.contains(it.text)) } }
    }


    // ---- CR-70 / CR-71 (2026-11-09, final spec output/agent-handoffs-2026-11-08/dup-general-2.md): port of `checkDup` + `factsOf` + `dateKeys` in tools/consistency-check.mjs ----
    private val MON = mapOf("gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6, "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6, "lug" to 7, "ago" to 8, "set" to 9, "sett" to 9, "ott" to 10, "nov" to 11, "dic" to 12)
    private val MRE = MON.keys.sortedByDescending { it.length }.joinToString("|")
    private val FULL = MON.keys.filter { it.length > 4 }.joinToString("|")
    private const val SEP = "(?:–|—|-|→|\\bal\\b)"
    private val R_YEAR_RANGE = Regex("($MRE)\\s*(\\d{4})\\s*$SEP\\s*($MRE)\\s*(\\d{4})")
    private val R_DAY_RANGE = Regex("(\\d{1,2})(?:\\s*($MRE)\\b)?\\s*$SEP\\s*(\\d{1,2})\\s*($MRE)\\b")
    private val R_DAY = Regex("(\\d{1,2})\\s+($MRE)\\b")
    private val R_FULL = Regex("\\b($FULL)\\b")
    private val R_BARE = Regex("^[‹›\\s]*\\d{1,2}(\\s*($MRE))?(\\s*$SEP\\s*\\d{1,2}\\s*($MRE))?[‹›\\s]*$")
    /** The base token of CR-70: a digit run with its attached word or unit (the .mjs TOKEN, same stop words). */
    private val TOKEN = Regex("(?<![\\d.,])[−–+\\\$£-]*(\\d[\\d.,:/]*)(?:\\s*(€|%|[a-zà-ÿ]{2,}))?", setOf(RegexOption.IGNORE_CASE))
    private val UNIT_STOP = setOf("di", "da", "al", "e", "a", "il", "in", "su", "con", "per", "of", "to", "at", "on", "the", "and")

    /** One element-level silencing entry of `tools/duplicate-filters.json`: screen glob, resource-id (optional), text regex, reason. */
    class DupFilter(val screen: String, val id: String, val text: String, val reason: String) {
        private val screenRe = Regex("^" + screen.split("*").joinToString(".*") { Regex.escape(it) } + "$")
        private val textRe = Regex(text.ifEmpty { "." }, RegexOption.IGNORE_CASE)
        fun matchesScreen(name: String) = screenRe.matches(name)
        fun matches(n: N) = (id.isEmpty() || id == n.id) && textRe.containsMatchIn(n.text)
    }
    class DupHit { var n = 0; val screens = LinkedHashSet<String>() }

    fun dupFilters(): List<DupFilter> {
        val f = File("../tools/duplicate-filters.json").takeIf { it.exists() } ?: File("tools/duplicate-filters.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("screen").isNotEmpty() }.map { DupFilter(it.getString("screen"), it.optString("id"), it.optString("text"), it.optString("reason")) }
    }

    /** Screen globs the duplicate check applies to: `tools/duplicate-scope.json` (same source as the .mjs checkers). User, 2026-11-09: repetition is judged on a single screen, not on the unfolded two-pane layout. Absent file = every screen. */
    fun dupScope(): List<Regex>? {
        val f = File("../tools/duplicate-scope.json").takeIf { it.exists() } ?: File("tools/duplicate-scope.json")
        if (!f.exists()) return null
        val a = org.json.JSONObject(f.readText()).optJSONArray("screens") ?: return null
        return (0 until a.length()).map { g -> Regex("^" + a.getString(g).split("*").joinToString(".*") { Regex.escape(it) } + "$") }
    }

    /** Applied-count report of the last [check] (what the .mjs prints as "Duplicate filters applied"). */
    @Volatile var lastDupReport: String = ""

    /** BASE value tokens (the .mjs `factsOf`): every digit token with its attached word or unit; a plain integer without a unit is skipped. No language. */
    fun factsOf(text: String): Set<String> {
        val keys = LinkedHashSet<String>()
        for (m in TOKEN.findAll(text.lowercase())) {
            val d = m.groupValues[1].replace(Regex("[.,:/]+$"), "").replace(Regex("[,.]00$"), "")
            var u = m.groupValues[2].lowercase(); if (u in UNIT_STOP) u = ""
            if (u.isEmpty() && d.all { it.isDigit() }) continue
            keys += "V:$d$u"
        }
        return keys
    }

    /** ADD-ON dateRanges (Italian): R = range, E = endpoint of a range or bare date, D = date in a sentence, M = month name, MY = month + year. A range matches its own endpoints and bare dates, not sentence dates. */
    fun dateKeys(text: String, isBare: Boolean = false): Set<String> {
        var t = text.lowercase(); val keys = LinkedHashSet<String>()
        t = R_YEAR_RANGE.replace(t) { m -> val g = m.groupValues; keys += "R:${MON[g[1]]}/${g[2]}-${MON[g[3]]}/${g[4]}"; keys += "MY:${MON[g[1]]}/${g[2]}"; keys += "MY:${MON[g[3]]}/${g[4]}"; " " }
        t = R_DAY_RANGE.replace(t) { m ->
            val g = m.groupValues; val a = "${g[1].toInt()}/${MON[g[2].ifEmpty { g[4] }]}"; val b = "${g[3].toInt()}/${MON[g[4]]}"
            keys += "R:$a-$b"; keys += "E:$a"; keys += "E:$b"; " "
        }
        t = R_DAY.replace(t) { m -> keys += "${if (isBare) "E" else "D"}:${m.groupValues[1].toInt()}/${MON[m.groupValues[2]]}"; " " }
        for (m in R_FULL.findAll(t)) keys += "M:${MON[m.groupValues[1]]}"
        return keys
    }

    /** Largest font size inside [n] (a tagged container carries no layout of its own); a node with no text layout falls back to its height. */
    private fun textSize(s: Shot, n: N): Float = if (n.fs > 0f) n.fs else s.nodes.filter { it.fs > 0f && inside(it, n) }.maxOfOrNull { it.fs } ?: n.h

    /**
     * CLASS RULE (2026-11-10, user: "judge by meaning, not by text"): identical texts in SIBLING ITEMS of one repeated component are each item's own attribute, not one fact repeated. Two nodes with the
     * same normalised text are siblings when they sit on one line of a repeated layout (same top and bottom with disjoint columns: items in a row; same left and right with disjoint rows: items in a list) AND each
     * has an owner context (the other texts sharing its column, or its row band, outside its own line) that differs between the two. Identical contexts, no context or another alignment (a header and a card) stay
     * flagged. Mirrors `siblingClasses` of tools/consistency-check.mjs. Returns node -> class id (nodes in no class are absent).
     */
    fun siblingClasses(nodes: List<N>, norm: (N) -> String): Map<N, String> {
        fun ov(a0: Float, a1: Float, b0: Float, b1: Float) = maxOf(0f, minOf(a1, b1) - maxOf(a0, b0))
        fun owners(a: N, horiz: Boolean): String {
            val set = sortedSetOf<String>()
            for (m in nodes) {
                if (m === a || m.text.isBlank() || norm(m) == norm(a)) continue
                if (m.l <= a.l + 1 && m.r >= a.r - 1 && m.t <= a.t + 1 && m.b >= a.b - 1) continue   // a container of the node is not its owner
                val along = if (horiz) ov(a.l, a.r, m.l, m.r) >= 0.5f * minOf(a.w, m.w) else ov(a.t, a.b, m.t, m.b) >= 0.5f * minOf(a.h, m.h)
                val apart = if (horiz) ov(a.t, a.b, m.t, m.b) < 0.5f * a.h else ov(a.l, a.r, m.l, m.r) < 0.5f * a.w
                if (along && apart) set += norm(m)
            }
            return set.joinToString("")
        }
        val out = HashMap<N, String>()
        for (g in nodes.groupBy(norm).values) {
            if (g.size < 2) continue
            val par = IntArray(g.size) { it }
            fun find(x: Int): Int { var y = x; while (par[y] != y) { par[y] = par[par[y]]; y = par[y] }; return y }
            for (i in g.indices) for (j in i + 1 until g.size) {
                val a = g[i]; val b = g[j]
                val row = Math.abs(a.t - b.t) <= 1f && Math.abs(a.b - b.b) <= 1f && ov(a.l, a.r, b.l, b.r) == 0f
                val list = Math.abs(a.l - b.l) <= 1f && Math.abs(a.r - b.r) <= 1f && ov(a.t, a.b, b.t, b.b) == 0f
                if (!row && !list) continue
                val oa = owners(a, row); val ob = owners(b, row)
                if (oa.isNotEmpty() && ob.isNotEmpty() && oa != ob) par[find(i)] = find(j)
            }
            for (i in g.indices) if (par.indices.any { it != i && find(it) == find(i) }) out[g[i]] = norm(g[0]) + "#" + find(i)
        }
        return out
    }

    /**
     * CR-70, whole screen, no pane / sibling / row exemption: (a) identical normalised text (whitespace, case) in two nodes; (b) a base value token in two nodes; (c) the date add-on keys.
     * Symbol-only nodes ("‹", "›", "—") are not compared; a [filters] entry (screen glob + id + text regex) silences ONE element and its applied count is kept in [hits].
     * The only non-spec step (wrong by construction): a TAGGED node without a Text of its own is given the text of its first descendant (collectNodes `texts(n)`, flag [N.echo]); it is
     * that descendant seen a second time (one on-screen element), not another element, so echo nodes are not compared. Their Text child (when it is a node) is.
     */
    fun duplicates(s0: Shot, filters: List<DupFilter>, hits: MutableMap<DupFilter, DupHit>): List<Dev> {
        val s = sp(s0)   // wave 7: Unicode spaces read as U+0020 (tests call duplicates() directly)
        dupScope()?.let { sc -> if (sc.none { it.matches(s.name) }) return emptyList() }   // scope by config, not by filters
        val flt = filters.filter { it.matchesScreen(s.name) }
        val ws = Regex("\\s+")
        val norm = { n: N -> n.text.trim().lowercase().replace(ws, " ") }
        val bare = { n: N -> R_BARE.matches(n.text.lowercase().trim()) }
        val seenNode = HashSet<String>()
        val cand0 = ArrayList<N>()
        for (n in s.nodes) {
            if (n.text.isBlank() || !n.text.any { it.isLetterOrDigit() }) continue
            val k5 = listOf(n.l, n.t, n.r, n.b, n.text).joinToString("|"); if (!seenNode.add(k5)) continue
            val f = flt.firstOrNull { it.matches(n) }
            if (f != null) { val h = hits.getOrPut(f) { DupHit() }; h.n++; h.screens += s.name; continue }
            cand0 += n
        }
        val cand = cand0.filter { !it.echo }
        val cls = siblingClasses(cand, norm)
        val idx = LinkedHashMap<String, ArrayList<N>>()
        for (n in cand) for (k in factsOf(n.text) + dateKeys(n.text, bare(n)) + "T:${norm(n)}") idx.getOrPut(k) { ArrayList() } += n
        val seen = LinkedHashMap<String, Pair<List<N>, ArrayList<String>>>()
        for ((k, hit0) in idx) {
            // sibling twins (class rule, see [siblingClasses]) are not compared with each other
            val hit = hit0.filterIndexed { i, n -> cls[n] == null || hit0.indexOfFirst { cls[it] == cls[n] } == i }
            if (hit.size < 2) continue
            val sig = hit.map { "${it.l},${it.t}" }.sorted().joinToString(";")
            seen.getOrPut(sig) { hit to ArrayList() }.second += k
        }
        return seen.values.map { (hit, keys) ->
            Dev(s.name, "duplicate: a text or value appears once per screen (CR-70)", hit.joinToString(" + ") { "\"" + it.text.take(28) + "\"" }, hit.size.toFloat(), 1f, "fact ${keys.joinToString(", ")} [${hit.joinToString(", ") { it.id.ifEmpty { "-" } }}]")
        }
    }

    /**
     * CR-71: a pane with a `page-title` and a `period-viewport` (not the Pilastri hero, which has `period-label-cur`) exposes `period-title`, same height on every page (majority, through [addHeight]),
     * and no other header text (page-title, page-subtitle, page-trailing, chip) is larger. Text size is the font size from the text layout (Robolectric line boxes are not the Tokens heights).
     */
    private fun periodTitle(s: Shot, addHeight: (String, N, Float) -> Unit): List<Dev> {
        val out = ArrayList<Dev>()
        fun d(rule: String, el: String, v: Float, e: Float, note: String = "") { out += Dev(s.name, rule, el, v, e, note) }
        val twoPanes = s.layout == "fold" && s.top
        val cut = { n: N -> if (twoPanes) (if (n.cx < s.width / 2) "L" else "R") else "P" }
        var qualifying = 0
        for (p in if (twoPanes) listOf("L", "R") else listOf("P")) {
            val inP = { n: N -> cut(n) == p }
            if (s.nodes.none { it.id == "page-title" && inP(it) } || s.nodes.none { it.id == "period-viewport" && inP(it) } || s.nodes.any { it.id == "period-label-cur" && inP(it) }) continue
            qualifying++
            val pts = s.nodes.filter { it.id == "period-title" && inP(it) }
            if (pts.isEmpty()) { d("period: swipe page exposes its current period in a `period-title` node (CR-71)", "", 0f, 1f, "request tag period-title"); continue }
            for (n in pts) addHeight("period: period-title height (same on every period page, CR-71)", n, n.h)
            val pt = pts.maxOf { textSize(s, it) }
            for (n in s.nodes.filter { it.id in setOf("page-subtitle", "page-trailing", "chip") && it.text.isNotBlank() && inP(it) && pts.none { q -> Math.abs(q.l - it.l) < 1f && Math.abs(q.t - it.t) < 1f && Math.abs(q.b - it.b) < 1f } })
                if (textSize(s, n) > pt + TOL) d("period: period-title is the tallest text of the page header (CR-71)", n.text.take(28), pt, textSize(s, n), "header text larger")
        }
        // Vacuity guard (JVM only): the suite's Mese and Spese shots must really be swipe pages, otherwise CR-71 checked nothing.
        if ((s.name.endsWith("-mese") || s.name.endsWith("-top-spese")) && qualifying == 0)
            d("period: Mese and Spese render a page-title and a period-viewport (CR-71, vacuity guard)", s.name, 0f, 1f, "request tags page-title, period-viewport")
        return out
    }

    private val bodyContainers =setOf("card", "card-body", "card-body-rows", "card-header", "content-row", "accent-card", "attention-row", "dock-item", "action-row", "page-content")
    private val frameIds = setOf("dock", "island", "gear")

    /**
     * CR-65 (wave 2 rule 1): card-body sits 14 dp (+-1) from its card on all four sides; its first / last visible content touches body top / bottom / start, end when it spans.
     * R2a: the first content of a titled card is the fixed 32 dp `card-header` row (K3), not the title's line box (Robolectric line boxes are 35 px, the dump's are the real ~16 dp: that is
     * why the JVM passed while the device did not); the header is one content unit, nodes inside it are not measured separately. A ROW card (`card-body-rows`: rows keep their own 10 dp
     * inset and 48 dp minimum height, text may follow an untagged leading glyph) is measured by its header and its end only. A sliver (body < 12 dp) or a body under the dock / island is cut.
     */
    private fun cardBody(s: Shot, clipped: (N) -> Boolean): List<Dev> {
        val out = ArrayList<Dev>()
        val cards = s.nodes.filter { it.id == "card" }
        val chrome = s.nodes.filter { it.id in frameIds }
        for (b in s.nodes.filter { it.id == "card-body" || it.id == "card-body-rows" }) {
            val c = cards.filter { it !== b && inside(b, it) }.minByOrNull { area(it) } ?: continue
            if (chrome.any { b.t >= it.t - .5f && b.l < it.r && b.r > it.l }) continue
            val sliver = b.h < 12f
            val rows = b.id == "card-body-rows"
            val cut = sliver || clipped(c)
            fun inset(side: String, v: Float) { if (Math.abs(v - CARD_INSET) > TOL) out += Dev(s.name, "card-body: inset $side is 14 dp (CR-65)", b.text.ifEmpty { b.id }.take(28), v, CARD_INSET) }
            inset("left", b.l - c.l); inset("right", c.r - b.r)
            if (!sliver) inset("top", b.t - c.t)
            if (!cut) inset("bottom", c.b - b.b)
            val headers = s.nodes.filter { it.id == "card-header" && inside(it, b) }
            val boxes = s.nodes.filter { (it.id == "chip" || it.id == "pill") && inside(it, b) }
            val raw = s.nodes.filter { n -> n !== b && n.id !in bodyContainers && inside(n, b) && chrome.none { inside(n, it) } && headers.none { inside(n, it) } &&
                (n.id == "chart" || n.id == "icon-disc" || n.id == "pill" || n.id == "chip" || n.text.isNotBlank()) && (n.id == "chart" || n.h < b.h * 0.9f) }
            val body = raw.map { k -> boxes.filter { inside(k, it) }.maxByOrNull { area(it) } ?: k }.distinct()
            val kids = headers + (if (rows) emptyList() else body)
            if (kids.isEmpty()) continue
            fun touch(what: String, v: Float, k: N) { if (Math.abs(v) > TOL) out += Dev(s.name, "card-body: first / last content touches the body $what (CR-65)", k.text.ifEmpty { k.id }.take(28), v, 0f) }
            if (!sliver) kids.minByOrNull { it.t }!!.let { touch("top", it.t - b.t, it) }
            if (!cut && !rows) kids.maxByOrNull { it.b }!!.let { touch("bottom", b.b - it.b, it) }
            if (!rows) kids.filter { !(cut && it.b >= s.height - 0.5f) }.minByOrNull { it.l }?.let { touch("start", it.l - b.l, it) }
            val spans = headers + body.filter { it.id == "chart" || it.id == "card-trailing" || b.r - it.r < 30f }
            spans.maxByOrNull { it.r }?.let { touch("end", b.r - it.r, it) }
        }
        return out
    }

    private val labelIds = setOf("pill", "chip", "section-label", "stat-label", "card-title", "card-trailing", "page-title", "page-subtitle", "primary-figure", "secondary-figure")

    /** CR-66 (wave 2 rule 2): no text clipped. Layout verdicts (overflow, ellipsis) come from the TextLayoutResult; the box check is geometric. */
    fun textClip(s: Shot): List<Dev> {
        val out = ArrayList<Dev>()
        val boxes = s.nodes.filter { it.id == "pill" || it.id == "chip" || it.id == "card" || it.id == "action-row" }
        for (n in s.nodes) {
            if (n.text.isBlank()) continue
            val el = n.text.take(28)
            if (n.ov) out += Dev(s.name, "clip: text has visual overflow (CR-66)", el, 1f, 0f)
            val guarded = n.id in labelIds || s.nodes.any { (it.id == "pill" || it.id == "chip" || it.id == "action-row") && inside(n, it) } || s.targets.any { it.w < 1000f && inside(n, it) && it.h < 100f }
            if (n.ell && guarded) out += Dev(s.name, "clip: ellipsis inside a pill, chip, clickable or label (CR-66)", el, 1f, 0f)
            if (n.fs <= 0f) continue   // only the node that owns the text layout: a tagged container repeats its child's text
            for (bx in boxes) {
                if (bx === n || !(n.cx > bx.l && n.cx < bx.r && n.cy > bx.t && n.cy < bx.b)) continue
                if (n.l < bx.l - TOL || n.r > bx.r + TOL) out += Dev(s.name, "clip: text box wider than its ${bx.id} (CR-66)", el, n.w, bx.w)
            }
        }
        return out
    }

    /**
     * CR-69 (F2b): two visible texts of one screen must not overlap (the "Ottob|Novembre" defect: period labels sharing a row). Text owners only (fs > 0: a tagged container repeats its
     * child's text), each box clamped to the window (a node cut by the viewport counts by its visible part); the boxes intersect by more than 1 dp in BOTH axes. A pair where one text
     * contains the other is one label seen twice (container + leaf). Rest snapshots only: a moving snapshot has pages mid-transition.
     */
    fun textOverlap(s: Shot): List<Dev> {
        val out = ArrayList<Dev>()
        val t = s.nodes.filter { it.fs > 0f && it.text.isNotBlank() }
        for (i in t.indices) for (j in i + 1 until t.size) {
            val a = t[i]; val b = t[j]
            if (a.text.contains(b.text) || b.text.contains(a.text)) continue
            val ix = Math.min(Math.min(a.r, b.r), s.width) - Math.max(Math.max(a.l, b.l), 0f)
            val iy = Math.min(a.b, b.b) - Math.max(a.t, b.t)
            if (ix > TOL && iy > TOL) out += Dev(s.name, "clip: texts overlap (CR-69)", a.text.take(14) + " / " + b.text.take(14), ix, 0f)
        }
        return out
    }

    /** Wave 2 rule 3 (K2 wave 6): the neighbour page of a pager is visible in the gutter margin = gutter - gap (8 dp), cover and open Fold (+-1). Applies to a rest snapshot. */
    fun peek(s: Shot): List<Dev> {
        val out = ArrayList<Dev>()
        val exp = if (s.layout == "fold") PEEK_FOLD else PEEK_COVER
        for (v in s.nodes.filter { it.id == "period-viewport" }) for (id in listOf("period-page-prev", "period-page-next")) {
            val p = s.nodes.filter { it.id == id && it.cy > v.t && it.cy < v.b }.minByOrNull { Math.abs(it.cx - v.cx) } ?: continue
            // Visible = the page's overlap with the viewport and the window (a clipped node already is; every clipping ancestor counts, CR-67).
            val vis = Math.max(0f, Math.min(p.r, Math.min(v.r, s.width)) - Math.max(p.l, Math.max(v.l, 0f)))
            if (vis < exp - TOL) out += Dev(s.name, "motion: neighbour page peek >= " + exp.toInt() + " dp (CR-01, user rule)", id, vis, exp)
        }
        return excuse(out)
    }

    /** Wave 2 rule 4: in a held-drag snapshot every period label moves like its page (+-1 dp); a snapshot identical to rest fails (vacuity guard). */
    fun pair(name: String, layout: String, rest: List<N>, moving: List<N>, titleMoving: List<N> = moving): List<Dev> {
        val out = ArrayList<Dev>()
        val same = rest.size == moving.size && rest.indices.all { i -> val a = rest[i]; val b = moving[i]; a.id == b.id && Math.abs(a.l - b.l) < .5f && Math.abs(a.t - b.t) < .5f && Math.abs(a.r - b.r) < .5f && Math.abs(a.b - b.b) < .5f }
        if (same) out += Dev(name, "motion: moving snapshot identical to rest (vacuity guard, CR-68)", "-", 0f, 1f)
        fun near(n: N, list: List<N>) = list.filter { it.id == n.id && Math.abs(it.t - n.t) < 1f }.minByOrNull { Math.abs(it.l - n.l) + Math.abs(it.t - n.t) }
        fun agree(a0: N, a1: N, p0: N, p1: N) = Math.abs((a1.l - a0.l) - (p1.l - p0.l)) <= TOL || Math.abs((a1.r - a0.r) - (p1.r - p0.r)) <= TOL
        val pages = rest.filter { it.id.startsWith("period-page-") && it.id != "period-page-viewport" }
        if (rest.any { it.id.startsWith("period-label-") } && pages.isEmpty()) out += Dev(name, "motion: period pages are not tagged (CR-68)", "period-page-*", 0f, 1f)
        for (l in rest.filter { it.id.startsWith("period-label-") }) {
            // Each label is paired with the page that CONTAINS it (D4: labels belong to their page): the page whose horizontal extent holds the label, nearest below it.
            val p0 = pages.filter { l.cx >= it.l - 1f && l.cx <= it.r + 1f && it.t >= l.b - 1f }.minByOrNull { it.t - l.b }
            if (p0 == null) { out += Dev(name, "motion: period label is inside no period page (CR-68)", l.id + " " + l.text.take(14), l.cx, 0f); continue }
            val p1 = near(p0, moving.filter { it.id == p0.id }) ?: continue
            val l1 = near(l, moving) ?: continue
            if (!agree(l, l1, p0, p1)) out += Dev(name, "motion: period label moves like its page (CR-68)", l.id + " " + l.text.take(14), l1.l - l.l, p1.l - p0.l)
        }
        // M1 extension (wave 6, R2-M1; user 2026-11-08 "se cose appartengono allo stesso elemento, quasi sempre avranno lo stesso movimento"): a `period-title` on a pane with a
        // `period-viewport` is displaced like that pane's `period-page-cur` (same direction, +-TOL), the page measured on the edge that does not touch its clip box.
        for (t in rest.filter { it.id == "period-title" && it.w > 0f }) {
            val vp = rest.firstOrNull { it.id == "period-viewport" && t.cx >= it.l - 1f && t.cx <= it.r + 1f } ?: continue
            val p0 = rest.filter { it.id == "period-page-cur" && it.l >= vp.l - 1f && it.r <= vp.r + 1f && it.t >= t.b - 1f }.minByOrNull { it.t } ?: continue
            // Harness (wave 6 P3): the title is measured on a SHORT held drag (titleMoving, shorter than the title is wide): on the long one it slides fully out of its clip slot and its bounds are zero-width.
            val p1 = near(p0, titleMoving.filter { it.id == p0.id }) ?: continue
            val t1 = near(t, titleMoving.filter { it.id == "period-title" && it.w > 0f })
            if (t1 == null) { out += Dev(name, "capture: period-title absent or zero-width in the moving dump while its rest dump has it (CR-68)", "period-title " + t.text.take(14), 0f, t.w); continue }   // never a silent pass
            val clipL = p1.l <= vp.l + .5f || p0.l <= vp.l + .5f; val clipR = p1.r >= vp.r - .5f || p0.r >= vp.r - .5f
            if (clipL && clipR) continue
            val pdx = if (clipL) p1.r - p0.r else p1.l - p0.l
            if (!(Math.abs((t1.l - t.l) - pdx) <= TOL || Math.abs((t1.r - t.r) - pdx) <= TOL)) out += Dev(name, "motion: period-title moves like its page (CR-68, M1)", "period-title " + t.text.take(14), t1.l - t.l, pdx)
            // Wave 8 (owner J, M1 on the whole period header group): page-subtitle inside the page-header that holds the period-title, every period-chip and period-subtitle of this pane
            // are displaced by the same dx. Present at rest but absent or zero-width while moving = explicit capture row.
            val hdr = rest.filter { it.id == "page-header" && inside(t, it) }.minByOrNull { area(it) }
            val members = rest.filter { it.w > 0f && ((it.id == "page-subtitle" && hdr != null && inside(it, hdr)) || ((it.id == "period-chip" || it.id == "period-subtitle") && it.cx >= vp.l - 1f && it.cx <= vp.r + 1f)) }
            for (mb in members) {
                val m1 = near(mb, titleMoving.filter { it.id == mb.id && it.w > 0f })
                if (m1 == null) { out += Dev(name, "capture: ${mb.id} absent or zero-width in the moving dump while its rest dump has it (CR-68)", "${mb.id} " + mb.text.take(14), 0f, mb.w); continue }
                if (!(Math.abs((m1.l - mb.l) - pdx) <= TOL || Math.abs((m1.r - mb.r) - pdx) <= TOL)) out += Dev(name, "motion: period header moves like its page (CR-68, M1)", "${mb.id} " + mb.text.take(14), m1.l - mb.l, pdx)
            }
        }
        return excuse(out)
    }

    private fun excuse(list: List<Dev>): List<Dev> { val ex = exceptions(); return list.filter { d -> ex.none { (it.rule.isEmpty() || d.rule.contains(it.rule)) && (it.screen.isEmpty() || d.screen.contains(it.screen)) && (it.text.isEmpty() || d.el.contains(it.text)) } } }

    private fun isBlock(n: N) = n.id == "card" || n.id == "accent-card" || n.id == "attention-row"

    /** CR-62: top-level blocks sit on the 16 dp gutter and 8 dp apart (Tokens "Rhythm"). Radius is a source rule (CardKit). */
    private fun blocks(s: Shot): List<Dev> {
        val out = ArrayList<Dev>()
        val all = s.nodes.filter { isBlock(it) }
        val widest = all.maxOfOrNull { it.w } ?: return out
        val top = all.filter { it.w >= widest * 0.4f }.filter { c -> all.none { o -> o !== c && inside(c, o) && area(o) > area(c) } }
        val half = s.width / 2
        for (b in top) {
            if (s.layout == "fold" && !s.top && b.cx >= half) continue
            val rightPane = s.layout == "fold" && s.top && b.cx >= half
            // Wave 12 (user 2026-11-09: UX beats alignment): a card inside a period viewport is inset by peek + gap, never narrower.
            val swipes = s.nodes.any { v -> v.id == "period-viewport" && b.l >= v.l + 20f && b.r <= v.r - 20f && b.t >= v.t - 1f && b.b <= v.b + 1f }
            val extra = if (swipes) (if (s.layout == "fold") PEEK_FOLD else PEEK_COVER) + GAP - GUTTER else 0f
            val leftEdge = (if (rightPane) half + GUTTER else GUTTER) + extra
            // K2 wave 6: no narrowing accepted; a card inside a period viewport is on the gutter line like every other block.
            if (Math.abs(b.l - leftEdge) > TOL) out += Dev(s.name, "gutter: block left edge on the 16 dp gutter (CR-62)", b.text.ifEmpty { b.id }.take(28), b.l, leftEdge)
            if (s.layout == "phone" && Math.abs((s.width - b.r) - GUTTER - extra) > TOL) out += Dev(s.name, "gutter: block right edge on the 16 dp gutter (CR-62)", b.text.ifEmpty { b.id }.take(28), s.width - b.r, GUTTER + extra)
        }
        val paneOf = { n: N -> if (s.layout == "fold" && n.cx >= half) 1 else 0 }
        for (p in 0..1) {
            val col = top.filter { paneOf(it) == p }.sortedBy { it.t }
            for (i in 1 until col.size) {
                val a = col[i - 1]; val b = col[i]
                val overlap = Math.min(a.r, b.r) - Math.max(a.l, b.l)
                if (overlap < Math.min(a.w, b.w) * 0.5f) continue
                // A label, chip or caption between two blocks is its own element: the 8 dp rule applies to directly stacked blocks.
                if (s.nodes.any { !isBlock(it) && it.text.isNotBlank() && it.t >= a.b - .5f && it.b <= b.t + .5f && it.w > 1f && Math.min(it.r, a.r) - Math.max(it.l, a.l) > 1f }) continue
                val gap = b.t - a.b
                if (Math.abs(gap - GAP) > TOL) out += Dev(s.name, "gap: 8 dp between stacked blocks (CR-62)", b.text.ifEmpty { b.id }.take(28), gap, GAP)
            }
        }
        return out
    }

    /** CR-55 (one accent-card per screen with a primary figure, containing it) and CR-56 (primary >= 1.5 x secondary and >= 2.3 x label, > page title). */
    /** Screens whose approved board draws an accent card with a primary figure (boards 1a Pilastri, 3a Spese + Rate, Futuro); elsewhere at most one per pane. */
    private val accentScreens = setOf("pilastri", "spese", "futuro")

    private fun accentAndFigures(s: Shot, by: (String) -> List<N>): List<Dev> {
        val out = ArrayList<Dev>()
        val pf = by("primary-figure"); val ac = by("accent-card")
        // Per PANE: the open Fold draws one accent card per pane (3a: Spese trio left, Rate right).
        val panes = if (s.layout == "fold" && (s.top || (by("page-content").any { it.cx < s.width / 2 } && by("page-content").any { it.cx >= s.width / 2 }))) listOf(0 to (s.width / 2), 1 to s.width) else listOf(0 to s.width)
        val base = s.name.substringAfter("top-", "")
        for ((i, edge) in panes) {
            val lo = if (i == 0) 0f else s.width / 2
            val inP = { n: N -> n.cx >= lo && n.cx < edge }
            val pP = pf.filter(inP); val aP = ac.filter(inP)
            val label = if (panes.size > 1) (if (i == 0) " (left pane)" else " (right pane)") else ""
            if (aP.size > 1 || pP.size > 1) out += Dev(s.name, "accent: at most one accent-card and primary-figure per pane (CR-55)$label", "accent-card", aP.size.toFloat(), 1f)
            else if (pP.size == 1 && aP.size == 0) out += Dev(s.name, "accent: a primary-figure sits inside an accent-card (CR-55)$label", pP[0].text.take(28), 0f, 1f)
            else if (aP.size == 1 && pP.isEmpty()) out += Dev(s.name, "accent: accent-card without a primary figure (CR-55)$label", "accent-card", 1f, 0f)
            else if (aP.size == 1 && !inside(pP[0], aP[0])) out += Dev(s.name, "accent: the primary-figure sits inside the accent-card (CR-55)$label", pP[0].text.take(28), pP[0].t, aP[0].t)
            // Required only where a board draws one: the screen as a whole, and on Spese both panes.
            val need = s.top && base in accentScreens && (panes.size == 1 || base == "spese" || i == 0)
            if (need && aP.isEmpty() && !(panes.size == 1 && ac.isNotEmpty())) out += Dev(s.name, "accent: exactly one accent-card on a screen the board draws one on (CR-55)$label", "accent-card", 0f, 1f)
        }
        val sec = by("secondary-figure"); val lab = by("stat-label"); val title = by("page-title")
        // JVM measures the font size (sp) from the text layout; a node without one falls back to the line box.
        fun size(n: N) = if (n.fs > 0f) n.fs else n.h
        if (pf.size >= 1) {
            fun primaryFor(n: N) = pf.firstOrNull { s.layout != "fold" || (it.cx < s.width / 2) == (n.cx < s.width / 2) } ?: pf[0]
            sec.forEach {  val p = size(primaryFor(it))
 if (p < 1.5f * size(it) - 0.05f) out += Dev(s.name, "figure: primary >= 1.5 x secondary-figure (CR-56)", it.text.take(28), r1(p / size(it) * 100) / 100, 1.5f) }
            lab.forEach { val p = size(primaryFor(it)); if (p < 2.3f * size(it) - 0.05f) out += Dev(s.name, "figure: primary >= 2.3 x stat-label (CR-56)", it.text.take(28), r1(p / size(it) * 100) / 100, 2.3f) }
            // wave 11a (user 2026-11-09 21:50): the page heading is the top text level (CR-83), no longer below the primary figure.
            if (false) title.forEach { val p = size(primaryFor(it)); if (p <= size(it)) out += Dev(s.name, "figure: primary larger than page-title (CR-56)", it.text.take(28), size(it), p) }
        }
        return out
    }

    /** CR-59 (structure part): an attention-row is a single target, 56 dp high, with no clickable inside it. Outline and chevron are CardKit source rules. */
    private fun attention(s: Shot, by: (String) -> List<N>): List<Dev> {
        val out = ArrayList<Dev>()
        for (r in by("attention-row")) {
            if (Math.abs(r.h - ATTENTION_H) > TOL) out += Dev(s.name, "attention: attention-row height 56 dp (CR-59)", r.text.take(28), r.h, ATTENTION_H)
            val inner = s.targets.filter { it.w > 1f && inside(it, r) && !(Math.abs(it.l - r.l) < 1f && Math.abs(it.r - r.r) < 1f) }
            if (inner.isNotEmpty()) out += Dev(s.name, "attention: no button inside the attention-row (CR-59)", inner[0].text.ifEmpty { inner[0].desc }.take(28), inner.size.toFloat(), 0f)
        }
        return out
    }

    /** CR-27 (cover dock centred, 5 items), CR-24 (island 12 dp from the right edge, centred), CR-07 (gear on the island axis). */
    private fun frame(s: Shot): List<Dev> {
        val out = ArrayList<Dev>()
        val dock = s.nodes.filter { it.id == "dock" }; val island = s.nodes.filter { it.id == "island" }; val gear = s.nodes.filter { it.id == "gear" }
        if (s.layout == "phone") {
            if (dock.size != 1) out += Dev(s.name, "frame: exactly one dock on the cover (CR-27)", "dock", dock.size.toFloat(), 1f)
            else {
                if (Math.abs(dock[0].cx - s.width / 2) > TOL) out += Dev(s.name, "frame: dock centre x equals screen centre (CR-27)", "dock", dock[0].cx, s.width / 2)
                val items = s.nodes.count { it.id == "dock-item" }
                if (items != 5) out += Dev(s.name, "frame: exactly 5 dock-item (CR-27)", "dock-item", items.toFloat(), 5f, if (items == 0) "request tag dock-item" else "")
            }
        } else {
            if (island.size != 1) out += Dev(s.name, "frame: exactly one island on the open Fold (CR-24)", "island", island.size.toFloat(), 1f)
            else {
                val i = island[0]
                if (Math.abs((s.width - i.r) - 12f) > TOL) out += Dev(s.name, "frame: island 12 dp from the right edge (CR-24)", "island", s.width - i.r, 12f)
                if (Math.abs(i.cy - s.height / 2) > 4f) out += Dev(s.name, "frame: island vertically centred +-4 dp (CR-24)", "island", i.cy, s.height / 2)
                gear.firstOrNull()?.let { if (Math.abs(it.cx - i.cx) > 2f) out += Dev(s.name, "frame: gear centre x equals island centre x (CR-07)", "gear", it.cx, i.cx) }
            }
        }
        return out
    }

    /** CR-41: panes split at W/2 (+-4) and both populated (proposed tags pane-left / pane-right). */
    private fun panes(s: Shot, by: (String) -> List<N>): List<Dev> {
        if (s.layout != "fold" || !s.top) return emptyList()
        val l = by("pane-left"); val r = by("pane-right")
        if (l.isEmpty() || r.isEmpty()) return listOf(Dev(s.name, "panes: tags pane-left and pane-right missing (CR-41)", "-", (l.size + r.size).toFloat(), 2f, "request tags pane-left, pane-right"))
        val out = ArrayList<Dev>()
        if (Math.abs(l[0].r - s.width / 2) > 4f) out += Dev(s.name, "panes: left pane ends at the split (CR-41)", "pane-left", l[0].r, s.width / 2)
        val content = s.nodes.filter { it.text.isNotBlank() && it.id != "island" && it.id != "gear" && it.id != "dock-item" }
        val left = content.count { it.cx < s.width / 2 }; val right = content.count { it.cx >= s.width / 2 }
        if (left < 3 || right < 3) out += Dev(s.name, "panes: both panes populated (>= 3 texted nodes each) (CR-41)", "left $left / right $right", Math.min(left, right).toFloat(), 3f)
        return out
    }
}

/** Walks a semantics tree; bounds in dp. [onlyClickable] keeps the clickable nodes only (use it on the MERGED tree for touch targets). */
fun collectNodes(root: androidx.compose.ui.semantics.SemanticsNode, density: androidx.compose.ui.unit.Density, onlyClickable: Boolean = false): List<N> {
    val out = ArrayList<N>()
    val p = androidx.compose.ui.semantics.SemanticsProperties
    fun texts(n: androidx.compose.ui.semantics.SemanticsNode): String =
        n.config.getOrNull(p.Text)?.joinToString(" ") { it.text }
            ?: n.children.asSequence().map { texts(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    fun fontSize(n: androidx.compose.ui.semantics.SemanticsNode): Float {
        val act = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action ?: return 0f
        val res = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        return if (act(res)) res.firstOrNull()?.layoutInput?.style?.fontSize?.value ?: 0f else 0f
    }
    /** Rule 2: the text layout's own verdict: (visual overflow, any line ellipsized). Only the node that owns the TextLayoutResult answers. */
    fun clip(n: androidx.compose.ui.semantics.SemanticsNode): Pair<Boolean, Boolean> {
        val act = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action ?: return false to false
        val res = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        if (!act(res)) return false to false
        val r = res.firstOrNull() ?: return false to false
        // CR-66: TextLayoutResult.hasVisualOverflow / didOverflowWidth / didOverflowHeight are true for every text under Robolectric (artefact: its 35 px line
        // height and font metrics do not match the box), so the verdict is measured: a line wider than the layout box by more than 1.5 px (Robolectric reports size = line width - 1 for every text; a centred line is offset, not wider),
        // lines cut by maxLines, or a word broken across lines. Ellipsis is read per line.
        val w = r.size.width.toFloat()
        val horizontal = (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > w + 1.5f }
        val txt = r.layoutInput.text.text
        // a single word broken across lines ("Salta" -> "Salt" / "a") is a clipped label: no break may fall inside a word
        val midWord = (0 until r.lineCount - 1).any { val e = r.getLineEnd(it); e in 1 until txt.length && !txt[e - 1].isWhitespace() && !txt[e].isWhitespace() && txt[e - 1] != '-' }
        val vertical = r.multiParagraph.didExceedMaxLines || midWord
        return (horizontal || vertical) to (0 until r.lineCount).any { r.isLineEllipsized(it) }
    }
    fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
        val tag = n.config.getOrNull(p.TestTag).orEmpty()
        val own = n.config.getOrNull(p.Text)?.joinToString(" ") { it.text }.orEmpty()
        val desc = n.config.getOrNull(p.ContentDescription)?.joinToString(" ").orEmpty()
        val click = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
        if (if (onlyClickable) click else (tag.isNotEmpty() || own.isNotEmpty() || click)) {
            val b = n.boundsInRoot
            val (ov, ell) = clip(n)
            // a zero-width text box that overflows is a label squeezed out of its cell: it must still be reported (CR-66)
            if ((b.width > 0 || ov) && b.height > 0) {
                val d = density.density
                out += N(tag, if (tag.isNotEmpty() || onlyClickable) texts(n) else own, b.left / d, b.top / d, b.right / d, b.bottom / d, click, desc, fontSize(n), ov, ell, echo = tag.isNotEmpty() && own.isEmpty() && !onlyClickable)
            }
        }
        n.children.forEach { walk(it) }
    }
    walk(root)
    return out
}

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): T? =
    if (contains(key)) get(key) else null
