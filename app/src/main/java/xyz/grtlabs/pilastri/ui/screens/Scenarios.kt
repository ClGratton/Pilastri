package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.ui.platform.testTag
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.*
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.charts.ChartLegend
import xyz.grtlabs.pilastri.ui.charts.FanChart

private fun centsInput(c: Long) = BigDecimal.valueOf(c, 2).stripTrailingZeros().toPlainString().replace('.', ',')
private fun parseRate(v: String): BigDecimal? = v.trim().replace(',', '.').takeIf { it.matches(Regex("-?\\d+(\\.\\d{1,6})?")) }?.let { runCatching { BigDecimal(it) }.getOrNull() }
private fun BigDecimal.okRate() = this > BigDecimal(-100) && this <= BigDecimal(1_000) && scale() <= 6
private fun refLabel(r: ProjectionPriceReference) = if (r == ProjectionPriceReference.START_2026) "inizio 2026" else "fine 2026"

/** The result follows the typed assumptions after this pause (lazy-UX #6); no "Calcola". */
private const val LiveDebounceMs = 280L
/** A swiped scenario leaves its row on the page spring for this long before the archive changes; the toast then offers "Annulla". */
private const val LeaveMs = 260L

/**
 * The scenario calculator: only the assumptions you type, years 2026–2085, growth on the opening balance and your and
 * the employer's additions at each year end, kept apart. The result follows the inputs as they change; the year-by-year
 * values are a line. Saved scenarios keep their source and leave by a swipe (or the editor's "Elimina") with an Undo;
 * results are always labelled as a scenario and in hypothetical purchasing power, never as a forecast.
 */
@Composable
fun ScenarioEditorScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("2026") }
    var end by rememberSaveable { mutableStateOf("2085") }
    var initial by rememberSaveable { mutableStateOf("") }
    var own by rememberSaveable { mutableStateOf("") }
    var employer by rememberSaveable { mutableStateOf("") }
    var growth by rememberSaveable { mutableStateOf("") }
    var inflation by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf(ProjectionPriceReference.START_2026.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ProjectionOutcome?>(null) }
    var gone by remember { mutableStateOf(setOf<String>()) }
    val nameFocus = remember { FocusRequester() }

    fun assumptions(): ProjectionAssumptions? {
        val f = start.toIntOrNull(); val l = end.toIntOrNull()
        val i = parseEuroInput(initial.ifBlank { "0" }); val p = parseEuroInput(own.ifBlank { "0" }); val e = parseEuroInput(employer.ifBlank { "0" })
        val g = parseRate(growth); val inf = parseRate(inflation)
        error = when {
            f == null || f !in 2026..2085 -> "Anno iniziale dal 2026 al 2085."
            l == null || l !in f..2085 -> "Anno finale uguale o successivo all'iniziale, fino al 2085."
            i == null || p == null || e == null -> listOf(initial, own, employer).firstNotNullOfOrNull { (readEuroInput(it.ifBlank { "0" }) as? EuroInput.Invalid)?.message } ?: "Controlla gli importi."
            g == null || !g.okRate() -> "Crescita annua tra −100% escluso e 1.000%, al massimo 6 decimali."
            inf == null || !inf.okRate() -> "Inflazione annua tra −100% esclusa e 1.000%, al massimo 6 decimali."
            else -> null
        }
        if (error != null) return null
        return runCatching { ProjectionAssumptions(f!!, l!!, i!!, p!!, e!!, g!!, inf!!, ProjectionPriceReference.valueOf(reference)) }.getOrElse { error = "Controlla i valori."; null }
    }
    fun load(s: SavedProjection) {
        selected = s.id; name = s.name; source = s.source; reference = s.assumptions.priceReference.name
        start = s.assumptions.startYear.toString(); end = s.assumptions.endYear.toString()
        initial = centsInput(s.assumptions.initialWealthCents); own = centsInput(s.assumptions.annualPersonalAdditionCents); employer = centsInput(s.assumptions.annualEmployerAdditionCents)
        growth = s.assumptions.annualNominalGrowthPercent.toPlainString().replace('.', ','); inflation = s.assumptions.annualInflationPercent.toPlainString().replace('.', ',')
        error = null; result = calculateProjection(s.assumptions); app.hx?.tick()
    }
    fun reset() { selected = null; name = ""; source = ""; start = "2026"; end = "2085"; initial = ""; own = ""; employer = ""; growth = ""; inflation = ""; reference = ProjectionPriceReference.START_2026.name; result = null; error = null }
    fun save() {
        val asm = assumptions() ?: return
        if (name.isBlank()) { error = "Dai un nome allo scenario."; return }
        if (source.isBlank()) { error = "Indica da dove vengono le ipotesi (anche \"scelta mia\")."; return }
        val now = Instant.now().toString()
        val saved = SavedProjection(selected ?: UUID.randomUUID().toString(), name.trim().take(200), asm, source.trim().take(500), now)
        app.commit("Scenario salvato.", then = { selected = saved.id; result = calculateProjection(asm) }) { cur ->
            val original = cur.projections.firstOrNull { it.id == saved.id }
            if (original == null) cur.withProjection(saved) else cur.correctProjection(original, saved, now)
        }
    }
    // Delete without a dialog: the row leaves on the page spring, the archive changes, a toast offers "Annulla" (lazy-UX #6).
    fun remove(s: SavedProjection) {
        scope.launch {
            val wasSelected = selected == s.id
            gone = gone + s.id
            delay(LeaveMs)
            app.commit(null, then = {
                gone = gone - s.id
                if (wasSelected) reset()
                app.say("Scenario eliminato.", "Annulla") { app.commit("Annullato.", then = { if (wasSelected) load(s) }) { cur -> cur.withProjection(s) } }
            }) { it.withoutProjection(s.id) }
        }
    }
    // Live result: recomputed shortly after the last keystroke; nothing (and no error) while the two rates are still empty.
    LaunchedEffect(start, end, initial, own, employer, growth, inflation, reference) {
        if (growth.isBlank() || inflation.isBlank()) { result = null; error = null; return@LaunchedEffect }
        delay(LiveDebounceMs)
        result = assumptions()?.let(::calculateProjection)
    }

    Page {
        BackHeader("Scenari", "ipotesi tue" + sampleSuffix(a), { app.back() }) {
            HelpButton("Scenari", "Uno scenario non è una previsione né un consiglio: usa solo le ipotesi che inserisci, non legge i tuoi saldi, non stima tasse o pensione. La crescita si applica al saldo di inizio anno; i versamenti tuoi e del datore arrivano a fine anno e restano separati. L'inflazione è un'ipotesi, distinta dagli indici ISTAT usati per i versamenti reali. Scorri uno scenario salvato verso sinistra per eliminarlo: puoi annullare.")
        }
        // Saved scenarios as a compact selectable list; a swipe to the left removes one.
        if (a.projections.isEmpty()) EmptyNote("Nessuno scenario salvato.", action = "Nuovo scenario", onAction = { reset(); runCatching { nameFocus.requestFocus() } })
        else RowsCard {
            a.projections.forEach { s ->
                key(s.id) {
                    Reveal(s.id !in gone) {
                        SwipeDecide(null, null, "Elimina", { remove(s) }) {
                            Box(Modifier.background(pal.card)) {
                                ListRow(s.name, "${s.assumptions.startYear}–${s.assumptions.endYear} · salvato ${runCatching { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALY).withZone(ZoneId.systemDefault()).format(Instant.parse(s.savedAt)) }.getOrDefault("")}",
                                    { load(s) }, selected = selected == s.id, chevron = false)
                            }
                        }
                    }
                }
            }
            ActionRow(Modifier.padding(start = RowInset, end = RowInset, bottom = RowVerticalInset)) { PillButton("Nuovo scenario", { reset() }, kind = Btn.GHOST, icon = Ic.plus) }
        }
        // Editor
        BoxWithConstraints {
            val wide = maxWidth >= 560.dp
            CardColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pair2(wide, { TextInput(name, { name = it }, "Nome", it.focusRequester(nameFocus)) }, { TextInput(source, { source = it }, "Fonte delle ipotesi", it) })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(start, { start = it }, "Dal", Modifier.weight(1f), number = true, center = true)
                    TextInput(end, { end = it }, "Al", Modifier.weight(1f), number = true, center = true)
                }
                TextInput(initial, { initial = it }, "Patrimonio iniziale €", number = true, center = true, big = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(own, { own = it }, "Tuoi all'anno €", Modifier.weight(1f), number = true, center = true)
                    TextInput(employer, { employer = it }, "Datore all'anno €", Modifier.weight(1f), number = true, center = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextInput(growth, { growth = it }, "Crescita % annua", Modifier.weight(1f), number = true, center = true)
                    TextInput(inflation, { inflation = it }, "Inflazione % annua", Modifier.weight(1f), number = true, center = true)
                }
                error?.let { Text(it, style = Type.small, color = pal.overInk) }
                // Elimina: the accessible fallback of the swipe (same removal, same Undo).
                RoleActionRow("scenario", spacing = 8.dp, end = true,
                    secondary = a.projections.firstOrNull { it.id == selected }?.let { sel -> { m -> PillButton("Elimina", { remove(sel) }, m, kind = Btn.OUTLINE) } },
                    confirm = { m -> PillButton("Salva", { save() }, m, kind = Btn.PRIMARY) })
            }
        }
        when (val r = result) {
            is ProjectionOutcome.Available -> {
                val last = r.years.last()
                val first = r.years.first()
                val ref = refLabel(ProjectionPriceReference.valueOf(reference))
                AccentCard(Modifier.animateContentSize(Motion.spatial())) {
                    Column(Modifier.weight(1f)) {
                        Text("Scenario · fine ${last.year}", style = Type.labelOnAccent, color = pal.accentLabel)
                        Text(Fmt.eur(last.endingWealthNominalCents), style = Type.primaryFigure, color = pal.onAccent, modifier = Modifier.testTag(UiTag.PRIMARY_FIGURE))
                        Text("nominali · ${Fmt.eur(last.endingWealth2026Cents)} in euro ipotetici di $ref", style = Type.captionOnAccent, color = pal.accentSoft)
                        VSpace(8.dp)
                        Text("Tuoi cumulati ${Fmt.eur(last.cumulativePersonalNominalCents)} · datore ${Fmt.eur(last.cumulativeEmployerNominalCents)} (separati)", style = Type.labelOnAccent, color = pal.onAccent)
                    }
                }
                // The year-by-year values as one line (the median-style line of the Futuro fan, no bands).
                CardColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FanChart(
                        first.year, r.years.map { it.endingWealthNominalCents }, null, null,
                        "Scenario anno per anno, valori nominali: ${Fmt.eur(first.endingWealthNominalCents)} a fine ${first.year}, ${Fmt.eur(last.endingWealthNominalCents)} a fine ${last.year}.",
                    )
                    ChartLegend("anno per anno", listOf("nominale" to pal.fill), "scenario · ipotesi tue")
                }
            }
            ProjectionOutcome.UnavailableOverflow -> EmptyNote("Risultato fuori scala", "riduci anni o tassi")
            null -> {}
        }
    }
}

@Composable
private fun Pair2(wide: Boolean, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth()) }
}
