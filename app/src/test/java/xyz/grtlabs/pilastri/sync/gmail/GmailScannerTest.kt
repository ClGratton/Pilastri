package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview
import xyz.grtlabs.pilastri.data.withInstallmentPaid
import xyz.grtlabs.pilastri.data.withInstallmentPlans
import xyz.grtlabs.pilastri.data.withInstallmentSync
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures as F

class GmailScannerTest {
    private val since = LocalDate.of(2026, 10, 1)

    private fun scan(source: MailSource, limits: ScanLimits = ScanLimits()) =
        runBlocking { GmailScanner(source, limits = limits, now = { F.fixedNow }).scan(since, F.today) }

    private val lifecycle = arrayOf(F.paypalPlan, F.paypalReminder, F.paypalPaid, F.klarnaPlan, F.klarnaReminder, F.newsletter, F.paypalUnrelated, F.unrelatedShop)

    @Test fun scanReadsParsesAndReportsWithoutTouchingAnything() {
        val src = FakeMailSource.of(*lifecycle)
        val found = scan(src)
        assertEquals(lifecycle.size, found.scanned)
        assertEquals(setOf("paypal:7AB12345CD678901E", "klarna:KL-2026-778899"), found.plans.map { it.id }.toSet())
        assertEquals(
            setOf("paypal:7AB12345CD678901E#1", "paypal:7AB12345CD678901E#2", "klarna:KL-2026-778899#1"),
            found.payments.map { "${it.planId}#${it.seq}" }.toSet(),
        )
        assertTrue(found.payments.any { it.planId == "paypal:7AB12345CD678901E" && it.seq == 2 })
        assertEquals(InstallmentSource.GMAIL, found.sync.source)
        assertEquals(F.fixedNow.toString(), found.sync.lastScanAt)
        assertEquals(since, found.sync.coverageFrom)
        assertEquals(F.today, found.sync.coverageTo)
        assertTrue(found.sync.lastResult!!, found.sync.lastResult!!.startsWith("Letti 8 messaggi; 2 piani"))
        assertTrue(found.sync.lastResult!!.length <= 500)
    }

    @Test fun theThreeSearchesAreBoundedAndNeverCarryTheMailBody() {
        val src = FakeMailSource.of(F.paypalPlan)
        scan(src)
        val queries = src.listCalls.map { it.first }.distinct()
        assertEquals(3, queries.size)
        assertTrue(queries.all { it.startsWith("after:2026/09/30 ") })
        assertTrue("PayPal is searched with a text filter, not wholesale", queries[0].contains("from:(paypal.it OR paypal.com)") && queries[0].contains("\"paga in 3\""))
        assertTrue(queries[1].contains("klarna.com") && queries[1].contains("scalapay.com") && queries[1].contains("-category:promotions"))
        assertTrue("the broadest search runs last, excluding senders already covered",
            queries[2].contains("subject:(") && queries[2].contains("\"pagamento a rate\"") && queries[2].contains("-from:(klarna.com") && queries[2].contains("-from:(paypal.it"))
    }

    @Test fun listingIsPagedAndCappedAt300Messages() {
        val ids = (1..1000).map { "m$it" }
        val msgs = ids.associateWith { F.msg(it, F.KLARNA, "Newsletter", "2026-11-0${1 + it.removePrefix("m").toInt() % 9}", "Offerte") }
        val src = FakeMailSource({ ids }, msgs)
        val found = scan(src)
        assertEquals("the cap is on distinct messages read", 300, src.fetched.distinct().size)
        assertEquals(300, found.scanned)
        // Pages are asked in sizes that never exceed the room left under the cap, 100 at most.
        assertTrue(src.listCalls.all { it.third in 1..100 })
        assertEquals("100+100+100 for the first search, then the cap is spent", 3, src.listCalls.size)
        assertTrue(found.sync.lastResult!!, found.sync.lastResult!!.contains("limite di 300 raggiunto"))
        assertTrue("coverage is partial, not the whole window", found.sync.coverageFrom!! > since)
    }

    @Test fun untilBoundsTheWindowSoAnOlderWindowCanBeReadAfterTheCap() {
        val early = F.paypalPlan.copy(id = "e1", date = LocalDate.of(2026, 10, 20))
        val late = F.paypalPlan.copy(id = "e2", date = LocalDate.of(2026, 11, 8))
        val src = FakeMailSource.of(early, late)
        val found = runBlocking { GmailScanner(src, now = { F.fixedNow }).scan(since, F.today, until = LocalDate.of(2026, 10, 31)) }
        assertEquals("only the older message is inside since..until", 1, found.scanned)
        assertEquals(LocalDate.of(2026, 10, 31), found.sync.coverageTo)
        assertEquals(since, found.sync.coverageFrom)
        assertTrue(src.listCalls.all { it.first.contains(" before:2026/11/02") })
        assertTrue("without until there is no before:", FakeMailSource.of(early).also { scan(it) }.listCalls.none { it.first.contains("before:") })
    }

    @Test fun aSearchEndingByItselfIsNotReportedAsPartial() {
        val found = scan(FakeMailSource.of(*lifecycle))
        assertFalse(found.sync.lastResult!!.contains("limite"))
        assertEquals(since, found.sync.coverageFrom)
    }

    @Test fun pagesAreFollowedByTokenUpToThePageCap() {
        val ids = (1..250).map { "m$it" }
        val src = FakeMailSource({ q -> if (q.contains(" from:(paypal")) ids else emptyList() }, ids.associateWith { F.msg(it, F.KLARNA, "x", "2026-11-05", "y") })
        scan(src, ScanLimits(maxMessages = 300, pageSize = 100, maxPagesPerQuery = 2))
        val firstSearch = src.listCalls.filter { it.first.contains(" from:(paypal") }
        assertEquals(listOf<String?>(null, "100"), firstSearch.map { it.second })
        assertEquals("200 ids of 250: the page cap, not the end", 200, src.fetched.size)
    }

    @Test fun messagesBeforeTheWindowAreIgnoredAndMissingOnesSkipped() {
        val old = F.paypalPlan.copy(id = "old", date = LocalDate.of(2026, 9, 1))
        val src = FakeMailSource({ listOf("old", "gone", "p1") }, mapOf("old" to old, "p1" to F.paypalPlan))
        val found = scan(src)
        assertEquals(1, found.scanned)
        assertEquals(1, found.plans.size)
    }

    @Test fun rescanningGivesTheSamePlansAndIds() {
        val a = scan(FakeMailSource.of(*lifecycle))
        val b = scan(FakeMailSource.of(*lifecycle.reversedArray()))
        assertEquals(a.plans, b.plans)
        assertEquals(a.payments, b.payments)
    }

    @Test fun theHostMergesTheResultAndARescanChangesNothing() {
        val found = scan(FakeMailSource.of(*lifecycle))
        val at = "2026-11-10T09:00:00Z"
        val first = Archive().withInstallmentPlans(found.plans, at)
        assertEquals(2, first.added); assertEquals(0, first.updated)
        var archive = first.archive
        found.payments.forEach { archive = archive.withInstallmentPaid(it, at) }
        archive = archive.withInstallmentSync(found.sync)
        val paypal = archive.installments.single { it.id.startsWith("paypal:") }
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.PAID, InstallmentState.DUE), paypal.installments.sortedBy { it.seq }.map { it.state })
        assertTrue(archive.installments.all { it.review == PlanReview.PENDING })

        val again = scan(FakeMailSource.of(*lifecycle))
        val second = archive.withInstallmentPlans(again.plans, at)
        assertEquals(0, second.added); assertEquals(0, second.updated)
    }

    @Test fun anAiTransportIsNeverCalledWhenNoneIsPassed() {
        val src = FakeMailSource.of(F.unknownInstallmentMail)
        val found = scan(src)
        assertTrue(found.plans.isEmpty())
        assertFalse(found.sync.lastResult!!.contains("assistente"))
    }
}
