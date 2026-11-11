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
import xyz.grtlabs.pilastri.data.AuditEntry
import xyz.grtlabs.pilastri.data.CategoryFixtures
import xyz.grtlabs.pilastri.data.CategorySource
import xyz.grtlabs.pilastri.data.ExternalMovement
import xyz.grtlabs.pilastri.data.Planning
import java.time.Instant

/** Synthetic data only: the assistant's side of the 2026-11-01 category rule. */
class CategoryGuardTest {
    private val f = CategoryFixtures
    private val food = f.spend("c:alimentari", "Alimentari")
    private val bogus = f.spend("c:pagamenti-a-rate", "Pagamenti a rate")
    private val shop1 = f.mv("PAYPAL:s1", "Negozio sintetico uno", -1_000)
    private val shop2 = f.mv("PAYPAL:s2", "Negozio sintetico due", -2_000)
    private val part = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000)

    private fun archive(planning: Planning = Planning(categories = listOf(food)), extra: List<ExternalMovement> = emptyList()) =
        Archive(accounts = listOf(f.paypal), externalMovements = listOf(shop1, shop2, part) + extra, planning = planning).also { ArchiveCodec.encode(it) }

    private fun json(vararg pairs: Pair<ExternalMovement, String>) = JSONObject().put("assegnazioni", JSONArray().apply {
        pairs.forEach { (m, c) -> put(JSONObject().put("rif", movementRef(m.id)).put("categoria", c)) }
    }).toString()

    private fun failure(json: String, a: Archive = archive()) = (AiFixtures.run(CATEGORY_TOOL, json, a) as AiToolResult.Failure).message

    @Test fun aPaymentMethodIsRefusedAsACategory() {
        val msg = failure(json(shop1 to "Pagamenti a rate", shop2 to "PayPal"))
        assertTrue(msg, msg.contains("mezz") && msg.contains("non una categoria"))
        // An id of the bogus category is judged by its name.
        val withBogus = archive(Planning(categories = listOf(food, bogus)))
        assertTrue(failure(json(shop1 to bogus.id), withBogus).contains("mezzo di pagamento"))
    }

    @Test fun refusedEntriesAreSkippedAndTheRestStillMakesOneCard() {
        val p = AiFixtures.proposal(CATEGORY_TOOL, json(shop1 to "Alimentari", shop2 to "Carta di credito", part to "Alimentari"), archive())
        assertEquals(1, p.change.count)
        assertTrue(p.modelNote, p.modelNote.contains("1 rate saltate") && p.modelNote.contains("1 assegnazioni rifiutate"))
        val applied = p.change.applyTo(archive())
        assertEquals(null, applied.externalMovements.first { it.id == shop2.id }.categoryId)
        assertEquals(null, applied.externalMovements.first { it.id == part.id }.categoryId)
    }

    @Test fun installmentPartsAreNeverCategorizedFromTheirText() {
        assertEquals(CategoryGuard.RATE_REFUSAL, failure(json(part to "Acquisti")))
    }

    @Test fun aProposedNameMapsOntoTheExistingCategoryAndOnlyRealPurposesAreCreated() {
        val p = AiFixtures.proposal(CATEGORY_TOOL, json(shop1 to "Supermercato", shop2 to "ALIMENTARI"), archive())
        assertTrue(p.change.newCategories.isEmpty())
        assertTrue(p.change.diff.none { it.isNew })
        val applied = p.change.applyTo(archive())
        assertEquals(listOf(food), applied.planning.categories)
        assertTrue(applied.externalMovements.filter { it.id != part.id }.all { it.categoryId == food.id && it.categorySource == CategorySource.AGENT })

        val created = AiFixtures.proposal(CATEGORY_TOOL, json(shop1 to "utenze", shop2 to "Giardinaggio"), archive(Planning()))
        assertEquals(listOf("Bollette", "Giardinaggio"), created.change.newCategories)
        assertTrue(created.change.diff.all { it.isNew })
        assertEquals(setOf("Bollette", "Giardinaggio"), created.change.diff.map { it.after }.toSet())
        assertTrue(created.modelNote.contains("nuove categorie: Bollette, Giardinaggio"))
    }

    @Test fun mergedBatchesShowEachNewCategoryOnce() {
        val a = archive(Planning())
        val one = AiFixtures.proposal(CATEGORY_TOOL, json(shop1 to "Giardinaggio"), a, "c1").change
        val two = AiFixtures.proposal(CATEGORY_TOOL, json(shop2 to "Giardinaggio"), a, "c2").change
        assertEquals(listOf("Giardinaggio"), one.mergedWith(two).newCategories)
    }

    @Test fun theAutomaticBatchLeavesPartsOutAndHoldsMethodNames() {
        val attempted = emptySet<String>()
        val candidates = AutoCategorize.candidates(archive(), attempted).map { it.id }
        assertEquals(listOf(shop1.id, shop2.id), candidates)
        val reply = AutoCategorize.parse("""{"assegnazioni":[{"rif":"a1","categoria":"PayPal"},{"rif":"a2","categoria":"Alimentari"}]}""", mapOf("a1" to shop1.id, "a2" to shop2.id))!!
        assertEquals(mapOf(shop2.id to "Alimentari"), reply.assignments)
        assertEquals(AutoReview.AMBIGUOUS, reply.review[shop1.id])
    }

    @Test fun theAutomaticRunOffersTheDefaultsWhenThePersonHasNoCategories() {
        var sent = ""
        val model = AutoCategoryModel { _, message -> sent = message; """{"assegnazioni":[],"da_controllare":[]}""" }
        val host = AiFixtures.Host(archive(Planning()))
        AiFixtures.blocking { AutoCategorizeRun(model, AutoCategoryAttempts(MemoryStore()), "test", AiFixtures.clock).run(host.archive, host.commit) }
        assertTrue(sent.contains("Ristoranti e bar") && sent.contains("Alimentari"))
        assertFalse(sent.contains("Paga in 3 rate"))
    }

    @Test fun theAutomaticWriteDropsMethodNamesAndParts() {
        val host = AiFixtures.Host(archive())
        val r = AiFixtures.blocking {
            AiAutoCategoryWrite.commit(mapOf(shop1.id to "PayPal", shop2.id to "Alimentari", part.id to "Alimentari"), setOf(shop1.id, shop2.id, part.id), host.commit, "test", AiFixtures.clock, listOf(0L))
        }
        assertEquals(1, r.count)
        assertEquals(food.id, host.archive.externalMovements.first { it.id == shop2.id }.categoryId)
        assertNull(host.archive.externalMovements.first { it.id == shop1.id }.categoryId)
        assertNull(host.archive.externalMovements.first { it.id == part.id }.categoryId)
    }

    @Test fun aPlanCannotTakeAPaymentMethodCategory() {
        val plan = f.plan("manual:k1", "Negozio sintetico", emptyList())
        val a = Archive(accounts = listOf(f.paypal), installments = listOf(plan), planning = Planning(categories = listOf(food, bogus)))
        val r = AiFixtures.run("imposta_categoria_piano_rate", """{"rif":"${InstallmentTools.planRef(plan.id)}","categoria_id":"${bogus.id}"}""", a)
        assertTrue((r as AiToolResult.Failure).message.contains("mezzo di pagamento"))
    }

    @Test fun theRepairIsOneChangeWithAnExactUndo() {
        val agentRan = AuditEntry("2026-10-31T10:00:00Z", "assistente", "Categorizza 2 movimenti importati")
        val a = Archive(
            accounts = listOf(f.paypal), planning = Planning(categories = listOf(food, bogus)), audit = listOf(agentRan),
            externalMovements = listOf(shop1.copy(categoryId = bogus.id, categorySource = CategorySource.AGENT), part.copy(categoryId = bogus.id, categorySource = CategorySource.AGENT), shop2),
        )
        val clock = { Instant.parse("2026-11-01T10:00:00Z") }
        val change = CategoryRepairChange.prepare(a, clock)!!
        assertEquals(2, change.count)
        assertEquals("«Pagamenti a rate» non è una categoria: 2 spese da riclassificare", change.summary)
        val applied = change.applyTo(a)
        assertEquals(listOf(food), applied.planning.categories)
        assertNull(CategoryRepairChange.prepare(applied, clock))
        val undone = change.undoFrom(applied)
        assertEquals(a.externalMovements, undone.externalMovements)
        assertNotNull(undone.planning.categories.firstOrNull { it.id == bogus.id })
        assertNull(CategoryRepairChange.prepare(undone, clock)) // undone by the person: not repaired again
    }
}
