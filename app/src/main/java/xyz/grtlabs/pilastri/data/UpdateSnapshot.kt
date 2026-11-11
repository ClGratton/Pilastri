package xyz.grtlabs.pilastri.data

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the marker of the last snapshotted install/update lives (app-private prefs in production). */
interface SnapshotMarker {
    fun get(): String?
    fun set(value: String)
}

/**
 * One byte-for-byte copy of the archive files before the first write after each install or update, in
 * `filesDir/snapshots/<yyyyMMdd-HHmmss>-u<updateId>/`. The marker is written only after the copy is verified (size + SHA-256);
 * the newest [KEEP] folders are kept, older ones are deleted only after the new one is verified. A failure is reported through
 * [log] (no counts or values), never thrown, never writes the marker, and is retried by the next [ensure] in a later process.
 * [ensure] is synchronized: the first archive write in a process waits for the attempt (success or failure) to finish.
 * The folder lives inside filesDir but only the listed archive files are ever copied or exported; nothing enumerates filesDir.
 */
class UpdateSnapshot(
    private val dir: File,
    private val files: List<File>,
    private val updateId: String,
    private val marker: SnapshotMarker,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var attempted = false

    @Synchronized
    fun ensure() {
        if (attempted) return
        attempted = true
        try {
            if (marker.get() == updateId) return
            val existing = files.filter { it.isFile }
            if (existing.isEmpty()) { marker.set(updateId); return } // fresh install: nothing to protect
            val root = File(dir, DIR_NAME).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now()))
            val target = File(root, "$stamp-u$updateId")
            val tmp = File(root, "${target.name}.tmp")
            tmp.deleteRecursively()
            check(tmp.mkdirs()) { "cartella non creata" }
            try {
                existing.forEach { src ->
                    val copy = File(tmp, src.name)
                    src.inputStream().use { i -> copy.outputStream().use { o -> i.copyTo(o); o.fd.sync() } }
                    check(copy.length() == src.length() && sha(copy).contentEquals(sha(src))) { "copia non verificata" }
                }
                target.deleteRecursively()
                check(tmp.renameTo(target)) { "cartella non rinominata" }
            } catch (t: Throwable) { tmp.deleteRecursively(); throw t }
            marker.set(updateId)
            prune(root)
        } catch (t: Throwable) {
            log("snapshot dell'archivio non riuscito (${t.javaClass.simpleName}); nuovo tentativo al prossimo avvio")
        }
    }

    private fun prune(root: File) {
        root.listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") }.orEmpty()
            .sortedByDescending { it.name }.drop(KEEP).forEach { it.deleteRecursively() }
    }

    private fun sha(f: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    companion object {
        const val DIR_NAME = "snapshots"
        const val KEEP = 5
    }
}
