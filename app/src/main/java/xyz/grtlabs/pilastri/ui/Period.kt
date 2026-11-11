package xyz.grtlabs.pilastri.ui

// Adapted from Xtraordinary (MIT); see assets/licenses/Xtraordinary-MIT.txt.
// Source: the Xteink companion app, ui/components/MagneticPager.kt (MagneticHorizontalPager, PagerResistanceFeedback,
// shouldEmitPagerSettleHaptic, as used by TicketContent.kt) and MagneticSwipe.kt (magneticSelectionStrength). Ported: the
// commit threshold of 0.42 of a page (the pager's snapPositionalThreshold), the fling commit, the ζ 0.78 snap settle that
// continues from the release velocity, the neighbour's fade by selection strength, haptics only for a real change. Changed for
// this app: no HorizontalPager (the strip draws the current period, its neighbours and their peeks itself), the content is drawn at
// MagneticSwipe's resisted position (free 0.08, then 0.48·sin, released by a 90 ms tween at the 0.42 commit point) with a rubber
// band at the ends, MagneticPager's threshold haptic (CommitHaptics through Hx, so system settings apply) and its snapKick
// recoil; the tokens live in Motion.kt.
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/* ---------- Pure physics (unit-tested) ---------- */

object PeriodPhysics {
    /** Rubber band: x·L/(x+L), 1:1 at the edge and never past [limit] pages. */
    fun rubber(x: Float, limit: Float = Motion.PeriodRubber): Float = if (x <= 0f) 0f else x * limit / (x + limit)

    /** The drawn position for a finger position: 1:1 between [first] and [last], rubber band outside (no period there). */
    fun displayed(raw: Float, first: Int, last: Int): Float = when {
        raw < first -> first - rubber(first - raw)
        raw > last -> last + rubber(raw - last)
        else -> raw
    }

    /**
     * Xtraordinary's MagneticSwipeConfig.displayedProgress: the drawn travel (pages, signed) for a finger travel [signed]; [resistance] 1 =
     * resisted, 0 = the finger. Up to [Motion.PeriodFreeTravel] it is the finger; beyond, free + resisted·sin(share of the rest · π/2), never ahead of it.
     */
    fun displayedProgress(signed: Float, resistance: Float): Float {
        val b = signed.coerceIn(-1f, 1f)
        val p = abs(b)
        if (p <= Motion.PeriodFreeTravel) return b
        val c = (p - Motion.PeriodFreeTravel) / (1f - Motion.PeriodFreeTravel)
        val resisted = b.sign * minOf(Motion.PeriodFreeTravel + Motion.PeriodResistedTravel * kotlin.math.sin(c * (Math.PI.toFloat() / 2f)), p)
        return b + (resisted - b) * resistance.coerceIn(0f, 1f)
    }

    /** The finger position that draws [shown] (regrabbing inside the rubber band continues without a jump). */
    fun rawOf(shown: Float, first: Int, last: Int, limit: Float = Motion.PeriodRubber): Float {
        fun inv(d: Float) = d.coerceAtMost(limit * 0.999f).let { it * limit / (limit - it) }
        return when { shown < first -> first - inv(first - shown); shown > last -> last + inv(shown - last); else -> shown }
    }

    /**
     * Where a release settles: ONE period at most from [start] (Xtraordinary's pager snaps at most one page,
     * PagerSnapDistance.atMost(1)), inside [first]..[last]. A fling of at least [flingThreshold] periods per second commits
     * the neighbour in its direction (a quick flick below the distance threshold still goes); otherwise the neighbour
     * commits past [Motion.PeriodThreshold] of a page and anything less goes back. The release velocity is not used for the
     * target: it only carries into the settle spring, so momentum stays without ever crossing a second period.
     */
    fun settleTarget(position: Float, start: Int, velocity: Float, flingThreshold: Float, first: Int, last: Int): Int {
        val step = when {
            abs(velocity) >= flingThreshold -> velocity.sign.toInt()
            abs(position - start) >= Motion.PeriodThreshold -> (position - start).sign.toInt()
            else -> 0
        }
        return (start + step).coerceIn(first, maxOf(first, last))
    }

    /**
     * R1-03 / K5 (one gutter): the strip's viewport overhangs the caller's column by exactly the page [gutter] on each side (the
     * caller's parents clip there) and the pages sit at the column edge, so the current page's cards are on the same gutter line as
     * every other card. No narrowing anywhere.
     */
    fun bleed(gutter: Dp): Dp = gutter

    /**
     * G-11 range ends: how far, in units of the end shift (inset - content margin), a settled page sits from the centred position. A side with no drawn
     * neighbour puts the page's edge on the content margin: no neighbour on the right moves the page right (+1), none on the left moves it left (-1); a
     * neighbour on both sides, or on neither (the page cannot move both ways at the one width), keeps the centred page (0).
     */
    fun alignment(leftDrawn: Boolean, rightDrawn: Boolean, leftExists: Boolean = leftDrawn, rightExists: Boolean = rightDrawn): Float = when {
        leftDrawn && !rightDrawn -> 1f
        !leftDrawn && rightDrawn -> -1f
        // Neither neighbour is drawn (a neighbour without data is hidden): the end of the strip still decides, so the newest page keeps the right margin and the oldest the left one,
        // never a page centred between two empty slots (Mese hero at the newest month, polish 3 B). A middle page with both neighbours hidden stays centred.
        !leftDrawn && !rightDrawn && leftExists && !rightExists -> 1f
        !leftDrawn && !rightDrawn && !leftExists && rightExists -> -1f
        else -> 0f
    }

    /** The alignment at the float [position]: interpolated between the two adjacent settled pages' alignments, so it is part of the swipe travel (clamped past the ends). */
    fun alignmentAt(position: Float, last: Int, at: (Int) -> Float): Float {
        if (last <= 0) return at(0)
        val c = position.coerceIn(0f, last.toFloat())
        val lo = c.toInt().coerceAtMost(last - 1)
        val f = c - lo
        val a = at(lo)
        return a + (at(lo + 1) - a) * f
    }

    /** The part of a neighbour page visible inside the gutter margin at rest: gutter minus the gap between pages (CR-01, 2026-11-09 second note: [Motion.PeriodPeek] 28 dp cover / [Motion.PeriodPeekWide] 40 dp open Fold; [inset] is peek + gap, from the pane edge). */
    fun peek(inset: Dp): Dp = (inset - Motion.PeriodPeekGap).coerceAtLeast(0.dp)

    /**
     * A page's label row opacity from its offset [d] = index - position (pages): the same selection strength as the card's own fade,
     * continuous in the position (no resting / moving or dragging / settling branch), so a row is hidden at rest as a neighbour (no
     * half name in a peek), crossfades with its card while travelling and never jumps at release.
     */
    fun rowAlpha(d: Float): Float = (1f - abs(d)).coerceIn(0f, 1f)

    /** How much of [page] is "selected" at [position] (magneticSelectionStrength): 1 centred, 0 a page away. */
    fun strength(position: Float, page: Int): Float = (1f - abs(position - page)).coerceIn(0f, 1f)
}

/**
 * D-14: a page's content fade while it is a neighbour (selection strength: 0 a page away, 1 centred). Cards read it so that at rest a
 * peek shows an empty tinted card edge, and the neighbour's content fades in continuously with the drag offset as it travels in.
 */
val LocalPeriodPageFade = compositionLocalOf<() -> Float> { { 1f } }

/**
 * CR-01 (peek-visible, every strip): the shared card shells ([periodPageSurfaceBounds]) report the bounds of the cards they draw inside a period page.
 * [PeriodSwipe] then tags `period-page-surface` from this one place, on the union of those card bounds (not the page bounds), so a card inset from the
 * page edge never overstates the visible peek.
 */
@Stable
class PeriodPageSurface {
    var drawn by mutableStateOf(false)
    /** The union of the reporting cards in the tag box's own coordinates; null until measured (the tag then covers the page). */
    var inner by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    internal val cards = HashMap<Any, androidx.compose.ui.layout.LayoutCoordinates>()
    internal var self: androidx.compose.ui.layout.LayoutCoordinates? = null
    internal fun measure() {
        val me = self?.takeIf { it.isAttached } ?: return
        val rs = cards.values.filter { it.isAttached }.map { me.localBoundingBoxOf(it, clipBounds = false) }
        if (rs.isEmpty()) return
        val u = androidx.compose.ui.geometry.Rect(rs.minOf { it.left }, rs.minOf { it.top }, rs.maxOf { it.right }, rs.maxOf { it.bottom })
        if (inner != u) inner = u
    }
}
val LocalPeriodPageSurface = compositionLocalOf<PeriodPageSurface?> { null }

/** Called by a drawn card (on the card's own modifier chain): tells the enclosing period page (if any) that it draws a surface, and where. No effect outside a strip. */
@Composable
fun Modifier.periodPageSurfaceBounds(): Modifier {
    val s = LocalPeriodPageSurface.current ?: return this
    val key = remember { Any() }
    SideEffect { if (!s.drawn) s.drawn = true }
    androidx.compose.runtime.DisposableEffect(s, key) { onDispose { s.cards.remove(key); s.measure() } }
    return this.onGloballyPositioned { c -> s.cards[key] = c; s.measure() }
}

/** The tagged surface node of a period page: the union of its cards (the whole page until the first measure). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageSurfaceTag(m: PeriodPageSurface) {
    val r = m.inner
    val d = LocalDensity.current
    Box(Modifier.matchParentSize().onGloballyPositioned { m.self = it; m.measure() }) {
        if (r == null) Box(Modifier.matchParentSize().testTag(UiTag.PERIOD_PAGE_SURFACE))
        else Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(with(d) { r.width.toDp() }, with(d) { r.height.toDp() }).testTag(UiTag.PERIOD_PAGE_SURFACE),
        )
    }
}

/** Fades a swiped page's content by [LocalPeriodPageFade]; no effect outside a strip. */
fun Modifier.periodContentFade(): Modifier = composed {
    val f = LocalPeriodPageFade.current
    // ModulateAlpha: the alpha multiplies each draw op, no offscreen buffer per card per frame; alpha 0 is not drawn at all.
    graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = f() }
}

/** Counts haptic ticks: one per period the commit point or the settling travel crosses, in either direction. */
class PeriodTicker(start: Int) {
    var at: Int = start; private set
    var total: Int = 0; private set
    fun moveTo(index: Int): Int { val n = abs(index - at); at = index; total += n; return n }
}

/**
 * One drag session on the period strip, in periods. [drag] follows the finger 1:1 (rubber band at the ends) and returns the
 * ticks to play as the commit point moves; [release] picks the target; [travel] ticks the periods the settle passes; only
 * [settled] changes [applied], so figures never follow a period the strip only passes through.
 */
class PeriodGesture(startPosition: Float, private val first: Int, private val last: Int) {
    val start: Int = startPosition.roundToInt().coerceIn(first, maxOf(first, last))
    // The drag is limited to the neighbouring periods (one per gesture), with a rubber band beyond them and at the ends.
    private val lo = maxOf(first, start - 1)
    private val hi = minOf(maxOf(first, last), start + 1)
    private var raw = PeriodPhysics.rawOf(startPosition, lo, hi)
    var shown: Float = startPosition; private set
    val ticker = PeriodTicker(start)
    var applied: Int = start; private set

    fun drag(pages: Float): Int {
        raw += pages
        shown = PeriodPhysics.displayed(raw, lo, hi)
        return ticker.moveTo(PeriodPhysics.settleTarget(shown, start, 0f, Float.MAX_VALUE, first, last))
    }

    /** The finger's travel from where the gesture began, in pages (what the commit point and the threshold haptic are measured on). */
    val fingerTravel: Float get() = shown - start

    /** The drawn period position for the finger's [shown], the Xtraordinary resistance blended by [resistance]. */
    fun displayed(resistance: Float): Float = start + PeriodPhysics.displayedProgress(shown - start, resistance)

    fun release(velocity: Float, flingThreshold: Float): Int = PeriodPhysics.settleTarget(shown, start, velocity, flingThreshold, first, last)

    fun travel(position: Float, target: Int): Int {
        val at = ticker.at; val r = position.roundToInt()
        val next = when { target > at -> r.coerceIn(at, target); target < at -> r.coerceIn(target, at); else -> at }
        return ticker.moveTo(next)
    }

    /** The settle completed on [target]: ticks still owed, and the period applies. */
    fun settled(target: Int): Int { applied = target; return ticker.moveTo(target) }
}

/* ---------- State ---------- */

/**
 * The period strip's state: [position] is the drawn period as a float index (0 = the oldest), [settled] the applied one.
 * Changing the selection from outside (the calendar, the other pane) settles with the same spring.
 */
@Stable
class PeriodState internal constructor(initial: Int, count: Int) {
    var count by mutableIntStateOf(count.coerceAtLeast(1)); internal set
    val position = Animatable(initial.toFloat())
    var settled by mutableIntStateOf(initial); internal set
    internal var dragging by mutableStateOf(false)
    /**
     * For a strip whose pane must stay stationary (the open Fold's left pane): a selection that changes from OUTSIDE fades the
     * strip through [fade] instead of sliding it (see [rememberPeriodState] `fadeOutside`). Always 1 otherwise; never drawn then.
     */
    internal var fadeOutside by mutableStateOf(false)
    internal val fade = Animatable(1f)
    internal fun fadeModifier(): Modifier = if (fadeOutside) Modifier.graphicsLayer { alpha = fade.value } else Modifier
    /** Registered by the PRIMARY strip sharing this state: the only one that ticks and applies the settled period. */
    internal var tickCb: (() -> Unit)? = null
    internal var settleCb: ((Int) -> Unit)? = null
    val last: Int get() = count - 1
    /** The settled period as an index that is always inside 0..[last]: [settled] can still be the old one for a frame after the count changed. Every consumer reads this, never [settled] raw. */
    val settledIndex: Int get() = settled.coerceIn(0, last)
    /** Written by the strip's layout: one page's travel (page width + gap) in px, so a title outside the strip can travel with its page. */
    internal var pitchPx: Float = 0f
    /** Written by the strip's layout (G-11 range ends): the pages' end alignment in px at a float position. */
    internal var alignUnitAt: (Float) -> Float = { 0f }
    internal var endShiftPx: Float = 0f
    /**
     * How far page [i] has moved at [position] beyond the plain pitch travel, relative to where it rests: what a title or label that belongs to that page adds to
     * its own pitch travel so it moves exactly like its page (M1), while at rest (position = i) it stays on the gutter line.
     */
    internal fun pageShiftPx(i: Int, position: Float, shiftPx: Float = endShiftPx): Float = (alignUnitAt(position) - alignUnitAt(i.toFloat())) * shiftPx
    /** Written by the cards' viewport (it spans the page, gutter to gutter): where the page is in the window, so a header piece can fade by how much of it is inside. */
    internal var viewLeftPx: Float = 0f
    internal var viewWidthPx: Float = 0f

    /** The share of a piece [left]..[left]+[width] (window px) that lies inside the page: 1 when the page is unknown. A cut glyph is never drawn at full strength. */
    internal fun visibleShare(left: Float, width: Float): Float {
        if (viewWidthPx <= 0f || width <= 0f) return 1f
        val o = minOf(left + width, viewLeftPx + viewWidthPx) - maxOf(left, viewLeftPx)
        return (o / width).coerceIn(0f, 1f)
    }

    /** Settles on [target] from wherever the strip is, with [velocity] in periods per second; [onTick] per period passed. */
    internal suspend fun settleTo(target: Int, velocity: Float, reduce: Boolean, gesture: PeriodGesture?, onTick: () -> Unit, onSettled: (Int) -> Unit, onArrive: () -> Unit = {}) {
        val t = target.coerceIn(0, last)
        val g = gesture ?: PeriodGesture(position.value, 0, last)
        if (reduce) position.snapTo(t.toFloat())
        else position.animateTo(t.toFloat(), Motion.periodSettle(), initialVelocity = velocity) { repeat(g.travel(value, t)) { onTick() } }
        repeat(g.settled(t)) { onTick() }
        // Xtraordinary's order: the settle tick when the page stops (only a gesture that changed the period), then the kick.
        if (gesture != null && t != gesture.start) onArrive()
        val changed = t != settled
        settled = t
        if (changed) onSettled(t)
        // Xtraordinary's snapKick, after any drag session (a release or a cancel): the page recoils a hair against the release
        // direction and rests (every piece reads position).
        val d = if (gesture == null) 0f else if (t != gesture.start) (t - gesture.start).sign.toFloat() else gesture.fingerTravel.sign
        if (!reduce && gesture != null && d != 0f) {
            position.snapTo(t - d * Motion.PeriodKick)
            position.animateTo(t + d * Motion.PeriodKickBack, tween(Motion.PeriodKickOutMs))
            position.animateTo(t.toFloat(), tween(Motion.PeriodKickInMs))
        }
    }
}

/**
 * THE state of every period strip (Pilastri's hero, Mese, Spese, Contributions): a strip over [count] periods showing [selected];
 * when [selected] changes from outside, the strip settles to it on the same spring. [fadeOutside]: where the strip's pane must not
 * move (the open Fold's left pane) the outside change fades the strip out, places it on the new period and fades it back in; the
 * person's own swipe on the strip is unchanged. That is the only difference between a hero strip and any other, and it is a parameter.
 */
@Composable
fun rememberPeriodState(selected: Int, count: Int, fadeOutside: Boolean = false): PeriodState {
    val state = remember { PeriodState(selected.coerceIn(0, (count - 1).coerceAtLeast(0)), count) }
    state.count = count.coerceAtLeast(1)
    // The count and the settled period change together: a shorter strip never keeps an index past its end (semantics, labels and pages read it in the same frame).
    if (state.settled > state.last) state.settled = state.last
    state.fadeOutside = fadeOutside
    val reduce = LocalReduceMotion.current
    LaunchedEffect(selected, count, fadeOutside) {
        val s = selected.coerceIn(0, state.last)
        if (!state.dragging && (s != state.settled || abs(state.position.value - s) > 0.001f)) {
            if (fadeOutside) {
                state.fade.travelTo(0f, reduce, Motion.effects())
                state.position.snapTo(s.toFloat())
                state.settled = s
                state.fade.travelTo(1f, reduce, Motion.effects())
            } else state.settleTo(s, 0f, reduce, null, {}, {})
        } else if (state.fade.value < 1f) state.fade.travelTo(1f, reduce, Motion.effects())
    }
    return state
}

/* ---------- The strip ---------- */

/**
 * G-11's swipe surface (DESIGN "Global frame" G-11): [content] for the current period follows the finger 1:1; a release past
 * the threshold or a quick flick settles on the spring on the neighbouring period only (never two per gesture; the release
 * velocity carries into the spring); the ends rubber-band; a tick per
 * period crossed; [onSettle] runs only for the settled period. At rest a faded sliver of each neighbour shows in the
 * [gutter] (the caller's horizontal padding, which the strip extends into), gutter minus [Motion.PeriodPeekGap] wide (CR-01: 28 dp cover / 40 dp open Fold, [inset] = peek + gap), alpha 0.6, at the current page's height and unscaled; no peek where there is no period or
 * the neighbour has no data. Reduced motion: no travel, a short crossfade. Vertical scrolling of the page is unaffected.
 */
@Composable
fun PeriodSwipe(
    state: PeriodState,
    onSettle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hasData: (Int) -> Boolean = { true },
    gutter: Dp = 16.dp,
    inset: Dp = gutter,
    wide: Boolean = false,
    primary: Boolean = true,
    surface: androidx.compose.ui.graphics.Color? = null,
    content: @Composable (index: Int) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val hx = rememberHx()
    val haptics = rememberCommitHaptics(Motion.PeriodThreshold)
    val reduceHx = rememberCommitHaptics(Motion.PeriodThreshold * 0.5f) // reduced motion commits at half a threshold of travel
    val blend = remember(state) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // CR-01: the viewport overhangs the column by the gutter (where the parent clips) and the pages sit at the column edge,
    // one gap apart, so the visible neighbour is gutter - gap (28 / 40 dp, CR-01) inside the real clip; swiping cards are inset by peek + gap, UX beats alignment with non-swiping cards.
    val bleed = PeriodPhysics.bleed(inset)
    val reach = gutter
    // G-11 range ends: the content margin is the caller's [gutter]; a side without a drawn neighbour moves the page edge from the inset out to it.
    val endShift = (inset - gutter).coerceAtLeast(0.dp)
    val dataNow = rememberUpdatedState(hasData)
    val alignOf = rememberUpdatedState { k: Int ->
        PeriodPhysics.alignment(leftDrawn = k > 0 && dataNow.value(k - 1), rightDrawn = k < state.last && dataNow.value(k + 1), leftExists = k > 0, rightExists = k < state.last)
    }
    val settle by rememberUpdatedState(onSettle)
    val data by rememberUpdatedState(hasData)
    // Several strips may share one state (one card each): only the primary one ticks and calls onSettle, a drag on any moves all.
    val tick = rememberUpdatedState { hx.tick() }
    if (primary) SideEffect { state.tickCb = { tick.value() }; state.settleCb = { settle(it) } }
    val doTick: () -> Unit = { state.tickCb?.invoke() }
    val doSettle: (Int) -> Unit = { state.settleCb?.invoke(it) }
    if (reduce) {
        // No travel: a horizontal swipe steps one period and the content crossfades.
        Box(modifier.then(state.fadeModifier()).testTag(UiTag.PERIOD_VIEWPORT).pointerInput(state) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f; reduceHx.start() },
                onDragEnd = {
                    val step = if (abs(total) / size.width.coerceAtLeast(1) > reduceHx.threshold) (-total.sign).toInt() else 0
                    // The crossfade is the visible change: the snap plays with it (nothing travels, so no ticks on the way).
                    reduceHx.release(committed = step != 0)
                    if (step != 0) scope.launch { state.settleTo(state.settledIndex + step, 0f, true, null, {}, doSettle) }
                },
                onDragCancel = { reduceHx.cancel() },
            ) { change, dx -> change.consume(); total += dx }
        }) {
            Crossfade(state.settledIndex, animationSpec = Motion.reducedFade(), label = "period") { k ->
                if (surface == null) content(k)
                else Box(Modifier.clip(RoundedCornerShape(Motion.PeriodSurfaceRadius)).background(surface).testTag(UiTag.PERIOD_PAGE_SURFACE)) { content(k) }
            }
        }
        return
    }
    // R2-M4: composition reads only the rounded page (one change per period), never the per-frame drag offset (read in layer / layout).
    val base by remember(state) { derivedStateOf { state.position.value.roundToInt().coerceIn(0, state.last) } }
    val pages = (base - 1..base + 1).filter { it in 0..state.last }
    Layout(
        content = {
            pages.forEach { i ->
                key(i) {
                    val pageAlpha = Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        val p = state.position.value
                        val s = PeriodPhysics.strength(p, i)
                        val neighbourHidden = i != state.settled && !data(i) && !state.dragging && !state.position.isRunning
                        alpha = if (neighbourHidden) 0f else Motion.PeriodPeekAlpha + (1f - Motion.PeriodPeekAlpha) * s
                    }
                    // Neighbour pages are a visual peek: no focusable / actionable semantics (the label tap and the prev / next
                    // actions step); they keep their position tag, set inside the clearing block so it is exposed.
                    val pageSemantics =
                        if (i != state.settled) Modifier.clearAndSetSemantics { testTag = if (i < state.settled) UiTag.PERIOD_PAGE_PREV else UiTag.PERIOD_PAGE_NEXT }
                        else Modifier.testTag(UiTag.PERIOD_PAGE_CUR)
                    val marker = remember(state, i) { PeriodPageSurface() }
                    // A neighbour without data is drawn at alpha 0 (see pageAlpha): it exposes no surface node, so the peek rule never counts an invisible page.
                    val hidden by remember(state, i) { derivedStateOf { i != state.settled && !data(i) && !state.dragging && !state.position.isRunning } }
                    val body: @Composable () -> Unit = {
                        val fade = remember(state, i) { { PeriodPhysics.strength(state.position.value, i) } }
                        CompositionLocalProvider(LocalPeriodPageFade provides fade, LocalPeriodPageSurface provides marker) { content(i) }
                    }
                    if (surface == null) {
                        // The page's own card is its drawn surface: tag the page bounds (a node of its own, outside the neighbour's cleared semantics) once a card reported itself.
                        Box(pageAlpha, propagateMinConstraints = true) {
                            if (marker.drawn && !hidden) PageSurfaceTag(marker)
                            Box(pageSemantics, propagateMinConstraints = true) { body() }
                        }
                    }
                    else {
                        // CR-01 (peek-visible): the page draws a tonal tile of its own, at the page's alpha and outside the content fade, so the
                        // neighbour's tinted edge shows in the peek slot at rest; its own node, because the neighbour's semantics are cleared.
                        Box(pageAlpha, propagateMinConstraints = true) {
                            if (!hidden) Box(Modifier.matchParentSize().clip(RoundedCornerShape(Motion.PeriodSurfaceRadius)).background(surface).testTag(UiTag.PERIOD_PAGE_SURFACE))
                            else Box(Modifier.matchParentSize().clip(RoundedCornerShape(Motion.PeriodSurfaceRadius)).background(surface))
                            Box(pageSemantics, propagateMinConstraints = true) { body() }
                        }
                    }
                }
            }
        },
        modifier = modifier.then(state.fadeModifier())
            // The strip reaches into the caller's padding so the neighbours' slivers show there.
            .layout { m, c ->
                val g = reach.roundToPx()
                val w = if (c.hasBoundedWidth) c.maxWidth else 0
                val p = m.measure(Constraints(minWidth = w + 2 * g, maxWidth = w + 2 * g, minHeight = c.minHeight, maxHeight = c.maxHeight))
                layout(w, p.height) { p.place(-g, 0) }
            }
            .animateContentSize(Motion.periodSettle<IntSize>())
            .clipToBounds()
            .onGloballyPositioned { state.viewLeftPx = it.positionInWindow().x; state.viewWidthPx = it.size.width.toFloat() }
            .testTag(UiTag.PERIOD_VIEWPORT)
            .pointerInput(state) {
                val tracker = VelocityTracker()
                var gesture: PeriodGesture? = null
                var beyond = false
                var blendJob: kotlinx.coroutines.Job? = null
                fun pageWidth() = (size.width - 2 * bleed.toPx() + Motion.PeriodPeekGap.toPx()).coerceAtLeast(1f)
                // G-11 range ends: on a segment whose two pages sit at different alignments the page travels (pitch - shift) per period, so the page under the finger still follows it 1:1.
                fun travelPx(forward: Boolean): Float {
                    val p = state.position.value
                    val seg = (if (forward) kotlin.math.floor(p).toInt() else kotlin.math.ceil(p).toInt() - 1)
                    if (seg < 0 || seg >= state.last) return pageWidth()
                    return (pageWidth() - (alignOf.value(seg + 1) - alignOf.value(seg)) * endShift.toPx()).coerceAtLeast(1f)
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        state.dragging = true
                        tracker.resetTracking()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.position.stop() }
                        gesture = PeriodGesture(state.position.value, 0, state.last)
                        beyond = false
                        blendJob?.cancel()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { blend.snapTo(1f) }
                        haptics.start()
                    },
                    onDragEnd = {
                        val g = gesture ?: return@detectHorizontalDragGestures
                        val vx = tracker.calculateVelocity().x
                        val v = -vx / travelPx(vx < 0f)
                        val flingThreshold = Motion.PeriodFlingDps * density / travelPx(vx < 0f)
                        val target = g.release(v, flingThreshold)
                        state.dragging = false
                        blendJob?.cancel()
                        val moved = target != g.start
                        haptics.release(committed = moved) // moment one: the snap for a commit that never crossed live
                        // moment two: the settle tick when the page has stopped on the new period, before the kick
                        scope.launch { state.settleTo(target, v, false, g, {}, doSettle, onArrive = { haptics.settled() }) }
                    },
                    onDragCancel = {
                        val g = gesture
                        state.dragging = false
                        blendJob?.cancel()
                        haptics.cancel() // beyond the threshold: the way-back snap as the resistance returns
                        scope.launch { state.settleTo(g?.start ?: state.settled, 0f, false, g, {}, doSettle) }
                    },
                ) { change, dx ->
                    val g = gesture ?: return@detectHorizontalDragGestures
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    g.drag(-dx / travelPx(dx < 0f))
                    haptics.progress(g.fingerTravel)
                    // Drawn at the RESISTED position (cards, labels and header all read state.position); a short tween releases the
                    // resistance at the commit point so the page catches up with the finger exactly where the haptic snaps.
                    val now = abs(g.fingerTravel) >= Motion.PeriodThreshold
                    if (now != beyond) {
                        beyond = now
                        blendJob?.cancel()
                        blendJob = scope.launch {
                            blend.animateTo(if (now) 0f else 1f, tween(Motion.PeriodResistanceMs)) {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { state.position.snapTo(g.displayed(value)) }
                            }
                        }
                    } else scope.launch(start = CoroutineStart.UNDISPATCHED) { state.position.snapTo(g.displayed(blend.value)) }
                }
            },
    ) { measurables, constraints ->
        val inset = bleed.roundToPx()
        val gap = Motion.PeriodPeekGap.roundToPx()
        val shift = endShift.roundToPx()
        val single = state.last <= 0 // a strip with one page spans margin to margin
        val w = (constraints.maxWidth - 2 * inset + if (single) 2 * shift else 0).coerceAtLeast(1)
        // Geometry is per strip; the state keeps only the PRIMARY strip's (the one the titles and labels outside the strips travel with), so a strip of another width or shift never overwrites it.
        if (primary) {
            state.pitchPx = (w + gap).toFloat()
            state.alignUnitAt = { pos -> if (single) 0f else PeriodPhysics.alignmentAt(pos, state.last, alignOf.value) }
            state.endShiftPx = shift.toFloat()
        }
        // The current page sets the height; neighbours are laid out at exactly that height (taller content is clipped), so a
        // card in a neighbour has the same top and bottom as the current one. A change of current height animates (outer spring).
        val ci = pages.indexOf(base)
        val cur = measurables.getOrNull(ci)?.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = constraints.maxHeight))
        val h = cur?.height ?: 0
        val placeables = measurables.mapIndexed { k, m -> if (k == ci && cur != null) cur else m.measure(Constraints(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)) }
        layout(constraints.maxWidth, h) {
            placeables.forEachIndexed { k, p ->
                val i = pages[k]
                p.placeWithLayer(if (single) inset - shift else inset, 0) {
                    translationX = (i - state.position.value) * (w + gap) + if (single) 0f else PeriodPhysics.alignmentAt(state.position.value, state.last, alignOf.value) * shift
                }
            }
        }
    }
}

/**
 * The period labels above a strip (D4: labels belong to their page). The row is a strip with the SAME geometry, pitch and
 * position state as the cards below it: every page draws its own row ("‹ previous" on the left line, the current name, "next ›"
 * on the right line, as G-11 places them at rest) and the rows travel with their cards, so each label moves exactly as its page
 * does and nothing is re-laid out at release or at the end of the settle. A row fades by the same selection strength as its
 * card's neighbour fade (hidden at rest for a neighbour, so no half name shows in a peek). The settled page's row carries the
 * semantics: the current name taps → [onOpenCalendar] (TalkBack has previous and next actions), a neighbour name steps once and
 * is absent where there is no period. With [showCurrent] false only the neighbours show (the page's title names the period).
 */
@Composable
fun PeriodLabels(
    state: PeriodState,
    label: (Int) -> String,
    onStep: (Int) -> Unit,
    onOpenCalendar: (() -> Unit)?,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    showCurrent: Boolean = true,
    hasData: (Int) -> Boolean = { true },
    edgeInset: Dp = 0.dp,
    gutter: Dp = 16.dp,
    inset: Dp = gutter,
    wide: Boolean = false,
) {
    // A neighbour row exists only while it is (partly) on its way in: at rest it is hidden and would only add overlapping hidden labels.
    // R2-M4: derived, so composition only sees a change when a row appears or goes (structural equality), not every drag frame.
    val ids by remember(state) {
        derivedStateOf {
            val b = state.position.value.roundToInt().coerceIn(0, state.last)
            (b - 1..b + 1).filter { it in 0..state.last && abs(it - state.position.value) < 0.999f }
        }
    }
    val pad = 10.dp
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // R1-03: the label rows share the cards' geometry (page at the column edge = the page gutter line), so no label shift is needed.
    val bleed = PeriodPhysics.bleed(inset)
    val reach = gutter
    @Composable
    fun Name(text: String, tag: String?, kind: Int, faded: Boolean, tappable: Boolean, onClick: () -> Unit, extra: Modifier, align: Alignment) {
        // kind -1 previous (its text starts on the left line), 0 current (centred), 1 next (its text ends on the right line).
        Box(
            extra.graphicsLayer { alpha = if (faded) 0.6f else 1f }
                .heightIn(min = 48.dp).widthIn(min = 48.dp)
                .pressable(RoundedCornerShape(16.dp), color, onClick = onClick, enabled = tappable)
                .padding(start = if (kind < 0) 0.dp else pad, end = if (kind > 0) 0.dp else pad),
            contentAlignment = align,
        ) {
            Row(if (tag != null) Modifier.testTag(tag) else Modifier, verticalAlignment = Alignment.CenterVertically) {
                if (kind < 0) Text("‹ ", style = style, color = color)
                Text(text, style = style, color = color, maxLines = 1, textAlign = TextAlign.Center)
                if (kind > 0) Text(" ›", style = style, color = color)
            }
        }
    }
    Layout(
        content = {
            ids.forEach { i ->
                key(i) {
                    val active = i == state.settled
                    val prev = (i - 1).takeIf { it >= 0 }
                    val next = (i + 1).takeIf { it <= state.last }
                    BoxWithConstraints(
                        Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = PeriodPhysics.rowAlpha(i - state.position.value) }
                            .padding(horizontal = edgeInset)
                            .then(if (active) Modifier else Modifier.clearAndSetSemantics { }),
                    ) {
                        // Each label owns a bounded slot (F2b): previous starts on the left line, current is centred, next ends on
                        // the right line, and the slots never overlap. When the row cannot hold everything the least important
                        // part goes first: neighbour names lose any suffix, then shorten to the month abbreviation, then the
                        // current name drops its "· dati ..." suffix, and last the neighbour names are left out. Never an ellipsis.
                        val curFull = if (showCurrent) label(i) else ""
                        val prevFull = prev?.let(label)
                        val nextFull = next?.let(label)
                        fun base(t: String) = t.substringBefore(" · ")
                        fun abbr(t: String) = base(t).let { b -> if (b.length > 4) b.take(3) else b }
                        fun w(t: String, arrow: Boolean, edge: Boolean): Float {
                            if (t.isEmpty()) return 0f
                            val txt = measurer.measure(AnnotatedString(if (arrow) "‹ $t" else t), style, maxLines = 1, softWrap = false)
                            return with(density) { txt.size.width.toDp().value } + (if (edge) pad.value else 2 * pad.value)
                        }
                        val avail = maxWidth.value
                        val gapMin = 8f
                        var curText = base(curFull)
                        var nb = 2 // 0 base, 1 abbreviation, 2 hidden
                        run pick@{
                            for (c in 0..1) {
                                val ct = if (c == 0) curFull else base(curFull)
                                val side = (avail - maxOf(48f, w(ct, false, false))) / 2f - gapMin
                                for (n in 0..1) {
                                    val pw = prevFull?.let { w(if (n == 0) base(it) else abbr(it), true, true) } ?: 0f
                                    val nw = nextFull?.let { w(if (n == 0) base(it) else abbr(it), true, true) } ?: 0f
                                    if (maxOf(pw, nw) <= side) { curText = ct; nb = n; return@pick }
                                }
                            }
                        }
                        if (prev != null && nb < 2) {
                            val name = label(prev)
                            Name(if (nb == 0) base(name) else abbr(name), if (active) UiTag.PERIOD_LABEL_PREV else null, -1, false, true, { onStep(prev) },
                                Modifier.align(Alignment.CenterStart).semantics { role = Role.Button; contentDescription = "Vai a $name" }, Alignment.CenterStart)
                        }
                        if (showCurrent) {
                            val name = label(i)
                            Name(curText, if (active) UiTag.PERIOD_LABEL_CUR else null, 0, !hasData(i), onOpenCalendar != null, { onOpenCalendar?.invoke() },
                                Modifier.align(Alignment.Center).semantics {
                                    role = Role.Button
                                    contentDescription = "$name, scegli il periodo"
                                    customActions = listOfNotNull(
                                        prev?.let { CustomAccessibilityAction("Periodo precedente: ${label(it)}") { onStep(it); true } },
                                        next?.let { CustomAccessibilityAction("Periodo successivo: ${label(it)}") { onStep(it); true } },
                                    )
                                }, Alignment.Center)
                        }
                        if (next != null && nb < 2) {
                            val name = label(next)
                            Name(if (nb == 0) base(name) else abbr(name), if (active) UiTag.PERIOD_LABEL_NEXT else null, 1, false, true, { onStep(next) },
                                Modifier.align(Alignment.CenterEnd).semantics { role = Role.Button; contentDescription = "Vai a $name" }, Alignment.CenterEnd)
                        }
                    }
                }
            }
        },
        modifier = modifier.then(state.fadeModifier()).fillMaxWidth().heightIn(min = 48.dp)
            // The same geometry as the cards' strip (D3/D4): overhang by [reach], pages [bleed] inside that edge, one pitch per period.
            .layout { m, c ->
                val g = reach.roundToPx()
                val w = if (c.hasBoundedWidth) c.maxWidth else 0
                val p = m.measure(Constraints(minWidth = w + 2 * g, maxWidth = w + 2 * g, minHeight = c.minHeight, maxHeight = c.maxHeight))
                layout(w, p.height) { p.place(-g, 0) }
            }
            .clipToBounds(),
    ) { measurables, constraints ->
        val inset = bleed.roundToPx()
        val gap = Motion.PeriodPeekGap.roundToPx()
        val w = (constraints.maxWidth - 2 * inset).coerceAtLeast(1)
        state.pitchPx = (w + gap).toFloat()
        val ownShift = (bleed - gutter).coerceAtLeast(0.dp).roundToPx().toFloat() // this row's own end shift (G-11 range ends), as its strips compute theirs
        val placeables = measurables.map { it.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0)) }
        val h = maxOf(48.dp.roundToPx(), placeables.maxOfOrNull { it.height } ?: 0)
        layout(constraints.maxWidth, h) {
            placeables.forEachIndexed { k, pl ->
                val i = ids[k]
                pl.placeWithLayer(inset, (h - pl.height) / 2) { translationX = (i - state.position.value) * (w + gap) + state.alignUnitAt(state.position.value) * ownShift } // a label row lives inside its page: it rests with the page's alignment (G-11 range ends), unlike a title on the gutter line
            }
        }
    }
}

/* ---------- The month calendar (G-11: tap the month name) ---------- */

/**
 * A compact calendar in a [Popover] from the month name: a year row and a 12-month grid. Months off the timeline are dimmed
 * and inert; months on it without data are dimmed but choosable ("—" there, never 0). Choosing settles the strip with the
 * same spring (the caller passes the choice to the strip's selection).
 */
@Composable
fun MonthCalendar(timeline: List<YearMonth>, hasData: (YearMonth) -> Boolean, selected: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    if (timeline.isEmpty()) { onDismiss(); return }
    val years = timeline.map { it.year }.distinct()
    var year by remember { mutableIntStateOf(selected.year.coerceIn(years.first(), years.last())) }
    Popover(onDismiss, "Scegli il mese", width = 300.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.back, "Anno precedente", { if (year > years.first()) year-- }, tint = if (year > years.first()) pal.brand else pal.ink3)
            Text(year.toString(), style = Type.rowTitle, color = pal.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            RoundIcon(Ic.chevronRight, "Anno successivo", { if (year < years.last()) year++ }, tint = if (year < years.last()) pal.brand else pal.ink3)
        }
        (1..12).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { mo ->
                    val m = YearMonth.of(year, mo)
                    val on = m in timeline
                    val chosen = m == selected
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .then(if (chosen) Modifier.background(pal.tabPill, shape) else Modifier)
                            .pressable(shape, pal.pressTint, onClick = { onPick(m); this@Popover.close() }, enabled = on)
                            .graphicsLayer { alpha = when { !on -> 0.3f; !hasData(m) -> 0.55f; else -> 1f } }
                            .semantics { contentDescription = Fmt.monthYear(m) + when { !on -> ", nessun dato"; !hasData(m) -> ", senza dati"; else -> "" } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Fmt.monthShort(m), style = if (chosen) Type.smallStrong else Type.small, color = if (chosen) pal.tabOn else pal.ink)
                    }
                }
            }
        }
    }
}

/**
 * The period's name as a control (Mese's title): a tap opens the calendar, TalkBack gets previous/next actions. For a name
 * that is not inside [PeriodLabels].
 */
fun Modifier.periodControl(state: PeriodState, label: (Int) -> String, onStep: (Int) -> Unit, onOpenCalendar: () -> Unit, tint: androidx.compose.ui.graphics.Color): Modifier =
    heightIn(min = 48.dp).widthIn(min = 48.dp).pressable(RoundedCornerShape(12.dp), tint, onClick = onOpenCalendar)
        .semantics {
            val i = state.settledIndex
            contentDescription = "${label(i)}, scegli il mese"
            customActions = listOfNotNull(
                (i - 1).takeIf { it >= 0 }?.let { CustomAccessibilityAction("Mese precedente: ${label(it)}") { onStep(it); true } },
                (i + 1).takeIf { it <= state.last }?.let { CustomAccessibilityAction("Mese successivo: ${label(it)}") { onStep(it); true } },
            )
        }
