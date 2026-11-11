package xyz.grtlabs.pilastri.domain

import java.time.LocalDate

/** A manually sourced value for one registered account, kept separate from pillar totals. */
data class AccountValuation(
    val accountId: String,
    val date: LocalDate,
    val amount: Money,
    val source: String,
    val confidence: Confidence
) {
    init {
        require(accountId.isNotBlank() && amount.cents >= 0)
        require(source.isNotBlank() && (confidence == Confidence.USER_REPORTED || confidence == Confidence.DOCUMENT))
    }
}
