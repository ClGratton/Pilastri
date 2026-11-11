package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures as F

class PlanAssemblerTest {
    private fun build(vararg ms: MailMessage): PlanAssembler.Output {
        val facts = ms.flatMap { (InstallmentMailParser.parse(it) as? Parsed.Facts)?.facts.orEmpty() }
        return PlanAssembler.build(facts, "2026-11-10T09:00:00Z")
    }

    private fun d(s: String) = LocalDate.parse(s)
    private fun InstallmentPlan.part(seq: Int) = installments.single { it.seq == seq }

    @Test fun paypalLifecycleConfirmationReminderAndPaidNotice() {
        val out = build(F.paypalPlan, F.paypalReminder, F.paypalPaid)
        val plan = out.plans.single()
        assertEquals("paypal:7AB12345CD678901E", plan.id)
        assertEquals(InstallmentSource.GMAIL, plan.source)
        assertEquals(PlanReview.PENDING, plan.review)
        assertFalse("read by deterministic parsers, not by the assistant", plan.extractedByAgent)
        assertEquals("EUR", plan.currency)
        assertEquals(3, plan.count); assertEquals(10_000L, plan.totalCents); assertEquals("Negozio Esempio", plan.merchant)
        assertEquals(InstallmentState.PAID, plan.part(1).state)
        assertEquals(InstallmentState.PAID, plan.part(2).state)
        assertEquals(d("2026-12-05"), plan.part(2).paidOn)
        assertEquals(InstallmentState.DUE, plan.part(3).state)
        assertEquals(d("2027-01-05"), plan.part(3).dueDate)
        assertEquals(3333L, plan.remainingCents)
        assertEquals(listOf("gmail:p1", "gmail:p2", "gmail:p3"), plan.evidence.map { it.ref })
        assertTrue(plan.evidence.all { it.summary.length <= 200 && it.summary.isNotBlank() })
        assertTrue("the plan is still active while a part is open", plan.active)
    }

    @Test fun paidNoticesAreMatchedToTheNumberedPartAndEmittedOnce() {
        val out = build(F.paypalPlan, F.paypalPaid)
        assertEquals(listOf(1 to 3334L, 2 to 3333L), out.payments.map { it.seq to it.cents })
        assertTrue(out.payments.all { it.planId == "paypal:7AB12345CD678901E" })
        assertEquals("gmail:p3", out.payments.single { it.seq == 2 }.evidence.ref)
        assertEquals(d("2026-12-05"), out.payments.single { it.seq == 2 }.paidOn)
    }

    @Test fun anUnnumberedPaidNoticeTakesTheEarliestOpenPartWithTheSameAmount() {
        val plan = F.klarnaPlan
        val paid = F.msg("k5", F.KLARNA, "Pagamento ricevuto", "2026-12-05", """
            Abbiamo ricevuto il tuo pagamento di 33,30 €.
            Riferimento ordine: KL-2026-778899
        """)
        val out = build(plan, paid)
        val p = out.plans.single()
        assertEquals(InstallmentState.PAID, p.part(1).state)
        assertEquals("part 1 was collected at checkout, so the notice is part 2", InstallmentState.PAID, p.part(2).state)
        assertEquals(InstallmentState.DUE, p.part(3).state)
        assertEquals(2, out.payments.single { it.evidence.ref == "gmail:k5" }.seq)
    }

    @Test fun theSamePaymentSeenTwiceDoesNotPayTwoParts() {
        val a = F.msg("k5", F.KLARNA, "Pagamento ricevuto", "2026-12-05", "Abbiamo ricevuto il tuo pagamento di 33,30 €.\nRiferimento ordine: KL-2026-778899")
        val b = F.msg("k6", F.KLARNA, "Ricevuta di pagamento", "2026-12-05", "Abbiamo ricevuto il tuo pagamento di 33,30 €.\nRiferimento ordine: KL-2026-778899")
        val p = build(F.klarnaPlan, a, b).plans.single()
        assertEquals(2, p.installments.count { it.state == InstallmentState.PAID })
        assertEquals(InstallmentState.DUE, p.part(3).state)
    }

    @Test fun aReminderWithoutANumberMatchesThePartByDate() {
        val p = build(F.klarnaPlan, F.klarnaReminder).plans.single()
        assertEquals(3, p.installments.size)
        assertEquals(d("2026-12-05"), p.part(2).dueDate)
        assertEquals(InstallmentState.DUE, p.part(2).state)
    }

    @Test fun cancellationClosesOpenPartsButKeepsPaidOnes() {
        val p = build(F.klarnaPlan, F.klarnaReminder, F.klarnaCancelled).plans.single()
        assertEquals(InstallmentState.PAID, p.part(1).state)
        assertEquals(InstallmentState.CANCELLED, p.part(2).state)
        assertEquals(InstallmentState.CANCELLED, p.part(3).state)
        assertEquals(0L, p.remainingCents)
        assertFalse("nothing left to pay", p.active)
    }

    @Test fun aPartialRefundOnlyAddsEvidence() {
        val partial = F.msg("r1", F.KLARNA, "Rimborso parziale per il tuo ordine", "2026-12-09", "Rimborso parziale: € 10,00\nRiferimento ordine: KL-2026-778899")
        val p = build(F.klarnaPlan, partial).plans.single()
        assertTrue(p.installments.none { it.state == InstallmentState.CANCELLED })
        assertTrue(p.evidence.any { it.ref == "gmail:r1" })
    }

    @Test fun aLateNoticeMarksThePartLateOnlyBecauseTheLenderSaidSo() {
        val p = build(F.scalapayPlan, F.scalapayReminder, F.scalapayLate).plans.single()
        assertEquals(InstallmentState.LATE, p.part(2).state)
        assertEquals(InstallmentState.DUE, p.part(3).state)
        assertTrue("LATE parts are still open", p.open.any { it.seq == 2 })
    }

    @Test fun aPartPaidAfterBeingLateIsPaid() {
        val paid = F.msg("s4", F.SCALAPAY, "Pagamento ricevuto", "2026-12-14", "Hai pagato la rata 2 di 3 di € 20,00 per l'ordine #SC554433.")
        val p = build(F.scalapayPlan, F.scalapayLate, paid).plans.single()
        assertEquals(InstallmentState.PAID, p.part(2).state)
        assertEquals(d("2026-12-14"), p.part(2).paidOn)
    }

    @Test fun planIdsAreStableAcrossRescansAndAcrossWhichMailsAreInTheWindow() {
        val first = build(F.paypalPlan, F.paypalReminder, F.paypalPaid).plans.single().id
        val second = build(F.paypalPaid, F.paypalPlan).plans.single().id
        val onlyReminder = build(F.paypalReminder).plans.single().id
        assertEquals("paypal:7AB12345CD678901E", first)
        assertEquals(first, second)
        assertEquals("a reminder with the lender's id lands on the same plan", first, onlyReminder)
    }

    @Test fun withoutAnIdTheIdIsAHashOfLenderMerchantDateAndTotal() {
        val m = F.msg("x1", F.KLARNA, "Il tuo piano di pagamento per l'ordine da Shop Esempio", "2026-11-05", """
            Paga in 3 rate senza interessi. Acquisto da Shop Esempio.
            Totale: 99,90 €
            Oggi 33,30 €
            5 dic 2026 33,30 €
            4 gen 2027 33,30 €
        """)
        val id = build(m).plans.single().id
        assertTrue(id, id.matches(Regex("klarna:h[0-9a-f]{20}")))
        assertEquals(id, build(m).plans.single().id)
        assertEquals("another message id, same purchase: same plan", id, build(m.copy(id = "x2")).plans.single().id)
        assertFalse("another total, another purchase", id == build(m.copy(text = m.text.replace("99,90", "109,90"))).plans.single().id)
        assertEquals(id, PlanAssembler.hashId("klarna", "shopesempio", LocalDate.parse("2026-11-05"), 9_990L))
    }

    @Test fun twoPurchasesFromTheSameShopStaySeparatePlans() {
        val a = F.msg("x1", F.KLARNA, "Il tuo piano di pagamento per l'ordine da Shop Esempio", "2026-11-05", "Paga in 3 rate. Acquisto da Shop Esempio.\nTotale: 99,90 €\nOggi 33,30 €\n5 dic 2026 33,30 €\n4 gen 2027 33,30 €")
        val b = a.copy(id = "x2", date = LocalDate.parse("2026-11-20"), text = a.text.replace("99,90", "60,00").replace("33,30", "20,00"))
        assertEquals(2, build(a, b).plans.size)
    }

    @Test fun aReminderWithNoIdAttachesOnlyToAnUnambiguousPlan() {
        val other = F.msg("x2", F.KLARNA, "Il tuo piano di pagamento per l'ordine da Altro Negozio", "2026-11-06", "Paga in 3 rate. Acquisto da Altro Negozio.\nTotale: 99,90 €\nOggi 33,30 €\n6 dic 2026 33,30 €\n6 gen 2027 33,30 €")
        val mine = F.msg("x1", F.KLARNA, "Il tuo piano di pagamento per l'ordine da Shop Esempio", "2026-11-05", "Paga in 3 rate. Acquisto da Shop Esempio.\nTotale: 99,90 €\nOggi 33,30 €\n5 dic 2026 33,30 €\n4 gen 2027 33,30 €")
        val reminderNamed = F.msg("x3", F.KLARNA, "Promemoria di pagamento da Shop Esempio", "2026-12-03", "Il pagamento di 33,30 € verrà addebitato il 5 dic 2026. Acquisto da Shop Esempio.")
        val reminderAnonymous = F.msg("x4", F.KLARNA, "Promemoria di pagamento", "2026-12-03", "Il pagamento di 33,30 € verrà addebitato il 5 dic 2026.")
        val named = build(mine, other, reminderNamed)
        assertEquals(2, named.plans.size)
        assertEquals("matched by merchant", d("2026-12-05"), named.plans.single { it.merchant == "Shop Esempio" }.part(2).dueDate)
        assertTrue(named.plans.single { it.merchant == "Shop Esempio" }.evidence.any { it.ref == "gmail:x3" })
        val anonymous = build(mine, other, reminderAnonymous)
        assertEquals("two candidate plans with the same amount: not attached to either", 1, anonymous.unmatched)
        assertTrue(anonymous.plans.none { p -> p.evidence.any { it.ref == "gmail:x4" } })
    }

    @Test fun aStubPlanIsCreatedFromAReminderWhoseConfirmationIsOutsideTheWindow() {
        val p = build(F.paypalReminder).plans.single()
        assertEquals("paypal:7AB12345CD678901E", p.id)
        assertNull("no total was stated", p.totalCents)
        assertEquals(3, p.count)
        assertEquals(listOf(2), p.installments.map { it.seq })
        assertEquals("two parts of the plan were never seen: their amounts stay unknown, not zero", 2, p.remainingUnknown)
    }

    @Test fun nothingIsRecordedForACancellationOfAnUnknownPlan() {
        val out = build(F.klarnaCancelled)
        assertTrue(out.plans.isEmpty())
    }

    @Test fun everyPlanAndPaymentSatisfiesTheDataContract() {
        val all = listOf(
            F.paypalPlan, F.paypalReminder, F.paypalPaid, F.klarnaPlan, F.klarnaReminder, F.klarnaThirtyDays, F.klarnaCancelled, F.scalapayPlan,
            F.scalapayReminder, F.scalapayLate, F.heylightPlan, F.compassPlan, F.findomesticReminder, F.findomesticPaid, F.cofidisPlan,
            F.cofidisCancelled, F.younitedPlan, F.younitedPaid, F.cardPlan,
        )
        val out = build(*all.toTypedArray())
        assertEquals(0, out.invalid)
        assertTrue(out.plans.isNotEmpty())
        assertEquals(out.plans.map { it.id }.distinct().size, out.plans.size)
        assertTrue(out.plans.all { it.source == InstallmentSource.GMAIL && it.review == PlanReview.PENDING && it.currency == "EUR" })
        assertTrue(out.payments.all { p -> out.plans.any { it.id == p.planId } })
    }
}
