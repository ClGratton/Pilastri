package xyz.grtlabs.pilastri.domain

import java.time.LocalDate

/** A sourced movement between two existing accounts; it is not a contribution or income. */
data class AccountTransfer(
    val id: String,
    val date: LocalDate,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: Money,
    val source: String
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(fromAccountId.isNotBlank() && fromAccountId.length <= 100)
        require(toAccountId.isNotBlank() && toAccountId.length <= 100)
        require(fromAccountId != toAccountId) { "I conti di origine e destinazione devono essere diversi." }
        require(amount.cents > 0) { "L'importo del trasferimento deve essere positivo." }
        require(source.isNotBlank() && source.length <= 500)
    }
}
