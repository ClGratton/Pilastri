package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import xyz.grtlabs.pilastri.data.Planning
import xyz.grtlabs.pilastri.data.monthSpending
import xyz.grtlabs.pilastri.data.monthView
import java.time.YearMonth

/** Synthetic data only. The assistant's category batches: one change, no cap, categories created, one undo. */
class CategorizeBatchTest {
    private val month = YearMonth.of(2026, 3)

    private fun spend(i: Int) = AiFixtures.movement("ISYBANK:n$i", AiFixtures.liquidity.id, "2026-03-10", -1_000L, "Negozio sintetico $i", ExternalKind.CARD_PAYMENT)

    private fun archive(count: Int, planning: Planning = Planning(), extra: List<ExternalMovement> = emptyList()) =
        Archive(accounts = listOf(AiFixtures.liquidity), externalMovements = (1..count).map(::spend) + extra, planning = planning)

    private fun json(vararg pairs: Pair<ExternalMovement, String>): String = JSONObject().put("assegnazioni", JSONArray().apply {
        pairs.forEach { (m, c) -> put(JSONObject().put("rif", movementRef(m.id)).put("categoria", c)) }
    }).toString()

    private fun categoryOf(a: Archive, m: ExternalMovement) = a.externalMovements.first { it.id == m.id }

    @Test fun aBatchOfFortyFiveHasNoCapChangesTheTotalsAndEmptiesSenzaCategoria() {
        val base = archive(45)
        val rows = base.externalMovements
        assertEquals(45_000L, base.monthSpending(month).byCategory[null])
        assertTrue(base.monthView(month).categories.any { it.id == null && it.name == "Senza categoria" })

        val p = AiFixtures.proposal("categorizza_movimenti", json(*rows.mapIndexed { i, m -> m to if (i < 30) "Alimentari" else "Trasporti" }.toTypedArray()), base)
        assertEquals(AiChangeKind.SET_IMPORT_CATEGORY, p.change.kind)
        assertEquals(45, p.change.count)
        assertEquals(45, p.change.diff.size)

        val applied = p.change.applyTo(base)
        ArchiveCodec.encode(applied)
        val cats = applied.planning.categories
        assertEquals(setOf("Alimentari", "Trasporti"), cats.map { it.name }.toSet())
        assertTrue(cats.all { it.kind == CategoryKind.SPEND })
        val spending = applied.monthSpending(month)
        assertNull(spending.byCategory[null])
        assertEquals(30_000L, spending.byCategory[cats.first { it.name == "Alimentari" }.id])
        assertEquals(15_000L, spending.byCategory[cats.first { it.name == "Trasporti" }.id])
        assertFalse(applied.monthView(month).categories.any { it.id == null })
        assertTrue(applied.externalMovements.all { it.categorySource == CategorySource.AGENT })
    }

    @Test fun anExistingCategoryIsReusedByNameAndTheUsersOwnChoiceIsLeftAlone() {
        val food = Category("c:alimentari", "Alimentari", CategoryKind.SPEND)
        val mine = spend(99).copy(categoryId = "c:alimentari", categorySource = CategorySource.OVERRIDE)
        val base = archive(3, Planning(categories = listOf(food, Category("c:altro", "Altro", CategoryKind.SPEND))), listOf(mine))
        val rows = base.externalMovements
        val p = AiFixtures.proposal("categorizza_movimenti", json(rows[0] to "alimentari", rows[1] to "ALIMENTARI", rows[2] to "Altro", mine to "Altro"), base)
        assertEquals(3, p.change.count)
        val applied = p.change.applyTo(base)
        assertEquals(2, applied.planning.categories.size)
        assertEquals("c:alimentari", categoryOf(applied, rows[0]).categoryId)
        assertEquals("c:altro", categoryOf(applied, rows[2]).categoryId)
        assertEquals(mine, categoryOf(applied, mine))
    }

    @Test fun undoRestoresEverythingIncludingTheCreatedCategories() {
        val base = archive(12)
        val p = AiFixtures.proposal("categorizza_movimenti", json(*base.externalMovements.map { it to "Casa" }.toTypedArray()), base)
        val applied = p.change.applyTo(base)
        assertEquals(1, applied.planning.categories.size)
        val undone = p.change.undoFrom(applied)
        ArchiveCodec.encode(undone)
        assertEquals(base, undone.copy(audit = base.audit))
        assertEquals("assistente · annullo", undone.audit.last().kind)
        assertEquals(12_000L, undone.monthSpending(month).byCategory[null])
    }

    @Test fun undoKeepsAMovementTheUserRecategorizedAndTheCategoryItNowUses() {
        val base = archive(4)
        val p = AiFixtures.proposal("categorizza_movimenti", json(*base.externalMovements.map { it to "Casa" }.toTypedArray()), base)
        val applied = p.change.applyTo(base)
        val first = base.externalMovements[0]
        val edited = applied.copy(externalMovements = applied.externalMovements.map {
            if (it.id == first.id) it.copy(categorySource = CategorySource.OVERRIDE) else it
        })
        val undone = p.change.undoFrom(edited)
        assertEquals(CategorySource.OVERRIDE, categoryOf(undone, first).categorySource)
        assertNull(categoryOf(undone, base.externalMovements[1]).categoryId)
        assertEquals(1, undone.planning.categories.size) // still used by the movement the user kept
    }

    @Test fun twoBatchesOfARequestMergeIntoOneChangeThatAppliesAndUndoesTogether() {
        val base = archive(30)
        val a = base.externalMovements.take(15)
        val b = base.externalMovements.drop(15)
        val one = AiFixtures.proposal("categorizza_movimenti", json(*a.map { it to "Casa" }.toTypedArray()), base, "c1").change
        val two = AiFixtures.proposal("categorizza_movimenti", json(*b.map { it to "Svago" }.toTypedArray()), base, "c2").change
        val merged = one.mergedWith(two)
        assertEquals(30, merged.count)
        assertEquals(listOf(one.id, two.id), merged.memberIds)
        assertEquals("$CATEGORY_BATCH_SUMMARY 30 movimenti importati", merged.summary)
        val applied = merged.applyTo(base)
        assertEquals(setOf("Casa", "Svago"), applied.planning.categories.map { it.name }.toSet())
        assertNull(applied.monthSpending(month).byCategory[null])
        val undone = merged.undoFrom(applied)
        assertEquals(base.externalMovements, undone.externalMovements)
        assertTrue(undone.planning.categories.isEmpty())
    }

    @Test fun theRouterTakesMoreThanTwentyCategoryCallsPerRequestWithoutACap() {
        val host = AiFixtures.Host(archive(40))
        val turn = AiTurnState()
        val router = AiWriteRouter(host.archive, host.commit, "Anthropic", turn, AiFixtures.clock, true, AiWriteQueue(), listOf(0L))
        AiFixtures.blocking {
            host.archive.externalMovements.forEachIndexed { i, m ->
                val routed = router.write("categorizza_movimenti", JSONObject(json(m to "Casa")), "w$i", batch = false)
                assertFalse(routed.text, routed.isError)
            }
        }
        assertEquals(40, router.applied.size + router.pending.size)
        assertTrue(router.failures.isEmpty())
        // The changes of the request merge into one that carries them all.
        val all = (router.applied + router.pending).reduce { x, y -> x.mergedWith(y) }
        assertEquals(40, all.count)
        assertNull(all.applyTo(archive(40)).monthSpending(month).byCategory[null])
    }

    @Test fun readingOnlyTheUncategorizedSpendingShrinksAfterTheBatch() {
        val base = archive(25)
        val before = AiFixtures.read("elenca_importati", """{"senza_categoria":true,"limite":50}""", base)
        assertEquals(25, before.getInt("totale"))
        val applied = AiFixtures.proposal("categorizza_movimenti", json(*base.externalMovements.take(20).map { it to "Casa" }.toTypedArray()), base).change.applyTo(base)
        val after = AiFixtures.read("elenca_importati", """{"senza_categoria":true,"limite":50}""", applied)
        assertEquals(5, after.getInt("totale"))
    }

    @Test fun emptyOrUnknownAssignmentsAreRefusedWithAMessage() {
        val base = archive(2)
        assertTrue((AiFixtures.run("categorizza_movimenti", """{"assegnazioni":[]}""", base) as AiToolResult.Failure).message.contains("vuoto"))
        assertTrue((AiFixtures.run("categorizza_movimenti", """{"assegnazioni":[{"rif":"zzz","categoria":"Casa"}]}""", base) as AiToolResult.Failure).message.contains("Nessun movimento"))
        val once = AiFixtures.proposal("categorizza_movimenti", json(base.externalMovements[0] to "Casa"), base).change.applyTo(base)
        assertTrue((AiFixtures.run("categorizza_movimenti", json(base.externalMovements[0] to "Casa"), once) as AiToolResult.Failure).message.contains("Nessuna modifica"))
    }
}
