package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.*
import xyz.grtlabs.pilastri.ui.*

private fun eventLabel(type: EventType): String = when (type) {
    EventType.PERSONAL_CONTRIBUTION -> "Versamento tuo"
    EventType.EMPLOYER_CONTRIBUTION -> "Contributo del datore"
    EventType.WITHDRAWAL -> "Prelievo"
    EventType.INCOME -> "Provento"
    EventType.FEE -> "Costo"
    else -> type.name
}

private fun unavailable(reason: ContributionAmountUnavailableReason): String = when (reason) {
    ContributionAmountUnavailableReason.NO_RECORDS -> "non registrati"
    ContributionAmountUnavailableReason.MISSING_JANUARY_2026_BASE -> "manca gen 2026"
    ContributionAmountUnavailableReason.MISSING_MONTH_INDEX -> "indice mancante"
    ContributionAmountUnavailableReason.INCOMPATIBLE_INDEX_SERIES -> "serie diverse"
    ContributionAmountUnavailableReason.INVALID_INDEX -> "indice non valido"
    ContributionAmountUnavailableReason.OVERFLOW -> "totale fuori scala"
}

/** Rows a ledger adds each time the end of the page comes near (lazy scrolling instead of a "Tutte" expander). */
internal const val LedgerStep = 30

/**
 * A scrolling page body like [Page] whose ledgers load as you scroll (lazy-UX #17): [content] gets a row [limit] that grows by
 * [LedgerStep] whenever the end is within about one screen and [total] rows are not all shown yet. [resetKey] restarts the
 * count (a different pillar or a changed archive).
 */
@Composable
internal fun LedgerPage(total: Int, resetKey: Any?, horizontal: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.(limit: Int) -> Unit) {
    val scroll = rememberScrollState()
    var limit by remember(resetKey) { mutableIntStateOf(LedgerStep) }
    val totalNow by rememberUpdatedState(total)
    LaunchedEffect(scroll, resetKey) {
        snapshotFlow { scroll.maxValue - scroll.value }.collect { left -> if (left < 1200 && limit < totalNow) limit += LedgerStep }
    }
    Column(
        // Same scaffold as Page (spacing 8, page-content tag), so a page built on it lines up with the others.
        Modifier.fillMaxSize().pageContentTag().verticalScroll(scroll).padding(start = horizontal, end = horizontal, top = PageSpec.Top, bottom = 16.dp + LocalDockInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content(limit) }
}

private val LedgerTypes = setOf(EventType.PERSONAL_CONTRIBUTION, EventType.EMPLOYER_CONTRIBUTION, EventType.WITHDRAWAL, EventType.INCOME, EventType.FEE)

private fun ledgerOf(a: Archive, pillar: Int): List<LedgerEvent> = a.events.filter { it.pillar == pillar && it.type in LedgerTypes }.sortedByDescending { it.date }

/**
 * "Quanto hai messo" in detail: every recorded flow kind kept apart (own, employer, withdrawals, income, costs), each in
 * nominal euros and in January-2026 euros when every month has a compatible ISTAT index. Withdrawals are never netted.
 * The pillar is a pager of four pages (lazy-UX #7): swipe the summary or the list, the selector follows on its spring, the
 * neighbouring pillar's name peeks at the edge; the selector stays tappable.
 */
@Composable
fun ContributionsScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    var pillar by rememberSaveable { mutableIntStateOf(4) }
    var registerFor by rememberSaveable { mutableStateOf<Int?>(null) }
    val summary = remember(a) { summarizeContributions(a.events, a.indices.associate { it.month to ContributionIndex(it.value, it.source) }) }
    val strip = rememberPeriodState(pillar - 1, PillarNames.size)
    val names = remember { PillarNames.map { if (it == "Lungo termine") "Lungo t." else it } }
    val total = remember(a, pillar) { ledgerOf(a, pillar).size }
    LedgerPage(total, listOf(pillar, a.events.size), PageSpec.Gutter) { limit ->
        BackHeader("Quanto hai messo", "per provenienza" + sampleSuffix(a), { app.back() }) {
            HelpButton("Quanto hai messo", "Importi lordi: i prelievi restano a parte perché la loro origine non si conosce, e nulla di questo è un rendimento. Gli euro di gennaio 2026 usano l'indice ISTAT NIC del mese di ogni movimento: se anche un solo mese non ha indice (prima del 2026, o non ancora pubblicato) la colonna resta \"—\"; la card della home mostra la parte indicizzata.")
        }
        val selector: @Composable () -> Unit = {
            Segmented(names, pillar - 1, { pillar = it + 1 })
            PeriodLabels(strip, { names[it] }, { i -> app.hx?.tick(); pillar = i + 1 }, null, Type.smallStrong, pal.ink2, showCurrent = false, gutter = PageSpec.Gutter, inset = Motion.periodInset(app.twoPane), wide = app.twoPane)
        }
        BoxWithConstraints {
            if (maxWidth >= 640.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("Tutti i pilastri", summary.allPillars, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { selector() }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("Tutti i pilastri", summary.allPillars)
                selector()
            }
        }
        // G-11: the summary card and the registrations are two strips on the one state, so each neighbour sliver follows its card.
        val g = PageSpec.Gutter
        PeriodSwipe(strip, { i -> pillar = i + 1 }, gutter = g, inset = Motion.periodInset(app.twoPane), wide = app.twoPane) { i -> SummaryCard(pillarName(i + 1), summary.byPillar.getValue(i + 1)) }
        PeriodSwipe(strip, { i -> pillar = i + 1 }, gutter = g, inset = Motion.periodInset(app.twoPane), wide = app.twoPane, primary = false) { i ->
            PillarLedger(a, i + 1, limit) { registerFor = it }
        }
    }
    registerFor?.let { p -> RegisterSheet(app, p, null, RegMode.CONTRIBUTION) { registerFor = null } }
}

/** One page of the second strip: the pillar's registrations (an empty pillar offers "Registra"). */
@Composable
private fun PillarLedger(a: Archive, pillar: Int, limit: Int, onRegister: (Int) -> Unit) {
    val pal = LocalPal.current
    val shown = remember(a, pillar) { ledgerOf(a, pillar) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Registrazioni · ${pillarName(pillar)}")
        if (shown.isEmpty()) EmptyNote("Nessuna registrazione", action = "Registra", onAction = { onRegister(pillar) })
        else CardColumn(rows = true) {
            shown.take(limit).forEach { e ->
                val acc = e.accountId?.let { id -> a.accounts.firstOrNull { it.id == id }?.name }
                ListRow(eventLabel(e.type), "${Fmt.dayYear(e.date)}" + (acc?.let { " · $it" } ?: "") + " · ${e.source.removePrefix("Dati di esempio · ").take(48)}", null,
                    value = Fmt.eurCents(e.amount.cents), valueColor = if (e.type == EventType.EMPLOYER_CONTRIBUTION) pal.brand else null)
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, s: PillarContributionSummary, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        CardColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeader(title)
            if (!compact) Row {
                Spacer(Modifier.weight(1.3f))
                Text("nominale", style = Type.tiny, color = pal.ink2, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text("€ gen 2026", style = Type.tiny, color = pal.ink2, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            Line("Versamenti tuoi", s.personalGross, compact, strong = true)
            Line("Datore · separato", s.employerGross, compact)
            Hairline()
            Line("Prelievi", s.withdrawals, compact)
            Line("Proventi", s.income, compact)
            Line("Costi", s.fees, compact)
        }
    }
}

@Composable
private fun Line(label: String, s: ContributionCategorySummary, compact: Boolean, strong: Boolean = false) {
    val pal = LocalPal.current
    val none = (s.nominal as? ContributionAmount.Unavailable)?.reason == ContributionAmountUnavailableReason.NO_RECORDS
    if (compact) Column {
        Text(label, style = if (strong) Type.rowTitle else Type.body, color = pal.ink)
        if (none) Text("non registrati", style = Type.small, color = pal.ink2)
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Amount(s.nominal, "nominale", Modifier.weight(1f)); Amount(s.realJanuary2026, "€ gen 2026", Modifier.weight(1f))
        }
    } else Row(verticalAlignment = Alignment.Top) {
        Text(label, style = if (strong) Type.rowTitle else Type.body, color = pal.ink, modifier = Modifier.weight(1.3f))
        if (none) Text("non registrati", style = Type.small, color = pal.ink2, textAlign = TextAlign.End, modifier = Modifier.weight(2f))
        else { Amount(s.nominal, null, Modifier.weight(1f)); Amount(s.realJanuary2026, null, Modifier.weight(1f)) }
    }
}

@Composable
private fun Amount(amount: ContributionAmount, caption: String?, modifier: Modifier) {
    val pal = LocalPal.current
    Column(modifier, horizontalAlignment = if (caption == null) Alignment.End else Alignment.Start) {
        if (caption != null) Text(caption, style = Type.tiny, color = pal.ink2)
        when (amount) {
            is ContributionAmount.Available -> Text(Fmt.eurCents(amount.amount.cents), style = Type.amount, color = pal.ink)
            is ContributionAmount.Unavailable -> { Text("—", style = Type.amount, color = pal.ink); Text(unavailable(amount.reason), style = Type.tiny, color = pal.ink2, textAlign = TextAlign.End) }
        }
    }
}
