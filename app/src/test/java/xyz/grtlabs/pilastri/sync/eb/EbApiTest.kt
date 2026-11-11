package xyz.grtlabs.pilastri.sync.eb

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.eb.EbFx.page
import xyz.grtlabs.pilastri.sync.eb.EbFx.tx

class EbApiTest {
    private val eb = FakeEb()
    private val api = eb.api()
    private val from = LocalDate.of(2026, 10, 1)
    private val to = LocalDate.of(2026, 10, 31)

    @Test fun everyCallCarriesTheBearerToken() {
        eb.route = { FakeEb.Reply(body = EbFx.ASPSPS) }
        val banks = api.aspsps(country = "IT")
        assertEquals("Bearer synthetic-token", eb.requests.single().header("Authorization"))
        assertEquals("/aspsps", eb.requests.single().url.encodedPath)
        assertEquals("IT", eb.requests.single().url.queryParameter("country"))
        assertEquals("AIS", eb.requests.single().url.queryParameter("service"))
        val isy = banks.first()
        assertEquals("Isybank", isy.name)
        assertEquals(15_552_000L, isy.maximumConsentSeconds)
        assertEquals(listOf("redirect"), isy.authMethods)
        assertEquals(listOf("Psu-Ip-Address"), isy.requiredPsuHeaders)
        assertNull(banks[1].maximumConsentSeconds)
    }

    @Test fun startAuthSendsTheDocumentedBody() {
        eb.route = { FakeEb.Reply(body = EbFx.AUTH) }
        val start = api.startAuth(EbAuthRequest("Isybank", "IT", "state-1", EbFx.REDIRECT, Instant.parse("2027-04-29T10:00:00Z")))
        assertEquals("https://auth.example.test/start?id=synthetic", start.url)
        assertEquals("auth-synthetic-1", start.authorizationId)
        val body = JSONObject(eb.bodies.single())
        assertEquals("2027-04-29T10:00:00Z", body.getJSONObject("access").getString("valid_until"))
        assertEquals("Isybank", body.getJSONObject("aspsp").getString("name"))
        assertEquals("IT", body.getJSONObject("aspsp").getString("country"))
        assertEquals("state-1", body.getString("state"))
        assertEquals(EbFx.REDIRECT, body.getString("redirect_url"))
        assertEquals("personal", body.getString("psu_type"))
        assertEquals("POST /auth", eb.paths().single())
    }

    @Test fun sessionCreationAndLookupReadAccountsInBothShapes() {
        eb.route = { FakeEb.Reply(body = EbFx.SESSION) }
        val s = api.createSession("code-1")
        assertEquals("sess-synthetic-1", s.sessionId)
        assertEquals(Instant.parse("2027-04-29T10:00:00Z"), s.validUntil)
        assertEquals(listOf("uid-a" to "hash-a", "uid-b" to "hash-b"), s.accounts.map { it.uid to it.identificationHash })
        assertEquals("Deposito sintetico", s.accounts[1].label)
        assertEquals("""{"code":"code-1"}""", eb.bodies.single())
        assertEquals("EbSession(***)", s.toString())

        eb.route = { FakeEb.Reply(body = """{"status":"AUTHORIZED","accounts":["uid-a","uid-b"],"accounts_data":[{"uid":"uid-a","identification_hash":"hash-a"},{"uid":"uid-b","identification_hash":"hash-b"}],"access":{"valid_until":"2027-04-29T10:00:00+00:00"}}""") }
        val info = api.session("sess-synthetic-1")
        assertEquals("AUTHORIZED", info.status)
        assertEquals(listOf("hash-a", "hash-b"), info.accounts.map { it.identificationHash })
        assertEquals("/sessions/sess-synthetic-1", eb.requests.last().url.encodedPath)
    }

    @Test fun deleteSessionAcceptsAnEmptyBody() {
        eb.route = { FakeEb.Reply(200, "") }
        api.deleteSession("sess-synthetic-1")
        assertEquals("DELETE /sessions/sess-synthetic-1", eb.paths().single())
    }

    @Test fun transactionsAreReadPageByPageUntilTheContinuationKeyIsGone() {
        eb.route = { r ->
            when (r.url.queryParameter("continuation_key")) {
                null -> FakeEb.Reply(body = page(listOf(tx("1.00", "DBIT"), tx("2.00", "DBIT")), "k1"))
                "k1" -> FakeEb.Reply(body = page(listOf(tx("3.00", "CRDT")), "k2/+="))
                "k2/+=" -> FakeEb.Reply(body = page(listOf(tx("4.00", "DBIT"), "7")))
                else -> FakeEb.Reply(500)
            }
        }
        val result = api.allTransactions("uid-a", from, to, "BOOK")
        assertEquals(4, result.transactions.size)
        assertEquals(3, result.pages)
        assertEquals(1, result.unreadable)
        assertTrue(result.complete)
        val first = eb.requests.first().url
        assertEquals("2026-10-01", first.queryParameter("date_from"))
        assertEquals("2026-10-31", first.queryParameter("date_to"))
        assertEquals("BOOK", first.queryParameter("transaction_status"))
        assertEquals("/accounts/uid-a/transactions", first.encodedPath)
        assertEquals("k2/+=", eb.requests.last().url.queryParameter("continuation_key"))
    }

    @Test fun aFailedPageNeverReturnsAPartialList() {
        eb.route = { r -> if (r.url.queryParameter("continuation_key") == null) FakeEb.Reply(body = page(listOf(tx("1.00", "DBIT")), "k1")) else FakeEb.Reply(503, """{"error":"SERVER_ERROR"}""") }
        assertThrows(EbException.Server::class.java) { api.allTransactions("uid-a", from, to, "BOOK") }
    }

    @Test fun aRepeatedOrEndlessContinuationKeyIsIncomplete() {
        eb.route = { FakeEb.Reply(body = page(listOf(tx("1.00", "DBIT")), "same")) }
        assertThrows(EbException.Incomplete::class.java) { api.allTransactions("uid-a", from, to, "BOOK") }
        var n = 0
        eb.route = { FakeEb.Reply(body = page(emptyList(), "k${n++}")) }
        assertThrows(EbException.Incomplete::class.java) { api.allTransactions("uid-a", from, to, "BOOK", maxPages = 5) }
    }

    @Test fun onlyAnExplicitEmptyListMeansNoTransactions() {
        eb.route = { FakeEb.Reply(body = """{"transactions":[]}""") }
        val empty = api.allTransactions("uid-a", from, to, "BOOK")
        assertEquals(0, empty.transactions.size)
        assertEquals(1, empty.pages)
        eb.route = { FakeEb.Reply(body = "{}") }
        assertThrows(EbException.Malformed::class.java) { api.allTransactions("uid-a", from, to, "BOOK") }
        eb.route = { FakeEb.Reply(body = "<html>") }
        assertThrows(EbException.Malformed::class.java) { api.allTransactions("uid-a", from, to, "BOOK") }
    }

    @Test fun balancesAreParsedWithTheirDates() {
        eb.route = { FakeEb.Reply(body = EbFx.balances(EbFx.balance("CLBD", "1234.50"), EbFx.balance("ITAV", "10.00", date = null))) }
        val b = api.balances("uid-a")
        assertEquals(listOf("CLBD", "ITAV"), b.map { it.type })
        assertEquals(LocalDate.of(2026, 10, 30), b[0].referenceDate)
        assertNull(b[1].referenceDate)
        assertEquals("/accounts/uid-a/balances", eb.requests.single().url.encodedPath)
    }

    @Test fun errorsAreClassifiedAndNeverCarryTheBody() {
        fun fail(code: Int, body: String, headers: Map<String, String> = emptyMap()): EbException {
            eb.route = { FakeEb.Reply(code, body, headers) }
            return assertThrows(EbException::class.java) { api.balances("uid-a") }
        }
        val limited = fail(429, """{"message":"IBAN IT00SECRET","error":"ASPSP_RATE_LIMIT_EXCEEDED"}""", mapOf("Retry-After" to "120")) as EbException.RateLimited
        assertEquals(120L, limited.retryAfterSeconds)
        assertFalse(limited.message!!.contains("SECRET"))
        assertTrue(fail(400, """{"error":"ASPSP_RATE_LIMIT_EXCEEDED"}""") is EbException.RateLimited)
        assertTrue(fail(401, """{"error":"CLOSED_SESSION","code":401}""") is EbException.SessionGone)
        assertTrue(fail(404, """{"error":"SESSION_DOES_NOT_EXIST"}""") is EbException.SessionGone)
        assertTrue(fail(401, """{"message":"Authorization header is not provided","code":401}""") is EbException.Unauthorized)
        assertTrue(fail(422, """{"error":"PSU_HEADER_NOT_PROVIDED"}""") is EbException.PsuHeaders)
        assertTrue(fail(500, "x") is EbException.Server)
        assertTrue(fail(400, """{"error":"WRONG_TRANSACTIONS_PERIOD"}""") is EbException.Rejected)
    }

    @Test fun aNetworkFailureIsReportedAsSuch() {
        eb.failWith = IOException("boom")
        assertThrows(EbException.Network::class.java) { api.aspsps() }
    }

    @Test fun psuHeadersAreSentOnlyWhenGivenAndOnlyWhenAllRequiredAreKnown() {
        eb.route = { FakeEb.Reply(body = EbFx.balances()) }
        api.balances("uid-a")
        assertNull(eb.requests.last().header("Psu-User-Agent"))
        api.balances("uid-a", mapOf("Psu-User-Agent" to "Pilastri/test"))
        assertEquals("Pilastri/test", eb.requests.last().header("Psu-User-Agent"))
        val ua = mapOf("Psu-User-Agent" to "Pilastri/test")
        val both = ua + ("Psu-Ip-Address" to "192.0.2.1")
        assertEquals(emptyMap<String, String>(), EbPsu.select(false, listOf("Psu-Ip-Address"), both))
        assertEquals(emptyMap<String, String>(), EbPsu.select(true, listOf("Psu-Ip-Address"), ua))
        assertEquals(both, EbPsu.select(true, listOf("psu-ip-address"), both))
        assertEquals(ua, EbPsu.select(true, emptyList(), ua))
    }
}
