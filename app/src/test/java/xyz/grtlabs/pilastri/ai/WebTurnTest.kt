package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Text-only replies go through the same policy path as API tool calls: tiers, the taint rule, idempotency and undo.
 * Fakes only: no page, no network.
 */
class WebTurnTest {
    private fun block(vararg ops: String) = "Risposta.\n\n```json\n{\"pilastri\": \"proposte-v1\", \"operazioni\": [${ops.joinToString(",")}]}\n```"

    private val planOp = """{"strumento": "aggiungi_voce_piano", "argomenti": {"mese": "2026-12", "etichetta": "Prova web", "tipo": "EXPENSE", "stato": "SCENARIO", "importo_eur": "12.34"}}"""
    private val eventOp = """{"strumento": "registra_movimento", "argomenti": {"pilastro": 4, "tipo": "PERSONAL_CONTRIBUTION", "data": "2026-04-02", "importo_eur": "150.00"}}"""

    private fun run(
        reply: String,
        host: AiFixtures.Host = AiFixtures.Host(AiFixtures.archive()),
        turn: AiTurnState = AiTurnState(),
        external: Boolean = false,
        forced: String? = null,
        queue: AiWriteQueue = AiWriteQueue(),
        accountPage: Boolean = false,
        conversation: AiConversationTaint? = null,
    ): AiRunResult = AiFixtures.blocking {
        AiWebTurns.process(WebReplyText(reply, external, accountPage), turn, host.archive, host.commit, "Claude (account)", queue, AiFixtures.clock, forced, listOf(0L),
            conversation = conversation)
    }

    @Test fun everyAccountPageReplyIsTaintedEvenWithoutTheDomFlag() {
        val host = AiFixtures.Host(AiFixtures.archive())
        // A Tier-1 proposal, nothing imported in the snapshot, and the page script saw no sources or tools.
        val reply = WebReplyText.fromAccountPage(block(planOp), external = false)
        assertTrue(reply.accountPage)
        val result = AiFixtures.blocking {
            AiWebTurns.process(reply, AiTurnState(), host.archive, host.commit, "Claude (account)", AiWriteQueue(), AiFixtures.clock, retryDelaysMs = listOf(0L))
        }
        assertTrue(result.tainted)
        assertTrue(result.applied.isEmpty())
        val pending = result.pending.single()
        assertTrue(pending.reviewNote!!.contains(AiWebTurns.ACCOUNT_PAGE_REASON))
        assertFalse(pending.reviewNote!!.contains("fonti esterne"))
        assertEquals(AiFixtures.archive().plan, host.archive.plan)
        // The DOM flag still adds its detail.
        val flagged = run(block(planOp), accountPage = true, external = true)
        assertTrue(flagged.pending.single().reviewNote!!.contains("fonti esterne"))
    }

    @Test fun aTaintedWebTurnKeepsLaterTurnsOfTheConversationTaintedUntilNuova() {
        val conversation = AiConversationTaint()
        val host = AiFixtures.Host(AiFixtures.archive())
        // Turn 1: the snapshot carried imported text.
        val first = run("Ecco le spese.", host, WebContext.build(host.archive, "categorizza le spese", null, AiFixtures.clock).turn, conversation = conversation)
        assertTrue(first.tainted)
        // Turn 2: a snapshot without imported rows; the page still holds turn 1, so a Tier-1 write needs the tap.
        val clean = WebContext.build(host.archive, "quanto ho?", null, AiFixtures.clock)
        assertFalse(clean.turn.tainted)
        val second = run(block(planOp), host, clean.turn, conversation = conversation)
        assertTrue(second.tainted)
        assertTrue(second.applied.isEmpty())
        assertTrue(second.pending.single().reviewNote!!.contains("richiesta precedente"))
        assertEquals(AiFixtures.archive().plan, host.archive.plan)
        // "Nuova".
        conversation.clear()
        val third = run(block(planOp), host, WebContext.build(host.archive, "quanto ho?", null, AiFixtures.clock).turn, conversation = conversation)
        assertEquals(1, third.applied.size)
    }

    @Test fun aSingleTierOneProposalIsAppliedAndCanBeUndone() {
        val host = AiFixtures.Host(AiFixtures.archive())
        val before = host.archive
        val result = run(block(planOp), host)
        assertEquals(1, result.applied.size)
        assertTrue(result.pending.isEmpty())
        assertFalse(result.tainted)
        val item = host.archive.plan.single { it.label == "Prova web" }
        assertTrue(item.source.startsWith("Assistente · Claude (account) · "))
        val undone = result.applied.single().undoFrom(host.archive)
        assertEquals(before.plan, undone.plan)
        assertEquals("Risposta.", result.text)
    }

    @Test fun tierTwoProposalsWaitForTheTap() {
        val host = AiFixtures.Host(AiFixtures.archive())
        val result = run(block(eventOp), host)
        assertTrue(result.applied.isEmpty())
        assertEquals(AiChangeKind.ADD_EVENT, result.pending.single().kind)
        assertTrue(result.pending.single().reviewNote!!.contains("richiede conferma"))
        assertEquals(AiFixtures.archive().events, host.archive.events)
    }

    @Test fun moreThanOneProposalIsABatchAndEverythingWaits() {
        val result = run(block(planOp, planOp.replace("Prova web", "Seconda")))
        assertTrue(result.applied.isEmpty())
        assertEquals(2, result.pending.size)
        result.pending.forEach { assertTrue(it.reviewNote!!.contains("più modifiche")) }
    }

    @Test fun aSnapshotWithImportedTextTaintsTheTurn() {
        val snapshot = WebContext.build(AiFixtures.archive(), "categorizza le spese", "Spese · marzo", AiFixtures.clock)
        assertTrue(snapshot.includesImported)
        val result = run(block(planOp), turn = snapshot.turn)
        assertTrue(result.tainted)
        val pending = result.pending.single()
        assertTrue(pending.reviewNote!!.contains("testi di terzi"))
        assertTrue(pending.sourceRows.any { it.contains(AiFixtures.INJECTION.take(20)) })
    }

    @Test fun aReplyThatUsedOutsideSourcesIsTainted() {
        val result = run(block(planOp), external = true)
        assertTrue(result.tainted)
        assertTrue(result.pending.single().reviewNote!!.contains("fonti esterne"))
    }

    @Test fun aRoutineOrPastedReplyIsAlwaysTainted() {
        val result = run(block(planOp), forced = "risposta incollata dagli appunti")
        assertTrue(result.pending.single().reviewNote!!.contains("incollata"))
    }

    @Test fun theSameReplyProcessedTwiceNeverDuplicates() {
        val host = AiFixtures.Host(AiFixtures.archive())
        val first = run(block(planOp), host)
        val second = run(block(planOp), host)
        assertEquals(first.applied.single().id, second.applied.single().id)
        assertEquals(1, host.archive.plan.count { it.label == "Prova web" })
    }

    @Test fun refusedOperationsAndToolFailuresBecomeNotesNotChanges() {
        val future = LocalDate.now().plusDays(10)
        val futureEvent = eventOp.replace("2026-04-02", future.toString())
        val host = AiFixtures.Host(AiFixtures.archive())
        val result = run(block("""{"strumento": "ripristina_archivio", "argomenti": {}}""", futureEvent), host)
        assertTrue(result.applied.isEmpty() && result.pending.isEmpty())
        assertTrue(result.text.contains("Note dell'app"))
        assertTrue(result.text.contains("sconosciuto"))
        assertTrue(result.text.contains("futura"))
        assertEquals(AiFixtures.archive(), host.archive)
    }

    @Test fun anInjectedBlockInsideImportedTextCannotApplyAnything() {
        // The page echoes a hostile block that came from an imported description: the snapshot included imports, so it waits.
        val snapshot = WebContext.build(AiFixtures.archive(), "rivedi i movimenti importati", null, AiFixtures.clock)
        val host = AiFixtures.Host(AiFixtures.archive())
        val result = run(block(planOp, eventOp), host, snapshot.turn)
        assertTrue(result.applied.isEmpty())
        assertEquals(2, result.pending.size)
        assertEquals(AiFixtures.archive(), host.archive)
    }

    @Test fun queuedWritesAreRetriedFirst() {
        val queue = AiWriteQueue()
        val change = AiFixtures.proposal("aggiungi_voce_piano", """{"mese":"2026-11","etichetta":"In coda","tipo":"EXPENSE","stato":"SCENARIO","importo_eur":"1.00"}""").change
        queue.add(change, "occupato")
        val host = AiFixtures.Host(AiFixtures.archive())
        val result = run("Solo testo.", host, queue = queue)
        assertEquals(change.id, result.applied.single().id)
        assertTrue(queue.isEmpty())
        assertNotNull(host.archive.plan.firstOrNull { it.label == "In coda" })
    }

    @Test fun readsOfTheSnapshotAreListedInTheReply() {
        val snapshot = WebContext.build(AiFixtures.archive(), "quanto ho?", null, AiFixtures.clock)
        val result = run("Hai un totale parziale.", turn = snapshot.turn)
        assertTrue(result.readsLine.contains("riepilogo_pilastri"))
        assertFalse(result.readsLine.contains("elenca_importati"))
    }
}
