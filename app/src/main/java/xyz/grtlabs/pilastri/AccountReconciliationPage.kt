package xyz.grtlabs.pilastri

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.components.actionFeedback
import xyz.grtlabs.pilastri.data.AccountPillarComparison
import xyz.grtlabs.pilastri.data.AccountPillarReconciliation
import xyz.grtlabs.pilastri.data.AccountSnapshot
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.reconcileAccountValuations
import xyz.grtlabs.pilastri.domain.Confidence

private val pillarNames = listOf("Liquidità", "Emergenze", "Progetti", "Lungo termine")

@Composable
fun AccountReconciliationPage(data: Archive, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.height(48.dp).actionFeedback()) { Text("Indietro") }
        Text("Confronto conti e pilastri", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Il registro dei conti potrebbe essere incompleto. Anche quando le date coincidono, questo confronto riguarda solo i conti registrati e non conferma il valore complessivo del pilastro.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pillars = reconcileAccountValuations(data)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    pillars.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            pair.forEach { pillar ->
                                PillarReconciliationSection(pillar, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    pillars.forEach { pillar -> PillarReconciliationSection(pillar, Modifier.fillMaxWidth()) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PillarReconciliationSection(section: AccountPillarReconciliation, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pillarNames[section.pillar - 1], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Valutazione esplicita del pilastro", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val pillarValue = section.latestPillarValuation
            if (pillarValue == null) {
                Text("Nessuna valutazione esplicita registrata.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${euros(pillarValue.amount.cents)} · ${pillarValue.date}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("Fonte: ${pillarValue.source} · ${confidenceLabel(pillarValue.confidence)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (val comparison = section.comparison) {
                is AccountPillarComparison.Available -> {
                    HorizontalDivider()
                    Text("Confronto per data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Snapshot di tutti i conti registrati al ${comparison.date}.", style = MaterialTheme.typography.bodySmall)
                    Text("Somma dei conti registrati: ${euros(comparison.accountTotalCents)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Differenza rispetto al pilastro: ${euros(comparison.differenceCents)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Questo non verifica che il registro conti sia completo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is AccountPillarComparison.Unavailable -> {
                    HorizontalDivider()
                    Text("Confronto non disponibile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    when (comparison.reason) {
                        AccountPillarComparison.Reason.NO_REGISTERED_ACCOUNTS -> Text("Nessun conto registrato in questo pilastro; non è possibile calcolare una somma.", style = MaterialTheme.typography.bodySmall)
                        AccountPillarComparison.Reason.NO_PILLAR_VALUATION -> Text("Manca una valutazione esplicita del pilastro da confrontare.", style = MaterialTheme.typography.bodySmall)
                        AccountPillarComparison.Reason.MISSING_SAME_DATE_SNAPSHOT -> {
                            Text("Serve uno snapshot per ogni conto registrato nella stessa data della valutazione del pilastro.", style = MaterialTheme.typography.bodySmall)
                            comparison.accountsWithoutMatchingDate.forEach { snapshot -> MissingDateDetail(snapshot, pillarValue?.date?.toString().orEmpty()) }
                        }
                        AccountPillarComparison.Reason.TOTAL_OVERFLOW -> Text("La somma dei valori dei conti supera l'intervallo numerico disponibile.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Text("Ultimo valore per conto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (section.accounts.isEmpty()) {
                Text("Nessun conto registrato.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                section.accounts.forEachIndexed { index, snapshot ->
                    if (index > 0) HorizontalDivider()
                    AccountSnapshotDetail(snapshot)
                }
            }
        }
    }
}

@Composable
private fun MissingDateDetail(snapshot: AccountSnapshot, requestedDate: String) {
    val latest = snapshot.latest
    Text(
        if (latest == null) "${snapshot.account.name}: nessun valore registrato; richiesto $requestedDate."
        else "${snapshot.account.name}: ultimo valore ${latest.date}; richiesto $requestedDate.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun AccountSnapshotDetail(snapshot: AccountSnapshot) {
    val latest = snapshot.latest
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(snapshot.account.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (latest == null) {
            Text("Nessun valore registrato.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("${euros(latest.amount.cents)} · ${latest.date}", style = MaterialTheme.typography.bodyMedium)
            Text("Fonte: ${latest.source} · ${confidenceLabel(latest.confidence)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun confidenceLabel(confidence: Confidence): String = when (confidence) {
    Confidence.DOCUMENT -> "da documento"
    Confidence.USER_REPORTED -> "dichiarato"
    else -> "origine non classificata"
}
