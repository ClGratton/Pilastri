package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.ai.AiConnectionsSection
import xyz.grtlabs.pilastri.ai.AssistantPage
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.charts.*

/**
 * Futuro (board 4a, cover 7d): the Lungo termine value now, the scenario's end value as a "stima", the money put in, the
 * Previdenza card, the composition of Lungo termine and the money put in per year. One basis toggle retargets every figure.
 * On the open Fold the scenario fan is the right-pane root ([ScenariosPane]); on the cover the "Scenario" row opens it (G-08).
 */
@Composable
fun FutureScreen(app: App, wide: Boolean) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val today = app.today
    val def = remember(a) { a.deflator() }
    val now = remember(a, def) { a.longTermNow(def) }
    val slices = remember(a) { a.longTermSources() }
    val scenario = remember(a, today, def) { a.futureScenario(today, def) }
    val versato = remember(a) { a.realCard(a.versatoInto(4)) }
    val fresh = remember(slices, today) { longTermFreshness(slices, today) }
    // The € gen 2026 side is the default when today's value can be indexed; without an index it reads n/d (4a-02).
    var real by rememberSaveable { mutableStateOf(now.realCents != null) }
    val basis = if (real) "€ gen 2026" else "nominale"

    Page(horizontal = PageSpec.gutter(wide), top = PageSpec.Top) {
      Column(Modifier.fillMaxWidth().pageContentTag(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RootHeader("Futuro", sampleSuffix(a).removePrefix(" · ").ifBlank { null }) // CR-70/K8: the freshness line owns the date
        // U-08: a side that can only give n/d says so in its label and is not selectable; the toggle never opens on it.
        BasisToggle(
            real, { v -> if (if (v) now.realCents != null else now.nominalCents != null) real = v },
            realLabel = if (now.realCents == null) "€ gen 2026 · n/d" else "€ gen 2026",
            nominalLabel = if (now.nominalCents == null) "nominale · n/d" else "nominale",
        )
        KpiCard(today, real, now, scenario, versato, fresh)
        // G-08: on the open Fold the fan is already the right-pane root, so this entry is neither drawn nor focusable there.
        if (!app.twoPane) {
            RowsCard {
                ListRow("Scenario", null, { app.go(Route.PaneRoot(Tab.FUTURO)) }, leading = { IconDisc(Ic.futuro, pal.lilacBg, pal.lilacInk, 36.dp) }, tag = "stima")
            }
        }
        PrevidenzaCard(app, a)
        CompositionCard(app, slices, real, def, now, today)
        YearlyCard(a, today, real, def, basis)
        PublicPensionCard(a)
      }
    }
}

/** Shown value of a figure that follows its target on the chart-update spring when the basis flips. Null stays null (n/d). */
@Composable
private fun rememberShown(cents: Long?): String? {
    val anim = rememberRetarget(listOf(cents?.toFloat()))
    return if (cents == null) null else if (anim[0].isRunning) Fmt.eur(anim[0].value.toLong()) else Fmt.eur(cents)
}

private fun band(low: Long, high: Long) = Fmt.eur(roundStima(low)).removeSuffix(Fmt.EUR_SUFFIX) + "–" + Fmt.eur(roundStima(high))

/** 4a-03, 4a-04, 4a-05 and 4a-06 on the accent card: "Oggi" as the hero, then the stima and the money put in side by side, then freshness. */
@Composable
private fun KpiCard(today: LocalDate, real: Boolean, now: LongTermNow, scenario: FutureScenario, versato: RealCard, fresh: LongTermFreshness) {
    val pal = LocalPal.current
    val nowCents = if (real) now.realCents else now.nominalCents
    val dated = now.date != null && ageDays(now.date, today) > GapDays
    val nowLabel = if (dated) "Dato del ${Fmt.day(now.date!!)}" else "Oggi"
    val nowCaption = if (real) "€ gen 2026 · nominale " + (now.nominalCents?.let { Fmt.eur(it) } ?: "n/d")
    else "nominale · € gen 2026 " + (now.realCents?.let { Fmt.eur(it) } ?: "n/d")
    val end = scenario.fan?.end
    val stima = end?.let { roundStima(it.median) }
    // The scenario is only defined in € gen 2026 (real growth rates): on the nominal side it keeps that basis and says so.
    val stimaCaption = if (end != null) band(end.low, end.high) + (if (!real) "\u00A0€ · € gen 2026" else "\u00A0€")
    else if (now.nominalCents == null) "manca il valore" else "manca l'indice ISTAT"
    val vCents = if (real) versato.indexedRealCents else versato.nominalTotalCents.takeIf { versato.totalCount > 0 }
    val vLabel = "Versato tuo"
    val vCaption = when {
        real && versato.indexedRealCents != null && versato.indexedCount < versato.totalCount -> "su ${versato.indexedCount} di ${versato.totalCount} versamenti"
        !real && versato.totalCount > 0 -> "${versato.totalCount} versamenti"
        else -> null
    }
    val vInfo = buildString {
        append("Soldi tuoi messi in Lungo termine, lordi: i prelievi non li compensano e il contributo del datore resta separato.\n\n")
        append("Nominale: ${Fmt.eurCents(versato.nominalTotalCents)} su ${versato.totalCount} versamenti.\n")
        if (versato.indexedRealCents != null) append("In euro di gennaio 2026 (ISTAT NIC): ${Fmt.eurCents(versato.indexedRealCents)} su ${versato.indexedCount} di ${versato.totalCount} versamenti.")
        else if (versato.totalCount > 0) append("Nessun versamento ha ancora un indice ISTAT compatibile: in euro di gennaio 2026 è n/d.")
    }
    val nowShown = rememberShown(nowCents); val stimaShown = rememberShown(stima); val vShown = rememberShown(vCents)
    AccentCard {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$nowLabel, Lungo termine: ${nowShown ?: "n/d"}, $nowCaption" }) {
                Text(nowLabel, style = Type.labelOnAccent, color = pal.accentLabel, maxLines = 1)
                FitText(nowShown ?: "n/d", Type.primaryFigure, pal.onAccent, Modifier.testTag(UiTag.PRIMARY_FIGURE))
                Text(nowCaption, style = Type.captionOnAccent, color = pal.accentSoft, maxLines = 1)
            }
            // D-12: both stats have a one-line label row of the same height, so the two figures share one baseline.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${scenario.horizon.endYear}, stima: ${stimaShown ?: "n/d"}, $stimaCaption" }) {
                    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                        Text("${scenario.horizon.endYear} · stima", style = Type.labelOnAccent, color = pal.accentLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FitText(stimaShown ?: "n/d", Type.secondaryFigure, pal.onAccent, Modifier.testTag(UiTag.SECONDARY_FIGURE))
                    Text(stimaCaption, style = Type.captionOnAccent, color = pal.accentSoft, maxLines = 2)
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vLabel, style = Type.labelOnAccent, color = pal.accentLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { InfoButton("Versato tuo", vInfo, Modifier.requiredSize(48.dp), tint = pal.accentSoft) }
                    }
                    FitText(vShown ?: if (versato.totalCount == 0) "—" else "n/d", Type.secondaryFigure, pal.onAccent, Modifier.testTag(UiTag.SECONDARY_FIGURE))
                    if (vCaption != null) Text(vCaption, style = Type.captionOnAccent, color = pal.accentSoft, maxLines = 2)
                }
            }
            FreshnessLine(freshnessText(fresh.asOf, fresh.known, fresh.of, fresh.stale), fresh.complete, onAccent = true)
        }
    }
}

/** 4a-08: the Lungo termine value as one stacked bar, a segment per source; a source without a value is a dashed n/d slot. */
@Composable
private fun CompositionCard(app: App, slices: List<SourceSlice>, real: Boolean, def: Deflator, now: LongTermNow, today: LocalDate) {
    val pal = LocalPal.current
    if (slices.isEmpty()) { EmptyNote("Lungo termine n/d", action = "Collega conti", onAction = { app.go(Route.Connections) }); return }
    val total = if (real) now.realCents else now.nominalCents
    val shares = slices.map { SourceShare(it.name, it.inBasis(real, def), it.date, it.stale(today)) }
    val shown = rememberShown(total)
    val desc = "Lungo termine ${shown ?: "n/d"}, " + shares.joinToString(", ") { it.name + " " + (it.cents?.let { c -> Fmt.eur(c) } ?: "n/d") + if (it.stale && it.date != null) ", datato ${Fmt.day(it.date)}" else "" }
    TitledCard("Lungo termine", verticalArrangement = Arrangement.spacedBy(10.dp), trailing = {
        Text(shown ?: "n/d", style = Type.amount, color = if (shown == null) pal.ink2 else pal.ink)
    }) {
        SourcesBar(shares, desc)
    }
}

/** 4a-09: the money put in per year since the first contribution, on its own printed scale; this year is to date. */
@Composable
private fun YearlyCard(a: Archive, today: LocalDate, real: Boolean, def: Deflator, basis: String) {
    val bars = remember(a, today, real, def) { a.yearlyContributions(today, real, def) }
    if (bars.isEmpty()) { EmptyNote("Versato tuo per anno", "nessun versamento registrato"); return }
    val top = niceCeil((bars.filter { it.state == BarState.DATA }.maxOfOrNull { it.cents ?: 0L } ?: 0L).coerceAtLeast(100L))
    val month = Fmt.monthShort(YearMonth.from(today))
    val desc = buildString {
        append("Versato tuo per anno, $basis, scala da 0 a ${axisLabel(top)}\u00A0€: ")
        append("${bars.count { it.state == BarState.DATA }} anni con versamenti, ${bars.count { it.state == BarState.NONE }} senza, ${bars.count { it.state == BarState.ND }} n/d. ")
        append("${today.year} fino a $month.")
    }
    ChartCard("Versato tuo per anno", basis) {
        YearBarsChart(bars, month, desc)
    }
}

/**
 * Kept below the board blocks (DIFFERENT from 4a): the public-pension estimate has no other home. The fund rows that used to
 * sit here live on the Previdenza page now.
 */
@Composable
private fun PublicPensionCard(a: Archive) {
    val pal = LocalPal.current
    val inps = a.pension.inps.maxByOrNull { it.asOf }
    if (inps != null) TitledCard("Pensione pubblica", trailing = {
        Tag("scenario INPS")
        InfoButton("Ipotesi della stima INPS", inps.assumptions.ifBlank { "Nessuna ipotesi registrata." } + "\n\nFonte: ${inps.source}")
    }) {
        Text("${Fmt.eur(inps.monthlyNetCents)} al mese", style = Type.secondaryFigure, color = pal.ink)
        Text("stima del ${Fmt.day(inps.asOf)}" + (inps.retirementDate?.let { " · dal ${Fmt.dayYear(it)}" } ?: ""), style = Type.small, color = pal.ink2)
    } else TitledCard("Pensione pubblica", trailing = {
        HelpButton("Pensione pubblica", "Copia il risultato del simulatore INPS \"La mia pensione futura\" e chiedi all'agente di registrarlo: resta uno scenario INPS, con data e ipotesi.")
    }) {
        Text("nessuna stima", style = Type.small, color = pal.ink2)
    }
}

/* ---------- Scenario (4a-10, 4a-11, 4a-12): right-pane root on two panes, a pushed page on the cover ---------- */

/** Right-pane root of Futuro on two panes (the cover pushes the same page): the fan, its table, and one secondary action. */
@Composable
fun ScenariosPane(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val inPane = LocalInPane.current
    val today = app.today
    val scenario = remember(a, today) { a.futureScenario(today) }
    val now = remember(a) { a.longTermNow() }
    val subtitle = "partenza ${Fmt.dayYear(scenario.startDate)} · € gen 2026"
    val help = "Una stima in euro di gennaio 2026, non una previsione né un consiglio. Parte dal valore di Lungo termine e aggiunge ogni anno la media dei tuoi versamenti; " +
        "la mediana cresce del $MedianRate% reale all'anno, le bande del $NearLow–$NearHigh% e del $FarLow–$FarHigh%. Il datore di lavoro e le tasse non sono inclusi."
    // Wave 8 L1: with no fan the state card and "Scenari tuoi" sit directly under the header (first content at the same y as the other panes), not centred in the pane height.
    Page(top = PageSpec.Top, horizontal = PageSpec.gutter(app.twoPane)) {
        // G-12: pane root or pushed page, the same header (Back is the island's on the open Fold).
        BackHeader("Scenario", subtitle, if (inPane) null else ({ app.back() }), backLabel = "Indietro a Futuro") { Tag("stima"); HelpButton("Scenario", help) }
        val fan = scenario.fan
        if (fan == null) EmptyNote("Scenario n/d", if (now.nominalCents == null) "Lungo termine non ha ancora un valore" else "serve un indice ISTAT compatibile per gli euro di gennaio 2026")
        else {
            val end = fan.end
            val desc = "Scenario in euro di gennaio 2026 da ${Fmt.dayYear(fan.startDate)}, ${fan.years} anni: dal valore di partenza ${Fmt.eur(fan.startCents)} " +
                "la mediana ($MedianRate% reale) arriva a ${Fmt.eur(roundStima(end.median))} nel ${end.year}, la banda $FarLow–$FarHigh% da ${Fmt.eur(roundStima(end.low))} a ${Fmt.eur(roundStima(end.high))}."
            CardColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // D-13: board 4a draws the fan 420 high in the pane (viewBox 324x420); on the cover the usual 188 dp.
                FanChart(
                    fan.startDate.year, fan.median,
                    fan.paths.getValue(NearHigh) to fan.paths.getValue(NearLow), fan.paths.getValue(FarHigh) to fan.paths.getValue(FarLow), desc,
                    height = if (inPane) 420.dp else 188.dp,
                )
                ChartLegend("stima", listOf("mediana $MedianRate%" to pal.fill, "banda $NearLow–$NearHigh%" to pal.ribbonIn, "banda $FarLow–$FarHigh%" to pal.ribbonOut), "€ gen 2026")
            }
            ScenarioTable(fan, scenario)
        }
        // 4a-12 (accepted deviation): the saved scenarios and the calculator, one secondary action.
        // U-08: a row with a chevron, so it reads as actionable.
        RowsCard { ListRow("Scenari tuoi", null, { app.go(Route.Scenarios) }) }
    }
}

/** 4a-11: year / median / band 2–6% at +10, +20, +30 years (or the horizon), and the contribution and horizon it rests on. */
@Composable
private fun ScenarioTable(fan: Fan, scenario: FutureScenario) {
    val pal = LocalPal.current
    val h = scenario.horizon
    CardColumn {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("anno", style = Type.tinyStrong, color = pal.brand, modifier = Modifier.weight(0.7f))
            Text("mediana", style = Type.tinyStrong, color = pal.brand, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            Text("banda $FarLow–$FarHigh%", style = Type.tinyStrong, color = pal.brand, modifier = Modifier.weight(1.6f), textAlign = TextAlign.End)
        }
        Hairline()
        milestoneOffsets(fan.years).forEach { off ->
            val r = fan.row(off)
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(r.year.toString(), style = Type.rowTitle, color = pal.ink, modifier = Modifier.weight(0.7f))
                Text(Fmt.eur(roundStima(r.median)), style = Type.amount, color = pal.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(band(r.low, r.high) + Fmt.EUR_SUFFIX, style = Type.small, color = pal.ink2, modifier = Modifier.weight(1.6f), textAlign = TextAlign.End)
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(scenario.yearlyReal?.let { "versamenti ${Fmt.eur(it)} l'anno" } ?: "versamenti n/d", style = Type.small, color = pal.ink)
                    if (scenario.yearlyReal != null) Tag("media")
                }
                Text(
                    if (scenario.yearlyReal == null || scenario.yearlySince == null) "stima sul solo valore di partenza" else "dal ${Fmt.month(YearMonth.from(scenario.yearlySince))} ${scenario.yearlySince.year}",
                    style = Type.tiny, color = pal.ink2,
                )
                Text("orizzonte ${h.years} anni · " + (h.retirement?.let { "pensionamento dal ${Fmt.dayYear(it)}" } ?: "predefinito"), style = Type.tiny, color = pal.ink2)
            }
            Text("€ gen 2026", style = Type.tiny, color = pal.ink2)
        }
    }
}

/* ---------- Agente ---------- */

@Composable
fun AgentScreen(app: App) {
    val a = app.archive ?: return
    AssistantPage(a, app.aiCommit, Modifier.fillMaxSize(), app.assistant)
}

/** Right-pane root of Agente on two panes: the assistants' connections and the context of the last question. */
@Composable
fun AgentSidePane(app: App) {
    val pal = LocalPal.current
    Page(top = PageSpec.Top, horizontal = PageSpec.gutter(app.twoPane)) {
        PageHeader("Assistenti", app.assistant.draftContext?.let { "ultima domanda da: $it" })
        AiConnectionsSection()
    }
}
