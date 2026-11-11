package xyz.grtlabs.pilastri.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Where the card grows from, as fractions of the card: written while positioning, read in draw. */
private class Origin { @Volatile var x = 0.5f; @Volatile var y = 0f }

/**
 * Below the anchor when it fits, otherwise above; clamped inside [margin]. An anchor that covers most of the window (a call
 * from the app's root, such as a notice) centres the card and grows it from its centre.
 */
private class PopoverPosition(private val gap: Int, private val margin: Int, private val origin: Origin) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width; val h = popupContentSize.height
        if (anchorBounds.width > windowSize.width / 2 && anchorBounds.height > windowSize.height / 2) {
            origin.x = 0.5f; origin.y = 0.5f
            return IntOffset((windowSize.width - w) / 2, (windowSize.height - h) / 2)
        }
        val x = (anchorBounds.center.x - w / 2).coerceIn(margin, (windowSize.width - w - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val fitsBelow = below + h <= windowSize.height - margin
        val y = if (fitsBelow) below else (anchorBounds.top - gap - h).coerceAtLeast(margin)
        origin.x = if (w > 0) ((anchorBounds.center.x - x).toFloat() / w).coerceIn(0f, 1f) else 0.5f
        origin.y = if (fitsBelow) 0f else 1f
        return IntOffset(x, y)
    }
}

/** The popover's content scope: a column that can also close the card with its exit animation (after a choice). */
class PopoverScope internal constructor(column: ColumnScope, val close: () -> Unit) : ColumnScope by column

/**
 * A small card that grows out of what opened it (lazy-UX #10): the "?" explanations and the month calendar. It is dismissed
 * by a tap outside, by dragging it away (up or down, following the finger; a scrolling body hands over its leftover drag) or
 * by Back. No "Chiudi" button. Enter on the [Motion.spatial] spring from [Motion.PopoverFromScale] at the anchor, exit on
 * [Motion.page]; reduced motion crossfades. The anchor is the layout the caller composes it in.
 */
@Composable
fun Popover(onDismiss: () -> Unit, label: String, width: Dp = 320.dp, content: @Composable PopoverScope.() -> Unit) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val shown = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var heightPx by remember { mutableStateOf(1f) }
    val origin = remember { Origin() }
    val ch = rememberCommitHaptics(Motion.DismissFraction)
    var feeling by remember { mutableStateOf(false) }
    fun feel(next: Float) { if (!feeling) { feeling = true; ch.start() }; ch.progress(abs(next) / heightPx) }
    LaunchedEffect(Unit) { shown.animateTo(1f, if (reduce) Motion.reducedFade() else Motion.spatial()) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { shown.animateTo(0f, if (reduce) Motion.reducedFade() else Motion.page); dismiss() }
    }
    fun release(velocity: Float) {
        val commit = abs(drag.value) > heightPx * Motion.DismissFraction || abs(velocity) > 1200f
        if (feeling) { feeling = false; ch.release(committed = commit) }
        if (commit) close()
        else scope.launch { drag.animateTo(0f, Motion.spatial(), initialVelocity = velocity) }
    }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                feel(drag.value + available.y)
                scope.launch { drag.snapTo(drag.value + available.y) }
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (abs(drag.value) < 0.5f) return Velocity.Zero
                release(available.y); return available
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val position = remember(density) { with(density) { PopoverPosition(6.dp.roundToPx(), 16.dp.roundToPx(), origin) } }
    val maxWidth = minOf(width, (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp)
    val shape = RoundedCornerShape(24.dp)
    Popup(popupPositionProvider = position, onDismissRequest = { close() }, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.widthIn(max = maxWidth).fillMaxWidth()
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    val s = shown.value
                    val pull = (abs(drag.value) / heightPx).coerceIn(0f, 1f)
                    alpha = (s.coerceIn(0f, 1f) * (1f - pull * 0.6f)).coerceIn(0f, 1f)
                    if (!reduce) {
                        val k = Motion.PopoverFromScale + (1f - Motion.PopoverFromScale) * s
                        scaleX = k; scaleY = k
                        transformOrigin = TransformOrigin(origin.x, origin.y)
                        translationY = drag.value
                    }
                }
                .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                .background(pal.card, shape).border(1.dp, pal.cardLine, shape)
                .nestedScroll(connection)
                .draggable(rememberDraggableState { d -> feel(drag.value + d); scope.launch { drag.snapTo(drag.value + d) } }, Orientation.Vertical, onDragStopped = { v -> release(v) })
                .semantics { paneTitle = label; dismiss { close(); true } }
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
        ) { PopoverScope(this) { close() }.content() }
    }
}
