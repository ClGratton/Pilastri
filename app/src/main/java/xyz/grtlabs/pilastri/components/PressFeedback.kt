package xyz.grtlabs.pilastri.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import xyz.grtlabs.pilastri.ui.pressable

/** Press feedback is ONE implementation, [pressable] (ui/Press.kt): this only passes the parameters (no click, the caller owns it). */
fun Modifier.immediatePress(shape: Shape, tint: Color, enabled: Boolean = true): Modifier =
    pressable(shape, tint, onClick = null, enabled = enabled)

@Composable
fun Modifier.actionFeedback(enabled: Boolean = true, haptics: Boolean = true): Modifier {
    val hapticFeedback = LocalHapticFeedback.current
    return pressable(MaterialTheme.shapes.small, MaterialTheme.colorScheme.primary, onClick = null, enabled = enabled,
        haptic = if (enabled && haptics) ({ hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick) }) else null)
}
