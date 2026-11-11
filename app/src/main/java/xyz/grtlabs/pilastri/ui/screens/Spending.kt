package xyz.grtlabs.pilastri.ui.screens

import xyz.grtlabs.pilastri.data.CategorySource

/* Spese lives in Spese.kt (boards 3a/3b/7c, Council 5). The old calendar-month Spese, MonthScreen and its Flusso card were removed (3a-10). */

/** Bank descriptions are shouted and long: "NETFLIX.COM PAGAMENTO POS" becomes "Netflix.com pagamento…", cut at a word. */
fun pretty(text: String, max: Int = 26): String {
    val t = text.trim().lowercase().replaceFirstChar { it.titlecase() }
    if (t.length <= max) return t
    val cut = t.take(max).substringBeforeLast(' ').ifBlank { t.take(max) }
    return "$cut…"
}

fun sourceLabel(s: CategorySource) = when (s) { CategorySource.OVERRIDE -> "scelta tua"; CategorySource.RULE -> "regola"; CategorySource.PROVIDER -> "dal fornitore"; CategorySource.AGENT -> "suggerita" }
