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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import xyz.grtlabs.pilastri.components.EditorChoice
import xyz.grtlabs.pilastri.components.EditorPanel
import xyz.grtlabs.pilastri.components.actionFeedback
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.*

/** A partial user-entered journal; this screen never derives holdings, balances, or performance. */
@Composable
fun TradingPage(data: Archive, onSave: ArchiveSave, onBack: () -> Unit) {
    var current by remember(data) { mutableStateOf(data) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var instrumentEditor by rememberSaveable { mutableStateOf(false) }
    var editingInstrumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var tradeEditor by rememberSaveable { mutableStateOf(false) }
    var editingTradeId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTradeId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingInstrument = current.instruments.firstOrNull { it.id == editingInstrumentId }
    val editingTrade = current.trades.firstOrNull { it.id == editingTradeId }
    val pendingRemoval = current.trades.firstOrNull { it.id == removeTradeId }

    fun persist(candidate: Archive, after: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        onSave(candidate) { saved ->
            busy = false
            if (saved) { current = candidate; after() }
            else error = "Salvataggio non riuscito. I dati precedenti sono conservati; puoi riprovare."
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Registro strumenti", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).actionFeedback(!busy)) { Text("Indietro") }
            }
            Text("Storico parziale inserito manualmente. Acquisti e vendite non sono contributi. Non vengono ricavati saldo, possesso completo, rendimento o prezzi correnti.", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val instruments: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Strumenti", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { editingInstrumentId = null; instrumentEditor = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).actionFeedback(!busy)) { Text("Aggiungi strumento") }
                    if (current.instruments.isEmpty()) Text("Nessuno strumento registrato.")
                    current.instruments.forEach { instrument ->
                        Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(instrument.name, style = MaterialTheme.typography.titleMedium)
                            instrument.identifier?.let { Text("Identificativo: $it") }
                            Text("Fonte: ${instrument.source}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editingInstrumentId = instrument.id; instrumentEditor = true }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Modifica") }
                                val referenced = current.trades.any { it.instrumentId == instrument.id }
                                TextButton(onClick = { runCatching { current.withoutInstrument(instrument.id) }.onSuccess { persist(it) }.onFailure { error = it.message } }, enabled = !busy && !referenced, modifier = Modifier.actionFeedback(!busy && !referenced)) {
                                    Text(if (referenced) "Usato nello storico" else "Rimuovi")
                                }
                            }
                        } }
                    }
                }
            }
            val trades: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Operazioni", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = {
                        if (current.accounts.isEmpty() || current.instruments.isEmpty()) error = "Aggiungi prima un conto e uno strumento."
                        else { editingTradeId = null; tradeEditor = true }
                    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).actionFeedback(!busy)) { Text("Aggiungi acquisto o vendita") }
                    if (current.trades.isEmpty()) Text("Nessuna operazione registrata.")
                    current.trades.sortedByDescending { it.date }.forEach { trade ->
                        val account = current.accounts.firstOrNull { it.id == trade.accountId }?.name ?: "Conto non disponibile"
                        val instrument = current.instruments.firstOrNull { it.id == trade.instrumentId }?.name ?: "Strumento non disponibile"
                        Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${if (trade.side == TradeSide.BUY) "Acquisto" else "Vendita"} · ${trade.date}", style = MaterialTheme.typography.titleMedium)
                            Text("$instrument · conto: $account")
                            Text("Quantità: ${trade.quantity.toPlainString()}")
                            Text("Importo lordo dichiarato: ${euros(trade.grossAmount.cents)} · spese: ${euros(trade.fees.cents)}")
                            Text("Fonte: ${trade.source}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editingTradeId = trade.id; tradeEditor = true }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Modifica") }
                                TextButton(onClick = { removeTradeId = trade.id }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Rimuovi") }
                            }
                        } }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { instruments() }
                Column(Modifier.weight(1.4f)) { trades() }
            } else { instruments(); trades() }
        }
    }

    if (instrumentEditor) InstrumentEditor(
        initial = editingInstrument,
        onDismiss = { if (!busy) { instrumentEditor = false; editingInstrumentId = null } },
        onSave = { item ->
            val original = editingInstrument
            val replacement = if (original == null) item else item.copy(id = original.id)
            val candidate = if (original == null) current.copy(instruments = current.instruments + replacement)
                else current.correctInstrument(original, replacement, Instant.now().toString())
            persist(candidate) { instrumentEditor = false; editingInstrumentId = null }
        }, saving = busy, saveError = error
    )
    if (tradeEditor) TradeEditor(
        accounts = current.accounts, instruments = current.instruments, initial = editingTrade,
        onDismiss = { if (!busy) { tradeEditor = false; editingTradeId = null } },
        onSave = { trade ->
            val original = editingTrade
            val replacement = if (original == null) trade else trade.copy(id = original.id)
            val candidate = if (original == null) current.copy(trades = current.trades + replacement)
                else current.correctTrade(original, replacement, Instant.now().toString())
            persist(candidate) { tradeEditor = false; editingTradeId = null }
        }, saving = busy, saveError = error
    )
    pendingRemoval?.let { trade -> AlertDialog(
        onDismissRequest = { if (!busy) removeTradeId = null }, title = { Text("Rimuovere l'operazione?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("La registrazione verrà rimossa dallo storico e conservata nel registro delle modifiche.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { persist(current.withoutTrade(trade.id)) { removeTradeId = null } }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text(if (busy) "Salvataggio…" else "Rimuovi") } },
        dismissButton = { TextButton(onClick = { removeTradeId = null }, enabled = !busy, modifier = Modifier.actionFeedback(!busy)) { Text("Annulla") } })
    }
}

@Composable private fun InstrumentEditor(initial: Instrument?, onDismiss: () -> Unit, onSave: (Instrument) -> Unit, saving: Boolean, saveError: String?) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var identifier by rememberSaveable(initial?.id) { mutableStateOf(initial?.identifier.orEmpty()) }
    var source by rememberSaveable(initial?.id) { mutableStateOf(initial?.source ?: "") }
    var attempted by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var localError by rememberSaveable(initial?.id) { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && name.trim().length <= 200 && identifier.trim().length <= 100 && source.isNotBlank() && source.trim().length <= 500
    EditorPanel(if (initial == null) "Nuovo strumento" else "Modifica strumento", onDismiss, onConfirm = {
        attempted = true
        if (valid) runCatching { onSave(Instrument(initial?.id ?: UUID.randomUUID().toString(), name.trim(), identifier.trim().ifEmpty { null }, source.trim())) }
            .onFailure { localError = it.message ?: "Controlla i dati inseriti." }
        else localError = "Inserisci nome e fonte entro i limiti indicati."
    }, confirmLabel = if (initial == null) "Aggiungi" else "Salva modifiche", saving = saving) {
        OutlinedTextField(name, { name = it; localError = null }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(identifier, { identifier = it; localError = null }, label = { Text("Identificativo (facoltativo)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(source, { source = it; localError = null }, label = { Text("Fonte del dato") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (attempted) (localError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun TradeEditor(accounts: List<Account>, instruments: List<Instrument>, initial: Trade?, onDismiss: () -> Unit, onSave: (Trade) -> Unit, saving: Boolean, saveError: String?) {
    var accountId by rememberSaveable(initial?.id) { mutableStateOf(initial?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }
    var instrumentId by rememberSaveable(initial?.id) { mutableStateOf(initial?.instrumentId ?: instruments.firstOrNull()?.id.orEmpty()) }
    var sideIndex by rememberSaveable(initial?.id) { mutableIntStateOf(if (initial?.side == TradeSide.SELL) 1 else 0) }
    val side = if (sideIndex == 1) TradeSide.SELL else TradeSide.BUY
    var date by rememberSaveable(initial?.id) { mutableStateOf(initial?.date?.toString() ?: LocalDate.now().toString()) }
    var quantity by rememberSaveable(initial?.id) { mutableStateOf(initial?.quantity?.toPlainString().orEmpty()) }
    var gross by rememberSaveable(initial?.id) { mutableStateOf(initial?.grossAmount?.let { BigDecimal.valueOf(it.cents, 2).toPlainString() }.orEmpty()) }
    var fees by rememberSaveable(initial?.id) { mutableStateOf(initial?.fees?.let { BigDecimal.valueOf(it.cents, 2).toPlainString() } ?: "0") }
    var source by rememberSaveable(initial?.id) { mutableStateOf(initial?.source.orEmpty()) }
    var attempted by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var localError by rememberSaveable(initial?.id) { mutableStateOf<String?>(null) }
    fun cents(raw: String, allowZero: Boolean): Long? = runCatching { BigDecimal(raw.trim().replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }
    val q = runCatching { BigDecimal(quantity.trim().replace(',', '.')) }.getOrNull()
    val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    val amount = cents(gross, false); val fee = cents(fees, true)
    val valid = accounts.any { it.id == accountId } && instruments.any { it.id == instrumentId } && q?.let { it.signum() > 0 && it.scale() in 0..12 && it.precision() <= 24 } == true && parsedDate?.let { !it.isAfter(LocalDate.now()) } == true && amount != null && fee != null && source.isNotBlank() && source.trim().length <= 500
    EditorPanel(if (initial == null) "Nuova operazione" else "Modifica operazione", onDismiss, onConfirm = {
        attempted = true
        if (valid) runCatching { onSave(Trade(initial?.id ?: UUID.randomUUID().toString(), accountId, instrumentId, parsedDate!!, side, q!!, Money(amount!!), Money(fee!!), source.trim())) }
            .onFailure { localError = it.message ?: "Controlla i dati inseriti." }
        else localError = "Verifica conto, strumento, data, quantità, importi e fonte; i campi devono rispettare i limiti e la data non può essere futura."
    }, confirmLabel = if (initial == null) "Aggiungi" else "Salva modifiche", saving = saving) { wide ->
        Text("Acquisto e vendita sono registrazioni interne, separate dai contributi.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorChoice("Acquisto", side == TradeSide.BUY, Modifier.weight(1f)) { sideIndex = 0 }
            EditorChoice("Vendita", side == TradeSide.SELL, Modifier.weight(1f)) { sideIndex = 1 }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { ChoicePicker("Conto", accounts.map { it.id to it.name }, accountId) { accountId = it; localError = null } }
            Box(Modifier.weight(1f)) { ChoicePicker("Strumento", instruments.map { it.id to it.name }, instrumentId) { instrumentId = it; localError = null } }
        } else {
            ChoicePicker("Conto", accounts.map { it.id to it.name }, accountId) { accountId = it; localError = null }
            ChoicePicker("Strumento", instruments.map { it.id to it.name }, instrumentId) { instrumentId = it; localError = null }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(date, { date = it; localError = null }, label = { Text("Data (AAAA-MM-GG)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(quantity, { quantity = it; localError = null }, label = { Text("Quantità positiva") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
        } else {
            OutlinedTextField(date, { date = it; localError = null }, label = { Text("Data (AAAA-MM-GG)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(quantity, { quantity = it; localError = null }, label = { Text("Quantità positiva") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(gross, { gross = it; localError = null }, label = { Text("Lordo dichiarato (EUR)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(fees, { fees = it; localError = null }, label = { Text("Spese (EUR)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
        } else {
            OutlinedTextField(gross, { gross = it; localError = null }, label = { Text("Importo lordo dichiarato (EUR)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fees, { fees = it; localError = null }, label = { Text("Spese (EUR, zero consentito)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(source, { source = it; localError = null }, label = { Text("Fonte") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (attempted) (localError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun ChoicePicker(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val value = options.firstOrNull { it.first == selected }?.second ?: "Seleziona"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).actionFeedback()) { Text("$label: $value", maxLines = 2) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}
