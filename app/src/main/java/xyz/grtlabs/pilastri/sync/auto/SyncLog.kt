package xyz.grtlabs.pilastri.sync.auto

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Debug builds only: one logcat line per sync step under the tag [TAG], with counts and states only. Never names, amounts,
 * dates of movements, URLs with query values, cookies or credentials. Off in release builds and in unit tests.
 */
object SyncLog {
    const val TAG = "PilastriSync"
    @Volatile var enabled: Boolean = false
        private set

    /** Turns the log on when the app is debuggable (a debug build). */
    fun init(context: Context) { enabled = isDebuggable(context) || xyz.grtlabs.pilastri.BuildConfig.WEBVIEW_DEVTOOLS }

    fun isDebuggable(context: Context): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun d(line: String) { if (enabled) Log.d(TAG, line) }

    /** Setup-flow lines (tags `Pilastri.EB`, `Pilastri.Mail`): on in every build, the `fast` one included. Shapes only: never a value. */
    fun always(tag: String, line: String) { try { Log.i(tag, line) } catch (_: Throwable) { /* JVM tests */ } }
}
