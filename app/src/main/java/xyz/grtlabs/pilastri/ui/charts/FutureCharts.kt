package xyz.grtlabs.pilastri.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import xyz.grtlabs.pilastri.ui.UiTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*

/*
 * Chart primitives of Futuro (board 4a): the scenario fan, the money put in per year and the Lungo termine composition bar.
 * Same rules as Charts.kt: theme colours and Motion tokens only, missing is a dashed "n/d" slot never zero, every chart has a
 * content description, charts enter once per appearance and retarget on a data change, reduced motion shows the final state.
 */

private fun DrawScope.text(m: TextMeasurer, text: String, x: Float, baselineY: Float, style: TextStyle, anchor: Int) {
    val r = m.measure(text, style)
    val left = when (anchor) { -1 -> x; 1 -> x - r.size.width; else -> x - r.size.width / 2f }
    val x0 = left.coerceIn(0f, (size.width - r.size.width).coerceAtLeast(0f)); val y0 = baselineY - r.firstBaseline
    drawChartText(r, text, x0, y0)
}

/* ---------- Retargeting a whole set of values ---------- */

private class Morph(var from: FloatArray, var to: FloatArray)

/**
 * A float array that follows [target]: when it changes (a sync moved the start value) the shown values move from where they
 * were on [Motion.chartUpdate], velocity kept by restarting from the shown state. A different shape snaps. Reduced motion snaps.
 */
@Composable
private fun rememberMorph(target: FloatArray): () -> FloatArray {
    val reduce = LocalReduceMotion.current
    val m = remember { Morph(target, target) }
    val p = remember { Animatable(1f) }
    val key = target.contentHashCode()
    LaunchedEffect(key, target.size) {
        if (m.to.contentEquals(target)) return@LaunchedEffect
        val now = if (m.from.size == m.to.size) FloatArray(m.to.size) { i -> m.from[i] + (m.to[i] - m.from[i]) * p.value } else target
        m.from = if (now.size == target.size) now else target
        m.to = target
        if (reduce || m.from.contentEquals(target)) p.snapTo(1f) else { p.snapTo(0f); p.animateTo(1f, Motion.chartUpdate()) }
    }
    return { if (labelPassActive) target else { val f = p.value; FloatArray(m.to.size) { i -> m.from[i] + (m.to[i] - m.from[i]) * f } } }
}

/* ---------- The fan ---------- */

private fun Path.band(xs: (Int) -> Float, ys: (Float) -> Float, hi: FloatArray, lo: FloatArray, n: Int) {
    moveTo(xs(0), ys(hi[0]))
    for (i in 1 until n) lineTo(xs(i), ys(hi[i]))
    for (i in n - 1 downTo 0) lineTo(xs(i), ys(lo[i]))
    close()
}

/**
 * A scenario fan on a year axis (4a-10): the start dot, the median line, the near and far bands and the labelled end values.
 * Index i of every list is the end of year [startYear] + i; [near] and [far] are (high, low) pairs and may be absent (the
 * calculator draws the median alone). The line trims in once; a data change moves the curves. Values are in the page's basis.
 */
@Composable
fun FanChart(
    startYear: Int, median: List<Long>, near: Pair<List<Long>, List<Long>>?, far: Pair<List<Long>, List<Long>>?,
    description: String, modifier: Modifier = Modifier, height: Dp = 188.dp, gutter: Dp = ChartGutter,
) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val enter = rememberChartEnter()
    val n = median.size
    val all = buildList { add(median); near?.let { add(it.first); add(it.second) }; far?.let { add(it.first); add(it.second) } }
    val target = remember(median, near, far) { FloatArray(all.size * n) { k -> all[k / n][k % n].toFloat() } }
    val shown = rememberMorph(target)
    val farIdx = if (near != null) 3 else 1
    val scaleTarget = remember(all) { val flat = all.flatten(); if (flat.isEmpty()) null else niceScale(0L, flat.max()) }
    val range = rememberRetarget(listOf(scaleTarget?.lo?.toFloat(), scaleTarget?.hi?.toFloat()))
    val small = Type.tiny.copy(color = pal.ink2, fontWeight = FontWeight.Medium)
    val strong = Type.smallStrong.copy(color = pal.ink, fontWeight = FontWeight.ExtraBold)
    ChartCanvas(modifier.fillMaxWidth().height(height).graphicsLayer { alpha = enter.alpha.value }, description) {
        // D-03: every edge of the canvas is drawn on: y labels start at x = 0, the end labels end at the canvas end, the top
        // label starts at the top and the year row ends at the bottom.
        val smallH = measurer.measure("0", small).size.height.toFloat(); val strongH = measurer.measure("0", strong).size.height.toFloat()
        val top = ChartEdge.plotTop(maxOf(smallH, strongH)); val bottom = ChartEdge.axisBase(size.height, smallH, 6.dp.toPx()) - 6.dp.toPx()
        val left = gutter.toPx()
        val endW = (scaleTarget?.hi?.let { measurer.measure(axisLabel(it.toLong()), strong).size.width.toFloat() } ?: 0f)
        val right = size.width - endW - 6.dp.toPx()
        if (n == 0 || scaleTarget == null) { text(measurer, "n/d", size.width / 2, (top + bottom) / 2, strong.copy(color = pal.ink2), 0); return@ChartCanvas }
        val lo = range[0].shown; val hi = range[1].shown.coerceAtLeast(lo + 1f)
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        fun x(i: Int) = if (n <= 1) left else left + i.toFloat() / (n - 1) * (right - left)
        scaleTarget.ticks.forEach { t ->
            val ty = y(t.toFloat())
            drawLine(pal.cardLine, Offset(left, ty), Offset(size.width, ty), 1.dp.toPx())
            labelMid(measurer, axisLabel(t), 0f, ty, small, -1)
        }
        // Year axis: round steps, at most about five intervals, the last year labelled when it is not too close to a tick.
        val span = n - 1
        val step = listOf(1, 2, 5, 10, 20, 25).firstOrNull { span / it <= 5 } ?: 25
        var last = -1
        for (i in 0..span step step) { labelBottom(measurer, (startYear + i).toString(), x(i), small, if (i == 0) -1 else 0); last = i }
        if (span - last >= (step + 1) / 2 && span > 0) labelBottom(measurer, (startYear + span).toString(), x(span), small, 1)
        val cur = shown()
        fun series(k: Int) = cur.copyOfRange(k * n, (k + 1) * n)
        val reveal = left - 6.dp.toPx() + (size.width - left) * enter.trim.value
        clipRect(right = reveal) {
            val nearS = if (near != null) series(1) to series(2) else null
            val farS = if (far != null) series(farIdx) to series(farIdx + 1) else null
            val m = series(0)
            if (n > 1) {
                farS?.let { (h, l) -> drawPath(Path().apply { band(::x, ::y, h, l, n) }, pal.fill, alpha = 0.12f) }
                nearS?.let { (h, l) -> drawPath(Path().apply { band(::x, ::y, h, l, n) }, pal.fill, alpha = 0.22f) }
                val path = Path().apply { moveTo(x(0), y(m[0])); for (i in 1 until n) lineTo(x(i), y(m[i])) }
                drawPath(path, pal.fill, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val c = Offset(x(0), y(m[0]))
            drawCircle(pal.card, 5.2.dp.toPx(), c)
            drawCircle(pal.fill, 5.2.dp.toPx(), c, style = Stroke(2.2.dp.toPx()))
            drawCircle(pal.fill, 2.4.dp.toPx(), c)
        }
        if ((labelPassActive || enter.trim.value > 0.9f) && n > 1) {
            val cur2 = shown()
            val mEnd = cur2[n - 1]
            val ex = size.width
            labelMid(measurer, axisLabel(mEnd.toLong()), ex, y(mEnd), strong, 1)
            if (far != null) {
                val hiV = cur2[farIdx * n + n - 1]; val loV = cur2[(farIdx + 1) * n + n - 1]
                if (y(loV) - y(hiV) > 40.dp.toPx()) {
                    labelMid(measurer, axisLabel(hiV.toLong()), ex, y(hiV), small, 1)
                    labelMid(measurer, axisLabel(loV.toLong()), ex, y(loV), small, 1)
                }
            }
        }
    }
}

/* ---------- Money put in per year (4a-09) ---------- */

/**
 * A bar per year on its own printed scale (0 and a round top at the left). The current year is drawn dashed and labelled with
 * its month ("2026 · ott": to date, not a complete year). NONE is a hollow outline at the base; n/d a dashed "n/d" slot.
 */
@Composable
fun YearBarsChart(bars: List<YearBar>, toDateMonth: String, description: String, modifier: Modifier = Modifier, height: Dp = 108.dp, gutter: Dp = ChartGutter) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val grow = rememberBarGrow(bars.size)
    val enter = rememberChartEnter()
    val top = bars.filter { it.state == BarState.DATA }.maxOfOrNull { it.cents ?: 0L } ?: 0L
    val scaleHi = niceCeil(top.coerceAtLeast(100L))
    val hiAnim = rememberRetarget(listOf(scaleHi.toFloat()))
    val values = rememberRetarget(bars.map { if (it.state == BarState.DATA) it.cents?.toFloat() else 0f })
    val small = Type.tiny.copy(color = pal.ink2, fontWeight = FontWeight.Medium)
    val tiny = Type.tiny.copy(color = pal.ink2, fontWeight = FontWeight.Medium)
    ChartCanvas(modifier.fillMaxWidth().height(height).graphicsLayer { alpha = enter.alpha.value }, description) {
        val labelH = measurer.measure("0", tiny).size.height.toFloat()
        val plotTop = ChartEdge.plotTop(labelH); val base = ChartEdge.axisBase(size.height, labelH, 4.dp.toPx())
        val left = gutter.toPx(); val right = size.width
        val slotW = (right - left) / bars.size.coerceAtLeast(1)
        val barW = minOf(22.dp.toPx(), slotW * 0.62f)
        val stride = labelStride(measurer, bars.map { if (it.toDate) "${it.year} · $toDateMonth" else it.year.toString() }, slotW, tiny)
        drawLine(pal.cardLine, Offset(left, base), Offset(right, base), 1.dp.toPx())
        labelMid(measurer, axisLabel(scaleHi), 0f, plotTop, small, -1)
        labelMid(measurer, "0", 0f, base, small, -1)
        val ndNote = bars.indexOfLast { it.state == BarState.ND } // one "n/d" note, not one per empty column
        val hi = hiAnim[0].value.coerceAtLeast(1f)
        val dash = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
        bars.forEachIndexed { i, b ->
            val cx = left + slotW * (i + 0.5f)
            if ((bars.lastIndex - i) % stride == 0) labelBottom(measurer, if (b.toDate) "${b.year} · $toDateMonth" else b.year.toString(), cx, tiny, if (b.toDate) 1 else 0)
            val g = (grow.getOrNull(i)?.value ?: 1f).coerceIn(0f, 1.2f)
            when (b.state) {
                BarState.DATA -> {
                    val h = (values[i].value / hi * (base - plotTop)).coerceAtLeast(0f) * g
                    if (h > 0.5f) {
                        val tl = Offset(cx - barW / 2, base - h); val sz = Size(barW, h); val cr = CornerRadius(3.dp.toPx())
                        if (b.toDate) { drawRoundRect(pal.fill, tl, sz, cr, alpha = 0.22f); drawRoundRect(pal.fill, tl, sz, cr, style = dash) }
                        else drawRoundRect(pal.fill, tl, sz, cr)
                    }
                }
                BarState.NONE -> drawRoundRect(pal.fill, Offset(cx - barW / 2, base - 3.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()), style = Stroke(1.2.dp.toPx()), alpha = g.coerceIn(0f, 1f))
                BarState.ND -> {
                    val h = (base - plotTop) * 0.45f
                    drawRoundRect(pal.ink2, Offset(cx - barW / 2, base - h), Size(barW, h), CornerRadius(3.dp.toPx()), style = dash, alpha = 0.7f * g.coerceIn(0f, 1f))
                    if (i == ndNote) labelAt(measurer, "n/d", cx, base - h - 3.dp.toPx() - labelH, tiny.copy(fontWeight = FontWeight.SemiBold), 0)
                }
            }
        }
    }
}

/* ---------- Lungo termine composition (4a-08) ---------- */

/** One source of the composition: [cents] in the page's basis (null = n/d), [stale] → the "datato" tag. */
data class SourceShare(val name: String, val cents: Long?, val date: LocalDate?, val stale: Boolean)

/**
 * One stacked bar with a segment per source, widths by value; a source with no value is a dashed "n/d" slot of fixed width, never
 * a zero segment. Below it, one row per source: swatch, name, "datato <dd mmm>" when stale, the value. The bar grows in once and
 * its segments follow a data change on the chart-update spring.
 */
@Composable
fun SourcesBar(items: List<SourceShare>, description: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val colors = listOf(pal.fill, pal.ribbonIn, pal.nodeDark, pal.ribbonOut)
    val enterG = rememberBarGrow(1)[0]
    val total = items.sumOf { it.cents ?: 0L }.coerceAtLeast(1L)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(16.dp).graphicsLayer { scaleX = enterG.value.coerceIn(0f, 1f); transformOrigin = TransformOrigin(0f, 0.5f) }
                .clearAndSetSemantics { testTag = UiTag.CHART; contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEachIndexed { i, s ->
                val raw = if (s.cents == null) 0.12f else (s.cents.toFloat() / total).coerceAtLeast(0.02f)
                val w by animateFloatAsState(raw, Motion.chartUpdate(), label = "share$i")
                if (s.cents == null) Box(Modifier.weight(w.coerceAtLeast(0.001f)).fillMaxHeight().dashedSlot(pal.ink2))
                else Box(Modifier.weight(w.coerceAtLeast(0.001f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(colors[i % colors.size]))
            }
        }
        items.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                if (s.cents == null) Box(Modifier.size(9.dp).dashedSlot(pal.ink2)) else Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(colors[i % colors.size]))
                Spacer(Modifier.width(8.dp))
                // D-10: name + chip share the weighted name column (the name ellipsizes, the chip stays); the value has a fixed end-aligned column.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = Type.rowTitle, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (s.stale && s.date != null) { Spacer(Modifier.width(6.dp)); Tag("datato") /* CR-70/K8: the freshness line owns the oldest stale date */ }
                }
                Text(s.cents?.let { Fmt.eur(it) } ?: "n/d", style = Type.amount, color = if (s.cents == null) pal.ink2 else pal.ink, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(88.dp))
            }
        }
    }
}

private fun Modifier.dashedSlot(color: Color): Modifier = this.drawBehind {
    val sw = 1.2.dp.toPx()
    drawRoundRect(color.copy(alpha = 0.7f), Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(4.dp.toPx()),
        style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
}
