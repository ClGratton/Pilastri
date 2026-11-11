package xyz.grtlabs.pilastri.sync.paypal

/**
 * SYNTHETIC FIXTURES ONLY. No real PayPal export was available. The English header follows PayPal's published Activity Download
 * column names; the Italian header spellings are a best guess (UNVERIFIED) and the rows are invented: names, amounts, codes and
 * dates describe no real person, merchant or account.
 */
internal object PaypalFixtures {
    const val EN_HEADER = "\"Date\",\"Time\",\"TimeZone\",\"Name\",\"Type\",\"Status\",\"Currency\",\"Gross\",\"Fee\",\"Net\",\"Transaction ID\"," +
        "\"Reference Txn ID\",\"Balance\",\"Balance Impact\",\"Transaction Event Code\",\"Number of Installments\""
    const val IT_HEADER = "\"Data\",\"Ora\",\"Fuso orario\",\"Nome\",\"Tipo\",\"Stato\",\"Valuta\",\"Lordo\",\"Tariffa\",\"Netto\",\"Codice transazione\"," +
        "\"Codice transazione di riferimento\",\"Saldo\",\"Impatto sul saldo\",\"Codice evento transazione\",\"Numero di rate\""

    /** A 17-character synthetic transaction code. */
    fun id(n: Int) = "SYNTH%012d".format(n)

    class Tx(
        val date: String,
        val type: String,
        val gross: String,
        val id: String,
        val name: String = "",
        val status: String = "Completed",
        val fee: String = "0.00",
        val net: String = gross,
        val ref: String = "",
        val balance: String = "",
        val impact: String = "",
        val currency: String = "EUR",
        val time: String = "10:00:00",
        val code: String = "",
        val installments: String = "",
    ) {
        fun line() = listOf(date, time, "CET", name, type, status, currency, gross, fee, net, id, ref, balance, impact, code, installments)
            .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    fun csv(header: String, vararg rows: Tx, eol: String = "\r\n"): ByteArray = (listOf(header) + rows.map { it.line() }).joinToString(eol, postfix = eol).toByteArray(Charsets.UTF_8)

    fun en(vararg rows: Tx) = csv(EN_HEADER, *rows)
    fun it(vararg rows: Tx) = csv(IT_HEADER, *rows)
}
