package xyz.grtlabs.pilastri.ui.setup

import android.content.Context
import org.json.JSONObject
import xyz.grtlabs.pilastri.sync.eb.EbAspsp

/**
 * The banks offered in setup before the Enable Banking application is registered: a dated, sourced data asset
 * (`assets/banks/banks-it-2026.json`), like the rule pack. Names and marks only; nothing person- or provider-specific lives in code.
 * The live Enable Banking list replaces it once registered ([SetupRules.matchChosen] maps the choices).
 */
data class CatalogBank(val id: String, val name: String, val initials: String)

data class BankCatalog(val version: String, val source: String, val banks: List<CatalogBank>) {
    /** The picker's rows. */
    fun aspsps(): List<EbAspsp> = banks.map { EbAspsp(it.name, "IT", null, listOf("personal"), emptyList(), emptyList(), false) }

    /** Bank name to its two-letter mark. */
    fun marks(): Map<String, String> = banks.associate { it.name to it.initials }

    companion object {
        const val ASSET = "banks/banks-it-2026.json"

        fun parse(json: String): BankCatalog {
            val o = JSONObject(json)
            val arr = o.getJSONArray("banks")
            val banks = (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                val name = b.getString("name")
                CatalogBank(b.getString("id"), name, b.optString("initials").ifBlank { name.filter { it.isLetterOrDigit() }.take(2) })
            }
            return BankCatalog(o.getString("version"), o.getString("source"), banks)
        }

        fun load(context: Context): BankCatalog =
            context.applicationContext.assets.open(ASSET).use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }
}
