package xyz.grtlabs.pilastri.sync

import xyz.grtlabs.pilastri.data.ImportBatch

/**
 * In-process handoff from a connector activity to MainActivity: the batch is large and not Parcelable, and it must
 * never be written to disk or logs. The connector stores it under a fresh token and returns only the token.
 */
object ImportHandoff {
    private val pending = HashMap<String, ImportBatch>()
    @Synchronized fun put(batch: ImportBatch): String = java.util.UUID.randomUUID().toString().also { pending[it] = batch }
    @Synchronized fun take(token: String?): ImportBatch? = token?.let { pending.remove(it) }
}

/** What a connector activity reports back. Errors are Italian, user-facing, and never contain credentials or cookies. */
sealed interface ConnectorResult {
    /** Movements (and, for PayPal, installment plans inside the batch) to preview and confirm. */
    data class Imported(val batch: ImportBatch) : ConnectorResult
    data object LoggedIn : ConnectorResult
    data object SessionExpired : ConnectorResult
    data class Failed(val message: String) : ConnectorResult
    data object Cancelled : ConnectorResult
}
