package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.grtlabs.pilastri.sync.ProviderSyncPage
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.auto.ProviderSyncState
import xyz.grtlabs.pilastri.sync.auto.UserAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import xyz.grtlabs.pilastri.ui.charts.*
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*


/**
 * A pillar's detail (board 2; DESIGN "Pillar detail"; right pane on the Fold, pushed page on the cover): name and target,
 * the hero value with its basis, the basis toggle "€ gen 2026 | nominale" that retargets every figure, the chart "Valore e
 * versamenti" (the value line on dated points, the monthly tuo / datore bars beneath on their own scale) and "Dove sono".
 * Manual entry stays as one secondary "Registra" (2-06). Kept below the board blocks, with no other home: the guarded
 * monthly return when it passes, the months of essential spending covered (P2) and the projects (P3).
 */
@Composable
fun PillarScreen(app: App, pillar: Int) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val today = app.today
    val value = remember(a, pillar) { a.pillarValue(pillar) }
    val target = remember(a, pillar) { a.targetFor(pillar, today) }
    val def = remember(a) { a.deflator() }
    val line = remember(a, pillar, today) { a.pillarLine(pillar, today) }
    val bars = remember(a, pillar, today) { a.pillarContributionBars(pillar, today) }
    val window = remember(today) { yearWindow(today) }
    val cents = value.centsOrNull()
    val date = when (value) { is PillarValue.Snapshot -> value.date; is PillarValue.Composed -> value.newest; is PillarValue.Unknown -> null }
    val realNow = if (cents != null && date != null) def.real(cents, YearMonth.from(date)) else null
    // The € gen 2026 side is the default when today's value can be indexed; without an index it reads n/d (2-02).
    var real by rememberSaveable(pillar) { mutableStateOf(realNow != null) }
    var registering by remember { mutableStateOf(false) }
    val basis = if (real) "€ gen 2026" else "nominale"
    val shownLine = remember(line, real, def) { if (real) line.mapNotNull { def.real(it) } else line }
    val shownBars = remember(bars, real, def) { if (real) bars.map { def.real(it) } else bars }
    val hero = if (real) realNow else cents
    val heroAnim = rememberRetarget(listOf(hero?.toFloat()))
    val targetText = when (target) {
        is TargetView.Amount -> "obiettivo ${Fmt.eur(target.cents)}" + (cents?.let { " · " + Fmt.pct(it.toDouble() / target.cents) } ?: "")
        is TargetView.Unavailable -> "obiettivo n/d"
        TargetView.None -> "senza obiettivo"
    }
    Page(spacing = 8.dp) {
        BackHeader(pillarName(pillar), "P$pillar · $targetText" + sampleSuffix(a), { app.back() }, backLabel = "Indietro a Pilastri")
        // 2-01: the hero value and its basis.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shown = if (hero == null) "n/d" else if (heroAnim[0].isRunning) Fmt.eur(heroAnim[0].value.toLong()) else Fmt.eur(hero)
            Text(shown, style = Type.heroSmall, color = pal.ink, maxLines = 1)
            Text((if (real) "€ gen 2026" else "valore nominale") + (date?.let { " · al ${Fmt.day(it)}" } ?: ""), style = Type.small, color = pal.ink2,
                modifier = Modifier.padding(bottom = 5.dp), maxLines = 2)
        }
        if (value is PillarValue.Unknown) Text(value.reason, style = Type.small, color = pal.ink2, modifier = Modifier.padding(horizontal = 4.dp))
        BasisToggle(real, { real = it })
        ValueAndContributions(pillar, shownLine, shownBars, window, basis)
        WhereCard(app, a, pillar, value, today) { registering = true }
        // The guarded return: only the time-weighted calculation with a current confirmation may print "rendimento".
        val perf = remember(a, pillar) { evaluatePillarPerformance(a, pillar, PerformancePeriod.MONTH) }
        if (perf is PillarPerformanceResult.Available) CardColumn {
            FactLine("Rendimento mese (nominale)", (if (perf.nominal.percent.signum() > 0) "+" else "") + perf.nominal.percent.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%",
                note = "${Fmt.day(perf.window.start)} → ${Fmt.day(perf.window.end)} · flussi confermati da te")
        }
        PillarSpecific(a, pillar, cents, today)
    }
    if (registering) RegisterSheet(app, pillar, null, if (a.accounts.none { it.pillar == pillar }) RegMode.ACCOUNT else RegMode.VALUE) { registering = false }
}

/** 2-03: "Valore e versamenti" over the last year; the two parts share the time axis (same gutter), the bars have their own scale. */
@Composable
private fun ValueAndContributions(pillar: Int, line: List<SeriesPoint>, bars: List<MonthBar>, window: Window, basis: String) {
    val pal = LocalPal.current
    val gutter = 54.dp
    val first = line.firstOrNull(); val last = line.lastOrNull()
    val gaps = (runsOf(line).size - 1).coerceAtLeast(0)
    val lineDesc = buildString {
        append("${pillarName(pillar)}, ultimi 12 mesi, $basis")
        if (first == null || last == null) append(": nessun valore datato")
        else {
            append(": valore da ${Fmt.eur(first.cents)} il ${Fmt.dayYear(first.date)} a ${Fmt.eur(last.cents)} il ${Fmt.dayYear(last.date)}")
            if (gaps > 0) append(", $gaps ${if (gaps == 1) "interruzione" else "interruzioni"} senza dati")
        }
    }
    val top = niceCeil((bars.filter { it.state == BarState.DATA }.maxOfOrNull { it.tuoCents + it.datoreCents } ?: 0L).coerceAtLeast(100L))
    val barsDesc = buildString {
        append("Versamenti mensili, $basis, scala da 0 a ${axisLabelEur(top)}: ")
        append("${bars.count { it.state == BarState.DATA }} mesi con versamenti, ${bars.count { it.state == BarState.NONE }} senza, ${bars.count { it.state == BarState.ND }} n/d. ")
        append("Tuo ${Fmt.eur(bars.sumOf { it.tuoCents })}, datore ${Fmt.eur(bars.sumOf { it.datoreCents })}, separati.")
    }
    ChartCard("Valore e versamenti", windowLabel(window)) {
        DatedLineChart(line, window, lineDesc, endLabel = null /* CR-70/K8: the hero figure owns the value and its date */, height = 132.dp, gutter = gutter, showTicks = false)
        Spacer(Modifier.height(ChartLegendGap))   // the legend never sits flush against the axis labels above (Q2, R1 item 5)
        ChartLegend("versamenti", listOf("tuo" to pal.fill, "datore" to pal.ribbonIn), null)
        Spacer(Modifier.height(ChartLegendGap))   // ... nor against the printed top of the bar scale below
        MonthBarsChart(bars.map { BarSlot(it.month, listOf(it.tuoCents, it.datoreCents), it.state) }, listOf(pal.fill, pal.ribbonIn), barsDesc, window = window, gutter = gutter)
    }
}

private fun axisLabelEur(cents: Long) = axisLabel(cents) + Fmt.EUR_SUFFIX

/** 2-04 "Dove sono": each account with its date, amount and a "datato" chip past 35 days; then the one secondary "Registra" (2-06). */
@Composable
private fun WhereCard(app: App, a: Archive, pillar: Int, value: PillarValue, today: LocalDate, onRegister: () -> Unit) {
    val pal = LocalPal.current
    val accounts = a.accounts.filter { it.pillar == pillar }
    TitledCard("Dove sono", rows = true) {
        if (accounts.isEmpty()) {
            if (value is PillarValue.Snapshot) ListRow(value.source.removePrefix("$SamplePrefix · ").take(60), "al ${Fmt.day(value.date)}", null,
                value = Fmt.eur(value.cents), tag = if (ageDays(value.date, today) > 35) "datato" else null)
            else ListRow("—", null, null)
            ActionRow(Modifier.padding(horizontal = RowInset)) { PillButton("Collega conti", { app.go(Route.Connections) }, kind = Btn.GHOST, icon = Ic.link, height = 48.dp) }
        } else accounts.forEach { acc ->
            val v = a.accountValuations.filter { it.accountId == acc.id }.maxByOrNull { it.date }
            ListRow(acc.name, v?.let { "al ${Fmt.day(it.date)}" } ?: "nessun valore", { app.go(Route.Account(acc.id)) },
                value = v?.let { Fmt.eur(it.amount.cents) } ?: "n/d", tag = if (v != null && ageDays(v.date, today) > 35) "datato" else null)
        }
        ActionRow(Modifier.padding(start = RowInset, end = RowInset, bottom = 10.dp)) { PillButton("Registra", onRegister, kind = Btn.GHOST, icon = Ic.plus) }
    }
}

/** Kept with no other home (accepted deviation, DESIGN 2-06): P2 months of essential spending covered, P3 projects. */
@Composable
private fun PillarSpecific(a: Archive, pillar: Int, cents: Long?, today: LocalDate) {
    val pal = LocalPal.current
    when (pillar) {
        2 -> {
            val monthly = remember(a) { a.essentialMonthlyCents(today) }
            CardColumn {
                FactLine("Mesi di spese essenziali coperti", if (monthly != null && monthly > 0 && cents != null) BigDecimal(cents).divide(BigDecimal(monthly), 1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') else "n/d",
                    note = if (monthly == null) "n/d · servono 3 mesi completi" else "spese essenziali medie ${Fmt.eur(monthly)} al mese · calcolato")
            }
        }
        3 -> {
            val goals = a.planning.goals
            if (goals.isNotEmpty()) CardColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                goals.forEach { g ->
                    Column {
                        Row { Text(g.name, style = Type.rowTitle, color = pal.ink, modifier = Modifier.weight(1f)); Text(Fmt.eur(g.targetCents), style = Type.amount, color = pal.ink) }
                        Text((g.targetDate?.let { "entro ${Fmt.dayYear(it)}" } ?: "senza data") + (g.earmarkCents?.let { " · accantonati ${Fmt.eur(it)}" } ?: " · accantonato n/d"), style = Type.small, color = pal.ink2)
                        VSpace(4.dp)
                        Meter(g.earmarkCents?.let { it.toFloat() / g.targetCents }, track = pal.track, fill = pal.fill, height = 6.dp, outline = if (g.earmarkCents == null) pal.btnLine else null)
                    }
                }
            }
        }
        else -> {}
    }
}

/**
 * One account (lazy-UX 3/16): the figure edited in place, one "+" Registra on the account, and, for a connected account, the
 * freshness line as the state with "Aggiorna" as its only (secondary) action; the page also pulls down to refresh.
 */
@Composable
fun AccountScreen(app: App, accountId: String) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val reduce = LocalReduceMotion.current
    val acc = a.accounts.firstOrNull { it.id == accountId }
    var registering by remember { mutableStateOf(false) }
    if (acc == null) { Page { BackHeader("Conto", null, { app.back() }); EmptyNote("Conto non disponibile") }; return }
    val values = a.accountValuations.filter { it.accountId == acc.id }.sortedByDescending { it.date }
    val latest = values.firstOrNull()
    val connection = a.connections.firstOrNull { it.accountId == acc.id || it.securitiesAccountId == acc.id }
    val provider = connection?.provider
    val syncFlow = remember(provider) { provider?.let { AutoSyncKit.state(ctx, it) } ?: MutableStateFlow<ProviderSyncState>(ProviderSyncState.Idle) }
    val state by syncFlow.collectAsState()
    val linked = remember(provider, state) { provider != null && AutoSyncKit.coordinator(ctx).isLinked(provider) }
    val automatic = remember(provider) { provider != null && (provider == ImportProvider.TRADE_REPUBLIC || ProviderSyncPage.isAutomatic(provider)) }
    val (syncText, warn) = accountSyncLine(state, connection?.coverageTo, app.today)
    val action = accountSyncAction(state, linked, automatic)
    PullToRefresh(
        refreshing = state is ProviderSyncState.Syncing, onRefresh = { provider?.let { app.refresh(it) } },
        modifier = Modifier.fillMaxSize(), enabled = provider != null && accountOffersRefresh(automatic, linked),
    ) {
        Page {
            BackHeader(acc.name, pillarName(acc.pillar) + (provider?.let { " · ${it.label}" } ?: " · senza collegamento") + sampleSuffix(a), { app.back() }, trailing = {
                RoundIcon(Ic.plus, "Registra su ${acc.name}", { registering = true }, bg = pal.primary, tint = pal.onPrimary)
            })
            CardColumn {
                InlineAmountField(
                    cents = latest?.amount?.cents, label = "Valore di ${acc.name}", sheetHint = "oggi · inserito da te",
                    onSave = { cents -> app.commit("Valore salvato.") { it.withEditedAccountValue(acc.id, app.today, cents, "Inserito a mano · ${acc.name}") } },
                )
                Text(latest?.let { "dato al ${Fmt.day(it.date)} · " + if (it.confidence == xyz.grtlabs.pilastri.domain.Confidence.DOCUMENT) "dal fornitore" else "inserito da te" } ?: "nessun valore", style = Type.small, color = pal.ink2)
                if (connection != null) AnimatedContent(Triple(syncText, warn, action), transitionSpec = fadeThrough(reduce), label = "account-sync") { (text, attention, act) ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FreshnessLine(text ?: "dati: n/d", complete = !attention, Modifier.weight(1f))
                        when (act) {
                            AccountSyncAction.SIGN_IN -> PillButton(accountActionLabel((state as? ProviderSyncState.NeedsUser)?.action), { provider?.let { app.signIn(it) } }, kind = Btn.GHOST, height = 36.dp)
                            AccountSyncAction.CONNECT -> PillButton("Collega", { provider?.let { app.signIn(it) } }, kind = Btn.GHOST, height = 36.dp)
                            AccountSyncAction.REFRESH -> PillButton("Aggiorna", { provider?.let { app.refresh(it) } }, kind = Btn.GHOST, icon = Ic.refresh, enabled = state !is ProviderSyncState.Syncing, height = 36.dp)
                            AccountSyncAction.NONE -> {}
                        }
                    }
                }
            }
            if (values.isNotEmpty()) {
                SectionHeader("Valori")
                CardColumn(rows = true) {
                    values.take(24).forEach { v -> ListRow(Fmt.dayYear(v.date), v.source.take(80), null, value = Fmt.eurCents(v.amount.cents)) }
                }
            }
        }
    }
    if (registering) RegisterSheet(app, acc.pillar, acc.id, RegMode.CONTRIBUTION) { registering = false }
}

/** What the sync row of an account offers beside its state: the page when the person is needed, "Collega" when not linked, otherwise "Aggiorna". */
enum class AccountSyncAction { NONE, SIGN_IN, CONNECT, REFRESH }

fun accountSyncAction(state: ProviderSyncState, linked: Boolean, automatic: Boolean): AccountSyncAction = when {
    state is ProviderSyncState.NeedsUser && state.needsPage -> AccountSyncAction.SIGN_IN
    !linked -> AccountSyncAction.CONNECT
    automatic -> AccountSyncAction.REFRESH
    else -> AccountSyncAction.NONE
}

/** Pull-to-refresh and "Aggiorna" exist only for a linked account whose provider syncs by itself. */
fun accountOffersRefresh(automatic: Boolean, linked: Boolean): Boolean = automatic && linked

private fun accountActionLabel(a: UserAction?) = when (a) {
    UserAction.APPROVE_2FA -> "Conferma"
    UserAction.CHALLENGE -> "Verifica"
    else -> "Accedi"
}

/** The freshness text of an account's sync row and whether it needs attention: "dati fino al <giorno>", else when it last synced, else the archived coverage; a user-needed or failed state shows its own line. */
fun accountSyncLine(state: ProviderSyncState, archivedCoverageTo: LocalDate?, today: LocalDate): Pair<String?, Boolean> {
    fun fresh(f: ProviderSyncState.Fresh?): String? = f?.coverageTo?.let { "dati fino al ${Fmt.day(it)}" }
        ?: f?.let { "aggiornato ${Fmt.relative(it.syncedAt.localDate(), today)}" }
        ?: archivedCoverageTo?.let { "dati fino al ${Fmt.day(it)}" }
    return when (state) {
        ProviderSyncState.Idle -> fresh(null) to false
        is ProviderSyncState.Fresh -> fresh(state) to false
        is ProviderSyncState.Syncing -> fresh(state.last) to false
        is ProviderSyncState.NeedsUser -> state.line to true
        is ProviderSyncState.Failed -> state.message to true
    }
}

/**
 * A value typed on the account page: a value of today typed by the person is replaced, a value of today from the provider is kept
 * (it refuses), anything else is a new dated point; the rules of [withReportedAccountValue] still apply.
 */
internal fun Archive.withEditedAccountValue(accountId: String, date: LocalDate, cents: Long, source: String): Archive {
    val same = accountValuations.filter { it.accountId == accountId && it.date == date }
    require(same.none { it.confidence == xyz.grtlabs.pilastri.domain.Confidence.DOCUMENT }) { "Il valore di oggi arriva dal fornitore" }
    val base = if (same.isEmpty()) this else copy(accountValuations = accountValuations - same.toSet())
    return base.withReportedAccountValue(accountId, date, cents, source)
}
