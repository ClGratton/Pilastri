package xyz.grtlabs.pilastri.sync.auto

import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.Connection
import xyz.grtlabs.pilastri.data.ImportProvider

/**
 * One-time repair of PayPal coverage claimed by the readers before 2026-11-01.
 *
 * Two over-claims are known:
 * 1. (2026-10-31) the reader accepted an unrecognized list, read 0 of about 32 rows and still claimed a year of coverage.
 * 2. (2026-11-01) the reader took any list it could read as the whole window, but two 90-day windows on the Fold showed exactly 30
 *    rows each: PayPal's page seems to show at most 30 rows, newest first, so a window with more movements was claimed back to its
 *    start while its oldest movements were never shown. The stored rows cannot tell which window was cut, so no PayPal claim made
 *    before the fix is trusted.
 *
 * The rule is narrow in what it touches: a PAYPAL connection's coverage dates are cleared, so the next sync reads the whole year
 * again (with the cut-window handling of [PaypalActivityReader]) and claims only what it showed. Nothing else changes: other
 * providers, movements, plans, the connection itself and its last result stay as they are. Idempotent ([repaired] of a repaired
 * archive is the same archive). Pure; the host applies it once ([AutoSyncCoordinator.repairDone]) and also clears the shown
 * freshness ([AutoSyncCoordinator.forgetFreshness]).
 */
object CoverageRepair {
    /**
     * v3 (2026-11-01): every PayPal coverage claim made before the cut-window fix is cleared once. (v2: the rule did not depend on the
     * last result text; v1 did, and may have been marked done unapplied.) A new key makes the host run the repair once more.
     */
    const val PAYPAL_EMPTY = "paypal-unbacked-coverage-v3"

    fun needed(archive: Archive): Boolean = archive.connections.any(::claims)

    /** [archive] without the PayPal coverage claims; the same instance when there is nothing to repair. */
    fun repaired(archive: Archive): Archive = if (!needed(archive)) archive else archive.copy(
        connections = archive.connections.map { if (claims(it)) it.copy(coverageFrom = null, coverageTo = null) else it },
    )

    /** A PAYPAL connection that claims any coverage. */
    private fun claims(c: Connection): Boolean =
        c.provider == ImportProvider.PAYPAL && (c.coverageFrom != null || c.coverageTo != null)
}
