package xyz.grtlabs.pilastri.sync.auto

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.data.ImportBatch

/** A known PayPal installment plan to read again: its id in the archive and the link of its plan page (path and query, as the row gave it). */
data class PlanRef(val planId: String, val link: String)

/**
 * An inclusive date range. [plans]: plans already in the archive whose plan page is read again after the activity windows (their
 * paid parts change over time); empty for providers without plan pages.
 */
data class SyncRange(val from: LocalDate, val to: LocalDate, val plans: List<PlanRef> = emptyList()) {
    init { require(!from.isAfter(to)) { "Periodo non valido." } }

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    /** Consecutive windows of at most [size] days, newest first, covering the whole range. */
    fun windows(size: Int): List<SyncRange> {
        require(size >= 1)
        val out = ArrayList<SyncRange>()
        var end = to
        while (!end.isBefore(from)) {
            val start = end.minusDays(size - 1L).let { if (it.isBefore(from)) from else it }
            out.add(SyncRange(start, end))
            end = start.minusDays(1)
        }
        return out
    }

    companion object {
        /**
         * What to ask for: from a few days before the last covered date (late-booked rows) to today, or [firstDays] back on the
         * first sync. Never in the future.
         */
        fun incremental(today: LocalDate, lastCovered: LocalDate?, firstDays: Int = 365, overlapDays: Int = 7): SyncRange {
            val from = lastCovered?.minusDays(overlapDays.toLong())?.let { if (it.isAfter(today)) today else it } ?: today.minusDays(firstDays - 1L)
            return SyncRange(from, today)
        }
    }
}

/** What one fetch attempt with the stored session gave. Messages are Italian, user-facing, and never carry cookies or credentials. */
sealed interface FetchOutcome {
    class Fetched(val batch: ImportBatch) : FetchOutcome
    data object SignedOut : FetchOutcome
    /** The answer needs a navigation that would break the page the person is on (a sign-in or 2FA step): ask again later. */
    data object NotReady : FetchOutcome
    /** The provider put up a bot check. Never solved by the app. */
    data object Challenge : FetchOutcome
    data class Failed(val message: String) : FetchOutcome
    data class NotConfigured(val message: String) : FetchOutcome
}

/**
 * The page of a WebView that holds the provider session (hidden behind a progress cover, or offscreen). Implemented by
 * [WebLoginDriver]; faked in tests.
 */
interface PageAccess {
    /** Loads [url] in the main frame and suspends until it has finished loading. The final URL, or null on failure or timeout. */
    suspend fun load(url: String): String?
    /** Runs [script] in the main frame and returns the string the script evaluates to, or null. Only on provider pages. */
    suspend fun evaluate(script: String): String?
    fun currentUrl(): String?
}

/** Reads a provider's data with a stored session, without user steps. */
interface SessionFetcher {
    val site: ProviderSite
    /** False until the request shape is known: [fetch] then answers [FetchOutcome.NotConfigured]. */
    val configured: Boolean
    /** True when [fetch] needs a [PageAccess] (a WebView), false when cookies alone are enough. */
    val needsPage: Boolean
    /** True when [fetch] navigates the page; such a fetch is only tried with mayNavigate = true. */
    val navigates: Boolean
    suspend fun fetch(range: SyncRange, page: PageAccess?, mayNavigate: Boolean): FetchOutcome
}

const val NOT_CONFIGURED = "formato non ancora configurato"

/** Several window batches of one provider as one: movements and plans by id (first wins), newest balance, joined coverage. */
internal fun mergeBatches(parts: List<ImportBatch>): ImportBatch {
    require(parts.isNotEmpty())
    if (parts.size == 1) return parts[0]
    val newestFirst = parts.sortedByDescending { it.coverageTo ?: LocalDate.MIN }
    val movements = LinkedHashMap<String, xyz.grtlabs.pilastri.data.ImportedMovement>()
    newestFirst.forEach { b -> b.movements.forEach { movements.putIfAbsent(it.externalId, it) } }
    val plans = LinkedHashMap<String, xyz.grtlabs.pilastri.data.InstallmentPlan>()
    newestFirst.forEach { b -> b.installmentPlans.forEach { plans.putIfAbsent(it.id, it) } }
    val first = newestFirst.first()
    return first.copy(
        coverageFrom = parts.mapNotNull { it.coverageFrom }.minOrNull(),
        coverageTo = parts.mapNotNull { it.coverageTo }.maxOrNull(),
        movements = movements.values.sortedByDescending { it.date },
        balance = newestFirst.firstNotNullOfOrNull { it.balance },
        cashBalance = newestFirst.firstNotNullOfOrNull { it.cashBalance },
        securitiesBalance = newestFirst.firstNotNullOfOrNull { it.securitiesBalance },
        warnings = parts.flatMap { it.warnings }.distinct(),
        installmentPlans = plans.values.toList(),
        installmentPayments = parts.flatMap { it.installmentPayments }.distinct(),
    )
}
