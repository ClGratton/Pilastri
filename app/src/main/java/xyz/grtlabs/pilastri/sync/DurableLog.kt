package xyz.grtlabs.pilastri.sync

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * The durable copy of a debug log (user, 2026-11-10: "che se succede qualcosa li estrae subito qualcuno. Cmq sia durable").
 * Logcat is a short ring buffer; the same lines (same text, same content rules, nothing new) also go, with date and time to the
 * millisecond and the build's versionName, to a bounded file the app owns: `pilastri-log-0.txt` (current) and `pilastri-log-1.txt`
 * (the previous one). One sink for every flow's log (EbLog feeds it; any other debug log can call [DurableLog.append]).
 * Folder: the app-specific external files folder `Android/data/<package>/files/log` (readable with plain `adb pull` on a
 * non-debuggable build; other apps cannot read it on Android 11+ scoped storage; allowBackup is false so it is not in a backup).
 * It never enters the archive, the backups or the AI context. A write failure never breaks the flow.
 */

/** The file writer: append, flushed per line, bounded by rotation. Synchronous; [DurableLog] runs it off the main thread. */
internal class DurableLogFile(private val dir: File, private val maxBytes: Long, private val version: String, private val now: () -> Long = System::currentTimeMillis) {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private var out: FileOutputStream? = null
    private var size = 0L
    private fun current() = File(dir, "pilastri-log-0.txt")
    private fun previous() = File(dir, "pilastri-log-1.txt")

    @Synchronized fun write(tag: String, text: String) {
        try {
            val o = out ?: open()
            val bytes = "${fmt.format(Date(now()))} v$version $tag $text\n".toByteArray(Charsets.UTF_8)
            if (size > 0 && size + bytes.size > maxBytes) { rotate(); return write2(bytes) }
            o.write(bytes); o.flush(); size += bytes.size
        } catch (_: Throwable) { try { out?.close() } catch (_: Throwable) {}; out = null }
    }

    private fun write2(bytes: ByteArray) { val o = out ?: open(); o.write(bytes); o.flush(); size += bytes.size }

    private fun open(): FileOutputStream {
        dir.mkdirs()
        size = current().takeIf { it.exists() }?.length() ?: 0L
        return FileOutputStream(current(), true).also { out = it }   // append: a re-created logger never truncates
    }

    private fun rotate() {
        try { out?.close() } catch (_: Throwable) {}
        out = null
        previous().delete()
        current().renameTo(previous())
        size = 0
        open()
    }

    @Synchronized fun close() { try { out?.close() } catch (_: Throwable) {}; out = null }
}

object DurableLog {
    const val FOLDER = "log"
    const val MAX_BYTES = 512L * 1024
    @Volatile private var file: DurableLogFile? = null
    private val pool = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue(2000)) { r -> Thread(r, "durable-log").apply { isDaemon = true } }

    /** Call once at start (Application.onCreate); lines before it are logcat-only. */
    fun init(context: Context) {
        try {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val v = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }
            val dir = File(base, FOLDER)
            val first = !File(dir, "pilastri-log-0.txt").exists()
            file = DurableLogFile(dir, MAX_BYTES, v)
            append("Pilastri.App", startText(android.os.Process.myPid(), android.os.Build.VERSION.SDK_INT, first))   // a kill/restart (or a fresh install) is visible in the file
        } catch (_: Throwable) { file = null }
    }

    /** The process-start marker: ids and flags only (no content). */
    fun startText(pid: Int, sdk: Int, firstFile: Boolean) = "process_start pid=$pid sdk=$sdk first_file=$firstFile"

    /** One line, off the calling thread; a full queue or any failure drops the line, never throws. */
    fun append(tag: String, text: String) {
        val f = file ?: return
        try { pool.execute { f.write(tag, text) } } catch (_: RejectedExecutionException) {} catch (_: Throwable) {}
    }
}
