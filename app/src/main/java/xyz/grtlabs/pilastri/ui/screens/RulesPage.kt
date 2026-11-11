package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.runtime.Composable
import xyz.grtlabs.pilastri.ui.*

/**
 * "Fonti e regole" (4b-07): the history of the rules in force and every change to them, with sources. The same card sits
 * inline in Impostazioni; this page is where Previdenza opens it.
 */
@Composable
fun RulesPage(app: App) {
    val a = app.archive ?: return
    Page {
        BackHeader("Regole", "storico e fonti" + sampleSuffix(a), { app.back() })
        RulesHistoryCard(a)
    }
}
