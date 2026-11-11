package xyz.grtlabs.pilastri.sync.isybank

import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic workbooks only. */
class XlsxTest {
    private val day = LocalDate.of(2026, 3, 27)

    @Test fun readsSheetNamesStringsNumbersAndBooleans() {
        val bytes = XlsxFixture.build(
            FixtureSheet("Lista Operazione", listOf(row("Data", "Importo"), row("a & b <c>", -12.5, true), emptyList(), row(null, 7))),
            FixtureSheet("Altro", listOf(row("x")), inlineStrings = true),
        )
        val workbook = Xlsx.read(bytes)

        assertEquals(listOf("Lista Operazione", "Altro"), workbook.sheets.map { it.name })
        val rows = workbook.sheets[0].rows
        assertEquals(4, rows.size)
        assertEquals(Cell.Text("Data"), rows[0][0])
        assertEquals(Cell.Text("a & b <c>"), rows[1][0])
        assertEquals(0, BigDecimal("-12.5").compareTo((rows[1][1] as Cell.Num).value))
        assertEquals(Cell.Bool(true), rows[1][2])
        assertTrue(rows[2].isEmpty())
        assertNull(rows[3][0])
        assertEquals(0, BigDecimal(7).compareTo((rows[3][1] as Cell.Num).value))
        assertEquals(Cell.Text("x"), workbook.sheets[1].rows[0][0])
    }

    @Test fun onlyCellXfsDecideWhichStylesAreDates() {
        val serial = BigDecimal(XlsxFixture.serial(day))
        val bytes = XlsxFixture.build(FixtureSheet("S", listOf(
            row(day, StyledDate(day, 2), StyledDate(day, 4), StyledDate(day, 3), StyledDate(day, 0)),
        )))
        val cells = Xlsx.read(bytes).sheets.single().rows.single()

        assertEquals(Cell.Date(day), cells[0])   // built-in 14
        assertEquals(Cell.Date(day), cells[1])   // custom dd/mm/yyyy
        assertEquals(Cell.Date(day), cells[2])   // custom [$-410]d mmmm yyyy
        assertEquals(0, serial.compareTo((cells[3] as Cell.Num).value))   // custom #,##0.00 is a number
        assertEquals(0, serial.compareTo((cells[4] as Cell.Num).value))   // style 0: cellStyleXfs' 14 must not leak in
    }

    @Test fun serialsFollowTheExcelSystems() {
        assertEquals(LocalDate.of(2024, 1, 1), Xlsx.serialToDate(BigDecimal(45292)))
        assertEquals(LocalDate.of(2024, 1, 1), Xlsx.serialToDate(BigDecimal("45292.75")))
        assertEquals(LocalDate.of(1900, 3, 1), Xlsx.serialToDate(BigDecimal(61)))
        assertEquals(LocalDate.of(1900, 2, 28), Xlsx.serialToDate(BigDecimal(59)))
        assertEquals(LocalDate.of(1900, 1, 1), Xlsx.serialToDate(BigDecimal(1)))
        assertNull(Xlsx.serialToDate(BigDecimal(0)))
        assertNull(Xlsx.serialToDate(BigDecimal(-3)))
        assertNull(Xlsx.serialToDate(BigDecimal("1E20")))
        assertEquals(LocalDate.of(1904, 1, 1), Xlsx.serialToDate(BigDecimal(0), date1904 = true))
        assertEquals(LocalDate.of(1904, 1, 2), Xlsx.serialToDate(BigDecimal(1), date1904 = true))
    }

    @Test fun workbooksInThe1904SystemAreHonoured() {
        val bytes = XlsxFixture.build(FixtureSheet("S", listOf(row(day))), date1904 = true)
        assertEquals(Cell.Date(day), Xlsx.read(bytes).sheets.single().rows.single()[0])
    }

    @Test fun customFormatsAreDatesOnlyWhenTheyShowADate() {
        listOf("dd/mm/yyyy", "d-mmm-yy", "yyyy", "mmmm", "[$-410]dd mmmm yyyy", "dd/mm/yyyy hh:mm", "m/d/yy;@").forEach { assertTrue(it, Xlsx.looksLikeDateFormat(it)) }
        listOf("General", "0.00", "#,##0.00", "#,##0.00 \"d\"", "0.00 \"giorni\"", "[\$€-410] #,##0.00", "hh:mm:ss", "[h]:mm", "[m]:ss", "0.0E+00", "_-* #,##0.00_-;\\-* #,##0.00_-")
            .forEach { assertFalse(it, Xlsx.looksLikeDateFormat(it)) }
    }

    @Test fun richTextSharedStringsAreJoinedAndPhoneticRunsDropped() {
        val parts = XlsxFixture.parts(listOf(FixtureSheet("S", listOf(row("placeholder")))))
        parts["xl/sharedStrings.xml"] = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <si><r><t>Ciao </t></r><r><rPr><b/></rPr><t>mondo</t></r><rPh sb="0" eb="1"><t>XXX</t></rPh></si></sst>"""
        val cell = Xlsx.read(XlsxFixture.zip(parts)).sheets.single().rows.single()[0]
        assertEquals(Cell.Text("Ciao mondo"), cell)
    }

    @Test fun missingSharedStringsAndStylesStillReadNumbers() {
        val parts = XlsxFixture.parts(listOf(FixtureSheet("S", listOf(row(1.5, day)))))
        parts.remove("xl/sharedStrings.xml"); parts.remove("xl/styles.xml")
        val cells = Xlsx.read(XlsxFixture.zip(parts)).sheets.single().rows.single()
        assertTrue(cells[0] is Cell.Num)
        assertTrue("without styles a serial stays a number", cells[1] is Cell.Num)
    }

    @Test fun decompressedSizeIsCapped() {
        val bomb = XlsxFixture.zipBytes(mapOf("xl/worksheets/sheet1.xml" to ByteArray(Xlsx.MAX_UNCOMPRESSED_BYTES + 1024 * 1024) { ' '.code.toByte() }))
        assertTrue("the bomb is tiny compressed", bomb.size < 200_000)
        val error = assertThrows(IllegalArgumentException::class.java) { Xlsx.read(bomb) }
        assertTrue(error.message!!, error.message!!.contains("troppo grande"))

        val valid = XlsxFixture.build(FixtureSheet("S", listOf(row("x"))))
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(valid, maxUncompressedBytes = 100) }
    }

    @Test fun entryCountIsCapped() {
        val many = XlsxFixture.zip((1..Xlsx.MAX_ENTRIES + 1).associate { "xl/media/x$it.xml" to "<a/>" })
        val error = assertThrows(IllegalArgumentException::class.java) { Xlsx.read(many) }
        assertTrue(error.message!!, error.message!!.contains("troppi elementi"))
    }

    @Test fun corruptAndNonWorkbookZipsFailInItalian() {
        val valid = XlsxFixture.build(FixtureSheet("S", listOf(row("x", "y"))))
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(valid.copyOf(valid.size * 6 / 10)) }
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(byteArrayOf(0x50, 0x4B, 3, 4, 1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(XlsxFixture.zip(mapOf("readme.txt.xml" to "<a/>"))) }
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(ByteArray(0)) }
    }

    @Test fun doctypeAndExternalEntitiesAreRefused() {
        val parts = XlsxFixture.parts(listOf(FixtureSheet("S", listOf(row("x")))))
        parts["xl/workbook.xml"] = """<?xml version="1.0"?><!DOCTYPE workbook [<!ENTITY xxe SYSTEM "file:///nonexistent/secret">]>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets><sheet name="&xxe;" sheetId="1"/></sheets></workbook>"""
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(XlsxFixture.zip(parts)) }
    }

    @Test fun rowIndexesBeyondTheCapAreRefused() {
        val parts = XlsxFixture.parts(listOf(FixtureSheet("S", listOf(row("x")))))
        parts["xl/worksheets/sheet1.xml"] = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="${Xlsx.MAX_ROWS + 1}"><c r="A1"><v>1</v></c></row></sheetData></worksheet>"""
        assertThrows(IllegalArgumentException::class.java) { Xlsx.read(XlsxFixture.zip(parts)) }
    }

    @Test fun detectsLegacyXlsAndZipSignatures() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0)
        assertTrue(Xlsx.isLegacyXls(ole))
        assertFalse(Xlsx.isZip(ole))
        assertTrue(Xlsx.isZip(XlsxFixture.build(FixtureSheet("S", listOf(row("x"))))))
        assertFalse(Xlsx.isLegacyXls("Data;Importo".toByteArray()))
    }

    // ---- CSV -----------------------------------------------------------------------------------------------

    private fun csv(text: String, charset: Charset = Charsets.UTF_8) = Csv.read(text.toByteArray(charset)).rows
    private fun texts(row: List<Cell?>) = row.map { (it as Cell.Text?)?.value }

    @Test fun csvHandlesQuotesEmbeddedDelimitersAndNewlines() {
        val rows = csv("a;b;c\r\n\"x;y\";\"say \"\"hi\"\"\";\"line1\nline2\"\r\n;;last")
        assertEquals(listOf("a", "b", "c"), texts(rows[0]))
        assertEquals(listOf("x;y", "say \"hi\"", "line1\nline2"), texts(rows[1]))
        assertEquals(listOf(null, null, "last"), texts(rows[2]))
        assertEquals(3, rows.size)
    }

    @Test fun csvPicksTheDelimiterThatOccursMostOutsideQuotes() {
        assertEquals(listOf("1.234,56", "x"), texts(csv("Importo,Nota\n\"1.234,56\",x")[1]))
        assertEquals(listOf("a", "b"), texts(csv("a\tb\n1\t2")[0]))
        assertEquals(listOf("solo"), texts(csv("solo")[0]))
    }

    @Test fun csvDecodesBomUtf16AndWindows1252() {
        assertEquals("Caffè", texts(csv("﻿Caffè;1")[0])[0])
        assertEquals("Caffè", texts(csv("Caffè;1", Charset.forName("windows-1252"))[0])[0])
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Data\tImporto".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("Data", "Importo"), texts(Csv.read(utf16).rows[0]))
    }
}
