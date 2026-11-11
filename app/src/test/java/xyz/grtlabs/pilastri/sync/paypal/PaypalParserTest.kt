package xyz.grtlabs.pilastri.sync.paypal

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.Tx
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.id

/** Synthetic PayPal activity files only (see [PaypalFixtures]). */
class PaypalParserTest {
    private val at = Instant.parse("2026-10-31T10:00:00Z")
    private fun parse(bytes: ByteArray, name: String = "Download.csv") = PaypalParser.parse(bytes, name, at)
    private fun ImportBatch.byId() = movements.associateBy { it.externalId }
    private fun ImportBatch.warned(fragment: String) = warnings.any { it.contains(fragment) }

    // ---- headers and number formats ------------------------------------------------------------------------

    @Test fun italianHeadersDecimalCommaAndBom() {
        val body = PaypalFixtures.it(
            Tx("05/10/2026", "Deposito da conto bancario", "49,90", id(2), status = "Completata", time = "10:00:00", balance = "49,90", impact = "Credito"),
            Tx("05/10/2026", "Pagamento Express Checkout", "-49,90", id(1), name = "Negozio Sintetico", status = "Completata", time = "10:00:01", balance = "0,00", impact = "Debito"),
            Tx("06/10/2026", "Pagamento Express Checkout", "-1.234,56", id(3), name = "Grande Acquisto Sintetico", status = "Completata", net = "-1.234,56"),
        )
        val batch = parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body)
        assertEquals(ImportProvider.PAYPAL, batch.provider)
        assertEquals("PayPal", batch.accountLabel)
        assertEquals(LocalDate.of(2026, 10, 5), batch.coverageFrom)
        assertEquals(LocalDate.of(2026, 10, 6), batch.coverageTo)
        val m = batch.byId()
        assertEquals(-4_990L, m.getValue(id(1)).signedCents)
        assertEquals(ExternalKind.CARD_PAYMENT, m.getValue(id(1)).kind)
        assertEquals("Negozio Sintetico", m.getValue(id(1)).description)
        assertEquals(4_990L, m.getValue(id(2)).signedCents)
        assertEquals(ExternalKind.DEPOSIT, m.getValue(id(2)).kind)
        assertEquals(-123_456L, m.getValue(id(3)).signedCents)
        assertEquals("EUR", m.getValue(id(1)).currency)
        assertEquals(LocalDate.of(2026, 10, 5), m.getValue(id(1)).date)
        // the latest row with a balance (05/10 10:00:01) carries the running balance
        assertEquals(xyz.grtlabs.pilastri.data.ImportedBalance(LocalDate.of(2026, 10, 5), 0L), batch.balance)
    }

    @Test fun englishHeadersUsDatesAndDecimalPoint() {
        val batch = parse(PaypalFixtures.en(
            Tx("10/18/2026", "Express Checkout Payment", "-1,234.56", id(1), name = "Synthetic Shop"),
            Tx("10/19/2026", "Express Checkout Payment", "-12.50", id(2), name = "Synthetic Cafe"),
        ))
        assertEquals(LocalDate.of(2026, 10, 18), batch.byId().getValue(id(1)).date)
        assertEquals(-123_456L, batch.byId().getValue(id(1)).signedCents)
        assertEquals(-1_250L, batch.byId().getValue(id(2)).signedCents)
        assertFalse(batch.warned("ambigue"))
    }

    @Test fun ambiguousDatesAreReadDayFirstWithAWarning_andProofOfDayFirstIsSilent() {
        val ambiguous = parse(PaypalFixtures.it(Tx("05/10/2026", "Pagamento", "-1,00", id(1), name = "A", status = "Completata")))
        assertEquals(LocalDate.of(2026, 10, 5), ambiguous.movements.single().date)
        assertTrue(ambiguous.warned("ambigue"))
        val proven = parse(PaypalFixtures.it(
            Tx("05/10/2026", "Pagamento", "-1,00", id(1), name = "A", status = "Completata"),
            Tx("18/10/2026", "Pagamento", "-2,00", id(2), name = "B", status = "Completata")))
        assertFalse(proven.warned("ambigue"))
        assertThrows(IllegalArgumentException::class.java) {
            parse(PaypalFixtures.en(Tx("13/10/2026", "Payment", "-1.00", id(1)), Tx("10/18/2026", "Payment", "-2.00", id(2))))
        }
    }

    @Test fun semicolonAndTabDelimitedFilesAndWindows1252Text() {
        val semicolon = "Data;Ora;Nome;Tipo;Stato;Valuta;Lordo;Tariffa;Netto;Codice transazione\r\n" +
            "05/10/2026;10:00:00;Caffè Sintetico;Pagamento Express Checkout;Completata;EUR;-3,50;0,00;-3,50;${id(1)}\r\n"
        val batch = parse(semicolon.toByteArray(charset("windows-1252")))
        assertEquals("Caffè Sintetico", batch.movements.single().description)
        assertEquals(-350L, batch.movements.single().signedCents)
        val tab = "Date\tTime\tName\tType\tStatus\tCurrency\tGross\tTransaction ID\r\n05/10/2026\t10:00:00\tShop\tPayment\tCompleted\tEUR\t-3.50\t${id(2)}\r\n"
        assertEquals(-350L, parse(tab.toByteArray()).movements.single().signedCents)
    }

    @Test fun quotedFieldsKeepCommasQuotesAndLineBreaks() {
        val tx = Tx("05/10/2026", "Payment", "-3.50", id(1), name = "Shop, \"Synthetic\"\nBranch", status = "Completed")
        val batch = parse(PaypalFixtures.en(tx, Tx("06/10/2026", "Payment", "-1.00", id(2), name = "Other")))
        assertEquals("Shop, \"Synthetic\" Branch", batch.byId().getValue(id(1)).description)
        assertEquals(2, batch.movements.size)
    }

    @Test fun amountParserIsStrictAboutAmbiguousSeparators() {
        fun cents(s: String) = PaypalParser.parseCents(s)
        assertEquals(123_456L, cents("1.234,56"))
        assertEquals(123_456L, cents("1,234.56"))
        assertEquals(-1_250L, cents("-12,50"))
        assertEquals(-1_250L, cents("−12.50"))
        assertEquals(1_250L, cents("12.5"))
        assertEquals(1_200L, cents("12"))
        assertEquals(5L, cents("0,05"))
        assertEquals(123_456_700L, cents("1.234.567"))
        assertNull("one separator + three digits is thousands or decimals: refuse", cents("1,234"))
        assertNull(cents("1.234"))
        assertNull(cents(""))
        assertNull(cents("abc"))
        assertNull(cents("12,345.6.7"))
        assertNull(cents("1234567890123456"))
    }

    // ---- mapping of every Tipo -----------------------------------------------------------------------------

    @Test fun everyTypeMapsToTheRightKindOrIsExcluded() {
        fun tx(type: String, gross: String, n: Int, name: String = "") = Tx("05/10/2026", type, gross, id(n), name, status = "Completata")
        val rows = arrayOf(
            tx("Pagamento Express Checkout", "-10,00", 1, "Negozio Sintetico"),
            tx("Pagamento ricevuto", "5,00", 2, "Amico Sintetico"),
            tx("Deposito da conto bancario", "20,00", 3),
            tx("Prelievo generico", "-15,00", 4),
            tx("Commissione", "-0,50", 5),
            tx("Rimborso pagamento", "10,00", 6, "Negozio Sintetico"),
            tx("Conversione di valuta generica", "-8,00", 7),
            tx("Autorizzazione generica", "-30,00", 8),
            tx("Storno di blocco conto generico", "30,00", 9),
            tx("Tipo sconosciuto", "-7,00", 10),
            tx("Tipo sconosciuto", "7,00", 11),
            tx("Bonus cashback", "1,00", 12),
            tx("T0006 - Pagamento Express Checkout", "-2,00", 13, "Negozio Due"),
            tx("Storno", "-5,00", 14),
        )
        val batch = parse(PaypalFixtures.it(*rows))
        val m = batch.byId()
        assertEquals(ExternalKind.CARD_PAYMENT, m.getValue(id(1)).kind)
        assertEquals(ExternalKind.OTHER, m.getValue(id(2)).kind)
        assertEquals("Pagamento ricevuto · Amico Sintetico", m.getValue(id(2)).description)
        assertEquals(ExternalKind.DEPOSIT, m.getValue(id(3)).kind)
        assertEquals(ExternalKind.WITHDRAWAL, m.getValue(id(4)).kind)
        assertEquals(ExternalKind.FEE, m.getValue(id(5)).kind)
        assertEquals("a refund coming in is OTHER, never income or a contribution", ExternalKind.OTHER, m.getValue(id(6)).kind)
        val excluded = setOf(id(7), id(8), id(9), id(10), id(14))
        assertTrue("conversion, authorization, hold reversal, unknown outflow and outgoing reversal are not movements", excluded.none { it in m })
        assertEquals(ExternalKind.OTHER, m.getValue(id(11)).kind)
        assertEquals(ExternalKind.BONUS, m.getValue(id(12)).kind)
        assertEquals(ExternalKind.CARD_PAYMENT, m.getValue(id(13)).kind)
        assertEquals("the T-code prefix is not part of the raw type", "Pagamento Express Checkout", m.getValue(id(13)).rawType)
        assertEquals(9, batch.movements.size)
        assertTrue(batch.warned("conversione di valuta"))
        assertTrue(batch.warned("blocchi e autorizzazioni"))
        assertTrue(batch.warned("Tipo sconosciuto"))
        assertTrue(batch.warned("non sono entrate né contributi"))
    }

    @Test fun eventCodeColumnDecidesTheKindWhenTheTypeTextIsOpaque() {
        val rows = arrayOf(
            Tx("05/10/2026", "x", "-10.00", id(1), name = "Shop", code = "T0006"),
            Tx("05/10/2026", "x", "20.00", id(2), code = "T0300"),
            Tx("05/10/2026", "x", "-8.00", id(3), code = "T0200"),
            Tx("05/10/2026", "x", "4.00", id(4), code = "T1107", name = "Shop"),
            Tx("05/10/2026", "x", "-30.00", id(5), code = "T1110"),
            Tx("05/10/2026", "x", "-30.00", id(6), code = "T1300"),
            Tx("05/10/2026", "x", "-15.00", id(7), code = "T0400"),
            Tx("05/10/2026", "x", "-0.30", id(8), code = "T0100"),
            Tx("05/10/2026", "x", "9.00", id(9), code = "T0700"),
        )
        val batch = parse(PaypalFixtures.en(*rows))
        val kinds = batch.movements.associate { it.externalId to it.kind }
        assertEquals(
            mapOf(id(1) to ExternalKind.CARD_PAYMENT, id(2) to ExternalKind.DEPOSIT, id(4) to ExternalKind.OTHER, id(7) to ExternalKind.WITHDRAWAL,
                id(8) to ExternalKind.FEE, id(9) to ExternalKind.DEPOSIT), kinds)
    }

    // ---- excluded rows, fees, dedupe -----------------------------------------------------------------------

    @Test fun onlyCompletedBalanceAffectingNonZeroReadableRowsAreImported() {
        val batch = parse(PaypalFixtures.it(
            Tx("05/10/2026", "Pagamento", "-1,00", id(1), name = "Ok", status = "Completata"),
            Tx("05/10/2026", "Pagamento", "-2,00", id(2), name = "In sospeso", status = "In sospeso"),
            Tx("05/10/2026", "Pagamento", "-3,00", id(3), name = "Rifiutata", status = "Rifiutata"),
            Tx("05/10/2026", "Pagamento", "-4,00", id(4), name = "Promemoria", status = "Completata", impact = "Memo"),
            Tx("05/10/2026", "Pagamento", "0,00", id(5), name = "Zero", status = "Completata"),
            Tx("05/10/2026", "Pagamento", "-5,00", id(1), name = "Doppione", status = "Completata"),
            Tx("31/02/2026", "Pagamento", "-6,00", id(6), name = "Data impossibile", status = "Completata"),
            Tx("05/10/2026", "Pagamento", "abc", id(7), name = "Importo illeggibile", status = "Completata"),
            Tx("05/10/2026", "Pagamento", "-8,00", "###", name = "Codice illeggibile", status = "Completata"),
        ))
        assertEquals(listOf(id(1)), batch.movements.map { it.externalId })
        assertEquals("the first row with a code wins", -100L, batch.movements.single().signedCents)
        assertTrue(batch.warned("2 righe non completate"))
        assertTrue(batch.warned("senza effetto sul saldo"))
        assertTrue(batch.warned("importo zero"))
        assertTrue(batch.warned("duplicata"))
        assertTrue(batch.warned("3 righe con data, importo o codice non leggibile"))
    }

    @Test fun feeBecomesItsOwnMovementAndInconsistentNetIsReported() {
        val batch = parse(PaypalFixtures.it(
            Tx("05/10/2026", "Pagamento ricevuto", "10,00", id(1), name = "Cliente Sintetico", status = "Completata", fee = "-0,50", net = "9,50"),
            Tx("05/10/2026", "Pagamento ricevuto", "10,00", id(2), name = "Cliente Due", status = "Completata", fee = "-0,50", net = "9,00"),
        ))
        val m = batch.byId()
        assertEquals(1_000L, m.getValue(id(1)).signedCents)
        assertEquals(-50L, m.getValue("${id(1)}:fee").signedCents)
        assertEquals(ExternalKind.FEE, m.getValue("${id(1)}:fee").kind)
        assertEquals(4, batch.movements.size)
        assertTrue(batch.warned("netto diverso da lordo"))
    }

    @Test fun nonEuroRowsKeepTheirCurrencySoTheImportCanCountThemOut() {
        val batch = parse(PaypalFixtures.en(Tx("05/10/2026", "Express Checkout Payment", "-9.99", id(1), name = "Shop", currency = "USD")))
        assertEquals("USD", batch.movements.single().currency)
        assertTrue(batch.warned("valuta diversa da EUR"))
    }

    @Test fun balanceIsTheLatestUnambiguousEuroBalance() {
        val clear = parse(PaypalFixtures.en(
            Tx("05/10/2026", "Bank Deposit to PP Account", "10.00", id(1), balance = "10.00", time = "09:00:00"),
            Tx("05/10/2026", "Express Checkout Payment", "-4.00", id(2), name = "Shop", balance = "6.00", time = "09:00:05"),
        ))
        assertEquals(600L, clear.balance?.cents)
        assertEquals(LocalDate.of(2026, 10, 5), clear.balance?.date)
        val ambiguous = parse(PaypalFixtures.en(
            Tx("05/10/2026", "Bank Deposit to PP Account", "10.00", id(1), balance = "10.00"),
            Tx("05/10/2026", "Express Checkout Payment", "-4.00", id(2), name = "Shop", balance = "6.00"),
        ))
        assertNull(ambiguous.balance)
        assertTrue(ambiguous.warned("saldo"))
    }

    // ---- hostile or wrong input ----------------------------------------------------------------------------

    @Test fun formulaAndControlCharactersNeverReachTheDescription() {
        val batch = parse(PaypalFixtures.en(
            Tx("05/10/2026", "Express Checkout Payment", "-1.00", id(1), name = "=HYPERLINK(\"http://evil.example\",\"x\")"),
            Tx("05/10/2026", "Express Checkout Payment", "-2.00", id(2), name = "@SUM(A1)\u0007‮evil"),
            Tx("05/10/2026", "Express Checkout Payment", "-3.00", id(3), name = "+cmd|' /C calc'!A0"),
        ))
        batch.movements.forEach { m ->
            assertFalse(m.description, m.description.first() in "=+-@")
            assertTrue(m.description, m.description.none { it.code < 32 || it == '‮' })
        }
        assertEquals("evil", batch.byId().getValue(id(2)).description.takeLast(4))
    }

    @Test fun wrongFilesAreRefusedWithAnItalianMessage() {
        fun fails(bytes: ByteArray, name: String, fragment: String) {
            val e = assertThrows(IllegalArgumentException::class.java) { PaypalParser.parse(bytes, name, at) }
            assertTrue(e.message, e.message!!.contains(fragment))
        }
        fails(ByteArray(0), "x.csv", "vuoto")
        fails("%PDF-1.7 ...".toByteArray(), "x.pdf", "PDF")
        fails(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0), "x.xls", "Excel")
        fails("MZ\u0090\u0000".toByteArray(Charsets.ISO_8859_1), "x.csv", "non è un report")
        fails("<html><body>Accedi</body></html>".toByteArray(), "Download.csv", "pagina web")
        fails("!Type:Bank\nD10/05/2026\n".toByteArray(), "x.qif", "QIF")
        fails("niente,da,vedere\n1,2,3\n".toByteArray(), "x.csv", "report Attività di PayPal")
        fails("Date,Gross,Name\n05/10/2026,-1.00,A\n".toByteArray(), "x.csv", "codice transazione")
        fails(ByteArray(PaypalParser.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }, "x.csv", "10 MB")
    }

    @Test fun zipOfCsvPartsIsMergedAndHostileEntriesAreIgnoredOrRefused() {
        fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
            return out.toByteArray()
        }
        val part1 = PaypalFixtures.en(Tx("05/10/2026", "Express Checkout Payment", "-1.00", id(1), name = "A"))
        val part2 = PaypalFixtures.en(Tx("06/10/2026", "Express Checkout Payment", "-2.00", id(2), name = "B"), Tx("05/10/2026", "Express Checkout Payment", "-1.00", id(1), name = "A"))
        val merged = parse(zip("Download-1.csv" to part1, "../evil.csv" to part1, "run.exe" to byteArrayOf(0x4D, 0x5A), "Download-2.csv" to part2), "Download.zip")
        assertEquals(listOf(id(1), id(2)), merged.movements.map { it.externalId })
        assertTrue(merged.warned("duplicata"))
        assertThrows(IllegalArgumentException::class.java) { parse(zip("readme.pdf" to byteArrayOf(1, 2, 3)), "Download.zip") }
        val bomb = zip("big.csv" to ByteArray(PaypalParser.MAX_EXPANDED_BYTES + 1024))
        assertTrue("a small archive must not inflate past the cap", bomb.size < PaypalParser.MAX_FILE_BYTES)
        val e = assertThrows(IllegalArgumentException::class.java) { parse(bomb, "Download.zip") }
        assertTrue(e.message, e.message!!.contains("troppo grande"))
    }

    @Test fun anExportWithoutRowsGivesAnEmptyBatchAndAWarning() {
        val batch = parse(PaypalFixtures.en())
        assertTrue(batch.movements.isEmpty())
        assertTrue(batch.warned("Nessun movimento"))
        assertNull(batch.coverageFrom)
    }
}
