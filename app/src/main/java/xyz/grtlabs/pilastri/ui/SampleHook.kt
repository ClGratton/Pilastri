package xyz.grtlabs.pilastri.ui

import java.time.LocalDate
import xyz.grtlabs.pilastri.data.Archive

/**
 * Debug builds install a synthetic "Dati di esempio" archive builder here (src/debug, SampleInstaller). Release builds
 * leave it null, so the empty state never offers sample data outside a debug build.
 */
object SampleHook {
    @Volatile var build: ((LocalDate) -> Archive)? = null
}
