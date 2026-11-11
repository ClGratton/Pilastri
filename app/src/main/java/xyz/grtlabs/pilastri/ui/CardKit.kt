package xyz.grtlabs.pilastri.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ONE card container + ONE card header (DESIGN G-12 "Card inner padding"). Every titled card goes through [TitledCard]
 * (or [CardColumn] + [CardHeader] when the body is custom); nothing draws `Type.cardTitle` itself and no card adds
 * its own padding or a trailing Spacer: the inset is [CardInset] on all four sides, the bottom included, and body content
 * sits on the same left/right inset. The header row is [CardHeaderHeight] tall, so the title has the same top inset in every card
 * whatever the trailing element; the trailing element is centred on the title row.
 */
val CardInset: Dp = 14.dp
val CardHeaderHeight: Dp = 32.dp
/** The horizontal inset list rows carry themselves (ListRow 10 dp). */
val RowInset: Dp = 10.dp
/** The vertical inset list rows carry themselves: container CardInset - RowInset (4) + 10 = CardInset (14) to the text. */
val RowVerticalInset: Dp = 10.dp

/** Card surface with the equal inner padding. Tagged CARD. Header and body are children; no per-card padding inside. */
@Composable
fun CardColumn(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    color: Color? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    rows: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPal.current
    // rows = true: the body is full-width list rows ([ListRow], category rows) that carry their own 10 dp inset, so the container takes
    // CardInset - 10 dp at the sides (text still sits on CardInset) and the header is indented by the row inset.
    val pad = if (rows) PaddingValues(start = CardInset - RowInset, end = CardInset - RowInset, top = CardInset, bottom = CardInset - RowInset) else PaddingValues(CardInset)
    CardShell(modifier, shape, color ?: pal.card, pad, rows, verticalArrangement, content)
}

/**
 * The card surface + the CARD_BODY node (14 dp from the card on all four sides, DESIGN G-12). Plain cards: the content column IS the body.
 * Row cards ([rows]): the rows carry their own inset (selected fill, touch area) so the real content column is wider than the body; the body is then
 * an empty overlay node with the exact 14 dp geometry (no pointer input, so it never takes a touch).
 */
@Composable
private fun CardShell(
    modifier: Modifier, shape: Shape, color: Color, pad: PaddingValues, rows: Boolean,
    verticalArrangement: Arrangement.Vertical, content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPal.current
    val surfaceBounds = Modifier.periodPageSurfaceBounds()
    if (!rows) {
        Column(modifier.fillMaxWidth().then(surfaceBounds).card(pal, shape, color).padding(pad)) {
            Column(Modifier.fillMaxWidth().periodContentFade().cardBodyTag(), verticalArrangement = verticalArrangement, content = content)
        }
    } else {
        Box(modifier.fillMaxWidth().then(surfaceBounds).card(pal, shape, color)) {
            Column(Modifier.fillMaxWidth().padding(pad).periodContentFade(), verticalArrangement = verticalArrangement, content = content)
            Box(Modifier.matchParentSize().padding(CardInset)) { Box(Modifier.fillMaxSize().cardBodyRowsTag()) }
        }
    }
}

/**
 * The ONE row of actions (pills, buttons, chips). Children are laid out on one line when they fit; otherwise they wrap onto further lines
 * (never clipped, never scrolled). [end] right-aligns each line; [equalWidth] stretches the children of a one-line row to equal widths
 * (two or three answers of the same weight). Tagged ACTION_ROW. Children are plain composables (no RowScope weights).
 */
@Composable
fun ActionRow(modifier: Modifier = Modifier, spacing: Dp = 6.dp, end: Boolean = false, equalWidth: Boolean = false, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth().actionRowTag()) { measurables, c ->
        val gap = spacing.roundToPx()
        val limit = if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE
        // Each measurable is measured exactly ONCE (Compose throws "measure() may not be called multiple times"): the natural widths come from
        // intrinsics, the equal-width decision is made on them, and only then is every child measured with its final constraints.
        val natural = measurables.map { it.maxIntrinsicWidth(Int.MAX_VALUE).coerceAtMost(limit) }
        val lines = ArrayList<List<Int>>()
        var cur = ArrayList<Int>(); var used = 0
        natural.forEachIndexed { i, nw ->
            val need = if (cur.isEmpty()) nw else used + gap + nw
            if (cur.isNotEmpty() && need > limit) { lines += cur; cur = ArrayList(); used = 0 }
            used = if (cur.isEmpty()) nw else used + gap + nw
            cur += i
        }
        if (cur.isNotEmpty()) lines += cur
        val eqW = if (equalWidth && lines.size == 1 && measurables.size > 1 && limit != Int.MAX_VALUE) (limit - gap * (measurables.size - 1)) / measurables.size else 0
        val equal = eqW > 0 && natural.all { it <= eqW }
        val placeables = measurables.map { if (equal) it.measure(Constraints(minWidth = eqW, maxWidth = eqW)) else it.measure(Constraints(maxWidth = limit)) }
        val lineWidth = lines.map { l -> l.sumOf { placeables[it].width } + gap * (l.size - 1) }
        val width = if (c.hasBoundedWidth) c.maxWidth else (lineWidth.maxOrNull() ?: 0)
        val lineHeights = lines.map { l -> l.maxOf { placeables[it].height } }
        layout(width, lineHeights.sum()) {
            var y = 0
            lines.forEachIndexed { li, l ->
                var x = if (end) width - lineWidth[li] else 0
                l.forEach { i -> val p = placeables[i]; p.placeRelative(x, y + (lineHeights[li] - p.height) / 2); x += p.width + gap }
                y += lineHeights[li]
            }
        }
    }
}

/**
 * The ONE row of sibling actions by ROLE (G-17(d), CR-96: the confirming / forward action is rightmost). Callers pass [confirm] and [secondary]
 * as named slots, never a positional order; this row places secondary (left) then confirm (right) and applies the testTags
 * `action-secondary-<tag>` / `action-confirm-<tag>` through the Modifier each slot receives (the slot must put it on its button).
 * Layout is [ActionRow]'s (same widths, edges, wrapping). A source rule fails on any other Row of sibling PillButtons.
 */
@Composable
fun RoleActionRow(
    tag: String, modifier: Modifier = Modifier, spacing: Dp = 6.dp, end: Boolean = false, equalWidth: Boolean = false,
    secondary: (@Composable (Modifier) -> Unit)? = null, confirm: (@Composable (Modifier) -> Unit)? = null,
) {
    ActionRow(modifier, spacing, end, equalWidth) {
        secondary?.invoke(Modifier.testTag("action-secondary-$tag"))
        confirm?.invoke(Modifier.testTag("action-confirm-$tag"))
    }
}

/** The fixed-height title row: title (CARD_TITLE) left, optional [trailing] (CARD_TRAILING) centred on the row, right. */
@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, indent: Dp = 0.dp, maxLines: Int = Int.MAX_VALUE, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val pal = LocalPal.current
    // SpaceBetween + weight(1f, fill = false): the title box is as wide as its text (it still ellipsizes at the trailing slot), so its box edge
    // is never mistaken for the content's right edge in front of a trailing chip/caption (device dumps: right inset 74.7 dp on "Flusso delle entrate").
    Row(modifier.fillMaxWidth().padding(horizontal = indent).heightIn(min = CardHeaderHeight).cardHeaderTag(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            title, style = Type.cardTitle, color = pal.ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).cardTitleTag().semantics { heading() },
        )
        if (trailing != null) {
            Row(Modifier.layout { m, c -> val p = m.measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)); layout(p.width, 0) { p.place(0, -p.height / 2) } }.cardTrailingTag(), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/** Short right-hand caption of a header ("ott 25 – ott 26", "in €"). */
@Composable
fun CardTrailingText(text: String) {
    Text(text, style = Type.tiny, color = LocalPal.current.ink2)
}

/** Container + header + body. Body content must not add its own horizontal or bottom padding. */
@Composable
fun TitledCard(
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    color: Color? = null,
    titleMaxLines: Int = 1,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    rows: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CardColumn(modifier, shape, color, verticalArrangement, rows) {
        CardHeader(title, indent = if (rows) RowInset else 0.dp, maxLines = titleMaxLines, trailing = trailing)
        content()
    }
}

/** Accent card radius (DESIGN Tokens: r 26). */
val AccentCardShape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)

/**
 * The ONLY place the accent fill is drawn (CR-61). One per screen with a primary figure (CR-55). Tagged ACCENT_CARD.
 * Content is a [StatRow] of [AccentStat] columns (primary 26 / secondary 17 / label 11 per Tokens).
 */
@Composable
fun AccentCard(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val pal = LocalPal.current
    Row(
        modifier.fillMaxWidth().periodPageSurfaceBounds().testTag(UiTag.ACCENT_CARD).clip(AccentCardShape).background(pal.accent).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.fillMaxWidth().periodContentFade(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** One stat column of an [AccentCard]: label above value. [primary] uses the primary-figure rank (one per screen), else secondary. */
@Composable
fun RowScope.AccentStat(label: String, value: String, weight: Float = 1f, primary: Boolean = false, valueColor: Color? = null) {
    val pal = LocalPal.current
    Column(Modifier.weight(weight)) {
        Text(label, style = Type.labelOnAccent, color = pal.accentLabel, modifier = Modifier.testTag(UiTag.STAT_LABEL))
        Text(
            value, style = if (primary) Type.figure else Type.secondaryFigure, color = valueColor ?: pal.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(if (primary) UiTag.PRIMARY_FIGURE else UiTag.SECONDARY_FIGURE),
        )
    }
}

/**
 * "Da controllare" exactly as boards 3b/7c (CR-32, CR-59): white card, 2 dp #CFBDFE outline, 32 dp icon disc, title + one line,
 * chevron, no inner button. The caller shows it only when N > 0. Tagged ATTENTION_ROW.
 */
/** Board 3b/7c: the attention row is exactly this tall (CR-59). */
val AttentionRowHeight: Dp = 56.dp

@Composable
fun AttentionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val tall = LocalDensity.current.fontScale > 1.3f
    Row(
        // ONE clickable node (CR-59): the row itself carries the click and merges its children; no clickable inside it.
        modifier.fillMaxWidth().testTag(UiTag.ATTENTION_ROW).semantics(mergeDescendants = true) {}.then(if (tall) Modifier.heightIn(min = AttentionRowHeight) else Modifier.height(AttentionRowHeight))
            .pressable(CardShape, pal.pressTint, onClick = onClick).background(pal.card)
            .border(2.dp, pal.accentLine, CardShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).testTag(UiTag.ICON_DISC).clip(CircleShape).background(pal.lilacBg), contentAlignment = Alignment.Center) { androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = pal.lilacInk) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.rowTitle, color = pal.lilacInk, maxLines = if (tall) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = Type.small, color = pal.ink2, maxLines = if (tall) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.Icon(Ic.chevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = pal.ink2)
    }
}

/** Quiet content row style (CR-58): white + 1 dp outline, tagged CONTENT_ROW. Selected state tinted via [selectedTag] by the caller. */
fun Modifier.contentRow(pal: Pal, shape: androidx.compose.ui.graphics.Shape = CardShape): Modifier =
    this.testTag(UiTag.CONTENT_ROW).clip(shape).background(pal.card).border(1.dp, pal.cardLine, shape)

/** Untitled list card: full-width rows that carry their own inset; 4 dp container padding (rows sit on CardInset). Tagged CARD. */
@Composable
fun RowsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    CardShell(modifier, CardShape, pal.card, PaddingValues(CardInset - RowInset), true, Arrangement.Top, content)
}
