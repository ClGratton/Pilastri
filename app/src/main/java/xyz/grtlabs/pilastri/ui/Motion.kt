package xyz.grtlabs.pilastri.ui

import androidx.compose.ui.unit.dp
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * The only motion tokens in the app (AGENTS.md motion contract). Springs for space, short tweens only for color/alpha.
 *  - [page]: push/pop of whole pages. Critically damped so a full-width page never overshoots the edge; ~0.32 s response.
 *  - [spatial]: things that move inside a page (selection indicator, expanding rows, pane content). A little life, still settles fast.
 *  - [press]: press-down and release; quick with a slight rebound on release.
 *  - [effects]: color/alpha changes that must not bounce.
 */
object Motion {
    /** The concept's page spring ("Linguaggio di movimento", Main.dc.html): k 380, ζ 0.96, progress 0..1. */
    const val PageStiffness = 380f
    const val PageDamping = 0.96f

    /**
     * Android's animator duration scale, observed live. Compose already multiplies every animation's time by it, so at
     * 0.5 a page settled in about 0.15 s and read as a cut on the user's Fold. Below 1 the springs are softened by the
     * same factor (stiffness × scale): the net duration then shrinks only with √scale (0.71× at 0.5). A faster setting
     * still means faster motion, and 0 still means none (reduced motion), but travel stays visible.
     */
    @Volatile var platformScale: Float = 1f
    private val soften: Float get() = platformScale.coerceIn(0.3f, 1f)

    /**
     * Reduced motion = Android's "Remove animations" (animator scale 0). The window recomposer is installed with
     * [AppMotionScale], which runs every animation on real time (scale 1) instead of finishing it in the first frame, so no
     * animation is skipped and every token that would travel has to say what it does instead: a position, offset, size,
     * scale or rotation ([travel]) snaps to its end and the state change is shown by the fade that goes with it; a progress
     * that is drawn as a crossfade ([page], [sibling], [morph]) runs the short [reducedFade]. Color and alpha ([effects])
     * are the same in both modes.
     */
    val reduced: Boolean get() = platformScale == 0f

    /** [spec] for something that moves (position, offset, size, scale, rotation); under reduced motion it snaps to its end. */
    fun <T> travel(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (reduced) snap() else spec

    /** Page progress runs 0..1; a 0.001 threshold avoids the 1% end jump of the default. */
    val page: FiniteAnimationSpec<Float> get() = if (reduced) reducedFade() else spring(PageDamping, PageStiffness * soften, visibilityThreshold = 0.001f)
    fun pageOffset(): FiniteAnimationSpec<IntOffset> = travel(spring(PageDamping, PageStiffness * soften, IntOffset(1, 1)))
    fun <T> spatial(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.78f, stiffness = 420f * soften))
    fun spatialOffset(): FiniteAnimationSpec<IntOffset> = travel(spring(0.86f, 420f * soften, IntOffset(1, 1)))
    /** Sibling pages (another pillar) travel sideways: the page spring, slightly firmer so a quick series stays crisp. */
    val sibling: FiniteAnimationSpec<Float> get() = if (reduced) reducedFade() else spring(0.94f, 330f * soften, visibilityThreshold = 0.0005f)
    /** Fold/unfold arrival (G-09) uses [page]; the reduced-motion form is [reducedFade]. */
    /** The dock's Agente item growing into the panel: a container transform with a little mass. */
    val morph: FiniteAnimationSpec<Float> get() = if (reduced) reducedFade() else spring(0.9f, 300f * soften, visibilityThreshold = 0.001f)
    /** Selection indicator (island capsule, segmented choices): k520/ζ0.62, about 8% overshoot, clipped at the ends by the caller. */
    fun <T> selector(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.62f, stiffness = 520f * soften))
    /** Gauges and bars growing from their base (DESIGN "New tokens"): k170/ζ0.72. Capped values clamp at the track. */
    fun <T> fill(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.72f, stiffness = 170f * soften))
    /** A chart line drawing in (trim 0..1): k170/ζ0.9, so it never overshoots the real end. */
    val chartLine: FiniteAnimationSpec<Float> get() = travel(spring(0.9f, 170f * soften, visibilityThreshold = 0.001f))
    /** Chart updates and basis toggles retarget with velocity: k420/ζ0.78 (same as [spatial]). */
    fun <T> chartUpdate(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.78f, stiffness = 420f * soften))
    /** Stagger between bars growing in a chart. */
    const val BarStaggerMs = 20L
    /**
     * Charts enter once per appearance, when the pushed page is about 60% in (DESIGN "Chart enter: from push p ≥ 0.6").
     * On the critically damped [page] spring (k380/ζ0.96) p reaches 0.6 after ≈ 0.10 s.
     */
    const val ChartEnterDelayMs = 100L
    /** Elastic release after a press: k700/ζ0.5. */
    fun <T> release(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.5f, stiffness = 700f * soften))
    /** Reduced motion: the final state with a short crossfade instead of travel. */
    const val ReducedFadeMs = 120
    fun <T> reducedFade(): FiniteAnimationSpec<T> = tween(ReducedFadeMs)
    /** The Agente panel's veil over the pane beneath is complete at this morph progress, before the panel's own content starts to show. */
    const val AgentVeilUntil = 0.25f
    fun <T> press(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow * 2.6f))
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1400f)

    /*
     * Period swipe (G-11), the Xtraordinary ticket-selector physics (MagneticPager.kt, MIT; see assets/licenses/
     * Xtraordinary-MIT.txt): the content follows the finger 1:1, a release past [PeriodThreshold] of a page (the pager's
     * snapPositionalThreshold) or a fling faster than [PeriodFlingDps] commits, and the settle is the snap spring's ζ 0.78.
     * One gesture moves at most one period (PagerSnapDistance.atMost(1)); the release velocity only feeds the settle spring.
     */
    fun <T> periodSettle(): FiniteAnimationSpec<T> = travel(spring(dampingRatio = 0.78f, stiffness = 600f * soften))
    const val PeriodThreshold = 0.42f
    /** The minimum fling, in dp per second (the pager's default fling threshold). */
    const val PeriodFlingDps = 400f
        /** Rubber band past the first or last period: x·L/(x+L) pages, 1:1 at the edge and never beyond [PeriodRubber]. */
    const val PeriodRubber = 0.3f
    /**
     * Xtraordinary's resistance (MagneticSwipe.kt:31-41, MagneticPager.kt:44-48,73-99,108-110): below [PeriodThreshold] the page is drawn at the
     * finger for the first [PeriodFreeTravel] of a page, then at [PeriodFreeTravel] + [PeriodResistedTravel]·sin(...) of the rest (never ahead
     * of the finger); a [PeriodResistanceMs] tween releases the resistance at the threshold (the page catches up) and restores it on the way
     * back; after a committed settle the page recoils [PeriodKick] of a page for [PeriodKickOutMs], overshoots [PeriodKickBack], rests in [PeriodKickInMs].
     */
    const val PeriodFreeTravel = 0.08f
    const val PeriodResistedTravel = 0.48f
    const val PeriodResistanceMs = 90
    const val PeriodKick = 0.018f
    const val PeriodKickBack = 0.006f
    const val PeriodKickOutMs = 70
    const val PeriodKickInMs = 100
    /**
     * A neighbouring period at rest (G-11, amended 2026-11-08): its alpha and how much of it shows. Neighbours are unscaled (G-11), although
     * Xteink's default (expressive) theme scales them 0.986/0.99: a design decision here, not a theme switch.
     */
    const val PeriodPeekAlpha = 0.6f
    val PeriodPeek = 28.dp
    val PeriodPeekWide = 40.dp
    /** The clear space between the current page and the visible part of its neighbour. */
    val PeriodPeekGap = 8.dp
    /** A strip page that draws a tonal tile (the hero month on the accent card): its radius and the content inset inside it. */
    val PeriodSurfaceRadius = 16.dp
    val PeriodSurfacePad = 10.dp
    fun periodPeek(wide: Boolean) = if (wide) PeriodPeekWide else PeriodPeek
    /** The side inset a swiping block needs for its peek; a page whose gutter is at least this wide loses no width. */
    fun periodInset(wide: Boolean) = periodPeek(wide) + PeriodPeekGap
    /** A popover ("?", the month calendar) grows from its anchor from this scale on [spatial]. */
    const val PopoverFromScale = 0.86f
    /** A dragged card (popover, income card) is dismissed past this fraction of its size or by a fling. */
    const val DismissFraction = 0.35f
    /** Swipe to decide (Spese lazy-UX #2/#9): a release faster than this, in dp per second, commits. */
    const val SwipeFlingDps = 900f
    /** Swipe to decide: the rubber band, as a fraction of the row, on a side without a decision. */
    const val SwipeRubber = 0.08f
    /** Swipe to decide: if the decided row is still there after this (the commit was refused), it springs back. */
    const val SwipeReturnMs = 900L

    /** Parent page shift while a child is on top, as a fraction of the width. */
    const val ParentShift = 0.28f
    /** Darkening of the parent page under a fully opened child. */
    const val ParentDim = 0.10f
    const val PressedScale = 0.965f
    /** The island's round buttons and capsule items. */
    const val RoundPressedScale = 0.93f
    /** Tint over the stationary left pane while the Agente panel is open on the open Fold. */
    const val AgentTint = 0.22f
}

/** Peers (top-level destinations): the old content leaves quickly, the new one arrives just after with a small settle. */
fun <S> fadeThrough(reduce: Boolean): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    if (reduce) reducedSwap()
    else (fadeIn(tween(210, 90)) + scaleIn(Motion.spatial(), initialScale = 0.985f)) togetherWith fadeOut(tween(90))
}

/** Reduced motion for a swap of content: the old fades out, the new fades in, and the container takes the new size at once. */
fun reducedSwap(): ContentTransform =
    ContentTransform(fadeIn(tween(Motion.ReducedFadeMs)), fadeOut(tween(80)), sizeTransform = SizeTransform(clip = true) { _, _ -> snap() })

/** What a block that appears in place does by default (grow and fade), and under reduced motion only fade. */
fun appear(reduce: Boolean): EnterTransition = if (reduce) fadeIn(Motion.reducedFade()) else fadeIn() + expandIn()
fun vanish(reduce: Boolean): ExitTransition = if (reduce) fadeOut(Motion.reducedFade()) else fadeOut() + shrinkOut()

/**
 * The animation scale of every window's recomposer. Compose multiplies the time of each animation started from a
 * composition by this scale, and with "Remove animations" (system scale 0) it finishes them all in their first frame, so a
 * crossfade could never be seen. Here reduced motion reads as 1: the animations run on real time, and the tokens in
 * [Motion] (which see [Motion.reduced]) decide what travels (nothing) and what fades. Any other system scale is passed
 * through unchanged, so normal motion is exactly what Compose would have done.
 */
internal class AppMotionScale : MotionDurationScale {
    override val scaleFactor: Float get() = effective(Motion.platformScale)
    companion object { fun effective(system: Float): Float = if (system == 0f) 1f else system }
}

/**
 * Installs [AppMotionScale] on every window recomposer of the process (the policy is process-wide and read when the first
 * ComposeView of a window attaches) and keeps [Motion.platformScale] live from the system setting. Called once from the Application.
 */
@OptIn(InternalComposeUiApi::class)
fun installAppMotion(context: Context) {
    val cr = context.applicationContext.contentResolver
    Motion.platformScale = animatorScale(cr)
    cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false,
        object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { Motion.platformScale = animatorScale(cr) }
        })
    val scale = AppMotionScale()
    WindowRecomposerPolicy.setFactory(object : WindowRecomposerFactory {
        override fun createRecomposer(windowRootView: View): Recomposer = windowRootView.createLifecycleAwareWindowRecomposer(coroutineContext = scale)
    })
}

/**
 * Real time for the reduced-motion crossfade. "Remove animations" sets the animator duration scale to 0 and Compose then
 * finishes every animation (tween or spring) in its first frame, so a crossfade run under the system scale is never seen.
 * The crossfade is a fixed [Motion.ReducedFadeMs] tween on a scale of 1: only alpha moves, and the state is already final
 * for input and semantics while it runs.
 */
internal object RealTime : MotionDurationScale { override val scaleFactor: Float get() = 1f }

suspend fun <R> withRealTime(block: suspend CoroutineScope.() -> R): R = withContext(RealTime, block)

/**
 * One move of a 0..1 progress (a page, a pane, a panel): [spec] normally (with [initialVelocity] when given), and under
 * reduced motion the fixed crossfade on [RealTime], whatever the system scale, so the change is always seen as a fade.
 */
suspend fun Animatable<Float, AnimationVector1D>.travelTo(
    target: Float, reduce: Boolean, spec: FiniteAnimationSpec<Float>, initialVelocity: Float? = null,
) {
    when {
        reduce -> withRealTime { animateTo(target, Motion.reducedFade()) }
        initialVelocity == null -> animateTo(target, spec)
        else -> animateTo(target, spec, initialVelocity)
    }
}

/** animateFloatAsState for a selection or presence value: [spec] normally, the real-time crossfade under reduced motion. */
@Composable
fun animateMotion(target: Float, reduce: Boolean, spec: FiniteAnimationSpec<Float>): State<Float> {
    val a = remember { Animatable(target) }
    LaunchedEffect(target, reduce) { a.travelTo(target, reduce, spec) }
    return a.asState()
}

/** Android's "Remove animations" (animator scale 0), observed live from the root. */
val LocalReduceMotion = staticCompositionLocalOf { false }

private fun animatorScale(cr: android.content.ContentResolver) = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
private fun reduceMotionNow(cr: android.content.ContentResolver) = animatorScale(cr).also { Motion.platformScale = it } == 0f

@Composable
fun observeReduceMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    var off by remember { mutableStateOf(reduceMotionNow(cr)) }
    DisposableEffect(cr) {
        val watcher = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { off = reduceMotionNow(cr) }
        }
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, watcher)
        onDispose { cr.unregisterContentObserver(watcher) }
    }
    return off
}

/**
 * One haptic vocabulary (Cattedra's table), all platform constants so system haptic settings apply:
 * tick = choosing among peers, toggle, confirm = saved/committed, reject = refused or at a limit,
 * threshold = a gesture crosses its commit point. Navigating, scrolling and closing do not vibrate.
 */
class Hx(private val h: HapticFeedback, private val context: android.content.Context? = null) : HapticSink {
    /** Threshold-haptic tokens, taken from Xtraordinary's MagneticPager.kt (MIT): scales, delay, tick spacing. */
    companion object Commit {
        const val DragStartScale = 0.18f
        const val TickScaleFar = 0.18f   // distance tick at the start of the approach
        const val TickScaleNear = 0.62f  // ... rising to this at the threshold (0.18 + 0.44 * approach)
        const val SettleScale = 0.34f
        const val ThresholdTickScale = 0.72f
        const val ThresholdClickScale = 1f
        const val ThresholdClickDelayMs = 20
        const val QuietSamplesAfterCrossing = 8
        const val TickSpacingFar = 0.065f   // page-fraction of travel between ticks far from the threshold
        const val TickSpacingSlope = 0.037f // spacing = TickSpacingFar - approach * TickSpacingSlope
        const val ReferenceThreshold = 0.42f // the reference's commit fraction the spacings are measured against
    }

    private val vibrator: android.os.Vibrator? by lazy(LazyThreadSafetyMode.NONE) {
        context?.let { c ->
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) c.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else c.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }

    private fun touchEnabled() = context?.let { Settings.System.getInt(it.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 } ?: true

    /** A refused vibration (no VIBRATE permission, a revoked service) is a miss, never a crash: the caller falls back to performHapticFeedback. */
    private inline fun safely(play: () -> Unit): Boolean = try { play(); true } catch (_: SecurityException) { false }

    private fun primitive(scale: Float): Boolean {
        val v = vibrator ?: return false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || !v.hasVibrator() || !touchEnabled()) return false
        val p = android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        if (!v.areAllPrimitivesSupported(p)) return false
        return safely { v.vibrate(android.os.VibrationEffect.startComposition().addPrimitive(p, scale.coerceIn(0f, 1f)).compose()) }
    }

    private fun snap(): Boolean {
        val v = vibrator ?: return false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || !v.hasVibrator() || !touchEnabled()) return false
        val low = android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        val click = android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
        if (!v.areAllPrimitivesSupported(low, click)) return false
        return safely { v.vibrate(android.os.VibrationEffect.startComposition().addPrimitive(low, ThresholdTickScale).addPrimitive(click, ThresholdClickScale, ThresholdClickDelayMs).compose()) }
    }

    /** Silent when the system touch haptics are off; the platform fallback otherwise honours the setting itself. */
    private fun fallbackAllowed() = touchEnabled()

    override fun commitPoint() { if (!snap() && fallbackAllowed()) h.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    override fun thresholdOut() = commitPoint() // the reference plays the same snap when the drag leaves the threshold
    override fun dragStart() { if (!primitive(DragStartScale) && fallbackAllowed()) h.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
    override fun distanceTick(scale: Float) { if (!primitive(scale) && fallbackAllowed()) h.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
    override fun settle() { if (!primitive(SettleScale) && fallbackAllowed()) h.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }

    fun tick() = h.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun tap() = h.performHapticFeedback(HapticFeedbackType.ContextClick)
    fun toggle(on: Boolean) = h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun confirm() = h.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = h.performHapticFeedback(HapticFeedbackType.Reject)
    fun threshold() = h.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
}

@Composable
fun rememberHx(): Hx { val h = LocalHapticFeedback.current; val c = LocalContext.current; return remember(h, c) { Hx(h, c) } }

/** Animate the alpha of one color, never to/from Color.Transparent (that interpolates through black and flashes gray). */
@Composable
fun animateFill(color: Color, shown: Boolean, label: String = "fill"): State<Color> =
    animateColorAsState(if (shown) color else color.copy(alpha = 0f), Motion.effects(), label = label)
