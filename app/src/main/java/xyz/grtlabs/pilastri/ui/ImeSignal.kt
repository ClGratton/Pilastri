package xyz.grtlabs.pilastri.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * DESIGN G-15: the one signal for "the keyboard is, or is going to be, shown". It reads the keyboard's animation TARGET, which
 * changes in the first frame of a show or a hide, not the animated inset (which reaches 0 only when the keyboard has gone, so a
 * control gated on it started its own entrance a second late). Every control hidden for the keyboard (the setup dock, the cover
 * dock) and the body padding that follows it use this and nothing else; a source rule bans `WindowInsets.ime.getBottom` elsewhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun keyboardShownOrComing(): Boolean = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
