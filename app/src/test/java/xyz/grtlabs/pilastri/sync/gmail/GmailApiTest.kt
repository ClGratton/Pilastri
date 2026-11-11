package xyz.grtlabs.pilastri.sync.gmail

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures as F

class GmailApiTest {
    private val token = "ya29.SYNTHETIC-TEST-TOKEN"

    /** Scripted HTTP: replies come from [handler]; every call is recorded. */
    private class FakeHttp(val handler: (String) -> GmailReply) : GmailHttp {
        val calls = ArrayList<Pair<String, String>>()
        override suspend fun get(url: String, bearer: String): GmailReply { calls += url to bearer; return handler(url) }
    }

    private fun source(http: GmailHttp) = GmailRestSource(http, token)

    private fun messageJson(id: String, text: String) = F.gmailJson(id, "PayPal <service@paypal.it>", "Promemoria", Instant.parse("2026-12-03T10:00:00Z").toEpochMilli(),
        F.part("text/plain", F.b64(text))).toString()

    private fun failure(block: suspend () -> Unit): GmailApiException {
        try { runBlocking { block() } } catch (e: GmailApiException) { return e }
        fail("expected a GmailApiException"); throw IllegalStateException()
    }

    @Test fun listUsesTheDocumentedEndpointQueryAndPageToken() {
        val http = FakeHttp { GmailReply(200, """{"messages":[{"id":"18c3a","threadId":"t"},{"id":"bad id!","threadId":"t"},{"id":"18c3b"}],"nextPageToken":"NEXT"}""") }
        val page = runBlocking { source(http).list("after:2026/10/01 from:(klarna.com) \"paga in 3\"", "TOKEN1", 100) }
        assertEquals(listOf("18c3a", "18c3b"), page.ids)
        assertEquals("NEXT", page.next)
        val url = http.calls.single().first.toHttpUrl()
        assertEquals("gmail.googleapis.com", url.host)
        assertEquals("/gmail/v1/users/me/messages", url.encodedPath)
        assertEquals("after:2026/10/01 from:(klarna.com) \"paga in 3\"", url.queryParameter("q"))
        assertEquals("100", url.queryParameter("maxResults"))
        assertEquals("TOKEN1", url.queryParameter("pageToken"))
        assertEquals("nextPageToken,messages/id", url.queryParameter("fields"))
    }

    @Test fun anEmptyMailboxAnswerIsAnEmptyPage() {
        val page = runBlocking { source(FakeHttp { GmailReply(200, """{"resultSizeEstimate":0}""") }).list("q", null, 50) }
        assertTrue(page.ids.isEmpty()); assertNull(page.next)
    }

    @Test fun pageSizeIsClampedToGmailsMaximum() {
        val http = FakeHttp { GmailReply(200, "{}") }
        runBlocking { source(http).list("q", null, 9999) }
        assertEquals("500", http.calls.single().first.toHttpUrl().queryParameter("maxResults"))
    }

    @Test fun getAsksForTheFullFormatWithAPartialFieldMaskAndParsesTheMessage() {
        val http = FakeHttp { GmailReply(200, messageJson("18c3a", "Promemoria: la rata 2 di 3 da € 33,33 verrà addebitata il 5 dic 2026.")) }
        val msg = runBlocking { source(http).fetch("18c3a") }!!
        assertEquals("18c3a", msg.id)
        assertTrue(msg.text.contains("rata 2 di 3"))
        val url = http.calls.single().first.toHttpUrl()
        assertEquals("/gmail/v1/users/me/messages/18c3a", url.encodedPath)
        assertEquals("full", url.queryParameter("format"))
        assertTrue(url.queryParameter("fields")!!.startsWith("id,internalDate,payload(mimeType,filename,headers,body/data,parts("))
    }

    @Test fun theTokenTravelsOnlyInTheAuthorizationHeader() {
        val http = FakeHttp { u -> if (u.contains("/messages/")) GmailReply(200, messageJson("m1", "x")) else GmailReply(200, """{"messages":[{"id":"m1"}]}""") }
        val s = source(http)
        runBlocking { s.list("q", null, 10); s.fetch("m1") }
        assertEquals(2, http.calls.size)
        assertTrue(http.calls.all { it.second == token })
        assertTrue("never in a URL, which could end up in a log", http.calls.none { it.first.contains(token) || it.first.contains("access_token") })
    }

    @Test fun aRejectedFieldMaskFallsBackToAPlainGetAndRemembersIt() {
        val http = FakeHttp { u -> if (u.contains("fields=")) GmailReply(400, """{"error":{"code":400,"message":"Invalid field selection"}}""") else GmailReply(200, messageJson("m1", "ok")) }
        val s = source(http)
        assertNotNull(runBlocking { s.fetch("m1") })
        assertEquals(2, http.calls.size)
        assertFalse(http.calls[1].first.contains("fields="))
        runBlocking { s.fetch("m1") }
        assertEquals("after the fallback the mask is no longer sent", 3, http.calls.size)
    }

    @Test fun aMessageDeletedBetweenListAndGetIsNullAndBadIdsAreNotRequested() {
        val http = FakeHttp { GmailReply(404, """{"error":{"code":404}}""") }
        assertNull(runBlocking { source(http).fetch("gone") })
        assertNull(runBlocking { source(http).fetch("../../etc") })
        assertEquals(1, http.calls.size)
    }

    @Test fun errorsMapToTheKindTheConnectorActsOn() {
        fun kind(status: Int, body: String) = failure { source(FakeHttp { GmailReply(status, body) }).list("q", null, 10) }.kind
        assertEquals(GmailApiException.Kind.AUTH, kind(401, """{"error":{"code":401,"status":"UNAUTHENTICATED"}}"""))
        assertEquals(GmailApiException.Kind.NOT_CONFIGURED, kind(403, """{"error":{"code":403,"errors":[{"reason":"accessNotConfigured"}],"status":"PERMISSION_DENIED"}}"""))
        assertEquals(GmailApiException.Kind.NOT_CONFIGURED, kind(403, """{"error":{"code":403,"status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"""))
        assertEquals(GmailApiException.Kind.AUTH, kind(403, """{"error":{"code":403,"errors":[{"reason":"insufficientPermissions"}]}}"""))
        assertEquals(GmailApiException.Kind.RATE_LIMIT, kind(403, """{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}]}}"""))
        assertEquals(GmailApiException.Kind.RATE_LIMIT, kind(429, "{}"))
        assertEquals(GmailApiException.Kind.SERVER, kind(503, "{}"))
        assertEquals(GmailApiException.Kind.BAD_RESPONSE, kind(400, "{}"))
        assertEquals(GmailApiException.Kind.BAD_RESPONSE, kind(200, "<html>not json</html>"))
    }

    @Test fun errorMessagesAreItalianAndNeverEchoTheBodyOrTheToken() {
        val body = """{"error":{"code":401,"message":"Invalid Credentials $token"}}"""
        val e = failure { source(FakeHttp { GmailReply(401, body) }).list("q", null, 10) }
        assertFalse(e.message!!.contains(token)); assertFalse(e.message!!.contains("Invalid Credentials"))
        assertTrue(e.message!!.contains("Gmail"))
        val disabled = failure { source(FakeHttp { GmailReply(403, """{"error":{"errors":[{"reason":"accessNotConfigured"}]}}""") }).list("q", null, 10) }
        assertTrue(disabled.message!!, disabled.message!!.contains("Gmail API") && disabled.message!!.contains("Abilita"))
    }

    @Test fun aNetworkFailureIsMappedWithoutTheCause() {
        val e = failure { source(GmailHttp { _, _ -> throw IOException("unexpected end of stream on https://gmail.googleapis.com?access_token=$token") }).list("q", null, 10) }
        assertEquals(GmailApiException.Kind.NETWORK, e.kind)
        assertFalse(e.message!!.contains(token))
    }

    @Test fun getFieldMaskIsWellFormed() {
        val mask = GmailRestSource.MESSAGE_FIELDS
        assertEquals("parentheses balance", mask.count { it == '(' }, mask.count { it == ')' })
        assertFalse(mask.contains(" "))
        assertTrue(JSONObject().put("x", mask).toString().isNotEmpty())
    }

    @Test fun theQueriesStartWithADayOfSlack() {
        val q = GmailQueries.build(java.time.LocalDate.of(2026, 3, 1))
        assertTrue(q.all { it.startsWith("after:2026/02/28 ") })
        assertEquals(3, q.size)
    }
}
