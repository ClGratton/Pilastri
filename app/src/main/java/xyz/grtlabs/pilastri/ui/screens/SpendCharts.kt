package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.charts.ChartCanvas
import xyz.grtlabs.pilastri.ui.charts.ChartEdge
import xyz.grtlabs.pilastri.ui.charts.drawChartText
import xyz.grtlabs.pilastri.ui.charts.labelPassActive
import xyz.grtlabs.pilastri.ui.charts.shown
import xyz.grtlabs.pilastri.ui.charts.axisLabel
import xyz.grtlabs.pilastri.ui.charts.labelAt
import xyz.grtlabs.pilastri.ui.charts.labelBottom
import xyz.grtlabs.pilastri.ui.charts.labelMid
import xyz.grtlabs.pilastri.ui.charts.labelStride
import xyz.grtlabs.pilastri.ui.charts.rememberBarGrow
import xyz.grtlabs.pilastri.ui.charts.rememberChartEnter
import xyz.grtlabs.pilastri.ui.charts.rememberRetarget
import kotlin.math.abs

/*
 * Spese's charts (boards 3a/3b; DESIGN G-06): "Ritmo di spesa", the category bar with its pace tick, the 6-window sparkline,
 * "Dove limare" and the 12-month Rate bars. Theme colours and Motion.kt tokens only; lines trim on chartLine, bars grow on
 * fill with the 20 ms stagger, updates retarget, reduced motion shows the final state with a 120 ms fade; each chart speaks.
 */

/** The overspend signal's hysteresis (Council 5 d): a UI cache, never the archive. Empty after a restart: plain 1.10. */
object SpendSignalCache {
    private val on = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun wasOn(key: String): Boolean? = on[key]
    /** Records the live window's states (window 0 only). */
    fun record(v: SpendWindowView) {
        if (v.window.k != 0) return
        on[SpendSignal.TOTAL_KEY] = v.over
        v.categories.forEach { c -> c.id?.let { on[it] = c.over } }
    }
    fun recordTotal(over: Boolean) { on[SpendSignal.TOTAL_KEY] = over }
}

/** The Spese dot (3b-03, G-04 attention place 2): on while the live signal holds; it clears by itself. */
@Composable
fun rememberSpendDot(app: App): Boolean {
    val a = app.archive ?: return false
    val today = app.today
    val on = remember(a, today) { runCatching { a.spendSignalNow(today, SpendSignalCache::wasOn) }.getOrDefault(false) }
    SideEffect { SpendSignalCache.recordTotal(on) }
    return on
}

private fun DrawScope.text(m: TextMeasurer, s: String, x: Float, baselineY: Float, style: TextStyle, anchor: Int) {
    val r = m.measure(s, style)
    val left = when (anchor) { -1 -> x; 1 -> x - r.size.width; else -> x - r.size.width / 2f }
    val x0 = left.coerceIn(0f, (size.width - r.size.width).coerceAtLeast(0f)); val y0 = baselineY - r.firstBaseline
    drawChartText(r, s, x0, y0)
}

/** A round top for a scale in cents: 1, 2, 2.5 or 5 × 10^k euro. */
internal fun niceTop(cents: Long): Long {
    val e = (cents.coerceAtLeast(100L)) / 100.0
    var step = 1.0
    while (step * 10 <= e) step *= 10
    val m = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * step >= e }
    return (m * step * 100).toLong()
}

/**
 * "Ritmo di spesa" (3a-03, 3b-01): x = the window's N days; the cumulative spend from the first covered day (earlier days a gap)
 * against the dashed budget pace from 0; the end dot with the total (open when the data stops before today). Over: the area
 * above the pace is shaded and the line and its total turn red.
 */
@Composable
internal fun PaceChart(v: SpendWindowView, description: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val enter = rememberChartEnter()
    val w = v.window
    val n = w.days
    val first = w.coveredFrom?.let { w.dayOf(it) }
    val paceEnd = v.paceCents
    val hi = niceTop(maxOf(v.pace.filterNotNull().maxOrNull() ?: 0L, paceEnd ?: 0L))
    val anim = rememberRetarget(v.pace.map { it?.toFloat() } + listOf(hi.toFloat(), (paceEnd ?: 0L).toFloat()))
    val small = Type.tiny.copy(color = pal.ink2)
    val strong = Type.smallStrong.copy(color = if (v.over) pal.overInk else pal.ink)
    val lineColor = if (v.over) pal.over else pal.fill
    ChartCanvas(modifier.fillMaxWidth().height(150.dp).graphicsLayer { alpha = enter.alpha.value }, description) {
        // D-03: the canvas is the drawn extent: date row ends at the bottom, the highest label starts at the top, the line starts at
        // x = 0 and its end dot ends at the canvas end.
        val smallH = measurer.measure("0", small).size.height.toFloat()
        val left = 0f; val right = size.width - 5.3.dp.toPx()
        val plotTop = smallH + 4.dp.toPx(); val base = ChartEdge.axisBase(size.height, smallH, 6.dp.toPx())
        val maxV = anim[n].shown.coerceAtLeast(1f)
        fun x(day: Float) = left + day / n * (right - left)
        fun y(c: Float) = base - c / maxV * (base - plotTop)
        drawLine(pal.cardLine, Offset(left, base), Offset(size.width, base), 1.dp.toPx())
        // K8: the period's range is the page's PeriodTitle; the axis carries only the day numbers at its ends.
        labelBottom(measurer, w.shownFrom.dayOfMonth.toString(), 0f, small, -1)
        labelBottom(measurer, w.to.dayOfMonth.toString(), size.width, small, 1)
        if (first == null) { text(measurer, "n/d", size.width / 2, (plotTop + base) / 2, strong.copy(color = pal.ink2), 0); return@ChartCanvas }
        val x0 = x(first - 1f)
        if (first > 1) {
            drawRoundRect(pal.inset, Offset(left, plotTop), Size(x0 - left, base - plotTop), CornerRadius(6.dp.toPx()))
            if (x0 - left > 30.dp.toPx()) labelMid(measurer, "n/d", (left + x0) / 2, (plotTop + base) / 2, small, 0)
        }
        val pe = anim[n + 1].shown
        if (paceEnd != null) {
            drawLine(pal.ink2, Offset(x0, y(0f)), Offset(right, y(pe)), 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            // CR-70: the budget amount (and its "stima" marker) is already in the card's caption right above ("73% di 1.700 € · stima"): the line only names itself.
            val label = if (w.full) "budget" else "ritmo " + Fmt.eur(paceEnd)
            labelAt(measurer, label, size.width, (y(pe) - 4.dp.toPx() - smallH).coerceAtLeast(0f), small, 1)
        }
        val path = Path().apply { moveTo(x0, y(0f)); for (d in first..n) lineTo(x(d.toFloat()), y(anim[d - 1].value)) }
        val reveal = x0 + (right - x0 + 8.dp.toPx()) * enter.trim.value
        clipRect(right = reveal) {
            if (v.over && paceEnd != null) {
                val above = Path().apply { moveTo(x0, y(0f)); lineTo(right, y(pe)); lineTo(right, 0f); lineTo(x0, 0f); close() }
                val area = Path().apply { moveTo(x0, y(0f)); for (d in first..n) lineTo(x(d.toFloat()), y(anim[d - 1].value)); lineTo(right, y(pe)); close() }
                clipPath(above) { drawPath(area, pal.over.copy(alpha = 0.16f)) }
            }
            drawPath(path, lineColor, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if ((labelPassActive || enter.trim.value > 0.9f)) {
            val c = Offset(x(n.toFloat()), y(anim[n - 1].shown))
            drawCircle(if (v.stale) pal.card else lineColor, 4.4.dp.toPx(), c)
            drawCircle(lineColor, 4.4.dp.toPx(), c, style = Stroke(1.8.dp.toPx()))
            // CR-70/K8: no end value here; the primary figure "Spesa" owns the spent total.
        }
    }
}

/** A category's bar: spent over budget, the tick at the budget's pace (c/N); no budget: an empty outline without tick. */
@Composable
internal fun PaceBar(fraction: Float?, tick: Float?, over: Boolean, grow: Float, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val f by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), Motion.fill(), label = "paceBar")
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(pal.track, cornerRadius = r)
        if (fraction == null) drawRoundRect(pal.btnLine, cornerRadius = r, style = Stroke(1.dp.toPx()))
        else if (f * grow > 0.005f) drawRoundRect(if (over) pal.over else pal.fill, size = Size(size.width * f * grow, size.height), cornerRadius = r)
        tick?.let { val tx = size.width * it.coerceIn(0f, 1f); drawLine(pal.ink, Offset(tx, -2.dp.toPx()), Offset(tx, size.height + 2.dp.toPx()), 2.dp.toPx()) }
    }
}

/** The 6-window sparkline (3a-05): fully covered windows only, a gap never drawn as 0; red when the category is over. */
@Composable
internal fun Sparkline(values: List<Long?>, over: Boolean, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val enter = rememberChartEnter()
    val color = if (over) pal.over else pal.fill
    Canvas(modifier.size(48.dp, 20.dp).graphicsLayer { alpha = enter.alpha.value }.clearAndSetSemantics {
        val known = values.filterNotNull()
        contentDescription = if (known.isEmpty()) "Andamento mensile, n/d" else "Andamento di ${values.size} mesi, ${known.size} con dati, ultimo ${Fmt.eur(known.last())}"
    }) {
        val pts = values.withIndex().filter { it.value != null }
        if (pts.isEmpty()) return@Canvas
        val maxV = pts.maxOf { it.value!! }.coerceAtLeast(1L).toFloat()
        fun x(i: Int) = 2.dp.toPx() + i / (values.size - 1f).coerceAtLeast(1f) * (size.width - 4.dp.toPx())
        fun y(c: Long) = size.height - 2.dp.toPx() - c / maxV * (size.height - 4.dp.toPx())
        clipRect(right = size.width * enter.trim.value + 3.dp.toPx()) {
            pts.zipWithNext().forEach { (a, b) -> if (b.index == a.index + 1) drawLine(color, Offset(x(a.index), y(a.value!!)), Offset(x(b.index), y(b.value!!)), 1.6.dp.toPx(), cap = StrokeCap.Round) }
            pts.forEachIndexed { i, p -> drawCircle(color, (if (i == pts.lastIndex) 2.2.dp else 1.3.dp).toPx(), Offset(x(p.index), y(p.value!!))) }
        }
    }
}

/** "Dove limare" (3b-02): the amount above its own pace per category, on a printed scale from 0. */
@Composable
internal fun TrimBars(lines: List<SpendCategoryLine>, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val grow = rememberBarGrow(lines.size)
    val top = niceTop(lines.maxOfOrNull { it.overPaceCents } ?: 0L)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) { contentDescription = "${c.name}, ${Fmt.eur(c.overPaceCents)} sopra il ritmo" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = Type.small, color = pal.ink, maxLines = 1, modifier = Modifier.width(96.dp))
                val g = grow.getOrNull(i)?.value ?: 1f
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val wd = size.width * (c.overPaceCents.toFloat() / top).coerceIn(0f, 1f) * g
                    drawRoundRect(pal.track, cornerRadius = CornerRadius(size.height / 2))
                    if (wd > 0.5f) drawRoundRect(pal.over, size = Size(wd, size.height), cornerRadius = CornerRadius(size.height / 2))
                }
                Spacer(Modifier.width(8.dp))
                Text("+" + Fmt.eur(c.overPaceCents), style = Type.amountSmall, color = pal.overInk, maxLines = 1, modifier = Modifier.testTag(UiTag.OVER))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 96.dp)) {
            Text("0", style = Type.tiny, color = pal.ink2, modifier = Modifier.weight(1f))
            Text(axisLabel(top) + Fmt.EUR_SUFFIX, style = Type.tiny, color = pal.ink2)
        }
    }
}

/** Hues for Rate's plans (3a-07/08): four plus "altri"; theme colours only. */
@Composable
internal fun rateHues(): List<androidx.compose.ui.graphics.Color> {
    val pal = LocalPal.current
    return listOf(pal.fill, pal.good, pal.ink2, pal.ribbonIn, pal.btnLine)
}

/**
 * Rate (3a-07): 12 calendar months of parts stacked by plan; plans still to confirm are a dashed outline on top; nothing is drawn
 * after the last known part; a printed scale from 0. Calendar months on purpose (Council 5 f).
 */
@Composable
internal fun RateBars(r: RateOutlook, description: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val hues = rateHues()
    val measurer = rememberTextMeasurer()
    val enter = rememberChartEnter()
    val grow = rememberBarGrow(r.months.size)
    val top = niceTop(r.months.maxOfOrNull { it.confirmed.values.sum() + it.pending.values.sum() } ?: 0L)
    val hiAnim = rememberRetarget(listOf(top.toFloat()))
    val tiny = Type.tiny.copy(color = pal.ink2)
    val last = r.lastKnown
    ChartCanvas(modifier.fillMaxWidth().height(120.dp).graphicsLayer { alpha = enter.alpha.value }, description) {
        val labelH = measurer.measure("0", tiny).size.height.toFloat()
        val left = measurer.measure(axisLabel(top), tiny).size.width + 6.dp.toPx(); val right = size.width // D-03: edge to edge
        val plotTop = ChartEdge.plotTop(labelH); val base = ChartEdge.axisBase(size.height, labelH, 4.dp.toPx())
        val slot = (right - left) / r.months.size
        val barW = minOf(16.dp.toPx(), slot * 0.62f)
        val hi = hiAnim[0].value.coerceAtLeast(1f)
        drawLine(pal.cardLine, Offset(left, base), Offset(right, base), 1.dp.toPx())
        labelMid(measurer, axisLabel(top), 0f, plotTop, tiny, -1)
        labelMid(measurer, "0", 0f, base, tiny, -1)
        val dash = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
        val stride = labelStride(measurer, r.months.map { Fmt.monthShort(it.month) }, slot, tiny)
        r.months.forEachIndexed { i, m ->
            val cx = left + slot * (i + 0.5f)
            if ((r.months.lastIndex - i) % stride == 0) labelBottom(measurer, Fmt.monthShort(m.month), cx, tiny, 0)
            if (i > last) return@forEachIndexed
            val g = grow.getOrNull(i)?.value ?: 1f
            var yTop = base
            fun seg(cents: Long, hue: Int, pending: Boolean) {
                val h = cents / hi * (base - plotTop) * g
                if (h < 0.5f) return
                val tl = Offset(cx - barW / 2, yTop - h)
                if (pending) drawRoundRect(hues[hue], tl, Size(barW, h), CornerRadius(2.dp.toPx()), style = dash)
                else drawRoundRect(hues[hue], tl, Size(barW, h), CornerRadius(2.dp.toPx()))
                yTop -= h
            }
            m.confirmed.entries.sortedBy { r.hueOf(it.key) }.forEach { seg(it.value, r.hueOf(it.key), false) }
            m.pending.entries.sortedBy { r.hueOf(it.key) }.forEach { seg(it.value, r.hueOf(it.key), true) }
        }
    }
}

/**
 * Swipe to decide (lazy-UX #2, #9): the row follows the finger 1:1; past [Motion.DismissFraction] of its width (or a fling) it
 * leaves on the spatial spring and [onRight] / [onLeft] runs, with the threshold haptic when the reveal arms; otherwise it springs
 * back. The same decisions stay as buttons in the row and as TalkBack actions. Reduced motion: no travel on release.
 */
@Composable
internal fun SwipeDecide(
    rightLabel: String?, onRight: (() -> Unit)?, leftLabel: String?, onLeft: (() -> Unit)?, modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pal = LocalPal.current
    val ch = xyz.grtlabs.pilastri.ui.rememberCommitHaptics(Motion.DismissFraction)
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    var armed by remember { mutableIntStateOf(0) }
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    Box(modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }.semantics {
        customActions = listOfNotNull(
            if (rightLabel != null && onRight != null) CustomAccessibilityAction(rightLabel) { onRight(); true } else null,
            if (leftLabel != null && onLeft != null) CustomAccessibilityAction(leftLabel) { onLeft(); true } else null,
        )
    }) {
        val shown = offset.value
        if (abs(shown) > 1f) Row(
            Modifier.matchParentSize().clip(CardShape).background(if (shown > 0) pal.goodBg else pal.inset).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (shown > 0) Arrangement.Start else Arrangement.End,
        ) {
            val label = if (shown > 0) rightLabel else leftLabel
            val ink = if (shown > 0) pal.goodInk else pal.ink2
            Icon(if (shown > 0) Ic.check else Ic.close, null, Modifier.size(18.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
            Text(label.orEmpty(), style = if (armed != 0) Type.smallStrong else Type.small, color = ink)
        }
        Box(Modifier.graphicsLayer { translationX = offset.value }.pointerInput(Unit) {
            val tracker = VelocityTracker()
            val fling = Motion.SwipeFlingDps.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); armed = 0; ch.start() },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x
                    val th = width * Motion.DismissFraction
                    val dir = when {
                        right != null && (offset.value > th || (v > fling && offset.value > 0)) -> 1
                        left != null && (offset.value < -th || (v < -fling && offset.value < 0)) -> -1
                        else -> 0
                    }
                    ch.release(committed = dir != 0)
                    scope.launch {
                        if (dir == 0) { if (reduce) offset.snapTo(0f) else offset.animateTo(0f, Motion.spatial(), v); return@launch }
                        if (reduce) offset.snapTo(dir * width * 1.1f) else offset.animateTo(dir * width * 1.1f, Motion.spatial(), v)
                        (if (dir > 0) right else left)?.invoke()
                        // The row normally leaves the list; if the decision was refused it comes back.
                        delay(Motion.SwipeReturnMs)
                        offset.animateTo(0f, Motion.spatial())
                    }
                },
                onDragCancel = { ch.cancel(); scope.launch { offset.animateTo(0f, Motion.spatial()) } },
            ) { change, dx ->
                tracker.addPosition(change.uptimeMillis, change.position)
                val raw = offset.value + dx
                // No decision on a side: a short rubber band.
                val next = when { raw > 0 && right == null -> raw.coerceAtMost(width * Motion.SwipeRubber); raw < 0 && left == null -> raw.coerceAtLeast(-width * Motion.SwipeRubber); else -> raw }
                change.consume()
                scope.launch { offset.snapTo(next) }
                armed = if (abs(next) > width * Motion.DismissFraction) (if (next > 0) 1 else -1) else 0
                ch.progress(abs(next) / width)
            }
        }) { content() }
    }
}
