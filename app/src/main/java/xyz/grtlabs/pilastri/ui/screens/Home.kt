package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.auto.ProviderSyncState
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.charts.*

/**
 * Pilastri, the left pane (Giro 3 boards 1a, 1b, 1c; cover 7a, 7b; DESIGN "Pilastri, left pane"): the income card when a
 * credit arrives, the accent card (Attività and Versato tuo, the four pillar columns, the month's two figures, freshness),
 * then "Attività · 12 mesi" and, on the cover, the month row that opens Mese. On two panes Mese is the right pane's root.
 */
@Composable
fun HomeScreen(app: App, wide: Boolean) {
    val a = app.archive ?: return
    val today = app.today
    // 1a-20, G-11: one month for the month figures and the cover row (and Mese), swiped on either; only the settled month applies.
    val span = rememberMonthSpan(app, a)
    // Open Fold: the left pane never moves, so a month chosen on the right pane fades the hero's month content through instead of sliding it (a parameter of the one strip).
    val strip = rememberPeriodState(span.index, span.timeline.size, fadeOutside = app.twoPane)
    // Page's layout with the root scroll shared by both window shapes (G-09: folding and unfolding keep the scroll).
    val h = PageSpec.gutter(wide)
    val scroll = LocalRootScroll.current ?: androidx.compose.foundation.rememberScrollState()
    Column(Modifier.fillMaxSize()) { Column(
        Modifier.weight(1f).pageContentTag().verticalScroll(scroll).padding(start = h, end = h, top = PageSpec.Top, bottom = 16.dp + LocalDockInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Impostazioni has its own corner button (Council 3 §1, last amendment): never in a title row.
        RootHeader("Pilastri", sampleSuffix(a).removePrefix(" · ").ifBlank { null }) // CR-70/K8: today's date is not repeated; the freshness line owns the date
        IncomeCard(app, a)
        AccentCard(app, a, span, strip)
        if (a.isEmpty) xyz.grtlabs.pilastri.ui.setup.SetupEntryCard(app)
        else {
            AttivitaCard(app.home(a).line)
            // G-08: on the open Fold Mese is the right pane's root, so its entry is the same component, hidden, state kept.
            CoverMonthRow(app, a, span, strip, Modifier.paneEntry(app.twoPane))
        }
    } }
}

@Composable
private fun AccentCard(app: App, a: Archive, span: MonthSpan, strip: PeriodState) {
    val pal = LocalPal.current
    val today = app.today
    val model = app.home(a)
    val sum = model.sum
    val real = model.real
    val fresh = model.fresh
    val asOf = model.asOf
    // Large text: the columns become a 2×2 grid, so names and figures stay whole.
    val big = LocalDensity.current.fontScale > 1.25f
    val selected: Any? = when {
        !app.twoPane -> null
        app.nav.tab != Tab.PILASTRI -> null
        app.nav.depth.isEmpty() -> null // N-02: board 1a draws no ring around the month block
        else -> (app.nav.depth.first() as? Route.Pillar)?.pillar
    }
    Box(Modifier.fillMaxWidth()) {
        // CR-55/61: the shared AccentCard is the only accent fill; its content is one column (16 dp sides, board 1a).
        AccentCard {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatAttivita(sum, Modifier.weight(1f))
                    StatVersato(real, Modifier.weight(1f)) { app.go(Route.Contributions) }
                }
                VSpace(12.dp)
                HaloBox(selected, Color.White.copy(alpha = 0.10f), Color.White, radius = 16.dp) { halo ->
                    Column {
                        PillarColumns(app, a, big, selected, halo)
                        if (!a.isEmpty) {
                            VSpace(12.dp)
                            Column {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
                                // L-2: the label row and the strip sit on the card's content padding (PageSpec.Gutter), the peek inside it.
                                MonthLabels(app, span, strip, Type.labelOnAccent, pal.onAccent, edgeInset = Motion.PeriodSurfacePad, gutter = PageSpec.Gutter, wide = app.twoPane) // CR-02: the edge labels start / end on the tile's content edge, like the figures
                                MonthSwipe(app, span, strip, gutter = PageSpec.Gutter, wide = app.twoPane, surface = pal.accentTile) { m ->
                                    val change = remember(model, m) { model.change(m) }
                                    val vers = remember(model, m) { model.versamenti(m) }
                                    MonthFigures(change, vers, span.has(m), span.note(m, today).takeUnless { app.meseBeside }, Modifier.periodContentFade(), beside = app.meseBeside, onClick = if (app.twoPane) ({ app.nav = app.nav.popToRoot() }) else null)
                                }
                            }
                        }
                    }
                }
                FreshnessLine(freshnessText(asOf, fresh.known, 4, fresh.stale.firstOrNull()), complete = fresh.known == 4 && fresh.stale.isEmpty(),
                    Modifier.padding(top = 4.dp), onAccent = true)
                Relogin(app, a)
            }
        }
        SyncLine(Modifier.align(Alignment.TopCenter))
    }
}

/** 1a-02: a 2 dp line along the top edge while any provider syncs; nothing at rest, never a text state. */
@Composable
private fun SyncLine(modifier: Modifier) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val states = ImportProvider.entries.map { p -> remember(p) { AutoSyncKit.state(ctx, p) }.collectAsState() }
    val running = states.any { it.value is ProviderSyncState.Syncing }
    AnimatedVisibility(running, modifier, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects()), label = "sync-line") { SyncBar(Modifier.padding(horizontal = 34.dp), color = LocalPal.current.accentLabel, track = Color.White.copy(alpha = 0.20f)) }
}

@Composable
private fun heroLabelSlot(): androidx.compose.ui.unit.Dp = if (LocalDensity.current.fontScale > 1.25f) 48.dp else 24.dp

/** Top padding that puts a label's FIRST line at the centre of [heroLabelSlot]: both hero labels start on one line whether one of them wraps to two (font 2.0) or not. */
@Composable
private fun heroLabelTop(): androidx.compose.ui.unit.Dp = with(LocalDensity.current) { ((heroLabelSlot() - Type.labelOnAccent.lineHeight.toDp()) / 2).coerceAtLeast(0.dp) }

/** The shared height of the hero's figure slot (M3): the primary figure's line, at least the 48 dp target of Versato tuo's press. */
@Composable
private fun heroValueSlot(): androidx.compose.ui.unit.Dp = with(LocalDensity.current) { maxOf(48.dp, Type.primaryFigure.lineHeight.toDp()) }

/** 1a-03: the sum of the pillars that have a value, "su N di 4" when some are n/d; none → "n/d". */
@Composable
private fun StatAttivita(sum: AttivitaSum, modifier: Modifier) {
    val pal = LocalPal.current
    Column(modifier.semantics(mergeDescendants = true) {}) {
        // Board 1a: Attività is THE primary figure (CR-55/56); Versato tuo is a secondary one.
        // M3 (wave 10): both hero columns put their figure in one slot height, so the two labels sit on one line.
        Box(Modifier.height(heroValueSlot()), contentAlignment = Alignment.CenterStart) {
            FitText(sum.cents?.let { Fmt.eur(it) } ?: "n/d", Type.primaryFigure, pal.onAccent, Modifier.primaryFigureTag())
        }
        // M3: the neighbour column's label row holds the "?" button; this one reserves the same row height so both labels start on one line.
        Row(Modifier.heightIn(min = heroLabelSlot()), verticalAlignment = Alignment.Top) {
            Text(if (sum.known in 1..3) "Attività · su ${sum.known} di 4" else "Attività", style = Type.labelOnAccent, color = pal.accentSoft, maxLines = 2, modifier = Modifier.padding(top = heroLabelTop()).statLabelTag())
        }
    }
}

/**
 * 1a-04: personal money put in, in January-2026 euros (NIC), inside the accent card; "su N di M versamenti" when some months
 * have no index yet; nothing indexable → "n/d", the nominal figure behind "?". Tapping opens the detail (Quanto hai messo).
 */
@Composable
private fun StatVersato(real: RealCard, modifier: Modifier, onOpen: () -> Unit) {
    val pal = LocalPal.current
    val value = real.indexedRealCents?.let { Fmt.eur(it) } ?: if (real.totalCount == 0) "—" else "n/d"
    val partial = real.indexedRealCents != null && real.indexedCount < real.totalCount
    val details = buildString {
        append("Soldi tuoi messi nei pilastri, lordi: i prelievi non li compensano e il contributo del datore resta separato.\n\n")
        append("Nominale: ${Fmt.eurCents(real.nominalTotalCents)} su ${real.totalCount} versamenti.\n")
        if (real.indexedRealCents != null) append("In euro di gennaio 2026 (ISTAT NIC): ${Fmt.eurCents(real.indexedRealCents)} su ${real.indexedCount} di ${real.totalCount} versamenti.\n")
        else if (real.totalCount > 0) append("Nessun versamento ha ancora un indice ISTAT compatibile: in euro di gennaio 2026 è n/d.\n")
        real.awaiting.forEach { (m, c) -> append("${Fmt.monthTitle(m)}: ${Fmt.eurCents(c)} in attesa dell'indice.\n") }
        append("\nDatore: ").append(real.employerCents?.let { Fmt.eurCents(it) } ?: "n/d").append(", separato.")
    }
    Column(modifier) {
        // UX-N4: the value opens "Quanto hai messo"; the trailing chevron says so (decorative: the label below carries the action),
        // and the press (scale + white glow, role Button) is immediate on pointer down.
        Row(Modifier.fillMaxWidth().height(heroValueSlot()).pressable(RoundedCornerShape(12.dp), Color.White, onClick = onOpen, role = Role.Button).semantics(mergeDescendants = true) {
            contentDescription = "Versato tuo in euro di gennaio 2026: $value" + (if (partial) ", su ${real.indexedCount} di ${real.totalCount} versamenti" else "") + ". Apri Quanto hai messo"
        }, verticalAlignment = Alignment.CenterVertically) {
            FitText(value, Type.secondaryFigure, pal.onAccent, Modifier.weight(1f, fill = false).secondaryFigureTag())
            Icon(Ic.chevronRight, null, Modifier.padding(start = 4.dp).size(14.dp), tint = pal.accentSoft)
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("Versato tuo · € gen 2026", style = Type.labelOnAccent, color = pal.accentSoft, maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(top = heroLabelTop()).statLabelTag())
            // The "?" keeps a 48 dp target while taking 24 dp in the line (board: inline after the caption).
            // Large text: the caption wraps to the slot's edge, so the "?" takes its full 48 dp target in the row instead of overlapping the caption.
            val slot = heroLabelSlot()
            // P3: the 48 dp target starts at the slot's start, so it never covers the caption's last characters (it overhangs on the free side only).
            Box(Modifier.size(slot).wrapContentSize(Alignment.CenterStart, unbounded = true), contentAlignment = Alignment.CenterStart) { InfoButton("Versato tuo", details, Modifier.requiredSize(48.dp), tint = pal.accentSoft) }
        }
        if (partial) Text("su ${real.indexedCount} di ${real.totalCount} versamenti", style = Type.captionOnAccent, color = pal.accentLabel, maxLines = 1)
    }
}

/* ---------- Pillar columns (1a-05 … 1a-08) ---------- */

/** Press clip of a pillar column: round on top only (D-08), so the text under the tile never meets a rounded corner. */
private val PillarColumnShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

internal data class ColumnData(val pillar: Int, val cents: Long?, val target: TargetView, val date: ValueDate)

@Composable
private fun PillarColumns(app: App, a: Archive, grid: Boolean, selected: Any?, halo: HaloState) {
    val today = app.today
    val cols = app.home(a).columns
    @Composable fun one(c: ColumnData, m: Modifier) = PillarColumn(c, selected == c.pillar, m, Modifier.haloTarget(halo, c.pillar)) { app.select(Route.Pillar(c.pillar)) }
    if (grid) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cols.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { one(it, Modifier.weight(1f)) } } }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { cols.forEach { one(it, Modifier.weight(1f)) } }
}

private fun ValueDate.text(): String? = when (this) {
    is ValueDate.At -> "al ${Fmt.day(date)}"
    is ValueDate.Range -> "dal ${Fmt.day(from)}" // CR-70/K8: the newest end of the range is the freshness line's date
    is ValueDate.Old -> "dato del ${Fmt.day(date)}"
    ValueDate.None -> null
}

/**
 * One pillar column (board 1a): a 120 dp tile filling from the base against a real target only, on the `fill` spring and
 * never past the track, with the % printed inside the fill; outline "senza obiettivo" without a target; dashed "n/d" when
 * the value is unknown. Under it the name, the value, the scale "di <obiettivo>" and the date. It sinks from its base.
 */
@Composable
private fun PillarColumn(c: ColumnData, selected: Boolean, modifier: Modifier, tileModifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val amount = (c.target as? TargetView.Amount)?.cents?.takeIf { it > 0 }
    val fraction = if (c.cents != null && amount != null) c.cents.toFloat() / amount else null
    val fill = remember { Animatable(if (reduce) (fraction ?: 0f) else 0f) }
    LaunchedEffect(fraction) {
        if (fraction == null) return@LaunchedEffect
        if (reduce) fill.snapTo(fraction) else { if (fill.value == 0f) delay(Motion.ChartEnterDelayMs + Motion.BarStaggerMs * c.pillar); fill.animateTo(fraction, Motion.fill()) }
    }
    val pct = fraction?.let { Fmt.pct(it.toDouble()) }
    val inside = when {
        fraction != null -> null
        c.cents == null -> "n/d"
        c.target is TargetView.Unavailable -> "obiettivo n/d"
        else -> "senza obiettivo"
    }
    val scale = when (c.target) { is TargetView.Amount -> "di " + Fmt.eur(c.target.cents); is TargetView.Unavailable -> "di n/d"; TargetView.None -> "—" }
    val date = c.date.text()
    val stale = (c.date as? ValueDate.Old)?.stale == true
    val spoken = listOfNotNull(
        pillarName(c.pillar), c.cents?.let { Fmt.eur(it) } ?: "valore n/d",
        when (c.target) { TargetView.None -> "senza obiettivo"; is TargetView.Unavailable -> "obiettivo n/d"; is TargetView.Amount -> (pct?.let { "$it " } ?: "") + scale },
        date?.let { if (stale) "$it, datato" else it }, if (selected) "selezionato" else null,
    ).joinToString(", ")
    val shape = RoundedCornerShape(16.dp)
    Column(
        // D-08: only the TOP corners are round (the 120 dp tile); a round bottom corner cut the first glyph of the date line.
        modifier.pressable(PillarColumnShape, Color.White, onClick = onClick, pressedScale = 0.94f, origin = TransformOrigin(0.5f, 1f))
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button },
    ) {
        Box(
            tileModifier.fillMaxWidth().height(120.dp).clip(shape)
                .then(if (fraction != null) Modifier.background(pal.accentTile) else Modifier)
                .drawBehind {
                    if (fraction != null) {
                        val h = size.height * fill.value.coerceIn(0f, 1f)
                        if (h > 0.5f) {
                            val r = 10.dp.toPx()
                            drawRoundRect(pal.accentFill, Offset(0f, size.height - h), Size(size.width, h + r), CornerRadius(r))
                        }
                    } else {
                        val w = 1.5.dp.toPx()
                        val dash = if (c.cents == null) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null
                        drawRoundRect(pal.accentLine, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(16.dp.toPx() - w / 2), style = Stroke(w, pathEffect = dash))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (pct != null) {
                // The % sits inside the fill near its top; a fill too low to hold it puts it just above, in the light ink.
                val h = 120f * fill.value.coerceIn(0f, 1f)
                val insideFill = h >= 26f
                Text(pct, style = Type.bodyStrong, color = if (insideFill) pal.composerDiscInk else pal.onAccent, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (if (insideFill) 120f - h + 7f else (120f - h - 20f).coerceAtLeast(4f)).dp))
            } else if (inside != null) Text(inside, style = if (c.cents == null) Type.bodyStrong else Type.tinyStrong,
                color = pal.accentSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pillarName(c.pillar), style = Type.labelOnAccent, color = if (selected) pal.onAccent else pal.accentSoft, maxLines = 1, modifier = Modifier.weight(1f, fill = false).statLabelTag())
            if (selected) Icon(Ic.chevronRight, null, Modifier.padding(start = 2.dp).size(10.dp), tint = pal.onAccent)
        }
        FitText(c.cents?.let { Fmt.eur(it) } ?: "n/d", Type.secondaryFigure, pal.onAccent, Modifier.secondaryFigureTag())
        FitText(scale, Type.captionOnAccent, pal.accentSoft, minScale = 0.8f)
        // D-08: a plain single-line Text (no FitText step-down re-layout), so the first glyph is never drawn before the final size settles.
        if (date != null) Text(date, style = if (stale) Type.tinyStrong else Type.captionOnAccent, color = pal.accentLabel, maxLines = 1, softWrap = false)
    }
}

/* ---------- The month's two figures (1a-09, 1a-10), never netted ---------- */

/**
 * 1a-09, 1a-10 for one month (a page of the month strip). A month without data reads "—" with its coverage, never "0 €"
 * (1a-20); [coverage] is the month's coverage state ("dati al 6 nov") when partial.
 */
@Composable
internal fun MonthFigures(change: MonthValueChange?, v: MonthVersamenti, hasData: Boolean, coverage: String?, modifier: Modifier, onClick: (() -> Unit)?, beside: Boolean = false) {
    val pal = LocalPal.current
    val aValue = when { !hasData -> "—"; change == null -> "n/d"; else -> Fmt.signed(change.cents) }
    val aSub = when {
        !hasData -> coverage ?: if (beside) "—" else "nessun dato" // beside Mese its subtitle owns the coverage of any month (CR-70/K8)
        change == null -> coverage ?: if (beside) "—" else "n/d"
        else -> "dal " + Fmt.day(change.from) + if (change.counted < 4) " · su ${change.counted} di 4" else ""
    }
    val bValue = if (!hasData || v.tuoTotalCount == 0) "—" else Fmt.eur(v.tuoCents)
    val bSub = when {
        !hasData -> coverage ?: if (beside) "—" else "nessun dato" // beside Mese its subtitle owns the coverage of any month (CR-70/K8)
        v.tuoTotalCount == 0 -> "tuo · nessun versamento"
        v.tuoCount < v.tuoTotalCount -> "tuo · su ${v.tuoCount} di ${v.tuoTotalCount} versamenti"
        else -> "tuo · ${v.tuoCount} " + if (v.tuoCount == 1) "versamento" else "versamenti"
    }
    // CR-01 (peek-visible): the page is a tonal tile (PeriodSwipe `surface`); the figures keep PeriodSurfacePad off its sides.
    Column(modifier.fillMaxWidth().padding(horizontal = Motion.PeriodSurfacePad)) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .then(if (onClick != null) Modifier.pressable(RoundedCornerShape(8.dp), Color.White, onClick = onClick) else Modifier) // CR-66: radius 8 stays inside the 10/6 dp padding
                .padding(top = 10.dp, bottom = 6.dp), // L-2: figures start on the month labels' line (the box content edge)
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // P2: the first cell is as wide as its content needs (its label stays on one line, also at Fold width), the second takes the rest.
            Figure("Variazione del mese · valore", aValue, aSub, Modifier.width(IntrinsicSize.Max))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.18f)))
            Figure("Versato nel mese", bValue, bSub, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(caption: String, value: String, sub: String, modifier: Modifier) {
    val pal = LocalPal.current
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(caption, style = Type.labelOnAccent, color = pal.accentSoft, maxLines = 2, modifier = Modifier.statLabelTag())
        FitText(value, Type.secondaryFigure, pal.onAccent, Modifier.secondaryFigureTag())
        Text(sub, style = Type.captionOnAccent, color = pal.accentTileLabel, maxLines = 2) // F5: the hero month tile's own caption token (>= 4.5:1 on the tile)
    }
}

/* ---------- Attività · 12 mesi (1a-13) ---------- */

@Composable
private fun AttivitaCard(line: AttivitaLine) {
    val first = line.points.firstOrNull(); val last = line.points.lastOrNull()
    val gaps = runsOf(line.points).size - 1
    val description = buildString {
        append("Attività negli ultimi 12 mesi")
        if (first == null || last == null) append(": nessun mese con tutti e quattro i pilastri valorizzati")
        else {
            append(": da ${Fmt.eur(first.cents)} il ${Fmt.dayYear(first.date)} a ${Fmt.eur(last.cents)} il ${Fmt.dayYear(last.date)}")
            if (gaps > 0) append(", $gaps ${if (gaps == 1) "interruzione" else "interruzioni"} senza dati")
            if (line.partial) append(", ultimo totale completo: i dati di oggi sono parziali")
        }
    }
    ChartCard("Attività · 12 mesi", "in €") {
        DatedLineChart(line.points, line.window, description, openEnd = line.partial) // CR-70/K8: the primary figure owns the value, the freshness line the date
    }
}

/* ---------- Cover: the month row (7b-01) ---------- */

@Composable
private fun CoverMonthRow(app: App, a: Archive, span: MonthSpan, strip: PeriodState, modifier: Modifier) {
    val pal = LocalPal.current
    MonthSwipe(app, span, strip, modifier, gutter = 16.dp, wide = false, primary = false) { month ->
        val sub = "Flusso e versamenti"   // CR-70/K8: the hero month label and figures own the coverage, the row title the month; no range or coverage here
        RowsCard {
            ListRow(
                Fmt.monthTitle(month), sub, { app.nav = app.nav.withMonth(month.toString()).push(Route.Month(month.toString())) },
                Modifier.semantics { contentDescription = "Apri ${Fmt.month(month)}: $sub"; role = Role.Button },
                leading = { IconDisc(Ic.calendar, pal.chip, pal.brand, 36.dp) },
            )
        }
    }
}

/** "Accedi di nuovo · provider" inline under the freshness line, only while a session has lapsed (Council 3 §3). */
@Composable
private fun Relogin(app: App, a: Archive) {
    val pal = LocalPal.current
    val lapsed = app.home(a).lapsed
    Reveal(lapsed != null) {
        val p = lapsed ?: return@Reveal
        val shape = RoundedCornerShape(50)
        Row(Modifier.padding(top = 2.dp, bottom = 4.dp).heightIn(min = 48.dp).clip(shape).pressable(shape, Color.White.copy(alpha = 0.16f), onClick = { app.reconnect(p) })
            .semantics { role = Role.Button }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.link, null, Modifier.size(16.dp), tint = pal.onAccent)
            Spacer(Modifier.width(6.dp))
            Text("Accedi di nuovo · ${p.label}", style = Type.smallStrong, color = pal.onAccent)
        }
    }
}

/* ---------- Income card (Council 4 d) ---------- */

/** The credit whose card the person closed: a UI preference only, never part of the archive. */
private object IncomePrefs {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    fun closed(c: android.content.Context): String? = prefs(c).getString("incomeClosed", null)
    fun close(c: android.content.Context, movementId: String) = prefs(c).edit().putString("incomeClosed", movementId).apply()
}

/** The income card's split swatch is outlined (not filled) exactly when the line's amount is 0 €, for any pillar (1b-02). */
internal fun swatchOutlined(cents: Long): Boolean = cents == 0L

/**
 * The single owner of the income function: titled by its rule, only for a confirmed recurring rule matching a credit in its
 * window. It grows in at the top on the page spring and the content below slides. No transfer buttons. From September to
 * December it adds "Spazio di deduzione" when the ceiling and the counted amount rest on verified rules only (1b-03).
 */
@Composable
private fun IncomeCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val arrival = app.home(a).arrival
    val rules = rememberRules()
    val room = remember(a, rules, app.today) {
        if (!deductionSeason(app.today)) null else runCatching { verifiedDeductionRoom(rules?.deductionCeiling(app.today.year, a.fundContributions(app.today.year))) }.getOrNull()
    }
    var closed by remember { mutableStateOf(IncomePrefs.closed(ctx)) }
    val shown = arrival?.takeIf { it.movement.id != closed }
    // Keeps the last card drawn while it shrinks away.
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    Reveal(shown != null) {
        val s = last ?: return@Reveal
        val lines = remember(a, s) { a.paydayProposal(s.movement.signedCents, app.today) }
        val tones = listOf(pal.brand, pal.brand.copy(alpha = 0.72f), pal.brand.copy(alpha = 0.45f), Color.Transparent)
        val close = { closed = s.movement.id; IncomePrefs.close(ctx, s.movement.id) }
        CardColumn(Modifier.swipeAway(s.movement.id, onGone = close), shape = BigCardShape, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconDisc(Ic.salary, pal.goodBg, pal.goodInk, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.rule.title, style = Type.rowTitle, color = pal.ink)
                    Text("${Fmt.weekday(s.movement.date)} · accreditato", style = Type.small, color = pal.ink3)
                }
                // The board's "Chiudi" (1b-01) stays: the accessible and discoverable alternative to the swipe.
                PillButton("Chiudi", { app.hx?.tap(); close() }, kind = Btn.GHOST)
            }
            Text(Fmt.eurCents(s.movement.signedCents), style = Type.figure, color = pal.ink, modifier = Modifier.semantics { contentDescription = "${s.rule.title} accreditato, ${Fmt.eurCents(s.movement.signedCents)}" })
            val parts = lines.filter { it.cents > 0 }
            if (parts.isNotEmpty()) Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(pal.track)) {
                parts.forEach { l -> Box(Modifier.weight(l.cents.toFloat()).fillMaxHeight().background(tones[(l.pillar - 1).coerceIn(0, 3)])) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tone = tones[(l.pillar - 1).coerceIn(0, 3)]
                            // Outlined at 0 € for any pillar (1b-02); P4's own transparent tone stays outlined too.
                            val fill = if (swatchOutlined(l.cents)) Color.Transparent else tone
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(fill).border(1.5.dp, if (tone == Color.Transparent) pal.brand else tone, RoundedCornerShape(4.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text("P${l.pillar} · ${pillarName(l.pillar)}", style = Type.small, color = pal.ink2, modifier = Modifier.weight(1f))
                            Text(Fmt.eur(l.cents), style = Type.smallStrong, color = pal.ink)
                        }
                    }
                }
                InfoButton("Ripartizione", lines.joinToString("\n") { "P${it.pillar} · ${pillarName(it.pillar)}: ${it.why}" })
            }
            if (room != null) {
                Hairline(Modifier.padding(end = 10.dp))
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Spazio di deduzione", style = Type.bodyStrong, color = pal.ink)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.eur(room), style = Type.amount, color = pal.ink, modifier = Modifier.weight(1f))
                    InfoButton("Spazio di deduzione", "Tetto annuo della deduzione meno i versamenti già contati quest'anno, tuoi e del datore; TFR escluso. Calcolato solo da regole verificate.")
                }
            }
        }
    }
}

/**
 * Lazy-UX #8: a card the person swipes sideways to dismiss. It follows the finger 1:1, fades a little as it travels, and a
 * release past [Motion.DismissFraction] of its width (or a fling) carries it off on the page spring with the release
 * velocity; [onGone] then removes it and the content below slides up ([Reveal]). Short of that it springs back. The threshold
 * crossing plays the shared threshold haptic (CommitHaptics). Reduced motion: no travel off-screen, a fade.
 */
private fun Modifier.swipeAway(key: Any, onGone: () -> Unit): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val haptics = rememberCommitHaptics(Motion.DismissFraction)
    val scope = rememberCoroutineScope()
    val x = remember(key) { Animatable(0f) }
    val gone by rememberUpdatedState(onGone)
    this
        .graphicsLayer {
            translationX = if (reduce) 0f else x.value
            alpha = 1f - (kotlin.math.abs(x.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.6f
        }
        .pointerInput(key) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); haptics.start(); scope.launch { x.stop() } },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x
                    val w = size.width.toFloat()
                    val out = kotlin.math.abs(x.value) > w * Motion.DismissFraction || kotlin.math.abs(v) > Motion.PeriodFlingDps * density * 2
                    haptics.release(committed = out)
                    scope.launch {
                        if (out) {
                            val dir = if (x.value != 0f) kotlin.math.sign(x.value) else kotlin.math.sign(v)
                            if (reduce) x.animateTo(dir * w, Motion.reducedFade()) else x.animateTo(dir * w * 1.1f, Motion.page, initialVelocity = v)
                            gone()
                        } else x.animateTo(0f, Motion.spatial(), initialVelocity = v)
                    }
                },
                onDragCancel = { haptics.cancel(); scope.launch { x.animateTo(0f, Motion.spatial()) } },
            ) { change, dx ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                val next = x.value + dx
                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { x.snapTo(next) }
                haptics.progress(next / size.width.coerceAtLeast(1))
            }
        }
}
