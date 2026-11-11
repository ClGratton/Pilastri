package xyz.grtlabs.pilastri.data

import java.time.Instant
import xyz.grtlabs.pilastri.domain.ProjectionAssumptions

/** A user-saved hypothetical model; it is never treated as an actual account value. */
data class SavedProjection(
    val id: String,
    val name: String,
    val assumptions: ProjectionAssumptions,
    val source: String,
    val savedAt: String,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH)
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        require(source.isNotBlank() && source.length <= MAX_SOURCE_LENGTH)
        require(savedAt.length <= MAX_TIMESTAMP_LENGTH)
        Instant.parse(savedAt)
    }

    companion object {
        const val MAX_ID_LENGTH = 100
        const val MAX_NAME_LENGTH = 200
        const val MAX_SOURCE_LENGTH = 500
        const val MAX_TIMESTAMP_LENGTH = 50
        const val MAX_COUNT = 1_000
    }
}
