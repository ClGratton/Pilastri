package xyz.grtlabs.pilastri.debug

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.*

/** The debug sample archive must stay valid and keep showing every state the redesigned screens need. */
class SampleArchiveTest {
    private val today = LocalDate.of(2026, 10, 31)
    private val october = YearMonth.of(2026, 10)
    private val archive = SampleArchive.build(today)

    @Test fun roundTripsThroughTheCodec() {
        val decoded = ArchiveCodec.decode(ArchiveCodec.encode(archive))
        assertEquals(archive, decoded)
    }

    @Test fun pillarValuesAreComposedFromAccounts() {
        assertTrue("Pillar values come from accounts, not pillar snapshots", archive.valuations.isEmpty())
        assertEquals(listOf(214_000L, 780_000L, 320_000L, 2_463_000L), (1..4).map { archive.pillarValue(it).centsOrNull() })
        val total = archive.assetsTotal()
        assertNotNull(total.cents)
        assertEquals(3_777_000L, total.cents)
        assertEquals("Allianz is the oldest component", today.minusDays(43), total.oldest)
        assertTrue(total.missing.isEmpty())
    }

    @Test fun everyPillarHasAnEarlierValueAboutAMonthBack() {
        val changes = (1..4).map { pillar ->
            val accountIds = archive.accounts.filter { it.pillar == pillar }.map { it.id }
            val latest = accountIds.sumOf { id -> archive.accountValuations.filter { it.accountId == id }.maxBy { it.date }.amount.cents }
            val earlier = accountIds.sumOf { id ->
                val sorted = archive.accountValuations.filter { it.accountId == id }.sortedByDescending { it.date }
                assertTrue("$id needs an earlier value", sorted.size >= 2)
                sorted[1].amount.cents
            }
            latest - earlier
        }
        assertEquals(listOf(-9_600L, 15_000L, 15_000L, 20_800L), changes)
    }

    @Test fun briefingShowsEveryKindTheTodayScreenNeeds() {
        val kinds = archive.briefing(today).map { it.kind }
        listOf(BriefingKind.SALARY, BriefingKind.REVIEW, BriefingKind.INSTALLMENT_DUE, BriefingKind.INSTALLMENT_REVIEW, BriefingKind.STALE_VALUE)
            .forEach { assertTrue("$it missing from $kinds", it in kinds) }
        assertEquals(kinds.sortedBy { it.ordinal }, kinds)
    }

    @Test fun octoberSpendingMatchesTheDesignBoard() {
        val month = archive.monthSpending(october)
        assertTrue(month.entrateCents > 0)
        assertEquals(184_200L, month.entrateCents)
        assertTrue("Spesa ${month.spesaCents} should be close to 1.318 euro", kotlin.math.abs(month.spesaCents - 131_800L) <= 3_000L)
        assertEquals(listOf("Casa", "Spesa", "Trasporti", "Ristoranti", "Svago", "Abbonamenti", "Salute", "Altro"),
            archive.planning.categories.filter { it.kind == CategoryKind.SPEND }.map { it.name })
        val byName = month.byCategory.mapKeys { (id, _) -> archive.planning.categories.firstOrNull { it.id == id }?.name }
        assertEquals(52_000L, byName["Casa"]); assertEquals(28_600L, byName["Spesa"]); assertEquals(14_200L, byName["Trasporti"])
        assertEquals(9_600L, byName["Svago"]); assertEquals(4_697L, byName["Abbonamenti"]); assertEquals(3_800L, byName["Salute"])
        assertTrue("The unreviewed bar payment is the only uncategorized spending", byName[null] == 2_340L)
        assertEquals(3, month.pending)
        // Coverage ends at the earliest liquidity source (isybank, three days ago), not the latest (PayPal).
        assertEquals(today.minusDays(3), month.coverageTo)
        // Earlier months exist, resemble October and carry a salary of their own.
        listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9)).forEach {
            val earlier = archive.monthSpending(it)
            assertEquals(184_200L, earlier.entrateCents)
            assertTrue("${it}: ${earlier.spesaCents}", earlier.spesaCents in 100_000L..170_000L)
            assertEquals(0, earlier.pending)
        }
    }

    @Test fun oneOffAndRecurringMerchantsAreIdentifiable() {
        val oneOff = archive.externalMovements.single { it.description == "FERRAMENTA ROSSI" }
        assertEquals(-24_000L, oneOff.signedCents)
        assertEquals(today.withDayOfMonth(19), oneOff.date)
        val disney = archive.externalMovements.filter { it.description == "DISNEY PLUS" }
        assertEquals(3, disney.size)
        assertEquals(YearMonth.of(2026, 8), YearMonth.from(disney.minOf { it.date }))
        assertTrue(disney.all { it.signedCents == -999L })
    }

    @Test fun realCardHasIndexedAwaitingAndHistoricalParts() {
        val card = archive.realCard()
        assertNotNull(card.indexedRealCents)
        assertTrue(card.awaiting.isNotEmpty())
        assertEquals(october, card.awaiting.first().first)
        assertTrue(card.unindexedHistoricalCount > 0)
        assertEquals(41_200L, card.employerCents)
    }

    @Test fun reviewQueueHasTransferAndUnclassifiedGroups() {
        val queue = archive.reviewQueue()
        assertTrue(queue.any { it.decision is ExternalDecision.AsTransfer && !it.bulk && it.count == 2 })
        assertTrue(queue.any { it.label == "Da classificare" || it.decision == null })
        assertEquals(4, queue.sumOf { it.count })
    }

    @Test fun installmentsShowDueReviewAndUnknownStates() {
        assertTrue(archive.installmentCommitment(YearMonth.of(2026, 11)).parts > 0)
        assertEquals(PlanReview.PENDING, archive.installments.single { it.merchant == "IKEA" }.review)
        assertTrue(archive.installments.single { it.merchant == "IKEA" }.remainingUnknown > 0)
        assertEquals(setOf(InstallmentSource.GMAIL, InstallmentSource.PAYPAL), archive.installmentSync.map { it.source }.toSet())
        // Paid parts point at the imported rows that paid them.
        val ids = archive.externalMovements.map { it.id }.toSet()
        archive.installments.flatMap { it.installments }.mapNotNull { it.movementId }.forEach { assertTrue(it in ids) }
    }

    @Test fun tradingHistoryAndInstrumentExist() {
        val trade = archive.trades.first()
        assertEquals("IE00B4L5Y983", archive.instruments.single().identifier)
        assertEquals(15_000L, trade.grossAmount.cents)
        assertEquals(3, archive.trades.size)
        assertTrue(archive.trades.any { it.date == today.withDayOfMonth(2) })
    }

    @Test fun everyRecordNamesItselfAsSampleData() {
        val prefix = SampleArchive.PREFIX
        val sources = archive.accounts.map { it.source } + archive.accountValuations.map { it.source } + archive.events.map { it.source } +
            archive.transfers.map { it.source } + archive.trades.map { it.source } + archive.instruments.map { it.source } +
            archive.holdings.map { it.source } + archive.projections.map { it.source } +
            archive.pension.statements.map { it.source } + archive.pension.profiles.map { it.source } +
            archive.pension.deductionYears.map { it.source } + archive.pension.inps.map { it.source }
        assertTrue(sources.isNotEmpty())
        sources.forEach { assertTrue("Source without prefix: $it", it.startsWith(prefix)) }
        assertNull(archive.prefillFacts)
        assertTrue(archive.audit.isEmpty())
    }

    @Test fun versatoHistoryHasPersonalOwnTransferAndUnindexedParts() {
        val inflows = archive.versatoTotal()
        assertTrue(inflows.any { it.fromPillar == null && it.date.year == 2025 })
        assertEquals(10, archive.versatoInto(2).size)
        assertEquals(10, archive.versatoInto(3).size)
        assertTrue(archive.versatoInto(4).any { it.fromPillar == 1 })
        assertEquals(4, archive.events.count { it.type == EventType.PERSONAL_CONTRIBUTION && it.accountId == "sample-allianz" && it.date.year == 2025 })
    }
}
