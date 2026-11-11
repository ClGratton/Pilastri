package xyz.grtlabs.pilastri.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import xyz.grtlabs.pilastri.data.StaleItem
import xyz.grtlabs.pilastri.ui.*

/* Shared controls for the chart screens (Pilastri, pillar detail, Futuro, Previdenza). */

/**
 * The basis toggle "€ gen 2026 | nominale" (2-02): the pill travels on the [Motion.selector] spring, its overshoot clipped
 * at the ends; the change is reported once, with a tick. Every figure of the page retargets on the chart-update spring.
 */
@Composable
fun BasisToggle(real: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, realLabel: String = "€ gen 2026", nominalLabel: String = "nominale") {
    val pal = LocalPal.current
    val hx = rememberHx()
    val reduce = LocalReduceMotion.current
    val pos by animateFloatAsState(if (real) 0f else 1f, Motion.selector(), label = "basis")
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(pal.track).padding(horizontal = 4.dp)
            .semantics { contentDescription = "Base delle cifre" },
    ) {
        val cell = maxWidth / 2
        Box(Modifier.offset(x = cell * pos.coerceIn(0f, 1f), y = 4.dp).width(cell).height(40.dp).clip(RoundedCornerShape(20.dp)).background(pal.card).border(1.dp, pal.ribbonIn, RoundedCornerShape(20.dp)))
        Row(Modifier.fillMaxWidth()) {
            listOf(true to realLabel, false to nominalLabel).forEach { (value, text) ->
                val on = value == real
                Box(
                    // 48 dp hit area (cell) around the 40 dp visual pill (CR-23); the selected cell carries the "selected" tag.
                    Modifier.weight(1f).height(48.dp)
                        .pressable(RoundedCornerShape(24.dp), pal.pressTint, onClick = { if (!on) { hx.tick(); onChange(value) } }, role = Role.RadioButton)
                        .then(if (on) Modifier.selectedTag() else Modifier)
                        .semantics { selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, style = if (on) Type.button else Type.body, color = if (on) pal.onTonal else pal.ink2, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/** "Aggiornato al <dd mmm> · N/M con valore[ · <nome> datato <dd mmm>]" (1a-11). Without any value: "Nessun valore · 0/M con valore". */
fun freshnessText(asOf: LocalDate?, known: Int, of: Int, stale: StaleItem? = null): String =
    (asOf?.let { "Aggiornato al ${Fmt.day(it)}" } ?: "Nessun valore") + " · $known/$of con valore" +
        (stale?.let { " · ${it.label} datato ${Fmt.day(it.since)}" } ?: "")

/**
 * The freshness line shared by Pilastri and Futuro: a check glyph when every source has a current value, otherwise an
 * alert glyph and the warning tone (state in glyph and text, never by colour alone). [onAccent]: on the accent card.
 */
@Composable
fun FreshnessLine(text: String, complete: Boolean, modifier: Modifier = Modifier, onAccent: Boolean = false) {
    val pal = LocalPal.current
    val ink = when { !complete && onAccent -> pal.onAccent; !complete -> pal.ink; onAccent -> pal.accentSoft; else -> pal.ink2 }
    val glyph = when { !complete && onAccent -> pal.onAccent; !complete -> pal.ink; onAccent -> pal.accentLabel; else -> pal.brand }
    Row(modifier.fillMaxWidth().heightIn(min = 34.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (complete) Ic.checkCircle else Ic.alertCircle, if (complete) "completo" else "parziale", Modifier.size(14.dp), tint = glyph)
        Spacer(Modifier.width(6.dp))
        // No orphan on the last line: the last two words stay together (NBSP) and the paragraph is balanced where the platform can.
        val bound = androidx.compose.runtime.remember(text) { text.lastIndexOf(' ').takeIf { it > 0 }?.let { text.substring(0, it) + "\u00A0" + text.substring(it + 1) } ?: text }
        Text(bound, style = Type.small.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A white chart card: title left, a short label right ("in €", "ott 25 – ott 26"), then the chart. */
@Composable
fun ChartCard(title: String, trailing: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    TitledCard(title, modifier, trailing = if (trailing != null) { { CardTrailingText(trailing) } } else null) { content() }
}

/** The clear space between a chart legend row and the plot or axis labels above and below it (the 8 dp block gap of the layout rules). */
val ChartLegendGap = 8.dp

/** A legend row: [title], swatches with names, and a right-aligned note (the basis and the printed scale). */
@Composable
fun ChartLegend(title: String?, items: List<Pair<String, Color>>, note: String?, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = Type.tinyStrong, color = pal.brand)
        items.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(4.dp))
                Text(name, style = Type.tiny, color = pal.ink)
            }
        }
        Spacer(Modifier.weight(1f))
        if (note != null) Text(note, style = Type.tiny, color = pal.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
