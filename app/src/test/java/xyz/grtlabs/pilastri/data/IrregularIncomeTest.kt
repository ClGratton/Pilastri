package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.domain.rules.Shipped
import java.time.LocalDate
import java.time.YearMonth

/** The pure data layer of the irregular-income blocks. Synthetic data only. */
class IrregularIncomeTest {
    private val bank = Account("bank", "Conto", 1, "fixture")
    private val savings = Account("sav", "Risparmio", 1, "fixture")
    private val broker = Account("brk", "Titoli", 2, "fixture")
    private val at = "2026-10-31T10:00:00Z"
    private val oct = YearMonth.of(2026, 10)
    private fun d(day: Int, month: Int = 10) = LocalDate.of(2026, month, day)

    private fun mv(
        id: String, date: LocalDate, cents: Long, description: String = "Bonifico da Rossi Srl", kind: ExternalKind = ExternalKind.DEPOSIT,
        status: ExternalStatus = ExternalStatus.PENDING, account: String = "bank", role: MovementRole? = null,
    ) = ExternalMovement("ISYBANK:$id", ImportProvider.ISYBANK, account, date, cents, "EUR", description, kind, null, null, null, status, null, at, role = role)

    private fun archive(vararg m: ExternalMovement, connections: List<Connection> = emptyList(), positions: List<IncomePosition> = emptyList()) =
        Archive(accounts = listOf(bank, savings, broker), externalMovements = m.toList(), connections = connections, positions = positions)

    private fun covering(from: LocalDate, to: LocalDate) = listOf(Connection(ImportProvider.ISYBANK, "bank", at, from, to, null))

    private fun ids(list: List<ExternalMovement>) = list.map { it.id.removePrefix("ISYBANK:") }.toSet()

    // ---- positions ----

    @Test fun irregularAndF24KindsFollowThePositionKind() {
        fun pos(id: String, k: PositionKind) = IncomePosition(id, coded(k))
        val a = archive(positions = listOf(pos("p1", PositionKind.AUTONOMO_FORFETTARIO), pos("p2", PositionKind.DIPENDENTE_PRIVATO), pos("p3", PositionKind.COLLABORATORE)))
        assertEquals(listOf("p1", "p3"), a.irregularPositions().map { it.id })
        assertEquals(setOf(PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO), F24Kinds)
        assertTrue(PositionKind.COLLABORATORE in IrregularKinds && PositionKind.COLLABORATORE !in F24Kinds)
    }

    // ---- credits ----

    @Test fun creditsKeepOnlyIncomeLookingCreditsOfTheMonth() {
        val a = archive(
            mv("ok1", d(5), 100_000, "Bonifico da Rossi Srl - fattura 1"),
            mv("ok2", d(8), 50_000, kind = ExternalKind.OTHER, role = MovementRole.INCOME),
            mv("ok3", d(9), 20_000, role = MovementRole.SALARY),
            mv("refund", d(6), 2_000, "Rimborso ordine 123", ExternalKind.OTHER),
            mv("ignored", d(7), 30_000, status = ExternalStatus.IGNORED),
            mv("ownTransfer", d(10), 40_000, role = MovementRole.OWN_TRANSFER),
            mv("spend", d(10), 41_000, role = MovementRole.SPEND),
            mv("interest", d(11), 300, kind = ExternalKind.INTEREST),
            mv("outflow", d(12), -5_000, kind = ExternalKind.WITHDRAWAL),
            mv("onBroker", d(13), 60_000, account = "brk"),
            mv("september", d(20, 9), 70_000),
            mv("pairOut", d(14), -80_000, "Bonifico al risparmio", ExternalKind.WITHDRAWAL),
            mv("pairIn", d(15), 80_000, "Bonifico dal conto", account = "sav"),
        )
        assertEquals(setOf("ok1", "ok2", "ok3"), ids(a.irregularCredits(oct)))
        assertEquals(listOf("pairIn"), a.transferPairs().keys.filter { it.endsWith("In") }.map { it.removePrefix("ISYBANK:") })
        assertEquals(setOf("september"), ids(a.irregularCredits(YearMonth.of(2026, 9))))
    }

    @Test fun aCreditAlreadyFiledAsATransferIsNotIncome() {
        val a = archive(
            mv("out", d(14), -80_000, "Bonifico al risparmio", ExternalKind.WITHDRAWAL),
            mv("in", d(15), 80_000, "Bonifico dal conto", account = "sav"),
            mv("real", d(16), 10_000),
        ).withExternalDecision("ISYBANK:in", ExternalDecision.AsTransfer("ISYBANK:out"))
        assertEquals(1, a.transfers.size)
        assertEquals(setOf("real"), ids(a.irregularCredits(oct)))
    }

    // ---- bars ----

    @Test fun barsAreDataNoneOrNd() {
        val a = archive(
            mv("oct", d(5), 100_000), mv("oct2", d(9), 20_000),
            mv("aug", d(10, 8), 50_000),
            mv("sepRefund", d(3, 9), 900, "Rimborso", ExternalKind.OTHER),
            connections = covering(d(15, 6), d(20)),
        )
        val bars = a.incomeBars(oct, d(20))
        assertEquals(12, bars.size)
        assertEquals(YearMonth.of(2025, 11), bars.first().month)
        assertEquals(oct, bars.last().month)
        val by = bars.associateBy { it.month }
        assertEquals(IncomeBar(oct, 120_000, BarState.DATA), by.getValue(oct))
        assertEquals(IncomeBar(YearMonth.of(2026, 8), 50_000, BarState.DATA), by.getValue(YearMonth.of(2026, 8)))
        assertEquals(BarState.NONE, by.getValue(YearMonth.of(2026, 9)).state)
        assertEquals(BarState.NONE, by.getValue(YearMonth.of(2026, 7)).state)
        assertEquals(BarState.NONE, by.getValue(YearMonth.of(2026, 6)).state)
        assertEquals(BarState.ND, by.getValue(YearMonth.of(2026, 5)).state)
        assertEquals(BarState.ND, bars.first().state)
        assertEquals(0L, by.getValue(YearMonth.of(2026, 5)).cents)
    }

    @Test fun withoutCoverageOnlyRecordedMonthsHaveData() {
        val bars = archive(mv("oct", d(5), 100_000)).incomeBars(oct, d(20))
        assertEquals(BarState.DATA, bars.last().state)
        assertTrue(bars.dropLast(1).all { it.state == BarState.ND })
    }

    @Test fun theCurrentMonthIsNoneWhileCoveredAndEmpty() {
        val bars = archive(connections = covering(d(1, 10), d(2))).incomeBars(oct, d(2))
        assertEquals(BarState.NONE, bars.last().state)
        assertEquals(BarState.ND, bars[10].state)
    }

    // ---- payers ----

    @Test fun payerOfReadsTheSenderAndCutsAtMarkers() {
        assertEquals("Rossi Srl", payerOf("Bonifico da Rossi Srl - Fattura 12"))
        assertEquals("ROSSI", payerOf("BONIFICO DA ROSSI"))
        assertEquals("Bianchi Mario", payerOf("Bonifico SEPA ordinante: Bianchi Mario causale: saldo"))
        assertEquals("Verdi SpA", payerOf("Accredito MITTENTE: Verdi SpA; CRO 123"))
        assertEquals("Neri Studio", payerOf("Bonifico da Neri Studio | rif. 7"))
        assertEquals("Gialli", payerOf("Bonifico da Gialli CRO 998877"))
        assertEquals("Blu Sas", payerOf("Bonifico da Blu Sas TRN 55"))
        assertEquals("Verde", payerOf("Da Verde"))
    }

    @Test fun payerOfIsNullWhenNothingReadableFollows() {
        assertNull(payerOf("Accredito stipendio"))
        assertNull(payerOf("Bonifico da  - fattura 1"))
        assertNull(payerOf("ordinante:"))
        assertNull(payerOf("Bonifico da causale: onorario"))
        assertNull(payerOf(""))
    }

    @Test fun compensiGroupByPayerAndKeepUnknownSendersApart() {
        val a = archive(
            mv("a", d(5), 100_000, "Bonifico da Rossi Srl - fattura 1"),
            mv("b", d(12), 20_000, "Bonifico da ROSSI SRL causale acconto"),
            mv("c", d(7), 30_000, "Bonifico SEPA ordinante: Bianchi Mario"),
            mv("d", d(9), 5_000, "Accredito generico"),
            mv("e", d(10), 1_000, "Altro accredito"),
        )
        val c = a.compensi(oct)
        assertEquals(listOf("ROSSI SRL", "Bianchi Mario", null), c.map { it.payer })
        assertEquals(PayerTotal("ROSSI SRL", 120_000, 2, d(12)), c[0])
        assertEquals(PayerTotal("Bianchi Mario", 30_000, 1, d(7)), c[1])
        assertEquals(PayerTotal(null, 6_000, 2, d(10)), c[2])
        assertEquals(emptyList<PayerTotal>(), a.compensi(YearMonth.of(2026, 9)))
    }

    // ---- F24 ----

    private val due = LocalDate.of(2026, 6, 30)
    private fun out(date: LocalDate, text: String, kind: ExternalKind = ExternalKind.WITHDRAWAL, status: ExternalStatus = ExternalStatus.PENDING) =
        mv("f${date.toEpochDay()}", date, -50_000, text, kind, status)

    @Test fun f24StatusWindowEdgesAreInclusive() {
        val today = LocalDate.of(2026, 6, 1)
        assertEquals(F24State.PAGATO, f24Status(due, today, listOf(out(due.minusDays(15), "Pagamento F24 saldo"))))
        assertEquals(F24State.DA_PAGARE, f24Status(due, today, listOf(out(due.minusDays(16), "Pagamento F24 saldo"))))
        assertEquals(F24State.PAGATO, f24Status(due, today, listOf(out(due.plusDays(40), "Pagamento F24 saldo"))))
        assertEquals(F24State.DA_PAGARE, f24Status(due, today, listOf(out(due.plusDays(41), "Pagamento F24 saldo"))))
    }

    @Test fun f24StatusThreeStates() {
        val paid = out(due.minusDays(1), "Addebito Agenzia delle Entrate")
        assertEquals(F24State.PAGATO, f24Status(due, LocalDate.of(2026, 5, 1), listOf(paid)))
        assertEquals(F24State.PAGATO, f24Status(due, LocalDate.of(2026, 9, 1), listOf(out(due, "Pagamento Agenzia Entrate"))))
        assertEquals(F24State.DA_PAGARE, f24Status(due, LocalDate.of(2026, 5, 1), emptyList()))
        assertEquals(F24State.DA_PAGARE, f24Status(due, due, emptyList()))
        assertEquals(F24State.NON_RILEVATO, f24Status(due, due.plusDays(1), emptyList()))
    }

    @Test fun f24StatusNeedsF24OrAgenziaTextNotJustTheTaxKind() {
        val today = due.plusDays(5)
        assertEquals(F24State.NON_RILEVATO, f24Status(due, today, listOf(out(due, "Imposta", ExternalKind.TAX))))
        assertEquals(F24State.NON_RILEVATO, f24Status(due, today, listOf(out(due, "Pagamento F24 saldo", status = ExternalStatus.IGNORED))))
        assertEquals(F24State.NON_RILEVATO, f24Status(due, today, listOf(out(due, "Pagamento ritenute f24x"))))
        assertEquals(F24State.NON_RILEVATO, f24Status(due, today, listOf(mv("in", due, 50_000, "Rimborso F24"))))
    }

    @Test fun f24LinesMergeSaldoAndFirstAccontoAndReadPaymentsFromLiquidity() {
        val a = archive(
            mv("p", due, -50_000, "Pagamento F24 saldo e acconto", ExternalKind.TAX),
            mv("onBroker", d(20, 11), -10_000, "F24", account = "brk"),
        )
        val lines = a.f24Lines(Shipped.rules, LocalDate.of(2026, 7, 15))
        assertEquals(listOf("F24 saldo e 1° acconto", "F24 2° acconto"), lines.map { it.label })
        assertEquals(due, lines[0].due)
        assertEquals(F24State.PAGATO, lines[0].state)
        assertNull(lines[0].daysLeft)
        assertEquals(LocalDate.of(2026, 11, 30), lines[1].due)
        assertEquals(F24State.DA_PAGARE, lines[1].state)
        assertEquals(138L, lines[1].daysLeft)
        assertTrue(lines.all { it.amountCents == null })
    }

    @Test fun f24LinesAddNextYearOnlyOnceEveryDateHasPassed() {
        val a = archive()
        assertEquals(2, a.f24Lines(Shipped.rules, LocalDate.of(2026, 11, 30)).size)
        val after = a.f24Lines(Shipped.rules, LocalDate.of(2026, 12, 5))
        assertEquals(listOf("F24 saldo e 1° acconto", "F24 2° acconto", "F24 saldo e 1° acconto", "F24 2° acconto"), after.map { it.label })
        assertEquals(F24State.NON_RILEVATO, after[1].state)
        assertEquals(F24State.DA_PAGARE, after[2].state)
    }

    // ---- accantonare ----

    @Test fun accantonareReadyNeedsEveryRowVerified() {
        assertFalse(accantonareReady(emptyList()))
        assertFalse(accantonareReady(listOf(PackStatus.VERIFIED, null)))
        assertFalse(accantonareReady(listOf(PackStatus.VERIFIED, PackStatus.CARRIED)))
        assertTrue(accantonareReady(listOf(PackStatus.VERIFIED, PackStatus.VERIFIED)))
    }

    @Test fun accantonareStaysClosedWhileTheContributionRowIsMissing() {
        val statuses = Shipped.rules.accantonareStatuses(LocalDate.of(2026, 10, 31))
        assertEquals(3, statuses.size)
        assertNull(statuses.last())
        assertFalse(accantonareReady(statuses))
    }
}
