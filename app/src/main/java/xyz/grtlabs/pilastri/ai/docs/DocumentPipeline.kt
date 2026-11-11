package xyz.grtlabs.pilastri.ai.docs

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** An attachment as the system handed it over: its bytes exist only in memory, only until the message is sent or the chip is removed. */
class DocumentInput(val name: String, val mime: String, val bytes: ByteArray) {
    val isPdf: Boolean get() = mime == "application/pdf" || (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF")
    val isImage: Boolean get() = !isPdf && mime.startsWith("image/")
}

/** Why a file could not be read, already in Italian and short (it goes to the chat as an info card). */
class DocumentReadException(message: String) : Exception(message)

/** On-device OCR and image preparation. The Android implementation uses ML Kit's bundled Latin model. */
interface ImageTools {
    /** Text of an encoded image (JPEG/PNG/HEIC). */
    suspend fun ocr(image: ByteArray): String

    /** A JPEG no larger than [maxSide] on its long edge, upright, without metadata (EXIF location included). */
    fun downscale(image: ByteArray, maxSide: Int): ByteArray
}

/** Renders PDF pages to JPEG bytes (the Android implementation uses [android.graphics.pdf.PdfRenderer]). */
fun interface PdfPageRenderer {
    fun render(pdf: ByteArray, maxPages: Int, maxSide: Int): List<ByteArray>
}

fun interface PdfTextReader {
    fun read(pdf: ByteArray, maxPages: Int): PdfText.Result
}

/** Text layer of a PDF through PdfBox-Android (Apache-2.0). */
object PdfText {
    class Result(val text: String, val pages: Int)

    /** Must run once before [extract] on a device (PdfBox reads its fonts and glyph lists from the app's assets). */
    fun init(context: android.content.Context) {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun extract(pdf: ByteArray, maxPages: Int): Result {
        val doc = try {
            PDDocument.load(pdf, "")
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw DocumentReadException("PDF protetto da password")
        } catch (e: java.io.IOException) {
            throw DocumentReadException("PDF non leggibile")
        }
        try {
            val pages = doc.numberOfPages
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setStartPage(1)
            stripper.setEndPage(minOf(pages, maxPages))
            return Result(stripper.getText(doc), pages)
        } finally {
            doc.close()
        }
    }
}

/** What the pipeline made of one attachment. */
class ProcessedDocument(
    /** "cedolino ottobre 2026", "CU 2025" or "documento": what the model and the chip call it (never the file name). */
    val label: String,
    val rawText: String,
    /** SHA-256 of the original bytes, hex. The agent passes it as `hash_documento`. */
    val sha256: String,
    /** The original, ready to send to a route that takes files: a downscaled JPEG or the PDF; null when too large or unavailable. */
    val sendMime: String?,
    val sendBytes: ByteArray?,
    val origin: Origin,
    val pages: Int,
) {
    enum class Origin { PDF_TEXT, PDF_OCR, IMAGE_OCR }
}

/**
 * Attachment -> text, on the device: a PDF's text layer, or OCR for a scanned PDF (pages rendered first) and for photos.
 * Nothing here talks to the network, and nothing is written to disk by this class.
 */
class DocumentPipeline(
    private val images: ImageTools,
    private val pages: PdfPageRenderer,
    private val pdfText: PdfTextReader = PdfTextReader { bytes, n -> PdfText.extract(bytes, n) },
) {
    suspend fun process(input: DocumentInput): ProcessedDocument = withContext(Dispatchers.Default) {
        val sha = sha256(input.bytes)
        when {
            input.isPdf -> {
                val layer = pdfText.read(input.bytes, MAX_PDF_PAGES)
                val perPage = layer.text.count { !it.isWhitespace() }.toDouble() / maxOf(1, minOf(layer.pages, MAX_PDF_PAGES))
                val (text, origin) = if (perPage >= MIN_CHARS_PER_PAGE) layer.text to ProcessedDocument.Origin.PDF_TEXT else {
                    // No usable text layer: a scan. Render the pages and read them.
                    val rendered = pages.render(input.bytes, MAX_OCR_PAGES, OCR_SIDE)
                    if (rendered.isEmpty()) throw DocumentReadException("PDF senza pagine leggibili")
                    buildString { rendered.forEachIndexed { i, page -> if (i > 0) append("\n\n"); append(images.ocr(page)) } } to ProcessedDocument.Origin.PDF_OCR
                }
                finish(text, sha, if (input.bytes.size <= MAX_SEND_BYTES) "application/pdf" else null, if (input.bytes.size <= MAX_SEND_BYTES) input.bytes else null, origin, layer.pages)
            }
            input.isImage -> {
                val text = images.ocr(input.bytes)
                val sent = try { images.downscale(input.bytes, SEND_SIDE) } catch (_: Exception) { null }
                finish(text, sha, sent?.let { "image/jpeg" }, sent, ProcessedDocument.Origin.IMAGE_OCR, 1)
            }
            else -> throw DocumentReadException("Formato non supportato (solo PDF e foto)")
        }
    }

    private fun finish(text: String, sha: String, mime: String?, bytes: ByteArray?, origin: ProcessedDocument.Origin, pageCount: Int): ProcessedDocument {
        val clean = text.replace('\u0000', ' ').lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.count { it.isLetterOrDigit() } < MIN_READABLE) throw DocumentReadException("Non riesco a leggere testo nel file")
        return ProcessedDocument(DocumentKinds.label(clean), clean, sha, mime, bytes, origin, pageCount)
    }

    companion object {
        const val MAX_PDF_PAGES = 30
        const val MAX_OCR_PAGES = 8
        const val MIN_CHARS_PER_PAGE = 80.0
        const val MIN_READABLE = 12
        const val OCR_SIDE = 2000
        const val SEND_SIDE = 1800
        const val MAX_SEND_BYTES = 8 * 1024 * 1024

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Names what a document is from its own text, deterministically: "cedolino ottobre 2026", "CU 2025". */
object DocumentKinds {
    private val months = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    private val monthYear = Regex("""(?i)\b(${months.joinToString("|")})\s+(20\d{2})\b""")
    private val numericMonth = Regex("""\b(0[1-9]|1[0-2])\s*[/\-]\s*(20\d{2})\b""")
    /** The income year ("anno 2025", "periodo d'imposta 2025") wins over the year printed after the title (the issue year). */
    private val cuIncomeYear = Regex("""(?i)(?:\banno|periodo\s+d'imposta|\bredditi)\D{0,12}(20\d{2})""")
    private val cuTitleYear = Regex("""(?i)certificazione\s+unica\D{0,12}(20\d{2})""")
    private val payslip = Regex("""(?i)\b(cedolino|busta\s+paga|prospetto\s+paga|foglio\s+paga|prospetto\s+di\s+paga|retribuzione\s+del\s+mese)\b""")
    private val cu = Regex("""(?i)\bcertificazione\s+unica\b|\bCU\s*20\d{2}\b""")

    fun label(text: String): String {
        val head = text.take(4000)
        if (cu.containsMatchIn(head)) {
            val year = cuIncomeYear.find(head)?.groupValues?.get(1) ?: cuTitleYear.find(head)?.groupValues?.get(1) ?: Regex("""\b(20\d{2})\b""").find(head)?.groupValues?.get(1)
            return if (year != null) "CU $year" else "CU"
        }
        if (payslip.containsMatchIn(head)) {
            val my = monthYear.find(head)
            if (my != null) return "cedolino ${my.groupValues[1].lowercase()} ${my.groupValues[2]}"
            val nm = numericMonth.find(head)
            if (nm != null) return "cedolino ${months[nm.groupValues[1].toInt() - 1]} ${nm.groupValues[2]}"
            return "cedolino"
        }
        return "documento"
    }
}
