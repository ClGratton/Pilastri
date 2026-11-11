package xyz.grtlabs.pilastri.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import xyz.grtlabs.pilastri.ui.UiTag

/** One text a chart draws on its canvas: the string and the rect it occupies, in canvas pixels. */
class DrawnLabel(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Canvas text is invisible to accessibility, to uiautomator and to the JVM semantics tree. A chart's labels are therefore derived
 * from its data and layout size in a label pass that runs OUTSIDE the draw phase ([collectLabels], in composition, once per
 * data or size change) by running the chart's own draw lambda with [collecting] set: the text helpers then record instead of
 * painting, and animated values read their target ([shown]) so semantics expose the settled text and rect. The live draw never
 * writes state: `collecting` is null there and [drawChartText] only paints.
 */
object ChartLabels {
    /** Non-null only while [collectLabels] runs (plain list, not snapshot state). */
    internal var collecting: ArrayList<DrawnLabel>? = null

    /** True during the label pass: animated values answer their target. */
    val settledPass: Boolean get() = collecting != null

    /** The last failure of a label pass (a pass never throws into composition; the chart then simply exposes no labels). */
    var lastError: Throwable? = null
        internal set
}

/** The value a draw lambda uses: the animated value while painting, the target during the label pass (settled labels). */
val Animatable<Float, AnimationVector1D>.shown: Float get() = if (ChartLabels.settledPass) targetValue else value

/** True while a chart's label pass runs (helpers outside ChartCanvas.kt use this instead of naming [ChartLabels]). */
val labelPassActive: Boolean get() = ChartLabels.settledPass

/** Paints [r] at ([x], [y]); during the label pass it records the label instead of painting. */
fun DrawScope.drawChartText(r: TextLayoutResult, text: String, x: Float, y: Float) {
    val sink = ChartLabels.collecting
    if (sink != null) sink.add(DrawnLabel(text, x, y, r.size.width.toFloat(), r.size.height.toFloat()))
    else drawText(r, topLeft = Offset(x, y))
}

/** Runs [onDraw] as a label pass at [size] and returns the labels it would draw (settled values). Never touches snapshot state. */
fun collectLabels(density: Density, size: IntSize, onDraw: DrawScope.() -> Unit): List<DrawnLabel> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val out = ArrayList<DrawnLabel>()
    val prev = ChartLabels.collecting
    ChartLabels.collecting = out
    try {
        Snapshot.withoutReadObservation {
            // A canvas with no bitmap behind it: every draw call is a no-op, the text helpers record instead of painting.
            CanvasHolder().drawInto(android.graphics.Canvas()) {
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, this, Size(size.width.toFloat(), size.height.toFloat())) { onDraw() }
            }
        }
    } catch (t: Throwable) {
        ChartLabels.lastError = t
        out.clear()
    } finally { ChartLabels.collecting = prev }
    return out
}

/**
 * A chart canvas whose drawn texts are also exposed as semantics: the box carries the chart tag and summary description, and
 * each label the chart helpers draw appears as its own invisible text node with the text and rect of the settled chart. The
 * nodes come from the label pass ([collectLabels]) and change only with the data, the size or the density, never per frame.
 * Pixels are unchanged; the nodes draw nothing and take no input.
 */
@Composable
fun ChartCanvas(modifier: Modifier, description: String, onDraw: DrawScope.() -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val labels = remember(onDraw, size, density) { collectLabels(density, size, onDraw) }
    Box(modifier.semantics { testTag = UiTag.CHART; contentDescription = description }.onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()) { onDraw() }
        ChartLabelNodes(labels)
    }
}

/** The invisible text nodes for [labels] (also used by charts that own their canvas, e.g. the Flusso). */
@Composable
fun ChartLabelNodes(labels: List<DrawnLabel>) {
    val density = LocalDensity.current
    labels.forEach { l ->
        Box(
            Modifier
                .offset { IntOffset(l.x.roundToInt(), l.y.roundToInt()) }
                .size(with(density) { l.w.toDp() }, with(density) { l.h.toDp() })
                .semantics { text = AnnotatedString(l.text) },
        )
    }
}
