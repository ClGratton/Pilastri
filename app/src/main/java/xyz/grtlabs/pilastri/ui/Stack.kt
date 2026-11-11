package xyz.grtlabs.pilastri.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class StackMove { PUSH, POP, SWAP }

/**
 * A page stack with one physical model (AGENTS.md motion contract, Main.dc.html "Linguaggio di movimento"):
 *  - push brings the child in from the right edge while the parent slides 28% left and dims; pop is the exact
 *    reverse; predictive back moves the same layers with the finger and settles with the page spring;
 *  - replacing the top with a sibling (another pillar from the left pane) travels sideways in [swapDirection]: the new
 *    page arrives from the right (or the left), the old one recedes 28% the other way and dims. Each page keeps its own
 *    spring, so a quick second tap or a reversal continues from where the pages are, with their velocity.
 *
 * The page under the top one stays composed, so it is already there when the top page moves away: nothing reloads.
 * States of covered pages are kept by a SaveableStateHolder; popped pages forget theirs.
 * [entries] belong to the caller: appending one entry pushes, removing the top pops, any other change swaps.
 * [onBack] is called when the back gesture commits.
 * [enterTop] (read once, when the host is created): the top entry arrives as a push instead of being already in place. Folding
 * and unfolding use it (G-09): the right pane's page is pushed over the left root on the cover, and returns into the right pane.
 */
@Composable
fun <T : Any> StackHost(
    entries: List<T>,
    onBack: () -> Unit,
    keyOf: (T) -> String,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    swapDirection: (from: T, to: T) -> Int = { _, _ -> 1 },
    enterTop: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val reduceNow by rememberUpdatedState(reduce)
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(1f) }
    var known by remember { mutableStateOf(if (enterTop && entries.isNotEmpty()) entries.dropLast(1) else entries) }
    val current by rememberUpdatedState(entries)
    var moving by remember { mutableStateOf<T?>(null) }
    var outgoing by remember { mutableStateOf<T?>(null) }
    var move by remember { mutableStateOf(StackMove.PUSH) }
    var dragging by remember { mutableStateOf(false) }
    var releaseVelocity by remember { mutableStateOf(0f) }
    // Sideways travel of sibling pages: offset as a fraction of the width (0 = in place), bottom to top.
    val side = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    var swapLayers by remember { mutableStateOf<List<T>>(emptyList()) }
    var swapGen by remember { mutableStateOf(0) }

    fun endSwap(keep: T?) {
        swapLayers.filter { it != keep && it !in known }.forEach { holder.removeState(keyOf(it)) }
        swapLayers = emptyList(); side.clear()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { current }.collect { now ->
            val before = known
            if (now == before) return@collect
            known = now
            val pushed = now.size == before.size + 1 && now.dropLast(1) == before
            val popped = now.size < before.size && before.take(now.size) == now && now.isNotEmpty()
            when {
                pushed -> launch {
                    if (swapLayers.isNotEmpty()) { swapGen++; endSwap(before.lastOrNull()) }
                    outgoing?.let { holder.removeState(keyOf(it)) }
                    outgoing = null; move = StackMove.PUSH; moving = now.last()
                    if (!dragging) progress.snapTo(0f)
                    progress.travelTo(1f, reduceNow, Motion.page)
                    moving = null
                }
                popped -> launch {
                    if (swapLayers.isNotEmpty()) { swapGen++; endSwap(before.lastOrNull()) }
                    // The gesture may already have moved the page: continue from wherever it is.
                    if (moving != before.last()) progress.snapTo(1f)
                    move = StackMove.POP; outgoing = before.last(); moving = before.last()
                    progress.travelTo(0f, reduceNow, Motion.page, releaseVelocity.coerceAtMost(0f))
                    releaseVelocity = 0f
                    before.drop(now.size).forEach { holder.removeState(keyOf(it)) }
                    outgoing = null; moving = null; progress.snapTo(1f)
                }
                else -> launch {
                    val from = before.lastOrNull(); val to = now.lastOrNull() ?: return@launch
                    val dir = if (from != null) swapDirection(from, to).coerceIn(-1, 1).let { if (it == 0) 1 else it } else 1
                    // A push still travelling (a second pillar tapped mid-way) hands its positions over: no jump.
                    val pushP = progress.value
                    val interruptedPush = move == StackMove.PUSH && moving != null && moving == from && pushP < 0.999f
                    if (interruptedPush && from != null) {
                        side[keyOf(from)] = Animatable(1f - pushP)
                        before.getOrNull(before.size - 2)?.takeIf { it != to }?.let { under ->
                            side[keyOf(under)] = Animatable(-Motion.ParentShift * pushP)
                            if (under !in swapLayers) swapLayers = listOf(under) + swapLayers
                            launch { side[keyOf(under)]?.travelTo(-Motion.ParentShift, reduceNow, Motion.sibling) }
                        }
                    }
                    move = StackMove.SWAP; moving = null; outgoing = null; progress.snapTo(1f)
                    val gen = ++swapGen
                    swapLayers = (swapLayers.filter { it != to } + listOfNotNull(from).filter { it !in swapLayers }) .let { it + to }
                    val incoming = side.getOrPut(keyOf(to)) { Animatable(dir.toFloat()) }
                    val leaving = from?.let { side.getOrPut(keyOf(it)) { Animatable(0f) } }
                    try {
                        coroutineScope {
                            launch { incoming.travelTo(0f, reduceNow, Motion.sibling) }
                            if (leaving != null) launch { leaving.travelTo(-dir * Motion.ParentShift, reduceNow, Motion.sibling) }
                        }
                    } catch (e: CancellationException) { throw e }
                    // Only the latest swap tidies up: an interrupted one leaves its pages to the next.
                    if (gen == swapGen) { endSwap(to); move = StackMove.PUSH }
                    before.filter { it !in now && it !in swapLayers }.forEach { holder.removeState(keyOf(it)) }
                }
            }
        }
    }

    // The page follows the finger 1:1 (touchX, not the system's eased progress) and the release velocity
    // is handed to the settling spring, so a flick keeps its momentum and a slow drag lands softly.
    var widthPx by remember { mutableStateOf(1f) }
    val commitHx = rememberCommitHaptics(Motion.DismissFraction) // the platform decides the commit: grab and release only, no invented threshold
    PredictiveBackHandler(enabled = backEnabled && entries.size > 1) { events ->
        commitHx.start()
        if (swapLayers.isNotEmpty()) { swapGen++; endSwap(known.lastOrNull()) }
        dragging = true
        move = StackMove.POP
        moving = entries.last()
        var startX = Float.NaN
        var lastX = 0f; var lastT = 0L; var velocity = 0f
        try {
            events.collect { e ->
                val now = System.nanoTime()
                if (startX.isNaN()) { startX = e.touchX; lastX = e.touchX; lastT = now }
                val travel = kotlin.math.abs(e.touchX - startX)
                val dt = (now - lastT) / 1e9f
                if (dt > 0.004f) { velocity = 0.6f * velocity + 0.4f * (kotlin.math.abs(e.touchX - startX) - kotlin.math.abs(lastX - startX)) / dt; lastX = e.touchX; lastT = now }
                progress.snapTo((1f - travel / widthPx).coerceIn(0f, 1f))
            }
            releaseVelocity = -velocity / widthPx
            commitHx.release(committed = true) // the snap as the commit becomes visible; no settle, nothing separate arrives
            onBack()
        } catch (e: CancellationException) {
            commitHx.cancel()
            scope.launch { progress.travelTo(1f, reduceNow, Motion.page, -velocity / widthPx); moving = null }
            throw e
        } finally { dragging = false }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        widthPx = width.coerceAtLeast(1f)
        val background = MaterialTheme.colorScheme.background
        val swapping = move == StackMove.SWAP && swapLayers.isNotEmpty()
        val layers = if (swapping) swapLayers else buildList {
            addAll(known.takeLast(2))
            outgoing?.let { if (it !in this) add(it) }
        }
        val top = if (swapping) swapLayers.last() else moving ?: known.lastOrNull()
        val parent = if (!swapping && moving != null) layers.getOrNull(layers.indexOf(moving) - 1) else null
        layers.forEach { entry ->
            key(keyOf(entry)) {
                val isMoving = !swapping && entry == moving
                val isParent = !swapping && entry == parent
                val visible = swapping || entry == top || isParent
                val sideAnim = if (swapping) side[keyOf(entry)] else null
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val p = progress.value
                            alpha = 1f
                            translationX = 0f; translationY = 0f; shadowElevation = 0f
                            when {
                                !visible -> alpha = 0f
                                sideAnim != null -> {
                                    val x = sideAnim.value
                                    if (reduce) { if (entry == top) alpha = (1f - kotlin.math.abs(x)).coerceIn(0f, 1f) }
                                    else {
                                        translationX = x * width
                                        if (entry == top && kotlin.math.abs(x) > 0.001f) { shadowElevation = 12.dp.toPx(); shape = RectangleShape; clip = false }
                                    }
                                }
                                isMoving -> if (reduce) alpha = p else {
                                    translationX = (1f - p) * width
                                    shadowElevation = 12.dp.toPx() * (if (p in 0.001f..0.999f) 1f else 0f)
                                    shape = RectangleShape; clip = false
                                }
                                isParent && !reduce -> translationX = -Motion.ParentShift * width * progress.value
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (isParent && !reduce) drawRect(Color.Black, alpha = Motion.ParentDim * progress.value)
                            if (sideAnim != null && entry != top && !reduce) {
                                val receded = (kotlin.math.abs(sideAnim.value) / Motion.ParentShift).coerceIn(0f, 1f)
                                if (receded > 0.001f) drawRect(Color.Black, alpha = Motion.ParentDim * receded)
                            }
                        }
                        .background(background)
                        // A covered page at rest (alpha 0) leaves the semantics tree: TalkBack, uiautomator and the checker see only what the person sees.
                        .then(if (!visible) Modifier.clearAndSetSemantics { } else Modifier)
                        // The visible top page is a hit target over its whole area, so taps never reach the page beneath.
                        .then(if (entry == top) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } } else Modifier),
                ) { holder.SaveableStateProvider(keyOf(entry)) { content(entry) } }
            }
        }
    }
}
