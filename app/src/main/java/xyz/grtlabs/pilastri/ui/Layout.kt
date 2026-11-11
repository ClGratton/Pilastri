package xyz.grtlabs.pilastri.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/** How the window is used (Council 1, 2026-10-28). */
sealed interface AppLayout {
    /** Bottom bar, one column (phones, the closed Fold, split-screen). */
    data object Compact : AppLayout
    /** One column with the floating island on the right edge: wide but too short or too narrow for two real panes. */
    data object RailSingle : AppLayout
    /** Two panes under the floating island (Council 3, 2026-10-31); [gap] is the hinge's occluded strip, or none. */
    data class TwoPane(val left: Dp, val gap: Dp, val right: Dp, val onHinge: Boolean) : AppLayout
}

const val RailWidthDp = 80f
private const val MinLeft = 300f
private const val MinRight = 360f
private const val Gutter = 0f

/** Council 3 §1 (COUNCIL.md): landscape-shaped turns ON at w > h + 24 dp and OFF at w < h − 24 dp; in between it holds. */
const val LandscapeEnterDp = 24f
const val LandscapeLeaveDp = 24f

/**
 * UX-N2: is the window landscape-shaped, with hysteresis? Beyond the band the shape decides (the open Fold, 832×750, is
 * 82 dp past the ON edge; a portrait tablet is past the OFF edge). Inside the ±24 dp band (a window that is nearly square,
 * e.g. while resizing in split-screen) the previous answer [wasLandscape] holds, so a size hovering near the threshold
 * does not flip the frame back and forth. Pure: the caller keeps [wasLandscape] (see [rememberAppLayout]).
 */
fun landscapeShaped(width: Float, height: Float, wasLandscape: Boolean): Boolean = when {
    width > height + LandscapeEnterDp -> true
    width < height - LandscapeLeaveDp -> false
    else -> wasLandscape
}

/**
 * Two panes only when ≥740×480dp AND landscape-shaped ([landscapeShaped]; width alone never decides). The panes split on
 * the vertical fold's bounds (flat or half-opened) when both sides meet the minima; otherwise in half (416|416 on the
 * open Fold); otherwise one column. A horizontal hinge never splits. [wasTwoPane] is the previous result (hysteresis
 * input; two panes imply landscape): omitted, a nearly square window counts as not landscape.
 * All values in dp, window-relative. No strip or reserved column: the island floats over the right pane's edge.
 */
fun appLayout(width: Float, height: Float, hingeLeft: Float?, hingeWidth: Float?, horizontalHinge: Boolean, wasTwoPane: Boolean = false): AppLayout {
    if (width < 600f) return AppLayout.Compact
    if (width < 740f || height < 480f || horizontalHinge || !landscapeShaped(width, height, wasTwoPane)) return AppLayout.RailSingle
    val content = width
    // D-06: the hinge splits the panes only when it is a real divider: it occludes something (width >= 4 dp) or sits near the
    // middle. The emulator's flat foldable reports a 1 px feature at 337 dp of 832 (an AVD quirk, not a divider): halves.
    val hingeIsDivider = hingeLeft != null && hingeWidth != null &&
        (hingeWidth >= 4f || kotlin.math.abs(hingeLeft + hingeWidth / 2f - width / 2f) <= width * 0.08f)
    if (hingeIsDivider && hingeLeft != null && hingeWidth != null) {
        val left = hingeLeft
        val right = width - hingeLeft - hingeWidth
        if (left >= MinLeft && right >= MinRight) return AppLayout.TwoPane(left.dp, hingeWidth.dp, right.dp, onHinge = true)
    }
    val left = content * 0.5f
    val right = content - left - Gutter
    return if (left >= MinLeft && right >= MinRight) AppLayout.TwoPane(left.dp, Gutter.dp, right.dp, onHinge = false) else AppLayout.RailSingle
}

/**
 * [appLayout] with its hysteresis kept across recompositions: the previous composed frame feeds [landscapeShaped]. Use this
 * instead of calling [appLayout] directly wherever the frame is chosen (AppRoot, SetupFlow).
 */
@Composable
fun rememberAppLayout(width: Float, height: Float, hingeLeft: Float?, hingeWidth: Float?, horizontalHinge: Boolean): AppLayout {
    val was = remember { booleanArrayOf(false) }
    val layout = appLayout(width, height, hingeLeft, hingeWidth, horizontalHinge, wasTwoPane = was[0])
    SideEffect { was[0] = layout is AppLayout.TwoPane }
    return layout
}

/** Folding features as reported by Jetpack WindowManager for this activity. */
@Composable
fun rememberFoldingFeature(): FoldingFeature? {
    val activity = LocalContext.current as ComponentActivity
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(initial = WindowLayoutInfo(emptyList()))
    return info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
}

/**
 * G-08: an entry point whose only job is to reveal what the open Fold already shows as the right-pane root (the Pilastri
 * month row → Mese, Spese → Rate, Futuro → the scenario fan). On two panes it stays composed (its state is kept) but takes
 * no room, is not drawn, takes no touch and leaves the accessibility and focus trees; on one pane it is the normal control.
 * Never a dead button. Reuse it for every such entry: `Modifier.paneEntry(app.twoPane)`.
 */
fun Modifier.paneEntry(hiddenOnPanes: Boolean): Modifier =
    if (!hiddenOnPanes) this
    else this
        .then(Modifier.focusProperties { canFocus = false })
        .clearAndSetSemantics { }
        .layout { measurable, constraints ->
            // Measured (state and layout stay warm) but neither placed nor sized: not drawn, not hit, not focused.
            measurable.measure(constraints)
            layout(0, 0) {}
        }
