package xyz.grtlabs.pilastri

import xyz.grtlabs.pilastri.ui.Motion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import xyz.grtlabs.pilastri.components.actionFeedback
import xyz.grtlabs.pilastri.components.EditorPanel
import xyz.grtlabs.pilastri.components.EditorChoice
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.correctContribution
import xyz.grtlabs.pilastri.data.correctValuation
import xyz.grtlabs.pilastri.domain.*

/** A focused editor, full screen on phones and a bounded panel on an unfolded Fold. */
@Composable
internal fun EntryDialog(
    data: Archive, originalValue: Valuation?, originalEvent: LedgerEvent?,
    onDismiss: () -> Unit, onSave: (Archive) -> Unit,
    saving: Boolean = false, saveError: String? = null
) {
    val correction = originalValue != null || originalEvent != null
    val names = listOf("Liquidità", "Emergenze", "Progetti", "Lungo termine")
    var pillar by rememberSaveable { mutableIntStateOf(originalValue?.pillar ?: originalEvent?.pillar ?: 1) }
    var accountId by rememberSaveable { mutableStateOf(originalEvent?.accountId) }
    var kind by rememberSaveable { mutableIntStateOf(when (originalEvent?.type) {
        EventType.EMPLOYER_CONTRIBUTION -> 2
        EventType.PERSONAL_CONTRIBUTION -> 1
        EventType.WITHDRAWAL -> 3
        EventType.INCOME -> 4
        EventType.FEE -> 5
        else -> 0
    }) }
    var amount by rememberSaveable { mutableStateOf((originalValue?.amount ?: originalEvent?.amount)?.let {
        BigDecimal.valueOf(it.cents, 2).toPlainString().replace('.', ',')
    } ?: "") }
    var date by rememberSaveable { mutableStateOf((originalValue?.date ?: originalEvent?.date ?: LocalDate.now()).toString()) }
    var source by rememberSaveable { mutableStateOf(originalValue?.source ?: originalEvent?.source ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var accountMenu by remember { mutableStateOf(false) }
    fun submit() {
        try {
            val input = amount.trim().replace(',', '.')
            require(input.matches(Regex("[0-9]+(\\.[0-9]{1,2})?"))) { "Inserisci un importo positivo con al massimo due decimali." }
            val cents = BigDecimal(input).movePointRight(2).longValueExact()
            val day = runCatching { LocalDate.parse(date.trim()) }.getOrElse { throw IllegalArgumentException("Usa una data nel formato AAAA-MM-GG.") }
            require(!day.isAfter(LocalDate.now())) { "La data non può essere futura." }
            require(source.isNotBlank() && source.trim().length <= 500) { "Indica la fonte, fino a 500 caratteri." }
            val result = if (kind == 0) {
                val value = Valuation(day, pillar, Money(cents), source.trim(), Confidence.USER_REPORTED)
                if (originalValue != null) data.correctValuation(originalValue, value, Instant.now().toString())
                else {
                    require(data.valuations.none { it.pillar == pillar && it.date == day }) { "Esiste già un valore per questo pilastro e giorno." }
                    data.copy(valuations = data.valuations + value)
                }
            } else {
                require(kind != 2 || pillar == 4) { "Il contributo del datore appartiene al lungo termine." }
                require(accountId == null || data.accounts.any { it.id == accountId && it.pillar == pillar }) { "Scegli un conto di questo pilastro." }
                val event = LedgerEvent(originalEvent?.id ?: UUID.randomUUID().toString(), day, pillar,
                    when (kind) {
                        2 -> EventType.EMPLOYER_CONTRIBUTION
                        3 -> EventType.WITHDRAWAL
                        4 -> EventType.INCOME
                        5 -> EventType.FEE
                        else -> EventType.PERSONAL_CONTRIBUTION
                    },
                    Money(cents), Confidence.USER_REPORTED, source.trim(), accountId = accountId)
                if (originalEvent != null) data.correctContribution(originalEvent, event, Instant.now().toString())
                else data.copy(events = data.events + event)
            }
            onSave(result)
        } catch (e: Exception) {
            error = if (e is ArithmeticException) "L'importo è troppo grande." else e.message ?: "Controlla i dati inseriti."
        }
    }
    EditorPanel(
        title = if (correction) "Modifica registrazione" else "Nuova registrazione",
        onDismiss = onDismiss,
        onConfirm = { submit() },
        confirmLabel = if (correction) "Salva modifiche" else "Salva registrazione",
        saving = saving
    ) { roomy ->
                        Text("Importo in euro", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            amount, { amount = it; error = null }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Importo in euro" },
                            placeholder = { Text("0,00", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineLarge) },
                            textStyle = MaterialTheme.typography.headlineLarge.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                            shape = RoundedCornerShape(24.dp)
                        )
                        Column(Modifier.animateContentSize(Motion.travel(spring(dampingRatio = .85f, stiffness = 420f))), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Pilastro", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val columns = if (roomy) 4 else 2
                                names.chunked(columns).forEachIndexed { row, labels ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        labels.forEachIndexed { col, label ->
                                            val id = row * columns + col + 1
                                            EditorChoice(label, pillar == id, Modifier.weight(1f)) {
                                                pillar = id
                                                if (kind == 2 && pillar != 4 && !correction) kind = 1
                                                if (accountId != null && data.accounts.none { it.id == accountId && it.pillar == pillar }) accountId = null
                                                error = null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Registrazione", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val labels = listOf("Valore", "Versato da te", "Datore", "Prelievo", "Provento", "Costo")
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            labels.chunked(3).forEachIndexed { row, choices ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                choices.forEachIndexed { col, label ->
                                    val index = row * 3 + col
                                    EditorChoice(label, kind == index, Modifier.weight(1f), enabled = !correction || kind == index) {
                                        kind = index
                                        if (kind == 2) { pillar = 4; if (data.accounts.none { it.id == accountId && it.pillar == 4 }) accountId = null }
                                        error = null
                                    }
                                }
                            }
                            }
                            }
                            Text(when (kind) {
                                0 -> "Il valore totale del pilastro alla data indicata."
                                1 -> "Un versamento personale, separato dal rendimento."
                                2 -> "Il contributo effettivamente versato dal datore."
                                3 -> "Denaro uscito dal patrimonio, distinto dai versamenti."
                                4 -> "Interessi, cedole o dividendi effettivamente ricevuti."
                                else -> "Commissioni o spese effettivamente addebitate."
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (kind != 0) {
                            Box(Modifier.fillMaxWidth()) {
                                val name = data.accounts.firstOrNull { it.id == accountId }?.name
                                OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).actionFeedback(), shape = RoundedCornerShape(16.dp)) {
                                    Text(name ?: "Conto facoltativo · scegli", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                                    DropdownMenuItem(text = { Text("Non specificato") }, onClick = { accountId = null; accountMenu = false })
                                    data.accounts.filter { it.pillar == pillar }.forEach { account ->
                                        DropdownMenuItem(text = { Text(account.name) }, onClick = { accountId = account.id; accountMenu = false })
                                    }
                                }
                            }
                        }
                        if (roomy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(date, { date = it; error = null }, modifier = Modifier.weight(.8f),
                                label = { Text("Data · AAAA-MM-GG") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(source, { source = it; error = null }, modifier = Modifier.weight(1.2f),
                                label = { Text("Fonte") }, placeholder = { Text("Es. estratto conto") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                        } else {
                            OutlinedTextField(date, { date = it; error = null }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Data · AAAA-MM-GG") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(source, { source = it; error = null }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Fonte") }, placeholder = { Text("Es. estratto conto di ottobre") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                        }
                        if (correction) Text("La versione precedente resta nello storico.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(4.dp))
    }
}
