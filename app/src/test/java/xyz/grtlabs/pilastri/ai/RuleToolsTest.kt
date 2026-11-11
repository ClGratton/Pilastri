package xyz.grtlabs.pilastri.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.PensionFund
import xyz.grtlabs.pilastri.data.RulesCodec
import xyz.grtlabs.pilastri.data.Sourced
import xyz.grtlabs.pilastri.domain.rules.EffectiveRules
import xyz.grtlabs.pilastri.domain.rules.RulesHistory
import xyz.grtlabs.pilastri.domain.rules.Shipped
import xyz.grtlabs.pilastri.domain.rules.ShippedPack
import xyz.grtlabs.pilastri.domain.rules.ShippedRules

/** Synthetic sources and values only. */
class RuleToolsTest {
    companion object {
        @BeforeClass @JvmStatic fun install() { ShippedRules.install(ShippedPack(Shipped.packJson, Shipped.snapshotJson)) }
    }

    private val base = AiFixtures.archive()

    private fun failure(name: String, json: String, archive: Archive = base): String =
        (AiFixtures.run(name, json, archive) as? AiToolResult.Failure)?.message ?: error("Atteso Failure per $name: ${AiFixtures.run(name, json, archive)}")
    private val text = "Testo sintetico di prova. Il limite annuo è di 5.400 euro."

    private fun lawArgs(url: String = "https://www.gazzettaufficiale.it/eli/id/sintetico/1", quote: String = "Il limite annuo è di 5.400 euro.", body: String = text) =
        JSONObject().put("riga_id", "pc.deduction.ceiling").put("valore", "5400").put("valida_dal", "2026-01-01").put("fonte_url", url)
            .put("citazione", quote).put("testo_fonte", body).put("verificato_il", "2026-10-27").put("etichetta", "Tetto deduzione").toString()

    @Test fun aLawProposalIsATier3RowThatNeverAppliesOnItsOwn() {
        val p = AiFixtures.proposal("proponi_regola", lawArgs())
        assertEquals(AiChangeKind.RULE_LAW_CHANGE, p.change.kind)
        assertTrue(AiPolicy.isTier3(p.change.kind) && AiPolicy.requiresTap(p.change.kind))
        val row = p.change.rule!!
        assertEquals("5.300,00 €" to "5.400,00 €", row.before to row.after)
        assertTrue(row.verified && row.confirmable)
        assertEquals("Il limite annuo è di 5.400 euro.", row.quote)
        assertTrue(row.outputs.any { it.card == "Deduzione" && it.label == "tetto annuo" })
        // Even a single untainted write waits: Tier 3 is never Tier 1 or 2.
        val host = AiFixtures.Host(base)
        val router = AiWriteRouter(host.archive, host.commit, "Anthropic", AiTurnState(), AiFixtures.clock, true, AiWriteQueue(), listOf(0L))
        val routed = AiFixtures.blocking { router.write("proponi_regola", JSONObject(lawArgs()), "w1", batch = false) }
        assertFalse(routed.text, routed.isError)
        assertTrue(router.applied.isEmpty())
        assertNotNull(router.pending.single().rule)
        assertTrue(RulesCodec.decode(host.archive.rulesData).versions.isEmpty())
        // A row is never merged into another card.
        try { router.pending.single().mergedWith(router.pending.single()); fail() } catch (_: IllegalArgumentException) {}
        // Confirming writes one version; undo re-points to the parent.
        val confirmed = p.change.applyTo(base)
        assertEquals(1, RulesCodec.decode(confirmed.rulesData).versions.size)
        assertEquals("assistente · regole", confirmed.audit.last().kind)
        val undone = p.change.undoFrom(confirmed)
        assertNull(EffectiveRules.headId(RulesCodec.decode(undone.rulesData)))
        assertEquals(listOf("UNDONE", "CONFIRMED"), RulesHistory.lines(undone).map { it.kind.name })
    }

    @Test fun aNonPrimarySourceProposesARowThatCannotBeConfirmed() {
        val p = AiFixtures.proposal("proponi_regola", lawArgs(url = "https://www.example.org/articolo"))
        val row = p.change.rule!!
        assertFalse(row.confirmable)
        assertFalse(row.verified)
        try { p.change.applyTo(base); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("non confermabile")) }
    }

    @Test fun badEvidenceIsRefusedWithAClearMessage() {
        assertTrue(failure("proponi_regola", lawArgs(quote = "limite di 5400 euro")).contains("letterale"))
        assertTrue(failure("proponi_regola", lawArgs(body = "$text IBAN ${AiFixtures.IBAN_TEXT}")).contains("dati personali"))
        assertTrue(failure("proponi_regola", lawArgs(url = "http://www.gazzettaufficiale.it/x")).contains("https"))
        assertTrue(failure("proponi_regola", lawArgs().replace("pc.deduction.ceiling", "nuova.riga")).contains("sconosciuta"))
    }

    @Test fun personalValuesOnlyFillDeclaredSlotsAndScenarioIsTier1() {
        assertTrue(failure("proponi_parametro_personale", """{"parametro":"irpef.bracket.1.rate","valore":"0.1","origine":"DICHIARATO"}""").contains("riga di legge"))
        assertTrue(failure("proponi_parametro_personale", """{"parametro":"quota_tfr","valore":"0.5","origine":"DICHIARATO"}""").contains("non è un parametro personale"))
        val p = AiFixtures.proposal("proponi_parametro_personale", """{"parametro":"domicilio_regione","valore":"Regione sintetica","origine":"DICHIARATO"}""")
        assertEquals(AiChangeKind.RULE_PERSONAL_CHANGE, p.change.kind)
        assertEquals("dichiarato", p.change.rule!!.origin)
        val s = AiFixtures.proposal("imposta_scenario", """{"parametro":"domicilio_regione","valore":"Regione futura"}""")
        assertEquals(AiChangeKind.SET_SCENARIO_VALUE, s.change.kind)
        assertNull(s.change.rule)
        assertFalse(AiPolicy.requiresTap(s.change.kind))
        val withScenario = s.change.applyTo(base)
        assertTrue(EffectiveRules.build(ShippedRules.current()!!, withScenario.rulesData).personal.isEmpty())
        assertTrue(RulesCodec.decode(s.change.undoFrom(withScenario).rulesData).params.isEmpty())
    }

    @Test fun readRulesListsRowsAndSlotsWithoutPersonalValues() {
        val r = AiFixtures.read("leggi_regole", """{"id":"irpef.gross"}""")
        val row = r.getJSONArray("righe").getJSONObject(0)
        assertEquals("irpef.gross", row.getString("id"))
        assertTrue(row.getJSONArray("passi").length() > 0)
        assertTrue(r.getJSONArray("parametri_personali").toString().contains("domicilio_regione"))
    }

    @Test fun accountsReferToFundsByStringIdAndUnknownIdsAreRefused() {
        assertTrue(failure("crea_conto", """{"nome":"Fondo X","pilastro":4,"tipo":"PENSION_FUND","fondo_id":"fund:missing"}""").contains("Fondo non trovato: fund:missing"))
        assertTrue(failure("crea_conto", """{"nome":"Fondo X","pilastro":4,"tipo":"ESPERO"}""").contains("Tipo di conto non valido"))
        assertTrue(failure("crea_conto", """{"nome":"Conto X","pilastro":1,"fondo_id":"fund:s"}""").contains("PENSION_FUND"))
        val withFund: Archive = base.copy(funds = listOf(PensionFund("fund:s", Sourced.declared("Fondo sintetico"))))
        val created = AiFixtures.proposal("crea_conto", """{"nome":"Fondo X","pilastro":4,"tipo":"PENSION_FUND","fondo_id":"fund:s"}""", withFund).change.applyTo(withFund)
        assertEquals("fund:s", created.accounts.last().fundId)
        assertEquals(1, created.funds.size)
    }
}
