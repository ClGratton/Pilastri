package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import org.json.JSONObject

/*
 * The setup's place, kept across a process kill and an app update (`install -r` drops saved instance state).
 * One small file in the no-backup directory: the setup model as SetupCodec writes it (bank names, steps, choices; no address, id,
 * key or consent) and, when the Enable Banking sub-flow is open, EbFlowCodec's text. Written on every change, cleared when the
 * setup finishes; read synchronously as the initial value, so the first frame is already the restored page.
 * A value that cannot be read is dropped: the setup starts normally.
 */
class SetupProgressStore(private val dir: File) {
    private val file get() = File(dir, "setup-progress.json")

    @Synchronized private fun read(): JSONObject? = try {
        file.takeIf { it.isFile }?.readText()?.let(::JSONObject)?.takeIf { it.optInt("v") == 1 }
    } catch (_: Exception) { runCatching { file.delete() }; null }

    @Synchronized private fun write(o: JSONObject) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "setup-progress.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { /* nothing to resume is better than a crash */ }
    }

    /** The saved setup, or null when there is none or it cannot be read. */
    fun loadSetup(): SetupModel? = read()?.optString("setup")?.takeIf { it.isNotEmpty() }?.let { t ->
        try { JSONObject(t); SetupCodec.decode(t) } catch (_: Exception) { clear(); null }
    }

    fun loadEb(): String? = read()?.optString("eb")?.takeIf { it.isNotEmpty() }

    fun saveSetup(m: SetupModel) { write((read() ?: JSONObject().put("v", 1)).put("setup", SetupCodec.encode(m))) }

    /** [text] null removes the Enable Banking part. */
    fun saveEb(text: String?) {
        val o = read() ?: JSONObject().put("v", 1)
        if (text == null) o.remove("eb") else o.put("eb", text)
        write(o)
    }

    @Synchronized fun clear() { runCatching { file.delete() } }

    companion object {
        fun of(context: android.content.Context) = SetupProgressStore(context.noBackupFilesDir)
    }
}
