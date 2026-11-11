package xyz.grtlabs.pilastri.ai.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One chip above the composer: reading, ready, or removed. A failed file never stays: the tray reports it and drops it. */
class AttachmentItem(val id: Long, val displayName: String) {
    var ready by mutableStateOf(false)
    /** What the chip says: the file name while reading, then "cedolino ottobre 2026" / "CU 2025" once recognised. */
    var label by mutableStateOf(displayName)
    /** The chip is leaving (exit animation); the item is removed from the tray when it ends. */
    var leaving by mutableStateOf(false)
    internal var doc: ProcessedDocument? = null
    internal var job: Job? = null
}

/**
 * The attachments waiting in the composer. Reading happens at once, on the device, in [scope]; the originals' bytes stay in
 * memory only until the message is sent ([clear]) or the chip is removed, and are never written anywhere (a camera shot's
 * temporary file is deleted as soon as it was read).
 */
class AttachmentTray(
    private val scope: CoroutineScope,
    private val pipeline: (Context) -> DocumentPipeline,
    /** A file that could not be read: its name and the short Italian reason (shown in the chat). */
    private val onFailed: (name: String, why: String) -> Unit,
) {
    val items = mutableStateListOf<AttachmentItem>()
    private var nextId = 0L

    val visible: List<AttachmentItem> get() = items.filter { !it.leaving }
    val busy: Boolean get() = visible.any { !it.ready }
    val hasAny: Boolean get() = visible.isNotEmpty()

    /** The documents of the chips that finished reading, in order. */
    fun documents(): List<ProcessedDocument> = visible.mapNotNull { if (it.ready) it.doc else null }

    fun addUri(context: Context, uri: Uri, mimeHint: String? = null, deleteAfter: File? = null) {
        if (visible.size >= MAX_ITEMS) { onFailed("Allegato", "Al massimo $MAX_ITEMS allegati"); deleteAfter?.delete(); return }
        val app = context.applicationContext
        val item = AttachmentItem(nextId++, "Allegato")
        items.add(item)
        item.job = scope.launch {
            try {
                val input = withContext(Dispatchers.IO) { read(app, uri, mimeHint) }
                item.label = input.name
                val doc = pipeline(app).process(input)
                item.doc = doc
                item.label = doc.label.takeIf { it != "documento" } ?: input.name
                item.ready = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentReadException) {
                fail(item, e.message ?: "File non leggibile")
            } catch (e: Throwable) {
                fail(item, "File non leggibile")
            } finally {
                deleteAfter?.delete()
            }
        }
    }

    private fun fail(item: AttachmentItem, why: String) {
        onFailed(item.label, why)
        items.remove(item)
    }

    /** Starts the chip's exit; [finishRemoval] runs when its animation ends. */
    fun remove(item: AttachmentItem) {
        item.job?.cancel()
        item.doc = null
        item.leaving = true
    }

    fun finishRemoval(item: AttachmentItem) { items.remove(item) }

    /** After a send: every original is dropped. */
    fun clear() {
        items.forEach { it.job?.cancel(); it.doc = null }
        items.clear()
    }

    private fun read(context: Context, uri: Uri, mimeHint: String?): DocumentInput {
        val resolver = context.contentResolver
        var name = "Allegato"
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (_: Exception) { }
        val mime = (resolver.getType(uri) ?: mimeHint ?: "application/octet-stream").lowercase()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw DocumentReadException("File troppo grande (massimo 25 MB)")
            }
            out.toByteArray()
        } ?: throw DocumentReadException("File non accessibile")
        if (bytes.isEmpty()) throw DocumentReadException("File vuoto")
        return DocumentInput(name, mime, bytes)
    }

    companion object {
        const val MAX_ITEMS = 8
        const val MAX_BYTES = 25 * 1024 * 1024
    }
}
