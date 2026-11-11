package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*

/** A scrolling page body with the boards' 16dp side margins; [footer] stays pinned below (the agent composer). */
@Composable
fun Page(
    modifier: Modifier = Modifier, spacing: Dp = 8.dp, horizontal: Dp = 16.dp, top: Dp = 8.dp,
    footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).pageContentTag().verticalScroll(rememberScrollState()).padding(start = horizontal, end = horizontal, top = top, bottom = 16.dp + if (footer == null) LocalDockInset.current else 0.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) { content() }
        if (footer != null) Box(Modifier.padding(start = horizontal, end = horizontal, bottom = 8.dp + LocalDockInset.current, top = 4.dp)) { footer() }
    }
}

/** Presence change without a cut: grows in on the page spring and the content below slides; reduced motion fades. */
@Composable
fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val size = androidx.compose.animation.core.spring(Motion.PageDamping, Motion.PageStiffness, androidx.compose.ui.unit.IntSize(1, 1))
    androidx.compose.animation.AnimatedVisibility(
        visible, modifier,
        enter = if (reduce) androidx.compose.animation.fadeIn(Motion.reducedFade()) else androidx.compose.animation.expandVertically(size, expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(Motion.page),
        exit = if (reduce) androidx.compose.animation.fadeOut(Motion.reducedFade()) else androidx.compose.animation.shrinkVertically(size, shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(Motion.page),
    ) { content() }
}

/** Header of a tab root: the shared [PageHeader] (G-12); every page has the same title style, subtitle style and height. */
@Composable
fun RootHeader(
    title: String, subtitle: String?, modifier: Modifier = Modifier,
    strip: PeriodState? = null, subtitleOf: ((Int) -> String?)? = null, trailing: (@Composable () -> Unit)? = null,
) = PageHeader(title, subtitle, modifier, trailing = trailing?.let { t -> { t() } }, strip = strip, subtitleOf = subtitleOf)

fun sampleSuffix(a: Archive) = if (a.isSample) " · Dati di esempio" else ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.card,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Choose a spending category; "Nuova categoria" creates one inline. Calls [onPick] with null to clear. */
@Composable
fun CategorySheet(app: App, title: String, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit, allowClear: Boolean = true) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    var newName by rememberSaveable { mutableStateOf("") }
    Sheet(onDismiss) {
        Text(title, style = Type.screenTitle, color = pal.ink)
        val cats = a.planning.categories.filter { it.kind == CategoryKind.SPEND }
        RowsCard {
            cats.forEach { c -> ListRow(c.name, null, { onPick(c.id) }, selected = c.id == current, chevron = false, value = if (c.id == current) "scelta" else null) }
            if (allowClear) ListRow("Senza categoria", null, { onPick(null) }, selected = current == null, chevron = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(newName, { newName = it }, "Nuova categoria", Modifier.weight(1f))
            PillButton("Aggiungi", {
                val name = newName.trim()
                if (name.isNotEmpty()) app.commit("Categoria aggiunta.") { it.withNewCategory(name).first }
                newName = ""
            }, kind = Btn.TONAL, enabled = newName.isNotBlank())
        }
    }
}

/** A plain rounded text field in the kit's style (no floating label: the label sits above). */
@Composable
fun TextInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false, center: Boolean = false, big: Boolean = false) {
    val pal = LocalPal.current
    Column(modifier) {
        Text(label, style = Type.tinyStrong, color = pal.ink2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(pal.inset).border(1.dp, pal.btnLine, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
            textStyle = (if (big) Type.secondaryFigure else Type.body).copy(color = pal.ink, textAlign = if (center) TextAlign.Center else TextAlign.Start),
            cursorBrush = SolidColor(pal.brand), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        )
    }
}

/** Segmented choice with a pill that travels on a spring (Xtraordinary model: only the settled choice is reported). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val pos by androidx.compose.animation.core.animateFloatAsState(selected.toFloat(), Motion.spatial(), label = "seg")
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(pal.inset).border(1.dp, pal.btnLine, RoundedCornerShape(50))) {
        val cell = maxWidth / options.size
        Box(Modifier.matchParentSize()) { Box(Modifier.offset(x = cell * pos).width(cell).fillMaxHeight().clip(RoundedCornerShape(50)).background(pal.selected)) }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp).pillTag().pressable(RoundedCornerShape(50), pal.pressTint, onClick = { if (i != selected) { hx.tick(); onSelect(i) } }, role = androidx.compose.ui.semantics.Role.RadioButton)
                    .then(if (i == selected) Modifier.selectedTag() else Modifier)
                    .semantics { this.selected = i == selected }, contentAlignment = Alignment.Center) {
                    Text(o, style = if (i == selected) Type.button else Type.body, color = if (i == selected) pal.onTonal else pal.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/** A value typed by the user: amount, date today, a source line. Used for accounts without a connector. */
@Composable
fun AmountSheet(title: String, hint: String, initial: String = "", confirm: String = "Salva", onSave: (Long) -> Unit, onDismiss: () -> Unit, allowEmpty: Boolean = false, onClear: (() -> Unit)? = null) {
    val pal = LocalPal.current
    var text by rememberSaveable { mutableStateOf(initial) }
    val cents = parseEuroInput(text)
    Sheet(onDismiss) {
        Text(title, style = Type.screenTitle, color = pal.ink)
        Text(hint, style = Type.body, color = pal.ink2)
        TextInput(text, { text = it }, "Importo in euro", number = true, center = true, big = true)
        (readEuroInput(text) as? EuroInput.Invalid)?.takeIf { text.isNotBlank() }?.let { Text(it.message, style = Type.small, color = pal.overInk) }
        RoleActionRow("amount", spacing = 8.dp, end = true,
            secondary = onClear?.let { clear -> { m -> PillButton("Rimuovi", { clear(); onDismiss() }, m, kind = Btn.OUTLINE) } },
            confirm = { m -> PillButton(confirm, { if (cents != null) { onSave(cents); onDismiss() } }, m, kind = Btn.PRIMARY, enabled = cents != null || allowEmpty) })
    }
}

/** What a typed amount means for a figure that now shows [InlineAmountField]'s current value. */
sealed interface InlineEdit {
    data object Unchanged : InlineEdit
    data class Save(val cents: Long) : InlineEdit
    data object Clear : InlineEdit
    data class Invalid(val message: String) : InlineEdit
}

/** Pure rule of the inline edit: same value or nothing to clear is [InlineEdit.Unchanged]; blank clears only when [allowEmpty]; the Italian amount rules are [readEuroInput]'s. */
fun resolveInlineAmount(text: String, currentCents: Long?, allowEmpty: Boolean): InlineEdit {
    if (text.isBlank()) return when {
        !allowEmpty -> InlineEdit.Invalid("Inserisci un importo.")
        currentCents == null -> InlineEdit.Unchanged
        else -> InlineEdit.Clear
    }
    return when (val r = readEuroInput(text)) {
        is EuroInput.Ok -> if (r.cents == currentCents) InlineEdit.Unchanged else InlineEdit.Save(r.cents)
        is EuroInput.Invalid -> InlineEdit.Invalid(r.message)
    }
}

/** The text an inline edit starts from: "1500" or "1500,50" (no grouping dots, so it reads back to the same cents); "" for no value. */
fun inlineEditText(cents: Long?): String {
    if (cents == null) return ""
    val abs = kotlin.math.abs(cents)
    val whole = (abs / 100).toString()
    val frac = (abs % 100).toInt()
    return (if (cents < 0) "-" else "") + whole + if (frac == 0) "" else "," + frac.toString().padStart(2, '0')
}

/** From this font scale up the figure edits in [AmountSheet]: an inline field would not fit the large text. */
fun useSheetFallback(fontScale: Float): Boolean = fontScale >= 1.5f

/**
 * A big figure edited in place: tap [shown] (dashed underline), type the Italian way, Done/check/leaving the field saves via [onSave]
 * (the confirm haptic is the caller's save, e.g. App.commit); invalid on Done keeps it open with the reason, invalid on leaving discards.
 * [allowEmpty] with [onClear] lets a blank clear it; large text opens the [AmountSheet] instead.
 */
@Composable
fun InlineAmountField(
    cents: Long?, label: String, onSave: (Long) -> Unit, modifier: Modifier = Modifier, style: TextStyle = Type.figure,
    shown: String = cents?.let { Fmt.eurCents(it) } ?: "—", allowEmpty: Boolean = false, onClear: (() -> Unit)? = null, sheetHint: String = "",
) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val reduce = LocalReduceMotion.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fontScale = LocalDensity.current.fontScale
    var editing by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    val canClear = allowEmpty && onClear != null
    fun begin() {
        if (useSheetFallback(fontScale)) { sheet = true; return }
        val t = inlineEditText(cents)
        field = TextFieldValue(t, TextRange(0, t.length)); error = null; editing = true
    }
    fun finish(fromDone: Boolean) {
        if (!editing) return
        when (val r = resolveInlineAmount(field.text, cents, canClear)) {
            InlineEdit.Unchanged -> editing = false
            is InlineEdit.Save -> { editing = false; onSave(r.cents) }
            InlineEdit.Clear -> { editing = false; onClear?.invoke() }
            is InlineEdit.Invalid -> if (fromDone) { error = r.message; hx.reject() } else editing = false
        }
        if (!editing) { keyboard?.hide(); focusManager.clearFocus() }
    }
    Column(modifier) {
        AnimatedContent(editing, transitionSpec = fadeThrough(reduce), label = "inline-amount") { on ->
            if (!on) {
                val line = pal.ink2.copy(alpha = 0.7f)
                Row(
                    Modifier.heightIn(min = 48.dp).pressable(RoundedCornerShape(12.dp), pal.pressTint, onClick = { begin() }, haptic = { hx.tap() })
                        .semantics { contentDescription = "$label: $shown. Modifica" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(shown, style = style, color = pal.ink, modifier = Modifier.drawBehind {
                        val y = size.height - 1.dp.toPx()
                        drawLine(line, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                    })
                }
            } else {
                val focus = remember { FocusRequester() }
                var hadFocus by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        field, { field = it; error = null },
                        Modifier.widthIn(min = 96.dp, max = 240.dp).focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) { hadFocus = false; finish(false) } }
                            .drawBehind { drawLine(pal.brand, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx()) }
                            .semantics { contentDescription = "$label, importo in euro" },
                        textStyle = style.copy(color = pal.ink), cursorBrush = SolidColor(pal.brand), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finish(true) }),
                    )
                    Text("€", style = style, color = pal.ink2, modifier = Modifier.padding(start = 6.dp))
                    RoundIcon(Ic.check, "Salva", { finish(true) })
                }
            }
        }
        AnimatedVisibility(editing && error != null, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
            Text(error ?: "", style = Type.small, color = pal.overInk, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    if (sheet) AmountSheet(
        label, sheetHint, initial = inlineEditText(cents), onSave = onSave, onDismiss = { sheet = false },
        allowEmpty = canClear, onClear = if (canClear) onClear else null,
    )
}

private val PullLimit = 96.dp
private val PullThreshold = 56.dp

/** The rubber-band distance for a raw pull: [raw]·[limit]/([raw]+[limit]), 1:1 at the start and never beyond [limit]. */
fun pullDistance(raw: Float, limit: Float): Float = if (raw <= 0f || limit <= 0f) 0f else limit * raw / (raw + limit)

/** True when the rubber-banded pull has reached the [threshold] (releasing now refreshes). */
fun pullArmed(raw: Float, threshold: Float, limit: Float): Boolean = pullDistance(raw, limit) >= threshold

/**
 * Pull the page down at its top to refresh ([enabled] false: no pull). The content follows the finger on a rubber band and settles on
 * [Motion.spatial] when released; a thin line shows the pull and then the refresh ([refreshing]). Reduced motion keeps the line, not the travel.
 */
@Composable
fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val pal = LocalPal.current
    val ch = rememberCommitHaptics(PullThreshold.value / PullLimit.value) // fraction of the pull limit at which releasing refreshes
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val limit = with(density) { PullLimit.toPx() }
    val threshold = with(density) { PullThreshold.toPx() }
    val canPull by rememberUpdatedState(enabled && !refreshing)
    val latestRefresh by rememberUpdatedState(onRefresh)
    var drag by remember { mutableFloatStateOf(0f) }
    val pulling by remember { derivedStateOf { drag > 0f } }
    val connection = remember(limit, threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || drag <= 0f || available.y >= 0f) return Offset.Zero
                val take = available.y.coerceAtLeast(-drag)
                drag += take
                ch.progress(pullDistance(drag, limit) / limit)
                return Offset(0f, take)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!canPull || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                if (drag <= 0f) ch.start()
                drag += available.y
                ch.progress(pullDistance(drag, limit) / limit)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0f) return Velocity.Zero
                val commit = canPull && pullArmed(drag, threshold, limit)
                if (commit) latestRefresh()
                ch.release(committed = commit)
                Animatable(drag).animateTo(0f, Motion.spatial()) { drag = value }
                drag = 0f
                return available
            }
        }
    }
    Box(modifier.nestedScroll(connection)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = if (reduce) 0f else pullDistance(drag, limit) }) { content() }
        AnimatedVisibility(refreshing || pulling, Modifier.align(Alignment.TopCenter), enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
            SyncBar(progress = if (refreshing) null else ({ (pullDistance(drag, limit) / threshold).coerceIn(0f, 1f) }))
        }
    }
}

/**
 * "?": explanations live behind this button (AGENTS.md "Copy on screens"), one per section at most, never inline.
 * It opens a small popover growing from the "?" (lazy-UX #10), dismissed by a tap outside, a drag or Back.
 */
@Composable
fun HelpButton(title: String, body: String, modifier: Modifier = Modifier) { // G-12: THE help glyph, 24 dp in a 48 dp target, on every page
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        RoundIcon(Ic.help, "$title: spiegazione", { open = true }, iconSize = 24.dp)
        if (open) InfoDialog(title, body) { open = false }
    }
}

/** A small "Dati di esempio" / provenance line under headers. */
@Composable
fun SourceNote(text: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Text(text, style = Type.tiny, color = pal.ink2, modifier = modifier.padding(horizontal = 6.dp))
}

/** A dot plus a word: freshness and states are never colour only. */
@Composable
fun Dot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))
