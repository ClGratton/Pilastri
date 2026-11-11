package xyz.grtlabs.pilastri.sync.isybank

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.TreeMap
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * One spreadsheet cell as stored in the file. Blank cells are null in a row. A numeric cell whose number format is a
 * date or time format arrives as [Date] (the time of day is dropped); every other numeric cell stays a [Num].
 */
sealed interface Cell {
    data class Text(val value: String) : Cell
    data class Num(val value: BigDecimal) : Cell
    data class Date(val value: LocalDate) : Cell
    data class Bool(val value: Boolean) : Cell
}

/** [rows] is indexed by 0-based row; [Sheet.rows] entries are indexed by 0-based column and may be shorter than the table. */
class Sheet(val name: String, val rows: List<List<Cell?>>)

class Workbook(val sheets: List<Sheet>)

/**
 * Minimal dependency-free reader for .xlsx (Office Open XML) and CSV. It only reads values: no formulas are evaluated,
 * nothing is written, nothing touches the network. Parsing uses java.util.zip and SAX, which exist on Android and in
 * plain JVM unit tests. A hostile file is bounded in uncompressed size, entry count, rows and columns, and XML
 * doctypes (external entities) are refused.
 */
object Xlsx {
    const val MAX_UNCOMPRESSED_BYTES = 20 * 1024 * 1024
    const val MAX_ENTRIES = 500
    const val MAX_ROWS = 100_000
    const val MAX_COLUMNS = 100
    private const val MAX_SHEETS = 32

    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** True for the legacy binary .xls container (BIFF inside OLE2), which this reader does not support. */
    fun isLegacyXls(bytes: ByteArray) = bytes.size >= OLE2_MAGIC.size && OLE2_MAGIC.indices.all { bytes[it] == OLE2_MAGIC[it] }

    fun read(
        bytes: ByteArray,
        maxUncompressedBytes: Int = MAX_UNCOMPRESSED_BYTES,
        maxEntries: Int = MAX_ENTRIES,
    ): Workbook {
        val parts = readParts(bytes, maxUncompressedBytes, maxEntries)
        val workbookXml = parts["xl/workbook.xml"] ?: throw IllegalArgumentException("Il file non è una cartella di lavoro Excel (.xlsx) valida.")

        val workbook = WorkbookHandler().also { parseXml(workbookXml, it) }
        val relationships = parts["xl/_rels/workbook.xml.rels"]?.let { RelationshipsHandler().also { h -> parseXml(it, h) }.targets } ?: emptyMap()
        val styles = parts["xl/styles.xml"]?.let { StylesHandler().also { h -> parseXml(it, h) } } ?: StylesHandler()
        val shared = parts["xl/sharedstrings.xml"]?.let { SharedStringsHandler().also { h -> parseXml(it, h) }.strings } ?: emptyList()

        if (workbook.sheets.isEmpty()) throw IllegalArgumentException("Il file .xlsx non contiene fogli.")
        if (workbook.sheets.size > MAX_SHEETS) throw IllegalArgumentException("Il file .xlsx contiene troppi fogli.")
        val sheets = workbook.sheets.mapIndexedNotNull { index, ref ->
            val target = ref.relationshipId?.let { relationships[it] }
            val path = if (target != null) resolveTarget(target) else "xl/worksheets/sheet${index + 1}.xml"
            val data = parts[path] ?: return@mapIndexedNotNull null
            val handler = SheetHandler(shared, styles, workbook.date1904).also { parseXml(data, it) }
            Sheet(ref.name, handler.toRows())
        }
        if (sheets.isEmpty()) throw IllegalArgumentException("Il file .xlsx non contiene fogli leggibili.")
        return Workbook(sheets)
    }

    /** Excel serial day number to date. The 1900 system keeps Excel's phantom 29 February 1900. Null if out of range. */
    fun serialToDate(serial: BigDecimal, date1904: Boolean = false): LocalDate? {
        if (serial.signum() < 0 || serial.precision() - serial.scale() > 8) return null
        val days = serial.setScale(0, java.math.RoundingMode.FLOOR).toLong()
        if (days > 2_958_465) return null
        return if (date1904) LocalDate.of(1904, 1, 1).plusDays(days)
        else when {
            days < 1 -> null
            days < 60 -> LocalDate.of(1899, 12, 31).plusDays(days)
            days == 60L -> LocalDate.of(1900, 2, 28)
            else -> LocalDate.of(1899, 12, 30).plusDays(days)
        }
    }

    /** Whether a custom number format code displays a calendar date. Elapsed-time codes such as [h]:mm are not dates. */
    fun looksLikeDateFormat(code: String): Boolean {
        val lower = code.lowercase()
        if (ELAPSED.containsMatchIn(lower)) return false
        val cleaned = lower
            .replace(QUOTED, "").replace(BRACKETED, "").replace(ESCAPED, "").replace(PADDING, "").replace(REPEAT, "")
            .substringBefore(';')
        if ('d' in cleaned || 'y' in cleaned) return true
        return 'm' in cleaned && 'h' !in cleaned && 's' !in cleaned
    }

    private val ELAPSED = Regex("\\[(h+|m+|s+)]")
    private val QUOTED = Regex("\"[^\"]*\"")
    private val BRACKETED = Regex("\\[[^\\]]*]")
    private val ESCAPED = Regex("\\\\.")
    private val PADDING = Regex("_.")
    private val REPEAT = Regex("\\*.")

    // ---- zip -----------------------------------------------------------------------------------------------

    private fun readParts(bytes: ByteArray, maxBytes: Int, maxEntries: Int): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > maxEntries) throw IllegalArgumentException("Il file .xlsx contiene troppi elementi: importazione rifiutata.")
                    val name = entry.name.replace('\\', '/').trimStart('/').lowercase()
                    val keep = !entry.isDirectory && (name.endsWith(".xml") || name.endsWith(".rels"))
                    val out = if (keep) ByteArrayOutputStream() else null
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IllegalArgumentException("Il file .xlsx decompresso è troppo grande: importazione rifiutata.")
                        out?.write(buffer, 0, n)
                    }
                    if (out != null) parts[name] = out.toByteArray()
                }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Il file .xlsx è danneggiato o non valido.")
        }
        if (entries == 0) throw IllegalArgumentException("Il file .xlsx è danneggiato o non valido.")
        return parts
    }

    private fun resolveTarget(target: String): String {
        val parts = ArrayList<String>()
        val base = if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
        for (segment in base.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/").lowercase()
    }

    // ---- xml -----------------------------------------------------------------------------------------------

    private fun parseXml(data: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // Hardening is best effort: Android's parser does not know every Xerces feature name.
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        try {
            val reader = factory.newSAXParser().xmlReader
            reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(data)))
        } catch (e: SAXException) {
            throw IllegalArgumentException("Il file .xlsx contiene dati non validi.")
        } catch (e: IOException) {
            throw IllegalArgumentException("Il file .xlsx contiene dati non validi.")
        } catch (e: javax.xml.parsers.ParserConfigurationException) {
            throw IllegalArgumentException("Il file .xlsx contiene dati non validi.")
        }
    }

    private fun elementName(localName: String?, qName: String?): String =
        if (!localName.isNullOrEmpty()) localName else (qName ?: "").substringAfter(':')

    private fun Attributes.value(local: String): String? {
        for (i in 0 until length) {
            val name = getLocalName(i).takeUnless { it.isNullOrEmpty() } ?: getQName(i).substringAfter(':')
            if (name == local) return getValue(i)
        }
        return null
    }

    private class SheetRef(val name: String, val relationshipId: String?)

    private class WorkbookHandler : DefaultHandler() {
        val sheets = ArrayList<SheetRef>()
        var date1904 = false
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val a = attributes ?: return
            when (elementName(localName, qName)) {
                "sheet" -> sheets += SheetRef(a.value("name") ?: "Foglio ${sheets.size + 1}", a.value("id"))
                "workbookPr" -> date1904 = a.value("date1904").let { it == "1" || it.equals("true", ignoreCase = true) }
            }
        }
    }

    private class RelationshipsHandler : DefaultHandler() {
        val targets = HashMap<String, String>()
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val a = attributes ?: return
            if (elementName(localName, qName) == "Relationship") {
                val id = a.value("Id")
                val target = a.value("Target")
                if (id != null && target != null) targets[id] = target
            }
        }
    }

    private class SharedStringsHandler : DefaultHandler() {
        val strings = ArrayList<String>()
        private var inItem = false
        private var inText = false
        private var phonetic = 0
        private val text = StringBuilder()
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (elementName(localName, qName)) {
                "si" -> { inItem = true; text.setLength(0) }
                "rPh" -> phonetic++
                "t" -> if (inItem && phonetic == 0) inText = true
            }
        }
        override fun characters(ch: CharArray?, start: Int, length: Int) { if (inText && ch != null) text.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (elementName(localName, qName)) {
                "t" -> inText = false
                "rPh" -> phonetic--
                "si" -> { strings += text.toString(); inItem = false }
            }
        }
    }

    private class StylesHandler : DefaultHandler() {
        private val customFormats = HashMap<Int, String>()
        private val cellFormatIds = ArrayList<Int>()
        private var inCellXfs = false
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val a = attributes
            when (elementName(localName, qName)) {
                "numFmt" -> {
                    val id = a?.value("numFmtId")?.toIntOrNull()
                    val code = a?.value("formatCode")
                    if (id != null && code != null) customFormats[id] = code
                }
                "cellXfs" -> inCellXfs = true
                "xf" -> if (inCellXfs) cellFormatIds += a?.value("numFmtId")?.toIntOrNull() ?: 0
            }
        }
        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (elementName(localName, qName) == "cellXfs") inCellXfs = false
        }
        fun isDateStyle(styleIndex: Int): Boolean {
            val formatId = cellFormatIds.getOrNull(styleIndex) ?: return false
            if (formatId in 14..22 || formatId in 45..47) return true
            return customFormats[formatId]?.let { looksLikeDateFormat(it) } ?: false
        }
    }

    private class SheetHandler(
        private val shared: List<String>,
        private val styles: StylesHandler,
        private val date1904: Boolean,
    ) : DefaultHandler() {
        private val rowMap = TreeMap<Int, List<Cell?>>()
        private var row = ArrayList<Cell?>()
        private var rowIndex = -1
        private var lastRowIndex = -1
        private var columnCursor = -1
        private var columnIndex = 0
        private var type: String? = null
        private var style = 0
        private val value = StringBuilder()
        private val inline = StringBuilder()
        private var inValue = false
        private var inInline = false
        private var inInlineText = false
        private var phonetic = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val a = attributes
            when (elementName(localName, qName)) {
                "row" -> {
                    rowIndex = a?.value("r")?.toIntOrNull()?.minus(1) ?: (lastRowIndex + 1)
                    if (rowIndex < 0 || rowIndex >= MAX_ROWS) throw SAXException("rows")
                    lastRowIndex = rowIndex
                    row = ArrayList()
                    columnCursor = -1
                }
                "c" -> {
                    columnIndex = a?.value("r")?.let { columnOf(it) } ?: (columnCursor + 1)
                    columnCursor = columnIndex
                    type = a?.value("t")
                    style = a?.value("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    inline.setLength(0)
                    phonetic = 0
                }
                "v" -> { inValue = true; value.setLength(0) }
                "is" -> inInline = true
                "rPh" -> phonetic++
                "t" -> if (inInline && phonetic == 0) inInlineText = true
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null) return
            if (inValue) value.append(ch, start, length) else if (inInlineText) inline.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (elementName(localName, qName)) {
                "v" -> inValue = false
                "t" -> inInlineText = false
                "rPh" -> phonetic--
                "is" -> inInline = false
                "c" -> {
                    val cell = buildCell()
                    if (cell != null && columnIndex in 0 until MAX_COLUMNS) {
                        while (row.size <= columnIndex) row.add(null)
                        row[columnIndex] = cell
                    }
                }
                "row" -> rowMap[rowIndex] = row
            }
        }

        private fun buildCell(): Cell? {
            val raw = value.toString()
            return when (type) {
                "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }?.takeIf { it.isNotBlank() }?.let { Cell.Text(it) }
                "str" -> raw.takeIf { it.isNotBlank() }?.let { Cell.Text(it) }
                "inlineStr" -> inline.toString().takeIf { it.isNotBlank() }?.let { Cell.Text(it) }
                "b" -> Cell.Bool(raw.trim() == "1")
                "d" -> runCatching { LocalDate.parse(raw.trim().take(10)) }.getOrNull()?.let { Cell.Date(it) }
                "e" -> null
                else -> {
                    val number = raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: return null
                    if (styles.isDateStyle(style)) serialToDate(number, date1904)?.let { Cell.Date(it) } ?: Cell.Num(number)
                    else Cell.Num(number)
                }
            }
        }

        fun toRows(): List<List<Cell?>> {
            if (rowMap.isEmpty()) return emptyList()
            return (0..rowMap.lastKey()).map { rowMap[it] ?: emptyList() }
        }

        private fun columnOf(reference: String): Int? {
            var column = 0
            var letters = 0
            for (ch in reference) {
                val upper = ch.uppercaseChar()
                if (upper !in 'A'..'Z') break
                column = column * 26 + (upper - 'A' + 1)
                if (++letters > 3) return null
            }
            return if (letters == 0) null else column - 1
        }
    }
}

/** Small CSV reader for bank exports: `;`, tab or `,` delimited, quoted fields, UTF-8/UTF-16 or Windows-1252 text. */
object Csv {
    fun read(bytes: ByteArray): Sheet {
        val text = decode(bytes)
        val delimiter = detectDelimiter(text)
        val rows = ArrayList<List<Cell?>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var fieldWasQuoted = false

        fun endField() {
            val value = field.toString().trim()
            fields += value
            field.setLength(0)
            fieldWasQuoted = false
        }
        fun endRow() {
            endField()
            if (rows.size >= Xlsx.MAX_ROWS) throw IllegalArgumentException("Il file contiene troppe righe.")
            rows += fields.take(Xlsx.MAX_COLUMNS).map { if (it.isEmpty()) null else Cell.Text(it) }
            fields = ArrayList()
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted -> when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    ch == '"' -> quoted = false
                    else -> field.append(ch)
                }
                ch == '"' && field.isBlank() -> { quoted = true; fieldWasQuoted = true; field.setLength(0) }
                ch == delimiter -> endField()
                ch == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty() || fieldWasQuoted) endRow()
        return Sheet("CSV", rows)
    }

    internal fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }

    /** The delimiter that occurs most often (outside quotes) in the first lines; `;` wins ties as Italian Excel's default. */
    private fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf(';' to 0, '\t' to 0, ',' to 0)
        var quoted = false
        var lines = 0
        for (ch in text) {
            if (ch == '"') quoted = !quoted
            else if (!quoted) {
                if (ch == '\n') { if (++lines >= 40) break } else if (ch in counts) counts[ch] = counts.getValue(ch) + 1
            }
        }
        val best = listOf(';', '\t', ',').maxByOrNull { counts.getValue(it) } ?: ';'
        return if (counts.getValue(best) == 0) ';' else best
    }
}
