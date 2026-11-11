package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.rules.HistoryKind
import xyz.grtlabs.pilastri.domain.rules.HistoryLine
import xyz.grtlabs.pilastri.domain.rules.RulesHistory
import xyz.grtlabs.pilastri.sync.eb.EbStateStore
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.setup.LocalBankFlow
import xyz.grtlabs.pilastri.ui.setup.PositionCatalog
import xyz.grtlabs.pilastri.ui.setup.SetupModel
import xyz.grtlabs.pilastri.ui.setup.SetupRules
import xyz.grtlabs.pilastri.ui.setup.SetupStore

/*
 * Impostazioni › Conti (board G3-6): "Posizione e fondi" and "Banche"; the connectors follow in ConnectionsScreen.
 * Positions use the setup's catalogue and rules; nothing here names a person, employer or fund.
 */

private fun IncomePosition.documented(): Boolean = listOf(validFrom, validTo, label, gestione, severanceRegime, severanceDestination,
    contractTerm, hireDate, ateco, regimeStart, incomeSource).any { it.origin == Origin.DOCUMENTO }

/** The person's known position kinds (unknown future codes are kept in the archive and left alone here). */
fun Archive.positionKinds(): Set<PositionKind> = positions.mapNotNull { it.kind.known }.toSet()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PositionsCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val kinds = remember(a) { PositionCatalog.order.filter { k -> a.positions.any { it.kind.known == k } } }
    val documented = remember(a) { a.positions.any { it.documented() } }
    fun reject(text: String) { app.hx?.reject(); app.say(text) }
    fun toggle(k: PositionKind) {
        val next = SetupRules.togglePosition(SetupModel(positions = kinds), k).positions
        val removed = kinds.filter { it !in next }
        if (a.positions.any { it.kind.known in removed && it.documented() }) return reject("Posizione documentata: resta")
        val add = if (k in next && k !in kinds) SetupRules.positionsToWrite(SetupModel(positions = listOf(k)), app.today) { kind -> "pos-${kind.name.lowercase()}-${UUID.randomUUID().toString().take(8)}" } else emptyList()
        app.hx?.tick()
        app.commit { ar -> SetupRules.applyToArchive(ar.copy(positions = ar.positions.filterNot { it.kind.known in removed }), add) }
    }
    fun setGestione(k: PositionKind, g: Gestione) {
        val p = a.positions.firstOrNull { it.kind.known == k } ?: return
        if (p.gestione.origin == Origin.DOCUMENTO) return reject("Gestione documentata: resta")
        val value: Sourced<Coded<Gestione>> = if (g == Gestione.ND || p.gestione.value?.known == g) Sourced.nd() else Sourced.declared(coded(g), app.today)
        app.hx?.tick()
        app.commit { ar -> ar.copy(positions = ar.positions.map { if (it.id == p.id) it.copy(gestione = value) else it }) }
    }
    // 6-01 (board 6): ONE summary row "<Posizione> · <origine>"; the chips that edit it open under the row on a tap, never as a standing card.
    var editing by rememberSaveable { mutableStateOf(false) }
    RowsCard(Modifier.animateContentSize(Motion.spatial())) {
        ListRow(
            if (kinds.isEmpty()) "Posizione n/d" else kinds.joinToString(" · ") { PositionCatalog.label(it) } + " · " + (if (documented) "documentato" else "dichiarato"),
            null, { editing = !editing },
            leading = { IconDisc(Ic.salary, pal.lilacBg, pal.lilacInk, 32.dp) },
        )
        if (editing) Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PositionCatalog.order.forEach { k -> ChoiceChipLite(PositionCatalog.label(k), k in kinds) { toggle(k) } }
            }
            kinds.filter { PositionCatalog.gestioni(it).isNotEmpty() }.forEach { k ->
                key(k) {
                    val chosen = a.positions.firstOrNull { it.kind.known == k }?.gestione?.value?.known
                    Column(Modifier.fillMaxWidth().animateContentSize(Motion.spatial()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Gestione · ${PositionCatalog.label(k)}", style = Type.smallStrong, color = pal.brand)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PositionCatalog.gestioni(k).forEach { g -> ChoiceChipLite(PositionCatalog.gestioneLabel(g), chosen == g || (g == Gestione.ND && chosen == null)) { setGestione(k, g) } }
                        }
                    }
                }
            }
        }
    }
}

/** The setup's compact chip (centred label, 48 dp, travelling fill), redrawn here because the setup's one is private. */
@Composable
private fun ChoiceChipLite(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(20.dp)
    val fill by animateFill(pal.selected, on, "chip")
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).pillTag().pressable(shape, pal.pressTint, onClick = onClick).background(pal.card).background(fill)
        .border(if (on) 2.dp else 1.dp, if (on) pal.selectedLine else pal.cardLine, shape)
        .semantics { selected = on; role = Role.Checkbox }.padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // U-09: the selected chip is shown by a check as well as the fill and the 2 dp outline (never colour alone).
            if (on) androidx.compose.material3.Icon(Ic.check, null, Modifier.size(14.dp), tint = pal.ink)
            Text(label, style = Type.rowTitle, color = pal.ink, textAlign = TextAlign.Center)
        }
    }
}

fun fundFormLabel(f: PensionFund): String = when (f.formType.value?.known) {
    FundFormType.NEGOZIALE -> "fondo negoziale"
    FundFormType.APERTO -> "fondo aperto"
    FundFormType.PIP -> "PIP"
    FundFormType.PREESISTENTE -> "fondo preesistente"
    FundFormType.ND, null -> "forma n/d"
}

@Composable
fun FundsCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val views = remember(a) { a.fundViews() }
    RowsCard {
        if (a.funds.isEmpty()) ListRow("Fondi", "n/d", null)
        a.funds.forEach { f ->
            val account = a.accounts.firstOrNull { it.fundId == f.id }
            val v = views.firstOrNull { it.account.id == account?.id }
            val date = v?.statement?.positionDate ?: v?.value?.date
            ListRow(f.name.value ?: "Fondo", fundFormLabel(f) + " · " + (date?.let { "dati al ${Fmt.day(it)}" } ?: "dati n/d"), { app.go(Route.Pension) },
                tag = if (date != null && ageDays(date, app.today) > 35) "datato" else null)
        }
    }
}

/** A consent that ends within 7 days, or already ended, offers "Rinnova"; an unknown end (n/d) offers nothing. */
internal fun consentNeedsRenewal(until: LocalDate?, today: LocalDate): Boolean = until != null && !until.isAfter(today.plusDays(7))

/** The Trade Republic PIN switch (Impostazioni › Conti and setup › Altri conti): label, and the cost of turning it on. */
internal const val TrPinLabel = "Ricorda il PIN"
internal const val TrPinNote = "Senza PIN niente lettura automatica"

/** The line under the switch is a fact about the switch, so it shows whether the switch is on or off. */
internal fun trPinLine(@Suppress("UNUSED_PARAMETER") declined: Boolean): String = TrPinNote

/** Bank consents through Enable Banking with their state, and the banks chosen in setup that still wait ("Da approvare"). */
@Composable
fun BanksCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val flow = LocalBankFlow.current
    // A finished bank-only run (Aggiungi banca / Rinnova) reloads the list.
    val version = flow?.version ?: 0
    // Bank name and consent end only, one entry per linked bank: the sessions themselves never leave the store.
    val linked by produceState<List<Pair<String, LocalDate?>>>(emptyList(), version) {
        value = withContext(Dispatchers.IO) { runCatching { EbStateStore.createOrNull(ctx)?.load()?.sessions }.getOrNull().orEmpty() }
            .map { it.bankName to it.validUntil?.atZone(ZoneId.of("Europe/Rome"))?.toLocalDate() }
    }
    val pending by produceState(emptySet<String>(), version) { value = withContext(Dispatchers.IO) { SetupStore.pendingBanks(ctx) } }
    RowsCard(Modifier.animateContentSize(Motion.spatial())) {
        if (linked.isEmpty() && pending.isEmpty()) ListRow("Banca", "n/d", null)
        linked.forEach { (name, until) ->
            val lapsed = until != null && until.isBefore(app.today)
            ListRow(name, until?.let { if (lapsed) "consenso scaduto il ${Fmt.day(it)}" else "consenso fino al ${Fmt.dayYear(it)}" } ?: "consenso · scadenza n/d", null,
                tag = if (lapsed) "scaduto" else null)
            if (flow != null && consentNeedsRenewal(until, app.today)) {
                PillButton("Rinnova", { flow.open(name) }, Modifier.padding(horizontal = 6.dp), kind = Btn.TONAL, icon = Ic.refresh, height = 48.dp, description = "Rinnova il consenso di $name")
            }
        }
        (pending - linked.map { it.first }.toSet()).sorted().forEach { ListRow(it, "consenso da dare", null, tag = "Da approvare") }
        if (a.connections.any { it.provider == ImportProvider.ISYBANK }) ActionRow(Modifier.padding(start = RowInset, end = RowInset, bottom = RowVerticalInset)) { PillButton("Importa un file", { app.launchers.isybankFile() }, kind = Btn.GHOST, icon = Ic.upload) }
    }
}

private fun historyTitle(l: HistoryLine): String = l.rowId + " · " + when (l.kind) {
    HistoryKind.CONFIRMED -> "confermata"
    HistoryKind.UNDONE -> "annullata"
    HistoryKind.REFRESHED -> "ricontrollata"
    HistoryKind.UPDATE_RESOLVED -> if (l.winner == UpdateWinner.KEPT) "tenuto il tuo valore" else "aggiornata"
}

/** Impostazioni › "Fonti e regole": the read-only history of the rule pack, newest first. */
@Composable
fun RulesHistoryCard(a: Archive) {
    val pal = LocalPal.current
    val lines = remember(a) { RulesHistory.lines(a) }
    var all by rememberSaveable { mutableStateOf(false) }
    SectionHeader("Fonti e regole")
    RowsCard(Modifier.animateContentSize(Motion.spatial())) {
        if (lines.isEmpty()) ListRow("Regole dell'app", "regole di serie", null)
        (if (all) lines else lines.take(6)).forEach { l ->
            ListRow(historyTitle(l), listOfNotNull(Fmt.dayYear(l.date), listOfNotNull(l.before, l.after).joinToString(" → ").ifEmpty { null }, l.source).joinToString(" · "), null)
        }
        if (lines.size > 6) PillButton(if (all) "Mostra meno" else "Altre ${lines.size - 6}", { all = !all }, Modifier.align(Alignment.CenterHorizontally), kind = Btn.GHOST)
    }
}
