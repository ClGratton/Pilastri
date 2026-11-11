package xyz.grtlabs.pilastri.ui.setup

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.SeveranceRegime

/** Synthetic values only (invented document labels and a fake SHA-256). */
class SetupProfileTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val sha = "c".repeat(64)
    private val label = "cedolino set 2026"
    private fun id(k: PositionKind) = "pos-${k.name.lowercase()}"

    private fun read(kind: PositionKind = PositionKind.DIPENDENTE_PUBBLICO, gestione: Gestione? = null, severance: SeveranceRegime? = null) =
        SetupDocReader.Result.Read(setOf(kind), gestione, severance, label, sha)

    private fun afterDocument(gestione: Gestione? = null, severance: SeveranceRegime? = SeveranceRegime.TFS) =
        SetupRules.applyDocument(SetupModel(), read(gestione = gestione, severance = severance))

    @Test fun aDocumentPreSelectsTheChipAndKeepsItsSource() {
        val m = afterDocument()
        assertEquals(listOf(PositionKind.DIPENDENTE_PUBBLICO), m.positions)
        assertEquals(mapOf(PositionKind.DIPENDENTE_PUBBLICO to sha), m.positionSources)
        assertEquals(listOf(SetupDoc(label, sha, null, SeveranceRegime.TFS)), m.docs)
        assertEquals(label, SetupRules.docOf(m, PositionKind.DIPENDENTE_PUBBLICO)?.label)
    }

    @Test fun confirmAllIsTrueOnlyForRowsCopiedVerbatimFromDocuments() {
        assertTrue(SetupRules.confirmAll(afterDocument()))
        // A declared chip and no document: the plain Continua.
        assertFalse(SetupRules.confirmAll(SetupRules.togglePosition(SetupModel(), PositionKind.DIPENDENTE_PUBBLICO)))
        // Nothing chosen: no rows to confirm.
        assertFalse(SetupRules.confirmAll(SetupModel()))
    }

    @Test fun declaringAChipAfterADocumentEndsConfirmAll() {
        val m = SetupRules.togglePosition(afterDocument(), PositionKind.AUTONOMO_FORFETTARIO)
        assertFalse(SetupRules.confirmAll(m))
        val tags = SetupRules.profileRows(m).filter { it.label == "Posizione" }.associate { it.value to it.tag }
        assertEquals(label, tags["Dipendente pubblico"])
        assertEquals("dichiarato", tags["Partita IVA forfettaria"])
        // A declared gestione is also the person's own row.
        val withGestione = SetupRules.setGestione(m, PositionKind.AUTONOMO_FORFETTARIO, Gestione.ARTIGIANI)
        assertFalse(SetupRules.confirmAll(withGestione))
    }

    @Test fun togglePositionDropsTheTogglesKindsSource() {
        val read = afterDocument()
        val off = SetupRules.togglePosition(read, PositionKind.DIPENDENTE_PUBBLICO)
        assertTrue(off.positions.isEmpty())
        assertTrue(off.positionSources.isEmpty())
        // Choosing it again is the person's own declaration: the chip is back, the document is not its source.
        val on = SetupRules.togglePosition(off, PositionKind.DIPENDENTE_PUBBLICO)
        assertEquals(listOf(PositionKind.DIPENDENTE_PUBBLICO), on.positions)
        assertNull(SetupRules.docOf(on, PositionKind.DIPENDENTE_PUBBLICO))
        assertFalse(SetupRules.confirmAll(on))
        assertEquals("dichiarato", SetupRules.profileRows(on).first { it.label == "Posizione" }.tag)
        // Toggling another kind leaves this one's source alone.
        assertEquals(mapOf(PositionKind.DIPENDENTE_PUBBLICO to sha), SetupRules.togglePosition(read, PositionKind.STUDENTE).positionSources)
    }

    @Test fun documentRowsCarryTheDocumentAndWhatSetupCannotStoreIsSkipped() {
        val rows = SetupRules.profileRows(afterDocument())
        fun row(l: String) = rows.first { it.label == l }
        assertEquals(ProfileRow("Posizione", "Dipendente pubblico", label, RowTone.SOURCE), row("Posizione"))
        assertEquals(ProfileRow("Fine rapporto", "TFS", label, RowTone.SOURCE), row("Fine rapporto"))
        for (l in listOf("Fondo pensione", "Stipendio netto", "Addizionale comunale")) assertEquals(ProfileRow(l, SetupRules.ND, "saltato", RowTone.WARN), row(l))
    }

    @Test fun withoutADocumentTheRowsAreAsBefore() {
        val rows = SetupRules.profileRows(SetupRules.togglePosition(SetupModel(), PositionKind.DIPENDENTE_PUBBLICO))
        assertEquals(ProfileRow("Fine rapporto", SetupRules.ND, null, RowTone.WARN), rows.first { it.label == "Fine rapporto" })
        assertEquals(ProfileRow("Fondo pensione", SetupRules.ND, null, RowTone.WARN), rows.first { it.label == "Fondo pensione" })
        assertTrue(rows.none { it.label == "Stipendio netto" || it.label == "Addizionale comunale" })
    }

    @Test fun anUnstatedFineRapportoIsSkippedNotInvented() {
        val rows = SetupRules.profileRows(afterDocument(severance = null))
        assertEquals(ProfileRow("Fine rapporto", SetupRules.ND, "saltato", RowTone.WARN), rows.first { it.label == "Fine rapporto" })
    }

    @Test fun aDocumentGestioneIsAGestioneRowWithItsSource() {
        val rows = SetupRules.profileRows(afterDocument(gestione = Gestione.CPDEL))
        assertEquals(ProfileRow("Gestione · Dipendente pubblico", "CPDEL", label, RowTone.SOURCE), rows.first { it.label.startsWith("Gestione") })
    }

    @Test fun positionsToWriteWritesTheDocumentsSourcedValues() {
        val p = SetupRules.positionsToWrite(afterDocument(gestione = Gestione.CPDEL), today, ::id).single()
        assertEquals(Origin.DOCUMENTO, p.label.origin)
        assertEquals("Dipendente pubblico", p.label.value)
        assertEquals(sha, p.label.docId)
        assertEquals(label, p.label.fieldLabel)
        assertEquals(today, p.label.asOf)
        assertEquals(Origin.DOCUMENTO, p.gestione.origin)
        assertEquals(Gestione.CPDEL, p.gestione.value?.known)
        assertEquals(sha, p.gestione.docId)
        assertEquals(Origin.DOCUMENTO, p.severanceRegime.origin)
        assertEquals(SeveranceRegime.TFS, p.severanceRegime.value?.known)
        assertEquals(sha, p.severanceRegime.docId)
        // The reader does not read who pays the income: it stays ND.
        assertEquals(Origin.ND, p.incomeSource.origin)
        assertNull(p.incomeSource.value)
    }

    @Test fun aDeclaredPositionIsStillWrittenAsDeclaredAndAToggledOneLosesTheDocument() {
        val declared = SetupRules.positionsToWrite(SetupRules.togglePosition(SetupModel(), PositionKind.DIPENDENTE_PUBBLICO), today, ::id).single()
        assertEquals(Origin.DICHIARATO, declared.label.origin)
        assertNull(declared.label.docId)
        assertEquals(Origin.ND, declared.severanceRegime.origin)
        // Off and on again after the document: the person's own declaration, no document value is written.
        val again = SetupRules.togglePosition(SetupRules.togglePosition(afterDocument(), PositionKind.DIPENDENTE_PUBBLICO), PositionKind.DIPENDENTE_PUBBLICO)
        val written = SetupRules.positionsToWrite(again, today, ::id).single()
        assertEquals(Origin.DICHIARATO, written.label.origin)
        assertEquals(Origin.ND, written.severanceRegime.origin)
    }

    @Test fun aPrivateEmployeeNeverGetsAGestioneFromAnotherKindsDocument() {
        // The document states a gestione and a regime for ITS position only.
        val m = SetupRules.togglePosition(afterDocument(gestione = Gestione.CPDEL), PositionKind.DIPENDENTE_PRIVATO)
        val written = SetupRules.positionsToWrite(m, today, ::id).associateBy { it.kind.known }
        assertEquals(Origin.DOCUMENTO, written.getValue(PositionKind.DIPENDENTE_PUBBLICO).gestione.origin)
        assertEquals(Origin.ND, written.getValue(PositionKind.DIPENDENTE_PRIVATO).gestione.origin)
        assertEquals(Origin.DICHIARATO, written.getValue(PositionKind.DIPENDENTE_PRIVATO).label.origin)
        assertNotNull(written[PositionKind.DIPENDENTE_PRIVATO])
    }

    @Test fun dopoDropsWhatTheDocumentSelected() {
        val skipped = SetupRules.skipPositions(afterDocument())
        assertTrue(skipped.positions.isEmpty() && skipped.positionSources.isEmpty() && skipped.docs.isEmpty())
        assertFalse(SetupRules.confirmAll(skipped))
    }

    @Test fun theSetupCodecNeverSavesTheDocuments() {
        val restored = SetupCodec.decode(SetupCodec.encode(afterDocument(gestione = Gestione.CPDEL)))
        assertEquals(listOf(PositionKind.DIPENDENTE_PUBBLICO), restored.positions)
        assertTrue(restored.docs.isEmpty() && restored.positionSources.isEmpty())
        assertFalse(SetupRules.confirmAll(restored))
        assertFalse(SetupCodec.encode(afterDocument()).contains(sha))
    }
}
