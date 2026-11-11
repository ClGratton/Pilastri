package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Synthetic data only: the repair of the assistant's payment-method "categories". */
class CategoryRepairTest {
    private val f = CategoryFixtures
    private val bogus = f.spend("c:pagamenti-a-rate", "Pagamenti a rate")
    private val userPaypal = f.spend("c:paypal", "PayPal") // made by the person: only their own assignments
    private val food = f.spend("c:alimentari", "Alimentari")
    private val agentRan = AuditEntry("2026-10-31T10:00:00Z", "assistente", "Categorizza 7 movimenti importati")

    private val part1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000, categoryId = bogus.id, source = CategorySource.AGENT)
    private val part2 = f.mv("PAYPAL:p2", "Paga in 3 rate", -1_000, "2026-04-10", bogus.id, CategorySource.AGENT)
    private val loosePart = f.mv("PAYPAL:p3", "Paga in 3 rate", -2_000, "2026-03-15", food.id, CategorySource.AGENT)
    private val shopInBogus = f.mv("PAYPAL:s1", "Negozio sintetico", -4_000, "2026-03-11", bogus.id, CategorySource.AGENT)
    private val mine = f.mv("PAYPAL:m1", "Bottega sintetica", -500, "2026-03-12", userPaypal.id, CategorySource.OVERRIDE)
    private val groceries = f.mv("PAYPAL:g1", "Supermercato sintetico", -700, "2026-03-13", food.id, CategorySource.AGENT)
    private val plan = f.plan("paypal-web:k1", null, listOf(f.part(1, part1.id), f.part(2, part2.id, "2026-04-10")))

    private fun base(planning: Planning = Planning(categories = listOf(bogus, userPaypal, food)), extra: List<ExternalMovement> = emptyList(), audit: List<AuditEntry> = listOf(agentRan)) =
        Archive(
            accounts = listOf(f.paypal), externalMovements = listOf(part1, part2, loosePart, shopInBogus, mine, groceries) + extra,
            installments = listOf(plan), planning = planning, audit = audit,
        ).also { ArchiveCodec.encode(it) }

    private fun Archive.cat(m: ExternalMovement) = externalMovements.first { it.id == m.id }.let { it.categoryId to it.categorySource }

    @Test fun theAgentsMethodCategoryGoesAndItsMovementsBecomeUncategorized() {
        val a = base()
        assertEquals(setOf(bogus.id), CategoryRepair.agentMethodCategories(a))
        val r = CategoryRepair.run(a, "2026-11-01T10:00:00Z")!!
        ArchiveCodec.encode(r.archive)
        assertEquals(listOf(userPaypal, food), r.archive.planning.categories)
        listOf(part1, part2, shopInBogus, loosePart).forEach { assertEquals(null to null, r.archive.cat(it)) }
        assertEquals(mine.categoryId to mine.categorySource, r.archive.cat(mine))
        assertEquals(food.id to CategorySource.AGENT, r.archive.cat(groceries))
        assertEquals(3, r.record.fromMethodCategories)
        assertEquals(1, r.record.fromInstallmentParts)
        assertEquals(CategoryRepair.AUDIT_APPLY, r.archive.audit.last().kind)
        // The parts now read "rata · acquisto da classificare".
        assertEquals(InstallmentPaymentState.ToClassify(plan.id), r.archive.installmentPaymentState(part1.id))
        assertEquals(InstallmentPaymentState.ToClassify(null), r.archive.installmentPaymentState(loosePart.id))
        assertEquals(7_000L, r.archive.monthSpending(YearMonth.of(2026, 3)).byCategory[null])
    }

    @Test fun theRepairIsIdempotent() {
        val once = CategoryRepair.run(base(), "2026-11-01T10:00:00Z")!!.archive
        assertNull(CategoryRepair.run(once, "2026-11-02T10:00:00Z"))
    }

    @Test fun somethingThePersonSetKeepsTheCategoryButItsAgentMovementsStillGoBack() {
        val budgeted = base(Planning(categories = listOf(bogus, userPaypal, food), budgets = listOf(Budget(bogus.id, 10_000, YearMonth.of(2026, 1)))))
        val r1 = CategoryRepair.run(budgeted, "2026-11-01T10:00:00Z")!!
        assertTrue(r1.archive.planning.categories.contains(bogus))
        assertEquals(null to null, r1.archive.cat(part1))

        val chosen = f.mv("PAYPAL:u1", "Negozio sintetico", -900, "2026-03-20", bogus.id, CategorySource.OVERRIDE)
        val r2 = CategoryRepair.run(base(extra = listOf(chosen)), "2026-11-01T10:00:00Z")!!
        assertTrue(r2.archive.planning.categories.contains(bogus))
        assertEquals(bogus.id to CategorySource.OVERRIDE, r2.archive.cat(chosen))

        val edited = base(Planning(categories = listOf(bogus.copy(essential = Essential.ESSENTIAL), userPaypal, food)))
        assertEquals(3, CategoryRepair.run(edited, "2026-11-01T10:00:00Z")!!.archive.planning.categories.size)
    }

    @Test fun userCategoriesAndAssignmentsAreNeverTouched() {
        val noAgent = Archive(accounts = listOf(f.paypal), externalMovements = listOf(mine), planning = Planning(categories = listOf(userPaypal, bogus)), audit = listOf(agentRan))
        assertNull(CategoryRepair.run(noAgent, "2026-11-01T10:00:00Z"))
        // Without any trace of the assistant's category tool in the history, no category counts as the assistant's.
        val noHistory = base(audit = emptyList())
        assertTrue(CategoryRepair.agentMethodCategories(noHistory).isEmpty())
        val r = CategoryRepair.run(noHistory, "2026-11-01T10:00:00Z")!!
        assertEquals(noHistory.planning, r.archive.planning)
        assertEquals(bogus.id to CategorySource.AGENT, r.archive.cat(shopInBogus))
        assertEquals(null to null, r.archive.cat(part1)) // installment parts the assistant categorized still go back
    }

    @Test fun undoRestoresExactlyAndTheRepairIsNotRunAgainAfterwards() {
        val a = base()
        val r = CategoryRepair.run(a, "2026-11-01T10:00:00Z")!!
        val undone = CategoryRepair.undo(r.archive, r.record, "2026-11-01T10:05:00Z")
        ArchiveCodec.encode(undone)
        assertEquals(a.externalMovements, undone.externalMovements)
        assertEquals(a.planning.categories.toSet(), undone.planning.categories.toSet())
        assertEquals(listOf(CategoryRepair.AUDIT_APPLY, CategoryRepair.AUDIT_UNDO), undone.audit.drop(a.audit.size).map { it.kind })
        assertNull(CategoryRepair.run(undone, "2026-11-02T10:00:00Z"))
    }

    @Test fun undoKeepsWhatThePersonChangedSince() {
        val r = CategoryRepair.run(base(), "2026-11-01T10:00:00Z")!!
        val later = r.archive.withMovementCategory(part1.id, food.id)
        val undone = CategoryRepair.undo(later, r.record, "2026-11-01T10:05:00Z")
        assertEquals(food.id to CategorySource.OVERRIDE, undone.cat(part1))
        assertEquals(bogus.id to CategorySource.AGENT, undone.cat(part2))
        assertNotNull(undone.planning.categories.firstOrNull { it.id == bogus.id })
    }
}
