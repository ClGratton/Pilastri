package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.ui.*

enum class RegMode(val label: String) { VALUE("Valore"), CONTRIBUTION("Versamento"), TRANSFER("Trasferimento"), ACCOUNT("Conto") }

/**
 * "Registra": the exception path for what connectors do not see (a fund statement, a payslip contribution, a transfer
 * between own accounts, a new account). One compact sheet: a travelling selector for the kind, choices as pills, a
 * centered amount, date and source side by side when the window is wide. Everything saved is USER_REPORTED and sourced.
 */
@Composable
fun RegisterSheet(app: App, pillar: Int?, accountId: String?, initial: RegMode, onDismiss: () -> Unit) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    var mode by rememberSaveable { mutableStateOf(initial) }
    val scoped = a.accounts.filter { pillar == null || it.pillar == pillar }
    var account by rememberSaveable { mutableStateOf(accountId ?: scoped.singleOrNull()?.id) }
    var employer by rememberSaveable { mutableStateOf(false) }
    var fromId by rememberSaveable { mutableStateOf(accountId ?: a.accounts.firstOrNull { it.pillar == 1 }?.id) }
    var toId by rememberSaveable { mutableStateOf(a.accounts.firstOrNull { it.id != fromId && (pillar == null || it.pillar == pillar) }?.id) }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var amount by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var newPillar by rememberSaveable { mutableIntStateOf(pillar ?: 1) }
    var type by rememberSaveable { mutableIntStateOf(0) }
    val date = LocalDate.parse(dateText)
    val cents = parseEuroInput(amount)
    val accPillar = account?.let { id -> a.accounts.firstOrNull { it.id == id }?.pillar } ?: pillar
    val canEmployer = (accPillar ?: pillar) == 4
    // Quick sources: the last three used for the account this entry is about (the origin for a transfer), by recency.
    val sourceScope = when (mode) { RegMode.VALUE, RegMode.CONTRIBUTION -> account; RegMode.TRANSFER -> fromId; RegMode.ACCOUNT -> null }
    val suggestions = remember(a, sourceScope) { sourceSuggestions(a.sourceUsesFor(sourceScope)) }
    val sourceHint = when (mode) {
        RegMode.VALUE -> "Estratto conto, app del fornitore…"
        RegMode.CONTRIBUTION -> if (employer) "Estratto del fondo, busta paga…" else "Busta paga, bonifico, estratto…"
        RegMode.TRANSFER -> "Bonifico, ricevuta…"
        RegMode.ACCOUNT -> "Da dove sai che esiste"
    }
    val ready = when (mode) {
        RegMode.VALUE -> account != null && cents != null && source.isNotBlank()
        RegMode.CONTRIBUTION -> cents != null && cents > 0 && source.isNotBlank() && (accPillar ?: pillar) != null && (!employer || canEmployer)
        RegMode.TRANSFER -> fromId != null && toId != null && fromId != toId && cents != null && cents > 0 && source.isNotBlank()
        RegMode.ACCOUNT -> name.isNotBlank() && source.isNotBlank()
    }
    fun save() {
        if (!ready) return
        val s = source.trim()
        when (mode) {
            RegMode.VALUE -> app.commit("Valore registrato.") { it.withReportedAccountValue(account!!, date, cents!!, s) }
            RegMode.CONTRIBUTION -> app.commit(if (employer) "Contributo del datore registrato." else "Versamento registrato.") {
                it.withManualContribution(if (employer) EventType.EMPLOYER_CONTRIBUTION else EventType.PERSONAL_CONTRIBUTION, (accPillar ?: pillar)!!, account, date, cents!!, s)
            }
            RegMode.TRANSFER -> app.commit("Trasferimento registrato.") { it.withManualTransfer(fromId!!, toId!!, date, cents!!, s) }
            RegMode.ACCOUNT -> app.commit("Conto creato.") {
                // Both fund choices create a PENSION_FUND with a fund named from the account (the choices go with the Council 4 UI).
                it.withManualAccount(name, newPillar, if (newPillar == 4) listOf(AccountType.GENERAL, AccountType.PENSION_FUND)[type.coerceIn(0, 1)] else null, s).first
            }
        }
        onDismiss()
    }
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Registra", style = Type.screenTitle, color = pal.ink, modifier = Modifier.weight(1f))
            HelpButton("Registra", "Per ciò che i collegamenti non vedono: un valore letto su un estratto, un versamento in busta paga, un trasferimento tra tuoi conti, un conto nuovo. Tutto resta \"inserito da te\", con data e fonte.\n\nIl contributo del datore resta separato dai tuoi versamenti e appartiene al lungo termine. Un trasferimento tra tuoi conti non è un versamento né un'uscita: sposta soldi tra pilastri. Creare un conto non crea un saldo.")
        }
        Segmented(RegMode.entries.map { it.label }, mode.ordinal, { mode = RegMode.entries[it] })
        BoxWithConstraints {
            val wide = maxWidth >= 560.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    RegMode.VALUE -> {
                        Label("Conto")
                        if (scoped.isEmpty()) NoAccounts { mode = RegMode.ACCOUNT }
                        else AccountPills(scoped, account) { account = it }
                    }
                    RegMode.CONTRIBUTION -> {
                        Segmented(listOf("Tuo", "Del datore"), if (employer) 1 else 0, { employer = it == 1 })
                        if (employer && !canEmployer) Text("scegli un conto del lungo termine", style = Type.small, color = pal.overInk)
                        Label("Conto")
                        val pills = if (employer) a.accounts.filter { it.pillar == 4 } else scoped
                        AccountPills(pills, account, allowNone = pillar != null) { account = it }
                    }
                    RegMode.TRANSFER -> {
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Label("Da"); AccountPills(a.accounts, fromId) { fromId = it } }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Label("A"); AccountPills(a.accounts.filter { it.id != fromId }, toId) { toId = it } }
                        } else {
                            Label("Da"); AccountPills(a.accounts, fromId) { fromId = it }
                            Label("A"); AccountPills(a.accounts.filter { it.id != fromId }, toId) { toId = it }
                        }
                    }
                    RegMode.ACCOUNT -> {
                        TextInput(name, { name = it }, "Nome del conto")
                        Label("Pilastro")
                        Segmented(listOf("Liquidità", "Emergenze", "Progetti", "Lungo t."), newPillar - 1, { newPillar = it + 1 })
                        if (newPillar == 4) { Label("Tipo"); Segmented(listOf("Generico", "Fondo pensione"), type.coerceAtMost(1), { type = it }) }
                    }
                }
                if (mode != RegMode.ACCOUNT) {
                    TextInput(amount, { amount = it }, "Importo in euro", number = true, center = true, big = true)
                    (readEuroInput(amount) as? EuroInput.Invalid)?.takeIf { amount.isNotBlank() }?.let { Text(it.message, style = Type.small, color = pal.overInk) }
                }
                if (wide && mode != RegMode.ACCOUNT) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    DatePills(date, app.today, { dateText = it.toString() }, Modifier.weight(0.9f))
                    SourceField(source, { source = it }, sourceHint, suggestions, Modifier.weight(1.1f))
                } else {
                    if (mode != RegMode.ACCOUNT) DatePills(date, app.today, { dateText = it.toString() })
                    SourceField(source, { source = it }, sourceHint, suggestions)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Inserito da te · datato", style = Type.tiny, color = pal.ink2, modifier = Modifier.weight(1f))
            PillButton("Salva", { save() }, kind = Btn.PRIMARY, enabled = ready)
        }
    }
}

@Composable
private fun Label(text: String) {
    val pal = LocalPal.current
    Text(text, style = Type.tinyStrong, color = pal.ink2, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun NoAccounts(onCreate: () -> Unit) {
    val pal = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Nessun conto in questo pilastro.", style = Type.body, color = pal.ink2, modifier = Modifier.weight(1f))
        PillButton("Crea un conto", onCreate, kind = Btn.TONAL)
    }
}

/** Accounts as pills that wrap: a selection, never a long vertical radio list. The fill eases in; the choice is spoken. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountPills(accounts: List<Account>, selected: String?, allowNone: Boolean = false, onSelect: (String?) -> Unit) {
    val pal = LocalPal.current
    val hx = rememberHx()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val items = (if (allowNone) listOf<Account?>(null) else emptyList()) + accounts
        items.forEach { acc ->
            val on = acc?.id == selected
            val shape = RoundedCornerShape(20.dp)
            val fill by animateFill(pal.selected, on, "pill")
            Box(
                Modifier.heightIn(min = 44.dp).pressable(shape, pal.pressTint, onClick = { if (!on) { hx.tick(); onSelect(acc?.id) } }, role = androidx.compose.ui.semantics.Role.RadioButton)
                    .background(pal.inset).background(fill).border(if (on) 2.dp else 1.dp, if (on) pal.selectedLine else pal.btnLine, shape)
                    .semantics { this.selected = on; contentDescription = (acc?.name ?: "Senza conto") + (acc?.let { ", " + pillarName(it.pillar) } ?: "") }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(acc?.name ?: "Senza conto", style = Type.bodyStrong, color = pal.ink, textAlign = TextAlign.Center)
                    if (acc != null) Text(pillarName(acc.pillar), style = Type.tiny, color = pal.ink2, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A date as a pill that opens the system date picker; future dates are not selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text("Data", style = Type.tinyStrong, color = pal.ink2, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(RoundedCornerShape(16.dp), pal.pressTint, onClick = { open = true })
                .background(pal.inset).border(1.dp, pal.btnLine, RoundedCornerShape(16.dp)).semantics { contentDescription = "Data: ${Fmt.dayYear(date)}. Cambia" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Fmt.weekday(date) + " " + date.year, style = Type.body, color = pal.ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(Ic.calendar, null, Modifier.size(18.dp), tint = pal.brand)
        }
    }
    if (open) PickDateDialog(date, onPick) { open = false }
}

/** The system date picker in a dialog; future dates are not selectable. [onDismiss] runs after a choice too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDateDialog(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val today = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today })
    DatePickerDialog(onDismissRequest = onDismiss, colors = DatePickerDefaults.colors(containerColor = pal.card),
        confirmButton = { PillButton("Scegli", { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; onDismiss() }, kind = Btn.PRIMARY) },
        dismissButton = { PillButton("Annulla", onDismiss, kind = Btn.GHOST) }) {
        DatePicker(state, showModeToggle = true)
    }
}

/** The date choices of the Registra sheet; anything but today and yesterday is [OTHER] and goes through the calendar. */
enum class DateChoice { TODAY, YESTERDAY, OTHER }

fun dateChoice(date: LocalDate, today: LocalDate): DateChoice = when (date) {
    today -> DateChoice.TODAY
    today.minusDays(1) -> DateChoice.YESTERDAY
    else -> DateChoice.OTHER
}

/** The date a pill stands for; [OTHER] is the date already [picked] (today when there is none). */
fun dateForChoice(choice: DateChoice, today: LocalDate, picked: LocalDate? = null): LocalDate = when (choice) {
    DateChoice.TODAY -> today
    DateChoice.YESTERDAY -> today.minusDays(1)
    DateChoice.OTHER -> picked ?: today
}

/** A source text the person used before, with the day of that entry. */
data class SourceUse(val date: LocalDate, val source: String)

/** The last [limit] distinct sources (trimmed, case-insensitive), newest first; entries of the same day keep the later one first. */
fun recentSources(uses: List<SourceUse>, limit: Int = 3): List<String> =
    uses.withIndex().sortedWith(compareByDescending<IndexedValue<SourceUse>> { it.value.date }.thenByDescending { it.index })
        .map { it.value.source.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(limit)

/** The chips offered under "Fonte": the recent sources, or the one honest default when nothing was typed yet. */
fun sourceSuggestions(uses: List<SourceUse>): List<String> = recentSources(uses).ifEmpty { listOf("Inserito a mano") }

/**
 * What the person typed as the source of their own entries: reported values, contributions and transfers touching [accountId]
 * (all of them when null). Connector and document data is not a typed source and is left out.
 */
fun Archive.sourceUsesFor(accountId: String?): List<SourceUse> =
    accountValuations.filter { it.confidence == Confidence.USER_REPORTED && (accountId == null || it.accountId == accountId) }.map { SourceUse(it.date, it.source) } +
        events.filter { it.confidence == Confidence.USER_REPORTED && (accountId == null || it.accountId == accountId) }.map { SourceUse(it.date, it.source) } +
        transfers.filter { accountId == null || it.fromAccountId == accountId || it.toAccountId == accountId }.map { SourceUse(it.date, it.source) }

/** "Oggi / Ieri / altra data": the calendar opens only for the third pill, whose label becomes the chosen day. */
@Composable
fun DatePills(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    val choice = dateChoice(date, today)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("Data")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Oggi", choice == DateChoice.TODAY, Modifier.weight(1f), tick = choice != DateChoice.TODAY) { onPick(dateForChoice(DateChoice.TODAY, today)) }
            ChoiceChip("Ieri", choice == DateChoice.YESTERDAY, Modifier.weight(1f), tick = choice != DateChoice.YESTERDAY) { onPick(dateForChoice(DateChoice.YESTERDAY, today)) }
            ChoiceChip(if (choice == DateChoice.OTHER) Fmt.dayYear(date) else "Altra data", choice == DateChoice.OTHER, Modifier.weight(1.4f), icon = Ic.calendar) { picking = true }
        }
    }
    if (picking) PickDateDialog(date, onPick) { picking = false }
}

/** The recent sources as chips above the free-text field; a second tap on the chosen one clears it. Typing stays possible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceField(source: String, onChange: (String) -> Unit, hint: String, suggestions: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Fonte · recenti")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                val on = source.trim().equals(s, ignoreCase = true)
                ChoiceChip(s, on, Modifier.widthIn(max = 240.dp), tick = true) { onChange(if (on) "" else s) }
            }
        }
        TextInput(source, onChange, "Fonte · $hint")
    }
}

/** A selectable pill (48 dp target) in the same material as [AccountPills]: the fill eases in, the choice is spoken. */
@Composable
private fun ChoiceChip(label: String, on: Boolean, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, tick: Boolean = true, onClick: () -> Unit) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val shape = RoundedCornerShape(20.dp)
    val fill by animateFill(pal.selected, on, "chip")
    Box(
        modifier.heightIn(min = 48.dp).pressable(shape, pal.pressTint, onClick = { if (tick) hx.tick(); onClick() }, role = androidx.compose.ui.semantics.Role.RadioButton)
            .background(pal.inset).background(fill).border(if (on) 2.dp else 1.dp, if (on) pal.selectedLine else pal.btnLine, shape)
            .semantics { this.selected = on; contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) androidx.compose.material3.Icon(icon, null, Modifier.size(16.dp), tint = pal.brand)
            Text(label, style = if (on) Type.bodyStrong else Type.body, color = pal.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
