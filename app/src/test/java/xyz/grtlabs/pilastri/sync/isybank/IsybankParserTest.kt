package xyz.grtlabs.pilastri.sync.isybank

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedBalance
import xyz.grtlabs.pilastri.data.withImportBatch

/**
 * Synthetic files only. The column spellings follow the public Intesa Sanpaolo layouts (ofxstatement-intesasp); the real
 * isybank export has not been seen, so these tests prove the detection logic, not the exact isybank file.
 */
class IsybankParserTest {
    private val fetchedAt = Instant.parse("2026-03-29T10:00:00Z")
    private fun parse(bytes: ByteArray, name: String = "movimenti.xlsx"): ImportBatch = IsybankParser.parse(bytes, name, fetchedAt)
    private fun d(day: Int, month: Int = 3) = LocalDate.of(2026, month, day)

    private val v2Header = row("Data", "Operazione", "Dettagli", "Conto o carta", "Contabilizzazione", "Categoria", "Valuta", "Importo")

    /** "Lista Operazione" layout with the header on 0-based [headerAt]; period labels sit in B/C of rows 2 and 3. */
    private fun v2Rows(headerAt: Int, data: List<List<Any?>>): List<List<Any?>> {
        val rows = ArrayList<List<Any?>>()
        for (i in 0 until headerAt) rows.add(when (i) {
            1 -> row(null, "Data inizio periodo:", "01/03/2026")
            2 -> row(null, "Data fine periodo:", "28/03/2026")
            else -> emptyList()
        })
        rows.add(v2Header)
        rows.addAll(data)
        return rows
    }

    private fun v2(headerAt: Int, data: List<List<Any?>>, inline: Boolean = false) =
        XlsxFixture.build(FixtureSheet("Lista Operazione", v2Rows(headerAt, data), inline))

    private val v2Data = listOf(
        row(d(27), "Pagamento POS", "Carrefour Express Milano", "Carta 1234", "CONTABILIZZATO", "Alimentari", "Euro", -23.45),
        row(d(26), "Prelievo Bancomat", "Sportello Via Roma", "Carta 1234", "CONTABILIZZATO", "Contanti", "Euro", -50.0),
        row(d(25), "Bonifico in entrata", "Stipendio marzo", "Conto", "CONTABILIZZATO", "Entrate", "Euro", 1800.0),
        row(d(24), "Commissioni", "Canone mensile", "Conto", "CONTABILIZZATO", "Banca", "Euro", -2.0),
        row(d(23), "Imposta di bollo", "Imposta di bollo", "Conto", "CONTABILIZZATO", "Tasse", "Euro", -2.0),
        row(d(22), "Accredito interessi", "Interessi attivi", "Conto", "CONTABILIZZATO", "Interessi", "Euro", 0.12),
        row(d(28), "Pagamento POS", "Bar Centrale", "Carta 1234", "NON CONTABILIZZATO", "Bar", "Euro", -1.5),
        emptyList(),
    )

    @Test fun appLayoutWithPeriodLabelsKindsPendingAndSignedAmounts() {
        val batch = parse(v2(10, v2Data))

        assertEquals(ImportProvider.ISYBANK, batch.provider)
        assertEquals(fetchedAt, batch.fetchedAt)
        assertEquals(6, batch.movements.size)
        assertEquals(listOf(-2345L, -5000L, 180000L, -200L, -200L, 12L), batch.movements.map { it.signedCents })
        assertEquals(
            listOf(ExternalKind.CARD_PAYMENT, ExternalKind.WITHDRAWAL, ExternalKind.DEPOSIT, ExternalKind.FEE, ExternalKind.TAX, ExternalKind.INTEREST),
            batch.movements.map { it.kind },
        )
        val first = batch.movements.first()
        assertEquals(d(27), first.date)
        assertEquals("Carrefour Express Milano", first.description)
        assertEquals("Pagamento POS", first.rawType)
        assertEquals("Alimentari", first.category)
        assertEquals("EUR", first.currency)
        assertEquals("isybank · Conto", batch.accountLabel)
        assertEquals(d(1), batch.coverageFrom)
        assertEquals(d(28), batch.coverageTo)
        assertNull(batch.balance)
        assertTrue(batch.warnings.toString(), batch.warnings.any { it.startsWith("1 movimento non contabilizzato") })
    }

    @Test fun inlineStringsReadTheSame() {
        assertEquals(parse(v2(10, v2Data)).movements, parse(v2(10, v2Data, inline = true)).movements)
    }

    @Test fun headerIsFoundAtAnyRowWithinTheScanWindow() {
        val expected = parse(v2(0, v2Data)).movements
        listOf(1, 3, 10, 29, IsybankParser.HEADER_SCAN_ROWS - 1).forEach { at ->
            assertEquals("header at row ${at + 1}", expected, parse(v2(at, v2Data)).movements)
        }
        val error = assertThrows(IllegalArgumentException::class.java) { parse(v2(IsybankParser.HEADER_SCAN_ROWS, v2Data)) }
        assertTrue(error.message!!, error.message!!.contains("intestazione"))
    }

    private fun v1Bytes() = XlsxFixture.build(FixtureSheet("Lista Movimenti", buildList<List<Any?>> {
        repeat(7) { add(emptyList()) }
        add(row("Conto", null, null, "Conto sintetico"))
        repeat(2) { add(emptyList()) }
        add(row("Saldo contabile al 28/03/2026", null, null, 1234.56))
        repeat(18) { add(emptyList()) }
        add(row("Data contabile", "Data valuta", "Descrizione", "Accrediti", "Addebiti", "Descrizione estesa", "Mezzo"))
        add(row(d(20), d(21), "Bonifico a vostro favore", 500.0, null, "Bonifico a vostro favore da Rossi per affitto", "Bonifico"))
        add(row(StyledDate(d(19), 2), d(19), "Pagamento POS", null, 12.34, "Pagamento POS Conad Roma", "Carta di debito"))
        add(row(d(18), d(18), "Prelievo contante", null, -9.99, "Prelievo ATM", "Carta"))
        add(row(d(17), d(17), "Canone trimestrale", "-", 6.0, "Canone conto", "Addebito"))
    }))

    @Test fun movementsLayoutUsesBookingDateAndColumnSignsEvenForUnsignedDebits() {
        val batch = parse(v1Bytes())

        assertEquals(4, batch.movements.size)
        assertEquals(listOf(d(20), d(19), d(18), d(17)), batch.movements.map { it.date })
        assertEquals(listOf(50_000L, -1_234L, -999L, -600L), batch.movements.map { it.signedCents })
        assertEquals(listOf(ExternalKind.DEPOSIT, ExternalKind.CARD_PAYMENT, ExternalKind.WITHDRAWAL, ExternalKind.FEE), batch.movements.map { it.kind })
        assertEquals("Bonifico a vostro favore da Rossi per affitto", batch.movements[0].description)
        assertEquals("Bonifico", batch.movements[0].rawType)
        assertEquals("isybank", batch.accountLabel)
        assertEquals(d(17), batch.coverageFrom)
        assertEquals(d(20), batch.coverageTo)
        assertEquals(ImportedBalance(d(28), 123_456), batch.balance)
    }

    @Test fun balanceNeedsAClearLabelAndADate() {
        fun withBalanceRow(label: String, vararg tail: Any?) = XlsxFixture.build(FixtureSheet("S", listOf(
            row(label, *tail), row("Data", "Descrizione", "Importo"), row(d(3), "Bar", -1.0))))
        assertEquals(ImportedBalance(d(2), 50_000), parse(withBalanceRow("Saldo contabile", d(2), 500.0)).balance)
        assertEquals(ImportedBalance(d(2), 50_000), parse(withBalanceRow("Saldo contabile al 02/03/2026", 500.0)).balance)
        assertNull("no date", parse(withBalanceRow("Saldo contabile", 500.0)).balance)
        assertNull("not a balance label", parse(withBalanceRow("Totale", d(2), 500.0)).balance)
        val available = parse(withBalanceRow("Saldo disponibile", d(2), 480.0))
        assertEquals(ImportedBalance(d(2), 48_000), available.balance)
        assertTrue(available.warnings.toString(), available.warnings.any { it.contains("disponibile") })
    }

    @Test fun pendingRowsAreSkippedWhateverMarksThem() {
        val batch = parse(v2(2, listOf(
            row(d(10), "Pagamento POS", "A", "Conto", "In corso", "x", "Euro", -1.0),
            row("In corso", "Pagamento POS", "B", "Conto", null, "x", "Euro", -2.0),
            row(d(11), "Pagamento POS", "C", "Conto", "Contabilizzato", "x", "Euro", -3.0),
            row(d(12), "Pagamento POS", "D", "Conto", "NON CONTABILIZZATO", "x", "Euro", -4.0),
        )))

        assertEquals(listOf("C"), batch.movements.map { it.description })
        assertTrue(batch.warnings.toString(), batch.warnings.any { it.startsWith("3 movimenti non contabilizzati") })
    }

    @Test fun textDatesAndItalianTextAmountsInAWorkbookAreRead() {
        val batch = parse(XlsxFixture.build(FixtureSheet("S", listOf(
            row("Data contabile", "Descrizione", "Accrediti", "Addebiti"),
            row("27/03/2026", "Uno", "1.234,56", null),
            row("2026-03-26", "Due", null, "12,5"),
            row("26.03.26", "Tre", "€ 3,00", ""),
            row("(non una data)", "Quattro", "5,00", null),
        ))))

        assertEquals(listOf(d(27), d(26), d(26)), batch.movements.map { it.date })
        assertEquals(listOf(123_456L, -1_250L, 300L), batch.movements.map { it.signedCents })
        assertTrue(batch.warnings.toString(), batch.warnings.any { it.startsWith("1 riga") })
    }

    @Test fun rowsThatAreNeitherDataNorEmptyAreCountedNotInvented() {
        val batch = parse(XlsxFixture.build(FixtureSheet("S", listOf(
            row("Data", "Descrizione", "Accrediti", "Addebiti"),
            row(d(1), "Valida", 10.0, null),
            emptyList(),
            row(d(2), "Senza importo", null, null),
            row("Data", "Descrizione", "Accrediti", "Addebiti"),   // header repeated by a page break
            row(d(3), "Dopo", null, 4.0),
            row("Il presente documento non ha valore fiscale"),
        ))))

        assertEquals(listOf("Valida", "Dopo"), batch.movements.map { it.description })
        assertTrue(batch.warnings.toString(), batch.warnings.any { it.startsWith("1 riga con data o importo non leggibile") })
        assertTrue(batch.warnings.toString(), batch.warnings.any { it.startsWith("1 riga vuota") })
    }

    @Test fun nonEuroRowsAreFlaggedAndKeepTheirCurrency() {
        val batch = parse(v2(0, listOf(
            row(d(5), "Pagamento POS", "Shop", "Carta", "CONTABILIZZATO", "x", "USD", -10.0),
            row(d(6), "Pagamento POS", "Shop", "Carta", "CONTABILIZZATO", "x", "Euro", -10.0),
        )))
        assertEquals(listOf("USD", "EUR"), batch.movements.map { it.currency })
        assertTrue(batch.warnings.toString(), batch.warnings.any { it.contains("valuta diversa da EUR") })
    }

    @Test fun aTableWithoutBookedRowsIsAnEmptyBatchWithAWarning() {
        val batch = parse(v2(0, emptyList()))
        assertTrue(batch.movements.isEmpty())
        assertNull(batch.coverageFrom)
        assertTrue(batch.warnings.single().startsWith("Nessun movimento"))
    }

    // ---- ids -----------------------------------------------------------------------------------------------

    private fun bars(vararg days: Pair<Int, Int>) = XlsxFixture.build(FixtureSheet("S",
        listOf(row("Data", "Operazione", "Dettagli", "Importo")) +
            days.flatMap { (day, times) -> List(times) { row(d(day), "Pagamento POS", "Bar", -1.5) } } +
            listOf(row(d(9), "Pagamento POS", "Edicola", -2.0))))

    @Test fun identicalPurchasesStayDistinctAndOverlappingFilesShareIds() {
        val first = parse(bars(1 to 2, 2 to 1))      // 1st: two identical purchases, 2nd: one
        val overlap = parse(bars(2 to 1, 3 to 1))    // a later export that starts on the 2nd

        val ids = first.movements.map { it.externalId }
        assertEquals(4, ids.size)
        assertEquals("all distinct, including the two identical purchases", 4, ids.toSet().size)
        assertTrue(ids.all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
        val shared = ids.toSet() intersect overlap.movements.map { it.externalId }.toSet()
        assertEquals("the 2nd's purchase and the 9th's one are in both files", 2, shared.size)
        assertEquals(first.movements.first { it.date == d(2) }.externalId, overlap.movements.first { it.date == d(2) }.externalId)
        assertEquals(parse(bars(1 to 2, 2 to 1)).movements.map { it.externalId }, ids)
    }

    @Test fun fileOrderDoesNotChangeTheIdSet() {
        val forward = parse(bars(1 to 2, 2 to 1, 4 to 1))
        val backward = parse(XlsxFixture.build(FixtureSheet("S",
            listOf(row("Data", "Operazione", "Dettagli", "Importo")) +
                forward.movements.reversed().map { row(it.date, "Pagamento POS", it.description, it.signedCents / 100.0) })))
        assertEquals(forward.movements.map { it.externalId }.toSet(), backward.movements.map { it.externalId }.toSet())
    }

    @Test fun parsedMovementsFlowIntoTheArchiveAsPendingReview() {
        val account = Account("isy", "Conto sintetico", 4, "fixture")
        val batch = parse(v2(0, v2Data))
        val outcome = Archive(accounts = listOf(account)).withImportBatch(batch, account.id, "2026-03-29T10:00:01Z")

        assertEquals(6, outcome.added)
        assertEquals(0, outcome.skippedForeign)
        assertTrue(outcome.archive.events.isEmpty())
        assertEquals(outcome.added, outcome.archive.withImportBatch(batch, account.id).alreadyPresent)
    }

    // ---- kinds ---------------------------------------------------------------------------------------------

    @Test fun kindHeuristicsFollowSpecificityAndNeverSuggestRefundsAsContributions() {
        fun kind(text: String, cents: Long) = IsybankParser.classifyKind(text, cents)
        assertEquals(ExternalKind.CARD_PAYMENT, kind("Pagamento POS Carrefour", -2345))
        assertEquals(ExternalKind.CARD_PAYMENT, kind("Pagamento carta Amazon", -1000))
        assertEquals(ExternalKind.CARD_PAYMENT, kind("Apple Pay Bar", -250))
        assertEquals(ExternalKind.WITHDRAWAL, kind("Prelievo Bancomat ATM", -5000))
        assertEquals(ExternalKind.FEE, kind("Commissione prelievo", -200))
        assertEquals(ExternalKind.FEE, kind("Canone mensile carta", -200))
        assertEquals(ExternalKind.FEE, kind("Spese tenuta conto", -100))
        assertEquals(ExternalKind.TAX, kind("Imposta di bollo", -200))
        assertEquals(ExternalKind.TAX, kind("Bollo conto corrente", -200))
        assertEquals(ExternalKind.INTEREST, kind("Accredito interessi attivi", 12))
        assertEquals(ExternalKind.FEE, kind("Interessi debitori", -50))
        assertEquals(ExternalKind.DEPOSIT, kind("Bonifico in entrata Stipendio", 180_000))
        assertEquals(ExternalKind.WITHDRAWAL, kind("Bonifico Affitto", -70_000))
        assertEquals("pos is a word, not a substring", ExternalKind.WITHDRAWAL, kind("Bonifico Deposito cauzionale", -50_000))
        assertEquals(ExternalKind.OTHER, kind("Rimborso commissioni", 200))
        assertEquals(ExternalKind.OTHER, kind("Storno pagamento", 1000))
        assertEquals(ExternalKind.OTHER, kind("Accredito imposta", 100))
        assertEquals(ExternalKind.OTHER, kind("Giroconto", 0))
        assertEquals("accents and case do not matter", ExternalKind.WITHDRAWAL, kind("PRELIEVO CONTANTE", -100))
    }

    // ---- csv -----------------------------------------------------------------------------------------------

    @Test fun italianSemicolonCsvWithQuotesAndThousandsSeparators() {
        val text = """
            Data inizio periodo:;01/03/2026
            Data fine periodo:;28/03/2026

            Data;Operazione;Dettagli;Conto o carta;Contabilizzazione;Categoria;Valuta;Importo
            27/03/2026;Pagamento POS;"Negozio ""Da Mario""; Roma";Carta 1234;CONTABILIZZATO;Alimentari;Euro;"-1.234,56"
            26/03/2026;Bonifico in entrata;Stipendio;Conto;CONTABILIZZATO;Entrate;Euro;1.800,00
            25/03/2026;Commissioni;Canone;Conto;CONTABILIZZATO;Banca;Euro;-0,50
            24/03/2026;Pagamento POS;Bar;Carta 1234;NON CONTABILIZZATO;Bar;Euro;-1,50
        """.trimIndent()
        val batch = parse(text.toByteArray(Charsets.UTF_8), "movimenti.csv")

        assertEquals(listOf(-123_456L, 180_000L, -50L), batch.movements.map { it.signedCents })
        assertEquals("Negozio \"Da Mario\"; Roma", batch.movements[0].description)
        assertEquals(d(1), batch.coverageFrom)
        assertEquals(d(28), batch.coverageTo)
        assertTrue(batch.warnings.any { it.startsWith("1 movimento non contabilizzato") })
    }

    @Test fun commaCsvWithAccreditiAndAddebiti() {
        val text = "Data contabile,Data valuta,Descrizione,Accrediti,Addebiti,Mezzo\n" +
            "27/03/2026,28/03/2026,Bonifico,\"1.234,56\",,Bonifico\n" +
            "26/03/2026,26/03/2026,Pagamento POS,,\"12,00\",Carta\n"
        val batch = parse(text.toByteArray(Charsets.UTF_8), "movimenti.csv")

        assertEquals(listOf(d(27), d(26)), batch.movements.map { it.date })
        assertEquals(listOf(123_456L, -1_200L), batch.movements.map { it.signedCents })
    }

    @Test fun windows1252CsvKeepsItsAccents() {
        val text = "Data;Descrizione;Importo\n27/03/2026;Caffè e cornetto;-1,80\n"
        val batch = parse(text.toByteArray(java.nio.charset.Charset.forName("windows-1252")), "movimenti.csv")
        assertEquals("Caffè e cornetto", batch.movements.single().description)
    }

    // ---- unrecognized input --------------------------------------------------------------------------------

    private fun failure(bytes: ByteArray, name: String = "file.xlsx") =
        assertThrows(IllegalArgumentException::class.java) { parse(bytes, name) }.message!!

    @Test fun unrecognizedFilesFailInItalianWithoutGuessing() {
        assertTrue(failure(XlsxFixture.build(FixtureSheet("S", listOf(row("Nome", "Cognome"), row("Mario", "Rossi"))))).contains("isybank"))
        assertTrue("a date but no amount column", failure(XlsxFixture.build(FixtureSheet("S", listOf(row("Data", "Descrizione"), row(d(1), "x"))))).contains("intestazione"))
        assertTrue("credits without debits", failure(XlsxFixture.build(FixtureSheet("S", listOf(row("Data", "Accrediti"), row(d(1), 1.0))))).contains("intestazione"))
        assertTrue(failure("niente da vedere qui\nsolo testo".toByteArray(), "x.csv").contains("isybank"))
        assertTrue(failure(ByteArray(0)).contains("vuoto"))
        assertTrue(failure(ByteArray(64) { (it * 7 + 3).toByte() }, "x.xlsx").isNotBlank())
    }

    @Test fun legacyXlsPdfAndWebPagesGetSpecificMessages() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0, 0)
        val xls = failure(ole, "movimenti.xls")
        assertTrue(xls, xls.contains(".xls") && xls.contains(".xlsx") && xls.contains("CSV"))
        assertTrue(failure("%PDF-1.7 ...".toByteArray(), "movimenti.pdf").contains("PDF"))
        assertTrue(failure("<html><table><tr><td>Data</td></tr></table></html>".toByteArray(), "movimenti.xls").contains("pagina web"))
    }

    @Test fun oversizedInputIsRefusedBeforeParsing() {
        val error = failure(ByteArray(IsybankParser.MAX_FILE_BYTES + 1))
        assertTrue(error, error.contains("10 MB"))
    }
}
