package xyz.grtlabs.pilastri.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.Text

/** What one paste field says about itself; the Enable Banking link and the assistant's reply differ only by these parameters. */
internal data class PasteSpec(
    val placeholder: String,
    val description: String,
    val buttonDescription: String,
    val multiline: Boolean = false,
    val keyboard: KeyboardType = KeyboardType.Uri,
    val fieldTag: String = EbTag.FIELD,
    val buttonTag: String = EbTag.BUTTON,
)

internal val LINK_PASTE = PasteSpec("Link dall'e-mail", "Link dall'e-mail", "Incolla il link copiato")

/**
 * The one paste field (concept "paste field"): a visible editable field with a trailing "Incolla" button inside it. The button is
 * the only thing that reads the clipboard ([onPaste]); this composable reads nothing itself.
 */
@Composable
internal fun PasteField(text: String, onText: (String) -> Unit, onPaste: () -> Unit, onSubmit: () -> Unit, spec: PasteSpec = LINK_PASTE) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(16.dp)
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = if (spec.multiline) 96.dp else 60.dp).clip(shape).background(pal.card).border(1.5.dp, pal.primary, shape).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(text, onText, singleLine = !spec.multiline, maxLines = if (spec.multiline) 6 else 1, textStyle = SType.value.copy(color = pal.ink), cursorBrush = SolidColor(pal.primary),
            keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard, imeAction = if (spec.multiline) ImeAction.Default else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            visualTransformation = if (focused || spec.multiline) VisualTransformation.None else MiddleEllipsis(),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.testTag(spec.fieldTag).semantics { contentDescription = spec.description },
            decorationBox = { inner ->
                Box(contentAlignment = if (spec.multiline) Alignment.TopStart else Alignment.CenterStart, modifier = if (spec.multiline) Modifier.padding(vertical = 14.dp) else Modifier) {
                    if (text.isEmpty()) Text(spec.placeholder, style = SType.value, color = pal.ink2, modifier = Modifier.clearAndSetSemantics { })
                    inner()
                }
            })
        ChipAction("Incolla", spec.buttonDescription, enabled = true, tag = spec.buttonTag, onClick = onPaste)
    }
}

/** The outcome line under a paste field: reserved height, so showing or changing it moves nothing (CR-83). */
@Composable
internal fun PasteOutcomeSlot(outcome: ActionOutcome, tag: String) {
    Box(Modifier.fillMaxWidth().height(48.dp).testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.CenterStart) {
        OutcomeLine(outcome)
    }
}
