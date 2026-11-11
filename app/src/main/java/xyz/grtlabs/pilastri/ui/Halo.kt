package xyz.grtlabs.pilastri.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where each selectable element of a pane sits, so one highlight can travel between them. */
@Stable
class HaloState {
    internal val bounds = mutableStateMapOf<Any, Rect>()
    internal var origin: LayoutCoordinates? = null
}

/** Registers this element as a possible selection target of the enclosing [HaloBox]. */
fun Modifier.haloTarget(state: HaloState, key: Any): Modifier = this.onGloballyPositioned { c ->
    val o = state.origin
    if (o != null && o.isAttached && c.isAttached) state.bounds[key] = o.localBoundingBoxOf(c, clipBounds = false)
}

/**
 * The selection highlight of a stable pane (AGENTS.md: "the selection highlight moves between items with a spring").
 * One rounded shape travels from the old item to the new one on the spatial spring and keeps its velocity if the
 * selection changes again mid-flight; layout changes (scrolling) follow without lag; it fades when nothing is selected.
 * The fill sits behind the items and the outline in front, so text stays fully legible.
 */
@Composable
fun HaloBox(selected: Any?, fill: Color, line: Color, modifier: Modifier = Modifier, radius: Dp = 20.dp, lineWidth: Dp = 2.dp, content: @Composable BoxScope.(HaloState) -> Unit) {
    val state = remember { HaloState() }
    val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val alpha = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    var shownKey by remember { mutableStateOf<Any?>(null) }
    val target = selected?.let { state.bounds[it] }
    LaunchedEffect(selected, target) {
        when {
            target == null -> { alpha.animateTo(0f, Motion.effects()); shownKey = null }
            shownKey == null || alpha.value < 0.02f -> { rect.snapTo(target); shownKey = selected; alpha.animateTo(1f, Motion.effects()) }
            // Reduced motion: the highlight never slides; it fades out where it was and in where it is now.
            shownKey != selected && reduce -> { alpha.animateTo(0f, Motion.effects()); rect.snapTo(target); shownKey = selected; alpha.animateTo(1f, Motion.effects()) }
            shownKey != selected -> { shownKey = selected; rect.animateTo(target, Motion.spatial()) }
            rect.isRunning -> rect.animateTo(target, Motion.spatial())
            else -> rect.snapTo(target)
        }
    }
    Box(
        modifier
            .onGloballyPositioned { state.origin = it }
            .drawBehind {
                if (alpha.value > 0.01f) {
                    val r = rect.value; val cr = CornerRadius(radius.toPx(), radius.toPx())
                    drawRoundRect(fill, r.topLeft, r.size, cr, alpha = alpha.value)
                }
            }
            .drawWithContent {
                drawContent()
                if (alpha.value > 0.01f) {
                    val r = rect.value; val w = lineWidth.toPx(); val cr = CornerRadius(radius.toPx() - w / 2, radius.toPx() - w / 2)
                    drawRoundRect(line, r.topLeft.plus(androidx.compose.ui.geometry.Offset(w / 2, w / 2)), androidx.compose.ui.geometry.Size(r.width - w, r.height - w), cr, alpha = alpha.value, style = Stroke(w))
                }
            },
    ) { content(state) }
}
