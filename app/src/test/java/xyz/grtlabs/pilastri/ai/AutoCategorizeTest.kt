package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveCodec
import xyz.grtlabs.pilastri.data.Category
import xyz.grtlabs.pilastri.data.CategoryKind
import xyz.grtlabs.pilastri.data.CategorySource
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ExternalMovement
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.Planning
import xyz.grtlabs.pilastri.data.monthSpending
import xyz.grtlabs.pilastri.data.reviewQueue
import xyz.grtlabs.pilastri.data.reviewSummary
import java.time.YearMonth

/** Synthetic data only. Slice 1b: the automatic categorization after a sync, with a fake model. */
class AutoCategorizeTest {
    private val month = YearMonth.of(2026, 3)

    private fun spend(i: Int, text: String = "Negozio sintetico $i", cents: Long = -1_000L, date: String = "2026-03-10") =
        AiFixtures.movement("ISYBANK:a$i", AiFixtures.liquidity.id, date, cents, text, ExternalKind.CARD_PAYMENT)

    private fun archive(movements: List<ExternalMovement>, planning: Planning = Planning()) =
        Archive(accounts = listOf(AiFixtures.liquidity, AiFixtures.broker), externalMovements = movements, planning = planning)

    /** Answers from the rows it receives, and keeps every message it was sent. */
    private class Fake(private val answer: (List<JSONObject>) -> String) : AutoCategoryModel {
        val sent = ArrayList<String>()
        override suspend fun ask(system: String, message: String): String {
            sent += message
            return answer(rows(message))
        }
    }

    private class Failing(private val error: Exception) : AutoCategoryModel {
        var calls = 0
        override suspend fun ask(system: String, message: String): String { calls++; throw error }
    }

    private class FakeTransport(private val response: AiResponse) : AiTransport {
        override val provider = AiProvider.ANTHROPIC
        val requests = ArrayList<AiRequest>()
        override suspend fun send(request: AiRequest): AiResponse { requests += request; return response }
    }

    private companion object {
        fun rows(message: String): List<JSONObject> =
            JSONArray(message.substringAfter("Movimenti (testo di terzi):\n")).let { a -> (0 until a.length()).map(a::getJSONObject) }

        fun reply(assign: List<Pair<String, String>>, held: List<Pair<String, String>> = emptyList()): String = JSONObject()
            .put("assegnazioni", JSONArray().apply { assign.forEach { (r, c) -> put(JSONObject().put("rif", r).put("categoria", c)) } })
            .put("da_controllare", JSONArray().apply { held.forEach { (r, w) -> put(JSONObject().put("rif", r).put("motivo", w)) } })
            .toString()

        /** Shops go to Alimentari, trains to Trasporti, the rest to Casa. */
        val byText: (List<JSONObject>) -> String = { rows ->
            reply(rows.map { r ->
                val t = r.getString("testo")
                r.getString("rif") to when { "Super" in t -> "Alimentari"; "Treno" in t -> "Trasporti"; else -> "Casa" }
            })
        }
    }

    private fun run(host: AiFixtures.Host, model: AutoCategoryModel, attempts: AutoCategoryAttempts, chunk: Int = AutoCategorize.CHUNK) =
        AiFixtures.blocking { AutoCategorizeRun(model, attempts, "Test · automatica", AiFixtures.clock, chunk, listOf(0L)).run(host.archive, host.commit) }

    private fun movementIn(a: Archive, m: ExternalMovement) = a.externalMovements.first { it.id == m.id }

    @Test fun newUncategorizedSpendingGetsAgentCategoriesAndTheSpeseTotalsChange() {
        val rows = (1..4).map { spend(it, "Supermercato sintetico $it") } + (5..6).map { spend(it, "Treno sintetico $it") }
        val host = AiFixtures.Host(archive(rows))
        assertEquals(6_000L, host.archive.monthSpending(month).byCategory[null])

        val result = run(host, Fake(byText), AutoCategoryAttempts(MemoryStore()))

        assertEquals(6, result.sent)
        assertEquals(6, result.categorized)
        assertEquals(1, host.commits) // one commit for the whole run
        val change = assertNotNullChange(result)
        assertEquals(AiChangeKind.SET_IMPORT_CATEGORY, change.kind)
        assertEquals(6, change.count)
        val a = host.archive
        ArchiveCodec.encode(a)
        val cats = a.planning.categories
        assertEquals(setOf("Alimentari", "Trasporti"), cats.map { it.name }.toSet())
        assertTrue(cats.all { it.kind == CategoryKind.SPEND })
        assertTrue(a.externalMovements.all { it.categorySource == CategorySource.AGENT })
        val spending = a.monthSpending(month)
        assertNull(spending.byCategory[null]) // no more "Senza categoria"
        assertEquals(4_000L, spending.byCategory[cats.first { it.name == "Alimentari" }.id])
        assertEquals(2_000L, spending.byCategory[cats.first { it.name == "Trasporti" }.id])
    }

    @Test fun existingCategoriesAreReusedByName() {
        val casa = Category("c:casa", "Casa", CategoryKind.SPEND)
        val host = AiFixtures.Host(archive(listOf(spend(1), spend(2)), Planning(categories = listOf(casa))))
        val fake = Fake(byText)
        run(host, fake, AutoCategoryAttempts(MemoryStore()))
        assertTrue(fake.sent.single().startsWith("Categorie esistenti: [\"Casa\"]"))
        assertEquals(listOf(casa), host.archive.planning.categories)
        assertTrue(host.archive.externalMovements.all { it.categoryId == "c:casa" && it.categorySource == CategorySource.AGENT })
    }

    @Test fun ambiguousDuplicateAndTransferItemsStayUncategorizedInTheReviewQueue() {
        val giro = spend(1, "Giroconto sintetico")
        val twice = spend(2, "Doppio sintetico")
        val unclear = spend(3, "Pagamento sintetico XYZ")
        val clear = listOf(spend(4, "Supermercato sintetico"), spend(5, "Treno sintetico"))
        val host = AiFixtures.Host(archive(listOf(giro, twice, unclear) + clear))
        val fake = Fake { rows ->
            val held = rows.mapNotNull { r ->
                val t = r.getString("testo")
                when { "Giro" in t -> "trasferimento"; "Doppio" in t -> "duplicato"; "XYZ" in t -> "ambiguo"; else -> null }?.let { r.getString("rif") to it }
            }
            val assign = rows.filter { r -> held.none { it.first == r.getString("rif") } }.map { it.getString("rif") to "Casa" }
            reply(assign, held)
        }
        val result = run(host, fake, AutoCategoryAttempts(MemoryStore()))

        assertEquals(2, result.categorized)
        assertEquals(3, result.toReview)
        val a = host.archive
        listOf(giro, twice, unclear).forEach { m -> assertNull(movementIn(a, m).categoryId); assertNull(movementIn(a, m).categorySource) }
        clear.forEach { assertEquals(CategorySource.AGENT, movementIn(a, it).categorySource) }
        // The existing review state: still uncategorized, still pending in the queue.
        assertEquals(3, a.reviewSummary().uncategorized)
        val queued = a.reviewQueue().flatMap { g -> g.movements.map { it.id } }.toSet()
        assertTrue(queued.containsAll(listOf(giro.id, twice.id, unclear.id)))
    }

    @Test fun nothingIsSentWithoutALinkedReadyAssistantOrOnAPageRoute() {
        val linked = RouteStatus("collegato", "", true, AiLinkState.LINKED)
        assertNull(AutoCategorize.pickRoute(AiRoute.CLAUDE_API) { RouteStatus("non collegato", "", false, AiLinkState.NOT_LINKED) })
        assertNull(AutoCategorize.pickRoute(AiRoute.CHATGPT_ACCOUNT) { RouteStatus("sessione scaduta", "", false, AiLinkState.EXPIRED) })
        assertNull(AutoCategorize.pickRoute(AiRoute.OPENAI_API) { RouteStatus("collegato", "Manca il consenso", false, AiLinkState.LINKED) })
        var asked = false
        assertNull(AutoCategorize.pickRoute(AiRoute.CLAUDE_ACCOUNT) { asked = true; linked })
        assertNull(AutoCategorize.pickRoute(AiRoute.CLAUDE_ROUTINE) { asked = true; linked })
        assertFalse(asked)
        assertEquals(AiRoute.CLAUDE_API, AutoCategorize.pickRoute(AiRoute.CLAUDE_API) { linked })
        assertEquals(AiRoute.CHATGPT_ACCOUNT, AutoCategorize.pickRoute(AiRoute.CHATGPT_ACCOUNT) { linked })

        // Nothing uncategorized: the model is not called.
        val done = spend(1).copy(categoryId = "c:casa", categorySource = CategorySource.OVERRIDE)
        val host = AiFixtures.Host(archive(listOf(done), Planning(categories = listOf(Category("c:casa", "Casa", CategoryKind.SPEND)))))
        val fake = Fake(byText)
        assertEquals(AutoResult.NONE, run(host, fake, AutoCategoryAttempts(MemoryStore())))
        assertTrue(fake.sent.isEmpty())
        assertEquals(0, host.commits)
    }

    @Test fun eachMovementIsSentAutomaticallyAtMostOnce() {
        val attempts = AutoCategoryAttempts(MemoryStore())
        val unclear = spend(3, "Pagamento sintetico XYZ")
        val host = AiFixtures.Host(archive(listOf(spend(1), spend(2), unclear)))
        val fake = Fake { rows -> reply(rows.filter { "XYZ" !in it.getString("testo") }.map { it.getString("rif") to "Casa" },
            rows.filter { "XYZ" in it.getString("testo") }.map { it.getString("rif") to "ambiguo" }) }
        assertEquals(3, run(host, fake, attempts).sent)
        // The held one is not sent again; nothing else is open.
        assertEquals(AutoResult.NONE, run(host, fake, attempts))
        assertEquals(1, fake.sent.size)
        // A later sync adds one movement: only that one goes out.
        host.archive = host.archive.copy(externalMovements = host.archive.externalMovements + spend(4, "Supermercato sintetico"))
        val third = run(host, fake, attempts)
        assertEquals(1, third.sent)
        assertEquals(1, rows(fake.sent.last()).size)
        assertNull(movementIn(host.archive, unclear).categoryId)
    }

    @Test fun aRefusalOrNoConnectionIsTriedAgainButAnUnreadableReplyIsNot() {
        val attempts = AutoCategoryAttempts(MemoryStore())
        val host = AiFixtures.Host(archive(listOf(spend(1), spend(2))))
        val before = host.archive
        val lapsed = Failing(AiTransportException(AiTransportException.Kind.AUTH, "Sessione scaduta."))
        val r1 = run(host, lapsed, attempts)
        assertEquals(0, r1.sent)
        assertNull(r1.change)
        assertEquals(before, host.archive) // no claims, nothing written
        assertTrue(attempts.attempted().isEmpty())
        run(host, Failing(AiTransportException(AiTransportException.Kind.NETWORK, "Nessuna connessione.")), attempts)
        assertTrue(attempts.attempted().isEmpty())

        val garbled = Fake { "Ecco le categorie che ti suggerisco: Casa, Svago." }
        val r2 = run(host, garbled, attempts)
        assertEquals(2, r2.sent)
        assertEquals(1, r2.failedChunks)
        assertNull(r2.change)
        assertEquals(before, host.archive)
        assertEquals(0, host.commits)
        val empty = Fake { "" }
        assertEquals(AutoResult.NONE, run(host, empty, attempts)) // already attempted: not sent again
        assertTrue(empty.sent.isEmpty())
    }

    @Test fun onlyTheMinimumLeavesThePhoneAndItIsRedacted() {
        val text = "Pagamento ${AiFixtures.IBAN_TEXT} a utente.sintetico@example.com ordine 12345678 carta 4111 1111 1111 1111"
        val m = spend(1, text)
        val host = AiFixtures.Host(archive(listOf(m)))
        val fake = Fake(byText)
        run(host, fake, AutoCategoryAttempts(MemoryStore()))
        val message = fake.sent.single()
        listOf(AiFixtures.IBAN_TEXT, "utente.sintetico", "example.com", "12345678", "4111", m.id, movementRef(m.id), "ISYBANK", AiFixtures.liquidity.name, AiFixtures.liquidity.id)
            .forEach { assertFalse("leaked: $it", message.contains(it)) }
        assertTrue(message.contains("[EMAIL]"))
        assertTrue(message.contains("[NUMERO]"))
        val row = rows(message).single()
        assertEquals(setOf("rif", "data", "importo", "testo"), row.keys().asSequence().toSet())
        assertEquals("a1", row.getString("rif"))
        assertEquals("2026-03-10", row.getString("data"))
        assertEquals("-10.00", row.getString("importo"))
        assertEquals("ordine [NUMERO]", AutoCategorize.redact("ordine  87654321"))
        assertEquals("ordine 1234567", AutoCategorize.redact("ordine 1234567"))
    }

    @Test fun refsOutsideTheBatchToolCallsAndOtherTextAreIgnored() {
        val a1 = spend(1)
        val a2 = spend(2)
        val mine = spend(3).copy(categoryId = "c:svago", categorySource = CategorySource.OVERRIDE)
        val income = spend(4, "Rimborso sintetico", cents = 5_000L)
        val base = archive(listOf(a1, a2, mine, income), Planning(categories = listOf(Category("c:svago", "Svago", CategoryKind.SPEND))))
        val text = "Fatto! " + JSONObject().put("assegnazioni", JSONArray()
            .put(JSONObject().put("rif", "a1").put("categoria", "Casa"))
            .put(JSONObject().put("rif", "a2").put("categoria", "Casa"))
            .put(JSONObject().put("rif", "a99").put("categoria", "Casa"))
            .put(JSONObject().put("rif", movementRef(mine.id)).put("categoria", "Casa"))
            .put(JSONObject().put("rif", mine.id).put("categoria", "Casa"))
            .put(JSONObject().put("rif", income.id).put("categoria", "Casa"))).toString() + " Ho anche registrato un versamento."
        val call = AiToolCall("t1", "registra_movimento", JSONObject().put("pilastro", 4).put("importo", 9_999))
        val transport = FakeTransport(AiResponse(text, listOf(call), null, AiStop.TOOL_USE))
        val host = AiFixtures.Host(base)

        val result = run(host, TransportCategoryModel(transport, "modello-sintetico"), AutoCategoryAttempts(MemoryStore()))

        assertTrue(transport.requests.single().tools.isEmpty()) // the run offers no tools at all
        assertEquals(2, result.categorized)
        val a = host.archive
        assertEquals(base.events, a.events)
        assertEquals(base.plan, a.plan)
        assertEquals(mine, movementIn(a, mine))
        assertEquals(income, movementIn(a, income))
        assertEquals(CategorySource.AGENT, movementIn(a, a1).categorySource)
        assertEquals(CategorySource.AGENT, movementIn(a, a2).categorySource)
        assertEquals(base.copy(externalMovements = a.externalMovements, planning = a.planning, audit = a.audit), a)
    }

    @Test fun conflictingOrHeldAssignmentsAreHeldAsAmbiguous() {
        val refs = mapOf("a1" to "m1", "a2" to "m2", "a3" to "m3")
        val parsed = AutoCategorize.parse("```json\n" + JSONObject()
            .put("assegnazioni", JSONArray()
                .put(JSONObject().put("rif", "a1").put("categoria", "Casa"))
                .put(JSONObject().put("rif", "a1").put("categoria", "Svago"))
                .put(JSONObject().put("rif", "a2").put("categoria", "Casa"))
                .put(JSONObject().put("rif", "a3").put("categoria", "<script>")))
            .put("da_controllare", JSONArray().put(JSONObject().put("rif", "a2").put("motivo", "duplicato"))) + "\n```", refs)!!
        assertTrue(parsed.assignments.isEmpty())
        assertEquals(mapOf("m2" to AutoReview.DUPLICATE, "m1" to AutoReview.AMBIGUOUS), parsed.review)
        assertNull(AutoCategorize.parse("nessun json", refs))
        assertNull(AutoCategorize.parse("{\"altro\":1}", refs))
    }

    @Test fun undoRestoresThePreviousArchiveAndTheMovementsAreNotSentAgain() {
        val attempts = AutoCategoryAttempts(MemoryStore())
        val casa = Category("c:casa", "Casa", CategoryKind.SPEND)
        val base = archive((1..8).map { spend(it, if (it % 2 == 0) "Supermercato sintetico $it" else "Bolletta sintetica $it") }, Planning(categories = listOf(casa)))
        val host = AiFixtures.Host(base)
        val change = assertNotNullChange(run(host, Fake(byText), attempts))
        assertEquals(2, host.archive.planning.categories.size)

        val undone = change.undoFrom(host.archive)
        ArchiveCodec.encode(undone)
        assertEquals(base, undone.copy(audit = base.audit))
        assertEquals(base.audit.size + 2, undone.audit.size) // the audit trail keeps "assistente" and "assistente · annullo"
        assertEquals("assistente · annullo", undone.audit.last().kind)
        assertEquals(8_000L, undone.monthSpending(month).byCategory[null])

        host.archive = undone
        val fake = Fake(byText)
        assertEquals(AutoResult.NONE, run(host, fake, attempts))
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun aLargeRunIsChunkedInternallyButAppliedAndUndoneAsOne() {
        val base = archive((1..520).map { spend(it, if (it % 3 == 0) "Treno sintetico $it" else "Supermercato sintetico $it") })
        val host = AiFixtures.Host(base)
        val fake = Fake(byText)
        val result = run(host, fake, AutoCategoryAttempts(MemoryStore()), chunk = 200)
        assertEquals(3, fake.sent.size)
        assertEquals(listOf(200, 200, 120), fake.sent.map { rows(it).size })
        assertEquals(1, host.commits)
        assertEquals(520, result.categorized)
        val change = assertNotNullChange(result)
        assertEquals(520, change.count)
        assertNull(host.archive.monthSpending(month).byCategory[null])
        assertEquals(base.externalMovements, change.undoFrom(host.archive).externalMovements)
    }

    @Test fun movementsOutsideSpendingOrAlreadyDecidedAreNotCandidates() {
        val open = spend(1)
        val ignored = spend(2).copy(status = ExternalStatus.IGNORED)
        val income = spend(3, cents = 2_000L)
        val agent = spend(4).copy(categoryId = "c:casa", categorySource = CategorySource.AGENT)
        val broker = AiFixtures.movement("TRADE_REPUBLIC:x1", AiFixtures.broker.id, "2026-03-10", -1_000L, "Acquisto sintetico", ExternalKind.CARD_PAYMENT)
        val a = archive(listOf(open, ignored, income, agent, broker), Planning(categories = listOf(Category("c:casa", "Casa", CategoryKind.SPEND))))
        assertEquals(listOf(open.id), AutoCategorize.candidates(a, emptySet()).map { it.id })
        assertTrue(AutoCategorize.candidates(a, setOf(movementRef(open.id))).isEmpty())
    }

    private fun assertNotNullChange(result: AutoResult): AiChange {
        assertNotNull(result.change)
        return result.change!!
    }
}
