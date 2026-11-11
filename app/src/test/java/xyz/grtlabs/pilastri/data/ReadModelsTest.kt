package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.domain.*

/** Council 2 evidence tests, synthetic data only. */
class ReadModelsTest {
    private val bank = Account("bank", "Conto", 1, "fixture")
    private val emergency = Account("cash", "Riserva", 2, "fixture")
    private val broker = Account("etf", "Broker", 4, "fixture")
    private val accounts = listOf(bank, emergency, broker)
    private val sep = LocalDate.of(2026, 9, 10)
    private val indexSource = "ISTAT NIC fixture"
    private fun idx(m: Int, v: String) = IndexPoint(YearMonth.of(2026, m), BigDecimal(v), indexSource)

    @Test fun salaryIsNotAContributionAndAChainCountsOnce() {
        val salary = ImportedMovement("sal", sep, 180_000, "EUR", "Stipendio", ExternalKind.DEPOSIT)
        val archive = Archive(accounts = accounts).withImportBatch(ImportBatch(ImportProvider.ISYBANK, "Conto", Instant.now(), sep, sep, listOf(salary)), bank.id).archive
            .copy(transfers = listOf(
                AccountTransfer("t1", sep, bank.id, emergency.id, Money(30_000), "fixture"),
                AccountTransfer("t2", sep.plusDays(1), emergency.id, broker.id, Money(10_000), "fixture"),
            ))
        assertTrue("Salary creates no inflow", archive.inflows().none { it.recordId.contains("sal") })
        assertEquals(30_000L, archive.versatoInto(2).sumOf { it.cents })
        assertEquals(10_000L, archive.versatoInto(4).sumOf { it.cents })
        assertEquals("P1 → P2 → P4 counts the outside money once", 30_000L, archive.versatoTotal().sumOf { it.cents })
    }

    @Test fun realCardSeparatesIndexedAwaitingAndHistorical() {
        val events = listOf(
            LedgerEvent("a", LocalDate.of(2026, 1, 5), 4, EventType.PERSONAL_CONTRIBUTION, Money(10_000), Confidence.DOCUMENT, "f"),
            LedgerEvent("b", LocalDate.of(2026, 2, 5), 4, EventType.PERSONAL_CONTRIBUTION, Money(10_000), Confidence.DOCUMENT, "f"),
            LedgerEvent("c", LocalDate.of(2026, 3, 5), 4, EventType.PERSONAL_CONTRIBUTION, Money(5_000), Confidence.DOCUMENT, "f"),
            LedgerEvent("old", LocalDate.of(2025, 6, 5), 4, EventType.PERSONAL_CONTRIBUTION, Money(7_000), Confidence.DOCUMENT, "f"),
            LedgerEvent("emp", LocalDate.of(2026, 2, 5), 4, EventType.EMPLOYER_CONTRIBUTION, Money(400), Confidence.DOCUMENT, "f"),
        )
        val all = Archive(events = events, indices = listOf(idx(1, "100"), idx(2, "101"), idx(3, "102"))).realCard()
        assertEquals(4, all.totalCount); assertEquals(3, all.indexedCount); assertEquals(1, all.unindexedHistoricalCount)
        assertTrue(all.awaiting.isEmpty())
        assertEquals(10_000L + Math.round(10_000 * 100 / 101.0) + Math.round(5_000 * 100 / 102.0), all.indexedRealCents)
        assertEquals("Employer stays apart", 400L, all.employerCents)

        val awaiting = Archive(events = events, indices = listOf(idx(1, "100"), idx(2, "101"))).realCard()
        assertEquals(listOf(YearMonth.of(2026, 3) to 5_000L), awaiting.awaiting)
        assertEquals(YearMonth.of(2026, 2), awaiting.indexedThrough)

        val none = Archive(events = events).realCard()
        assertNull("Nothing indexed: nominal leads", none.indexedRealCents)
        assertEquals(32_000L, none.nominalTotalCents)
    }

    @Test fun spesaExcludesTransfersIgnoredAndTrades() {
        val batch = ImportBatch(ImportProvider.ISYBANK, "Conto", Instant.now(), sep.withDayOfMonth(1), sep, listOf(
            ImportedMovement("food", sep, -4_000, "EUR", "POS SUPERMERCATO", ExternalKind.CARD_PAYMENT),
            ImportedMovement("move", sep, -30_000, "EUR", "Bonifico a riserva", ExternalKind.WITHDRAWAL),
            ImportedMovement("skip", sep, -1_000, "EUR", "Rimborso spese", ExternalKind.WITHDRAWAL),
            ImportedMovement("sal", sep, 180_000, "EUR", "BONIFICO DATORE SINTETICO", ExternalKind.DEPOSIT),
        ))
        val cashBatch = ImportBatch(ImportProvider.TRADE_REPUBLIC, "Riserva", Instant.now(), sep, sep, listOf(ImportedMovement("in", sep, 30_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT)))
        var archive = Archive(accounts = accounts, planning = Planning(incomeRules = listOf(IncomeRule("s", "Stipendio", bank.id, "datore sintetico", 1, 31, 100_000, 300_000, "2026-09-01"))))
            .withImportBatch(batch, bank.id).archive.withImportBatch(cashBatch, emergency.id).archive
        archive = archive.withExternalDecision("ISYBANK:move", ExternalDecision.AsTransfer("TRADE_REPUBLIC:in"))
            .withExternalDecision("ISYBANK:skip", ExternalDecision.Ignore)
        val month = archive.monthSpending(YearMonth.of(2026, 9))
        assertEquals("Only the card payment is spending", 4_000L, month.spesaCents)
        assertEquals(180_000L, month.entrateCents)
        assertEquals(176_000L, month.risparmiatoCents)
        assertEquals(sep, month.coverageTo)
    }

    @Test fun pillarValueIsUnknownWhenAnyAccountLacksAValue() {
        val archive = Archive(accounts = accounts + Account("etf2", "Altro broker", 4, "fixture"),
            accountValuations = listOf(AccountValuation(broker.id, sep, Money(100_000), "Broker · sincronizzazione", Confidence.DOCUMENT)))
        assertTrue(archive.pillarValue(4) is PillarValue.Unknown)
        assertEquals(null, archive.assetsTotal().cents)
        val withSnapshots = archive.copy(accounts = accounts, valuations = listOf(Valuation(sep, 1, Money(1), "f", Confidence.USER_REPORTED), Valuation(sep, 2, Money(2), "f", Confidence.USER_REPORTED), Valuation(sep, 3, Money(3), "f", Confidence.USER_REPORTED)))
        assertTrue("A reported pillar snapshot stands when an account has no value yet", withSnapshots.pillarValue(1) is PillarValue.Snapshot)
        assertEquals(100_000L, withSnapshots.pillarValue(4).centsOrNull())
        assertEquals(100_006L, withSnapshots.assetsTotal().cents)
        val scenarioOnly = Archive(valuations = listOf(Valuation(sep, 3, Money(9), "f", Confidence.SCENARIO)))
        assertTrue("Scenario values never form a pillar value", scenarioOnly.pillarValue(3) is PillarValue.Unknown)
    }

    @Test fun briefingRanksSalaryThenReviewThenStale() {
        val today = LocalDate.of(2026, 10, 28)
        val batch = ImportBatch(ImportProvider.ISYBANK, "Conto", Instant.now(), today.minusDays(20), today.minusDays(1), listOf(
            ImportedMovement("sal", today.minusDays(1), 184_200, "EUR", "BONIFICO DATORE SINTETICO", ExternalKind.DEPOSIT)))
        val archive = Archive(accounts = accounts, planning = Planning(incomeRules = listOf(IncomeRule("s", "Stipendio", bank.id, "datore sintetico", 20, 31, 100_000, 300_000, "2026-09-01"))),
            accountValuations = listOf(AccountValuation(broker.id, LocalDate.of(2026, 9, 18), Money(1), "f", Confidence.DOCUMENT)))
            .withImportBatch(batch, bank.id).archive
        val kinds = archive.briefing(today).map { it.kind }
        assertEquals(listOf(BriefingKind.SALARY, BriefingKind.REVIEW, BriefingKind.STALE_VALUE), kinds)
    }

    @Test fun reviewQueueGroupsByEffectAndKeepsTransfersAndDuplicatesSingle() {
        val bankBatch = ImportBatch(ImportProvider.ISYBANK, "Conto", Instant.now(), sep.minusDays(5), sep.plusDays(10), listOf(
            ImportedMovement("c1", sep, -1_000, "EUR", "POS BAR", ExternalKind.CARD_PAYMENT),
            ImportedMovement("c2", sep, -2_000, "EUR", "POS MERCATO", ExternalKind.CARD_PAYMENT),
            ImportedMovement("tx", sep, -30_000, "EUR", "Bonifico a broker", ExternalKind.WITHDRAWAL),
        ))
        val brokerBatch = ImportBatch(ImportProvider.TRADE_REPUBLIC, "Broker", Instant.now(), sep, sep, listOf(
            ImportedMovement("in", sep.plusDays(1), 30_000, "EUR", "Bonifico in entrata", ExternalKind.DEPOSIT),
            ImportedMovement("div", sep, 1_200, "EUR", "Dividendo", ExternalKind.DIVIDEND),
        ))
        val manual = LedgerEvent("manual", sep, 4, EventType.INCOME, Money(1_200), Confidence.USER_REPORTED, "a mano", accountId = broker.id)
        val archive = Archive(accounts = accounts, events = listOf(manual)).withImportBatch(bankBatch, bank.id).archive.withImportBatch(brokerBatch, broker.id).archive
        val queue = archive.reviewQueue()
        val outflows = queue.single { it.label == "Uscita" }
        assertEquals(2, outflows.count); assertEquals(3_000L, outflows.totalCents); assertTrue(outflows.bulk)
        val transfer = queue.single { it.label.startsWith("Possibile trasferimento") }
        assertEquals(2, transfer.count); assertTrue(!transfer.bulk)
        assertTrue("A dividend matching a manual record is a possible duplicate, never bulk", queue.single { it.label == "Possibile duplicato" }.let { !it.bulk && it.movements.single().id == "TRADE_REPUBLIC:div" })
    }

    @Test fun daControllareCountsOnlyUncategorizedDuplicatesTransfersAndHeldAmbiguous() {
        val oct = LocalDate.of(2026, 10, 10)
        val rows = listOf(
            ImportedMovement("dup", oct, -5_000, "EUR", "Bonifico fixture", ExternalKind.WITHDRAWAL),
            ImportedMovement("held", oct, -1_234, "EUR", "POS fixture A", ExternalKind.CARD_PAYMENT),
            ImportedMovement("fresh", oct, -2_345, "EUR", "POS fixture B", ExternalKind.CARD_PAYMENT),
            ImportedMovement("done", oct, -3_456, "EUR", "POS fixture C", ExternalKind.CARD_PAYMENT),
        )
        val imported = Archive(accounts = accounts).withImportBatch(ImportBatch(ImportProvider.ISYBANK, "Conto", Instant.now(), oct, oct, rows), bank.id).archive
        val ids = imported.externalMovements.associateBy { it.description }
        val manual = LedgerEvent("e1", oct.plusDays(1), 1, EventType.PERSONAL_CONTRIBUTION, Money(5_000), Confidence.DOCUMENT, "fixture", accountId = bank.id)
        val archive = imported.copy(
            events = imported.events + manual,
            externalMovements = imported.externalMovements.map { if (it.description == "POS fixture C") it.copy(categoryId = "x", categorySource = CategorySource.AGENT) else it },
        )
        val held = setOf(ids.getValue("POS fixture A").id, ids.getValue("POS fixture C").id)
        val t = archive.toCheck { it in held }
        assertEquals("the manual record makes one possible duplicate", 1, t.duplicates)
        assertEquals(0, t.transfers)
        assertEquals("only the held, still uncategorized one is ambiguous", 1, t.ambiguous)
        assertEquals(2, t.count)
        assertEquals("nothing held: not yet looked at is not counted", 1, archive.toCheck().count)
    }

    @Test fun incomeCardNeedsAConfirmedRuleMatchingACreditInItsWindow() {
        val day = LocalDate.of(2026, 10, 27)
        val rows = listOf(
            ImportedMovement("p", day.minusDays(1), 150_000, "EUR", "Accredito ENTE FIXTURE", ExternalKind.DEPOSIT),
            ImportedMovement("f", day.minusDays(2), 40_000, "EUR", "Fattura cliente fixture", ExternalKind.DEPOSIT),
        )
        val imported = Archive(accounts = accounts).withImportBatch(ImportBatch(ImportProvider.ISYBANK, "Conto", Instant.now(), day.minusDays(3), day, rows), bank.id).archive
        val rule = IncomeRule("r1", "Pensione", bank.id, "ENTE FIXTURE", 20, 28, 100_000, 200_000, confirmedAt = null)
        assertNull("an unconfirmed rule opens no card", imported.copy(planning = imported.planning.copy(incomeRules = listOf(rule))).incomeArrival(day))
        val confirmed = imported.copy(planning = imported.planning.copy(incomeRules = listOf(rule.copy(confirmedAt = "2026-10-01T00:00:00Z"))))
        val arrival = confirmed.incomeArrival(day)
        assertEquals("Pensione", arrival?.rule?.title)
        assertEquals(150_000L, arrival?.movement?.signedCents)
        assertNull("outside the recent window", confirmed.incomeArrival(day.plusDays(10)))
    }
}
