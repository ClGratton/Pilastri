package xyz.grtlabs.pilastri.sync.eb

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.auto.MemorySecretStore
import xyz.grtlabs.pilastri.sync.auto.SoftwareCipher
import xyz.grtlabs.pilastri.sync.eb.EbFx.page
import xyz.grtlabs.pilastri.sync.eb.EbFx.tx

/** Synthetic data and a scripted Enable Banking only: no network. */
class EbConnectorTest {
    private val eb = FakeEb()
    private val store = EbStateStore(MemorySecretStore(), SoftwareCipher())
    private var now = Instant.parse("2026-10-31T10:00:00Z")
    private val connector = EbConnector(eb.api(), store, clock = { now }, io = Dispatchers.Unconfined)
    private val isybank = EbAspsp("Isybank", "IT", 15_552_000L, listOf("personal"), listOf("redirect"), listOf("Psu-Ip-Address"), false)

    private fun accountCalls() = eb.requests.filter { it.url.encodedPath.startsWith("/accounts/") }
    private fun isTx(r: Request, status: String) = r.url.encodedPath.endsWith("/transactions") && r.url.queryParameter("transaction_status") == status

    private fun dataRoute(session: String = EbFx.SESSION): (Request) -> FakeEb.Reply = { r ->
        when {
            r.url.encodedPath == "/auth" -> FakeEb.Reply(body = EbFx.AUTH)
            r.url.encodedPath == "/sessions" -> FakeEb.Reply(body = session)
            isTx(r, "BOOK") -> FakeEb.Reply(body = page(listOf(tx("12.30", "DBIT"), tx("1000.00", "CRDT", debtor = "Azienda Sintetica", creditor = null))))
            isTx(r, "PDNG") -> FakeEb.Reply(body = page(listOf(tx("4.00", "DBIT", status = "PDNG"))))
            r.url.encodedPath.endsWith("/balances") -> FakeEb.Reply(body = EbFx.balances(EbFx.balance("CLBD", "500.00")))
            else -> FakeEb.Reply(404, """{"error":"NOT_FOUND"}""")
        }
    }

    private fun link(session: String = EbFx.SESSION) {
        eb.route = dataRoute(session)
        connector.saveRegistration(EbFx.APP_ID, EbFx.REDIRECT)
        val start = runBlocking { connector.startLink(isybank) }
        val done = runBlocking { connector.completeLink("${EbFx.REDIRECT}?code=code-1&state=${start.state}", isybank) }
        assertTrue(done is EbLinkResult.Linked)
        eb.requests.clear(); eb.bodies.clear()
    }

    // ---- redirect and ranges --------------------------------------------------------------------------------------

    @Test fun theRedirectIsClaimedOnlyWhenItIsOursAndTheStateMatches() {
        val ok = "${EbFx.REDIRECT}?code=abc%2B1&state=s1"
        assertEquals(EbRedirect.Result.Code("abc+1"), EbRedirect.parse(ok, EbFx.REDIRECT, "s1"))
        assertEquals(EbRedirect.Result.Invalid, EbRedirect.parse(ok, EbFx.REDIRECT, "other"))
        assertEquals(EbRedirect.Result.Invalid, EbRedirect.parse("${EbFx.REDIRECT}?code=abc", EbFx.REDIRECT, "s1"))
        assertEquals(EbRedirect.Result.Invalid, EbRedirect.parse("https://evil.example.test/eb-callback?code=abc&state=s1", EbFx.REDIRECT, "s1"))
        assertEquals(EbRedirect.Result.Invalid, EbRedirect.parse("${EbFx.REDIRECT}/x?code=abc&state=s1", EbFx.REDIRECT, "s1"))
        assertEquals(EbRedirect.Result.Invalid, EbRedirect.parse("${EbFx.REDIRECT}?code=&state=s1", EbFx.REDIRECT, "s1"))
        assertEquals(EbRedirect.Result.Denied("access_denied"), EbRedirect.parse("${EbFx.REDIRECT}?error=access_denied&state=s1", EbFx.REDIRECT, "s1"))
        assertEquals(EbRedirect.Result.Denied(null), EbRedirect.parse("${EbFx.REDIRECT}?error=%3Cscript%3E&state=s1", EbFx.REDIRECT, "s1"))
        assertTrue(EbRedirect.matches("${EbFx.REDIRECT}?anything", EbFx.REDIRECT))
        assertFalse(EbRedirect.matches("not a url", EbFx.REDIRECT))
    }

    @Test fun rangesGoDeepOnlyRightAfterAuthorizationAndNeverPastNinetyDaysOtherwise() {
        val today = LocalDate.of(2026, 10, 31)
        assertEquals(today.minusDays(364), EbRanges.plan(today, null, Duration.ofMinutes(5)).from)
        assertEquals(today.minusDays(89), EbRanges.plan(today, null, Duration.ofHours(3)).from)
        assertEquals(today.minusDays(89), EbRanges.plan(today, null, null).from)
        assertEquals(today.minusDays(89), EbRanges.plan(today, today.minusDays(200), Duration.ofDays(3)).from)
        assertEquals(today.minusDays(10), EbRanges.plan(today, today.minusDays(3), Duration.ofDays(3)).from)
        assertEquals(today, EbRanges.plan(today, null, null).to)
    }

    @Test fun consentEndStaysUnderTheBanksMaximum() {
        assertEquals(now.plusSeconds(15_552_000L - 3600), EbConnector.consentEnd(now, 15_552_000L))
        assertEquals(now.plus(Duration.ofDays(90)).minusSeconds(3600), EbConnector.consentEnd(now, null))
        assertEquals(isybank, EbConnector.findBank(listOf(EbAspsp("Banca Sintetica Due", "IT", null, emptyList(), emptyList(), emptyList(), false), isybank), "isybank"))
        assertNull(EbConnector.findBank(listOf(isybank), "scalable"))
        assertEquals(ImportProvider.ISYBANK, EbConnector.defaultProviderFor("Isybank"))
        assertNull(EbConnector.defaultProviderFor("Banca Sintetica Due"))
    }

    // ---- link -----------------------------------------------------------------------------------------------------

    @Test fun linkingChecksTheStateThenStoresTheSessionAndKeepsAssignments() {
        eb.route = dataRoute()
        assertThrows(IllegalArgumentException::class.java) { connector.saveRegistration(EbFx.APP_ID, "http://pilastri.example.test/cb") }
        connector.saveRegistration(EbFx.APP_ID, EbFx.REDIRECT)
        val start = runBlocking { connector.startLink(isybank) }
        val body = JSONObject(eb.bodies.single())
        assertEquals(start.state, body.getString("state"))
        assertEquals(EbFx.REDIRECT, body.getString("redirect_url"))
        assertEquals(now.plusSeconds(15_552_000L - 3600), start.validUntil)
        assertEquals(start.state, connector.local().pending!!.state)

        val wrong = runBlocking { connector.completeLink("${EbFx.REDIRECT}?code=code-1&state=wrong", isybank) }
        assertEquals(EbLinkResult.Ignored, wrong)
        assertEquals(listOf("POST /auth"), eb.paths())

        val linked = runBlocking { connector.completeLink("${EbFx.REDIRECT}?code=code-1&state=${start.state}", isybank) } as EbLinkResult.Linked
        assertEquals(listOf("hash-a", "hash-b"), linked.accounts.map { it.key })
        val local = connector.local()
        assertNull(local.pending)
        assertEquals("sess-synthetic-1", local.session!!.sessionId)
        assertEquals(listOf("Psu-Ip-Address"), local.session!!.requiredPsuHeaders)
        assertEquals(EbRedirect.Result.Invalid, EbRedirect.parse("${EbFx.REDIRECT}?code=code-1&state=${start.state}", EbFx.REDIRECT, "x"))

        connector.assign(linked.accounts[0], "app-1")
        val again = runBlocking { connector.startLink(isybank) }
        runBlocking { connector.completeLink("${EbFx.REDIRECT}?code=code-2&state=${again.state}", isybank) }
        assertEquals(mapOf("hash-a" to "app-1"), connector.local().assignments)
    }

    @Test fun aDeclinedAuthorizationClearsThePendingStateAndExchangesNothing() {
        eb.route = dataRoute()
        connector.saveRegistration(EbFx.APP_ID, EbFx.REDIRECT)
        val start = runBlocking { connector.startLink(isybank) }
        val r = runBlocking { connector.completeLink("${EbFx.REDIRECT}?error=access_denied&state=${start.state}", isybank) }
        assertEquals(EbLinkResult.Denied("access_denied"), r)
        assertNull(connector.local().pending)
        assertEquals(listOf("POST /auth"), eb.paths())
    }

    // ---- sync -----------------------------------------------------------------------------------------------------

    @Test fun anUnattendedSyncCostsTwoCallsPerAccountAndSendsNoPsuHeaders() {
        link()
        connector.assign(EbLinkedAccount("uid-a", "hash-a", "x", "EUR"), "app-1")
        val outcome = runBlocking { connector.sync(coveredTo = { null }, psuHeaders = mapOf("Psu-User-Agent" to "Pilastri/test")) } as EbSyncOutcome.Done
        assertEquals(4, accountCalls().size)
        assertTrue(accountCalls().none { isTx(it, "PDNG") })
        assertTrue(accountCalls().all { it.header("Psu-User-Agent") == null && it.header("Authorization") == "Bearer synthetic-token" })
        assertEquals(listOf("app-1", null), outcome.accounts.map { it.appAccountId })
        val first = outcome.accounts[0].result as EbReadResult.Ok
        assertEquals(2, first.batch.movements.size)
        assertEquals(ImportProvider.ISYBANK, first.batch.provider)
        assertEquals(50_000L, first.batch.balance!!.cents)
        assertFalse(first.pendingComplete)
        assertEquals(LocalDate.of(2026, 10, 31), first.batch.coverageTo)
        assertEquals(LocalDate.of(2026, 10, 31).minusDays(364), first.batch.coverageFrom)
    }

    @Test fun aSyncThePersonTriggersAddsPendingRowsAndPsuHeadersWhenAllRequiredAreKnown() {
        link()
        val headers = mapOf("Psu-User-Agent" to "Pilastri/test", "Psu-Ip-Address" to "192.0.2.7")
        val outcome = runBlocking { connector.sync(coveredTo = { null }, userPresent = true, psuHeaders = headers) } as EbSyncOutcome.Done
        assertEquals(6, accountCalls().size)
        assertTrue(accountCalls().all { it.header("Psu-Ip-Address") == "192.0.2.7" })
        val first = outcome.accounts[0].result as EbReadResult.Ok
        assertTrue(first.pendingComplete)
        assertEquals(listOf(-400L), first.pending.map { it.signedCents })
        assertEquals(2, first.batch.movements.size)

        eb.requests.clear()
        runBlocking { connector.sync(coveredTo = { null }, userPresent = true, psuHeaders = mapOf("Psu-User-Agent" to "Pilastri/test")) }
        assertTrue(accountCalls().all { it.header("Psu-User-Agent") == null })
    }

    @Test fun laterSyncsAskOnlyForTheRecentRangeAndNeverPastNinetyDays() {
        link()
        now = now.plus(Duration.ofHours(3))
        runBlocking { connector.sync(coveredTo = { LocalDate.of(2026, 10, 30) }) }
        assertEquals("2026-10-23", accountCalls().first { isTx(it, "BOOK") }.url.queryParameter("date_from"))
        eb.requests.clear()
        runBlocking { connector.sync(coveredTo = { null }) }
        assertEquals(LocalDate.of(2026, 10, 31).minusDays(89).toString(), accountCalls().first { isTx(it, "BOOK") }.url.queryParameter("date_from"))
    }

    @Test fun aFailedOptionalPendingReadOrBalanceDoesNotFailTheAccount() {
        link()
        val base = dataRoute()
        eb.route = { r -> if (isTx(r, "PDNG")) FakeEb.Reply(400, """{"error":"WRONG_TRANSACTIONS_PERIOD"}""") else if (r.url.encodedPath.endsWith("/balances")) FakeEb.Reply(400, "{}") else base(r) }
        val outcome = runBlocking { connector.sync(coveredTo = { null }, userPresent = true) } as EbSyncOutcome.Done
        val ok = outcome.accounts[0].result as EbReadResult.Ok
        assertFalse(ok.pendingComplete)
        assertNull(ok.batch.balance)
        assertEquals(2, ok.batch.movements.size)
        assertEquals(LocalDate.of(2026, 10, 31), ok.batch.coverageTo)
    }

    @Test fun aFailedBookedReadFailsTheSyncWithoutAnyBatch() {
        link()
        val base = dataRoute()
        eb.route = { r -> if (isTx(r, "BOOK")) FakeEb.Reply(500, "x") else base(r) }
        assertTrue(runBlocking { connector.sync(coveredTo = { null }) } is EbSyncOutcome.Failed)
    }

    @Test fun anEmptyBookedAnswerWithoutTheListFailsTheAccountRead() {
        link()
        val base = dataRoute()
        eb.route = { r -> if (isTx(r, "BOOK")) FakeEb.Reply(body = "{}") else base(r) }
        assertTrue(runBlocking { connector.sync(coveredTo = { null }) } is EbSyncOutcome.Failed)
    }

    @Test fun rateLimitingStopsTheSyncAndBacksOffWithoutFurtherCalls() {
        link()
        val base = dataRoute()
        eb.route = { r -> if (isTx(r, "BOOK")) FakeEb.Reply(429, """{"error":"ASPSP_RATE_LIMIT_EXCEEDED"}""") else base(r) }
        val first = runBlocking { connector.sync(coveredTo = { null }) } as EbSyncOutcome.RateLimited
        assertEquals(now.plus(EbConnector.RATE_LIMIT_BACKOFF), first.until)
        val calls = eb.requests.size
        now = now.plus(Duration.ofHours(1))
        assertTrue(runBlocking { connector.sync(coveredTo = { null }) } is EbSyncOutcome.RateLimited)
        assertEquals(calls, eb.requests.size)
        eb.route = base
        now = now.plus(Duration.ofHours(6))
        assertTrue(runBlocking { connector.sync(coveredTo = { null }) } is EbSyncOutcome.Done)
    }

    @Test fun anExpiredConsentOrARevokedSessionNeedsANewLinkWithoutReadingAnything() {
        link()
        now = Instant.parse("2027-04-30T00:00:00Z")
        assertEquals(EbSyncOutcome.NeedsLink, runBlocking { connector.sync(coveredTo = { null }) })
        assertTrue(accountCalls().isEmpty())

        now = Instant.parse("2026-11-02T10:00:00Z")
        eb.route = { FakeEb.Reply(401, """{"error":"REVOKED_SESSION"}""") }
        assertEquals(EbSyncOutcome.NeedsLink, runBlocking { connector.sync(coveredTo = { null }) })
    }

    @Test fun aBankWithoutAnAppProviderIsReportedNotFiledUnderAWrongOne() {
        link(EbFx.SESSION.replace("\"name\":\"Isybank\"", "\"name\":\"Banca Sintetica Due\""))
        val outcome = runBlocking { connector.sync(coveredTo = { null }) } as EbSyncOutcome.Done
        assertTrue(outcome.accounts.all { it.result is EbReadResult.Failed })
        assertTrue(accountCalls().isEmpty())
    }

    @Test fun notLinkedAndUnlink() {
        assertEquals(EbSyncOutcome.NotLinked, runBlocking { connector.sync(coveredTo = { null }) })
        link()
        eb.route = { FakeEb.Reply(200, "") }
        runBlocking { connector.unlink() }
        assertEquals("DELETE /sessions/sess-synthetic-1", eb.paths().single())
        assertNull(connector.local().session)
        assertEquals(EbFx.APP_ID, connector.local().applicationId)
    }
}
