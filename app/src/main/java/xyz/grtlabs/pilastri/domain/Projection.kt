package xyz.grtlabs.pilastri.domain

import java.math.BigDecimal
import java.math.RoundingMode

enum class ProjectionPriceReference { START_2026, END_2026_LEGACY }

data class ProjectionAssumptions(
    val startYear: Int,
    val endYear: Int,
    val initialWealthCents: Long,
    val annualPersonalAdditionCents: Long,
    val annualEmployerAdditionCents: Long,
    val annualNominalGrowthPercent: BigDecimal,
    val annualInflationPercent: BigDecimal,
    val priceReference: ProjectionPriceReference = ProjectionPriceReference.START_2026,
) {
    init {
        require(startYear in FIRST_PROJECTION_YEAR..LAST_PROJECTION_YEAR)
        require(endYear in startYear..LAST_PROJECTION_YEAR)
        require(initialWealthCents >= 0 && annualPersonalAdditionCents >= 0 && annualEmployerAdditionCents >= 0)
        require(annualNominalGrowthPercent.isSupportedScenarioRate())
        require(annualInflationPercent.isSupportedScenarioRate())
    }

    companion object {
        const val FIRST_PROJECTION_YEAR = 2026
        const val LAST_PROJECTION_YEAR = 2085
    }
}

data class ProjectionYear(
    val year: Int,
    val endingWealthNominalCents: Long,
    val endingWealth2026Cents: Long,
    val cumulativePersonalNominalCents: Long,
    val cumulativePersonal2026Cents: Long,
    val cumulativeEmployerNominalCents: Long,
    val cumulativeEmployer2026Cents: Long,
)

sealed interface ProjectionOutcome {
    data class Available(val years: List<ProjectionYear>) : ProjectionOutcome
    data object UnavailableOverflow : ProjectionOutcome
}

/**
 * Projects only the supplied scenario. Annual growth applies to the opening balance;
 * personal and employer additions are made at each modeled year-end and earn no return
 * in that year. New scenarios deflate to the beginning of 2026. Earlier end-2026
 * scenarios retain their explicitly stored reference and interpretation.
 */
fun calculateProjection(assumptions: ProjectionAssumptions): ProjectionOutcome = try {
    val growthFactor = BigDecimal.ONE.add(assumptions.annualNominalGrowthPercent.movePointLeft(2))
    val inflationFactor = BigDecimal.ONE.add(assumptions.annualInflationPercent.movePointLeft(2))
    var wealthCents = assumptions.initialWealthCents
    var personalNominal = 0L
    var personal2026 = 0L
    var employerNominal = 0L
    var employer2026 = 0L
    val years = ArrayList<ProjectionYear>(assumptions.endYear - assumptions.startYear + 1)

    for (year in assumptions.startYear..assumptions.endYear) {
        val elapsedYears = year - ProjectionAssumptions.FIRST_PROJECTION_YEAR +
            if (assumptions.priceReference == ProjectionPriceReference.START_2026) 1 else 0
        val deflator = inflationFactor.pow(elapsedYears)
        val grownWealth = BigDecimal.valueOf(wealthCents).multiply(growthFactor)
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        wealthCents = Math.addExact(
            Math.addExact(grownWealth, assumptions.annualPersonalAdditionCents),
            assumptions.annualEmployerAdditionCents,
        )
        personalNominal = Math.addExact(personalNominal, assumptions.annualPersonalAdditionCents)
        personal2026 = Math.addExact(personal2026, deflate(assumptions.annualPersonalAdditionCents, deflator))
        employerNominal = Math.addExact(employerNominal, assumptions.annualEmployerAdditionCents)
        employer2026 = Math.addExact(employer2026, deflate(assumptions.annualEmployerAdditionCents, deflator))
        years += ProjectionYear(
            year = year,
            endingWealthNominalCents = wealthCents,
            endingWealth2026Cents = deflate(wealthCents, deflator),
            cumulativePersonalNominalCents = personalNominal,
            cumulativePersonal2026Cents = personal2026,
            cumulativeEmployerNominalCents = employerNominal,
            cumulativeEmployer2026Cents = employer2026,
        )
    }
    ProjectionOutcome.Available(years)
} catch (_: ArithmeticException) {
    ProjectionOutcome.UnavailableOverflow
}

private fun deflate(cents: Long, deflator: BigDecimal): Long =
    BigDecimal.valueOf(cents).divide(deflator, 0, RoundingMode.HALF_UP).longValueExact()

private fun BigDecimal.isSupportedScenarioRate(): Boolean =
    this > BigDecimal(-100) && this <= BigDecimal(1_000) && scale() <= 6
