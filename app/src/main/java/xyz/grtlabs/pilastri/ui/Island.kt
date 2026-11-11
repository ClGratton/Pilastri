package xyz.grtlabs.pilastri.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.ai.AssistantPage
import kotlin.math.roundToInt

/*
 * The floating frame (Council 3 §1 amendments). Open Fold: one island on the right edge (Back, the section capsule,
 * Agente) and Impostazioni alone in the bottom-leading corner. Cover and phone: ONE centred dock holding all five items
 * (user, 2026-10-31: "you have one page at a time, you can have them all in a normal dock").
 * Material of the old floating dock: card at 94%, 1 dp hairline, a soft shadow. Groups are separated only by space.
 */

/** Island geometry, in dp. */
object IslandSpec {
    val Margin = 12.dp
    /** The island column: island 56 + a margin each side (80). The island is centred in it, [Margin] either side. */
    val ColumnWidth: Dp get() = AgentFold + Margin * 2
    val GroupGap = 12.dp
    /** Left-pane scroll padding under the Impostazioni corner button (Page adds 16). */
    val FoldScrollInset = 68.dp
    /** Cover page room above the navigation bar: dock 64 + its 8 above the bar (Page adds 16 of air above the dock). */
    val CoverScrollInset = 72.dp
    /** Cover Agente page: room above the navigation bar for the dock (64 + its 8 + 8). */
    val CoverPageInset = 80.dp
    /** The dock's gap above the navigation bar. */
    val DockBottom = 8.dp
    internal val Item = 48.dp
    internal val ItemGap = 4.dp
    internal val CapsulePad = 4.dp
    internal val Back = 48.dp
    internal val AgentFold = 56.dp
    internal val Settings = 48.dp
    /** G-02: the gear's end inset: its centre sits on the island's axis (margin + half of the 56 dp column - half of 48). */
    val GearEnd = Margin + (AgentFold - Settings) / 2
    /** Dock items: the board's 76 dp at most; they narrow (never under [Item]) before anything clips. */
    internal val CoverItemMax = 76.dp
    internal val CoverItemH = 56.dp
}

/** Where the Agente button is, in the frame overlay's coordinates: the panel grows out of it. */
@Stable
class IslandGeometry {
    var overlay: LayoutCoordinates? = null
    var agent by mutableStateOf<Rect?>(null)
}

/** True where the frame shows Back (the open Fold's island): pushed pages then drop their own back button. */
val LocalBackInFrame = staticCompositionLocalOf { false }

/* ---------- Glyphs: the boards' 24-unit paths; selected = heavier stroke (2.25) plus the pill, never colour alone ---------- */

private fun glyph(name: String, d: String, width: Float, fill: String? = null): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    if (fill != null) addPath(PathParser().parsePathString(fill).toNodes(), fill = SolidColor(Color.Black))
    addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

private object IslandGlyph {
    private const val PILLARS = "M4 20h16M6 17V9M10 17V6M14 17V9M18 17V12"
    private const val SPESE = "M4 7h16v12H4zM4 7l2-3h12l2 3M15 13h3"
    private const val FUTURO = "M4 18l5-5 4 3 7-8M15 8h5v5"
    private const val GEAR = "M10.08 4.85L10.40 1.93L13.60 1.93L13.92 4.85L15.70 5.59L18.00 3.75L20.25 6.00L18.41 8.30L19.15 10.08L22.07 10.40L22.07 13.60L19.15 13.92L18.41 15.70L20.25 18.00L18.00 20.25L15.70 18.41L13.92 19.15L13.60 22.07L10.40 22.07L10.08 19.15L8.30 18.41L6.00 20.25L3.75 18.00L5.59 15.70L4.85 13.92L1.93 13.60L1.93 10.40L4.85 10.08L5.59 8.30L3.75 6.00L6.00 3.75L8.30 5.59zM15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z"
    private const val HUB = "M15.5 12a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z"
    val sectionOff = Tab.entries.associateWith { lazy { glyph("off-$it", path(it), 1.75f) } }
    val sectionOn = Tab.entries.associateWith { lazy { glyph("on-$it", path(it), 2.25f) } }
    val agent by lazy { glyph("agent", "M5 5h14v10H9l-4 4z", 1.75f) }
    /** Agente selected as the cover's peer page (G-10): heavier stroke plus the pill, never colour alone. */
    val agentOn by lazy { glyph("agent-on", "M5 5h14v10H9l-4 4z", 2.25f) }
    val close by lazy { glyph("close", "M6 6l12 12M18 6L6 18", 2f) }
    val back by lazy { glyph("back", "M15 6l-6 6 6 6", 2f) }
    val gear by lazy { glyph("gear", GEAR, 1.75f) }
    val gearOn by lazy { glyph("gear-on", GEAR, 2.25f, fill = HUB) }
    private fun path(t: Tab) = when (t) { Tab.PILASTRI -> PILLARS; Tab.SPESE -> SPESE; Tab.FUTURO -> FUTURO }
}

/* ---------- Material and round buttons ---------- */

private fun Modifier.islandShadow(shape: Shape) =
    shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))

private fun Modifier.islandFace(shape: Shape, fill: Color, line: Color) = background(fill.copy(alpha = 0.94f), shape).border(1.dp, line, shape)

/** A round button in the island material; press scales 0.93 on pointer down and springs back. */
@Composable
private fun RoundButton(size: Dp, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean? = null, content: @Composable BoxScope.() -> Unit) {
    val pal = LocalPal.current
    Box(
        modifier.size(size).islandShadow(CircleShape)
            .pressable(CircleShape, pal.pressTint, onClick = onClick, pressedScale = Motion.RoundPressedScale)
            .islandFace(CircleShape, pal.card, pal.cardLine)
            .semantics { contentDescription = label; if (selected != null) this.selected = selected },
        contentAlignment = Alignment.Center, content = content,
    )
}

/** Impostazioni: on the open Fold alone at the bottom, centred on the island's axis (G-02); on the cover it is the dock's fifth item; outline gear, filled while Impostazioni is open. */
@Composable
fun SettingsButton(app: App, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val open = app.nav.settingsOpen
    val on by animateFloatAsState(if (open) 1f else 0f, Motion.effects(), label = "gearOn")
    RoundButton(IslandSpec.Settings, "Impostazioni", { if (open && !app.agentOpen) app.nav = app.nav.leaveSettings() else app.openSettings() }, modifier.testTag(UiTag.GEAR), selected = open) {
        Icon(IslandGlyph.gear, null, Modifier.size(24.dp).graphicsLayer { alpha = 1f - on.coerceIn(0f, 1f) }, tint = pal.tabOff)
        Icon(IslandGlyph.gearOn, null, Modifier.size(24.dp).graphicsLayer { alpha = on.coerceIn(0f, 1f) }, tint = pal.tabOn)
    }
}

private fun agentLabel(app: App, cover: Boolean = false): String {
    val waiting = app.assistant.waiting
    return when { app.agentOpen && !cover -> "Chiudi Agente"; waiting > 0 -> "Agente, $waiting modifiche in attesa"; else -> "Agente" }
}

/** Reports the Agente control's bounds: the panel grows out of them. */
private fun Modifier.agentAnchor(geometry: IslandGeometry) =
    onGloballyPositioned { c -> geometry.overlay?.takeIf { it.isAttached }?.let { o -> geometry.agent = o.localBoundingBoxOf(c, clipBounds = false) } }

/** Agente's glyph: it turns into "×" on springs while the panel is open; a dot while changes wait. */
@Composable
private fun BoxScope.AgentGlyph(app: App) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val t by animateMotion(if (app.agentOpen) 1f else 0f, reduce, Motion.spatial())
    Icon(IslandGlyph.agent, null, Modifier.size(24.dp).graphicsLayer {
        val k = t.coerceIn(0f, 1f)
        alpha = 1f - k; if (!reduce) { rotationZ = 90f * t; scaleX = 1f - 0.4f * k; scaleY = 1f - 0.4f * k }
    }, tint = pal.tabOff)
    Icon(IslandGlyph.close, null, Modifier.size(24.dp).graphicsLayer {
        val k = t.coerceIn(0f, 1f)
        alpha = k; if (!reduce) { rotationZ = -90f * (1f - t); scaleX = 0.6f + 0.4f * t; scaleY = 0.6f + 0.4f * t }
    }, tint = pal.tabOn)
    if (app.assistant.waiting > 0) Box(
        Modifier.align(Alignment.Center).offset(9.dp, (-9).dp).size(6.dp)
            .graphicsLayer { alpha = 1f - t.coerceIn(0f, 1f) }.clip(CircleShape).background(pal.tabOn),
    )
}

/** Agente on the open Fold: an action in its own round button, never a section. */
@Composable
private fun AgentButton(app: App, size: Dp, geometry: IslandGeometry) {
    RoundButton(size, agentLabel(app), { app.toggleAgent() }, Modifier.agentAnchor(geometry)) { AgentGlyph(app) }
}

/**
 * The three sections in a capsule; the pill travels on the selector spring (overshoot clipped at the ends). [vertical]
 * on the open Fold (glyph only), horizontal with labels on the cover.
 */
@Composable
private fun SectionCapsule(selected: Tab, onSelect: (Tab) -> Unit, vertical: Boolean, itemW: Dp, itemH: Dp, labels: Boolean, dot: Tab? = null, pillOn: Boolean = true) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    // U-18: while a page that is not a section (Impostazioni) owns the pane, the pill leaves the capsule (the gear carries the selection).
    val pillK by animateMotion(if (pillOn) 1f else 0f, reduce, Motion.spatial())
    // 3b-03 / G-04: the Spese dot (overspend signal) appears and clears on a spring, never cut.
    val dotK by animateMotion(if (dot != null) 1f else 0f, reduce, Motion.spatial())
    val n = Tab.entries.size
    val index by animateMotion(selected.ordinal.toFloat(), reduce, Motion.selector())
    val run = (if (vertical) itemH else itemW) * n + IslandSpec.ItemGap * (n - 1) + IslandSpec.CapsulePad * 2
    val across = (if (vertical) itemW else itemH) + IslandSpec.CapsulePad * 2
    val shape = RoundedCornerShape(across / 2)
    val itemShape = RoundedCornerShape(minOf(itemW, itemH) / 2)
    Box(
        Modifier.size(if (vertical) across else run, if (vertical) run else across).islandShadow(shape).islandFace(shape, pal.card, pal.cardLine)
            .padding(IslandSpec.CapsulePad).selectableGroup(),
    ) {
        // Reduced motion: the pill never slides. The old one fades out at its slot while the new one fades in at its own.
        if (reduce) Crossfade(selected.ordinal, animationSpec = Motion.reducedFade(), label = "islandPill") { slot ->
            Box(Modifier.graphicsLayer {
                val step = (if (vertical) itemH else itemW).toPx() + IslandSpec.ItemGap.toPx()
                if (vertical) translationY = slot * step else translationX = slot * step
                alpha = pillK.coerceIn(0f, 1f)
            }.size(itemW, itemH).clip(itemShape).background(pal.tabPill))
        } else Box(Modifier.graphicsLayer {
            val step = (if (vertical) itemH else itemW).toPx() + IslandSpec.ItemGap.toPx()
            val i = index.coerceIn(0f, (n - 1).toFloat()) * step
            if (vertical) translationY = i else translationX = i
            val k = pillK.coerceIn(0f, 1f)
            alpha = k; scaleX = 0.6f + 0.4f * k; scaleY = 0.6f + 0.4f * k
        }.size(itemW, itemH).clip(itemShape).background(pal.tabPill))
        val items: @Composable () -> Unit = {
            Tab.entries.forEach { t ->
                val on = t == selected && pillOn
                val tint by animateColorAsState(if (on) pal.tabOn else pal.tabOff, tween(Motion.ReducedFadeMs), label = "islandTint")
                Column(
                    Modifier.size(itemW, itemH)
                        .pressable(itemShape, pal.pressTint, onClick = { onSelect(t) }, role = Role.Tab, pressedScale = Motion.RoundPressedScale)
                        .semantics { this.selected = on; contentDescription = t.label + if (t == dot) ", oltre il ritmo" else "" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon((if (on) IslandGlyph.sectionOn else IslandGlyph.sectionOff).getValue(t).value, null, Modifier.size(if (labels) 20.dp else 24.dp), tint = tint)
                        if (t == Tab.SPESE && dotK > 0.01f) Box(Modifier.offset(11.dp, (-11).dp).size(6.dp).graphicsLayer { alpha = dotK.coerceIn(0f, 1f); scaleX = dotK; scaleY = dotK }.clip(CircleShape).background(pal.over))
                    }
                    if (labels) {
                        Spacer(Modifier.height(2.dp))
                        Text(t.label, style = if (on) Type.smallStrong else Type.small, color = tint, maxLines = 1)
                    }
                }
            }
        }
        if (vertical) Column(verticalArrangement = Arrangement.spacedBy(IslandSpec.ItemGap)) { items() }
        else Row(horizontalArrangement = Arrangement.spacedBy(IslandSpec.ItemGap)) { items() }
    }
}

/**
 * The open Fold island's vertical plan in dp, from the container's top (user, 2026-11-01: controls never move under the
 * finger). Back's slot (button + gap) above the capsule and its mirror below are ALWAYS reserved, so the capsule and the
 * Agente button sit at the same place whether or not Back is showing: the group stays centred exactly as it was without
 * Back, and Back only appears (spring, fade) in the empty slot above. The slots are empty layout space, not a touch target.
 */
data class FoldIslandPlan(val backTop: Float, val capsuleTop: Float, val capsuleHeight: Float, val agentTop: Float, val height: Float)

fun foldIslandPlan(): FoldIslandPlan {
    val slot = IslandSpec.Back.value + IslandSpec.GroupGap.value
    val n = Tab.entries.size
    val capsule = IslandSpec.Item.value * n + IslandSpec.ItemGap.value * (n - 1) + IslandSpec.CapsulePad.value * 2
    val agentTop = slot + capsule + IslandSpec.GroupGap.value
    return FoldIslandPlan(backTop = 0f, capsuleTop = slot, capsuleHeight = capsule, agentTop = agentTop, height = agentTop + IslandSpec.AgentFold.value + slot)
}

/**
 * The open Fold's island, vertically centred on the right edge: the section capsule and Agente at a fixed place
 * ([foldIslandPlan]) and Back above them, growing upward out of the capsule when a detail is open.
 */
@Composable
fun FoldIsland(app: App, showBack: Boolean, geometry: IslandGeometry, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val plan = foldIslandPlan()
    val fromCapsule = TransformOrigin(0.5f, 1f)
    Column(modifier.testTag(UiTag.ISLAND).width(IslandSpec.AgentFold), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.height(plan.capsuleTop.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
            AnimatedVisibility(
                showBack,
                enter = if (reduce) fadeIn(Motion.reducedFade()) else slideInVertically(Motion.spatialOffset()) { it / 2 } + scaleIn(Motion.spatial(), 0.6f, fromCapsule) + fadeIn(tween(Motion.ReducedFadeMs)),
                exit = if (reduce) fadeOut(Motion.reducedFade()) else slideOutVertically(Motion.spatialOffset()) { it / 2 } + scaleOut(Motion.spatial(), 0.6f, fromCapsule) + fadeOut(tween(Motion.ReducedFadeMs)),
            ) {
                RoundButton(IslandSpec.Back, "Indietro", { app.nav = app.nav.pop() }) { Icon(IslandGlyph.back, null, Modifier.size(24.dp), tint = pal.tabOff) }
            }
        }
        SectionCapsule(app.nav.tab, { app.tab(it) }, vertical = true, itemW = IslandSpec.Item, itemH = IslandSpec.Item, labels = false,
            dot = if (xyz.grtlabs.pilastri.ui.screens.rememberSpendDot(app)) Tab.SPESE else null, pillOn = !app.nav.settingsOpen)
        Spacer(Modifier.height(IslandSpec.GroupGap))
        AgentButton(app, IslandSpec.AgentFold, geometry)
        Spacer(Modifier.height(plan.capsuleTop.dp))
    }
}

/* ---------- Cover and phone: one dock (COUNCIL.md §1, amendment after the user tried the build, 2026-10-31) ---------- */

/** The dock's items, in order. Agente is an action; Impostazioni is a page on the cover. */
enum class DockItem(val label: String, val tab: Tab?) {
    PILASTRI("Pilastri", Tab.PILASTRI), SPESE("Spese", Tab.SPESE), FUTURO("Futuro", Tab.FUTURO),
    AGENTE("Agente", null), IMPOSTAZIONI("Impostazioni", null);

    companion object { fun of(t: Tab): DockItem = entries.first { it.tab == t } }
}

/** What the frame holds at a layout: the cover/phone dock, or the open Fold's island plus Impostazioni alone in the corner. */
data class FrameSpec(val dock: List<DockItem>, val island: List<DockItem>, val cornerSettings: Boolean)

fun frameSpec(layout: AppLayout): FrameSpec = when (layout) {
    AppLayout.Compact -> FrameSpec(DockItem.entries, emptyList(), cornerSettings = false)
    else -> FrameSpec(emptyList(), listOf(DockItem.PILASTRI, DockItem.SPESE, DockItem.FUTURO, DockItem.AGENTE), cornerSettings = true)
}

/**
 * The dock item under the pill: Agente while its page is open (G-10: on the cover Agente is a normal peer page), else
 * Impostazioni while it is open, otherwise the section. Without [agentOpen] it is never Agente.
 */
fun dockSelection(nav: NavState, agentOpen: Boolean = false): DockItem = when {
    agentOpen -> DockItem.AGENTE
    nav.settingsOpen -> DockItem.IMPOSTAZIONI
    else -> DockItem.of(nav.tab)
}

/**
 * A section root opened as a page (Impostazioni): it is a destination with its own way out (its button, a toggle), not a detail, so the
 * island's Back is not shown on it (AGENTS: Back only with a detail open). Pages pushed over it are details.
 */
fun Route.isSectionRoot(): Boolean = this is Route.Settings

/** The island's Back: a detail is open (the top route is not a section root) and Agente is not. */
fun NavState.islandBackShown(agentOpen: Boolean): Boolean = !agentOpen && depth.lastOrNull()?.let { !it.isSectionRoot() } == true

/** Leaving Impostazioni from the dock: the stack returns to where Impostazioni was opened (a pop, not a cut). */
fun NavState.leaveSettings(): NavState {
    val at = depth.indexOf(Route.Settings)
    return if (at < 0) this else copy(stacks = stacks + (tab to depth.take(at)))
}

/** Dock geometry in dp: [item] wide items (≥48, ≤76) inside 12 dp margins, a [width] dock centred at [start]. */
data class DockGeometry(val item: Float, val width: Float, val start: Float, val height: Float)

fun dockGeometry(screenWidth: Float, items: Int = DockItem.entries.size): DockGeometry {
    val pad = IslandSpec.CapsulePad.value
    val item = ((screenWidth - 2 * IslandSpec.Margin.value - 2 * pad) / items).coerceIn(IslandSpec.Item.value, IslandSpec.CoverItemMax.value)
    val width = item * items + 2 * pad
    return DockGeometry(item, width, (screenWidth - width) / 2, IslandSpec.CoverItemH.value + 2 * pad)
}

/**
 * A dock tap: Agente opens its peer page (a tap on it while open does nothing); sections leave Agente and Impostazioni first
 * (both are pages on the cover); Impostazioni pushes or returns to itself.
 */
private fun onDock(app: App, item: DockItem, hx: Hx) {
    if (item == DockItem.AGENTE) {
        if (!app.agentOpen) { hx.tick(); app.openAgent() }
        return
    }
    val tab = item.tab
    if (tab == null) {
        if (!app.nav.settingsOpen || app.agentOpen) hx.tick()
        app.openSettings()
        return
    }
    if (app.agentOpen && tab == app.nav.tab && !app.nav.settingsOpen) hx.tick()
    if (app.nav.settingsOpen) {
        if (tab == app.nav.tab) hx.tick()
        app.nav = app.nav.leaveSettings()
    }
    app.tab(tab)
}

/** A section or Impostazioni in the dock: glyph only; selected = filled/heavier glyph plus the pill, never colour alone. */
@Composable
private fun DockTab(item: DockItem, on: Boolean, w: Dp, h: Dp, shape: Shape, enabled: Boolean, label: String = item.label, onClick: () -> Unit) {
    val pal = LocalPal.current
    val k by animateFloatAsState(if (on) 1f else 0f, Motion.effects(), label = "dockOn")
    val tint by animateColorAsState(if (on) pal.tabOn else pal.tabOff, tween(Motion.ReducedFadeMs), label = "dockTint")
    val off = item.tab?.let { IslandGlyph.sectionOff.getValue(it).value } ?: if (item == DockItem.AGENTE) IslandGlyph.agent else IslandGlyph.gear
    val filled = item.tab?.let { IslandGlyph.sectionOn.getValue(it).value } ?: if (item == DockItem.AGENTE) IslandGlyph.agentOn else IslandGlyph.gearOn
    Box(
        Modifier.size(w, h).dockItemTag()
            .pressable(shape, pal.pressTint, onClick = onClick, role = Role.Tab, pressedScale = Motion.RoundPressedScale, enabled = enabled)
            .semantics { selected = on; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(off, null, Modifier.size(24.dp).graphicsLayer { alpha = 1f - k.coerceIn(0f, 1f) }, tint = tint)
        Icon(filled, null, Modifier.size(24.dp).graphicsLayer { alpha = k.coerceIn(0f, 1f) }, tint = tint)
    }
}

/**
 * The cover/phone dock: one floating capsule, horizontally centred, with Pilastri, Spese, Futuro, Agente, Impostazioni.
 * The pill travels on the selector spring (a crossfade under reduced motion). Agente is a normal peer page here (G-10): the
 * pill lands on it and its glyph turns heavier, like the sections; its dot shows while changes wait. The dock slides below the window edge with the keyboard ([shown] 1 → 0, read in draw only)
 * and fades instead under reduced motion. The caller places it above the navigation bar once.
 */
@Composable
fun CoverDock(app: App, geometry: IslandGeometry, visible: Boolean, shown: () -> Float, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val hx = rememberHx()
    val selected = dockSelection(app.nav, app.agentOpen)
    val spendDot = xyz.grtlabs.pilastri.ui.screens.rememberSpendDot(app)
    val spendDotK by animateMotion(if (spendDot) 1f else 0f, reduce, Motion.spatial())
    val last = (DockItem.entries.size - 1).toFloat()
    val index by animateFloatAsState(selected.ordinal.toFloat(), Motion.selector(), label = "dockPill")
    val below = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val g = dockGeometry(maxWidth.value)
        val itemW = g.item.dp
        val itemH = IslandSpec.CoverItemH
        val shape = RoundedCornerShape(g.height.dp / 2)
        val itemShape = RoundedCornerShape(itemH / 2)
        Box(
            Modifier.testTag(UiTag.DOCK).size(g.width.dp, g.height.dp)
                .graphicsLayer {
                    val s = shown()
                    alpha = s.coerceIn(0f, 1f)
                    if (!reduce) translationY = (1f - s) * (size.height + IslandSpec.DockBottom.toPx() + below)
                }
                .islandShadow(shape).islandFace(shape, pal.card, pal.cardLine)
                .padding(IslandSpec.CapsulePad)
                .then(if (visible) Modifier.selectableGroup() else Modifier.clearAndSetSemantics { }),
        ) {
            // Reduced motion: alpha only. The old pill fades out at its slot while the new one fades in at its own.
            if (reduce) Crossfade(selected.ordinal, animationSpec = Motion.reducedFade(), label = "dockPillFade") { slot ->
                Box(Modifier.graphicsLayer { translationX = slot * itemW.toPx() }.size(itemW, itemH).clip(itemShape).background(pal.tabPill))
            } else Box(
                Modifier.graphicsLayer { translationX = index.coerceIn(0f, last) * itemW.toPx() }
                    .size(itemW, itemH).clip(itemShape).background(pal.tabPill),
            )
            Row {
                DockItem.entries.forEach { item ->
                    if (item == DockItem.AGENTE) Box(Modifier.agentAnchor(geometry), contentAlignment = Alignment.Center) {
                        DockTab(item, item == selected, itemW, itemH, itemShape, visible, label = agentLabel(app, cover = true)) { onDock(app, item, hx) }
                        if (app.assistant.waiting > 0 && !app.agentOpen) Box(Modifier.offset(9.dp, (-9).dp).size(6.dp).clip(CircleShape).background(pal.tabOn))
                    }
                    else if (item == DockItem.SPESE) Box(contentAlignment = Alignment.Center) {
                        // 3b-03 / G-04: the Spese dot while the overspend signal holds; it clears by itself.
                        DockTab(item, item == selected, itemW, itemH, itemShape, visible, label = if (spendDot) "Spese, oltre il ritmo" else item.label) { onDock(app, item, hx) }
                        if (spendDotK > 0.01f) Box(Modifier.offset(9.dp, (-9).dp).size(6.dp).graphicsLayer { alpha = spendDotK.coerceIn(0f, 1f); scaleX = spendDotK; scaleY = spendDotK }.clip(CircleShape).background(pal.over))
                    }
                    else DockTab(item, item == selected, itemW, itemH, itemShape, visible) { onDock(app, item, hx) }
                }
            }
        }
    }
}

/**
 * The open Fold's left pane tint while the Agente panel is open: drawn from the morph progress in the draw phase only,
 * over the pane (which never moves, recomposes or loses its scroll). Tapping it closes the panel.
 */
@Composable
fun AgentTintLayer(app: App, modifier: Modifier) {
    // The tint runs behind the system bars: the pane sits inside the status and navigation bar insets, so the rectangle is
    // extended over them (draw only, nothing is laid out) and no untinted band is left above or below it.
    val density = LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val top = WindowInsets.safeDrawing.getTop(density).toFloat()
    val start = WindowInsets.safeDrawing.getLeft(density, direction).toFloat()
    val bottom = WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(density).toFloat()
    Box(
        modifier
            .drawBehind {
                val a = Motion.AgentTint * app.agentMorph.value.coerceIn(0f, 1f)
                if (a > 0.001f) drawRect(
                    Color.Black, topLeft = androidx.compose.ui.geometry.Offset(-start, -top),
                    size = androidx.compose.ui.geometry.Size(size.width + start, size.height + top + bottom), alpha = a,
                )
            }
            // No semantics node here on purpose (D6): a covering node would drop the left pane from the accessibility tree.
            // "Chiudi Agente" is the island's button and predictive back; this layer only takes the tap.
            .then(if (app.agentOpen) Modifier.pointerInput(Unit) { detectTapGestures { app.closeAgent() } } else Modifier),
    )
}

/**
 * The Agente panel as a container transform out of the Agente button: the rect and corner radius lerp on the morph
 * spring (k300/ζ0.9) to [end] (the right pane on the open Fold, the whole content area on one pane). The assistant is
 * laid out at its final size and revealed by the growing clip, so nothing reflows. "×", Back and predictive back (live
 * progress, springing back on cancel) run the same close. Reduced motion crossfades in place.
 */
/**
 * The Agente panel's own content while it expands (container transform): present from the first frame of the morph and
 * fully shown when the veil over the pane beneath is complete ([Motion.AgentVeilUntil] and a little after), so the
 * growing panel never shows an empty frame; the clip of the growing rect reveals it.
 */
internal fun agentContentAlpha(progress: Float): Float = (progress / (Motion.AgentVeilUntil + 0.1f)).coerceIn(0f, 1f)

@Composable
fun AgentHost(app: App, geometry: IslandGeometry, end: Rect, insets: PaddingValues) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress = app.agentMorph
    LaunchedEffect(app.agentOpen) {
        val open = app.agentOpen
        progress.travelTo(if (open) 1f else 0f, reduce, Motion.morph)
        if (!open) app.agentClosed()
    }
    val commitHx = rememberCommitHaptics(Motion.DismissFraction) // the platform decides the commit: grab and release only
    PredictiveBackHandler(enabled = app.agentOpen) { events ->
        commitHx.start()
        try {
            events.collect { e -> progress.snapTo((1f - e.progress).coerceIn(0f, 1f)) }
            commitHx.release(committed = true) // the snap as the commit becomes visible; no settle, nothing separate arrives
            app.closeAgent()
        } catch (e: CancellationException) {
            commitHx.cancel()
            scope.launch { progress.travelTo(1f, reduce, Motion.morph) }
            throw e
        }
    }
    val p = progress.value
    if (p <= 0.0005f && !app.agentOpen) return
    val archive = app.archive ?: return
    // The pane under the panel gives way first: a veil of the panel's own colour rises over the first AgentVeilUntil of the
    // morph, so the pane's title ("Scenario") is gone before the panel's heading ("Agente") starts to show: never two titles
    // at once. It only draws, takes no touches, and follows the same progress, so it reverses with the panel.
    Box(
        Modifier
            .offset { IntOffset(end.left.roundToInt(), end.top.roundToInt()) }
            .layout { measurable, _ ->
                val w = end.width.roundToInt().coerceAtLeast(1); val h = end.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .drawBehind { drawRect(pal.bg, alpha = (progress.value / Motion.AgentVeilUntil).coerceIn(0f, 1f)) },
    )
    val startSize = with(density) { IslandSpec.AgentFold.toPx() }
    val start = geometry.agent ?: Rect(end.right - startSize, end.center.y - startSize / 2, end.right, end.center.y + startSize / 2)
    val rect = if (reduce) end else Rect(lerp(start.left, end.left, p), lerp(start.top, end.top, p), lerp(start.right, end.right, p), lerp(start.bottom, end.bottom, p))
    val radius = with(density) { if (reduce) 0f else lerp(start.width / 2f, 0f, p.coerceIn(0f, 1f)) }
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .layout { measurable, _ ->
                val w = rect.width.roundToInt().coerceAtLeast(1); val h = rect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                alpha = if (reduce) p.coerceIn(0f, 1f) else 1f
                shadowElevation = if (p in 0.001f..0.999f) 12.dp.toPx() else 0f
                shape = if (radius > 0.5f) RoundedCornerShape(radius) else RectangleShape; clip = true
            }
            .background(pal.bg)
            // The panel takes every touch over its area: nothing reaches the page beneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(end.width.roundToInt().coerceAtLeast(1), end.height.roundToInt().coerceAtLeast(1)))
                    layout(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)) {
                        placeable.place((end.left - rect.left).roundToInt(), (end.top - rect.top).roundToInt())
                    }
                }
                .graphicsLayer { alpha = if (reduce) 1f else agentContentAlpha(progress.value) },
        ) {
            if (p > 0.05f || app.agentOpen) AssistantPage(archive, app.aiCommit, Modifier.fillMaxSize(), app.assistant, insets = insets, wide = true)
        }
    }
}
