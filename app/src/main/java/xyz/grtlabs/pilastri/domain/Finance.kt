package xyz.grtlabs.pilastri.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class Confidence { USER_REPORTED, DOCUMENT, CALCULATED, SCENARIO, UNKNOWN }
enum class EventType { PERSONAL_CONTRIBUTION, EMPLOYER_CONTRIBUTION, WITHDRAWAL, INCOME, FEE, TRANSFER, CORRECTION }

data class Money(val cents: Long, val currency: String = "EUR") {
    init { require(currency == "EUR") { "Only EUR is currently supported" } }
}

data class LedgerEvent(val id: String, val date: LocalDate, val pillar: Int, val type: EventType,
    val amount: Money, val confidence: Confidence, val source: String, val transferPair: String? = null,
    val accountId: String? = null) {
    init {
        require(id.isNotBlank() && source.isNotBlank() && pillar in 1..4 && amount.cents >= 0 && confidence != Confidence.UNKNOWN)
        require(type != EventType.TRANSFER || !transferPair.isNullOrBlank())
        require(accountId == null || accountId.isNotBlank())
        require(accountId == null || type in setOf(
            EventType.PERSONAL_CONTRIBUTION,
            EventType.EMPLOYER_CONTRIBUTION,
            EventType.WITHDRAWAL,
            EventType.INCOME,
            EventType.FEE
        ))
    }
}

data class Valuation(val date: LocalDate, val pillar: Int, val amount: Money, val source: String, val confidence: Confidence) {
    init { require(pillar in 1..4 && amount.cents >= 0 && source.isNotBlank() && confidence != Confidence.UNKNOWN) }
}
data class ValueChange(val from: LocalDate, val to: LocalDate, val cents: Long)

object Finance {
    /** A raw change in snapshots, not investment return; no interpolation or missing-date guessing. */
    fun valueChange(valuations: List<Valuation>, pillar: Int, period: String): ValueChange? {
        val records = valuations.filter { it.pillar == pillar }
        val end = records.maxByOrNull { it.date } ?: return null
        val startDate = when (period) { "Giorno" -> end.date.minusDays(1); "Mese" -> end.date.minusMonths(1); "Anno" -> end.date.minusYears(1); else -> return null }
        val start = records.firstOrNull { it.date == startDate } ?: return null
        return try { ValueChange(startDate, end.date, Math.subtractExact(end.amount.cents, start.amount.cents)) } catch (_: ArithmeticException) { null }
    }
    fun ownContributions(events: List<LedgerEvent>, pillar: Int): Money = Money(events.asSequence()
        .filter { it.pillar == pillar && it.type == EventType.PERSONAL_CONTRIBUTION }
        .map { it.amount.cents }.fold(0L, Math::addExact))

    fun employerContributions(events: List<LedgerEvent>, pillar: Int): Money = Money(events.asSequence()
        .filter { it.pillar == pillar && it.type == EventType.EMPLOYER_CONTRIBUTION }
        .map { it.amount.cents }.fold(0L, Math::addExact))

    /** Only reported or documented values may form a total; calculated and scenario values never do (Council 2026-10-28). */
    fun isActual(v: Valuation) = v.confidence == Confidence.USER_REPORTED || v.confidence == Confidence.DOCUMENT

    // A total is known only if each pillar has an explicitly sourced valuation.
    fun completeTotal(valuations: List<Valuation>): Money? {
        val latest = valuations.filter(::isActual).groupBy { it.pillar }.mapValues { (_, rows) -> rows.maxBy { it.date } }
        if ((1..4).any { it !in latest }) return null
        return Money(latest.values.map { it.amount.cents }.fold(0L, Math::addExact))
    }

    fun inBaseYear(amount: Money, baseIndex: BigDecimal?, datedIndex: BigDecimal?): Money? {
        if (baseIndex == null || datedIndex == null || baseIndex.signum() <= 0 || datedIndex.signum() <= 0) return null
        val adjusted = BigDecimal.valueOf(amount.cents).multiply(baseIndex).divide(datedIndex, 0, RoundingMode.HALF_EVEN)
        return try { Money(adjusted.longValueExact(), amount.currency) } catch (_: ArithmeticException) { null }
    }
}
