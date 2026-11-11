package xyz.grtlabs.pilastri.domain

import java.math.BigDecimal
import java.time.LocalDate

/** User-maintained instrument reference; it does not assert ownership or a current holding. */
data class Instrument(val id: String, val name: String, val identifier: String?, val source: String) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(name.isNotBlank() && name.length <= 200)
        require(identifier == null || (identifier.isNotBlank() && identifier.length <= 100))
        require(source.isNotBlank() && source.length <= 500)
    }
}

enum class TradeSide { BUY, SELL }

/** A reported trade record, not a contribution, valuation, return, or balance. */
data class Trade(
    val id: String,
    val accountId: String,
    val instrumentId: String,
    val date: LocalDate,
    val side: TradeSide,
    val quantity: BigDecimal,
    val grossAmount: Money,
    val fees: Money,
    val source: String
) {
    init {
        require(id.isNotBlank() && id.length <= 100)
        require(accountId.isNotBlank() && accountId.length <= 100)
        require(instrumentId.isNotBlank() && instrumentId.length <= 100)
        require(quantity.signum() > 0 && quantity.scale() in 0..12 && quantity.precision() <= 24)
        require(grossAmount.cents > 0 && fees.cents >= 0)
        require(source.isNotBlank() && source.length <= 500)
    }
}
