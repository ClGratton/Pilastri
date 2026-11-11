package xyz.grtlabs.pilastri

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.components.EditorPanel
import xyz.grtlabs.pilastri.components.actionFeedback
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.AccountTransfer
import xyz.grtlabs.pilastri.domain.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Composable
fun TransferPage(data: Archive, onSave: ArchiveSave, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var originalId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun persist(candidate: Archive, after: () -> Unit) {
        if (busy) return
        busy = true; error = null
        onSave(candidate) { success ->
            busy = false
            if (success) after() else error = "Salvataggio non riuscito. I dati precedenti sono conservati; puoi riprovare."
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Trasferimenti", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).actionFeedback(!busy)) { Text("Indietro") }
        }
        Text("Sposta denaro tra due conti già registrati. Il trasferimento non aumenta i versamenti personali e non è un guadagno.")
        Button(onClick = { originalId = null; error = null; editing = true }, enabled = !busy && data.accounts.size >= 2,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).actionFeedback(!busy && data.accounts.size >= 2)) { Text("Nuovo trasferimento") }
        if (data.accounts.size < 2) Text("Aggiungi almeno due conti in Movimenti per registrare un trasferimento.")
        Text("I valori dei conti restano rilevazioni separate. Eventuali spese vanno registrate come costo.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (data.transfers.isEmpty()) Text("Nessun trasferimento registrato.")
        data.transfers.sortedByDescending { it.date }.forEach { transfer ->
            val from = data.accounts.firstOrNull { it.id == transfer.fromAccountId }?.name.orEmpty()
            val to = data.accounts.firstOrNull { it.id == transfer.toAccountId }?.name.orEmpty()
            HorizontalDivider()
            Text("$from → $to", style = MaterialTheme.typography.titleMedium)
            Text("${euros(transfer.amount.cents)} · ${transfer.date}")
            Text("Fonte: ${transfer.source}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { originalId = transfer.id; error = null; editing = true }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Modifica trasferimento") }
                TextButton(onClick = { removeId = transfer.id }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Rimuovi") }
            }
        }
    }
    if (editing) key(originalId) {
        val original = data.transfers.firstOrNull { it.id == originalId }
        TransferEditor(data.accounts, original, busy, error, { editing = false }) { transfer ->
            runCatching {
                if (original == null) data.copy(transfers = data.transfers + transfer)
                else data.correctTransfer(original, transfer, Instant.now().toString())
            }.onSuccess { persist(it) { editing = false } }.onFailure { error = it.message }
        }
    }
    removeId?.let { id ->
        AlertDialog(onDismissRequest = { if (!busy) removeId = null }, title = { Text("Rimuovere il trasferimento?") },
            text = { Text("Il record precedente resta nello storico delle modifiche.") },
            confirmButton = { TextButton(onClick = {
                runCatching { data.withoutTransfer(id) }.onSuccess { persist(it) { removeId = null } }.onFailure { error = it.message }
            }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Rimuovi") } },
            dismissButton = { TextButton(onClick = { removeId = null }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Annulla") } })
    }
}

@Composable
private fun TransferEditor(accounts: List<Account>, initial: AccountTransfer?, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSubmit: (AccountTransfer) -> Unit) {
    var fromId by rememberSaveable { mutableStateOf(initial?.fromAccountId ?: accounts.first().id) }
    var toId by rememberSaveable { mutableStateOf(initial?.toAccountId ?: accounts[1].id) }
    var date by rememberSaveable { mutableStateOf(initial?.date?.toString() ?: LocalDate.now().toString()) }
    var amount by rememberSaveable { mutableStateOf(initial?.amount?.cents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var source by rememberSaveable { mutableStateOf(initial?.source.orEmpty()) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    EditorPanel(if (initial == null) "Nuovo trasferimento" else "Modifica trasferimento", onDismiss, {
        val cents = runCatching { BigDecimal(amount.trim().replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }.getOrNull()
        val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        if (fromId == toId || accounts.none { it.id == fromId } || accounts.none { it.id == toId }) localError = "Scegli due conti diversi."
        else if (cents == null || cents <= 0) localError = "Inserisci un importo positivo con al massimo due decimali."
        else if (parsedDate == null || parsedDate.isAfter(LocalDate.now())) localError = "Inserisci una data valida, non futura."
        else if (source.isBlank() || source.trim().length > 500) localError = "Indica una fonte entro 500 caratteri."
        else { localError = null; onSubmit(AccountTransfer(initial?.id ?: UUID.randomUUID().toString(), parsedDate, fromId, toId, Money(cents), source.trim())) }
    }, saving = saving) { wide ->
        Text("Da quale conto a quale conto?")
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { TransferAccountPicker("Origine", accounts, fromId) { fromId = it } }
            Box(Modifier.weight(1f)) { TransferAccountPicker("Destinazione", accounts, toId) { toId = it } }
        } else {
            TransferAccountPicker("Origine", accounts, fromId) { fromId = it }
            TransferAccountPicker("Destinazione", accounts, toId) { toId = it }
        }
        OutlinedTextField(amount, { amount = it; localError = null }, label = { Text("Importo trasferito (EUR)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(date, { date = it; localError = null }, label = { Text("Data (AAAA-MM-GG)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(source, { source = it; localError = null }, label = { Text("Fonte") }, modifier = Modifier.fillMaxWidth())
        (localError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TransferAccountPicker(label: String, accounts: List<Account>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).actionFeedback()) {
            Text("$label: ${accounts.firstOrNull { it.id == selected }?.name ?: "Seleziona"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            accounts.forEach { account -> DropdownMenuItem(text = { Text("${account.name} · pilastro ${account.pillar}") },
                onClick = { onSelect(account.id); expanded = false }, modifier = Modifier.actionFeedback()) }
        }
    }
}
