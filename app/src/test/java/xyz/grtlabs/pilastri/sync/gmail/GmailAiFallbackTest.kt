package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.ai.AiMessage
import xyz.grtlabs.pilastri.ai.AiProvider
import xyz.grtlabs.pilastri.ai.AiRequest
import xyz.grtlabs.pilastri.ai.AiResponse
import xyz.grtlabs.pilastri.ai.AiStop
import xyz.grtlabs.pilastri.ai.AiTransport
import xyz.grtlabs.pilastri.ai.AiTransportException
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures as F

class GmailAiFallbackTest {
    /** Records every request and answers with scripted replies (a String is a model reply, an exception is thrown). */
    private class FakeAi(private vararg val replies: Any) : AiTransport {
        override val provider = AiProvider.ANTHROPIC
        val requests = ArrayList<AiRequest>()
        override suspend fun send(request: AiRequest): AiResponse {
            requests += request
            return when (val r = replies[minOf(requests.size - 1, replies.lastIndex)]) {
                is AiTransportException -> throw r
                else -> AiResponse(r as String, emptyList(), null, AiStop.END)
            }
        }
    }

    private val planReply = """
        Ecco il JSON:
        {"kind":"plan","merchant":"Negozio Esempio","order_id":"ORD-2026-0042","total":"120.00","count":4,"purchase_date":"2026-11-04",
         "parts":[{"seq":1,"due":"2026-11-04","amount":"30.00","paid":true},{"seq":2,"due":"2026-12-04","amount":"30.00","paid":false},
                  {"seq":3,"due":"2027-01-04","amount":"30.00","paid":false},{"seq":4,"due":"2027-02-04","amount":"30.00","paid":false}]}
    """.trimIndent()

    private fun scanWith(ai: AiTransport?, vararg ms: MailMessage) =
        runBlocking { GmailScanner(FakeMailSource.of(*ms), ai, now = { F.fixedNow }).scan(LocalDate.of(2026, 10, 1), F.today) }

    @Test fun onlyTheSubjectAndARedactedTrimmedTextLeaveTheDevice() {
        val ai = FakeAi(planReply)
        scanWith(ai, F.unknownInstallmentMail)
        val request = ai.requests.single()
        val sent = request.messages.single() as AiMessage.User
        val everything = request.system + "\n" + sent.text
        assertTrue(request.tools.isEmpty())
        assertTrue(sent.text.startsWith("Data del messaggio: 2026-11-04\nOGGETTO: Il tuo ordine è confermato\nTESTO:\n"))
        for (secret in listOf(
            "IT60X0542811101000000123456", "mario.rossi@example.com", "https://", "negozio-esempio.test", "ABCDEF1234567890XYZ",
            "333 1234567", "RSSMRA80A01H501U", "123456789012", "info@", "u1",
        )) assertFalse("must not be sent: $secret", everything.contains(secret))
        assertTrue(sent.text, sent.text.contains("[IBAN]") && sent.text.contains("[TEL]") && sent.text.contains("[CF]") && sent.text.contains("[NUMERO]"))
        assertTrue("amounts and dates stay readable", sent.text.contains("120,00") && sent.text.contains("4 dic 2026"))
        assertTrue(sent.text.length <= 700 + GmailAiFallback.MAX_TEXT_CHARS)
    }

    @Test fun sanitizeIsBoundedAndKeepsAmountsAndDates() {
        val raw = "Totale 33,33 € il 5 dic 2026. " + "x".repeat(10_000)
        val s = GmailAiFallback.sanitize(raw, GmailAiFallback.MAX_TEXT_CHARS)
        assertEquals(GmailAiFallback.MAX_TEXT_CHARS, s.length)
        assertTrue(s.startsWith("Totale 33,33 € il 5 dic 2026."))
        assertEquals("a b", GmailAiFallback.sanitize("a   https://x.test/path?a=1   b", 100))
    }

    @Test fun aValidReplyBecomesAPendingPlanMarkedAsExtractedByTheAssistant() {
        val found = scanWith(FakeAi(planReply), F.unknownInstallmentMail)
        val plan = found.plans.single()
        assertEquals("negozioesempiotest:ORD-2026-0042", plan.id)
        assertTrue(plan.extractedByAgent)
        assertEquals(PlanReview.PENDING, plan.review)
        assertEquals(12_000L, plan.totalCents); assertEquals(4, plan.count); assertEquals("Negozio Esempio", plan.merchant)
        assertEquals(listOf(InstallmentState.PAID, InstallmentState.DUE, InstallmentState.DUE, InstallmentState.DUE), plan.installments.map { it.state })
        assertTrue(plan.evidence.single().summary.contains("letto dall'assistente"))
        assertEquals("gmail:u1", plan.evidence.single().ref)
        assertTrue(found.sync.lastResult!!, found.sync.lastResult!!.contains("assistente: 1/1"))
    }

    @Test fun deterministicallyReadMailsNeverGoToTheAssistant() {
        val ai = FakeAi(planReply)
        val found = scanWith(ai, F.paypalPlan, F.klarnaPlan, F.cardPlan, F.newsletter, F.unrelatedShop)
        assertTrue(ai.requests.isEmpty())
        assertTrue(found.plans.none { it.extractedByAgent })
    }

    @Test fun badRepliesAreIgnoredAndNeverInventValues() {
        for (reply in listOf(
            "non so", "{}", """{"kind":"none"}""", """{"kind":"plan","count":4}""", """{"kind":"plan","count":1000,"total":"10.00"}""",
            """{"kind":"paid"}""", """{"kind":"reminder"}""", """{"kind":"cancelled"}""", "{ not json }",
            """{"kind":"plan","count":3,"total":"-5","parts":[{"seq":1,"amount":"abc","due":"31/02/2026"}]}""",
        )) {
            val found = scanWith(FakeAi(reply), F.unknownInstallmentMail)
            assertTrue("reply: $reply", found.plans.isEmpty())
        }
    }

    @Test fun repliesAreValidatedFieldByField() {
        val msg = F.unknownInstallmentMail
        val lender = Lenders.generic(msg.from)
        val facts = GmailAiFallback.parseReply(
            """{"kind":"plan","merchant":"X","order_id":"no digits","total":1234.5,"count":"3","parts":[{"seq":2,"due":"2026-12-04","amount":"41.15"},{"seq":2,"due":"bad","amount":"41.15"},{"seq":9,"amount":"1,00"}]}""",
            msg, lender,
        )
        val f = facts.single()
        assertEquals(123_450L, f.totalCents)
        assertEquals(3, f.count)
        assertEquals("the merchant needs two characters", null, f.merchant)
        assertEquals("an id without a digit is not an id", null, f.orderKey)
        assertEquals("duplicate part numbers are renumbered in order", listOf(1, 2, 3), f.parts.map { it.seq }.filterNotNull().sorted().take(3))
        assertTrue(f.byAgent)
    }

    @Test fun aPaidReplyNeedsAPartOrAnAmount() {
        val msg = F.unknownInstallmentMail
        val lender = Lenders.generic(msg.from)
        val ok = GmailAiFallback.parseReply("""{"kind":"paid","order_id":"ORD-2026-0042","parts":[{"seq":2,"amount":"30.00"}],"paid_on":"2026-12-04"}""", msg, lender).single()
        assertEquals(FactKind.PAID, ok.kind)
        assertEquals(2, ok.parts.single().seq); assertEquals(LocalDate.of(2026, 12, 4), ok.parts.single().paidOn)
        assertTrue(GmailAiFallback.parseReply("""{"kind":"paid","order_id":"ORD-2026-0042"}""", msg, lender).isEmpty())
    }

    @Test fun anAuthOrQuotaFailureStopsFurtherCallsOtherFailuresAreCounted() {
        val mails = (1..4).map { F.unknownInstallmentMail.copy(id = "u$it", date = LocalDate.of(2026, 11, it)) }.toTypedArray()
        val auth = FakeAi(AiTransportException(AiTransportException.Kind.AUTH, "Chiave API non valida"))
        val found = scanWith(auth, *mails)
        assertEquals("a bad key fails every call: stop after the first", 1, auth.requests.size)
        assertTrue(found.plans.isEmpty())
        assertTrue(found.sync.lastResult!!.contains("1 non riusciti"))

        val flaky = FakeAi(AiTransportException(AiTransportException.Kind.NETWORK, "rete"))
        scanWith(flaky, *mails)
        assertEquals("a network error is per message: the others are still tried", 4, flaky.requests.size)
    }

    @Test fun theNumberOfMessagesSentIsCapped() {
        val mails = (1..30).map { F.unknownInstallmentMail.copy(id = "u$it", date = LocalDate.of(2026, 11, 1 + it % 9)) }.toTypedArray()
        val ai = FakeAi("{}")
        scanWith(ai, *mails)
        assertEquals(ScanLimits().aiMaxMessages, ai.requests.size)
    }

    @Test fun usesTheProvidersDefaultModelUnlessTheHostChoosesOne() {
        val ai = FakeAi("{}")
        runBlocking { GmailScanner(FakeMailSource.of(F.unknownInstallmentMail), ai, null).scan(LocalDate.of(2026, 10, 1), F.today) }
        assertEquals(AiProvider.ANTHROPIC.defaultModel, ai.requests.single().model)
        val chosen = FakeAi("{}")
        runBlocking { GmailScanner(FakeMailSource.of(F.unknownInstallmentMail), chosen, "claude-sonnet-5-5").scan(LocalDate.of(2026, 10, 1), F.today) }
        assertEquals("claude-sonnet-5-5", chosen.requests.single().model)
    }
}
