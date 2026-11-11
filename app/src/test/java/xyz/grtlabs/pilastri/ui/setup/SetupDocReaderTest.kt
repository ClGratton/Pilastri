package xyz.grtlabs.pilastri.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.SeveranceRegime

/** Synthetic document texts only: invented words that carry just the cues the reader looks for. */
class SetupDocReaderTest {
    private val sha = "b".repeat(64)

    private fun read(text: String, label: String? = null) = SetupDocReader.read(text, label, sha)
    private fun ok(text: String, label: String? = null) = read(text, label) as SetupDocReader.Result.Read

    @Test fun publicPayslipSelectsDipendentePubblico() {
        val r = ok("Cedolino ottobre 2026\nNoiPA\nMinistero dell'istruzione\nImporto di prova")
        assertEquals(setOf(PositionKind.DIPENDENTE_PUBBLICO), r.positions)
        assertEquals("cedolino ottobre 2026", r.label)
        assertEquals(sha, r.sha)
        assertNull(r.gestione)
        assertNull(r.severance)
    }

    @Test fun privatePayslipSelectsDipendentePrivato() {
        val r = ok("Busta paga novembre 2026\nCCNL Commercio\nTFR maturato nel mese")
        assertEquals(setOf(PositionKind.DIPENDENTE_PRIVATO), r.positions)
        assertEquals(SeveranceRegime.TFR, r.severance)
        assertEquals("cedolino novembre 2026", r.label)
    }

    @Test fun matricolaInpsIsAPrivateCue() {
        assertEquals(setOf(PositionKind.DIPENDENTE_PRIVATO), ok("Cedolino dicembre 2026\nMatricola INPS 000123").positions)
        assertEquals(setOf(PositionKind.DIPENDENTE_PRIVATO), ok("Cedolino dicembre 2026\nMatricola azienda INPS 000123").positions)
    }

    @Test fun aCuIsReadAndNamedByItsIncomeYear() {
        val r = ok("Certificazione Unica 2026 - Anno 2025\nMinistero di prova")
        assertEquals(setOf(PositionKind.DIPENDENTE_PUBBLICO), r.positions)
        assertEquals("CU 2025", r.label)
    }

    @Test fun noCueOrBothCuesLeaveThePositionUnset() {
        assertEquals(SetupDocReader.Result.Unreadable(SetupDocReader.NO_POSITION), read("Cedolino ottobre 2026\nMensile di prova senza altro"))
        // Public and private cues together are ambiguous: nothing is guessed.
        assertEquals(SetupDocReader.Result.Unreadable(SetupDocReader.NO_POSITION), read("Cedolino ottobre 2026\nNoiPA\nCCNL di prova"))
        // A bare "amministrazione" (an office in a private firm) is not a public cue.
        assertEquals(SetupDocReader.Result.Unreadable(SetupDocReader.NO_POSITION), read("Cedolino ottobre 2026\nUfficio amministrazione del personale"))
    }

    @Test fun unreadableIsOneLineAndNothingElse() {
        val r = read("Estratto conto di prova\nBanca Alfa\nSaldo di prova")
        assertTrue(r is SetupDocReader.Result.Unreadable)
        val line = (r as SetupDocReader.Result.Unreadable).line
        assertEquals(SetupDocReader.NOT_RECOGNISED, line)
        assertFalse(line.contains('\n'))
        assertFalse(SetupDocReader.NO_POSITION.contains('\n'))
    }

    @Test fun severanceIsSetOnlyWhenExactlyOneRegimeMatches() {
        assertEquals(SeveranceRegime.TFS, ok("Cedolino ottobre 2026\nNoiPA\nTrattamento di fine servizio").severance)
        assertEquals(SeveranceRegime.TFR, ok("Cedolino ottobre 2026\nNoiPA\nTrattamento di fine rapporto").severance)
        // Both regimes named: ambiguous, left unset, while the position (which does not depend on it) is still read.
        val both = ok("Cedolino ottobre 2026\nNoiPA\nTFR e TFS di prova")
        assertNull(both.severance)
        assertEquals(setOf(PositionKind.DIPENDENTE_PUBBLICO), both.positions)
        assertNull(ok("Cedolino ottobre 2026\nNoiPA").severance)
        // TFS is a public regime: on a private position it is contradictory and stays unset.
        assertNull(ok("Cedolino ottobre 2026\nCCNL di prova\nTFS di prova").severance)
    }

    @Test fun gestioneNeedsOneCodeConsistentWithThePosition() {
        assertEquals(Gestione.CPDEL, ok("Cedolino ottobre 2026\nNoiPA\nCassa CPDEL").gestione)
        assertEquals(Gestione.FPLD, ok("Cedolino ottobre 2026\nCCNL di prova\nFPLD").gestione)
        assertNull(ok("Cedolino ottobre 2026\nNoiPA\nFPLD").gestione)
        assertNull(ok("Cedolino ottobre 2026\nNoiPA\nCPDEL e CTPS").gestione)
        assertNull(ok("Cedolino ottobre 2026\nNoiPA").gestione)
    }

    @Test fun theGivenLabelNamesTheSourceAndFallsBackToTheTextsOwn() {
        assertEquals("cedolino set 2026", ok("Cedolino ottobre 2026\nNoiPA", label = "  cedolino set 2026 ").label)
        assertEquals("cedolino ottobre 2026", ok("Cedolino ottobre 2026\nNoiPA", label = "documento").label)
        assertEquals("cedolino ottobre 2026", ok("Cedolino ottobre 2026\nNoiPA", label = null).label)
    }
}
