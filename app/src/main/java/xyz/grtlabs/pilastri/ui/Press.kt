package xyz.grtlabs.pilastri.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Physical press: on pointer DOWN the surface sinks (scale and tint) within the same frame, observed in the Initial
 * pass so a scrolling parent cannot delay it; release springs back with a small rebound. A scroll or drag cancels
 * the press without firing. [onClick] runs on release through the normal clickable (keyboard and TalkBack keep working).
 */
fun Modifier.pressable(
    shape: Shape,
    tint: Color,
    onClick: (() -> Unit)?,
    role: Role = Role.Button,
    pressedScale: Float = Motion.PressedScale,
    haptic: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** Where the press scales from: columns sink from their base (DESIGN "Press scales"). */
    origin: androidx.compose.ui.graphics.TransformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val source = remember { MutableInteractionSource() }
    this
        // CR-23: a tappable is at least 48 dp to touch; the visible surface (below, inner) keeps its own size.
        .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value; transformOrigin = origin }
        .clip(shape)
        .drawWithContent { drawContent(); if (glow.value > 0.001f) drawRect(tint.copy(alpha = 0.16f * glow.value)) }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { launch { glow.animateTo(1f, Motion.effects()) }; scale.animateTo(pressedScale, Motion.press()) }
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                if (up != null) haptic?.invoke()
                scope.launch { launch { glow.animateTo(0f, Motion.effects()) }; scale.animateTo(1f, Motion.press()) }
            }
        }
        .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick) else Modifier)
}
