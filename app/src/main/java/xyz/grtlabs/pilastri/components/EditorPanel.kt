package xyz.grtlabs.pilastri.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Shared phone/Fold editor: scrollable content, reachable actions and one owner for insets. */
@Composable
fun EditorPanel(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "Salva",
    saving: Boolean = false,
    content: @Composable ColumnScope.(wide: Boolean) -> Unit
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(saving) { if (saving) focus.clearFocus(force = true) }
    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 600.dp
            DisposableEffect(dialogWindow, wide) {
                dialogWindow?.let { window ->
                    window.setDimAmount(if (wide) .24f else 0f)
                    window.isNavigationBarContrastEnforced = false
                    @Suppress("DEPRECATION")
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = true
                        isAppearanceLightNavigationBars = true
                    }
                }
                onDispose { }
            }
            Surface(
                modifier = if (wide) Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f).heightIn(max = 720.dp)
                    else Modifier.fillMaxSize(),
                shape = if (wide) RoundedCornerShape(32.dp) else RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = !saving,
                            modifier = Modifier.width(72.dp).heightIn(min = 48.dp).actionFeedback(!saving)) { Text("Chiudi") }
                        Text(title, Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(72.dp))
                    }
                    Column(Modifier.weight(1f).pointerInput(saving) {
                        if (saving) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }.verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 28.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) { content(wide) }
                    Box(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 12.dp)) {
                        Button(onClick = onConfirm, enabled = !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).actionFeedback(!saving),
                            shape = RoundedCornerShape(20.dp)) {
                            Text(if (saving) "Salvataggio…" else confirmLabel, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        spring(dampingRatio = .85f, stiffness = 450f), label = "editorChoice")
    Surface(color = background, shape = RoundedCornerShape(16.dp),
        modifier = modifier.heightIn(min = 48.dp).actionFeedback(enabled)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
                    else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
