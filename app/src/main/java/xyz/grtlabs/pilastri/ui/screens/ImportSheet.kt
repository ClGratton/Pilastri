package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.ui.*

private fun defaultPillar(p: ImportProvider) = when (p) { ImportProvider.TRADE_REPUBLIC -> 2; else -> 1 }

/**
 * The preview of a connector's batch: what arrived (count, period, balances, installment plans, warnings), then which
 * account receives it. Nothing is written before "Importa"; the movements then wait in "Da rivedere".
 */
@Composable
fun ImportSheet(app: App, pending: PendingImport, onDismiss: () -> Unit) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val b = pending.batch
    val known = a.connections.firstOrNull { it.provider == b.provider }
    val broker = b.provider == ImportProvider.TRADE_REPUBLIC
    val pillarLimit: Int? = if (b.provider == ImportProvider.PAYPAL) 1 else null
    val candidates = a.accounts.filter { pillarLimit == null || it.pillar == pillarLimit }
    // Account choice: existing id, or null for a new account with [newName] in [newPillar].
    var accountId by remember { mutableStateOf(known?.accountId ?: candidates.firstOrNull { it.name.contains(b.provider.label, true) }?.id) }
    var newName by remember { mutableStateOf(if (broker) "Trade Republic · liquidità" else b.accountLabel.ifBlank { b.provider.label }.take(60)) }
    var newPillar by remember { mutableIntStateOf(pillarLimit ?: defaultPillar(b.provider)) }
    val firstBroker = broker && known == null
    var split by remember { mutableStateOf(firstBroker) }
    var secId by remember { mutableStateOf<String?>(null) }
    var secName by remember { mutableStateOf("Trade Republic · titoli") }
    val dates = listOfNotNull(b.coverageFrom, b.coverageTo)
    Sheet(onDismiss) {
        Text("Importa da ${b.provider.label}", style = Type.screenTitle, color = pal.ink)
        CardColumn {
            FactLine("Movimenti", "${b.movements.size}", note = "da rivedere")
            if (dates.isNotEmpty()) FactLine("Periodo", "${b.coverageFrom?.let { Fmt.dayYear(it) } ?: "?"} – ${b.coverageTo?.let { Fmt.dayYear(it) } ?: "?"}", stacked = true)
            if (broker && (b.cashBalance != null || b.securitiesBalance != null || b.positions.isNotEmpty())) {
                FactLine("Liquidità", b.cashBalance?.let { "${Fmt.eurCents(it.cents)} · ${Fmt.day(it.date)}" } ?: "n/d", stacked = true)
                FactLine("Titoli", b.securitiesBalance?.let { "${Fmt.eurCents(it.cents)} · ${Fmt.day(it.date)}" } ?: "n/d", stacked = true, note = if (b.securitiesBalance == null && b.positions.isNotEmpty()) "manca la quotazione di almeno un titolo: valore non calcolato" else "${b.positions.size} posizioni")
            } else FactLine("Saldo", b.balance?.let { "${Fmt.eurCents(it.cents)} · ${Fmt.day(it.date)}" } ?: "non indicato", stacked = true, note = if (b.balance != null) "diventa il valore datato del conto, dal fornitore" else null)
            if (b.installmentPlans.isNotEmpty()) FactLine("Piani a rate", "${b.installmentPlans.size}", stacked = true, note = "da confermare dopo l'importazione")
        }
        if (b.warnings.isNotEmpty()) CardColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Da sapere", style = Type.smallStrong, color = pal.ink)
            b.warnings.take(6).forEach { Text("· $it", style = Type.small, color = pal.ink2) }
            if (b.warnings.size > 6) Text("e altri ${b.warnings.size - 6}", style = Type.small, color = pal.ink2)
        }
        if (known != null) {
            val acc = a.accounts.firstOrNull { it.id == known.accountId }
            val sec = known.securitiesAccountId?.let { id -> a.accounts.firstOrNull { it.id == id } }
            Text("Nel conto " + (acc?.name ?: "collegato") + (sec?.let { " (titoli in ${it.name})" } ?: "") + ", come l'ultima volta.", style = Type.body, color = pal.ink2)
        } else {
            if (broker) {
                Text("Liquidità e titoli", style = Type.smallStrong, color = pal.ink)
                Segmented(listOf("Due conti", "Un conto solo"), if (split) 0 else 1, { split = it == 0 })
                Text(if (split) "La liquidità va nel pilastro che scegli (di solito Emergenze), i titoli in Lungo termine." else "Tutto il saldo in un solo conto.", style = Type.small, color = pal.ink2)
            }
            AccountChooser(if (split) "Conto per la liquidità" else "Conto", candidates.filter { it.id != secId }, accountId, { accountId = it }, newName, { newName = it }, newPillar, if (pillarLimit == null) ({ newPillar = it }) else null)
            if (broker && split) {
                Text("Conto per i titoli", style = Type.smallStrong, color = pal.ink)
                RowsCard {
                    a.accounts.filter { it.pillar == 4 && it.id != accountId }.forEach { acc -> ListRow(acc.name, pillarName(acc.pillar), { secId = acc.id }, selected = secId == acc.id, chevron = false) }
                    ListRow("Nuovo conto: $secName", "Lungo termine", { secId = null }, selected = secId == null, chevron = false)
                }
            }
        }
        val valid = (known != null) || (accountId != null || newName.isNotBlank()) && (!split || secId != null || secName.isNotBlank())
        RoleActionRow("import", spacing = 8.dp, end = true,
            secondary = { m -> PillButton("Annulla", onDismiss, m, kind = Btn.GHOST) },
            confirm = { m -> PillButton("Importa", {
                onDismiss()
                var added = 0; var plans = 0
                app.commit(null, then = {
                    app.say("$added nuovi movimenti da rivedere" + if (plans > 0) ", $plans piani a rate" else "", if (added > 0) "Rivedi" else null) { app.go(Route.Review) }
                }) { cur ->
                    var arch = cur
                    val target = known?.accountId ?: accountId ?: arch.withNewAccount(newName, newPillar, "${b.provider.label} · collegamento").also { arch = it.first }.second
                    val securities = if (known != null) null else if (broker && split) secId ?: arch.withNewAccount(secName, 4, "${b.provider.label} · collegamento").also { arch = it.first }.second else null
                    val out = arch.withImportBatch(b, target, app.nowStamp(), securities)
                    added = out.added; plans = out.plansAdded
                    out.archive
                }
            }, m, kind = Btn.PRIMARY, enabled = valid) })
    }
}

@Composable
private fun AccountChooser(title: String, accounts: List<Account>, selected: String?, onSelect: (String?) -> Unit, newName: String, onName: (String) -> Unit, newPillar: Int, onPillar: ((Int) -> Unit)?) {
    val pal = LocalPal.current
    Text(title, style = Type.smallStrong, color = pal.ink)
    RowsCard {
        accounts.forEach { acc -> ListRow(acc.name, pillarName(acc.pillar), { onSelect(acc.id) }, selected = selected == acc.id, chevron = false) }
        ListRow("Nuovo conto", "creato con questa importazione", { onSelect(null) }, selected = selected == null, chevron = false)
    }
    if (selected == null) {
        TextInput(newName, onName, "Nome del nuovo conto")
        if (onPillar != null) Segmented(listOf("Liquidità", "Emergenze", "Progetti", "Lungo t."), newPillar - 1, { onPillar(it + 1) })
        else Text("Pilastro: Liquidità (le spese contano solo sui conti di Liquidità).", style = Type.small, color = pal.ink2)
    }
}

@Composable
fun RestoreDialog(app: App, candidate: Archive) {
    val pal = LocalPal.current
    AlertDialog(onDismissRequest = { app.restoreCandidate = null }, containerColor = pal.card, shape = BigCardShape,
        title = { Text("Ripristinare il backup?", style = Type.screenTitle, color = pal.ink) },
        text = { Text("Il backup contiene ${candidate.accounts.size} conti, ${candidate.events.size} registrazioni, ${candidate.accountValuations.size} valori di conto, ${candidate.externalMovements.size} movimenti importati e ${candidate.installments.size} piani a rate. Sostituirà tutti i dati attuali. Se l'archivio locale non è leggibile, una copia resta nella memoria privata dell'app.", style = Type.body, color = pal.ink2) },
        confirmButton = { PillButton("Sostituisci", {
            app.restoreCandidate = null
            app.scope.launch { if (app.commitNow(recover = app.loadError != null, keepCopy = true) { candidate }) { app.hx?.confirm(); app.say("Backup ripristinato.") } }
        }, kind = Btn.PRIMARY) },
        dismissButton = { PillButton("Annulla", { app.restoreCandidate = null }, kind = Btn.GHOST) })
}

@Composable
fun PrefillDialog(app: App, p: PendingPrefill) {
    val pal = LocalPal.current
    val a = app.archive
    val newItems = p.items.count { item -> a?.plan?.none { it.id == item.id } != false }
    AlertDialog(onDismissRequest = { app.prefillCandidate = null }, containerColor = pal.card, shape = BigCardShape,
        title = { Text("Rivedi i dati dichiarati", style = Type.screenTitle, color = pal.ink) },
        text = { Text("Il file propone ${p.items.size} pagamenti netti dichiarati ($newItems nuovi) e fatti di profilo (date di contratto, adesioni ad Allianz ed Espero, aliquote dichiarate). Le aliquote non sono versamenti; non si importano saldi né rendimenti. I fatti già presenti restano: i nuovi valori compilano solo i campi mancanti.", style = Type.body, color = pal.ink2) },
        confirmButton = { PillButton("Importa", {
            app.prefillCandidate = null
            app.commit("Dati dichiarati importati.") { it.withPrefillImport(p.items, p.facts) }
        }, kind = Btn.PRIMARY) },
        dismissButton = { PillButton("Annulla", { app.prefillCandidate = null }, kind = Btn.GHOST) })
}

