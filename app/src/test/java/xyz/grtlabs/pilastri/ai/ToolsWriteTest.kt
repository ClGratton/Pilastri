package xyz.grtlabs.pilastri.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveCodec
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.PlanStatus
import xyz.grtlabs.pilastri.data.correctContribution
import xyz.grtlabs.pilastri.data.correctTrade
import xyz.grtlabs.pilastri.data.correctTransfer
import xyz.grtlabs.pilastri.data.correctValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.Money
import java.time.LocalDate

class ToolsWriteTest {
    private val base = AiFixtures.archive()
    private val at = AiFixtures.fixedNow.toString()

    private fun proposal(name: String, json: String, archive: Archive = base, callId: String = "call-1") = AiFixtures.proposal(name, json, archive, callId)
    private fun failure(name: String, json: String, archive: Archive = base): String =
        (AiFixtures.run(name, json, archive) as? AiToolResult.Failure)?.message ?: error("Atteso Failure per $name $json")

    /** The archive after undo equals the original except for the two audit entries (apply and undo). */
    private fun assertUndoRestores(change: AiChange, original: Archive): Archive {
        val applied = change.applyTo(original)
        ArchiveCodec.encode(applied) // the result must be a valid archive
        assertEquals(original.audit.size + 1, applied.audit.size)
        val entry = applied.audit.last()
        assertEquals("assistente", entry.kind)
        assertEquals(change.summary.take(2000), entry.previous)
        val undone = change.undoFrom(applied)
        ArchiveCodec.encode(undone)
        assertEquals("assistente · annullo", undone.audit.last().kind)
        assertEquals(original, undone.copy(audit = original.audit))
        return applied
    }

    private fun assertModified(block: () -> Unit) {
        val e = assertThrows(AiAlreadyModifiedException::class.java) { block() }
        assertTrue(e.message!!, e.message!!.contains("già modificat"))
    }

    // ---- registra_movimento ----

    private val eventArgs = """{"pilastro":4,"tipo":"PERSONAL_CONTRIBUTION","data":"2026-04-02","importo_eur":"150.00","conto_id":"acc-fund"}"""

    @Test fun eventIsCreatedWithAssistantSourceAndIdempotentId() {
        val p = proposal("registra_movimento", eventArgs)
        assertEquals(AiChangeKind.ADD_EVENT, p.change.kind)
        val applied = assertUndoRestores(p.change, base)
        val event = applied.events.last()
        assertEquals("ai:${p.change.id}", event.id)
        assertEquals(EventType.PERSONAL_CONTRIBUTION, event.type)
        assertEquals(15_000L, event.amount.cents)
        assertEquals(Confidence.USER_REPORTED, event.confidence)
        assertEquals("acc-fund", event.accountId)
        assertEquals(LocalDate.of(2026, 4, 2), event.date)
        assertEquals("Assistente · Anthropic · 2026-10-28T10:00:00Z", event.source)
        assertTrue(p.change.summary.startsWith("Aggiungi versamento personale di 150,00 €"))
        assertTrue(p.change.diff.isNotEmpty())
    }

    @Test fun repeatedCallsCannotDuplicateARecord() {
        val first = proposal("registra_movimento", eventArgs, callId = "call-A")
        val again = proposal("registra_movimento", eventArgs, callId = "call-A")
        assertEquals(first.change.id, again.change.id)
        val once = first.change.applyTo(base)
        val twice = again.change.applyTo(once)
        assertEquals(once, twice) // no second event, no second audit entry
        assertEquals(base.events.size + 1, twice.events.size)
        assertEquals(base.audit.size + 1, twice.audit.size)
        // A different tool call id is a different write.
        assertNotEquals(first.change.id, proposal("registra_movimento", eventArgs, callId = "call-B").change.id)
        // The key does not depend on JSON key order.
        val reordered = JSONObject("""{"conto_id":"acc-fund","importo_eur":"150.00","data":"2026-04-02","tipo":"PERSONAL_CONTRIBUTION","pilastro":4}""")
        assertEquals(idempotencyKey("call-A", "registra_movimento", JSONObject(eventArgs)), idempotencyKey("call-A", "registra_movimento", reordered))
        assertEquals(16, first.change.id.length)
    }

    @Test fun eventUndoRefusesWhenTheRecordWasCorrectedOrRemoved() {
        val p = proposal("registra_movimento", eventArgs)
        val applied = p.change.applyTo(base)
        val created = applied.events.last()
        val corrected = applied.correctContribution(created, created.copy(amount = Money(99)), at)
        assertModified { p.change.undoFrom(corrected) }
        assertModified { p.change.undoFrom(base) }
        // A repeated undo is refused as well.
        assertModified { p.change.undoFrom(p.change.undoFrom(applied)) }
    }

    @Test fun eventArgumentsAreValidatedInItalian() {
        fun bad(json: String, fragment: String) = assertTrue(failure("registra_movimento", json), failure("registra_movimento", json).contains(fragment))
        bad("""{"pilastro":1,"tipo":"EMPLOYER_CONTRIBUTION","data":"2026-04-02","importo_eur":"10"}""", "pilastro 4")
        bad("""{"pilastro":4,"tipo":"PERSONAL_CONTRIBUTION","data":"2999-01-01","importo_eur":"10"}""", "futura")
        bad("""{"pilastro":4,"tipo":"PERSONAL_CONTRIBUTION","data":"2026-04-02","importo_eur":"12.345"}""", "due decimali")
        bad("""{"pilastro":4,"tipo":"PERSONAL_CONTRIBUTION","data":"2026-04-02","importo_eur":"1.234,56"}""", "importo_eur")
        bad("""{"pilastro":4,"tipo":"PERSONAL_CONTRIBUTION","data":"2026-04-02","importo_eur":"-5"}""", "importo_eur")
        bad("""{"pilastro":4,"tipo":"PERSONAL_CONTRIBUTION","data":"2026-04-02","importo_eur":"0"}""", "maggiore di zero")
        bad("""{"pilastro":4,"tipo":"TRANSFER","data":"2026-04-02","importo_eur":"5"}""", "Tipo non valido")
        bad("""{"pilastro":7,"tipo":"FEE","data":"2026-04-02","importo_eur":"5"}""", "pilastro")
        bad("""{"pilastro":1,"tipo":"FEE","data":"2026-04-02","importo_eur":"5","conto_id":"acc-fund"}""", "appartiene al pilastro 4")
        bad("""{"pilastro":4,"tipo":"FEE","data":"2026-04-02","importo_eur":"5","conto_id":"nope"}""", "Conto non trovato")
        bad("""{"pilastro":4,"tipo":"FEE","data":"ieri","importo_eur":"5"}""", "Data non valida")
        // A JSON number and a decimal comma are both accepted for plain amounts.
        assertEquals(1_050L, (proposal("registra_movimento", """{"pilastro":1,"tipo":"FEE","data":"2026-04-02","importo_eur":10.5}""").change.applyTo(base)).events.last().amount.cents)
        assertEquals(1_050L, (proposal("registra_movimento", """{"pilastro":1,"tipo":"FEE","data":"2026-04-02","importo_eur":"10,50"}""").change.applyTo(base)).events.last().amount.cents)
    }

    // ---- valuations ----

    @Test fun pillarValuationAppliesUndoesAndNeverOverwrites() {
        val p = proposal("registra_valutazione_pilastro", """{"pilastro":2,"data":"2026-04-30","importo_eur":"1234.56"}""")
        assertEquals(AiChangeKind.ADD_VALUATION, p.change.kind)
        val applied = assertUndoRestores(p.change, base)
        val v = applied.valuations.last()
        assertEquals(123_456L, v.amount.cents)
        assertEquals(Confidence.USER_REPORTED, v.confidence)
        assertTrue(v.source.startsWith("Assistente · Anthropic · "))
        assertEquals(applied, p.change.applyTo(applied)) // idempotent
        assertTrue(failure("registra_valutazione_pilastro", """{"pilastro":4,"data":"2026-03-31","importo_eur":"1.00"}""").contains("non la sovrascrive"))
        // A different value for the same day appeared before the tap: refused, not overwritten.
        val clash = base.copy(valuations = base.valuations + v.copy(amount = Money(1)))
        assertThrows(IllegalArgumentException::class.java) { p.change.applyTo(clash) }
        assertModified { p.change.undoFrom(applied.correctValuation(v, v.copy(amount = Money(5)), at)) }
    }

    @Test fun accountValuationAppliesUndoesAndNeverOverwrites() {
        val p = proposal("registra_valutazione_conto", """{"conto_id":"acc-liq","data":"2026-04-30","importo_eur":"800.00"}""")
        assertEquals(AiChangeKind.ADD_VALUATION, p.change.kind)
        val applied = assertUndoRestores(p.change, base)
        assertEquals(80_000L, applied.accountValuations.last().amount.cents)
        assertEquals(applied, p.change.applyTo(applied))
        assertTrue(failure("registra_valutazione_conto", """{"conto_id":"acc-fund","data":"2026-03-31","importo_eur":"1.00"}""").contains("non la sovrascrive"))
        assertTrue(failure("registra_valutazione_conto", """{"conto_id":"zzz","data":"2026-04-30","importo_eur":"1.00"}""").contains("Conto non trovato"))
        val edited = applied.copy(accountValuations = applied.accountValuations.map { if (it.accountId == "acc-liq") it.copy(amount = Money(1)) else it })
        assertModified { p.change.undoFrom(edited) }
    }

    // ---- crea_conto ----

    @Test fun accountIsCreatedAndOnlyRemovedWhileUnused() {
        val p = proposal("crea_conto", """{"nome":"  Conto   risparmio ","pilastro":2}""")
        assertEquals(AiChangeKind.ADD_ACCOUNT, p.change.kind)
        val applied = assertUndoRestores(p.change, base)
        val account = applied.accounts.last()
        assertEquals("ai:${p.change.id}", account.id)
        assertEquals("Conto risparmio", account.name)
        assertNull(account.type)
        assertEquals(applied, p.change.applyTo(applied))
        val used = applied.copy(events = applied.events + AiFixtures.event("e9", "2026-04-01", 2, EventType.FEE, 100, accountId = account.id))
        assertModified { p.change.undoFrom(used) }
        assertTrue(failure("crea_conto", """{"nome":"fondo SINTETICO","pilastro":4}""").contains("Esiste già"))
        assertTrue(failure("crea_conto", """{"nome":"X","pilastro":1,"tipo":"ESPERO"}""").isNotBlank())
        assertEquals(xyz.grtlabs.pilastri.data.AccountType.PENSION_FUND,
            proposal("crea_conto", """{"nome":"Fondo 2","pilastro":4,"tipo":"PENSION_FUND"}""").change.applyTo(base).accounts.last().type)
    }

    // ---- aggiungi_voce_piano ----

    @Test fun planItemIsAddedAndRemovedOnlyWhileUnchanged() {
        val p = proposal("aggiungi_voce_piano", """{"mese":"2026-05","etichetta":"Affitto","tipo":"EXPENSE","stato":"ACTUAL","importo_eur":"650.00"}""")
        assertEquals(AiChangeKind.ADD_PLAN_ITEM, p.change.kind)
        val applied = assertUndoRestores(p.change, base)
        val item = applied.plan.last()
        assertEquals(PlanStatus.ACTUAL, item.status)
        assertEquals(65_000L, item.amount.cents)
        assertEquals("ai:${p.change.id}", item.id)
        assertEquals(applied, p.change.applyTo(applied))
        assertModified { p.change.undoFrom(applied.copy(plan = applied.plan.map { if (it.id == item.id) it.copy(label = "Altro") else it })) }
        assertTrue(failure("aggiungi_voce_piano", """{"mese":"2999-05","etichetta":"x","tipo":"EXPENSE","stato":"SCENARIO","importo_eur":"1"}""").contains("2085"))
        assertTrue(failure("aggiungi_voce_piano", """{"mese":"2085-05","etichetta":"x","tipo":"EXPENSE","stato":"ACTUAL","importo_eur":"1"}""").contains("futuro"))
        assertTrue(failure("aggiungi_voce_piano", """{"mese":"2026-05","etichetta":"x","tipo":"EXPENSE","importo_eur":"1"}""").contains("Stato"))
        assertTrue(proposal("aggiungi_voce_piano", """{"mese":"2085-05","etichetta":"x","tipo":"INCOME","stato":"SCENARIO","importo_eur":"1"}""").change.applyTo(base).plan.last().status == PlanStatus.SCENARIO)
    }

    // ---- imported movements ----

    private fun ref(movement: xyz.grtlabs.pilastri.data.ExternalMovement) = movementRef(movement.id)

    @Test fun classifyAsEventCreatesTheLedgerRecordAndUndoReopensTheMovement() {
        val p = proposal("classifica_importato", """{"rif":"${ref(AiFixtures.deposit)}","decisione":"PERSONAL_CONTRIBUTION"}""")
        assertEquals(AiChangeKind.CLASSIFY_IMPORT, p.change.kind)
        assertFalse("The model-facing note must not carry provider text", p.modelNote.contains("Bonifico"))
        assertTrue(p.change.summary.contains("Bonifico in entrata"))
        assertEquals(1, p.change.sourceRows.size)
        val applied = base.let { b ->
            val result = p.change.applyTo(b)
            ArchiveCodec.encode(result)
            result
        }
        assertEquals(ExternalStatus.APPLIED, applied.externalMovements.first { it.id == AiFixtures.deposit.id }.status)
        val event = applied.events.first { it.id == "x:${AiFixtures.deposit.id}" }
        assertEquals(50_000L, event.amount.cents)
        assertEquals(4, event.pillar)
        assertEquals("assistente", applied.audit.last().kind)
        val undone = p.change.undoFrom(applied)
        ArchiveCodec.encode(undone)
        assertEquals(ExternalStatus.PENDING, undone.externalMovements.first { it.id == AiFixtures.deposit.id }.status)
        assertTrue(undone.events.none { it.id.startsWith("x:") })
        assertEquals(base.externalMovements, undone.externalMovements)
        assertModified { p.change.undoFrom(applied.correctContribution(event, event.copy(amount = Money(7)), at)) }
        // Applying twice is refused rather than duplicated.
        assertThrows(IllegalArgumentException::class.java) { p.change.applyTo(applied) }
    }

    @Test fun classifyChecksSignsAndTheNeedForAPendingMovement() {
        fun bad(rif: String, decision: String, fragment: String, extra: String = "") =
            assertTrue(failure("classifica_importato", """{"rif":"$rif","decisione":"$decision"$extra}""").contains(fragment))
        bad(ref(AiFixtures.deposit), "WITHDRAWAL", "segno")
        bad(ref(AiFixtures.card), "INCOME", "segno")
        bad(ref(AiFixtures.deposit), "BUY", "segno")
        bad(ref(AiFixtures.buy), "SELL", "segno")
        bad(ref(AiFixtures.ignored), "FEE", "già stato revisionato")
        bad("m00000000", "FEE", "non trovato")
        bad(ref(AiFixtures.deposit), "MAGIC", "Decisione non valida")
        bad(ref(AiFixtures.deposit), "TRANSFER", "Manca controparte")
    }

    @Test fun classifyAsTransferLinksBothMovementsAndUndoReopensBoth() {
        val p = proposal("classifica_importato",
            """{"rif":"${ref(AiFixtures.giro)}","decisione":"TRANSFER","controparte":"${ref(AiFixtures.deposit)}"}""")
        assertEquals(2, p.change.sourceRows.size)
        val applied = p.change.applyTo(base)
        ArchiveCodec.encode(applied)
        val transfer = applied.transfers.single()
        assertEquals(50_000L, transfer.amount.cents)
        assertEquals(AiFixtures.liquidity.id, transfer.fromAccountId)
        assertEquals(AiFixtures.broker.id, transfer.toAccountId)
        assertTrue(applied.events.none { it.type == EventType.PERSONAL_CONTRIBUTION && it.id.startsWith("x:") })
        val undone = p.change.undoFrom(applied)
        assertTrue(undone.transfers.isEmpty())
        assertEquals(base.externalMovements, undone.externalMovements)
        assertModified { p.change.undoFrom(applied.correctTransfer(transfer, transfer.copy(amount = Money(1)), at)) }
    }

    @Test fun classifyAsTradeCreatesAnInstrumentThatUndoRemovesAgain() {
        val p = proposal("classifica_importato", """{"rif":"${ref(AiFixtures.buy)}","decisione":"BUY"}""")
        val applied = p.change.applyTo(base)
        ArchiveCodec.encode(applied)
        val trade = applied.trades.single()
        assertEquals(20_100L, trade.grossAmount.cents)
        assertEquals(1, applied.instruments.size)
        val undone = p.change.undoFrom(applied)
        ArchiveCodec.encode(undone)
        assertTrue(undone.trades.isEmpty())
        assertTrue(undone.instruments.isEmpty())
        assertModified { p.change.undoFrom(applied.correctTrade(trade, trade.copy(fees = Money(5)), at)) }
    }

    @Test fun ignoreAndReopenAreInverseAndRefuseWhenChanged() {
        val ignore = proposal("ignora_importato", """{"rif":"${ref(AiFixtures.injected)}"}""")
        assertEquals(AiChangeKind.IGNORE_IMPORT, ignore.change.kind)
        val ignored = ignore.change.applyTo(base)
        assertEquals(ExternalStatus.IGNORED, ignored.externalMovements.first { it.id == AiFixtures.injected.id }.status)
        assertTrue(ignored.events.size == base.events.size)
        val restored = ignore.change.undoFrom(ignored)
        assertEquals(base.externalMovements, restored.externalMovements)
        val recategorized = ignored.copy(externalMovements = ignored.externalMovements.map { if (it.id == AiFixtures.injected.id) it.copy(category = "x") else it })
        assertModified { ignore.change.undoFrom(recategorized) }
        assertTrue(failure("ignora_importato", """{"rif":"${ref(AiFixtures.ignored)}"}""").contains("già stato revisionato"))

        val reopen = proposal("riapri_importato", """{"rif":"${ref(AiFixtures.ignored)}"}""")
        assertEquals(AiChangeKind.REOPEN_IMPORT, reopen.change.kind)
        val reopened = reopen.change.applyTo(base)
        assertEquals(ExternalStatus.PENDING, reopened.externalMovements.first { it.id == AiFixtures.ignored.id }.status)
        assertEquals(base.externalMovements, reopen.change.undoFrom(reopened).externalMovements)
        assertTrue(failure("riapri_importato", """{"rif":"${ref(AiFixtures.deposit)}"}""").contains("già da rivedere"))
        // A movement already booked in the ledger is not reopened by the assistant (that would delete a record).
        val booked = proposal("classifica_importato", """{"rif":"${ref(AiFixtures.deposit)}","decisione":"PERSONAL_CONTRIBUTION"}""").change.applyTo(base)
        assertTrue(failure("riapri_importato", """{"rif":"${ref(AiFixtures.deposit)}"}""", booked).contains("non rimuove registrazioni"))
    }

    @Test fun categoryChangeRestoresThePreviousValueAndRefusesWhenChanged() {
        val p = proposal("categorizza_movimenti", """{"assegnazioni":[{"rif":"${ref(AiFixtures.card)}","categoria":"Spesa quotidiana"}]}""")
        assertEquals(AiChangeKind.SET_IMPORT_CATEGORY, p.change.kind)
        val applied = assertUndoRestores(p.change, base)
        val moved = applied.externalMovements.first { it.id == AiFixtures.card.id }
        assertEquals(applied.planning.categories.single().id, moved.categoryId)
        assertEquals("Spesa quotidiana", applied.planning.categories.single().name)
        assertEquals("Alimentari", p.change.undoFrom(applied).externalMovements.first { it.id == AiFixtures.card.id }.category)
        val changed = applied.copy(externalMovements = applied.externalMovements.map { if (it.id == AiFixtures.card.id) it.copy(categoryId = null, categorySource = null) else it })
        assertModified { p.change.undoFrom(changed) }
        assertTrue(failure("categorizza_movimenti", """{"assegnazioni":[{"rif":"${ref(AiFixtures.card)}"}]}""").contains("manca la categoria"))
        assertTrue(failure("categorizza_movimenti", """{"assegnazioni":[{"rif":"${ref(AiFixtures.card)}","categoria":"${"x".repeat(61)}"}]}""").contains("60"))
    }

    // ---- registry ----

    @Test fun registryOffersNoToolForTierThreeActions() {
        val names = AiTools.all.map { it.name }.toSet()
        assertEquals(
            setOf("riepilogo_pilastri", "elenca_movimenti", "elenca_valutazioni", "elenca_conti", "elenca_importati", "elenca_piano", "elenca_indici",
                "registra_movimento", "registra_valutazione_pilastro", "registra_valutazione_conto", "crea_conto", "aggiungi_voce_piano",
                "classifica_importato", "ignora_importato", "riapri_importato", "categorizza_movimenti",
                "elenca_piani_rate", "rate_in_scadenza", "impegno_rate_mese", "elenca_categorie",
                "conferma_piano_rate", "ignora_piano_rate", "imposta_categoria_piano_rate", "segna_rata_pagata",
                // Council 4 c: rule tools only propose; their Tier 3 rows are confirmed by the user one by one
                "leggi_regole", "proponi_regola", "proponi_passi_regola", "proponi_parametro_personale", "imposta_scenario"),
            names,
        )
        val forbidden = listOf("attestazion", "performance", "ripristin", "restore", "chiave", "api", "sessione", "session", "maschera", "redaz", "privacy", "elimina", "cancella", "rimuovi")
        names.forEach { name -> forbidden.forEach { assertFalse("$name must not look like a Tier 3 or delete tool", name.contains(it)) } }
        assertTrue(AiTools.all.filter { it.name.startsWith("elenca_") || it.name == "riepilogo_pilastri" }.none { it.writes })
        assertTrue(AiTools.all.filter { it.writes }.all { it.name.startsWith("registra_") || it.name in setOf("crea_conto", "aggiungi_voce_piano", "classifica_importato", "ignora_importato", "riapri_importato", "categorizza_movimenti",
            "conferma_piano_rate", "ignora_piano_rate", "imposta_categoria_piano_rate", "segna_rata_pagata",
            "proponi_regola", "proponi_passi_regola", "proponi_parametro_personale", "imposta_scenario") })
    }

    @Test fun instructionBlockRendersEveryToolAsFencedJson() {
        val block = AiTools.renderInstructionBlock()
        assertTrue(block.contains("```json"))
        AiTools.all.forEach { tool ->
            assertTrue(tool.name, block.contains("### ${tool.name}"))
            assertTrue(tool.name, block.contains(tool.parameters.toString()))
        }
        assertTrue(block.contains("\"tool\""))
        assertEquals("fences must be balanced", 1, block.split("```").size % 2)
    }
}
