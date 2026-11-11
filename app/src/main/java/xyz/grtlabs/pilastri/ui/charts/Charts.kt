package xyz.grtlabs.pilastri.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import xyz.grtlabs.pilastri.ui.UiTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*

/*
 * Shared chart primitives (DESIGN "Motion and charts specification" → "Charts"). Compose Canvas, theme colours only,
 * motion tokens from Motion.kt only. Missing data is a gap or a dashed "n/d" slot, never zero; every chart carries a
 * content description; charts enter once per appearance (line trim on chartLine, bars grow on fill with a 20 ms stagger),
 * retarget on k420/ζ0.78 when the data or the basis changes, and under reduced motion show the final state with a 120 ms fade.
 */

/* ---------- Motion ---------- */

/** Enter state of one chart appearance. */
@Stable
class ChartEnter internal constructor(val reduce: Boolean) {
    /** Line reveal 0..1, left to right, on [Motion.chartLine]. */
    val trim = Animatable(if (reduce) 1f else 0f)
    /** Reduced motion: the final state fades in. */
    val alpha = Animatable(if (reduce) 0f else 1f)
}

@Composable
fun rememberChartEnter(): ChartEnter {
    val reduce = LocalReduceMotion.current
    val e = remember { ChartEnter(reduce) }
    LaunchedEffect(e) {
        if (e.reduce) e.alpha.animateTo(1f, Motion.reducedFade())
        else { delay(Motion.ChartEnterDelayMs); e.trim.animateTo(1f, Motion.chartLine) }
    }
    return e
}

/** Per-bar growth 0..1 on [Motion.fill], staggered by [Motion.BarStaggerMs]; reduced motion starts grown. */
@Composable
fun rememberBarGrow(count: Int): List<Animatable<Float, AnimationVector1D>> {
    val reduce = LocalReduceMotion.current
    val bars = remember(count) { List(count) { Animatable(if (reduce) 1f else 0f) } }
    LaunchedEffect(bars) {
        if (!reduce) bars.forEachIndexed { i, a -> launch { delay(Motion.ChartEnterDelayMs + Motion.BarStaggerMs * i); a.animateTo(1f, Motion.fill()) } }
    }
    return bars
}

/**
 * Values that follow their targets on [Motion.chartUpdate], keeping velocity when retargeted mid-flight (a basis toggle,
 * a sync). A gap (null) is never animated through: the value snaps when it reappears. Reduced motion snaps.
 */
@Composable
fun rememberRetarget(values: List<Float?>): List<Animatable<Float, AnimationVector1D>> {
    val reduce = LocalReduceMotion.current
    val anims = remember(values.size) { values.map { Animatable(it ?: 0f) } }
    val wasGap = remember(values.size) { BooleanArray(values.size) { values[it] == null } }
    values.forEachIndexed { i, v ->
        LaunchedEffect(anims, i, v) {
            if (v == null) { wasGap[i] = true; return@LaunchedEffect }
            if (wasGap[i] || reduce) anims[i].snapTo(v) else anims[i].animateTo(v, Motion.chartUpdate())
            wasGap[i] = false
        }
    }
    return anims
}

/**
 * Like [rememberRetarget], keyed (by date, by month): a value whose key stays retargets with velocity even when other keys
 * come or go (a basis toggle that drops months without an index); a new key starts at its target.
 */
@Composable
fun <K : Any> rememberRetargetBy(entries: List<Pair<K, Float>>): Map<K, Animatable<Float, AnimationVector1D>> {
    val reduce = LocalReduceMotion.current
    val store = remember { mutableMapOf<K, Animatable<Float, AnimationVector1D>>() }
    val current = entries.associate { (k, v) -> k to store.getOrPut(k) { Animatable(v) } }
    LaunchedEffect(entries) {
        entries.forEach { (k, v) -> val a = store.getValue(k); launch { if (reduce) a.snapTo(v) else a.animateTo(v, Motion.chartUpdate()) } }
    }
    return current
}

/* ---------- Axes and labels ---------- */

/** Default left gutter for y labels; two charts stacked with the same gutter share their time axis. */
val ChartGutter: Dp = 50.dp

/** A y-axis label without the € sign (the card says "in €"): "32.000", "128k", "1,2 mln". */
fun axisLabel(cents: Long): String {
    val euros = Math.floorDiv(cents, 100L)
    val abs = kotlin.math.abs(euros)
    val sign = if (euros < 0) "−" else ""
    return when {
        abs >= 1_000_000 -> sign + String.format(java.util.Locale.ITALY, "%.1f mln", abs / 1_000_000.0)
        abs >= 100_000 -> sign + "${abs / 1000}k"
        else -> Fmt.eur(cents).removeSuffix(Fmt.EUR_SUFFIX)
    }
}

/** Short month for ticks: "ott", "ott 25" with [year]. */
fun tickLabel(m: YearMonth, year: Boolean = false): String = Fmt.monthShort(m) + if (year) " " + (m.year % 100).toString().padStart(2, '0') else ""

/** "ott 25 – ott 26": the range printed in a chart card's header. */
fun windowLabel(w: Window): String = tickLabel(YearMonth.from(w.from), true) + " – " + tickLabel(YearMonth.from(w.to), true)

/**
 * D-18/U-20, the one rule for every chart's axis labels: label every n-th slot (counted back from the last, which is always
 * labelled) so that neighbours never touch. [widest] is the widest label in px, [slot] the distance between two labels' anchors.
 */
fun labelStride(slot: Float, widest: Float, gap: Float = 6f): Int =
    if (slot <= 0f) 1 else kotlin.math.ceil((widest + gap) / slot).toInt().coerceAtLeast(1)

/** [labelStride] for a row of labels measured with [m] in [style]; [gapPx] is the minimum space between two labels. */
fun DrawScope.labelStride(m: TextMeasurer, labels: List<String>, slot: Float, style: TextStyle, gapPx: Float = 6.dp.toPx()): Int =
    labelStride(slot, labels.maxOfOrNull { m.measure(it, style).size.width.toFloat() } ?: 0f, gapPx)

/** The coverage sentence added to every dated line's description: range, last value, where the data starts. */
fun coverageDescription(shown: List<SeriesPoint>, window: Window): String =
    if (shown.isEmpty()) ". Periodo ${windowLabel(window)}, nessun dato"
    else ". Periodo ${windowLabel(window)}, dati dal ${Fmt.dayYear(shown.first().date)}" +
        (if (shown.size == 1) ", un solo punto" else ", ${shown.size} punti, ultimo ${Fmt.eur(shown.last().cents)} il ${Fmt.dayYear(shown.last().date)}")

private class TimeAxis(val window: Window, val left: Float, val right: Float) {
    private val days = ChronoUnit.DAYS.between(window.from, window.to).coerceAtLeast(1).toFloat()
    fun x(d: LocalDate): Float = left + ChronoUnit.DAYS.between(window.from, d) / days * (right - left)
}

private fun DrawScope.label(m: TextMeasurer, text: String, x: Float, baselineY: Float, style: TextStyle, anchor: Int) {
    val r = m.measure(text, style)
    val left = when (anchor) { -1 -> x; 1 -> x - r.size.width; else -> x - r.size.width / 2f }
    val x0 = left.coerceIn(0f, (size.width - r.size.width).coerceAtLeast(0f)); val y0 = baselineY - r.firstBaseline
    drawChartText(r, text, x0, y0)
}

/**
 * D-03: a chart's tagged bounds equal its drawn extents (the card body owns every inset), so the canvas has no blank margin:
 * the lowest label's bottom edge is the canvas bottom, the highest label (or the round top tick, centred on its line) starts
 * at the canvas top, and y labels start at x = 0. Pure geometry so the unit test can pin it.
 */
object ChartEdge {
    /** Top of a label whose bottom touches the canvas bottom. */
    fun labelTop(height: Float, labelH: Float): Float = height - labelH
    /** The axis line sits [gap] above the label row that ends at the canvas bottom. */
    fun axisBase(height: Float, labelH: Float, gap: Float): Float = height - labelH - gap
    /** Top gridline: the y label centred on it starts at the canvas top. */
    fun plotTop(labelH: Float, floor: Float = 0f): Float = maxOf(labelH / 2f, floor)
    /** The label row (bottom) and the highest y label (top) both lie inside the canvas. */
    fun fits(height: Float, labelH: Float, gap: Float): Boolean = axisBase(height, labelH, gap) > plotTop(labelH)
}

/** A label whose top edge is [top] (x anchored like [label]); text boxes are the drawn extents. */
fun DrawScope.labelAt(m: TextMeasurer, text: String, x: Float, top: Float, style: TextStyle, anchor: Int) {
    val r = m.measure(text, style)
    val left = when (anchor) { -1 -> x; 1 -> x - r.size.width; else -> x - r.size.width / 2f }
    val x0 = left.coerceIn(0f, (size.width - r.size.width).coerceAtLeast(0f))
    drawChartText(r, text, x0, top)
}

/** A label vertically centred on [y]. */
fun DrawScope.labelMid(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, anchor: Int) =
    labelAt(m, text, x, y - m.measure(text, style).size.height / 2f, style, anchor)

/** A label whose bottom edge is the canvas bottom. */
fun DrawScope.labelBottom(m: TextMeasurer, text: String, x: Float, style: TextStyle, anchor: Int) =
    labelAt(m, text, x, ChartEdge.labelTop(size.height, m.measure(text, style).size.height.toFloat()), style, anchor)

private fun DrawScope.monthTicks(m: TextMeasurer, axis: TimeAxis, style: TextStyle) {
    val first = axis.window.from
    val months = ChronoUnit.MONTHS.between(first, axis.window.to).coerceAtLeast(1)
    val widest = m.measure("ott 25", style).size.width.toFloat()
    val step = maxOf(2, labelStride((axis.right - axis.left) / months, widest, 6.dp.toPx())).toLong()
    var k = 0L
    while (true) {
        val d = first.plusMonths(k)
        if (d.isAfter(axis.window.to)) break
        val anchor = when { k == 0L -> -1; first.plusMonths(k + step).isAfter(axis.window.to) -> 1; else -> 0 }
        labelBottom(m, tickLabel(YearMonth.from(d), year = k == 0L), axis.x(d), style, anchor)
        k += step
    }
}

/* ---------- Line on dated values ---------- */

/**
 * A line on dated values (Attività · 12 mesi, the pillar value line): y labels on a round scale, month ticks every two
 * months, one path per run with a dashed "n/d" slot where more than 35 days pass without a value, the last point labelled
 * ([endLabel]) and drawn open when [openEnd] (partial data). Fewer than two points: the dated point(s) only, never a
 * flat zero line; none: "n/d".
 */
@Composable
fun DatedLineChart(
    points: List<SeriesPoint>, window: Window, description: String, modifier: Modifier = Modifier,
    openEnd: Boolean = false, endLabel: String? = null, height: Dp = 150.dp, gutter: Dp = ChartGutter, showTicks: Boolean = true,
) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val enter = rememberChartEnter()
    val shown = points.filter { it.date in window }.sortedBy { it.date }
    val scale = if (shown.isEmpty()) null else niceScale(shown.minOf { it.cents }, shown.maxOf { it.cents })
    val range = rememberRetarget(listOf(scale?.lo?.toFloat(), scale?.hi?.toFloat()))
    val ys = rememberRetargetBy(shown.map { it.date to it.cents.toFloat() })
    val small = Type.tiny.copy(color = pal.ink2)
    val strong = Type.section.copy(color = pal.ink, fontWeight = FontWeight.ExtraBold)
    ChartCanvas(modifier.fillMaxWidth().height(height).graphicsLayer { alpha = enter.alpha.value }, description + coverageDescription(shown, window)) {
        if (shown.size == 1) {
            // Partial data (G-06/U-17): the one dated value and where the data starts; no axis, no lone dot on empty ticks.
            val only = shown.first()
            label(measurer, endLabel ?: Fmt.eur(only.cents), size.width / 2, size.height / 2 - 2.dp.toPx(), Type.secondaryFigure.copy(color = pal.ink), 0)
            label(measurer, "dati dal " + Fmt.day(only.date), size.width / 2, size.height / 2 + 18.dp.toPx(), small, 0)
            return@ChartCanvas
        }
        val smallH = measurer.measure("0", small).size.height.toFloat()
        val strongH = measurer.measure("0", strong).size.height.toFloat()
        val dot = 5.3.dp.toPx()
        // The end label owns the strip above the plot (never over the line); every edge touches the canvas (D-03).
        val top = if (endLabel != null) strongH + 6.dp.toPx() else ChartEdge.plotTop(smallH, dot)
        val bottom = if (showTicks) ChartEdge.axisBase(size.height, smallH, 6.dp.toPx()) else size.height - maxOf(smallH / 2f, dot)
        val axis = TimeAxis(window, gutter.toPx(), size.width)
        if (showTicks) monthTicks(measurer, axis, small)
        if (scale == null) { label(measurer, "n/d", size.width / 2, (top + bottom) / 2, strong.copy(color = pal.ink2), 0); return@ChartCanvas }
        val lo = range[0].shown; val hi = range[1].shown.coerceAtLeast(lo + 1f)
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        scale.ticks.forEach { t ->
            val ty = y(t.toFloat())
            drawLine(pal.cardLine, Offset(axis.left, ty), Offset(axis.right, ty), 1.dp.toPx())
            labelMid(measurer, axisLabel(t), 0f, ty, small, -1)
        }
        val runs = runsOf(shown)
        val gapStroke = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        runs.zipWithNext().forEach { (a, b) ->
            val x0 = axis.x(a.last().date) + 5.dp.toPx(); val x1 = axis.x(b.first().date) - 5.dp.toPx()
            if (x1 - x0 > 4.dp.toPx()) {
                val tl = Offset(x0, top); val sz = Size(x1 - x0, bottom - top); val cr = CornerRadius(8.dp.toPx())
                drawRoundRect(pal.inset, tl, sz, cr)
                drawRoundRect(pal.btnLine, tl, sz, cr, style = gapStroke)
                if (x1 - x0 > 26.dp.toPx()) labelMid(measurer, "n/d", (x0 + x1) / 2, (top + bottom) / 2, small.copy(fontWeight = FontWeight.SemiBold), 0)
            }
        }
        val reveal = axis.left - 6.dp.toPx() + (axis.right - axis.left + 12.dp.toPx()) * enter.trim.value
        clipRect(right = reveal) {
            runs.forEach { r ->
                if (r.size < 2) return@forEach
                val path = Path()
                r.forEachIndexed { i, p -> val x = axis.x(p.date); val yy = y(ys.getValue(p.date).value); if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy) }
                drawPath(path, pal.fill, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            shown.forEachIndexed { i, p ->
                val c = Offset(axis.x(p.date), y(ys.getValue(p.date).value))
                val last = i == shown.lastIndex
                val r = (if (last) 4.4.dp else 2.6.dp).toPx()
                drawCircle(if (last && !openEnd) pal.fill else pal.card, r, c)
                drawCircle(pal.fill, r, c, style = Stroke(1.8.dp.toPx()))
            }
        }
        if (endLabel != null && (labelPassActive || enter.trim.value > 0.9f)) {
            val lp = shown.last()
            labelAt(measurer, endLabel, size.width, 0f, strong, 1) // top strip, clear of the line and the gridlines
        }
    }
}

/* ---------- Monthly stacked bars ---------- */

/** A drawn rectangle in canvas px (the chart geometry the tests read without drawing). */
internal data class ChartRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun intersects(o: ChartRect): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
}

/** Where the one "n/d" note of a bar chart goes (polish 2, Q2): centred on its slot, kept inside the canvas, then lifted [gap] clear above every bar it would otherwise cover (a clamped marker at the right edge sits over the neighbouring bar). */
internal fun ndMarkerRect(cx: Float, w: Float, h: Float, canvasW: Float, bars: List<ChartRect>, gap: Float): ChartRect {
    val l = (cx - w / 2f).coerceIn(0f, (canvasW - w).coerceAtLeast(0f))
    val lowestTop = bars.filter { it.l < l + w && it.r > l }.minOfOrNull { it.t } ?: Float.MAX_VALUE
    val top = (lowestTop - gap - h).coerceAtLeast(0f)
    return ChartRect(l, top, l + w, top + h)
}

/** One month of a stacked bar chart: [parts] bottom to top in cents (each drawn in its colour), or a NONE / ND slot. */
data class BarSlot(val month: YearMonth, val parts: List<Long>, val state: BarState)

/**
 * Monthly bars on their own printed scale (0 and a round top, labelled at the left), stacked by [colors]. NONE (known
 * nothing) is a hollow 3 dp outline at the base; ND is a dashed slot marked "n/d", never a zero bar. With [window] the bars
 * sit on the same time axis as a [DatedLineChart] with the same [gutter]; otherwise they are evenly spaced.
 */
@Composable
fun MonthBarsChart(
    slots: List<BarSlot>, colors: List<Color>, description: String, modifier: Modifier = Modifier,
    window: Window? = null, height: Dp = 96.dp, gutter: Dp = ChartGutter,
) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val grow = rememberBarGrow(slots.size)
    val enter = rememberChartEnter()
    val top = slots.filter { it.state == BarState.DATA }.maxOfOrNull { s -> s.parts.fold(0L) { a, b -> a + b } } ?: 0L
    val scaleHi = niceCeil(top.coerceAtLeast(100L))
    val hiAnim = rememberRetarget(listOf(scaleHi.toFloat()))
    val parts = rememberRetarget(slots.flatMap { s -> s.parts.map { if (s.state == BarState.DATA) it.toFloat() else 0f } })
    val small = Type.tiny.copy(color = pal.ink2)
    val tiny = Type.tiny.copy(color = pal.ink2)
    ChartCanvas(modifier.fillMaxWidth().height(height).graphicsLayer { alpha = enter.alpha.value }, description) {
        val labelH = measurer.measure("0", tiny).size.height.toFloat()
        val plotTop = ChartEdge.plotTop(labelH); val base = ChartEdge.axisBase(size.height, labelH, 4.dp.toPx())
        val left = gutter.toPx(); val right = size.width
        val axis = window?.let { TimeAxis(it, left, right) }
        val slotW = (right - left) / slots.size.coerceAtLeast(1)
        // Neighbouring bars never touch: the bar width follows the closest pair of centres.
        fun rawCx(i: Int, m: YearMonth, bw: Float): Float = axis?.let { a ->
            val mid = m.atDay(minOf(15, m.lengthOfMonth()))
            a.x(minOf(maxOf(mid, a.window.from), a.window.to)).coerceIn(left + bw / 2, right - bw / 2)
        } ?: (left + slotW * (i + 0.5f))
        val centres0 = slots.mapIndexed { i, s -> rawCx(i, s.month, 0f) }
        val closest = centres0.zipWithNext { a, b -> b - a }.minOrNull() ?: slotW
        val barW = minOf(14.dp.toPx(), slotW * 0.62f, closest * 0.7f).coerceAtLeast(2.dp.toPx())
        fun cx(i: Int, m: YearMonth): Float = rawCx(i, m, barW)
        drawLine(pal.cardLine, Offset(left, base), Offset(right, base), 1.dp.toPx())
        labelMid(measurer, axisLabel(scaleHi), 0f, plotTop, small, -1)
        labelMid(measurer, "0", 0f, base, small, -1)
        val hi = hiAnim[0].value.coerceAtLeast(1f)
        val dash = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
        var k = 0
        val stride = labelStride(measurer, slots.map { Fmt.monthShort(it.month) }, closest, tiny)
        // One "n/d" note for all empty columns (the most recent), never a repeated "n/d" (N-03/U-20).
        val ndNote = slots.indexOfLast { it.state == BarState.ND }
        val barRects = slots.mapIndexed { i, s ->
            val x = cx(i, s.month)
            val h = when (s.state) {
                BarState.DATA -> s.parts.fold(0L) { a, b -> a + b } / scaleHi.toFloat() * (base - plotTop)
                BarState.ND -> (base - plotTop) * 0.45f
                BarState.NONE -> 3.dp.toPx()
            }
            ChartRect(x - barW / 2, base - h, x + barW / 2, base)
        }
        slots.forEachIndexed { i, s ->
            val x = cx(i, s.month)
            if ((slots.lastIndex - i) % stride == 0) labelBottom(measurer, Fmt.monthShort(s.month), x, tiny, 0)
            val g = grow.getOrNull(i)?.value ?: 1f
            when (s.state) {
                BarState.DATA -> {
                    var y = base
                    s.parts.forEachIndexed { j, _ ->
                        val h = (parts[k + j].value / hi * (base - plotTop)).coerceAtLeast(0f) * g
                        if (h > 0.5f) {
                            drawRoundRect(colors[j % colors.size], Offset(x - barW / 2, y - h), Size(barW, h), CornerRadius(2.dp.toPx()))
                            y -= h
                        }
                    }
                }
                BarState.NONE -> drawRoundRect(pal.fill, Offset(x - barW / 2, base - 3.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()), style = Stroke(1.2.dp.toPx()), alpha = g.coerceIn(0f, 1f))
                BarState.ND -> {
                    val h = (base - plotTop) * 0.45f
                    drawRoundRect(pal.ink2, Offset(x - barW / 2, base - h), Size(barW, h), CornerRadius(3.dp.toPx()), style = dash, alpha = 0.7f * g.coerceIn(0f, 1f))
                    if (i == ndNote) {
                        val nd = tiny.copy(fontWeight = FontWeight.SemiBold)
                        val sz = measurer.measure("n/d", nd).size
                        val box = ndMarkerRect(x, sz.width.toFloat(), sz.height.toFloat(), size.width, barRects, 3.dp.toPx())
                        labelAt(measurer, "n/d", box.l, box.t, nd, -1)
                    }
                }
            }
            k += s.parts.size
        }
    }
}
