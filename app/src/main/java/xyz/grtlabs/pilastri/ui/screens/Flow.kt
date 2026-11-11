package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import xyz.grtlabs.pilastri.ui.charts.ChartLabelNodes
import xyz.grtlabs.pilastri.ui.charts.DrawnLabel
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.delay
import xyz.grtlabs.pilastri.ui.charts.rememberChartEnter
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*

/*
 * Where the month's income goes (concept 10's flow, also on concept 9's month page): income on the left, the part moved
 * to other pillars and the part that stays in Liquidità in the middle, spending by category (and what remains) on the
 * right. Every stream's thickness is proportional to its amount. Purchases inside a pillar and contributions from
 * outside are drawn dashed and separately, because they did not come from this month's income. All figures are
 * calculated from the movements; the card says so.
 *
 * Labels never overlap (user, 2026-10-31): values sit in one right-aligned column, names wrap to two lines or end with
 * "…" in the room left, rows keep a minimum spacing, the smallest categories fold into "Altro" when the column would
 * grow past its budget, and no text is drawn over another text, a node or a band. [layoutFlow] is pure and unit-tested.
 */

/** One spending category, already reduced to a name and its cents (> 0). */
internal data class FlowCategory(val name: String, val cents: Long, val over: Boolean, val id: String? = null, val openable: Boolean = false)

/** What a node or flow stands for (UX-N1): a category or a pillar opens its page; the grouped "Altro" expands in place. */
internal sealed interface FlowKey {
    data class Category(val id: String?) : FlowKey
    data class Pillar(val pillar: Int) : FlowKey
    data object Altro : FlowKey
}

/** The page a tap on [this] opens; "Altro" opens nothing (it expands). */
internal fun FlowKey.route(): Route? = when (this) {
    is FlowKey.Category -> Route.Category(id)
    is FlowKey.Pillar -> Route.Pillar(pillar)
    FlowKey.Altro -> null
}

/**
 * What the flow draws. [income] is null when the data holds no income for the month: it shows "—", never 0.
 * [pillarNumbers] is index-aligned with [pillars] (empty: no pillar is tappable).
 */
internal data class FlowInput(
    val income: Long?,
    val salaryNote: String?,
    val pillars: List<Pair<String, Long>>,
    val spend: Long,
    val categories: List<FlowCategory>,
    val extras: List<Pair<String, Long>>,
    val pillarNumbers: List<Int> = emptyList(),
)

internal fun MonthView.flowInput(): FlowInput {
    val shown = toPillars.filterValues { it > 0 }
    return FlowInput(
        income = spending.entrateCents.takeIf { it > 0 },
        salaryNote = salaryDate?.let { "stipendio " + Fmt.day(it) },
        pillars = shown.map { (p, c) -> pillarName(p) to c },
        spend = spending.spesaCents.coerceAtLeast(0),
        categories = categories.filter { it.spentCents > 0 }.map { FlowCategory(it.name, it.spentCents, it.over, it.id, openable = true) },
        extras = extraSources,
        pillarNumbers = shown.keys.toList(),
    )
}

internal enum class FlowText { TITLE, STRONG, SMALL, TINY, BIG }
internal enum class FlowInk { INK, INK2, BRAND, GOOD, OVER }
internal enum class FlowFill { RIBBON_IN, RIBBON_OUT, RIBBON_OVER, RIBBON_REST, INSET, NODE_MID, NODE_DARK, OVER, GOOD }
internal enum class FlowKind { NORMAL, OVER, REST }

internal data class FlowSize(val width: Float, val height: Float)

/** Measures [text] in [style] within [maxWidth] px and [maxLines] lines (the last line ends with "…"), in px. */
internal fun interface FlowMeasure { fun size(text: String, style: FlowText, maxWidth: Float, maxLines: Int): FlowSize }

internal data class FlowBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun overlaps(o: FlowBox, eps: Float = 0.01f): Boolean = left < o.right - eps && o.left < right - eps && top < o.bottom - eps && o.top < bottom - eps
}

/** A band from (x0, a0..a1) to (x1, b0..b1); both edges are cubics with their controls at the middle x, as drawn. */
internal class FlowRibbon(val x0: Float, val a0: Float, val a1: Float, val x1: Float, val b0: Float, val b1: Float, val fill: FlowFill, val dashed: FlowFill? = null) {
    /** Top and bottom edge at [x] (x(t) = 1.5t(1−t) + t³ is monotone, so a bisection finds t). */
    fun edgesAt(x: Float): Pair<Float, Float> {
        if (x1 <= x0) return a0 to a1
        val u = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        var lo = 0f; var hi = 1f
        repeat(32) { val t = (lo + hi) / 2; if (1.5f * t * (1 - t) + t * t * t < u) lo = t else hi = t }
        val t = (lo + hi) / 2
        val s = 3 * t * t - 2 * t * t * t
        return (a0 + (b0 - a0) * s) to (a1 + (b1 - a1) * s)
    }

    /** True when the band covers part of [b]. Both edges are monotone in x, so the ends of the overlap bound the band. */
    fun hits(b: FlowBox, eps: Float = 0.01f): Boolean {
        val l = maxOf(b.left, x0); val r = minOf(b.right, x1)
        if (r - l <= eps) return false
        val (t0, d0) = edgesAt(l); val (t1, d1) = edgesAt(r)
        return minOf(t0, t1) < b.bottom - eps && b.top < maxOf(d0, d1) - eps
    }
}

internal class FlowBar(val x: Float, val y: Float, val w: Float, val h: Float, val fill: FlowFill?, val dashed: FlowFill? = null) {
    val box get() = FlowBox(x, y, x + w, y + h)
}

/** A text measured within [maxWidth]; [alignEnd] puts it against the right end of that width (the value column). */
internal data class FlowLabel(
    val text: String, val style: FlowText, val ink: FlowInk, val left: Float, val top: Float,
    val maxWidth: Float, val maxLines: Int, val width: Float, val height: Float, val alignEnd: Boolean = false,
) {
    val box: FlowBox get() = if (alignEnd) FlowBox(left + maxWidth - width, top, left + maxWidth, top + height) else FlowBox(left, top, left + width, top + height)
}

/** A right-hand row after grouping: [members] are the categories it stands for ("Altro" holds several). */
internal data class FlowRow(
    val name: String, val cents: Long, val kind: FlowKind, val members: List<String> = listOf(name),
    /** What a tap on the row means; null: nothing to open ("restano", a category with no page). */
    val key: FlowKey? = null,
    /** For the grouped "Altro": the rows it folded, in input order (what it expands into). */
    val children: List<FlowRow> = emptyList(),
)

/** The drawn pieces of one right-hand row, kept together so a row can move, appear or fade as a unit. */
internal class FlowPart(val row: FlowRow, val ribbon: FlowRibbon, val bar: FlowBar, val name: FlowLabel, val value: FlowLabel, val sub: FlowLabel?)

/** A tappable region: [box] is at least 48 dp tall; [ribbon] (when set) is hit exactly along its curve. */
internal class FlowHit(val key: FlowKey, val label: String, val box: FlowBox, val ribbon: FlowRibbon? = null)

/**
 * [parts] are the elements after the first [staticRibbons]/[staticBars]/[staticLabels] of the lists (the header and
 * everything above it are identical whether "Altro" is open or not); [header] is the "Spese …" label.
 */
internal data class FlowChart(
    val height: Float, val ribbons: List<FlowRibbon>, val bars: List<FlowBar>, val labels: List<FlowLabel>, val rows: List<FlowRow>,
    val parts: List<FlowPart> = emptyList(), val hits: List<FlowHit> = emptyList(), val header: FlowLabel? = null,
    val staticRibbons: Int = ribbons.size, val staticBars: Int = bars.size, val staticLabels: Int = labels.size,
) {
    /** True when "Altro" folds several categories, so it can expand. */
    val hasGroup: Boolean get() = rows.any { it.key == FlowKey.Altro }
}

/** The target under ([x], [y]) in px: a ribbon exactly along its curve first, then the nearest tappable region. */
internal fun FlowChart.hitFor(x: Float, y: Float): FlowHit? {
    hits.firstOrNull { h -> h.ribbon?.let { r -> x >= r.x0 && x <= r.x1 && r.edgesAt(x).let { (a, b) -> y in a..b } } == true }?.let { return it }
    return hits.filter { x >= it.box.left && x <= it.box.right && y >= it.box.top && y <= it.box.bottom }
        .minByOrNull { Math.abs((it.box.top + it.box.bottom) / 2 - y) }
}

internal fun FlowChart.hitAt(x: Float, y: Float): FlowKey? = hitFor(x, y)?.key

/** One frame of the "Altro" morph: every element with the opacity it is drawn at (before the entrance fade). */
internal class FlowFrame(val height: Float, val ribbons: List<Pair<FlowRibbon, Float>>, val bars: List<Pair<FlowBar, Float>>, val labels: List<Pair<FlowLabel, Float>>)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpRibbon(a: FlowRibbon, b: FlowRibbon, t: Float) =
    FlowRibbon(lerpF(a.x0, b.x0, t), lerpF(a.a0, b.a0, t), lerpF(a.a1, b.a1, t), lerpF(a.x1, b.x1, t), lerpF(a.b0, b.b0, t), lerpF(a.b1, b.b1, t), b.fill, b.dashed)
private fun lerpBar(a: FlowBar, b: FlowBar, t: Float) = FlowBar(lerpF(a.x, b.x, t), lerpF(a.y, b.y, t), lerpF(a.w, b.w, t), lerpF(a.h, b.h, t), b.fill, b.dashed)
private fun lerpLabel(a: FlowLabel, b: FlowLabel, t: Float) = b.copy(top = lerpF(a.top, b.top, t))

/**
 * Where everything is between the folded chart [a] and the chart with "Altro" opened [b]. [g] is the geometry progress
 * (0 = [a], 1 = [b]; the rows below shift on it), [t] the opacity progress. Everything above the rows is the same in both
 * charts and just moves; a row present in both slides to its new place; the members of "Altro" grow out of the "Altro"
 * row (fading in) while that row fades out. Reduced motion passes a snapped [g] and a faded [t]: no travel, a crossfade.
 */
internal fun morphFrame(a: FlowChart, b: FlowChart, g: Float, t: Float): FlowFrame {
    val sameShape = a.staticRibbons == b.staticRibbons && a.staticBars == b.staticBars && a.staticLabels == b.staticLabels
    if (a === b || !sameShape) {
        val c = if (g >= 0.5f || a === b) b else a
        return FlowFrame(c.height, c.ribbons.map { it to 1f }, c.bars.map { it to 1f }, c.labels.map { it to 1f })
    }
    val rs = mutableListOf<Pair<FlowRibbon, Float>>(); val bs = mutableListOf<Pair<FlowBar, Float>>(); val ls = mutableListOf<Pair<FlowLabel, Float>>()
    for (i in 0 until a.staticRibbons) rs += lerpRibbon(a.ribbons[i], b.ribbons[i], g) to 1f
    for (i in 0 until a.staticBars) bs += lerpBar(a.bars[i], b.bars[i], g) to 1f
    for (i in 0 until a.staticLabels) ls += lerpLabel(a.labels[i], b.labels[i], g) to 1f
    val matched = HashSet<FlowPart>()
    for (pb in b.parts) {
        val exact = a.parts.firstOrNull { it !in matched && it.row.key != FlowKey.Altro && it.row.name == pb.row.name && it.row.kind == pb.row.kind }
        val pa = exact ?: a.parts.firstOrNull { it.row.key == FlowKey.Altro && pb.row.name in it.row.members }
        val alpha = if (exact != null) 1f else t
        if (exact != null) matched += exact
        if (pa == null) { rs += pb.ribbon to alpha; bs += pb.bar to alpha; ls += pb.name to alpha; ls += pb.value to alpha; pb.sub?.let { ls += it to alpha }; continue }
        rs += lerpRibbon(pa.ribbon, pb.ribbon, g) to alpha
        bs += lerpBar(pa.bar, pb.bar, g) to alpha
        ls += lerpLabel(pa.name, pb.name, g) to alpha
        ls += lerpLabel(pa.value, pb.value, g) to alpha
        pb.sub?.let { sb -> ls += (pa.sub?.let { sa -> lerpLabel(sa, sb, g) } ?: sb) to alpha }
    }
    // What only the folded chart has ("Altro") stays where it was and fades out.
    for (pa in a.parts) if (pa !in matched) {
        val alpha = 1f - t
        rs += pa.ribbon to alpha; bs += pa.bar to alpha; ls += pa.name to alpha; ls += pa.value to alpha; pa.sub?.let { ls += it to alpha }
    }
    return FlowFrame(lerpF(a.height, b.height, g), rs, bs, ls)
}

private fun ribbonPath(r: FlowRibbon): Path = Path().apply {
    val xm = (r.x0 + r.x1) / 2
    moveTo(r.x0, r.a0); cubicTo(xm, r.a0, xm, r.b0, r.x1, r.b0); lineTo(r.x1, r.b1); cubicTo(xm, r.b1, xm, r.a1, r.x0, r.a1); close()
}

private fun DrawScope.chevron(cx: Float, cy: Float, up: Boolean, color: Color, alpha: Float) {
    if (alpha <= 0.001f) return
    val s = 3.4.dp.toPx(); val d = if (up) -1f else 1f
    drawPath(
        Path().apply { moveTo(cx - s, cy - d * s / 2); lineTo(cx, cy + d * s / 2); lineTo(cx + s, cy - d * s / 2) },
        color, alpha = alpha, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * The income flow. Enters once per appearance (the bands reveal left to right on [Motion.chartLine], the nodes grow on
 * [Motion.fill], texts fade in behind the front; reduced motion: the final state fades in). With [onOpen], tapping a
 * category or pillar (node, label or band) opens it; tapping the grouped "Altro" expands it in place on the retarget
 * spring ([Motion.chartUpdate]), tapping the "Spese" header folds it back. Every action is also an accessibility action.
 */
@Composable
fun FlowDiagram(view: MonthView, modifier: Modifier = Modifier, onOpen: ((Route) -> Unit)? = null) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val density = LocalDensity.current
    val reduce = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val enter = rememberChartEnter()
    val grow = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(grow) { if (!reduce) { delay(Motion.ChartEnterDelayMs); grow.animateTo(1f, Motion.fill()) } }
    var expanded by remember(view.month) { mutableStateOf(false) }
    val morph = remember { Animatable(0f) }
    var lastHit by remember { mutableStateOf<FlowHit?>(null) }
    var down by remember { mutableStateOf(false) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(down) { glow.animateTo(if (down) 1f else 0f, Motion.effects()) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wPx = with(density) { maxWidth.toPx() }
        val input = remember(view) { view.flowInput() }
        val (closed, open) = remember(input, wPx, density) {
            val measure = FlowMeasure { text, style, maxWidth, maxLines ->
                val r = measurer.measure(text, flowStyle(style), overflow = TextOverflow.Ellipsis, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)))
                FlowSize(r.size.width.toFloat(), r.size.height.toFloat())
            }
            val toPx: (Float) -> Float = { with(density) { it.dp.toPx() } }
            val c = layoutFlow(input, wPx, toPx, density.fontScale, measure)
            c to (if (c.hasGroup) layoutFlow(input, wPx, toPx, density.fontScale, measure, expandAltro = true) else c)
        }
        val wantOpen = expanded && closed.hasGroup
        LaunchedEffect(wantOpen) { morph.animateTo(if (wantOpen) 1f else 0f, if (reduce) Motion.reducedFade<Float>() else Motion.chartUpdate<Float>()) }
        val t = morph.value.coerceIn(0f, 1f)
        val frame = morphFrame(closed, open, if (reduce) (if (wantOpen) 1f else 0f) else t, t)
        val active = if (wantOpen) open else closed
        val aria = remember(view) { describe(view) }
        val actions = remember(active, onOpen, wantOpen) {
            buildList {
                active.hits.filter { it.key == FlowKey.Altro || onOpen != null }.forEach { h ->
                    add(CustomAccessibilityAction(h.label) {
                        if (h.key == FlowKey.Altro) expanded = true else h.key.route()?.let { onOpen?.invoke(it) }
                        true
                    })
                }
                if (wantOpen) add(CustomAccessibilityAction("Comprimi Altro") { expanded = false; true })
            }
        }
        fun fill(f: FlowFill): Color = when (f) {
            FlowFill.RIBBON_IN -> pal.ribbonIn; FlowFill.RIBBON_OUT -> pal.ribbonOut; FlowFill.RIBBON_OVER -> pal.ribbonOver
            FlowFill.RIBBON_REST -> pal.ribbonRest; FlowFill.INSET -> pal.inset; FlowFill.NODE_MID -> pal.nodeMid
            FlowFill.NODE_DARK -> pal.nodeDark; FlowFill.OVER -> pal.over; FlowFill.GOOD -> pal.good
        }
        fun ink(i: FlowInk): Color = when (i) { FlowInk.INK -> pal.ink; FlowInk.INK2 -> pal.ink2; FlowInk.BRAND -> pal.brand; FlowInk.GOOD -> pal.goodInk; FlowInk.OVER -> pal.overInk }
        Canvas(
            Modifier.fillMaxWidth().height(with(density) { frame.height.toDp() })
                .chartTag().semantics { contentDescription = aria; customActions = actions }
                .pointerInput(active, onOpen, wantOpen) {
                    // The folded-back header only exists while "Altro" is open; the rest is the active chart's own targets.
                    fun target(o: Offset): FlowHit? {
                        val hd = active.header
                        if (wantOpen && hd != null && o.x >= hd.left && o.y >= hd.top - 8.dp.toPx() && o.y <= hd.top + hd.height + 8.dp.toPx())
                            return FlowHit(FlowKey.Altro, "Comprimi Altro", FlowBox(hd.left, hd.top - 8.dp.toPx(), size.width.toFloat(), hd.top + hd.height + 8.dp.toPx()))
                        return active.hitFor(o.x, o.y)?.takeIf { it.key == FlowKey.Altro || onOpen != null }
                    }
                    detectTapGestures(
                        onPress = { o -> target(o)?.let { lastHit = it; down = true; tryAwaitRelease(); down = false } },
                        onTap = { o ->
                            val h = target(o) ?: return@detectTapGestures
                            if (h.key == FlowKey.Altro) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); expanded = !wantOpen }
                            else h.key.route()?.let { onOpen?.invoke(it) }
                        },
                    )
                },
        ) {
            val w = size.width
            val front = if (reduce) 1f else enter.trim.value.coerceIn(0f, 1f)
            val fade = if (reduce) enter.alpha.value.coerceIn(0f, 1f) else 1f
            // 0 → 1 as the reveal front passes x (it overshoots the right edge by a feather so the end state is exactly 1).
            fun vis(x: Float): Float = if (reduce) 1f else (((front * 1.18f) - x / w) / 0.18f).coerceIn(0f, 1f)
            val gr = grow.value.coerceIn(0f, 1f)
            clipRect(left = 0f, top = 0f, right = if (reduce) w else front * w, bottom = size.height) {
                frame.ribbons.forEach { (r, a) ->
                    val p = ribbonPath(r)
                    drawPath(p, fill(r.fill), alpha = a * fade)
                    r.dashed?.let { drawPath(p, fill(it), alpha = a * fade, style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))) }
                }
            }
            frame.bars.forEach { (b, a) ->
                val cr = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                val hh = b.h * gr; val y = b.y + (b.h - hh) / 2
                val al = a * fade * vis(b.x)
                b.fill?.let { drawRoundRect(fill(it), Offset(b.x, y), Size(b.w, hh), cr, alpha = al) }
                b.dashed?.let { drawRoundRect(fill(it), Offset(b.x, y), Size(b.w, hh), cr, alpha = al, style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))) }
            }
            // Drawn with the very constraints they were measured with, so each text stays inside its box.
            frame.labels.forEach { (l, a) ->
                val m = measurer.measure(l.text, flowStyle(l.style).copy(color = ink(l.ink)), overflow = TextOverflow.Ellipsis, maxLines = l.maxLines,
                    constraints = Constraints(maxWidth = l.maxWidth.toInt().coerceAtLeast(1)))
                val lx = if (l.alignEnd) l.left + l.maxWidth - m.size.width else l.left
                drawText(m, topLeft = Offset(lx, l.top), alpha = a * fade * vis(l.left))
            }
            // The cue that "Altro" opens (a small chevron after its name) and, once open, that the header folds it back.
            if (closed.hasGroup) {
                closed.parts.firstOrNull { it.row.key == FlowKey.Altro }?.let { p ->
                    val x = p.name.box.right + 4.dp.toPx() + 3.4.dp.toPx()
                    if (x + 3.4.dp.toPx() < p.value.box.left - 4.dp.toPx()) chevron(x, p.name.top + p.name.height / 2, false, pal.ink2, (1f - t) * fade * vis(p.name.left))
                }
                closed.header?.let { hd ->
                    val x = hd.box.right + 4.dp.toPx() + 3.4.dp.toPx()
                    if (x + 3.4.dp.toPx() < w) chevron(x, hd.top + hd.height / 2, true, pal.ink2, t * fade)
                }
            }
            // Press feedback, on pointer down: a soft brand tint over the target (and along its band), fading out on release.
            lastHit?.let { h ->
                val gl = glow.value
                if (gl > 0.001f) {
                    drawRoundRect(pal.brand, Offset(h.box.left, h.box.top), Size(h.box.right - h.box.left, h.box.bottom - h.box.top), CornerRadius(8.dp.toPx()), alpha = 0.10f * gl)
                    h.ribbon?.let { drawPath(ribbonPath(it), pal.brand, alpha = 0.22f * gl) }
                }
            }
        }
        // Semantics for the canvas texts: the SETTLED frame's labels (target layout), computed from the data and width, never from the draw.
        val settledLabels = remember(closed, open, wantOpen) {
            val sf = morphFrame(closed, open, if (wantOpen) 1f else 0f, if (wantOpen) 1f else 0f)
            sf.labels.filter { it.second > 0.01f }.map { (l, _) ->
                val m = measurer.measure(l.text, flowStyle(l.style), overflow = TextOverflow.Ellipsis, maxLines = l.maxLines,
                    constraints = Constraints(maxWidth = l.maxWidth.toInt().coerceAtLeast(1)))
                val lx = if (l.alignEnd) l.left + l.maxWidth - m.size.width else l.left
                DrawnLabel(l.text, lx, l.top, m.size.width.toFloat(), m.size.height.toFloat())
            }
        }
        ChartLabelNodes(settledLabels)
    }
}

private fun flowStyle(s: FlowText): TextStyle = when (s) {
    FlowText.TITLE -> Type.smallStrong
    FlowText.STRONG -> Type.smallStrong
    FlowText.SMALL -> Type.small
    FlowText.TINY -> Type.tiny
    FlowText.BIG -> Type.secondaryFigure
}

/** Whole euros; a positive amount under 50 cents reads "<1\u00A0€", never "0 €". */
internal fun flowEur(cents: Long): String = if (cents in 1..49) "<1\u00A0€" else Fmt.eur(cents)

/**
 * Lays the flow out in px. Zones never share space: the title and the separate sources on top; pillar and Liquidità
 * labels right of the middle column and above the spending bands; the "Spese" header and the category rows in the text
 * column right of the category nodes (names, then values in one right-aligned column); "dal saldo" under the left
 * bands. [dp] converts dp to px; [fs] is the font scale (spacing between text rows grows with it).
 */
internal fun layoutFlow(input: FlowInput, w: Float, dp: (Float) -> Float, fs: Float, measure: FlowMeasure, expandAltro: Boolean = false): FlowChart {
    fun sp(x: Float) = dp(x) * fs
    val ribbons = mutableListOf<FlowRibbon>(); val bars = mutableListOf<FlowBar>(); val labels = mutableListOf<FlowLabel>()
    val hits = mutableListOf<FlowHit>(); val parts = mutableListOf<FlowPart>()
    val target = dp(48f) // every tappable region is at least this tall (centred on its row)
    fun FlowBox.minTall(): FlowBox = if (bottom - top >= target) this else ((top + bottom) / 2).let { FlowBox(left, it - target / 2, right, it + target / 2) }
    fun label(text: String, style: FlowText, ink: FlowInk, left: Float, maxWidth: Float, maxLines: Int = 1, alignEnd: Boolean = false): FlowLabel {
        val mw = maxWidth.coerceAtLeast(1f)
        val text = xyz.grtlabs.pilastri.ui.bindSeparators(text) // M1: drawn text is not a Text composable, so it binds its separators here
        val s = measure.size(text, style, mw, maxLines)
        return FlowLabel(text, style, ink, left, 0f, mw, maxLines, minOf(s.width, mw), s.height, alignEnd)
    }
    // A name with its value: side by side when the name keeps a readable width, otherwise the value under the name.
    fun nameValue(name: String, nameStyle: FlowText, value: String?, valueStyle: FlowText, ink: FlowInk, left: Float, right: Float): Pair<List<FlowLabel>, Float> {
        val room = right - left
        val v = value?.let { label(it, valueStyle, ink, left, room) }
        if (v == null) { val n = label(name, nameStyle, ink, left, room); return listOf(n) to n.height }
        val gap = dp(4f)
        val full = label(name, nameStyle, ink, left, room)
        val nameRoom = room - v.width - gap
        return if (nameRoom >= minOf(full.width, sp(48f))) {
            val n = label(name, nameStyle, ink, left, nameRoom)
            val h = maxOf(n.height, v.height)
            listOf(n.copy(top = (h - n.height) / 2), v.copy(left = left + n.width + gap, top = (h - v.height) / 2)) to h
        } else listOf(full, v.copy(top = full.height)) to full.height + v.height
    }
    fun List<FlowLabel>.at(top: Float) = map { it.copy(top = it.top + top) }

    val income = input.income ?: 0L
    val pillarsTotal = input.pillars.sumOf { it.second }
    val toLiquidity = (income - pillarsTotal).coerceAtLeast(0)
    val spend = input.spend.coerceAtLeast(0)
    val deficit = (spend - toLiquidity).coerceAtLeast(0)
    val rest = (toLiquidity - spend).coerceAtLeast(0)
    val liquidityTotal = toLiquidity + deficit

    val bw = dp(8f); val pad = dp(8f); val textGap = dp(8f) /* minimum clearance between node labels, both axes (R1 item 4: touching and tightly stacked labels) */; val rowGap = sp(5f); val nodeGap = dp(3f); val minNode = dp(2.4f)
    val textW = (w * 0.38f * fs).coerceIn(sp(118f).coerceAtMost(w * 0.55f), sp(170f).coerceAtMost(w * 0.58f))
    val lblX = w - textW
    val x2 = lblX - pad - bw
    // "dal saldo …" sits under the left bands: that column must be as wide as its longest unbreakable part (never elided).
    val deficitMinW = if (deficit > 0) maxOf(measure.size("saldo", FlowText.SMALL, w, 1).width, measure.size(flowEur(deficit), FlowText.SMALL, w, 1).width) + dp(6f) else 0f
    val x1 = (w * 0.31f).coerceAtMost(x2 - dp(70f)).coerceAtLeast(bw + dp(24f)).coerceAtLeast(deficitMinW.coerceAtMost(x2 - dp(24f)))
    val midX = x1 + bw + pad
    val scale = if (liquidityTotal + pillarsTotal > 0) (dp(170f) / (liquidityTotal + pillarsTotal).toFloat()).coerceAtMost(dp(0.13f)) else 0f
    fun h(c: Long) = (c * scale).coerceAtLeast(if (c > 0) minNode else 0f)
    var y = 0f // the first row touches the canvas top: the card body owns the inset (D-03)

    // Separate, dashed: money that did not come from this month's income.
    input.extras.forEach { (name, cents) ->
        val t = label(name + " " + flowEur(cents), FlowText.STRONG, FlowInk.INK, 0f, w, 2).copy(top = y)
        labels += t
        y = t.top + t.height + sp(4f)
        val hh = h(cents).coerceAtLeast(dp(6f))
        ribbons += FlowRibbon(bw, y, y + hh, x1, y, y + hh, FlowFill.INSET, dashed = FlowFill.NODE_MID)
        bars += FlowBar(0f, y, bw, hh, null, dashed = FlowFill.NODE_MID); bars += FlowBar(x1, y, bw, hh, null, dashed = FlowFill.NODE_MID)
        val note = label("fuori dallo stipendio", FlowText.SMALL, FlowInk.INK2, midX, w - midX, 2)
        val nt = maxOf(y + hh / 2 - note.height / 2, y)
        labels += note.copy(top = nt)
        y = maxOf(y + hh, nt + note.height) + sp(10f)
    }

    val salary = input.salaryNote?.takeIf { fs <= 1.3f }?.let { "  · $it" } ?: ""
    val title = label("Entrate " + (input.income?.let { flowEur(it) } ?: "—") + salary, FlowText.TITLE, FlowInk.INK, 0f, w, 2).copy(top = y)
    labels += title
    y = title.top + title.height + textGap
    val ey = y
    var prevText = ey - textGap // the lowest text so far right of the middle column

    // Allocation column: other pillars first, labels right of their nodes.
    var cursorIn = ey
    var ny = ey
    val centers = mutableListOf<Float>()
    val bracketX = midX + sp(120f)
    val bracket = input.pillars.size >= 2 && bracketX + sp(110f) < w
    val pillarRight = if (bracket) bracketX - dp(6f) else w
    input.pillars.forEachIndexed { pi, (name, cents) ->
        val hh = h(cents)
        val (row, rowH) = nameValue(name, FlowText.STRONG, flowEur(cents), FlowText.STRONG, FlowInk.INK, midX, pillarRight)
        ny = maxOf(ny, prevText + textGap + rowH / 2 - hh / 2)
        val c = ny + hh / 2
        val inRibbon = FlowRibbon(bw, cursorIn, cursorIn + hh, x1, ny, ny + hh, FlowFill.RIBBON_IN)
        ribbons += inRibbon
        bars += FlowBar(x1, ny, bw, hh, FlowFill.NODE_MID)
        labels += row.at(c - rowH / 2)
        input.pillarNumbers.getOrNull(pi)?.let { n ->
            // The node, its label row and the band feeding it: one target (never over the other pillars' rows: nearest centre wins).
            hits += FlowHit(FlowKey.Pillar(n), "Apri $name", FlowBox(x1, c - rowH / 2, pillarRight, c + rowH / 2).minTall(), inRibbon)
        }
        prevText = c + rowH / 2
        centers += c
        cursorIn += hh; ny += maxOf(hh, dp(4f)) + sp(12f)
    }
    if (bracket) {
        bars += FlowBar(bracketX, centers.first(), dp(1.4f), centers.last() - centers.first(), FlowFill.NODE_MID)
        val t = label("dalle entrate " + flowEur(pillarsTotal), FlowText.STRONG, FlowInk.BRAND, bracketX + dp(8f), w - bracketX - dp(8f), 2)
        val top = maxOf((centers.first() + centers.last()) / 2 - t.height / 2, ey)
        labels += t.copy(top = top)
        prevText = maxOf(prevText, top + t.height)
    }

    // Right-hand rows, grouped when the column would grow past its budget.
    val baseRows = input.categories.filter { it.cents > 0 }.map {
        FlowRow(it.name, it.cents, if (it.over) FlowKind.OVER else FlowKind.NORMAL, key = if (it.openable) FlowKey.Category(it.id) else null)
    } +
        (if (rest > 0) listOf(FlowRow("restano", rest, FlowKind.REST)) else emptyList())
    val valueW = (baseRows.map { it.cents } + baseRows.filter { it.kind == FlowKind.NORMAL }.sumOf { it.cents })
        .maxOfOrNull { label(flowEur(it), FlowText.STRONG, FlowInk.INK, 0f, textW).width } ?: 0f
    val nameW = (textW - valueW - dp(8f)).coerceAtLeast(1f)
    fun ink(k: FlowKind) = when (k) { FlowKind.OVER -> FlowInk.OVER; FlowKind.REST -> FlowInk.GOOD; FlowKind.NORMAL -> FlowInk.INK }
    class Block(val name: FlowLabel, val value: FlowLabel, val sub: FlowLabel?, val first: Float, val height: Float)
    fun block(r: FlowRow): Block {
        val nm = label(r.name, if (r.kind == FlowKind.NORMAL) FlowText.SMALL else FlowText.STRONG, ink(r.kind), lblX, nameW, 2)
        val v = label(flowEur(r.cents), FlowText.STRONG, ink(r.kind), w - valueW, valueW, 1, alignEnd = true)
        val sub = when (r.kind) {
            FlowKind.OVER -> label("oltre il budget", FlowText.TINY, FlowInk.OVER, lblX, textW, 1)
            FlowKind.REST -> label("in Liquidità · calcolato", FlowText.TINY, FlowInk.INK2, lblX, textW, 2)
            FlowKind.NORMAL -> null
        }
        val first = maxOf(nm.height, v.height)
        return Block(nm, v, sub, first, first + (sub?.let { dp(1f) + it.height } ?: 0f))
    }
    val budget = dp(300f) * fs.coerceIn(1f, 1.5f)
    fun need(rows: List<FlowRow>): Float = rows.sumOf { r -> maxOf(block(r).height + rowGap, h(r.cents).coerceAtLeast(minNode) + nodeGap).toDouble() }.toFloat()
    // Expanded: the folded categories are listed one by one (same rows, same order as the input), "Altro" is gone.
    val rows = if (expandAltro) baseRows else groupRows(baseRows, budget, ::need)

    // Liquidità: its node under everything above, its label right above the node (never inside a band).
    var ly = if (input.pillars.isNotEmpty()) ny + dp(4f) else ny
    ly = maxOf(ly, prevText + textGap)
    val inH = h(toLiquidity)
    val dh = h(deficit)
    val outH = rows.sumOf { h(it.cents).toDouble() }.toFloat()
    val lh = maxOf(h(liquidityTotal), inH + dh, outH, dp(4f))
    if (liquidityTotal > 0) {
        val (liq, liqH) = nameValue("Liquidità", FlowText.STRONG, toLiquidity.takeIf { it > 0 }?.let { flowEur(it) }, FlowText.BIG, FlowInk.INK, midX, w)
        ly = maxOf(ly, prevText + textGap + liqH + dp(4f))
        labels += liq.at(ly - dp(4f) - liqH)
        prevText = ly - dp(4f)
        if (toLiquidity > 0) ribbons += FlowRibbon(bw, cursorIn, cursorIn + inH, x1, ly, ly + inH, FlowFill.RIBBON_IN)
        bars += FlowBar(x1, ly, bw, lh, FlowFill.NODE_DARK)
    }
    val incomeH = cursorIn + inH - ey
    if (incomeH > 0f) bars += FlowBar(0f, ey, bw, incomeH, FlowFill.NODE_DARK)
    if (deficit > 0) {
        val dy = cursorIn + inH + dp(6f)
        ribbons += FlowRibbon(bw, dy, dy + dh, x1, ly + inH, ly + inH + dh, FlowFill.INSET, dashed = FlowFill.NODE_MID)
        bars += FlowBar(0f, dy, bw, dh, null, dashed = FlowFill.NODE_MID)
        // Under every left band and left of the middle column: the bands there all end above ly + lh.
        // x1 already leaves room for the longest unbreakable part (deficitMinW): wraps to up to 4 lines, never elided.
        val t = label("dal saldo " + flowEur(deficit), FlowText.SMALL, FlowInk.INK2, 0f, x1 - dp(4f), 4)
        labels += t.copy(top = maxOf(dy + dh, ly + lh) + dp(6f))
    }

    var header: FlowLabel? = null
    var staticR = ribbons.size; var staticB = bars.size; var staticL = labels.size
    if (rows.isNotEmpty()) {
        // The header clears whatever already sits over the text column (a long middle label, the bracket).
        val head = label("Spese " + flowEur(spend), FlowText.STRONG, FlowInk.INK, lblX, textW)
        // a label ending within the clearance of the text column counts as above it
        val above = (labels.map { it.box } + bars.map { it.box }).filter { it.right + textGap > lblX && it.left < w }.maxOfOrNull { it.bottom }
        val headTop = maxOf(ly - dp(2f) - head.height, (above ?: Float.NEGATIVE_INFINITY) + textGap)
        val headLabel = head.copy(top = headTop)
        labels += headLabel
        header = headLabel
        staticR = ribbons.size; staticB = bars.size; staticL = labels.size
        var prevNode = ly - nodeGap
        var prevRow = headTop + head.height
        var cum = ly
        rows.forEachIndexed { i, r ->
            val hh = h(r.cents); val vis = maxOf(hh, minNode)
            val b = block(r)
            val extra = if (r.kind == FlowKind.REST && i > 0) dp(5f) else 0f
            val c = maxOf(prevNode + nodeGap + extra + vis / 2, prevRow + rowGap + extra + b.height / 2)
            val top = c - vis / 2
            val (band, node) = when (r.kind) {
                FlowKind.OVER -> FlowFill.RIBBON_OVER to FlowFill.OVER
                FlowKind.REST -> FlowFill.RIBBON_REST to FlowFill.GOOD
                FlowKind.NORMAL -> FlowFill.RIBBON_OUT to FlowFill.NODE_MID
            }
            val ribbon = FlowRibbon(x1 + bw, cum, cum + hh, x2, top, top + hh, band)
            val bar = FlowBar(x2, top, bw, vis, node)
            ribbons += ribbon; bars += bar
            val bt = c - b.height / 2
            val nameL = b.name.copy(top = bt + (b.first - b.name.height) / 2)
            val valueL = b.value.copy(top = bt + (b.first - b.value.height) / 2)
            val subL = b.sub?.copy(top = bt + b.first + dp(1f))
            labels += nameL; labels += valueL; subL?.let { labels += it }
            parts += FlowPart(r, ribbon, bar, nameL, valueL, subL)
            r.key?.let { k ->
                // Node, band and the whole text row: from the node to the card's right edge.
                hits += FlowHit(k, if (k == FlowKey.Altro) "Espandi Altro" else "Apri ${r.name}", FlowBox(x2, bt, w, bt + b.height).minTall(), ribbon)
            }
            prevNode = top + vis; prevRow = bt + b.height; cum += hh
        }
    }

    val bottom = maxOf(
        labels.maxOfOrNull { it.top + it.height } ?: 0f,
        bars.maxOfOrNull { it.y + it.h } ?: 0f,
        ribbons.maxOfOrNull { maxOf(it.a1, it.b1) } ?: 0f,
    ) // the canvas ends at the last drawn row (D-03); no blank margin
    return FlowChart(bottom, ribbons, bars, labels, rows, parts, hits, header, staticR, staticB, staticL)
}

/**
 * Folds the smallest ordinary categories into one "Altro" row (their summed cents) until [need] fits [budget]. Over-budget
 * rows and "restano" always stay on their own; a category already called "Altro" joins the group so there is only one.
 * Nothing is dropped: the rows' total equals the input's.
 */
internal fun groupRows(rows: List<FlowRow>, budget: Float, need: (List<FlowRow>) -> Float): List<FlowRow> {
    if (need(rows) <= budget) return rows
    val normal = rows.indices.filter { rows[it].kind == FlowKind.NORMAL }
    val smallest = normal.sortedBy { rows[it].cents }
    val altro = normal.firstOrNull { rows[it].name.equals("Altro", ignoreCase = true) }
    var best = rows
    for (k in 2..smallest.size) {
        val merged = (smallest.take(k) + listOfNotNull(altro)).toSet()
        val group = merged.sorted().map { rows[it] }
        val keep = rows.indices.filter { it !in merged }.map { rows[it] }
        val altroRow = FlowRow("Altro", group.sumOf { it.cents }, FlowKind.NORMAL, group.flatMap { it.members }, FlowKey.Altro, group)
        best = keep.filter { it.kind != FlowKind.REST } + altroRow + keep.filter { it.kind == FlowKind.REST }
        if (need(best) <= budget) return best
    }
    return best
}

private fun describe(v: MonthView): String {
    val s = v.spending
    val parts = mutableListOf("Flusso di ${Fmt.month(v.month)}, calcolato", "entrate " + (s.entrateCents.takeIf { it > 0 }?.let { Fmt.eur(it) } ?: "non nei dati"))
    v.toPillars.forEach { (p, c) -> parts += "${Fmt.eur(c)} a ${pillarName(p)}" }
    parts += "spese ${Fmt.eur(s.spesaCents)}: " + v.categories.filter { it.spentCents > 0 }.joinToString(", ") { it.name + " " + Fmt.eur(it.spentCents) + if (it.over) " oltre budget" else "" }
    if (v.restCents > 0) parts += "restano ${Fmt.eur(v.restCents)} in Liquidità"
    v.extraSources.forEach { (l, c) -> parts += "separato: $l ${Fmt.eur(c)}, non dallo stipendio" }
    return parts.joinToString("; ")
}
