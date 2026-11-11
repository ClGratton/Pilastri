package xyz.grtlabs.pilastri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalDensity
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * G-12: the page-header metrics. One value per type, used by every page; nothing per screen.
 * Gutter: the side margin of every top-level page and right-pane root, equal to the swiping blocks' peek inset so titles
 * and period cards share one left edge. Top: the space above the header, the same on every page.
 */
object PageSpec {
    /** CR-01 restored (user 2026-11-08, 2026-11-09): [gutter] is the ONE page side gutter, peek + gap (24 dp cover, 32 dp open Fold), so every card on every page shares the same edges and a swiping block shows its neighbour 16 / 24 dp. [Gutter] (16 dp) is only the inner padding of the accent card. */
    val Gutter = 16.dp
    @Suppress("UNUSED_PARAMETER") fun gutter(wide: Boolean): Dp = Gutter
    /** Tokens: page padding 8 dp on top, header 48 dp. */
    val Top = 8.dp
    val HeaderMinHeight = 48.dp
    /** The trailing slot is at least one 48 dp target wide (help glyph, round control); wider controls grow it, never the header. */
    val TrailingSlot = 48.dp
    val TitleTop = 2.dp
    val BackGap = 8.dp
}

/**
 * H6: THE shared period title, the current period of a page that swipes between periods (Mese, Spese): the page-title
 * style, so it is as big as any header text, in the same component on every such page. [asPageTitle]: on Mese the period IS
 * the page title, so the page-title node sits inside it (same bounds). The only place that applies [UiTag.PERIOD_TITLE].
 */
@Composable
fun PeriodTitle(
    text: String, modifier: Modifier = Modifier, asPageTitle: Boolean = false, caret: Boolean = false,
    strip: PeriodState? = null, titleOf: ((Int) -> String)? = null,
) {
    val pal = LocalPal.current
    // One page's title: the text, and (when the title is a control) the caret that says "tap to choose the period", in ONE tagged node.
    @Composable
    fun One(t: String, tagged: Boolean) {
        Row(if (tagged) Modifier.testTag(UiTag.PERIOD_TITLE) else Modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(t, style = Type.screenTitle, color = pal.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, // one line at every font scale, never a break inside a word
                modifier = Modifier.weight(1f, fill = false).then(if (asPageTitle && tagged) Modifier.testTag(UiTag.PAGE_TITLE).semantics { heading() } else Modifier))
            if (caret) Icon(Ic.chevronRight, null, tint = pal.ink2, modifier = Modifier.padding(start = 2.dp).size(22.dp).rotate(90f))
        }
    }
    if (strip == null || titleOf == null) { Box(modifier) { One(text, true) }; return }
    PeriodStrip(strip, modifier, "period-title") { i, active -> One(titleOf(i), active) }
}

/**
 * M1 (belongs together, moves together): THE one mechanism that lays a per-period piece of a header (title, subtitle, chip) on the
 * strip's pitch and position, so it travels with its page during the drag and settles with the pager's own spring. Title, subtitle and
 * chip each call this with the SAME [strip], so they share one offset formula and one spring; nothing animates on its own. [content]
 * draws period [i]; a neighbour is hidden at rest and from accessibility (no cut, no second tagged node). Reduced motion: crossfade.
 */
/** The coverage line under a period title that is not the page title (Spese): travels with its page on the title's strip; the active piece is tagged period-coverage. */
@Composable
internal fun PeriodCoverage(strip: PeriodState, style: TextStyle = Type.sub, maxLines: Int = 1, lineOf: (Int) -> String?) {
    val pal = LocalPal.current
    PeriodStrip(strip, Modifier, "period-subtitle") { i, active ->
        lineOf(i)?.takeIf { it.isNotBlank() }?.let { Text(it, style = style, color = pal.ink2, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = if (active) Modifier.testTag(UiTag.PERIOD_COVERAGE) else Modifier) }
    }
}

@Composable
internal fun PeriodStrip(strip: PeriodState, modifier: Modifier, label: String, content: @Composable (i: Int, active: Boolean) -> Unit) {
    if (LocalReduceMotion.current) {
        Crossfade(strip.settledIndex, modifier, animationSpec = Motion.reducedFade(), label = label) { i -> content(i, true) }
        return
    }
    // R2-M4: the drag offset is read only in layer / layout blocks; composition sees just the rounded page, which changes once per period.
    // Only the pieces that can be visible: a neighbour exists while it is on its way in (|offset| < 1), plus the settled page's piece.
    val ids by remember(strip) {
        derivedStateOf {
            val p = strip.position.value
            val b = p.roundToInt().coerceIn(0, strip.last)
            (b - 1..b + 1).filter { it in 0..strip.last && (it == strip.settled || Math.abs(it - p) < 0.999f) }
        }
    }
    var x0 by remember { mutableFloatStateOf(0f) }
    Layout(
        content = {
            ids.forEach { i ->
                key(i) {
                    val active = i == strip.settled
                    Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha; alpha = PeriodPhysics.rowAlpha(i - strip.position.value) }
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics { })) { content(i, active) }
                }
            }
        },
        // No clip: the piece travels the page's full pitch, and a clip at its own box would cut it dead at the slot edge (the bounds a
        // dump reports must follow the page). It fades by the selection strength, so what overlaps a neighbouring slot is faint and short-lived.
        modifier = modifier.onGloballyPositioned { x0 = it.positionInWindow().x },
    ) { measurables, c ->
        val idsNow = ids   // the ids this measure pass was made for (the layer block runs later and must not see a newer list)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = c.maxWidth, maxHeight = c.maxHeight)) }   // the same height bound the piece had as a direct child (no overflow past its slot)
        val h = placeables.maxOfOrNull { it.height } ?: 0
        // Wrap the settled piece (a trailing chip must not take the whole row and starve the title); a neighbour never changes the slot's width.
        val w = placeables.getOrNull(idsNow.indexOf(strip.settled))?.width ?: placeables.maxOfOrNull { it.width } ?: 0
        layout(w.coerceIn(c.minWidth, c.maxWidth.coerceAtLeast(c.minWidth)), h) {
            placeables.forEachIndexed { k, pl ->
                pl.placeWithLayer(0, 0) {
                    translationX = (idsNow.getOrElse(k) { idsNow.last() } - strip.position.value) * strip.pitchPx + strip.pageShiftPx(idsNow.getOrElse(k) { idsNow.last() }, strip.position.value)
                    // The neighbour treatment of the cards (periodContentFade) for a piece that leaves or enters through the page edge: it is drawn
                    // by the share of it inside the page, squared, so a half-entered name is faint (never a cut "bre" at full strength) and it
                    // comes in with the drag. The same formula for title, subtitle and chip; at rest every share is 1.
                    val share = strip.visibleShare(x0 + translationX, pl.width.toFloat())
                    alpha = share * share
                }
            }
        }
    }
}

/** The "giorno d/D" chip of a period header (Mese): the one chip component ([Tag]: same height, type and padding as every chip), tagged [UiTag.PERIOD_CHIP]. */
@Composable
internal fun PeriodChip(text: String, description: String, modifier: Modifier = Modifier) {
    Tag(text, modifier.semantics { contentDescription = description }, tag = UiTag.PERIOD_CHIP)
}

/** A header whose title is the period (Mese): back in the leading slot unless the island carries it, the title through [PeriodTitle]. */
@Composable
fun PeriodPageHeader(
    title: String, subtitle: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, backLabel: String = "Indietro",
    titleModifier: Modifier = Modifier, strip: PeriodState? = null, titleOf: ((Int) -> String)? = null,
    subtitleOf: ((Int) -> String?)? = null, chipOf: ((Int) -> Pair<String, String>?)? = null,
) {
    val pal = LocalPal.current
    // M1: the chip (text, description) of a period, null when that period has none; it travels on the same strip as title and subtitle.
    val trailing: (@Composable RowScope.() -> Unit)? = chipOf?.let { chip ->
        {
            if (strip == null) chip(0)?.let { (t, d) -> PeriodChip(t, d) }
            else PeriodStrip(strip, Modifier, "period-chip") { i, _ -> chip(i)?.let { (t, d) -> PeriodChip(t, d) } }
        }
    }
    val back: (@Composable () -> Unit)? = if (onBack != null && !LocalBackInFrame.current) ({ RoundIcon(Ic.back, backLabel, onBack, bg = pal.card, line = true) }) else null
    PageHeaderImpl(title, subtitle, modifier, titleModifier, back, trailing, periodTitle = true, strip = strip, titleOf = titleOf, subtitleOf = subtitleOf)
}

/**
 * G-12: THE page header, for every top-level page (Pilastri, Spese, Futuro, Agente, Impostazioni) and every pushed or
 * right-pane page: title ([Type.screenTitle]) and one subtitle line ([Type.sub]) in the same style, size, weight and
 * colour, at one height; [leading] (Back) and [trailing] (selector, chip, help) sit in fixed slots at the ends.
 */
@Composable
fun PageHeader(
    title: String, subtitle: String?, modifier: Modifier = Modifier, titleModifier: Modifier = Modifier,
    strip: PeriodState? = null, subtitleOf: ((Int) -> String?)? = null,
    /** Setup look (boards 8a-8c): an overline line above a 30 sp title tagged [UiTag.SETUP_TITLE]; the overline line is always reserved. Default: the page look, unchanged. */
    setupOverline: SetupOverline? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null,
) = if (setupOverline != null) SetupLookHeader(title, setupOverline.text, modifier, trailing)
else PageHeaderImpl(title, subtitle, modifier, titleModifier, leading, trailing, periodTitle = false, strip = strip, titleOf = null, subtitleOf = subtitleOf)

/** Marks [PageHeader] as the Setup look; [text] is the overline (null = the reserved blank line). */
@JvmInline value class SetupOverline(val text: String?)

/** The Setup variant of THE page header (N-06): overline above the 30 sp title, the trailing slot centred on the title row. */
@Composable
private fun SetupLookHeader(title: String, overline: String?, modifier: Modifier, trailing: (@Composable RowScope.() -> Unit)?) {
    val pal = LocalPal.current
    Column(modifier.fillMaxWidth().testTag(UiTag.PAGE_HEADER)) {
        Text(overline ?: " ", style = Type.setupOverline, color = pal.onChip, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().heightIn(min = PageSpec.HeaderMinHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.setupTitle, color = pal.ink, maxLines = 2,
                modifier = Modifier.weight(1f).testTag(UiTag.SETUP_TITLE).semantics { heading() })
            if (trailing != null) Row(Modifier.testTag(UiTag.PAGE_TRAILING), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

@Composable
private fun PageHeaderImpl(
    title: String, subtitle: String?, modifier: Modifier, titleModifier: Modifier,
    leading: (@Composable () -> Unit)?, trailing: (@Composable RowScope.() -> Unit)?, periodTitle: Boolean,
    strip: PeriodState?, titleOf: ((Int) -> String)?, subtitleOf: ((Int) -> String?)?,
) {
    val pal = LocalPal.current
    // L3: a wrapped subtitle breaks in balanced lines (no one-word last line at font 2.0); on one line nothing changes.
    val subStyle = remember { Type.sub.copy(lineBreak = LineBreak.Heading) }
    // One fixed height: the title is one line and the subtitle exactly one line (ellipsis), reserved even when empty, so the
    // content below starts at the same y on every page. Longer state belongs in the page body. The end slots are centred.
    // G-12 large-text rule (L1): above font scale 1.3 the header grows with its text (title + subtitle never clipped); at 1.0 it is 48 dp.
    val tall = LocalDensity.current.fontScale > 1.3f
    val chipBelow = tall && periodTitle
    Row(modifier.fillMaxWidth().testTag(UiTag.PAGE_HEADER).then(if (tall) Modifier.heightIn(min = PageSpec.HeaderMinHeight) else Modifier.height(PageSpec.HeaderMinHeight)), verticalAlignment = Alignment.Top) {
        if (leading != null) { Box(Modifier.heightIn(min = PageSpec.HeaderMinHeight), contentAlignment = Alignment.Center) { leading() }; Spacer(Modifier.width(PageSpec.BackGap)) }
        // The title block's hit area is the full 48 dp header height (CR-23) while the text keeps its place: the control
        // modifier wraps the block, never the title text, so a tappable title does not move or grow the visuals.
        // H1 (wave 10): beside a fixed back button the moving pieces are clipped to their own slot, which starts after the button, so a travelling title never passes under or over it.
        Box(Modifier.weight(1f).then(if (tall) Modifier else Modifier.fillMaxHeight()).then(if (leading != null && strip != null) Modifier.clipToBounds() else Modifier)) {
            // A period header's pieces travel the page's pitch: the control's press clip must not wrap them (it cut "Ottobre" to "Ottob" at the slot edge),
            // so on a period header the control is an overlay with the slot's bounds and the pieces are drawn unclipped above it.
            val overlay = strip != null
            if (overlay) Box(Modifier.matchParentSize().then(titleModifier))
            Column((if (overlay) Modifier else titleModifier).then(if (tall) Modifier else Modifier.fillMaxHeight()).padding(top = PageSpec.TitleTop), verticalArrangement = Arrangement.Top) {
                // H6: where the page title IS the period (Mese), it goes through the one shared period component.
                if (periodTitle) PeriodTitle(title, asPageTitle = true, caret = true, strip = strip, titleOf = titleOf)
                else Text(title, style = Type.pageHeading, color = pal.ink, maxLines = if (tall) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(UiTag.PAGE_TITLE).semantics { heading() })
                // A blank subtitle emits no node (the header height is fixed, so nothing moves). L1: above font scale 1.3 title and subtitle wrap, never ellipsize.
                // M1: on a period header the subtitle is per period and travels with its page on the title's own strip.
                if (strip != null && subtitleOf != null) PeriodCoverage(strip, subStyle, if (tall) Int.MAX_VALUE else 1) { i -> subtitleOf(i) }
                else if (!subtitle.isNullOrBlank()) Text(subtitle, style = subStyle, color = pal.ink2, maxLines = if (tall) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(UiTag.PAGE_SUBTITLE))
                // Large text: the period title keeps one line over the full width, so its trailing chip drops to its own row below.
                if (chipBelow && trailing != null) Row(Modifier.testTag(UiTag.PAGE_TRAILING).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
        // Wave 11b: the trailing control's centre sits on the TITLE's centre (the title line starts TitleTop below the header top), not on the header's: one rule for every trailing control.
        val titleCentreY = with(LocalDensity.current) { PageSpec.TitleTop + (if (periodTitle) Type.screenTitle else Type.pageHeading).lineHeight.toDp() / 2 }
        if (trailing != null && !chipBelow) Row(Modifier.offset(y = titleCentreY - PageSpec.HeaderMinHeight / 2).testTag(UiTag.PAGE_TRAILING).heightIn(min = PageSpec.HeaderMinHeight).widthIn(min = PageSpec.TrailingSlot), verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}
