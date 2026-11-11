package xyz.grtlabs.pilastri.ai.docs

import android.content.res.AssetManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Synthetic helpers only. PdfBox-Android reads its fonts and glyph lists through an Android AssetManager; on the JVM a
 * mock serves them from the library's own assets directory, found next to the jar on the test classpath.
 */
internal object DocumentTestSupport {
    fun initPdfBox() {
        val field = PDFBoxResourceLoader::class.java.getDeclaredField("ASSET_MANAGER").apply { isAccessible = true }
        if (field.get(null) != null) return
        val aar = ZipFile(findAar())
        val manager = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(manager.open(Mockito.anyString())).thenAnswer { call ->
            val entry = aar.getEntry("assets/" + call.getArgument<String>(0)) ?: throw IOException("missing asset ${call.getArgument<String>(0)}")
            ByteArrayInputStream(aar.getInputStream(entry).use { it.readBytes() })
        }
        field.set(null, manager)
    }

    /** The library's AAR in the Gradle cache (its assets are not unpacked next to the runtime jar on the test classpath). */
    private fun findAar(): File {
        val home = System.getenv("GRADLE_USER_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".gradle")
        return File(home, "caches/modules-2/files-2.1/com.tom-roush/pdfbox-android").walkTopDown().firstOrNull { it.name.endsWith(".aar") }
            ?: error("pdfbox-android AAR not found under ${home.path}")
    }

    /** A one-page PDF with the given lines of text in Helvetica (the PDF is built here, byte by byte). */
    fun tinyPdf(lines: List<String>): ByteArray {
        val content = StringBuilder("BT /F1 12 Tf 14 TL 50 750 Td ")
        lines.forEach { line -> content.append('(').append(line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj T* ") }
        content.append("ET")
        return pdf(content.toString())
    }

    /** A valid one-page PDF without any text (what a scan looks like to a text extractor). */
    fun emptyPagePdf(): ByteArray = pdf("0.9 g 50 50 400 600 re f")

    private fun pdf(content: String): ByteArray {
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        )
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        bodies.forEachIndexed { i, body -> offsets += out.size(); write("${i + 1} 0 obj\n$body\nendobj\n") }
        val xref = out.size()
        write("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** Fake on-device OCR and image tools: the "image" bytes are the UTF-8 of the text the OCR will return. */
    class FakeImages : ImageTools {
        val ocrCalls = ArrayList<ByteArray>()
        override suspend fun ocr(image: ByteArray): String { ocrCalls += image; return String(image, Charsets.UTF_8) }
        override fun downscale(image: ByteArray, maxSide: Int): ByteArray = image.copyOf()
    }
}
