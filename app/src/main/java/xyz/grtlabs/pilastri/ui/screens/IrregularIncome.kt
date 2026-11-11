package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.charts.*
import xyz.grtlabs.pilastri.ui.setup.PositionCatalog

/* ---------- Board 9: the irregular-income blocks of the Mese page (9-02 … 9-06) ---------- */

/**
 * The blocks for a person with irregular income (partita IVA, collaboratore, occasionale…), stacked after the regular Mese blocks:
 * 9-02 Entrate (12 months), 9-03 Compensi, 9-04 Scadenze (F24), 9-05 Previdenza per position. Each block has its own gate.
 * 9-06 "Da accantonare" has no block yet: it renders only once [accantonareReady] holds, and nothing else stands in for it.
 */
@Composable
fun IrregularIncomeBlocks(app: App, a: Archive, month: YearMonth, today: LocalDate, swipe: @Composable (@Composable (YearMonth) -> Unit) -> Unit) {
    val irregular = remember(a) { a.irregularPositions() }
    val showF24 = remember(a) { a.positionKinds().any { it in F24Kinds } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (irregular.isNotEmpty()) {
            swipe { m -> IncomeBarsCard(a, m, today) }
            swipe { m -> CompensiCard(a, m) }
        }
        if (showF24) ScadenzeCard(a, today)
        irregular.forEach { p -> swipe { m -> PrevidenzaCard(app, p, m.year) } }
    }
}

/** A white list card in the Versamenti style: title left, a short note right, then the rows. */
@Composable
private fun BlockCard(title: String, trailing: String?, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    TitledCard(title, titleMaxLines = 2, rows = true, trailing = trailing?.let { t -> { CardTrailingText(t) } }) {
        content()
    }
}

/* ---------- 9-02 Entrate, 12 mesi ---------- */

@Composable
private fun IncomeBarsCard(a: Archive, month: YearMonth, today: LocalDate) {
    val pal = LocalPal.current
    val bars = remember(a, month, today) { a.incomeBars(month, today) }
    val slots = remember(bars) { bars.map { BarSlot(it.month, listOf(it.cents), it.state) } }
    val description = remember(bars) {
        "Entrate dei 12 mesi fino a ${Fmt.monthShort(month)}: " + bars.joinToString("; ") { b ->
            Fmt.monthShort(b.month) + " " + when (b.state) {
                BarState.DATA -> Fmt.eur(b.cents)
                BarState.NONE -> "nessuna entrata"
                BarState.ND -> "n/d"
            }
        }
    }
    val nd = bars.count { it.state == BarState.ND }
    val note = listOfNotNull(
        if (month == YearMonth.from(today)) "${Fmt.monthShort(month)} fino a oggi" else null,
        if (nd > 0) "$nd ${if (nd == 1) "mese" else "mesi"} n/d" else null,
    ).joinToString(" · ").ifEmpty { "12 mesi completi" }
    ChartCard("Entrate", "12 mesi") {
        MonthBarsChart(slots, listOf(pal.brand), description, Modifier.padding(horizontal = 8.dp))
        FreshnessLine(note, complete = nd == 0)
    }
}

/* ---------- 9-03 Compensi ---------- */

@Composable
private fun CompensiCard(a: Archive, month: YearMonth) {
    val pal = LocalPal.current
    val rows = remember(a, month) { a.compensi(month) }
    BlockCard("Compensi", Fmt.monthShort(month)) {
        if (rows.isEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("—", style = Type.body, color = pal.ink2)
        }
        rows.forEach { r ->
            ListRow(
                title = "Compenso · " + (r.payer ?: "mittente n/d"),
                caption = Fmt.day(r.last) + if (r.count > 1) " · ${r.count} accrediti" else "",
                onClick = null,
                value = Fmt.signedCents(r.cents),
            )
        }
        Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text("Entrate di ${Fmt.month(month)}", style = Type.body, color = pal.ink2, modifier = Modifier.weight(1f))
            Text(if (rows.isEmpty()) "—" else Fmt.eur(rows.sumOf { it.cents }), style = Type.amount, color = pal.ink, textAlign = TextAlign.End)
        }
    }
}

/* ---------- 9-04 Scadenze (F24) ---------- */

@Composable
private fun ScadenzeCard(a: Archive, today: LocalDate) {
    val pal = LocalPal.current
    val rules = rememberRules()
    if (rules == null) return
    val lines = remember(a, rules, today) { a.f24Lines(rules, today) }
    BlockCard("Scadenze", "F24") {
        if (lines.isEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("—", style = Type.body, color = pal.ink2)
        }
        lines.forEachIndexed { i, l ->
            if (i > 0) Hairline()
            F24Row(l)
        }
    }
}

@Composable
private fun F24Row(l: F24Line) {
    val pal = LocalPal.current
    val title = l.label + " · " + (l.due?.let { Fmt.day(it) } ?: "data n/d") + when (val n = l.daysLeft) {
        null -> ""
        0L -> " · oggi"
        1L -> " · tra 1 giorno"
        else -> if (n > 1) " · tra $n giorni" else " · ${-n} giorni fa"
    }
    val value = l.amountCents?.let { Fmt.eurCents(it) } ?: "n/d"
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.body, color = pal.ink, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = Type.amount, color = pal.ink, textAlign = TextAlign.End)
            l.state?.let { F24Chip(it) }
        }
    }
}

/** The F24 state as a chip: glyph and text together, never colour alone. */
@Composable
private fun F24Chip(state: F24State) {
    val pal = LocalPal.current
    val (text, icon) = when (state) {
        F24State.PAGATO -> "pagato" to Ic.checkCircle
        F24State.DA_PAGARE -> "da pagare" to Ic.alertCircle
        F24State.NON_RILEVATO -> "non rilevato" to Ic.help
    }
    val bg = when (state) { F24State.PAGATO -> pal.goodBg; F24State.DA_PAGARE -> pal.chip; F24State.NON_RILEVATO -> pal.chip }
    val fg = when (state) { F24State.PAGATO -> pal.goodInk; F24State.DA_PAGARE -> pal.onChip; F24State.NON_RILEVATO -> pal.onChip }
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.heightIn(min = 24.dp).clip(shape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text(text, style = Type.tinyStrong, color = fg, maxLines = 1)
    }
}

/* ---------- 9-05 Previdenza per position ---------- */

@Composable
private fun PrevidenzaCard(app: App, p: IncomePosition, year: Int) {
    val pal = LocalPal.current
    var sheet by rememberSaveable(p.id, year) { mutableStateOf(false) }
    val paid = p.paidContributions.firstOrNull { it.year == year }
    val cents = paid?.amount?.value
    val title = "Previdenza" + (p.gestione.value?.known?.let { " · " + PositionCatalog.gestioneLabel(it) } ?: "")
    val shape = RoundedCornerShape(16.dp)
    val valueText = cents?.let { Fmt.eurCents(it) } ?: "n/d"
    BlockCard(title, null) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).pressable(shape, pal.pressTint, onClick = { app.hx?.tick(); sheet = true })
                .semantics(mergeDescendants = true) { role = Role.Button }.padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            FactLine("Contributi versati $year", valueText, note = paid?.originLabel())
        }
    }
    if (sheet) AmountSheet(
        title = "Contributi versati $year",
        hint = "Totale versato nell'anno",
        initial = cents?.let { editAmount(it) } ?: "",
        onSave = { c -> app.commit("Contributi salvati") { it.withDeclaredPaidContribution(p.id, year, c, app.today) } },
        onDismiss = { sheet = false },
        allowEmpty = true,
        onClear = if (cents != null) ({ app.commit("Contributi rimossi") { it.withDeclaredPaidContribution(p.id, year, null, app.today) } }) else null,
    )
}

/** The editable text of an amount in cents: "1250" or "1250,50". */
private fun editAmount(cents: Long): String {
    val whole = cents / 100
    val rest = kotlin.math.abs(cents % 100)
    return if (rest == 0L) "$whole" else "$whole,${rest.toString().padStart(2, '0')}"
}
