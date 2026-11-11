package xyz.grtlabs.pilastri.ui

import android.os.SystemClock

/**
 * No-flash gate (CR-84). The first frame the person sees is the settled destination: the system splash stays up until the
 * archive is loaded and the start layer (setup or app) is the one on screen. MainActivity wires [keepSplash]; SetupFrame calls
 * [settle] after composing the decided layer. The timeout is a safety net for a stuck load, never the normal path.
 */
object StartGate {
    @Volatile var settled = false
        private set
    private val born = SystemClock.uptimeMillis()
    const val TIMEOUT_MS = 4000L

    fun settle() { settled = true }
    fun keepSplash(): Boolean = !settled && SystemClock.uptimeMillis() - born < TIMEOUT_MS
}
