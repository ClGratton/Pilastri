package xyz.grtlabs.pilastri.sync.isybank

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A date cell with an explicit style: 1 = built-in numFmt 14, 2 = custom dd/mm/yyyy, 4 = custom long date. */
internal data class StyledDate(val date: LocalDate, val style: Int)

/** Cell values: String, Number, Boolean, LocalDate, StyledDate or null (blank). */
internal class FixtureSheet(val name: String, val rows: List<List<Any?>>, val inlineStrings: Boolean = false)

internal fun row(vararg cells: Any?): List<Any?> = cells.toList()

/** Builds synthetic .xlsx packages (minimal workbook, rels, styles, sharedStrings, sheets) for tests. Never real data. */
internal object XlsxFixture {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun zip(entries: Map<String, String>): ByteArray = zipBytes(entries.mapValues { it.value.toByteArray(Charsets.UTF_8) })

    fun build(vararg sheets: FixtureSheet, date1904: Boolean = false): ByteArray = zip(parts(sheets.toList(), date1904))

    fun parts(sheets: List<FixtureSheet>, date1904: Boolean = false): LinkedHashMap<String, String> {
        val shared = LinkedHashMap<String, Int>()
        val parts = LinkedHashMap<String, String>()
        parts["[Content_Types].xml"] = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>"""
        parts["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG_REL"><Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        sheets.forEachIndexed { index, sheet -> parts["xl/worksheets/sheet${index + 1}.xml"] = sheetXml(sheet, shared, date1904) }
        val sheetTags = sheets.mapIndexed { i, s -> """<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("")
        val workbookPr = if (date1904) """<workbookPr date1904="1"/>""" else """<workbookPr/>"""
        parts["xl/workbook.xml"] = """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$NS" xmlns:r="$REL">$workbookPr<sheets>$sheetTags</sheets></workbook>"""
        val sheetRels = sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="$REL/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" }
        parts["xl/_rels/workbook.xml.rels"] = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG_REL">$sheetRels<Relationship Id="rId90" Type="$REL/styles" Target="styles.xml"/><Relationship Id="rId91" Type="$REL/sharedStrings" Target="sharedStrings.xml"/></Relationships>"""
        // cellStyleXfs deliberately carries numFmtId 14: only cellXfs decide a cell's format.
        parts["xl/styles.xml"] = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$NS">
            <numFmts count="3"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/><numFmt numFmtId="165" formatCode="#,##0.00"/><numFmt numFmtId="166" formatCode="[$-410]d mmmm yyyy"/></numFmts>
            <cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs>
            <cellXfs count="5"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="165"/><xf numFmtId="166"/></cellXfs>
            </styleSheet>"""
        val strings = shared.keys.joinToString("") { """<si><t xml:space="preserve">${esc(it)}</t></si>""" }
        parts["xl/sharedStrings.xml"] = """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$NS" count="${shared.size}" uniqueCount="${shared.size}">$strings</sst>"""
        return parts
    }

    fun serial(date: LocalDate, date1904: Boolean = false): Long =
        ChronoUnit.DAYS.between(if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30), date)

    private fun sheetXml(sheet: FixtureSheet, shared: MutableMap<String, Int>, date1904: Boolean): String {
        val rows = StringBuilder()
        sheet.rows.forEachIndexed { r, cells ->
            val rowNumber = r + 1
            val xml = StringBuilder()
            cells.forEachIndexed { c, value ->
                val ref = "${columnName(c)}$rowNumber"
                when (value) {
                    null -> Unit
                    is String ->
                        if (sheet.inlineStrings) xml.append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(value)}</t></is></c>""")
                        else xml.append("""<c r="$ref" t="s"><v>${shared.getOrPut(value) { shared.size }}</v></c>""")
                    is Boolean -> xml.append("""<c r="$ref" t="b"><v>${if (value) 1 else 0}</v></c>""")
                    is LocalDate -> xml.append("""<c r="$ref" s="1"><v>${serial(value, date1904)}</v></c>""")
                    is StyledDate -> xml.append("""<c r="$ref" s="${value.style}"><v>${serial(value.date, date1904)}</v></c>""")
                    is BigDecimal -> xml.append("""<c r="$ref"><v>${value.toPlainString()}</v></c>""")
                    is Double -> xml.append("""<c r="$ref"><v>${BigDecimal.valueOf(value).toPlainString()}</v></c>""")
                    is Number -> xml.append("""<c r="$ref"><v>$value</v></c>""")
                    else -> error("unsupported fixture cell $value")
                }
            }
            if (xml.isNotEmpty()) rows.append("""<row r="$rowNumber">$xml</row>""")
        }
        return """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$NS"><sheetData>$rows</sheetData></worksheet>"""
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val name = StringBuilder()
        while (n > 0) { name.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }
        return name.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
