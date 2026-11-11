package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.domain.*

/** Exact trailing calendar window ending at the latest actual pillar snapshot. */
enum class PerformancePeriod { DAY, MONTH, YEAR }

data class PerformanceDateWindow(val start: LocalDate, val end: LocalDate) {
    init { require(start.isBefore(end)) }
}

enum class PillarPerformanceBlocker {
    INVALID_PILLAR, NO_END_SNAPSHOT, MISSING_START_SNAPSHOT, INVALID_WINDOW,
    NON_ACTUAL_EVENT,
    NON_ACTUAL_SNAPSHOT, UNSUPPORTED_INCOME_ROLE, UNKNOWN_TRANSFER_ACCOUNT,
    ENDPOINT_FLOW, MULTIPLE_FLOWS_ON_DATE, MISSING_FLOW_SNAPSHOT,
    MISSING_ATTESTATION, INCOMPLETE_ATTESTATION, STALE_ATTESTATION,
    INVALID_INPUT, CALCULATION_UNAVAILABLE
}

data class PillarPerformancePreflight(
    val window: PerformanceDateWindow?,
    val start: Valuation?,
    val end: Valuation?,
    val flowBoundaries: List<ExternalFlowBoundary>?,
    val fingerprint: String?,
    val blocker: PillarPerformanceBlocker?,
    val missingFlowDates: List<LocalDate> = emptyList(),
    val flowConflictDates: List<LocalDate> = emptyList()
)

sealed interface PillarPerformanceResult {
    data class Available(
        val window: PerformanceDateWindow,
        val source: String,
        val nominal: TimeWeightedReturnResult.Available,
        /** Null means CPI is unavailable or not comparable; nominal remains valid. */
        val realPercent: BigDecimal?,
        val realUnavailableReason: RealPerformanceUnavailableReason?
    ) : PillarPerformanceResult

    data class Unavailable(
        val blocker: PillarPerformanceBlocker,
        val window: PerformanceDateWindow? = null,
        val missingFlowDates: List<LocalDate> = emptyList(),
        val flowConflictDates: List<LocalDate> = emptyList(),
        val calculationReason: PerformanceUnavailableReason? = null
    ) : PillarPerformanceResult
}

enum class RealPerformanceUnavailableReason { DAILY_WINDOW, MISSING_INDEX, INCOMPATIBLE_INDEX_SERIES, NUMERIC_LIMIT }

/**
 * Pick the latest actual snapshot and require an exact snapshot at the matching calendar offset.
 * No date interpolation or nearest-snapshot fallback is performed.
 */
fun performanceWindow(data: Archive, pillar: Int, period: PerformancePeriod): PerformanceDateWindow? {
    if (pillar !in 1..4) return null
    val end = data.valuations.filter { it.pillar == pillar && it.confidence.isActual() }
        .maxByOrNull { it.date } ?: return null
    val startDate = try {
        when (period) {
            PerformancePeriod.DAY -> end.date.minusDays(1)
            PerformancePeriod.MONTH -> end.date.minusMonths(1)
            PerformancePeriod.YEAR -> end.date.minusYears(1)
        }
    } catch (_: RuntimeException) { return null }
    if (startDate == end.date || data.valuations.none { it.pillar == pillar && it.date == startDate && it.confidence.isActual() }) return null
    return PerformanceDateWindow(startDate, end.date)
}

/** Preflight is deliberately usable before a completeness attestation has been saved. */
fun preparePillarPerformance(data: Archive, pillar: Int, period: PerformancePeriod, source: String = ""): PillarPerformancePreflight {
    if (pillar !in 1..4) return blocked(PillarPerformanceBlocker.INVALID_PILLAR)
    val window = performanceWindow(data, pillar, period) ?: return blocked(
        if (data.valuations.none { it.pillar == pillar && it.confidence.isActual() }) PillarPerformanceBlocker.NO_END_SNAPSHOT
        else PillarPerformanceBlocker.MISSING_START_SNAPSHOT
    )
    val start = data.valuations.singleOrNull { it.pillar == pillar && it.date == window.start }
        ?: return blocked(PillarPerformanceBlocker.MISSING_START_SNAPSHOT, window)
    val end = data.valuations.singleOrNull { it.pillar == pillar && it.date == window.end }
        ?: return blocked(PillarPerformanceBlocker.NO_END_SNAPSHOT, window)
    if (!start.confidence.isActual() || !end.confidence.isActual()) return blocked(PillarPerformanceBlocker.NON_ACTUAL_SNAPSHOT, window)

    val accounts = data.accounts.associateBy { it.id }
    val relevantTransfers = data.transfers.filter { transfer ->
        val from = accounts[transfer.fromAccountId]
        val to = accounts[transfer.toAccountId]
        from?.pillar == pillar || to?.pillar == pillar
    }.filter { !it.date.isBefore(window.start) && !it.date.isAfter(window.end) }
    if (relevantTransfers.any { accounts[it.fromAccountId] == null || accounts[it.toAccountId] == null }) {
        return blocked(PillarPerformanceBlocker.UNKNOWN_TRANSFER_ACCOUNT, window, start, end)
    }

    val scopedEvents = data.events.filter { it.pillar == pillar && it.date >= window.start && it.date <= window.end }
    if (scopedEvents.any { it.type == EventType.INCOME }) {
        return blocked(PillarPerformanceBlocker.UNSUPPORTED_INCOME_ROLE, window, start, end)
    }
    if (scopedEvents.any { !it.confidence.isActual() }) {
        return blocked(PillarPerformanceBlocker.NON_ACTUAL_EVENT, window, start, end)
    }

    val flows = mutableListOf<Pair<LocalDate, Long>>()
    scopedEvents.forEach { event ->
        if (!event.confidence.isActual()) return@forEach
        when (event.type) {
            EventType.PERSONAL_CONTRIBUTION, EventType.EMPLOYER_CONTRIBUTION -> flows += event.date to event.amount.cents
            EventType.WITHDRAWAL -> {
                val signed = try { Math.negateExact(event.amount.cents) }
                    catch (_: ArithmeticException) { return blocked(PillarPerformanceBlocker.INVALID_INPUT, window, start, end) }
                flows += event.date to signed
            }
            EventType.FEE, EventType.INCOME -> Unit
            else -> return blocked(PillarPerformanceBlocker.INVALID_INPUT, window, start, end)
        }
    }
    relevantTransfers.forEach { transfer ->
        if (accounts.getValue(transfer.fromAccountId).pillar == accounts.getValue(transfer.toAccountId).pillar) return@forEach
        val amount = transfer.amount.cents
        if (accounts.getValue(transfer.fromAccountId).pillar == pillar) {
            val signed = try { Math.negateExact(amount) } catch (_: ArithmeticException) {
                return blocked(PillarPerformanceBlocker.INVALID_INPUT, window, start, end)
            }
            flows += transfer.date to signed
        } else flows += transfer.date to amount
    }

    if (flows.any { it.first == window.start || it.first == window.end }) {
        return blocked(PillarPerformanceBlocker.ENDPOINT_FLOW, window, start, end)
    }
    val byDate = flows.groupBy({ it.first }, { it.second })
    val ambiguousDates = byDate.filterValues { it.size > 1 }.keys.sorted()
    if (ambiguousDates.isNotEmpty()) {
        return PillarPerformancePreflight(window, start, end, null,
            performanceInputFingerprint(data, pillar, window.start, window.end, source),
            PillarPerformanceBlocker.MULTIPLE_FLOWS_ON_DATE, flowConflictDates = ambiguousDates)
    }

    val boundaries = mutableListOf<ExternalFlowBoundary>()
    val missing = mutableListOf<LocalDate>()
    byDate.toSortedMap().forEach { (date, amounts) ->
        val amount = amounts.single()
        if (amount == 0L) return@forEach
        val valuation = data.valuations.singleOrNull { it.pillar == pillar && it.date == date && it.confidence.isActual() }
        if (valuation == null) missing += date else boundaries += ExternalFlowBoundary(date, valuation, amount)
    }
    if (missing.isNotEmpty()) return PillarPerformancePreflight(window, start, end, null,
        performanceInputFingerprint(data, pillar, window.start, window.end, source), PillarPerformanceBlocker.MISSING_FLOW_SNAPSHOT, missing)

    val fingerprint = performanceInputFingerprint(data, pillar, window.start, window.end, source)
    return PillarPerformancePreflight(window, start, end, boundaries, fingerprint, null)
}

/**
 * Evaluate only after a current user attestation matches the full input fingerprint.
 * `attestationOverride` supports a confirmation preview before the record is durably saved.
 */
fun evaluatePillarPerformance(
    data: Archive,
    pillar: Int,
    period: PerformancePeriod,
    attestationOverride: PerformanceAttestation? = null
): PillarPerformanceResult {
    val attestationForSource = attestationOverride ?: data.performanceAttestations
        .singleOrNull { it.pillar == pillar && performanceWindow(data, pillar, period)?.let { w -> it.start == w.start && it.end == w.end } == true }
    val preflight = preparePillarPerformance(data, pillar, period, attestationForSource?.source.orEmpty())
    val window = preflight.window
    if (preflight.blocker != null) return PillarPerformanceResult.Unavailable(
        preflight.blocker, window, preflight.missingFlowDates, preflight.flowConflictDates)
    val exactWindow = window ?: return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.INVALID_WINDOW)
    val source = attestationOverride?.source ?: data.performanceAttestations
        .singleOrNull { it.pillar == pillar && it.start == exactWindow.start && it.end == exactWindow.end }?.source
        ?: return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.MISSING_ATTESTATION, exactWindow)
    val attestation = attestationOverride ?: data.performanceAttestations.singleOrNull {
        it.pillar == pillar && it.start == exactWindow.start && it.end == exactWindow.end
    } ?: return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.MISSING_ATTESTATION, exactWindow)
    if (!attestation.externalFlowsComplete || !attestation.valuationRolesConfirmed) {
        return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.INCOMPLETE_ATTESTATION, exactWindow)
    }
    if (attestation.pillar != pillar || attestation.start != exactWindow.start || attestation.end != exactWindow.end) {
        return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.STALE_ATTESTATION, exactWindow)
    }
    val expectedFingerprint = performanceInputFingerprint(data, pillar, exactWindow.start, exactWindow.end, attestation.source)
    if (attestation.inputFingerprint != expectedFingerprint) {
        return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.STALE_ATTESTATION, exactWindow)
    }

    val start = preflight.start ?: return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.MISSING_START_SNAPSHOT, exactWindow)
    val end = preflight.end ?: return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.NO_END_SNAPSHOT, exactWindow)
    val result = timeWeightedReturn(start, preflight.flowBoundaries, end, externalFlowsComplete = true)
    if (result !is TimeWeightedReturnResult.Available) {
        return PillarPerformanceResult.Unavailable(PillarPerformanceBlocker.CALCULATION_UNAVAILABLE, exactWindow,
            calculationReason = (result as? TimeWeightedReturnResult.Unavailable)?.reason)
    }
    val (realPercent, realReason) = realPercent(data, exactWindow, period, result.factor)
    return PillarPerformanceResult.Available(exactWindow, source, result, realPercent, realReason)
}

/** SHA-256 over a canonical, versioned description of all scoped inputs. */
fun performanceInputFingerprint(
    data: Archive,
    pillar: Int,
    start: LocalDate,
    end: LocalDate,
    source: String
): String {
    require(pillar in 1..4 && start.isBefore(end))
    val out = StringBuilder("pillar-performance-input-v1\n")
    fun field(value: Any?) {
        val text = value?.toString() ?: "<null>"
        out.append(text.length).append(':').append(text).append('\n')
    }
    listOf(pillar, start, end, source, "endpoint-after-start-flow;end-flow-excluded", "pre-flow-interior-snapshot").forEach(::field)
    data.events.asSequence().filter { it.pillar == pillar && it.date >= start && it.date <= end }
        .sortedWith(compareBy<LedgerEvent>({ it.date }, { it.id }))
        .forEach { e -> listOf("event", e.id, e.date, e.pillar, e.type, e.amount.currency, e.amount.cents,
            e.confidence, e.source, e.accountId, e.transferPair).forEach(::field) }
    data.valuations.asSequence().filter { it.pillar == pillar && it.date >= start && it.date <= end }
        .sortedBy { it.date }
        .forEach { v -> listOf("valuation", v.date, v.pillar, v.amount.currency, v.amount.cents, v.source, v.confidence).forEach(::field) }
    val accountPillars = data.accounts.associate { it.id to it.pillar }
    data.transfers.asSequence().filter { transfer ->
        transfer.date >= start && transfer.date <= end &&
            (accountPillars[transfer.fromAccountId] == pillar || accountPillars[transfer.toAccountId] == pillar ||
                transfer.fromAccountId !in accountPillars || transfer.toAccountId !in accountPillars)
    }.sortedWith(compareBy<AccountTransfer>({ it.date }, { it.id }))
        .forEach { t -> listOf("transfer", t.id, t.date, t.fromAccountId, accountPillars[t.fromAccountId],
            t.toAccountId, accountPillars[t.toAccountId], t.amount.currency, t.amount.cents, t.source).forEach(::field) }
    val digest = MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun realPercent(
    data: Archive,
    window: PerformanceDateWindow,
    period: PerformancePeriod,
    nominalFactor: BigDecimal
): Pair<BigDecimal?, RealPerformanceUnavailableReason?> {
    if (period == PerformancePeriod.DAY) return null to RealPerformanceUnavailableReason.DAILY_WINDOW
    val startIndex = data.indices.singleOrNull { it.month == YearMonth.from(window.start) }
        ?: return null to RealPerformanceUnavailableReason.MISSING_INDEX
    val endIndex = data.indices.singleOrNull { it.month == YearMonth.from(window.end) }
        ?: return null to RealPerformanceUnavailableReason.MISSING_INDEX
    if (startIndex.source != endIndex.source) return null to RealPerformanceUnavailableReason.INCOMPATIBLE_INDEX_SERIES
    return try {
        val factor = nominalFactor.multiply(startIndex.value).divide(endIndex.value, 18, RoundingMode.HALF_EVEN)
        factor.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(8, RoundingMode.HALF_EVEN) to null
    } catch (_: ArithmeticException) { null to RealPerformanceUnavailableReason.NUMERIC_LIMIT }
}

private fun Confidence.isActual() = this == Confidence.USER_REPORTED || this == Confidence.DOCUMENT

private fun blocked(
    blocker: PillarPerformanceBlocker,
    window: PerformanceDateWindow? = null,
    start: Valuation? = null,
    end: Valuation? = null
) = PillarPerformancePreflight(window, start, end, null, null, blocker)
