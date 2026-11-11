package xyz.grtlabs.pilastri.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The status line of one Agente run ("Claude sta rispondendo…"). It exists only while a run does: [track] ends it on an answer,
 * an error and a cancellation alike, and a stage reported after the end (a late callback of a provider page) is dropped, so the
 * line can never outlive the run (board 5a-06).
 */
class RunStatus {
    var running by mutableStateOf(false)
        private set
    var stage by mutableStateOf<String?>(null)
        private set

    /** What the run is doing now. Ignored when no run is active. */
    fun report(text: String) {
        if (running) stage = text
    }

    fun begin() {
        running = true
        stage = null
    }

    fun end() {
        running = false
        stage = null
    }

    /** Runs [block] as one run; whatever happens inside, the status is cleared afterwards. */
    suspend fun <T> track(block: suspend () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }
}
