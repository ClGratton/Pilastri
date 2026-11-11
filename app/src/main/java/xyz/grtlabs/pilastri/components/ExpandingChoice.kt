// Adapted from Xtraordinary; see assets/licenses/Xtraordinary-MIT.txt.
package xyz.grtlabs.pilastri.components

import xyz.grtlabs.pilastri.ui.Motion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ExpandingChoice(
    val key: String,
    val title: String,
    val body: String,
)

@Composable
fun ExpandingChoiceRow(
    choices: List<ExpandingChoice>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    optionHeight: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedWeight: Float = 1.35f,
    unselectedWeight: Float = 0.75f,
    choiceWidthAnimationSpec: AnimationSpec<Float> = Motion.travel(spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )),
    optionContentPadding: Dp = 14.dp,
    optionContentSpacing: Dp = 6.dp,
    groupContentDescription: String? = null,
    selectedContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    selectedContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    restingContainer: Color = MaterialTheme.colorScheme.surfaceContainer,
    restingContent: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MaterialTheme.shapes.large,
    actionContent: @Composable ColumnScope.(String) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .then(
                if (groupContentDescription == null) Modifier
                else Modifier.semantics { contentDescription = groupContentDescription },
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        choices.forEach { choice ->
            val selected = choice.key == selectedKey
            val weight = animateFloatAsState(
                targetValue = if (selected) selectedWeight else unselectedWeight,
                animationSpec = choiceWidthAnimationSpec,
                label = "${choice.key} option width",
            )
            ExpandingChoiceOption(
                choice = choice,
                selected = selected,
                onSelect = { onSelect(choice.key) },
                enabled = enabled,
                optionHeight = optionHeight,
                selectedContainer = selectedContainer,
                selectedContent = selectedContent,
                restingContainer = restingContainer,
                restingContent = restingContent,
                shape = shape,
                contentPadding = optionContentPadding,
                contentSpacing = optionContentSpacing,
                modifier = Modifier.weight(weight.value),
                actionContent = actionContent,
            )
        }
    }
}

@Composable
private fun ExpandingChoiceOption(
    choice: ExpandingChoice,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean,
    optionHeight: Dp,
    selectedContainer: Color,
    selectedContent: Color,
    restingContainer: Color,
    restingContent: Color,
    shape: Shape,
    contentPadding: Dp,
    contentSpacing: Dp,
    modifier: Modifier = Modifier,
    actionContent: @Composable ColumnScope.(String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val animatedContainer by animateColorAsState(
        targetValue = if (selected) selectedContainer else restingContainer,
        animationSpec = tween(durationMillis = 180),
        label = "${choice.key} option container",
    )
    val animatedContent by animateColorAsState(
        targetValue = if (selected) selectedContent else restingContent,
        animationSpec = tween(durationMillis = 180),
        label = "${choice.key} option content",
    )
    val animatedOutline by animateColorAsState(
        targetValue = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
        animationSpec = tween(durationMillis = 180),
        label = "${choice.key} option outline",
    )
    Surface(
        modifier = modifier.heightIn(min = maxOf(optionHeight, 48.dp)).immediatePress(shape, selectedContent, enabled).selectable(
            selected = selected,
            enabled = enabled,
            role = androidx.compose.ui.semantics.Role.RadioButton,
            onClick = {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                onSelect()
            },
        ),
        color = animatedContainer,
        contentColor = animatedContent,
        shape = shape,
        border = BorderStroke(1.dp, animatedOutline),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(contentSpacing, Alignment.CenterVertically),
        ) {
            Text(choice.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
            if (choice.body.isNotBlank()) Text(
                text = choice.body,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) selectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                textAlign = TextAlign.Center,
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(140)) + expandVertically(
                    animationSpec = Motion.travel(spring(stiffness = Spring.StiffnessMediumLow)),
                    expandFrom = Alignment.Bottom,
                ),
                exit = fadeOut(tween(110)) + shrinkVertically(
                    animationSpec = Motion.travel(spring(stiffness = Spring.StiffnessMediumLow)),
                    shrinkTowards = Alignment.Bottom,
                ),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(contentSpacing)) {
                    actionContent(choice.key)
                }
            }
        }
    }
}
