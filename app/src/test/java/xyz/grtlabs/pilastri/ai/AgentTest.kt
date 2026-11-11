package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.ai.AiFixtures.Host
import xyz.grtlabs.pilastri.data.Archive

class AgentTest {
    /** Replies with the scripted items in order: an [AiResponse] is returned, an [AiTransportException] is thrown. */
    private class Scripted(private vararg val steps: Any) : AiTransport {
        override val provider = AiProvider.ANTHROPIC
        val requests = ArrayList<AiRequest>()
        override suspend fun send(request: AiRequest): AiResponse {
            requests += request
            val step = steps[minOf(requests.size - 1, steps.size - 1)]
            if (step is Exception) throw step
            return step as AiResponse
        }

        fun lastResults(): List<AiMessage.ToolResult> {
            val messages = requests.last().messages
            return messages.takeLastWhile { it is AiMessage.ToolResult }.filterIsInstance<AiMessage.ToolResult>()
        }
    }

    private fun call(id: String, name: String, json: String = "{}") = AiToolCall(id, name, JSONObject(json), json)
    private fun tools(vararg calls: AiToolCall, raw: Any? = null) = AiResponse("", calls.toList(), raw, AiStop.TOOL_USE)
    private fun text(value: String) = AiResponse(value, emptyList(), null, AiStop.END)
    private fun plan(label: String) = """{"mese":"2026-05","etichetta":"$label","tipo":"EXPENSE","stato":"SCENARIO","importo_eur":"10.00"}"""
    private fun valuation(day: String = "2026-04-30") = """{"pilastro":2,"data":"$day","importo_eur":"100.00"}"""
    private val noWait = listOf(0L, 0L, 0L)

    private fun agent(
        transport: AiTransport, maxRounds: Int = 8, maxWrites: Int = 20, queue: AiWriteQueue = AiWriteQueue(),
        extraTap: (AiChangeKind) -> Boolean = { false },
    ) = AiAgent(transport, "claude-sonnet-5-5", AiFixtures.clock, maxRounds, maxWrites, noWait, queue, extraTap)

    private fun run(
        agent: AiAgent, host: Host, question: String = "Domanda", redact: Boolean = true, archive: Archive = host.archive,
        history: List<AiMessage> = emptyList(), conversation: AiConversationTaint? = null,
    ) = AiFixtures.blocking { agent.run(question, history, archive, host.commit, redact, conversation = conversation) }

    @Test fun aDirectWriteIsAppliedThroughCommitAndAnsweredWithAReadsLine() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("Cena"))), text("Fatto."))
        val applied = ArrayList<AiChange>()
        val result = AiFixtures.blocking {
            agent(transport).run("Aggiungi una cena", emptyList(), host.archive, host.commit, onApplied = { applied += it })
        }
        assertEquals("Fatto.", result.text)
        assertEquals(1, result.applied.size)
        assertEquals(applied, result.applied)
        assertTrue(result.pending.isEmpty() && result.queued.isEmpty() && result.failures.isEmpty())
        assertEquals("ai:${result.applied[0].id}", host.archive.plan.last().id)
        assertEquals("assistente", host.archive.audit.last().kind)
        assertTrue(transport.lastResults().single().content.startsWith("Registrato"))
        assertFalse(transport.lastResults().single().isError)
        assertEquals("Letto dall'archivio: niente.", result.readsLine)
        assertTrue(result.displayText.endsWith("Letto dall'archivio: niente."))
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].system.contains("testo_esterno"))
        assertEquals(AiTools.specs().map { it.name }, transport.requests[0].tools.map { it.name })
        assertEquals(listOf(AiMessage.User("Aggiungi una cena"), AiMessage.Assistant("Fatto.")), result.historyDelta("Aggiungi una cena"))
        // The undo goes back through commit as an inverse operation.
        val undo = AiFixtures.blocking { AiCommits.run(host.commit) { result.applied[0].undoFrom(it) } }
        assertTrue(undo is AiCommitResult.Done)
        assertTrue(host.archive.plan.none { it.id.startsWith("ai:") })
    }

    @Test fun tierTwoKindsWaitForATapAndCarryADiff() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "registra_valutazione_pilastro", valuation())), text("Attende conferma."))
        val pending = ArrayList<AiChange>()
        val result = AiFixtures.blocking { agent(transport).run("Valuta", emptyList(), host.archive, host.commit, onPending = { pending += it }) }
        assertEquals(1, result.pending.size)
        assertEquals(pending, result.pending)
        assertTrue(result.applied.isEmpty())
        assertEquals(AiFixtures.archive().valuations, host.archive.valuations) // nothing written yet
        assertEquals(0, host.commits)
        val change = result.pending[0]
        assertTrue(change.reviewNote!!.contains("tipo di modifica"))
        assertTrue(change.diff.isNotEmpty())
        assertTrue(transport.lastResults().single().content.startsWith("In attesa di conferma"))
        // The tap applies it on the latest archive.
        val outcome = AiFixtures.blocking { AiCommits.run(host.commit) { change.applyTo(it) } }
        assertTrue(outcome is AiCommitResult.Done)
        assertEquals(3, host.archive.valuations.size)
    }

    @Test fun moreThanOneWriteInAResponseMakesEveryWritePending() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A")), call("c2", "aggiungi_voce_piano", plan("B"))), text("ok"))
        val result = run(agent(transport), host)
        assertEquals(2, result.pending.size)
        assertTrue(result.applied.isEmpty())
        assertTrue(result.pending.all { it.reviewNote!!.contains("più modifiche") })
        assertEquals(AiFixtures.archive().plan, host.archive.plan)
        assertEquals(2, transport.lastResults().size)
    }

    @Test fun aSecondWriteInTheSameTurnIsPendingEvenAcrossRounds() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A"))), tools(call("c2", "aggiungi_voce_piano", plan("B"))), text("ok"))
        val result = run(agent(transport), host)
        assertEquals(1, result.applied.size)
        assertEquals(1, result.pending.size)
        assertTrue(result.pending[0].reviewNote!!.contains("più modifiche"))
        assertEquals(AiFixtures.archive().plan.size + 1, host.archive.plan.size)
    }

    @Test fun readingThirdPartyTextForcesEveryLaterWriteToWaitAndShowsTheSourceRows() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(
            tools(call("r1", "elenca_importati", """{"stato":"PENDING"}""")),
            tools(call("c1", "aggiungi_voce_piano", plan("Cena"))),
            text("ok"),
        )
        val result = run(agent(transport), host)
        assertTrue(result.tainted)
        assertTrue(result.applied.isEmpty())
        val change = result.pending.single()
        assertTrue(change.reviewNote!!.contains("testi di terzi"))
        assertTrue(change.sourceRows.any { it.contains("Bonifico in entrata") })
        assertTrue(change.sourceRows.any { it.contains(AiFixtures.INJECTION) })
        assertEquals(AiFixtures.archive().plan, host.archive.plan)
        assertEquals("Letto dall'archivio: elenca_importati ×1 (5 righe).", result.readsLine)
    }

    @Test fun readsInTheSameResponseAreJudgedBeforeItsWrites() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("Cena")), call("r1", "elenca_importati")), text("ok"))
        val result = run(agent(transport), host)
        assertTrue(result.applied.isEmpty())
        assertEquals(1, result.pending.size)
        // Results go back in the order the model asked, even though the read ran first.
        assertEquals(listOf("c1", "r1"), transport.requests.last().messages.filterIsInstance<AiMessage.ToolResult>().map { it.callId })
    }

    @Test fun readingOwnDataDoesNotForceATap() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(
            tools(call("r1", "riepilogo_pilastri"), call("r2", "elenca_conti")),
            tools(call("c1", "aggiungi_voce_piano", plan("Cena"))),
            text("ok"),
        )
        val result = run(agent(transport), host)
        assertFalse(result.tainted)
        assertEquals(1, result.applied.size)
        assertEquals("Letto dall'archivio: riepilogo_pilastri ×1 (4 righe); elenca_conti ×1 (3 righe).", result.readsLine)
    }

    @Test fun extraTapsCanOnlyAddToThePolicyNeverWeakenIt() {
        val strict = run(agent(Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A"))), text("ok")), extraTap = { true }), Host(AiFixtures.archive()))
        assertEquals(1, strict.pending.size)
        assertTrue(strict.applied.isEmpty())

        // A "lenient" extra policy cannot let a Tier-2 kind through: the base policy is a pure function.
        val host = Host(AiFixtures.archive())
        val lenient = run(agent(Scripted(tools(call("c1", "registra_valutazione_pilastro", valuation())), text("ok")), extraTap = { false }), host)
        assertEquals(1, lenient.pending.size)
        assertTrue(lenient.applied.isEmpty())
        assertEquals(AiFixtures.archive().valuations, host.archive.valuations)
        // ...and the taint rule still forces a tap on a Tier-1 kind.
        val tainted = run(agent(Scripted(tools(call("r1", "elenca_importati")), tools(call("c1", "aggiungi_voce_piano", plan("B"))), text("ok"))), Host(AiFixtures.archive()))
        assertEquals(1, tainted.pending.size)
        assertTrue(tainted.applied.isEmpty())
        // The direct set cannot be widened through a cast.
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (AiPolicy.directKinds as MutableSet<AiChangeKind>).add(AiChangeKind.ADD_EVENT)
        }
        assertTrue(AiPolicy.requiresTap(AiChangeKind.ADD_EVENT))
    }

    @Test fun aTaintedTurnKeepsTheConversationTaintedUntilNuova() {
        val conversation = AiConversationTaint()
        val host = Host(AiFixtures.archive())
        // Turn 1 reads imported (third-party) text.
        val first = run(agent(Scripted(tools(call("r1", "elenca_importati")), text("Ecco i movimenti."))), host, conversation = conversation)
        assertTrue(first.tainted)
        assertTrue(conversation.tainted)

        // Turn 2 reads nothing external, but the provider still has turn 1 in its history: a Tier-1 write needs the tap.
        val second = run(
            agent(Scripted(tools(call("c1", "aggiungi_voce_piano", plan("Dopo"))), text("ok"))), host,
            history = first.historyDelta("Mostrami gli importati"), conversation = conversation,
        )
        assertTrue(second.tainted)
        assertTrue(second.applied.isEmpty())
        val pending = second.pending.single()
        assertTrue(pending.reviewNote!!.contains("richiesta precedente"))
        assertTrue(pending.sourceRows.any { it.contains(AiFixtures.INJECTION) })
        assertEquals(AiFixtures.archive().plan, host.archive.plan)

        // "Nuova" clears it: the same write is Tier 1 again.
        conversation.clear()
        val third = run(agent(Scripted(tools(call("c1", "aggiungi_voce_piano", plan("Nuova"))), text("ok"))), host, conversation = conversation)
        assertFalse(third.tainted)
        assertEquals(1, third.applied.size)
    }

    @Test fun aTurnThatFailsAfterReadingThirdPartyTextStillTaintsTheConversation() {
        val conversation = AiConversationTaint()
        val failing = Scripted(tools(call("r1", "elenca_importati")), AiTransportException(AiTransportException.Kind.NETWORK, "rete"))
        val result = run(agent(failing), Host(AiFixtures.archive()), conversation = conversation)
        assertTrue(result.error != null)
        assertTrue(conversation.tainted)
    }

    @Test fun aFailedSaveIsRetriedThenQueuedAndNeverDropped() {
        val host = Host(AiFixtures.archive(), failures = 10)
        val queue = AiWriteQueue()
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("Cena"))), text("In coda."))
        val queuedCallback = ArrayList<AiChange>()
        val first = AiFixtures.blocking {
            agent(transport, queue = queue).run("Aggiungi", emptyList(), host.archive, host.commit, onQueued = { queuedCallback += it })
        }
        assertEquals(3, host.commits) // initial attempt plus two retries
        assertTrue(first.applied.isEmpty())
        assertEquals(1, first.queued.size)
        assertEquals(queuedCallback, first.queued)
        assertEquals(1, queue.snapshot().size)
        assertTrue(first.failures.single().reason.contains("in coda"))
        assertTrue(transport.lastResults().single().isError)
        assertTrue(transport.lastResults().single().content.contains("in coda"))
        assertEquals(AiFixtures.archive().plan, host.archive.plan)

        // The next run flushes the queue before anything else, once the app can save again.
        host.failures = 0
        val second = AiFixtures.blocking {
            agent(Scripted(text("ok")), queue = queue).run("Altro", emptyList(), host.archive, host.commit)
        }
        assertEquals(1, second.applied.size)
        assertEquals(first.queued[0].id, second.applied[0].id)
        assertTrue(queue.isEmpty())
        assertEquals(1, host.archive.plan.count { it.id.startsWith("ai:") })
    }

    @Test fun aBusyAppSucceedsOnARetryWithoutQueueing() {
        val host = Host(AiFixtures.archive(), failures = 1)
        val queue = AiWriteQueue()
        val result = run(agent(Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A"))), text("ok")), queue = queue), host)
        assertEquals(1, result.applied.size)
        assertEquals(2, host.commits)
        assertTrue(queue.isEmpty() && result.queued.isEmpty())
    }

    @Test fun aRejectedWriteIsReportedWithItsReasonAndNotQueued() {
        val base = AiFixtures.archive()
        // Another path ignored the movement after the assistant read it.
        val host = Host(base.copy(externalMovements = base.externalMovements.map { if (it.id == AiFixtures.injected.id) it.copy(status = xyz.grtlabs.pilastri.data.ExternalStatus.IGNORED) else it }))
        val ref = movementRef(AiFixtures.injected.id)
        val transport = Scripted(tools(call("c1", "ignora_importato", """{"rif":"$ref"}""")), text("ok"))
        val queue = AiWriteQueue()
        val result = run(agent(transport, queue = queue), host, archive = base)
        assertTrue(result.applied.isEmpty() && result.queued.isEmpty())
        assertTrue(queue.isEmpty())
        assertTrue(result.failures.single().reason.contains("già stato revisionato"))
        assertTrue(transport.lastResults().single().isError)
        assertEquals(1, host.commits) // a deterministic rejection is not retried
    }

    @Test fun aTransportErrorStillReportsWorkAlreadyDone() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A"))), AiTransportException(AiTransportException.Kind.NETWORK, "Connessione non riuscita: controlla la rete e riprova."))
        val result = run(agent(transport), host)
        assertEquals("Connessione non riuscita: controlla la rete e riprova.", result.error)
        assertEquals(1, result.applied.size)
        assertTrue(result.historyDelta("q").isEmpty())
    }

    @Test fun theLoopStopsAfterTheRoundLimitWithoutRunningTheLastCalls() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("r1", "riepilogo_pilastri")))
        val result = run(agent(transport, maxRounds = 3), host)
        assertEquals(3, transport.requests.size)
        assertTrue(result.hitRoundLimit)
        assertTrue(result.text.contains("limite di 3 passaggi"))
        assertEquals("Letto dall'archivio: riepilogo_pilastri ×2 (8 righe).", result.readsLine)
    }

    @Test fun anIdenticalWriteTwiceInOneTurnIsRefused() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A"))), tools(call("c2", "aggiungi_voce_piano", plan("A"))), text("ok"))
        val result = run(agent(transport), host)
        assertEquals(1, result.applied.size)
        assertEquals(0, result.pending.size)
        val last = transport.requests.last().messages.filterIsInstance<AiMessage.ToolResult>().last()
        assertTrue(last.isError)
        assertTrue(last.content.contains("identica"))
        assertEquals(1, host.archive.plan.count { it.id.startsWith("ai:") })
    }

    @Test fun writesPerTurnAreCapped() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(tools(call("c1", "aggiungi_voce_piano", plan("A"))), tools(call("c2", "aggiungi_voce_piano", plan("B"))), text("ok"))
        val result = run(agent(transport, maxWrites = 1), host)
        assertEquals(1, result.applied.size)
        assertTrue(result.pending.isEmpty())
        assertTrue(transport.requests.last().messages.filterIsInstance<AiMessage.ToolResult>().last().content.contains("Limite di 1"))
    }

    @Test fun unknownToolsAndBadArgumentsComeBackAsErrorsForEveryCall() {
        val host = Host(AiFixtures.archive())
        val broken = AiToolCall("c2", "elenca_conti", JSONObject(), "{bad", "Argomenti dello strumento non interpretabili (JSON non valido).")
        val transport = Scripted(tools(call("c1", "non_esiste"), broken, call("c3", "elenca_movimenti", """{"tipo":"NOPE"}""")), text("ok"))
        val result = run(agent(transport), host)
        assertEquals("ok", result.text)
        val results = transport.requests.last().messages.filterIsInstance<AiMessage.ToolResult>()
        assertEquals(listOf("c1", "c2", "c3"), results.map { it.callId })
        assertTrue(results.all { it.isError })
        assertTrue(results[0].content.contains("sconosciuto"))
        assertTrue(results[2].content.contains("Tipo non valido"))
    }

    @Test fun redactionMasksWhatLeavesTheDeviceAndCanBeSwitchedOff() {
        val host = Host(AiFixtures.archive())
        val question = "Il mio IBAN è ${AiFixtures.IBAN_TEXT}, carta 4111 1111 1111 1111"
        val history = listOf(AiMessage.User("prima ${AiFixtures.IBAN_TEXT}"), AiMessage.Assistant("ok"))
        val masked = Scripted(text("ok"))
        run(agent(masked), host, question, redact = true, history = history)
        val sent = masked.requests[0].messages.filterIsInstance<AiMessage.User>().joinToString("|") { it.text }
        assertFalse(sent.contains(AiFixtures.IBAN_TEXT))
        assertTrue(sent.contains("[IBAN]") && sent.contains("[CARTA]"))
        val clear = Scripted(text("ok"))
        run(agent(clear), host, question, redact = false)
        assertTrue((clear.requests[0].messages.last() as AiMessage.User).text.contains(AiFixtures.IBAN_TEXT))
    }

    @Test fun refusalsTruncationAndBlankQuestionsAreHandledWithoutRunningTools() {
        val host = Host(AiFixtures.archive())
        val refused = run(agent(Scripted(AiResponse("", emptyList(), null, AiStop.REFUSED, note = "general_harms"))), host)
        assertTrue(refused.text.contains("rifiutato") && refused.text.contains("general_harms"))
        val truncated = Scripted(AiResponse("Inizio", listOf(call("c1", "aggiungi_voce_piano", plan("A"))), null, AiStop.TRUNCATED))
        val cut = run(agent(truncated), host)
        assertTrue(cut.text.contains("interrotta"))
        assertTrue(cut.applied.isEmpty() && cut.pending.isEmpty())
        assertEquals(1, truncated.requests.size)
        val silent = Scripted(text("x"))
        val blank = run(agent(silent), host, question = "   ")
        assertEquals("Scrivi una domanda.", blank.error)
        assertTrue(silent.requests.isEmpty())
    }

    @Test fun theAssistantTurnIsReplayedUnchangedInsideTheToolLoop() {
        val host = Host(AiFixtures.archive())
        val raw = JSONArray("""[{"type":"thinking","thinking":"","signature":"sig"},{"type":"tool_use","id":"c1","name":"riepilogo_pilastri","input":{}}]""")
        val transport = Scripted(tools(call("c1", "riepilogo_pilastri"), raw = raw), text("ok"))
        run(agent(transport), host)
        val replayed = transport.requests[1].messages.filterIsInstance<AiMessage.Assistant>().single()
        assertSame(raw, replayed.raw)
        assertNotNull(replayed.toolCalls.single())
    }

    @Test fun commitsReturnTheLatestStateSoLaterReadsSeeEarlierWrites() {
        val host = Host(AiFixtures.archive())
        val transport = Scripted(
            tools(call("c1", "aggiungi_voce_piano", plan("Cena"))),
            tools(call("r1", "elenca_piano", """{"anno":2026}""")),
            text("ok"),
        )
        run(agent(transport), host)
        val listing = JSONObject(transport.requests.last().messages.filterIsInstance<AiMessage.ToolResult>().last().content)
        assertEquals(2, listing.getInt("totale"))
    }

    @Test fun commitFailureOfATappedChangeSurfacesAsRejectedOrFailed() {
        val base = AiFixtures.archive()
        val change = AiFixtures.proposal("registra_valutazione_pilastro", valuation(), base).change
        val clash = Host(base.copy(valuations = base.valuations + xyz.grtlabs.pilastri.domain.Valuation(java.time.LocalDate.parse("2026-04-30"), 2,
            xyz.grtlabs.pilastri.domain.Money(1), "altra", xyz.grtlabs.pilastri.domain.Confidence.USER_REPORTED)))
        val rejected = AiFixtures.blocking { AiCommits.run(clash.commit, noWait) { change.applyTo(it) } }
        assertTrue(rejected is AiCommitResult.Rejected && rejected.message.contains("Esiste già"))
        val busy = Host(base, failures = 5)
        val failed = AiFixtures.blocking { AiCommits.run(busy.commit, noWait) { change.applyTo(it) } }
        assertTrue(failed is AiCommitResult.Failed)
        assertEquals(3, busy.commits)
    }
}
