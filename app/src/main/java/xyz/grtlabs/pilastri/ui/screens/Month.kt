package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.ui.*

/** Open Fold with Mese in the right pane: its subtitle owns the month's coverage, so the left hero month label drops it (CR-70/K8, user 2026-11-09 ~09:15). */
internal val App.meseBeside: Boolean get() = twoPane && nav.tab == Tab.PILASTRI && nav.depth.lastOrNull().let { it == null || it is Route.Month }

/** "1–27 ott": the days of [month] shown so far (the whole month once it is over). */
internal fun monthRangeText(month: YearMonth, today: LocalDate): String {
    val last = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
    return "1–$last ${Fmt.monthShort(month)}"
}

/* ---------- The Pilastri month (1a-20, G-11), shared by the month row (Home.kt) and Mese ---------- */

/**
 * The months the month row and Mese swipe through, oldest first (from the first month with data to the latest; the current
 * month only once it has data), the months that hold data, the liquidity coverage and the month shown ([NavState.month]
 * when chosen, else the latest with data). Nothing at all → today's month alone, shown as "—".
 */
internal class MonthSpan(val timeline: List<YearMonth>, private val data: Set<YearMonth>, val cover: Coverage, val shown: YearMonth) {
    val index: Int get() = timeline.indexOf(shown).coerceAtLeast(0)
    fun has(m: YearMonth) = m in data
    /** "dati al 6 nov" for a partly covered month, "nessun dato" outside the coverage, null when fully covered. */
    fun coverage(m: YearMonth, today: LocalDate): String? = coverageLabel(m, cover, today, Fmt.monthShort(m))
    /** The month's state line: "nessun dato" without data, else its partial coverage ("dati al 6 nov") or null. */
    fun note(m: YearMonth, today: LocalDate): String? = if (!has(m)) "nessun dato" else coverage(m, today)?.takeIf { it != "nessun dato" }
    /** The month's name; the running month adds its coverage while partial ("Novembre · dati al 6 nov"). */
    fun label(i: Int, today: LocalDate, withCoverage: Boolean = true): String {
        val m = timeline.getOrNull(i) ?: return "—" // total: an index outside the timeline (a strip caught between two counts) names nothing
        val c = if (withCoverage && m == YearMonth.from(today) && has(m)) note(m, today) else null
        return Fmt.monthTitle(m) + (c?.let { " · $it" } ?: "")
    }
}

/** Months with cash-flow data (movements, contributions, covered liquidity days): what Mese (Flusso, Versamenti) can show. */
internal fun Archive.flowMonths(today: LocalDate, cover: Coverage): Set<YearMonth> {
    val out = mutableSetOf<YearMonth>()
    externalMovements.filter { it.status != ExternalStatus.IGNORED }.forEach { out += YearMonth.from(it.date) }
    versatoTotal().forEach { out += YearMonth.from(it.date) }
    events.filter { it.type == EventType.EMPLOYER_CONTRIBUTION }.forEach { out += YearMonth.from(it.date) }
    if (cover.from != null && cover.to != null && !cover.from.isAfter(cover.to)) {
        var m = YearMonth.from(cover.from); val last = minOf(YearMonth.from(cover.to), YearMonth.from(today))
        while (!m.isAfter(last)) { out += m; m = m.plusMonths(1) }
    }
    return out
}

/**
 * 1a-20: the month chosen, else the latest month with covered cash-flow data (a month that holds only a dated pillar value
 * would open Mese on an empty Flusso), else the latest on the timeline.
 */
internal fun defaultShownMonth(chosen: String?, timeline: List<YearMonth>, flow: Set<YearMonth>, today: LocalDate): YearMonth {
    val c = chosen?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    return c?.takeIf { it in timeline } ?: timeline.lastOrNull { it in flow } ?: shownMonth(null, timeline, today)
}

@Composable
internal fun rememberMonthSpan(app: App, a: Archive, routeMonth: YearMonth? = null): MonthSpan {
    val today = app.today
    val m = app.home(a)
    return MonthSpan(m.timeline, m.data, m.cover, defaultShownMonth(app.nav.month ?: routeMonth?.toString(), m.timeline, m.flow, today))
}

/** The month strip over [span]: settling stores the month in the navigation state (one month for the row and Mese). */
@Composable
internal fun MonthSwipe(app: App, span: MonthSpan, strip: PeriodState, modifier: Modifier = Modifier, gutter: androidx.compose.ui.unit.Dp, wide: Boolean = false, primary: Boolean = true, peeking: Boolean = true, surface: Color? = null, content: @Composable (YearMonth) -> Unit) {
    PeriodSwipe(strip, { i -> span.timeline.getOrNull(i)?.let { app.nav = app.nav.withMonth(it.toString()) } }, modifier,
        hasData = { i -> span.timeline.getOrNull(i)?.let(span::has) == true }, gutter = gutter, inset = if (peeking) Motion.periodInset(wide) else gutter, wide = wide, primary = primary, surface = surface) { i ->
        span.timeline.getOrNull(i)?.let { content(it) }
    }
}

/** The month labels over a strip; the current name opens the calendar (G-11). [showCurrent] false: neighbours only. */
@Composable
internal fun MonthLabels(app: App, span: MonthSpan, strip: PeriodState, style: TextStyle, color: Color, showCurrent: Boolean = true, edgeInset: androidx.compose.ui.unit.Dp = 0.dp, gutter: androidx.compose.ui.unit.Dp = 16.dp, wide: Boolean = false, peeking: Boolean = true) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> span.timeline.getOrNull(i)?.let { app.hx?.tick(); app.nav = app.nav.withMonth(it.toString()) } }
    Box {
        PeriodLabels(strip, { span.label(it, app.today) }, pick, if (showCurrent) ({ calendar = true }) else null, style, color,
            showCurrent = showCurrent, hasData = { i -> span.timeline.getOrNull(i)?.let(span::has) == true }, edgeInset = edgeInset, gutter = gutter, inset = if (peeking) Motion.periodInset(wide) else gutter, wide = wide)
        if (calendar) MonthCalendar(span.timeline, span::has, span.shown, { m -> app.hx?.tick(); app.nav = app.nav.withMonth(m.toString()) }) { calendar = false }
    }
}

/**
 * Mese (DESIGN "Mese"; boards 1a, 1c right pane; pushed page on the cover): the whole cash-flow view of one month. Only the
 * board blocks: the header with the day chip, "Flusso delle entrate" with what stays, and "Versamenti" with the personal
 * and employer totals kept apart. Spending by category lives in Spese, which also owns the month's signals (1a-19).
 * G-11: the Flusso and Versamenti swipe between months (peek of the neighbours, their names at the edges); the title opens
 * the calendar; only the settled month changes the header. A month without data reads "—" with its coverage (1a-20).
 * [routeMonth]: the month a pushed page was opened on; null for the right pane's root.
 */
@Composable
fun MeseScreen(app: App, routeMonth: YearMonth?, root: Boolean) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val today = app.today
    // A page opened on a given month (a link from another screen) makes that month the chosen one.
    if (routeMonth != null) LaunchedEffect(Unit) { val m = routeMonth.toString(); if (app.nav.month != null && app.nav.month != m) app.nav = app.nav.withMonth(m) }
    val span = rememberMonthSpan(app, a, routeMonth)
    val strip = rememberPeriodState(span.index, span.timeline.size)
    val month = span.shown
    val v = remember(a, month) { a.monthView(month, today) }
    val to = span.cover.to
    val cov = span.coverage(month, today)
    // M1: the coverage line and the day chip are per period; each neighbour supplies its own, so they travel with the page (PeriodPageHeader).
    fun dataLineOf(m: YearMonth): String {
        val c = span.coverage(m, today)
        return when {
            !span.has(m) -> c ?: "nessun dato"
            to == null -> "nessun conto collegato"
            c != null && c != "nessun dato" -> c
            to.isBefore(m.atDay(1)) -> "nessun dato per questo mese"
            else -> coverageLabel(m, span.cover, today, Fmt.monthShort(m), fullAsEnd = true) ?: "nessun dato"
        }
    }
    val dataLine = dataLineOf(month)
    var calendar by rememberSaveable { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> span.timeline.getOrNull(i)?.let { app.hx?.tick(); app.nav = app.nav.withMonth(it.toString()) } }
    Page(horizontal = PageSpec.Gutter, top = PageSpec.Top, spacing = 8.dp) {
        Box {
            PeriodPageHeader(Fmt.monthTitle(month), dataLine + sampleSuffix(a), // CR-70/K8: no range; the period-title names the month, the coverage line its end
                 if (root) null else ({ app.back() }), backLabel = "Indietro a Pilastri",
                titleModifier = Modifier.periodControl(strip, { span.label(it, today) }, pick, { calendar = true }, pal.pressTint),
                strip = strip, titleOf = { i -> span.timeline.getOrNull(i)?.let { Fmt.monthTitle(it) } ?: Fmt.monthTitle(month) },
                subtitleOf = { i -> dataLineOf(span.timeline.getOrNull(i) ?: month) + sampleSuffix(a) },
                chipOf = { i -> (span.timeline.getOrNull(i) ?: month).takeIf { YearMonth.from(today) == it }?.let { "giorno ${today.dayOfMonth}/${it.lengthOfMonth()}" to "Giorno ${today.dayOfMonth} di ${it.lengthOfMonth()}" } })
            if (calendar) MonthCalendar(span.timeline, span::has, month, { m -> app.hx?.tick(); app.nav = app.nav.withMonth(m.toString()) }) { calendar = false }
        }
        MonthLabels(app, span, strip, Type.tinyStrong, pal.ink2, showCurrent = false, gutter = PageSpec.Gutter, wide = app.twoPane)
        // G-11: each card is its own strip on the one shared state, so a neighbour's sliver has exactly that card's top, bottom and corners.
        val g = PageSpec.Gutter
        MonthSwipe(app, span, strip, gutter = g, wide = app.twoPane) { m -> FlussoCard(app, a, m, today, span.has(m)) }
        MonthSwipe(app, span, strip, gutter = g, wide = app.twoPane, primary = false) { m -> VersamentiCard(remember(a, m) { a.monthVersamenti(m) }) }
        // Board 9 (9-02…9-06): irregular income, only for a person who has such a position or an F24 kind.
        if (remember(a) { a.irregularPositions().isNotEmpty() || a.positionKinds().any { it in F24Kinds } }) IrregularIncomeBlocks(app, a, span.shown, today) { c ->
            MonthSwipe(app, span, strip, gutter = g, wide = app.twoPane, primary = false, content = c)
        }
    }
}

/** 1a-15, 1a-16: where the month's income goes (a page of the Flusso strip); flows not funded by income are dashed with their caption (Flow.kt). */
@Composable
private fun FlussoCard(app: App, a: Archive, month: YearMonth, today: LocalDate, hasData: Boolean) {
    val pal = LocalPal.current
    val v = remember(a, month) { a.monthView(month, today) }
    TitledCard("Flusso delle entrate", trailing = {
        if (hasData) Tag("calcolato")   // CR-70/K8: the diagram row "restano" owns the amount
    }) {
        if (!hasData || (v.spending.entrateCents == 0L && v.spending.spesaCents == 0L)) Text("—", style = Type.body, color = pal.ink2)
        else FlowDiagram(v, onOpen = { if (app.twoPane) app.select(it) else app.go(it) })
    }
}

/** 1a-17, 1a-18: the month's money into the pillars, each row tagged tuo / datore / da verificare; the two totals kept apart. */
@Composable
private fun VersamentiCard(v: MonthVersamenti) {
    val pal = LocalPal.current
    // K8: the month's range is the header subtitle's; the card does not repeat it.
    TitledCard("Versamenti") {
        if (v.rows.isEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("—", style = Type.body, color = pal.ink2)
        }
        v.rows.forEach { r -> VersamentoLine(r) }
        Hairline()
        val mine = when {
            v.tuoTotalCount == 0 -> "—"
            v.tuoCount < v.tuoTotalCount -> Fmt.eur(v.tuoCents) + " · su ${v.tuoCount} di ${v.tuoTotalCount}"
            else -> Fmt.eur(v.tuoCents)
        }
        // CR-70/K8: one row that is the whole total is not repeated as a total line.
        if (!(v.rows.size == 1 && v.tuoTotalCount == 1 && v.tuoCount == 1)) FooterLine("Versato tuo nel mese", mine)
        FooterLine("Datore · separato", v.employerCents?.let { Fmt.eur(it) } ?: "n/d")
    }
}

@Composable
private fun VersamentoLine(r: VersamentoRow) {
    val pal = LocalPal.current
    val sub = Fmt.day(r.date) + (r.from?.let { " · da $it" } ?: r.note?.let { " · $it" } ?: "")
    val tag = when (r.kind) { VersamentoKind.TUO -> "tuo"; VersamentoKind.DATORE -> "datore"; VersamentoKind.DA_VERIFICARE -> "da verificare" }
    Hairline(color = pal.cardLine.copy(alpha = 0.6f))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) { contentDescription = "${r.name}, $sub, ${Fmt.eurCents(r.cents)}, $tag" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Type.rowTitle, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Type.tiny, color = pal.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Fmt.eur(r.cents), style = Type.amount, color = pal.ink, maxLines = 1)
            KindTag(r.kind, tag)
        }
    }
}

/** "tuo" filled lilac, "datore" outlined in the brand ink, "da verificare" outlined in the neutral ink like the other state tags: the text says it; warning colour is for over-budget only. */
@Composable
private fun KindTag(kind: VersamentoKind, text: String) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(9.dp)
    val m = when (kind) {
        VersamentoKind.TUO -> Modifier.background(pal.chip, shape)
        VersamentoKind.DATORE -> Modifier.border(1.dp, pal.btnLine, shape)
        VersamentoKind.DA_VERIFICARE -> Modifier.border(1.dp, pal.btnLine, shape)
    }
    val ink = when (kind) { VersamentoKind.TUO -> pal.onChip; VersamentoKind.DATORE -> pal.brand; VersamentoKind.DA_VERIFICARE -> pal.ink2 }
    Box(m.heightIn(min = 18.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { Text(text, style = Type.tinyStrong, color = ink, maxLines = 1) }
}

@Composable
private fun FooterLine(label: String, value: String) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.bodyStrong, color = pal.ink, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
        Text(value, style = Type.amount.copy(color = Color.Unspecified), color = pal.ink, maxLines = 1)
    }
}
