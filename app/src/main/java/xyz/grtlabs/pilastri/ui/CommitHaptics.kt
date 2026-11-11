package xyz.grtlabs.pilastri.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs

/** What a gesture with a commit point can feel. Implemented by [Hx]; faked in tests. */
interface HapticSink {
    fun commitPoint()
    fun thresholdOut()
    fun dragStart()
    fun distanceTick(scale: Float)
    fun settle()
}

/**
 * THE threshold haptic (Xtraordinary MagneticPager): a light tick at grab, ticks that grow and come closer as the drag
 * nears the commit point, one snap when it crosses, one when it comes back, a settle tick when a changed target comes to rest ([settled]).
 * [progress] takes the drag as a fraction of the full travel; [threshold] is the commit point in the same unit.
 * No Android calls here: everything goes through [sink].
 */
class CommitHaptics(private val sink: HapticSink, val threshold: Float) {
    private var beyond = false
    private var last = 0f
    private var travelSinceTick = 0f
    private var quiet = 0

    fun start() {
        beyond = false; last = 0f; travelSinceTick = 0f; quiet = 0
        sink.dragStart()
    }

    fun progress(fraction: Float) {
        val p = abs(fraction)
        travelSinceTick += abs(p - last)
        last = p
        val now = p >= threshold
        if (now != beyond) {
            beyond = now
            if (now) sink.commitPoint() else sink.thresholdOut()
            travelSinceTick = 0f
            quiet = Hx.QuietSamplesAfterCrossing
        } else if (!beyond && quiet == 0) {
            val approach = (p / threshold).coerceIn(0f, 1f)
            // Spacing is the reference's, measured against its 0.42 commit fraction, so it scales with any threshold.
            val spacing = (Hx.TickSpacingFar - approach * Hx.TickSpacingSlope) * threshold / Hx.ReferenceThreshold
            if (travelSinceTick >= spacing) {
                sink.distanceTick(Hx.TickScaleFar + approach * (Hx.TickScaleNear - Hx.TickScaleFar))
                travelSinceTick = 0f
            }
        } else if (quiet > 0) quiet -= 1
    }

    /**
     * The finger lets go (moment one). A commit that never crossed live (a fling, or a system-driven back) plays its snap now, because
     * the commit is what becomes visible; a drag released beyond the point without committing plays the way-back snap, because the
     * resistance returns. Nothing else: a commit that crossed live already snapped there. Exactly one event per state change.
     */
    fun release(committed: Boolean) {
        if (committed && !beyond) sink.commitPoint()
        else if (!committed && beyond) sink.thresholdOut()
        beyond = false
    }

    /** The gesture was cancelled (the reference's thresholdOut when it was beyond, nothing otherwise). */
    fun cancel() = release(committed = false)

    /** The motion stops on a different target (moment two): the settle tick, only for gestures that show a separate arrival. */
    fun settled() = sink.settle()
}

@Composable
fun rememberCommitHaptics(threshold: Float): CommitHaptics {
    val hx = rememberHx()
    return remember(hx, threshold) { CommitHaptics(hx, threshold) }
}
