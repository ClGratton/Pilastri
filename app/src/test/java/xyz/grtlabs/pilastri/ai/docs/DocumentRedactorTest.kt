package xyz.grtlabs.pilastri.ai.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic values only: no real person, employer, account or fiscal code. */
class DocumentRedactorTest {
    private val payslip = """
        CEDOLINO DI PAGA - ottobre 2026
        Ente: Azienda Sintetica Spa    Partita IVA 01234567890
        Cognome e Nome: ROSSI MARIO
        Matricola: 0012345
        Codice fiscale: RSSMRA85T10A562S
        IBAN accredito: IT60 X054 2811 1010 0000 0123 456
        Retribuzione lorda 2.345,67    Trattenute 345,10    Netto 2.000,57
        Contatti: mario.rossi@example.test  tel. 333 1234567  uff. 06 12345678
        Data 2026-10-27
        Firma del dipendente Mario Rossi
    """.trimIndent()

    @Test
    fun removesEveryPersonalValueAndKeepsTheFigures() {
        val out = DocumentRedactor.redact(payslip).text
        listOf("ROSSI", "MARIO", "Rossi", "0012345", "RSSMRA85T10A562S", "IT60", "0542811101", "mario.rossi@example.test", "333 1234567", "06 12345678", "01234567890")
            .forEach { assertFalse("«$it» survived: $out", out.contains(it, ignoreCase = true)) }
        // The figures the agent needs are untouched.
        listOf("2.345,67", "345,10", "2.000,57", "2026-10-27", "ottobre 2026", "CEDOLINO DI PAGA").forEach { assertTrue("«$it» lost: $out", out.contains(it)) }
        assertTrue(out.contains("[CF]") && out.contains("[IBAN]") && out.contains("[MATRICOLA]") && out.contains("[NOME]") && out.contains("[EMAIL]") && out.contains("[TELEFONO]"))
    }

    @Test
    fun countsEachTypedPlaceholder() {
        val counts = DocumentRedactor.redact(payslip).counts
        assertEquals(1, counts["CF"]); assertEquals(1, counts["IBAN"]); assertEquals(1, counts["MATRICOLA"]); assertEquals(1, counts["EMAIL"])
        assertEquals(2, counts["TELEFONO"])
        assertTrue((counts["NOME"] ?: 0) >= 2) // the labelled field and the signature
    }

    @Test
    fun codiceFiscaleInEveryPosition() {
        val out = DocumentRedactor.redact("Pagina 1 RSSMRA85T10A562S in testa.\nIn riga: x RSSMRA85T10A562S, e (rssmra85t10a562s) minuscolo.\nOmocodia: RSSMRA85T10A56LS fine").text
        assertFalse(Regex("(?i)rssmra85t10a56").containsMatchIn(out))
        assertEquals(4, Regex(Regex.escape("[CF]")).findAll(out).count())
    }

    @Test
    fun ibanCompactSpacedAndForeign() {
        val out = DocumentRedactor.redact("a IT60X0542811101000000123456 b\nIT60 X054 2811 1010 0000 0123 456\nDE89 3704 0044 0532 0130 00 c").text
        assertEquals(3, Regex(Regex.escape("[IBAN]")).findAll(out).count())
        assertFalse(out.contains("0542811101"))
        assertFalse(out.contains("0532"))
    }

    @Test
    fun matricolaOnTheNextLine() {
        val out = DocumentRedactor.redact("Matricola\n  7654321\nQualifica Impiegato").text
        assertFalse(out.contains("7654321"))
        assertTrue(out.contains("Qualifica Impiegato"))
    }

    @Test
    fun nameRunStopsAtTheNextLabel() {
        val out = DocumentRedactor.redact("Cognome e Nome: De Luca Anna Rosa Matricola 12345\nResidenza Roma").text
        assertFalse(out.contains("Luca")); assertFalse(out.contains("Anna")); assertFalse(out.contains("Rosa"))
        assertTrue(out.contains("Residenza Roma")) // the domicile is not a name: the regional surcharge needs it
        assertTrue(out.contains("Matricola"))
    }

    @Test
    fun profileNameIsMaskedEverywhereEvenWithoutALabel() {
        val out = DocumentRedactor.redact("Intestato a ANNA BIANCHI.\nCopia per Bianchi Anna, grazie. Anna", knownNames = listOf("Anna Bianchi")).text
        assertFalse(out.contains("ANNA", ignoreCase = true)); assertFalse(out.contains("BIANCHI", ignoreCase = true))
    }

    @Test
    fun proseWordsAndAmountsAreNotMasked() {
        val text = "Il nome del file e il lavoratore dipendente privato. Importo 1.234,56 euro il 03/2026, codice 0541-77."
        assertEquals(text, DocumentRedactor.redact(text).text)
    }

    @Test
    fun longDigitRunsBecomeNumero() {
        val out = DocumentRedactor.redact("Pos. INPS 123456789012 e conto 98765432109").text
        assertFalse(out.contains("123456789012")); assertFalse(out.contains("98765432109"))
    }
}
