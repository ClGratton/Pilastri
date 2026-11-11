package xyz.grtlabs.pilastri

import xyz.grtlabs.pilastri.ui.vanish
import xyz.grtlabs.pilastri.ui.appear
import xyz.grtlabs.pilastri.ui.Motion
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveSave
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.AccountType
import xyz.grtlabs.pilastri.data.withAccountFund
import xyz.grtlabs.pilastri.data.IndexPoint
import xyz.grtlabs.pilastri.data.AuditEntry
import xyz.grtlabs.pilastri.data.Istat
import xyz.grtlabs.pilastri.data.correctContribution
import xyz.grtlabs.pilastri.data.correctValuation
import xyz.grtlabs.pilastri.data.updateAccount
import xyz.grtlabs.pilastri.data.withoutAccount
import xyz.grtlabs.pilastri.components.actionFeedback
import xyz.grtlabs.pilastri.components.EditorChoice
import xyz.grtlabs.pilastri.components.EditorPanel
import xyz.grtlabs.pilastri.domain.*

private val names = listOf("Liquidità", "Emergenze", "Progetti", "Lungo termine")

@Composable
fun DataPage(data: Archive, onSave: ArchiveSave, onExport: () -> Unit, onImport: () -> Unit, onPrefill: () -> Unit, status: String?, message: String?) {
    var selectedSection by rememberSaveable { mutableStateOf("records") }
    var visibleHistoryCount by rememberSaveable { mutableIntStateOf(20) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingValueKey by rememberSaveable { mutableStateOf<String?>(null) }
    val editingValue = data.valuations.firstOrNull { it.pillar.toString() + ":" + it.date == editingValueKey }
    var editingEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingEvent = data.events.firstOrNull { it.id == editingEventId }
    var editingIndex by rememberSaveable { mutableStateOf(false) }
    var addingAccount by rememberSaveable { mutableStateOf(false) }
    var editingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingAccount = data.accounts.firstOrNull { it.id == editingAccountId }
    var addingAccountValuationForId by rememberSaveable { mutableStateOf<String?>(null) }
    val addingAccountValuationFor = data.accounts.firstOrNull { it.id == addingAccountValuationForId }
    var removal by remember { mutableStateOf<(() -> Archive)?>(null) }
    var showIstat by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val busy = saving || status != null
    fun persist(candidate: Archive, onSuccess: () -> Unit = {}) {
        if (saving || status != null) return
        saving = true
        saveError = null
        onSave(candidate) { saved ->
            saving = false
            if (saved) onSuccess() else saveError = "Salvataggio non riuscito. Riprova."
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Movimenti", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Valori, versamenti e conti", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status != null) Text(status, color = MaterialTheme.colorScheme.error)
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EditorChoice("Registrazioni", selectedSection == "records", Modifier.weight(1f)) { selectedSection = "records" }
            EditorChoice("Conti", selectedSection == "accounts", Modifier.weight(1f)) { selectedSection = "accounts" }
        }
        TextButton(onClick = { showTools = !showTools }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).actionFeedback()) {
            Text(if (showTools) "Nascondi strumenti e backup" else "Inflazione, importazione e backup")
        }
        AnimatedVisibility(showTools, enter = appear(Motion.reduced), exit = vanish(Motion.reduced)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Indice dei prezzi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Inserisci indici mensili ISTAT NIC confrontabili sulla stessa base statistica, inclusi gennaio 2026 e ogni mese dei versamenti. Usa esattamente la stessa descrizione della fonte per ogni mese. La vista reale usa gennaio 2026 come riferimento, non la media annuale.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.actionFeedback(!busy), onClick = { showIstat = true }, enabled = !busy) { Text("Carica ISTAT · gen–set 2026") }
        OutlinedButton(modifier = Modifier.actionFeedback(!busy), onClick = { editingIndex = true }, enabled = !busy) { Text("Aggiungi indice mensile") }
        data.indices.sortedByDescending { it.month }.forEach { p -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${p.month} · ${p.value.toPlainString()} · ${p.source}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(modifier = Modifier.actionFeedback(!busy), onClick = { removal = { data.copy(indices = data.indices - p, audit = data.audit + AuditEntry(java.time.Instant.now().toString(), "rimozione indice", p.toString())) } }, enabled = !busy) { Text("Rimuovi") }
        } }
        if (data.audit.isNotEmpty()) Text("Rettifiche registrate: ${data.audit.size}. I dettagli precedenti sono conservati nel backup.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Dati precompilati", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Puoi esaminare e importare gli stipendi netti dichiarati e alcune date e aliquote dal JSON 2026. Le aliquote non sono versamenti; ipotesi e saldi ignoti non vengono importati come fatti.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.actionFeedback(!busy), onClick = onPrefill, enabled = !busy) { Text("Scegli JSON 2026") }
        Spacer(Modifier.height(8.dp))
        Text("Backup locale", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Il file esportato contiene dati finanziari leggibili: conservalo in un luogo sicuro. Non è cifrato. Il ripristino sostituisce i dati attuali dopo conferma.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.actionFeedback(!busy), onClick = onExport, enabled = !busy) { Text("Esporta") }
            OutlinedButton(modifier = Modifier.actionFeedback(!busy), onClick = onImport, enabled = !busy) { Text("Ripristina") }
        }
        }
        }
        if (selectedSection == "records") {
            Button(onClick = { editingValueKey = null; editingEventId = null; editing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).actionFeedback(!busy)) { Text("Nuova registrazione") }
            val orderedValuations = data.valuations.sortedByDescending { it.date }
            val orderedEvents = data.events.sortedByDescending { it.date }
            if (orderedValuations.isEmpty() && orderedEvents.isEmpty()) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Inizia da un valore o un versamento", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Le registrazioni appariranno qui.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (orderedValuations.isNotEmpty()) {
                Text("Valutazioni per pilastro · ${orderedValuations.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Mostro ${minOf(visibleHistoryCount, orderedValuations.size)} di ${orderedValuations.size}, dalla più recente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                orderedValuations.take(visibleHistoryCount).forEach { v ->
                    Text("${v.date} · ${names[v.pillar - 1]} · Valore del pilastro", fontWeight = FontWeight.SemiBold)
                    Text("${euros(v.amount.cents)} · ${v.source}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(modifier = Modifier.actionFeedback(!busy), onClick = { editingValueKey = v.pillar.toString() + ":" + v.date; editingEventId = null; editing = true }, enabled = !busy) { Text("Modifica") }
                        TextButton(modifier = Modifier.actionFeedback(!busy), onClick = { removal = { data.copy(valuations = data.valuations - v, audit = data.audit + AuditEntry(java.time.Instant.now().toString(), "rimozione valutazione", v.toString())) } }, enabled = !busy) { Text("Rimuovi") }
                    }
                    HorizontalDivider()
                }
            }
            if (orderedEvents.isNotEmpty()) {
                Text("Movimenti · ${orderedEvents.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Mostro ${minOf(visibleHistoryCount, orderedEvents.size)} di ${orderedEvents.size}, dal più recente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                orderedEvents.take(visibleHistoryCount).forEach { e ->
                    val label = when (e.type) {
                        EventType.PERSONAL_CONTRIBUTION -> "Versamento personale"
                        EventType.EMPLOYER_CONTRIBUTION -> "Contributo datore"
                        EventType.WITHDRAWAL -> "Prelievo"
                        EventType.INCOME -> "Provento"
                        EventType.FEE -> "Costo"
                        else -> e.type.name
                    }
                    val accountName = e.accountId?.let { id -> data.accounts.firstOrNull { it.id == id }?.name }
                    Text("${e.date} · ${names[e.pillar - 1]} · $label", fontWeight = FontWeight.SemiBold)
                    Text("${euros(e.amount.cents)} · ${e.source} · ${accountName?.let { "Conto: $it" } ?: "Conto non specificato"}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(modifier = Modifier.actionFeedback(!busy), onClick = { editingValueKey = null; editingEventId = e.id; editing = true }, enabled = !busy) { Text("Modifica") }
                        TextButton(modifier = Modifier.actionFeedback(!busy), onClick = { removal = { data.copy(events = data.events - e, audit = data.audit + AuditEntry(java.time.Instant.now().toString(), "rimozione movimento", e.toString())) } }, enabled = !busy) { Text("Rimuovi") }
                    }
                    HorizontalDivider()
                }
            }
            val remaining = (orderedValuations.size - visibleHistoryCount).coerceAtLeast(0) + (orderedEvents.size - visibleHistoryCount).coerceAtLeast(0)
            if (remaining > 0) OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).actionFeedback(),
                onClick = { visibleHistoryCount = (visibleHistoryCount.toLong() + 20L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
            ) { Text("Mostra altre registrazioni · $remaining ancora") }
        } else {
            Text("Conti e fondi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Associa movimenti e valori ai conti quando li registri. I saldi non vengono derivati dai movimenti.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).actionFeedback(!busy), onClick = { editingAccountId = null; addingAccount = true }, enabled = !busy) { Text("Aggiungi conto o fondo") }
            if (data.accounts.isEmpty()) Text("Nessun conto registrato.", style = MaterialTheme.typography.bodySmall)
            data.accounts.forEach { account ->
                val accountValues = data.accountValuations.filter { it.accountId == account.id }.sortedByDescending { it.date }
                val eventReferences = data.events.count { it.accountId == account.id }
                val tradeReferences = data.trades.count { it.accountId == account.id }
                val transferReferences = data.transfers.count { it.fromAccountId == account.id || it.toAccountId == account.id }
                val referencedCount = eventReferences + tradeReferences + transferReferences + accountValues.size
                Text("${account.name} · ${names[account.pillar - 1]}", fontWeight = FontWeight.SemiBold)
                Text(account.source, style = MaterialTheme.typography.bodySmall)
                Text("Classificazione: ${account.type?.let(::accountTypeLabel) ?: "Non specificata"}", style = MaterialTheme.typography.bodySmall)
                Text("Valori manuali del conto · separati dai totali di pilastro", style = MaterialTheme.typography.bodySmall)
                accountValues.forEach { value ->
                    Text("${value.date} · ${euros(value.amount.cents)}", fontWeight = FontWeight.SemiBold)
                    Text("${value.source} · ${if (value.confidence == Confidence.DOCUMENT) "da documento" else "dichiarato"}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(modifier = Modifier.actionFeedback(!busy), onClick = {
                            removal = { data.copy(accountValuations = data.accountValuations - value,
                                audit = data.audit + AuditEntry(java.time.Instant.now().toString(), "rimozione valore conto", value.toString())) }
                        }, enabled = !busy) { Text("Rimuovi valore") }
                    }
                }
                OutlinedButton(modifier = Modifier.actionFeedback(!busy), onClick = { addingAccountValuationForId = account.id }, enabled = !busy) {
                    Text("Aggiungi valore del conto")
                }
                if (referencedCount > 0) Text("Associato a $referencedCount registrazioni, valori o operazioni; rimuovi i riferimenti prima di eliminare il conto.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(modifier = Modifier.actionFeedback(!busy), onClick = { editingAccountId = account.id; addingAccount = true }, enabled = !busy) { Text("Modifica conto") }
                    TextButton(modifier = Modifier.actionFeedback(!busy), onClick = {
                        removal = { data.withoutAccount(account.id).copy(audit = data.audit + AuditEntry(java.time.Instant.now().toString(), "rimozione conto", account.toString())) }
                    }, enabled = !busy && referencedCount == 0) { Text(if (referencedCount == 0) "Rimuovi" else "In uso") }
                }
                HorizontalDivider()
            }
        }
    }
    if (editing) EntryDialog(data, editingValue, editingEvent, saving = busy, saveError = saveError,
        onDismiss = { if (!busy) editing = false }, onSave = { candidate -> persist(candidate) { editing = false } })
    if (editingIndex) IndexDialog(data, saving = busy, saveError = saveError, onDismiss = { if (!busy) editingIndex = false },
        onSave = { candidate -> persist(candidate) { editingIndex = false } })
    if (addingAccount) AccountDialog(data, editingAccount, onDismiss = { if (!busy) { addingAccount = false; editingAccountId = null } },
        saving = busy, saveError = saveError,
        onSave = { candidate -> persist(candidate) { addingAccount = false; editingAccountId = null } })
    addingAccountValuationFor?.let { account ->
        AccountValuationDialog(data, account, saving = busy, saveError = saveError, onDismiss = { if (!busy) addingAccountValuationForId = null },
            onSave = { candidate -> persist(candidate) { addingAccountValuationForId = null } })
    }
    if (showIstat) {
        val compatible = data.indices.all { it.source == Istat.SERIES }
        val newPoints = Istat.snapshot().filter { candidate -> data.indices.none { it.month == candidate.month } }
        AlertDialog(onDismissRequest = { if (!busy) showIstat = false }, title = { Text("Indici ISTAT verificati") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (compatible) "NIC generale, tabacchi inclusi, base statistica 2025=100. Dati definitivi disponibili fino a settembre 2026. Aggiungerò ${newPoints.size} mesi senza sostituire quelli già inseriti; i mesi successivi restano sconosciuti." else "Gli indici esistenti usano una fonte diversa. Non verranno mescolate serie o basi statistiche; rimuovi o raccorda prima gli indici esistenti.")
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(modifier = Modifier.actionFeedback(compatible && newPoints.isNotEmpty() && !busy), enabled = compatible && newPoints.isNotEmpty() && !busy, onClick = { persist(data.copy(indices = data.indices + newPoints)) { showIstat = false } }) { Text(if (saving) "Salvataggio…" else "Aggiungi") } }, dismissButton = { TextButton(modifier = Modifier.actionFeedback(!busy), enabled = !busy, onClick = { showIstat = false }) { Text("Chiudi") } })
    }
    if (removal != null) AlertDialog(onDismissRequest = { if (!busy) removal = null }, title = { Text("Rimuovere la registrazione?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("L'elemento non sarà più incluso nei riepiloghi; una copia del suo contenuto rimarrà nello storico del backup. Per rettificarlo, inserisci poi il dato corretto.")
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(modifier = Modifier.actionFeedback(!busy), enabled = !busy, onClick = { val next = removal?.invoke(); if (next != null) persist(next) { removal = null } }) { Text(if (saving) "Salvataggio…" else "Rimuovi") } },
        dismissButton = { TextButton(modifier = Modifier.actionFeedback(!busy), enabled = !busy, onClick = { removal = null }) { Text("Annulla") } })
}

fun euros(cents: Long): String = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.ITALY).format(BigDecimal.valueOf(cents, 2))

@Composable
private fun AccountDialog(data: Archive, original: Account?, onDismiss: () -> Unit, saving: Boolean, saveError: String?, onSave: (Archive) -> Unit) {
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name ?: "") }
    var source by rememberSaveable(original?.id) { mutableStateOf(original?.source ?: "") }
    var pillar by rememberSaveable(original?.id) { mutableIntStateOf(original?.pillar ?: 1) }
    var accountType by rememberSaveable(original?.id) { mutableStateOf(original?.type) }
    var error by rememberSaveable(original?.id) { mutableStateOf<String?>(null) }
    EditorPanel(title = if (original == null) "Conto o fondo" else "Modifica conto o fondo", onDismiss = onDismiss, saving = saving,
        onConfirm = {
            val cleanName = name.trim(); val cleanSource = source.trim()
            if (cleanName.isEmpty() || cleanName.length > 200 || cleanSource.isEmpty() || cleanSource.length > 500) {
                error = "Inserisci nome (massimo 200 caratteri) e fonte (massimo 500 caratteri)."
            } else {
                try {
                    // An edit keeps the fund link and the legacy code; a new PENSION_FUND account gets a fund named from it.
                    val account = original?.copy(name = cleanName, pillar = pillar, source = cleanSource, type = accountType,
                        fundId = original.fundId.takeIf { accountType == AccountType.PENSION_FUND })
                        ?: Account(UUID.randomUUID().toString(), cleanName, pillar, cleanSource, accountType)
                    val next = (if (original == null) data.copy(accounts = data.accounts + account) else data.updateAccount(account)).withAccountFund(account.id)
                    onSave(next)
                } catch (e: Exception) {
                    error = e.message?.takeIf { it.startsWith("Un fondo") || it.startsWith("Il pilastro") || it.startsWith("Il conto") }
                        ?: "Controlla nome, fonte e classificazione."
                }
            }
        }) { wide ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("La classificazione è scelta da te; non viene dedotta dal nome. Non crea saldi o movimenti.", style = MaterialTheme.typography.bodySmall)
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(source, { source = it }, label = { Text("Fonte") }, singleLine = true, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(source, { source = it }, label = { Text("Fonte") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Text("Pilastro", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.chunked(2).forEachIndexed { rowIndex, rowNames ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowNames.forEachIndexed { columnIndex, label ->
                            val index = rowIndex * 2 + columnIndex
                            EditorChoice(label, pillar == index + 1, Modifier.weight(1f)) {
                                val nextPillar = index + 1
                                if (isPensionAccountType(accountType) && nextPillar != 4) {
                                    error = "Per cambiare pilastro, scegli prima una classificazione generale o non specificata."
                                } else { pillar = nextPillar; error = null }
                            }
                        }
                    }
                }
            }
            Text("Classificazione", style = MaterialTheme.typography.titleSmall)
            val types = listOf(
                    null to "Non specificata",
                    AccountType.GENERAL to "Generale",
                    AccountType.PENSION_FUND to "Fondo pensione"
                )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.chunked(2).forEach { rowTypes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTypes.forEach { (type, label) ->
                            EditorChoice(label, accountType == type, Modifier.weight(1f)) {
                                if (isPensionAccountType(type) && pillar != 4) {
                                    error = "Seleziona prima il pilastro 4 per classificare il conto come fondo pensione."
                                } else { accountType = type; error = null }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun accountTypeLabel(type: AccountType): String = when (type) {
    AccountType.GENERAL -> "Generale"
    AccountType.PENSION_FUND -> "Fondo pensione"
}

private fun isPensionAccountType(type: AccountType?): Boolean =
    type == AccountType.PENSION_FUND

@Composable
private fun AccountValuationDialog(data: Archive, account: Account, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (Archive) -> Unit) {
    var amount by rememberSaveable(account.id) { mutableStateOf("") }
    var date by rememberSaveable(account.id) { mutableStateOf(LocalDate.now().toString()) }
    var source by rememberSaveable(account.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    EditorPanel(title = "Valore manuale · ${account.name}", onDismiss = onDismiss, confirmLabel = "Salva valore", saving = saving, onConfirm = {
        try {
            val cents = BigDecimal(amount.trim().replace(',', '.')).movePointRight(2).longValueExact()
            require(cents >= 0) { "L'importo non può essere negativo." }
            val day = LocalDate.parse(date.trim())
            require(!day.isAfter(LocalDate.now())) { "La data non può essere futura." }
            require(source.isNotBlank() && source.trim().length <= 500) { "Inserisci la fonte (massimo 500 caratteri)." }
            require(data.accountValuations.none { it.accountId == account.id && it.date == day }) { "Esiste già un valore per questo conto e giorno." }
            val value = AccountValuation(account.id, day, Money(cents), source.trim(), Confidence.USER_REPORTED)
            onSave(data.copy(accountValuations = data.accountValuations + value))
        } catch (e: Exception) {
            error = e.message?.takeIf { it.startsWith("L'importo") || it.startsWith("La data") || it.startsWith("Inserisci") || it.startsWith("Esiste") }
                ?: "Controlla importo, data e fonte."
        }
    }) { wide ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Valore dichiarato del singolo conto. Non viene aggiunto ai totali del pilastro né derivato dai movimenti.", style = MaterialTheme.typography.bodySmall)
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text("Importo in euro") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(date, { date = it }, label = { Text("Data · AAAA-MM-GG") }, singleLine = true, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(amount, { amount = it }, label = { Text("Importo in euro") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(date, { date = it }, label = { Text("Data · AAAA-MM-GG") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(source, { source = it }, label = { Text("Fonte · es. estratto conto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun IndexDialog(data: Archive, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (Archive) -> Unit) {
    var month by rememberSaveable { mutableStateOf("2026-01") }
    var index by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(data.indices.firstOrNull()?.source ?: Istat.SERIES) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorPanel(title = "Indice mensile", onDismiss = onDismiss, saving = saving, onConfirm = {
        try {
            val parsedMonth = YearMonth.parse(month.trim())
            require(!parsedMonth.isAfter(YearMonth.now()) && data.indices.none { it.month == parsedMonth })
            require(data.indices.isEmpty() || data.indices.first().source.trim() == source.trim())
            val point = IndexPoint(parsedMonth, BigDecimal(index.trim().replace(',', '.')), source.trim())
            onSave(data.copy(indices = data.indices + point))
        } catch (_: Exception) { error = "Controlla mese, indice positivo e fonte: la fonte deve coincidere con gli altri indici, il mese non deve essere futuro o duplicato." }
    }) { wide ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Usa la stessa serie NIC e base statistica per tutti i mesi. Per basi diverse occorre il raccordo ISTAT.", style = MaterialTheme.typography.bodySmall)
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(month, { month = it }, label = { Text("Mese · AAAA-MM") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(index, { index = it }, label = { Text("Valore dell'indice") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(month, { month = it }, label = { Text("Mese · AAAA-MM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(index, { index = it }, label = { Text("Valore dell'indice") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(source, { source = it }, label = { Text("Fonte e base statistica") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

