package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.ai.AutoCategoryAttempts
import xyz.grtlabs.pilastri.ai.SharedPrefsStore
import xyz.grtlabs.pilastri.ai.movementRef
import androidx.compose.ui.text.style.TextOverflow
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.ui.*

/*
 * Spese (DESIGN "Screen specs" → Spese, boards 3a, 3b, cover 7c; Council 5 period). Left pane / cover root: the window header,
 * the trio, "Ritmo di spesa", "Dove limare" (open Fold, over state), "Categorie". Right root: "Da controllare", Rate, Piani.
 * The window ("Ultimo mese", earlier windows by swipe, peek or calendar, G-11) is named by the calendar month it ends in
 * ([App.spendMonth]), so the category page and a fold/unfold keep it. No Flusso here: it is Mese's (3a-10).
 */

private const val WindowDefinition = "Ultimo mese: da un mese fa fino all'ultimo giorno coperto dai conti, 28–31 giorni. " +
    "Non riparte il primo del mese né allo stipendio. Scorri il grafico per i periodi precedenti: ognuno inizia dove finisce " +
    "l'altro, così affitto e rate compaiono una volta sola. I giorni non coperti restano vuoti, mai zero.\n\n" +
    "Spesa: uscite dei conti di Liquidità, esclusi i trasferimenti tra i tuoi conti, i versamenti nei fondi e gli acquisti di " +
    "titoli; le rate contano il giorno in cui le paghi. Entrate e Risparmiato restano \"—\" finché ogni entrata attesa non è " +
    "arrivata una volta sola.\n\nBudget: come lo scrivi, per periodo. Senza budget, la stima è la mediana degli ultimi 3 periodi " +
    "completi. \"Oltre il ritmo\" compare da 14 giorni coperti, con la spesa almeno il 10% sopra il budget dei giorni passati."

/** The windows Spese can show and the one chosen ([k]); index i in a strip is window k = count − 1 − i (oldest first). */
internal class SpendSpan(val ledger: SpendLedger, val t: LocalDate?, val count: Int, val k: Int) {
    val index: Int get() = count - 1 - k
    fun kOf(i: Int): Int = count - 1 - i
    /** Total: a window index outside 0 until [count] (a strip caught between two counts, a stale caller) has no window, month, label or data, and never throws. */
    fun has(k: Int): Boolean = t != null && k in 0 until count
    fun window(k: Int): SpendWindow? = if (has(k)) SpendWindows.window(t!!, k, ledger.cover.from) else null
    fun month(k: Int): YearMonth? = if (has(k)) SpendWindows.monthOf(t!!, k) else null
    fun hasData(k: Int): Boolean = (window(k)?.covered ?: 0) > 0
    fun label(k: Int): String = if (k == 0 && has(0)) "Ultimo mese" else window(k)?.let { "${Fmt.day(it.shownFrom)} – ${Fmt.day(it.to)}" } ?: "—"
    val timeline: List<YearMonth> get() = (count - 1 downTo 0).mapNotNull { month(it) }
}

@Composable
internal fun rememberSpendSpan(app: App, a: Archive): SpendSpan {
    val today = app.today
    val ledger = app.home(a).ledger
    val t = SpendWindows.anchor(today, ledger.cover)
    val count = if (app.captureSingleWindow) 1 else remember(ledger, today) { ledger.windowCount(today) }
    val k = t?.let { SpendWindows.indexFor(it, app.spendMonth).coerceAtMost(count - 1) } ?: 0
    return SpendSpan(ledger, t, count, k)
}

/** Window [k]'s read model; the live window records the signal's hysteresis in the UI cache. */
@Composable
internal fun rememberWindowView(app: App, a: Archive, span: SpendSpan, k: Int): SpendWindowView? {
    val today = app.today
    val v = remember(a, span.ledger, k, today, span.count) { if (k in 0 until span.count) a.spendWindowView(today, k, span.ledger, SpendSignalCache::wasOn) else null }
    SideEffect { v?.let(SpendSignalCache::record) }
    return v
}

private fun App.pickWindow(span: SpendSpan, k: Int) { span.month(k)?.let { spendMonth = it } }

/** Spese's header shows ONE date range per period (the title, clamped to the covered days); the only subtitle is the sample note. */
internal fun windowNote(a: Archive): String? = sampleSuffix(a).removePrefix(" · ").ifBlank { null }

/** Undo for a decision taken in one gesture (lazy-UX #2, #9): the toast's "Annulla" reverses it. */
internal fun App.commitWithUndo(done: String, undo: (Archive) -> Archive, transform: (Archive) -> Archive) =
    commit(null, then = { say(done, "Annulla") { commit("Annullato.") { undo(it) } } }, transform)

/* ---------- Roots ---------- */

/** The cover's Spese root (board 7c). */
@Composable
fun SpendingScreen(app: App) = SpeseRoot(app, wide = false)

/** The open Fold's left pane (boards 3a/3b left). */
@Composable
fun SpendingLeft(app: App) = SpeseRoot(app, wide = true)

@Composable
private fun SpeseRoot(app: App, wide: Boolean) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val span = rememberSpendSpan(app, a)
    val v = rememberWindowView(app, a, span, span.k)
    val strip = rememberPeriodState(span.index, span.count)
    var calendar by rememberSaveable { mutableStateOf(false) }
    val gutter = PageSpec.gutter(wide)
    Page(Modifier.testTag(UiTag.PAGE_CONTENT), horizontal = gutter, top = PageSpec.Top) {
        val t = span.t
        // G-11: the window is one period control in the header's trailing slot (boards 3a/7c: the "ott 2026" chip): its real range,
        // a tap opens the calendar, TalkBack gets previous/next; the neighbours' names sit under the header with the swipe.
        // H6/K8: the page title "Spese", then the period ONCE, as the big shared PeriodTitle (same component and size as Mese's
        // month); it is also the period control (swipe, tap opens the calendar). No coverage line: the title is the covered range.
        val note = windowNote(a)
        RootHeader("Spese", null, strip = null) { HelpButton("Ultimo mese", WindowDefinition) }
        if (t != null) Column {
            val pick: (Int) -> Unit = { i -> app.hx?.tick(); app.pickWindow(span, span.kOf(i)) }
            val range = span.window(span.k)?.let { "${Fmt.day(it.shownFrom)} – ${Fmt.day(it.to)}" } ?: "n/d"
            // The 48 dp touch target is the outer box; the tagged PeriodTitle inside is only the text line (the same height as Mese's).
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                // The control is an overlay: its press clip must not cut the title while it travels with its page (same as PeriodPageHeader).
                Box(Modifier.matchParentSize().periodControl(strip, { span.label(span.kOf(it)) }, pick, { calendar = true }, pal.pressTint))
                PeriodTitle(range, caret = true, strip = strip, titleOf = { i -> span.window(span.kOf(i))?.let { "${Fmt.day(it.shownFrom)} – ${Fmt.day(it.to)}" } ?: "n/d" })
            }
            // One range per period: no coverage line (the title already shows the covered days); only the sample note.
            note?.let { n -> PeriodCoverage(strip) { n } }
            if (calendar) MonthCalendar(span.timeline, { m -> span.hasData(SpendWindows.indexFor(t, m)) }, span.month(span.k)!!,
                { m -> app.hx?.tick(); app.spendMonth = m }) { calendar = false }
        }
        if (t == null) {
            EmptyNote("Nessun conto collegato", action = "Collega un conto", onAction = { app.go(Route.Connections) })
            return@Page
        }
        // 7c-01: the cover's callout opens the same card in place; on the open Fold the card tops the right root (G-08).
        if (!app.twoPane) CheckCallout(app, a, Modifier)
        // Neighbours only (the title names the current period): with a single period the strip is an empty 48 dp band, so it is not drawn (device: 64 dp gap under "Da controllare").
        if (strip.last > 0) PeriodLabels(strip, { span.label(span.kOf(it)) }, { i -> app.hx?.tick(); app.pickWindow(span, span.kOf(i)) }, null,
            Type.smallStrong, pal.ink2, showCurrent = false, hasData = { span.hasData(span.kOf(it)) }, gutter = gutter, inset = Motion.periodInset(wide), wide = wide)
        // G-11: one strip per card block on the shared state (the first emits ticks and the settle), so a neighbour's sliver follows each card's geometry.
        val settle: (Int) -> Unit = { i -> app.pickWindow(span, span.kOf(i)) }
        val has: (Int) -> Boolean = { span.hasData(span.kOf(it)) }
        PeriodSwipe(strip, settle, hasData = has, gutter = gutter, inset = Motion.periodInset(wide), wide = wide) { i -> WindowTop(app, a, span, span.kOf(i)) }
        PeriodSwipe(strip, settle, hasData = has, gutter = gutter, inset = Motion.periodInset(wide), wide = wide, primary = false) { i -> WindowRhythm(app, a, span, span.kOf(i), wide) }
        PeriodSwipe(strip, settle, hasData = has, gutter = gutter, inset = Motion.periodInset(wide), wide = wide, primary = false) { i -> WindowCategories(app, a, span, span.kOf(i), wide) }
        if (!app.twoPane) RateEntry(app, a, Modifier)
    }
}

/** One window's first block (a page of the first strip): the empty note, or the 3a-02 trio. */
@Composable
private fun WindowTop(app: App, a: Archive, span: SpendSpan, k: Int) {
    val v = rememberWindowView(app, a, span, k) ?: return
    if (v.window.covered == 0) {
        val near = (k downTo 0).firstOrNull { span.hasData(it) }
        EmptyNote("Nessun dato in questo periodo", action = near?.let { "Vai a ${span.label(it)}" }, onAction = near?.let { { app.hx?.tick(); app.pickWindow(span, it) } })
    } else Trio(v)
}

/** 3a-03/3b-01 pace and 3b-02 "Dove limare" (open Fold): the second strip. */
@Composable
private fun WindowRhythm(app: App, a: Archive, span: SpendSpan, k: Int, wide: Boolean) {
    val v = rememberWindowView(app, a, span, k) ?: return
    if (v.window.covered == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RhythmCard(v)
        if (wide && v.trim.isNotEmpty()) TrimCard(v)
    }
}

/** 3a-04..06 categories: the third strip. */
@Composable
private fun WindowCategories(app: App, a: Archive, span: SpendSpan, k: Int, wide: Boolean) {
    val v = rememberWindowView(app, a, span, k) ?: return
    if (v.window.covered == 0) return
    CategoriesCard(app, span, v, wide)
}

/* ---------- 3a-02 trio ---------- */

private fun incomeCaption(i: WindowIncome): String? = when {
    i.cents != null && i.matched.isNotEmpty() -> i.matched.joinToString(" · ") { (r, d) -> "${r.title.lowercase()} ${Fmt.day(d)}" }
    i.cents != null -> if (i.credits == 1) "1 accredito" else "${i.credits} accrediti"
    i.causes.isNotEmpty() -> i.causes.joinToString(" · ") { c ->
        when (c) {
            is IncomeCause.Expected -> "${c.rule.title} atteso " + (if (YearMonth.from(c.from) == YearMonth.from(c.to)) "${c.from.dayOfMonth}–${Fmt.day(c.to)}" else "${Fmt.day(c.from)}–${Fmt.day(c.to)}")
            is IncomeCause.Credits -> (if (i.causes.size > 1) "${c.rule.title}: " else "") + "${c.count} accrediti"
        }
    }
    else -> null
}

private fun spendCaption(v: SpendWindowView): String? {
    if (v.spentCents == null) return null
    val b = v.budget ?: return "budget n/d"
    return (v.budgetFraction?.let { Fmt.pct(it) + " di " } ?: "") + Fmt.eur(b.cents) + if (b.estimate) " · stima" else ""
}

@Composable
private fun Trio(v: SpendWindowView) {
    // D-01 (boards 3a/7c): the accent card, Spesa the primary figure; the captions sit under it on the same columns (AccentStat has no caption slot).
    val weights = floatArrayOf(1f, 1.3f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AccentCard {
            AccentStat("Entrate", v.income.cents?.let(Fmt::eur) ?: "—", weights[0])
            AccentStat("Spesa", v.spentCents?.let(Fmt::eur) ?: "—", weights[1], primary = true)
            AccentStat("Risparmiato", v.savedCents?.let(Fmt::eur) ?: "—", weights[2])
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrioCaption(incomeCaption(v.income), false, Modifier.weight(weights[0]))
            TrioCaption(spendCaption(v), v.over, Modifier.weight(weights[1]))
            TrioCaption(v.savedFraction?.let { Fmt.pct(it) + " delle entrate" }, v.over && v.savedCents != null, Modifier.weight(weights[2]))
        }
    }
}

@Composable
private fun TrioCaption(caption: String?, red: Boolean, modifier: Modifier) {
    val pal = LocalPal.current
    Box(modifier) {
        if (caption != null) Text(caption, style = Type.tiny, color = if (red) pal.overInk else pal.ink2, maxLines = 2, modifier = if (red) Modifier.testTag(UiTag.OVER) else Modifier)
    }
}

/* ---------- 3a-03 / 3b-01 pace ---------- */

/** "Oltre il ritmo +N%": icon and text, never colour alone (3b-01, 3a-06). */
@Composable
private fun OverChip(percent: Int?, compact: Boolean = false) {
    val pal = LocalPal.current
    Row(Modifier.testTag(UiTag.OVER).clip(RoundedCornerShape(12.dp)).background(pal.over.copy(alpha = 0.14f)).padding(horizontal = if (compact) 6.dp else 9.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.warning, null, Modifier.size(if (compact) 11.dp else 13.dp), tint = pal.overInk)
        Spacer(Modifier.width(4.dp))
        Text("Oltre il ritmo" + (percent?.let { " +$it%" } ?: ""), style = if (compact) Type.tinyStrong else Type.smallStrong, color = pal.overInk, maxLines = 1)
    }
}

@Composable
private fun RhythmCard(v: SpendWindowView) {
    val pal = LocalPal.current
    val total = v.spentCents?.let(Fmt::eur) ?: "n/d"
    val state = when {
        v.budget == null -> "budget n/d"
        v.over -> "oltre il ritmo del ${v.overPercent}%"
        (v.ratio ?: 0.0) < SpendSignal.ON -> "ritmo in linea"
        else -> ""
    }
    val description = "Ritmo di spesa dal ${Fmt.day(v.window.shownFrom)} al ${Fmt.day(v.window.to)}: $total" +
        (v.budget?.let { " su un budget di ${Fmt.eur(it.cents)}" + if (it.estimate) " stimato" else "" } ?: "") + (if (state.isNotEmpty()) ", $state" else "")
    TitledCard("Ritmo di spesa", trailing = {
            when {
                v.budget == null -> Text("budget n/d", style = Type.small, color = pal.ink2)
                v.over -> OverChip(v.overPercent)
                (v.ratio ?: 0.0) < SpendSignal.ON -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.check, null, Modifier.size(13.dp), tint = pal.ink2); Spacer(Modifier.width(4.dp))
                    Text("ritmo in linea", style = Type.smallStrong, color = pal.ink2)
                }
            }
    }) {
        PaceChart(v, description)
    }
}

/* ---------- 3b-02 Dove limare ---------- */

@Composable
private fun TrimCard(v: SpendWindowView) {
    val pal = LocalPal.current
    TitledCard("Dove limare", verticalArrangement = Arrangement.spacedBy(8.dp), trailing = {
        v.abovePaceCents?.let { Text("${Fmt.eur(it)} sopra il ritmo", style = Type.small, color = pal.overInk, maxLines = 1) }
    }) {
        TrimBars(v.trim)
    }
}

/* ---------- 3a-04..06 categories ---------- */

@Composable
private fun CategoriesCard(app: App, span: SpendSpan, v: SpendWindowView, wide: Boolean) {
    val pal = LocalPal.current
    val grow = xyz.grtlabs.pilastri.ui.charts.rememberBarGrow(v.categories.size)
    val selected = if (wide) (app.nav.depth.firstOrNull() as? Route.Category)?.let { it.id ?: "none" } else null
    TitledCard("Categorie", rows = true, trailing = {
        Box(Modifier.size(2.dp, 11.dp).background(pal.ink))
        Spacer(Modifier.width(4.dp)); Text("ritmo", style = Type.tiny, color = pal.ink2); Spacer(Modifier.width(10.dp))
        Sparkline(listOf(2L, 4L, 3L, 5L), false, Modifier.size(18.dp, 10.dp))
        Spacer(Modifier.width(4.dp)); Text("6 mesi", style = Type.tiny, color = pal.ink2)
    }) {
        if (v.categories.isEmpty()) Text("Nessuna spesa in questo periodo.", style = Type.body, color = pal.ink2, modifier = Modifier.padding(horizontal = RowInset, vertical = RowVerticalInset))
        HaloBox(selected, pal.selected, pal.selectedLine, Modifier.fillMaxWidth(), radius = 20.dp) { halo ->
            Column {
                v.categories.forEachIndexed { i, c ->
                    CategoryRow(c, v.window, grow.getOrNull(i)?.value ?: 1f, Modifier.haloTarget(halo, c.id ?: "none")) {
                        app.pickWindow(span, v.window.k)
                        if (wide) app.select(Route.Category(c.id)) else app.go(Route.Category(c.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: SpendCategoryLine, w: SpendWindow, grow: Float, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    val ink = if (c.over) pal.overInk else pal.ink
    val amount = c.budget?.let { b -> Fmt.eur(c.spentCents).removeSuffix(Fmt.EUR_SUFFIX) + " / " + Fmt.eur(b.cents) + if (b.estimate) " · stima" else "" }
        ?: (Fmt.eur(c.spentCents) + " · budget n/d")
    val spoken = "${c.name}, $amount" + (if (c.over) ", oltre il ritmo del ${c.ratio?.let { kotlin.math.round((it - 1) * 100).toInt() }}%" else "") +
        (c.sparkMax?.let { ", massimo degli ultimi 6 mesi ${Fmt.eur(it)}" } ?: "")
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(RoundedCornerShape(20.dp), pal.pressTint, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = spoken }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = Type.rowTitle, color = ink, modifier = Modifier.weight(1f, fill = false))
                if (c.over) { Spacer(Modifier.width(6.dp)); OverChip(c.ratio?.let { kotlin.math.round((it - 1) * 100).toInt() }, compact = true) }
            }
            PaceBar(c.fraction, c.budget?.let { w.covered.toFloat() / w.days }, c.over, grow)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(amount, style = Type.amountSmall, color = ink, modifier = (if (c.over) Modifier.testTag(UiTag.OVER) else Modifier).weight(1f, fill = false))
                // U-12 / 3b: over its budget, the overspend is named ("138 / 100 €" -> "+38 €").
                val b = c.budget?.cents
                if (b != null && c.spentCents > b) { Spacer(Modifier.width(6.dp)); Text("+" + Fmt.eur(c.spentCents - b), style = Type.amountSmall, color = pal.overInk, maxLines = 1, modifier = Modifier.testTag(UiTag.OVER)) }
            }
        }
        if (c.sparkShown) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Sparkline(c.spark, c.over)
                Text("max ${Fmt.eur(c.sparkMax!!)}", style = Type.tiny, color = pal.ink2, maxLines = 1)
            }
        }
    }
}

/* ---------- G-08 Rate entry (cover only) ---------- */

private fun rateSummary(r: RateOutlook): String =
    if (r.lastKnown < 0) "nessuna rata nei prossimi 12 mesi"
    else "prossimi 6: ${Fmt.eur(r.next6)}" + if (r.next6Pending > 0) " +${Fmt.eur(r.next6Pending)} da confermare" else ""

@Composable
private fun RateEntry(app: App, a: Archive, modifier: Modifier) {
    val pal = LocalPal.current
    val r = remember(a, app.today) { a.rateOutlook(app.today) }
    Column(modifier.fillMaxWidth().contentRow(pal).padding(4.dp)) {
        ListRow("Rate", rateSummary(r), { app.go(Route.PaneRoot(Tab.SPESE)) }, leading = { IconDisc(Ic.calendar, pal.lilacBg, pal.lilacInk, 30.dp) })
    }
}

/* ---------- 3b-04 / 7c-01 Da controllare ---------- */

@Composable
internal fun rememberCheckItems(a: Archive): List<CheckItem> {
    val ctx = LocalContext.current
    return remember(a) {
        // Same preferences file as AutoCategorizeHost (ai/AutoCategorize.kt, PREFS): what the assistant held back as ambiguous.
        val refs = runCatching { AutoCategoryAttempts(SharedPrefsStore(ctx.getSharedPreferences("pilastri_autocat", android.content.Context.MODE_PRIVATE))).attempted() }.getOrDefault(emptySet())
        a.toCheckItems { id -> movementRef(id) in refs }
    }
}

private fun kindLabel(k: CheckKind) = when (k) { CheckKind.TRANSFER -> "Possibile trasferimento"; CheckKind.DUPLICATE -> "Possibile duplicato"; CheckKind.AMBIGUOUS -> "Da classificare" }

@Composable
private fun CheckCallout(app: App, a: Archive, modifier: Modifier) {
    val items = rememberCheckItems(a)
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Reveal(items.isNotEmpty()) {
            val first = items.firstOrNull()
            AttentionRow(Ic.search, "Da controllare · ${items.size}", first?.let { "${kindLabel(it.kind).lowercase()} · ${Fmt.eur(it.cents)}" } ?: "", { app.hx?.tick(); open = !open })
        }
        Reveal(open && items.isNotEmpty()) { CheckCard(app, a, items, showTitle = false) }
    }
}

/** One item at a time, a pager "i di N"; Salta moves on for this session; every decision has an Undo. */
@Composable
internal fun CheckCard(app: App, a: Archive, all: List<CheckItem>, showTitle: Boolean) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    val items = all.filter { it.key !in skipped }.ifEmpty { all }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val i = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val item = items.getOrNull(i) ?: return
    val n = items.size
    fun skip(it: CheckItem) { skipped = skipped + it.key }
    TitledCard(if (showTitle) "Da controllare · ${all.size}" else "", verticalArrangement = Arrangement.spacedBy(6.dp), trailing = {
        if (n > 1) {
            RoundIcon(Ic.back, "Precedente", { app.hx?.tick(); index = (i - 1 + n) % n }, size = 40.dp, iconSize = 16.dp)
            Text("${i + 1} di $n", style = Type.small, color = pal.ink2)
            RoundIcon(Ic.chevronRight, "Successivo", { app.hx?.tick(); index = (i + 1) % n }, size = 40.dp, iconSize = 16.dp)
        }
    }) {
        AnimatedContent(item, transitionSpec = {
            if (reduce) reducedSwap()
            else (slideInHorizontally(Motion.spatialOffset()) { it / 3 } + fadeIn(Motion.reducedFade())) togetherWith (slideOutHorizontally(Motion.spatialOffset()) { -it / 3 } + fadeOut(Motion.reducedFade()))
        }, label = "toCheck") { it -> CheckBody(app, a, it) { skip(it) } }
    }
}

@Composable
private fun CheckBody(app: App, a: Archive, item: CheckItem, onSkip: () -> Unit) {
    val pal = LocalPal.current
    val o = item.outflow ?: return
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${kindLabel(item.kind)} · ${Fmt.day(item.date)}", style = Type.rowTitle, color = pal.ink)
        item.movements.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.signedCents(m.signedCents), style = Type.amount, color = if (m.signedCents > 0) pal.goodInk else pal.ink, modifier = Modifier.width(120.dp))
                Text(m.provider.label, style = Type.small, color = pal.ink2, maxLines = 1)
            }
        }
        ActionRow {
            when (item.kind) {
                CheckKind.TRANSFER -> {
                    val other = item.movements.firstOrNull { it.id != o.id }
                    if (other != null) PillButton("È un trasferimento", { app.commitWithUndo("Trasferimento registrato.", { it.withExternalReopened(o.id) }) { it.withExternalDecision(o.id, ExternalDecision.AsTransfer(other.id)) } }, height = 48.dp, kind = Btn.PRIMARY)
                    PillButton("È una spesa", { picking = true }, height = 48.dp, kind = Btn.TONAL)
                }
                CheckKind.DUPLICATE -> PillButton("Duplicato", { app.commitWithUndo("Ignorato come duplicato.", { it.withExternalReopened(o.id) }) { it.withExternalDecision(o.id, ExternalDecision.Ignore) } }, height = 48.dp, kind = Btn.PRIMARY)
                CheckKind.AMBIGUOUS -> CategoryChips(app, a, o, o.categoryId) { id -> app.commitWithUndo("Categoria scelta.", { it.withMovementCategory(o.id, null) }) { it.withMovementCategory(o.id, id) } }
            }
            PillButton("Salta", { app.hx?.tick(); onSkip() }, height = 48.dp, kind = Btn.GHOST)
        }
    }
    if (picking) CategorySheet(app, "Spesa · categoria", o.categoryId, { id ->
        picking = false
        onSkip()
        app.commitWithUndo("Registrata come spesa.", { it.withExternalReopened(o.id).withMovementCategory(o.id, null) }) { it.withMovementCategory(o.id, id).withExternalDecision(o.id, ExternalDecision.AsEvent(EventType.WITHDRAWAL)) }
    }, { picking = false })
}

/* ---------- Lazy-UX #4: the likely categories inline ---------- */

/** The three most likely categories as chips; "altre" opens the full sheet. */
@Composable
internal fun CategoryChips(app: App, a: Archive, m: ExternalMovement, current: String?, onPick: (String?) -> Unit) {
    val pal = LocalPal.current
    val likely = remember(a, m.id) { a.likelyCategories(m) }
    var sheet by remember { mutableStateOf(false) }
    ActionRow {
        likely.forEach { id ->
            val on = id == current
            val fill by animateFill(pal.selected, on, "chip")
            Box(Modifier.heightIn(min = 48.dp).semantics { contentDescription = a.categoryName(id) + if (on) ", scelta" else "" }, contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(pal.card).background(fill)
                    .border(if (on) 2.dp else 1.dp, if (on) pal.selectedLine else pal.btnLine, RoundedCornerShape(16.dp))
                    .pressable(RoundedCornerShape(16.dp), pal.pressTint, onClick = { app.hx?.tick(); onPick(id) }).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(a.categoryName(id), style = if (on) Type.smallStrong else Type.small, color = pal.ink)
                }
            }
        }
        PillButton("altre", { sheet = true }, kind = Btn.GHOST, trailingIcon = Ic.down, description = "Altre categorie")
    }
    if (sheet) CategorySheet(app, "Categoria di \"${m.description.take(40)}\"", current, { onPick(it); sheet = false }, { sheet = false })
}

/* ---------- Right root: 3b-04, 3a-07, 3a-08 (Rate page on the cover) ---------- */

private fun rangeText(r: RateOutlook): String {
    val f = r.months.firstOrNull()?.month ?: return ""
    val l = r.months.last().month
    return "${Fmt.monthShort(f)} ${f.year} – ${Fmt.monthShort(l)} ${l.year}"
}

/** The open Fold's right-pane root, or the cover's Rate page (pushed, with its back header; G-08). */
@Composable
fun SpendingPane(app: App) {
    val a = app.archive ?: return
    val inPane = LocalInPane.current
    val r = remember(a, app.today) { a.rateOutlook(app.today) }
    Page(Modifier.testTag(UiTag.PAGE_CONTENT), top = PageSpec.Top, horizontal = PageSpec.gutter(app.twoPane), spacing = 8.dp) {
        // D-09 (board 3a): the pane's own header, "Rate" + range + "?", through the shared header; the cover has the back header.
        if (!inPane) BackHeader("Rate", rangeText(r) + sampleSuffix(a), { app.back() }, backLabel = "Indietro a Spese") { HelpButton("Rate", RateHelp) }
        else RootHeader("Rate", rangeText(r) + sampleSuffix(a)) { HelpButton("Rate", RateHelp) }
        if (inPane) {
            val items = rememberCheckItems(a)
            Reveal(items.isNotEmpty()) { CheckCard(app, a, items, showTitle = true) }
        }
        RateTotals(r)
        RateCard(r)
        PlansCard(app, r)
    }
}

private const val RateHelp = "Le rate dei piani noti, per mese, da Gmail, PayPal o dai tuoi piani. Tratteggiate: da confermare. Dopo l'ultima rata nota non c'è nulla: non è zero, non è ancora noto."

/** 3a-07: "prossimi 6" is the right pane's primary figure on the accent card, "12 mesi" secondary; pending sums are said under it. */
@Composable
private fun RateTotals(r: RateOutlook) {
    val pal = LocalPal.current
    if (r.lastKnown < 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AccentCard {
            AccentStat("prossimi 6", Fmt.eur(r.next6), 1.3f, primary = true)
            AccentStat("12 mesi", Fmt.eur(r.next12), 1f)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1.3f)) { if (r.next6Pending > 0) Text("+${Fmt.eur(r.next6Pending)} da confermare", style = Type.tiny, color = pal.ink2, maxLines = 1) }
            Box(Modifier.weight(1f)) { if (r.next12Pending > 0) Text("+${Fmt.eur(r.next12Pending)} da confermare", style = Type.tiny, color = pal.ink2, maxLines = 1) }
        }
    }
}

@Composable
private fun RateCard(r: RateOutlook) {
    val pal = LocalPal.current
    CardColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.lastKnown < 0) { Text("Nessuna rata nei prossimi 12 mesi.", style = Type.body, color = pal.ink2); return@CardColumn }
        val description = "Rate per mese da ${rangeText(r)}: prossimi 6 mesi ${Fmt.eur(r.next6)}" + (if (r.next6Pending > 0) " più ${Fmt.eur(r.next6Pending)} da confermare" else "") +
            ", 12 mesi ${Fmt.eur(r.next12)}" + if (r.next12Pending > 0) " più ${Fmt.eur(r.next12Pending)} da confermare" else ""
        RateBars(r, description)
        if (r.unknown12 > 0) Text("${r.unknown12} ${if (r.unknown12 == 1) "rata" else "rate"} senza importo", style = Type.tiny, color = pal.ink2)
    }
}

@Composable
private fun PlansCard(app: App, r: RateOutlook) {
    val pal = LocalPal.current
    val hues = rateHues()
    if (r.plans.isEmpty()) return
    val pending = r.plans.filter { it.pending }.map { it.plan.id }
    TitledCard("Piani", rows = true, trailing = { CardTrailingText("importo al mese") }) {
        // Lazy-UX #9: all plans still to confirm in one step, with Undo.
        if (pending.size > 1) Row(Modifier.padding(horizontal = 8.dp)) {
            PillButton("Conferma tutte · ${pending.size}", {
                app.commitWithUndo("${pending.size} piani confermati.", { cur -> pending.fold(cur) { acc, id -> acc.withPlanReview(id, PlanReview.PENDING) } }) { cur ->
                    pending.fold(cur) { acc, id -> acc.withPlanReview(id, PlanReview.CONFIRMED) }
                }
            }, kind = Btn.TONAL)
        }
        r.plans.forEach { line ->
            val p = line.plan
            val color = hues[line.hue.coerceIn(0, hues.lastIndex)]
            val row: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(20.dp))
                    .pressable(RoundedCornerShape(20.dp), pal.pressTint, onClick = { app.go(Route.Plan(p.id)) }).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).then(if (line.pending) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title(), style = Type.rowTitle, color = pal.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (line.pending) { Spacer(Modifier.width(6.dp)); Tag("da confermare") }
                        }
                        Text("fino a " + (line.until?.let { "${Fmt.monthShort(it)} ${it.year}" } ?: "n/d") + if (p.count == null) " · " + p.progressText() else "",
                            style = Type.small, color = pal.ink2, maxLines = 1)
                    }
                    Text(line.monthlyCents?.let(Fmt::eurCents) ?: "n/d", style = Type.amount, color = pal.ink)
                }
            }
            if (line.pending) SwipeDecide("Conferma", {
                app.commitWithUndo("Piano confermato.", { it.withPlanReview(p.id, PlanReview.PENDING) }) { it.withPlanReview(p.id, PlanReview.CONFIRMED) }
            }, "Non è mio", {
                app.commitWithUndo("Piano ignorato.", { it.withPlanReview(p.id, PlanReview.PENDING) }) { it.withPlanReview(p.id, PlanReview.IGNORED) }
            }) { row() } else row()
        }
    }
}

/* ---------- Category detail (one window) ---------- */

@Composable
fun CategoryScreen(app: App, categoryId: String?) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val span = rememberSpendSpan(app, a)
    val v = rememberWindowView(app, a, span, span.k)
    val line = v?.categories?.firstOrNull { it.id == categoryId }
    val rows = remember(span.ledger, v?.window, categoryId) { v?.window?.let { span.ledger.movements(it, categoryId) } ?: emptyList() }
    val spent = line?.spentCents ?: rows.sumOf { it.cents }.coerceAtLeast(0L)
    val budget = line?.budget
    var open by remember { mutableStateOf<String?>(null) }
    Page {
        BackHeader(a.categoryName(categoryId), "${span.label(span.k)} · ${rows.size} movimenti${sampleSuffix(a)}", { app.back() }) {
            HelpButton("Categorie", "Tocca un movimento per cambiarne la categoria: la tua scelta vale più delle regole e della categoria del fornitore. Una rata prende la categoria dell'acquisto.")
        }
        CardColumn {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.eurCents(spent), style = Type.figure, color = if (line?.over == true) pal.overInk else pal.ink, modifier = Modifier.weight(1f))
                Text(budget?.let { "di ${Fmt.eur(it.cents)}" + (if (it.estimate) " · stima" else "") } ?: "budget n/d", style = Type.small, color = pal.ink3)
            }
            VSpace(8.dp)
            PaceBar(line?.fraction, budget?.let { v?.window?.let { w -> w.covered.toFloat() / w.days } }, line?.over == true, 1f)
            if (line?.over == true) { VSpace(8.dp); OverChip(line.ratio?.let { kotlin.math.round((it - 1) * 100).toInt() }) }
        }
        if (rows.isEmpty()) {
            // Lazy-UX #14: a way out of an empty period.
            val near = (0 until span.count).firstOrNull { k -> k != span.k && span.window(k)?.let { span.ledger.movements(it, categoryId).isNotEmpty() } == true }
            EmptyNote("Nessuna spesa in questo periodo", action = near?.let { "Vai a ${span.label(it)}" }, onAction = near?.let { { app.hx?.tick(); app.pickWindow(span, it) } })
        } else CardColumn(rows = true) {
            rows.forEach { e ->
                val m = e.movement
                val part = span.ledger.states[m.id]
                Column {
                    ListRow(m.description.ifBlank { "Movimento" },
                        "${Fmt.day(m.date)} · ${m.provider.label}" + (if (part is InstallmentPaymentState.ToClassify) " · " + InstallmentPaymentState.LABEL else "") +
                            (if (m.status == ExternalStatus.PENDING) " · da rivedere" else "") + (m.categorySource?.takeIf { part == null }?.let { " · " + sourceLabel(it) } ?: ""),
                        { app.hx?.tick(); open = if (open == m.id) null else m.id }, value = Fmt.eurCents(e.cents), chevron = false, selected = open == m.id)
                    Reveal(open == m.id) {
                        Box(Modifier.padding(start = RowInset)) {
                            CategoryChips(app, a, m, e.categoryId) { id ->
                                open = null
                                // A part takes its purchase's category: choosing one classifies the plan (InstallmentCategories.kt).
                                val planId = part?.planId
                                if (planId != null) app.commit("Categoria dell'acquisto aggiornata.") { it.withPlanCategory(planId, id) }
                                else app.commit("Categoria aggiornata.") { it.withMovementCategory(m.id, id) }
                            }
                        }
                    }
                }
            }
        }
    }
}
