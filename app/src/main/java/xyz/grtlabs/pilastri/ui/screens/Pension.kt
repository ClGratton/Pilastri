package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.setup.PositionCatalog

/**
 * Previdenza (Council 4 d, board G3-4b): one block per position. Privato: TFR destination and funds; pubblico: regime, funds and
 * the TFR figurativo strip only beside a negoziale fund; autonomo/collaboratore: gestione and contributions; pensionato: the
 * pension slip. TFR accrual is n/d. No data: one n/d line. Then the deduction card and the "Fonti e regole" row.
 */
@Composable
fun PensionScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val views = remember(a) { a.fundViews() }
    val blocks = remember(a) { previdenzaBlocks(a.positions) }
    val employees = blocks.filter { it.kind.known == PositionKind.DIPENDENTE_PRIVATO || it.kind.known == PositionKind.DIPENDENTE_PUBBLICO }
    fun fundsOf(p: IncomePosition): List<PensionFund> = if (p !in employees) emptyList()
        else a.funds.filter { it.positionId.value == p.id || (it.positionId.value == null && employees.size == 1) }
    // Board 4b: the page is header + position blocks + deduction + one "Fonti e regole" row; no Registra pill and no movements block
    // (the ledger lives in "Quanto hai messo"). Same Page scaffold as the other pages.
    Page(horizontal = PageSpec.gutter(app.twoPane), top = PageSpec.Top) {
        BackHeader("Previdenza", Fmt.day(app.today) + sampleSuffix(a), { app.back() })
        if (blocks.isEmpty() && a.funds.isEmpty()) CardColumn { FactLine("Previdenza", "n/d") }
        blocks.forEach { p -> key(p.id) { PositionBlock(app, a, p, fundsOf(p), views) } }
        val loose = a.funds.filter { f -> blocks.none { p -> f in fundsOf(p) } }
        if (loose.isNotEmpty()) RowsCard { loose.forEach { FundCard(app, a, it, views, RowInset) } }
        DeductionDetailCard(app, a)
        RowsCard { ListRow("Fonti e regole", null, { app.go(Route.Rules) }) }
    }
}

@Composable
private fun <E : Enum<E>> SourcedLine(label: String, s: Sourced<Coded<E>>, name: (E) -> String) {
    val v = s.value?.known
    val note = listOfNotNull(originWord(s.origin), sourceDocLabel(s.origin, s.fieldLabel, s.asOf, Fmt::monthShort)).joinToString(" · ").ifEmpty { null }
    FactLine(label, v?.let(name) ?: "n/d", note = note)
}

/** Whether a position's block has anything to show beyond n/d (4b-02: no data = one n/d line). */
private fun hasBlockData(kind: PositionKind, p: IncomePosition, funds: List<PensionFund>, slip: ExternalMovement?): Boolean = when (kind) {
    PositionKind.DIPENDENTE_PRIVATO -> p.severanceDestination.value != null || funds.isNotEmpty()
    PositionKind.DIPENDENTE_PUBBLICO -> p.severanceRegime.value != null || funds.isNotEmpty()
    PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO, PositionKind.COLLABORATORE -> p.gestione.value != null
    PositionKind.PENSIONATO -> slip != null
    else -> false
}

@Composable
private fun PositionBlock(app: App, a: Archive, p: IncomePosition, funds: List<PensionFund>, views: List<FundView>) {
    val pal = LocalPal.current
    val kind = p.kind.known ?: return
    val slip = remember(a, p.id) {
        if (kind != PositionKind.PENSIONATO) null
        else a.externalMovements.filter { m -> m.signedCents > 0 && a.planning.incomeRules.any { r -> r.confirmedAt != null && r.positionId == p.id && r.matches(m) } }.maxByOrNull { it.date }
    }
    TitledCard(PositionCatalog.label(kind), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!hasBlockData(kind, p, funds, slip)) { FactLine("Previdenza", "n/d"); return@TitledCard }
        blockParts(kind, funds).forEach { part ->
            when (part) {
                BlockPart.REGIME -> {
                    Text(regimeLine(p.severanceRegime), style = Type.rowTitle, color = pal.ink)
                    sourceDocLabel(p.severanceRegime.origin, p.severanceRegime.fieldLabel, p.severanceRegime.asOf, Fmt::monthShort)?.let { Text(it, style = Type.small, color = pal.ink2) }
                }
                BlockPart.TFR_DESTINATION -> SourcedLine("Destinazione TFR", p.severanceDestination) { d ->
                    when (d) { SeveranceDestination.AZIENDA -> "in azienda"; SeveranceDestination.TESORERIA_INPS -> "Tesoreria INPS"; SeveranceDestination.FONDO -> "al fondo"; SeveranceDestination.ND -> "n/d" }
                }
                BlockPart.FUNDS ->
                    if (funds.isEmpty()) FactLine("Fondi", "n/d")
                    else Column(Modifier.fillMaxWidth()) { funds.forEach { FundCard(app, a, it, views) } }
                BlockPart.TFR_FIGURATIVO -> TfrFigurativo()
                BlockPart.GESTIONE -> SourcedLine("Gestione", p.gestione) { PositionCatalog.gestioneLabel(it) }
                BlockPart.CONTRIBUTIONS_BY_GESTIONE -> {
                    val paid = p.latestPaid()
                    val cents = paid?.amount?.value
                    if (paid != null && cents != null) FactLine("Contributi versati ${paid.year}", Fmt.eur(cents), note = paid.originLabel())
                    else FactLine("Contributi versati", "n/d")
                }
                BlockPart.PENSION_SLIP -> FactLine("Cedolino della pensione", slip?.let { "${Fmt.eurCents(it.signedCents)} · ${Fmt.day(it.date)}" } ?: "n/d")
            }
        }
    }
}

private fun formWord(f: PensionFund): String = when (f.formType.value?.known) {
    FundFormType.NEGOZIALE -> "negoziale"
    FundFormType.APERTO -> "aperto"
    FundFormType.PIP -> "PIP"
    FundFormType.PREESISTENTE -> "preesistente"
    FundFormType.ND, null -> "forma n/d"
}

/** 4b-04: name and form, "al <data>" or "datato <data>", then the two stats "tuo" and "datore". */
@Composable
private fun FundCard(app: App, a: Archive, f: PensionFund, views: List<FundView>, inset: androidx.compose.ui.unit.Dp = 0.dp) {
    val pal = LocalPal.current
    val account = a.accounts.firstOrNull { it.fundId == f.id }
    val v = views.firstOrNull { it.account.id == account?.id }
    val stamp = fundStamp(v?.statement?.positionDate ?: v?.value?.date, app.today)
    val own = ownFigure(v?.ownCents)
    val employer = employerFigure(v?.statement?.employerCents ?: v?.employerCents, f)
    fun text(x: PartyFigure) = when (x) { is PartyFigure.Amount -> Fmt.eur(x.cents); PartyFigure.Nessuno -> "nessuno"; PartyFigure.Nd -> "n/d" }
    val name = f.name.value ?: "Fondo"
    val stampText = stamp?.let { (if (it.stale) "datato " else "al ") + Fmt.day(it.date) } ?: "dati n/d"
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (account != null) Modifier.pressable(shape, pal.pressTint, onClick = { app.go(Route.Account(account.id)) }) else Modifier.clip(shape))
            .semantics(mergeDescendants = true) { contentDescription = "$name, ${formWord(f)}, $stampText, tuo ${text(own)}, datore ${text(employer)}" }
            .padding(horizontal = inset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Type.rowTitle, color = pal.ink, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
                Spacer(Modifier.width(6.dp))
                Tag(formWord(f))
            }
            Text(stampText, style = Type.small, color = pal.ink2)
        }
        Stat("tuo", text(own)); Spacer(Modifier.width(14.dp)); Stat("datore", text(employer))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val pal = LocalPal.current
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = Type.tiny, color = pal.ink2)
        Text(value, style = Type.amount, color = pal.ink, textAlign = TextAlign.End)
    }
}

/** A separate hatched strip, never stacked and outside P4, the ceiling and the totals. Accrual is n/d (Council 4 d). */
@Composable
private fun TfrFigurativo() {
    val pal = LocalPal.current
    val hatch = pal.brand.copy(alpha = 0.22f)
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(pal.card).border(1.dp, pal.cardLine, shape)
        .drawBehind {
            val step = 8.dp.toPx(); var x = -size.height
            while (x < size.width) { drawLine(hatch, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5.dp.toPx()); x += step }
        }.semantics(mergeDescendants = true) { contentDescription = "TFR figurativo, stima, fuori dai totali, n/d" }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("TFR figurativo · stima", style = Type.smallStrong, color = pal.ink)
            Text("fuori dai totali", style = Type.small, color = pal.ink3)
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(pal.chip).padding(horizontal = 12.dp, vertical = 4.dp)) { Text("n/d", style = Type.amount, color = pal.onChip) }
    }
}
