package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures as F

/** One test group per lender, on synthetic samples (see [GmailFixtures]). */
class LenderParsersTest {
    private fun facts(m: MailMessage): List<MailFact> = (InstallmentMailParser.parse(m) as Parsed.Facts).facts
    private fun one(m: MailMessage): MailFact = facts(m).single()
    private fun d(s: String) = LocalDate.parse(s)

    // ---- PayPal Paga in 3 rate ----------------------------------------------------------------------------------------------------
    @Test fun paypalConfirmationGivesPlanWithCheckoutPartPaid() {
        val f = one(F.paypalPlan)
        assertEquals(FactKind.PLAN, f.kind)
        assertEquals("7AB12345CD678901E", f.orderKey)
        assertEquals("Negozio Esempio", f.merchant)
        assertEquals(10_000L, f.totalCents)
        assertEquals(3, f.count)
        assertEquals("PayPal Paga in 3 rate", f.lenderLabel)
        assertEquals(listOf(1, 2, 3), f.parts.map { it.seq })
        assertEquals(listOf(3334L, 3333L, 3333L), f.parts.map { it.cents })
        assertEquals(listOf(d("2026-11-05"), d("2026-12-05"), d("2027-01-05")), f.parts.map { it.due })
        assertEquals("only the part dated today is collected at checkout", listOf(true, false, false), f.parts.map { it.paid })
        assertEquals(d("2026-11-05"), f.parts[0].paidOn)
    }

    @Test fun paypalReminderNamesThePartAndTheDueDate() {
        val f = one(F.paypalReminder)
        assertEquals(FactKind.REMINDER, f.kind)
        assertEquals("7AB12345CD678901E", f.orderKey)
        val p = f.parts.single()
        assertEquals(2, p.seq); assertEquals(3, f.count)
        assertEquals(d("2026-12-05"), p.due); assertEquals(3333L, p.cents); assertFalse(p.late)
    }

    @Test fun paypalPaidNoticeAlsoCarriesTheNextPart() {
        val fs = facts(F.paypalPaid)
        assertEquals(listOf(FactKind.PAID, FactKind.REMINDER), fs.map { it.kind })
        val paid = fs[0].parts.single()
        assertEquals(2, paid.seq); assertEquals(3333L, paid.cents); assertTrue(paid.paid); assertEquals(d("2026-12-05"), paid.paidOn)
        val next = fs[1].parts.single()
        assertEquals(3, next.seq); assertEquals(d("2027-01-05"), next.due); assertEquals(3333L, next.cents)
    }

    @Test fun paypalMailAboutOtherThingsIsIgnored() {
        assertEquals(Parsed.Skip, InstallmentMailParser.parse(F.paypalUnrelated))
    }

    // ---- Klarna ---------------------------------------------------------------------------------------------------------------------
    @Test fun klarnaThreeInstallments() {
        val f = one(F.klarnaPlan)
        assertEquals(FactKind.PLAN, f.kind)
        assertEquals("KL-2026-778899", f.orderKey)
        assertEquals("Shop Esempio", f.merchant)
        assertEquals(9_990L, f.totalCents)
        assertEquals(3, f.count)
        assertEquals(listOf(3330L, 3330L, 3330L), f.parts.map { it.cents })
        assertEquals(listOf(true, false, false), f.parts.map { it.paid })
    }

    @Test fun klarnaThirtyDaysIsASinglePartWithItsDueDate() {
        val f = one(F.klarnaThirtyDays)
        assertEquals(FactKind.PLAN, f.kind)
        assertEquals(1, f.count)
        assertEquals(5_990L, f.totalCents)
        val p = f.parts.single()
        assertEquals(1, p.seq); assertEquals(d("2026-12-06"), p.due); assertEquals(5_990L, p.cents); assertFalse(p.paid)
    }

    @Test fun klarnaReminderReadsARelativeDate() {
        val f = one(F.klarnaReminder)
        assertEquals(FactKind.REMINDER, f.kind)
        assertEquals("KL-2026-778899", f.orderKey)
        val p = f.parts.single()
        assertNull("the mail does not number the part", p.seq)
        assertEquals(d("2026-12-05"), p.due); assertEquals(3330L, p.cents)
    }

    @Test fun klarnaCancellation() {
        val f = one(F.klarnaCancelled)
        assertEquals(FactKind.CANCELLED, f.kind)
        assertEquals("KL-2026-778899", f.orderKey)
        assertFalse(f.partial)
    }

    @Test fun klarnaNewsletterIsNotAPlan() {
        assertEquals(Parsed.Skip, InstallmentMailParser.parse(F.newsletter))
    }

    // ---- Scalapay -------------------------------------------------------------------------------------------------------------------
    @Test fun scalapayPartsAreTheStatedAmountOnTheListedDates() {
        val f = one(F.scalapayPlan)
        assertEquals(FactKind.PLAN, f.kind)
        assertEquals("SC554433", f.orderKey)
        assertEquals("Moda Esempio", f.merchant)
        assertEquals(3, f.count)
        assertNull("no total is stated, none is invented", f.totalCents)
        assertEquals(listOf(2000L, 2000L, 2000L), f.parts.map { it.cents })
        assertEquals(listOf(d("2026-11-12"), d("2026-12-12"), d("2027-01-12")), f.parts.map { it.due })
        assertEquals(listOf(true, false, false), f.parts.map { it.paid })
    }

    @Test fun scalapayOrdinalReminderAndLateNotice() {
        val reminder = one(F.scalapayReminder).parts.single()
        assertEquals(2, reminder.seq); assertEquals(d("2026-12-12"), reminder.due); assertEquals(2000L, reminder.cents); assertFalse(reminder.late)
        val late = one(F.scalapayLate)
        assertEquals("SC554433", late.orderKey)
        val p = late.parts.single()
        assertEquals(2, p.seq); assertEquals(3, late.count); assertTrue("the lender says it failed", p.late); assertEquals(d("2026-12-12"), p.due)
    }

    // ---- HeyLight / Compass / Findomestic / Cofidis / Younited ---------------------------------------------------------------------
    @Test fun heylightFinancingStatesAmountOfEveryPartButDatesOnlyForTheFirst() {
        val f = one(F.heylightPlan)
        assertEquals("HL2026-123456", f.orderKey)
        assertEquals("Elettro Esempio", f.merchant)
        assertEquals(60_000L, f.totalCents)
        assertEquals(12, f.count)
        assertEquals((1..12).toList(), f.parts.map { it.seq })
        assertTrue(f.parts.all { it.cents == 5_250L })
        assertEquals(d("2026-12-05"), f.parts[0].due)
        assertTrue("later due dates are not invented", f.parts.drop(1).all { it.due == null })
        assertTrue("HeyLight does not charge at checkout", f.parts.none { it.paid })
    }

    @Test fun compassHasNoStatedTotalSoNoneIsRecorded() {
        val f = one(F.compassPlan)
        assertEquals("77889900", f.orderKey)
        assertEquals(24, f.count)
        assertNull(f.totalCents)
        assertEquals(5_500L, f.parts.first().cents)
        assertEquals(d("2026-12-05"), f.parts.first().due)
    }

    @Test fun findomesticReminderThenPaidNotice() {
        val r = one(F.findomesticReminder)
        assertEquals("77889900", r.orderKey)
        assertEquals(5, r.parts.single().seq); assertEquals(12, r.count)
        assertEquals(d("2026-12-05"), r.parts.single().due); assertEquals(5_500L, r.parts.single().cents)
        val paid = one(F.findomesticPaid)
        assertEquals(FactKind.PAID, paid.kind)
        assertEquals(5, paid.parts.single().seq); assertEquals(5_500L, paid.parts.single().cents); assertEquals(d("2026-12-06"), paid.parts.single().paidOn)
    }

    @Test fun cofidisFinancingAndItsCancellationAfterAReturn() {
        val plan = one(F.cofidisPlan)
        assertEquals("55667788", plan.orderKey)
        assertEquals("Amazon", plan.merchant)
        assertEquals(45_000L, plan.totalCents)
        assertEquals(9, plan.count)
        assertEquals(d("2026-12-02"), plan.parts.first().due)
        val cancel = one(F.cofidisCancelled)
        assertEquals(FactKind.CANCELLED, cancel.kind)
        assertEquals("55667788", cancel.orderKey)
    }

    @Test fun younitedPlanWithTwoListedDatesAndAPaidNotice() {
        val plan = one(F.younitedPlan)
        assertEquals("YN20261103A", plan.orderKey)
        assertEquals("Casa Esempio", plan.merchant)
        assertEquals(36_000L, plan.totalCents)
        assertEquals(6, plan.count)
        assertEquals(listOf(d("2026-12-05"), d("2027-01-05")), plan.parts.take(2).map { it.due })
        assertEquals((1..6).toList(), plan.parts.map { it.seq })
        val paid = one(F.younitedPaid)
        assertEquals(1, paid.parts.single().seq); assertEquals(6_000L, paid.parts.single().cents); assertEquals(6, paid.count)
    }

    // ---- Credit card, unknown sender ------------------------------------------------------------------------------------------------
    @Test fun cardInstallmentFromAnUnknownSenderWithAnUnmistakableShape() {
        val f = one(F.cardPlan)
        assertEquals(FactKind.PLAN, f.kind)
        assertEquals("bancaesempioexample", f.lender.key)
        assertEquals("OP2026100201", f.orderKey)
        assertEquals("Elettro Esempio", f.merchant)
        assertEquals(30_000L, f.totalCents)
        assertEquals(6, f.count)
        assertEquals(5_000L, f.parts.first().cents)
        assertEquals(d("2026-11-05"), f.parts.first().due)
        assertFalse(f.byAgent)
    }

    @Test fun unknownSendersNeedTheStrictShapeOtherwiseTheyAreOnlyCandidatesForTheAi() {
        assertEquals(Parsed.Unknown, InstallmentMailParser.parse(F.unknownInstallmentMail))
        assertEquals("an ordinary shipping mail is not an installment mail", Parsed.Skip, InstallmentMailParser.parse(F.unrelatedShop))
    }

    // ---- shared vocabulary -----------------------------------------------------------------------------------------------------------
    @Test fun partNumbersAndCountsInManyWordings() {
        assertEquals(2 to 3, InstallmentMailParser.seqCount("Rata 2 di 3"))
        assertEquals(2 to 3, InstallmentMailParser.seqCount("rata n. 2/3"))
        assertEquals(2 to 3, InstallmentMailParser.seqCount("2ª rata di 3"))
        assertEquals(2 to null, InstallmentMailParser.seqCount("la seconda rata"))
        assertEquals(4 to 12, InstallmentMailParser.seqCount("quarta rata di 12"))
        assertEquals(7 to null, InstallmentMailParser.seqCount("Rata 7 € 33,33"))
        assertEquals("a part number above the count is rejected", null to null, InstallmentMailParser.seqCount("rata 5 di 3"))
        assertEquals(null to null, InstallmentMailParser.seqCount("rata 33,33"))
        assertEquals(3, InstallmentMailParser.countIn("Paga in 3 rate"))
        assertEquals(4, InstallmentMailParser.countIn("in quattro rate mensili"))
        assertEquals(12, InstallmentMailParser.countIn("12 rate da € 50"))
        assertNull("30 days is a deadline, not a count", InstallmentMailParser.countIn("Paga in 30 giorni"))
    }

    @Test fun classificationFollowsTheSubjectThenTheBody() {
        assertEquals(FactKind.CANCELLED, InstallmentMailParser.classify("Ordine annullato", ""))
        assertEquals(FactKind.REMINDER, InstallmentMailParser.classify("Promemoria", "annullare l'iscrizione"))
        assertEquals(FactKind.PAID, InstallmentMailParser.classify("Conferma pagamento", ""))
        assertEquals(FactKind.PLAN, InstallmentMailParser.classify("Conferma ordine", "Il primo pagamento è stato addebitato oggi."))
        assertEquals(FactKind.PAID, InstallmentMailParser.classify("Aggiornamento", "Hai pagato la rata 2 di 3."))
        assertEquals(FactKind.REMINDER, InstallmentMailParser.classify("Aggiornamento", "La rata verrà addebitata il 5 dic."))
        assertEquals(FactKind.PLAN, InstallmentMailParser.classify("Aggiornamento", "9 rate da € 52,00; la prima sarà addebitata il 2 dic."))
    }

    @Test fun partialRefundIsNotACancellation() {
        val m = F.msg("r1", F.KLARNA, "Rimborso parziale per il tuo ordine", "2026-12-09", """
            Abbiamo rimborsato parzialmente il tuo ordine da Shop Esempio. Rimborso parziale: € 10,00
            Riferimento ordine: KL-2026-778899
        """)
        val f = one(m)
        assertEquals(FactKind.CANCELLED, f.kind)
        assertTrue(f.partial)
    }

    @Test fun senderDomainsAndLenderLookup() {
        assertEquals("paypal.it", Lenders.senderDomain("PayPal <service@paypal.it>"))
        assertEquals("klarna.com", Lenders.senderDomain("no-reply@klarna.com"))
        assertEquals(Lenders.KLARNA, Lenders.forSender("Klarna <no-reply@mail.klarna.com>"))
        assertNull("a look-alike domain is not Klarna", Lenders.forSender("x <a@klarna.com.evil.example>"))
        assertNull(Lenders.forSender("x <a@notklarna.com>"))
        assertEquals("nexiit", Lenders.generic("Nexi <avvisi@e.nexi.it>").key)
        assertEquals("Nexi", Lenders.generic("Nexi <avvisi@e.nexi.it>").label)
        // A lookalike domain never shares a key with the lender it imitates.
        assertEquals("klarnaco", Lenders.generic("Klarna <no-reply@klarna.co>").key)
        assertEquals("Nexi", Lenders.generic("Nexi <avvisi@e.nexi.it>").label)
    }
}
