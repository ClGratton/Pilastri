package xyz.grtlabs.pilastri.data

import java.time.Instant
import java.time.LocalDate

/** User-reviewed evidence that a dated performance window has complete flows and valuation roles. */
data class PerformanceAttestation(
    val id: String,
    val pillar: Int,
    val start: LocalDate,
    val end: LocalDate,
    val source: String,
    val reviewedAt: String,
    val inputFingerprint: String,
    val externalFlowsComplete: Boolean,
    val valuationRolesConfirmed: Boolean,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH)
        require(pillar in 1..4)
        require(start.isBefore(end) && !end.isAfter(LocalDate.now()))
        require(source.isNotBlank() && source.length <= MAX_SOURCE_LENGTH)
        require(reviewedAt.length <= MAX_TIMESTAMP_LENGTH)
        require(runCatching { Instant.parse(reviewedAt) }.isSuccess) { "Data della verifica non valida." }
        require(inputFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(externalFlowsComplete && valuationRolesConfirmed) {
            "La finestra non può essere confermata senza entrambi i controlli richiesti."
        }
    }

    companion object {
        const val MAX_ID_LENGTH = 100
        const val MAX_SOURCE_LENGTH = 500
        const val MAX_TIMESTAMP_LENGTH = 50
        const val MAX_COUNT = 1_000
    }
}
