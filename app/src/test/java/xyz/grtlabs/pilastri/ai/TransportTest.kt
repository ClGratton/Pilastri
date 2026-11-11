package xyz.grtlabs.pilastri.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class TransportTest {
    private class FakeHttp(private vararg val replies: Any) : AiHttp {
        class Call(val url: String, val headers: Map<String, String>, val body: String)
        val calls = ArrayList<Call>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): AiHttpReply {
            calls += Call(url, headers, body)
            val next = replies[minOf(calls.size - 1, replies.size - 1)]
            if (next is Exception) throw next
            return next as AiHttpReply
        }
    }

    private val key = "sk-test-SECRETKEY123456"
    private val ok = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Ciao"}],"usage":{"input_tokens":1,"output_tokens":2}}"""
    private fun req(model: String) = AiRequest(model, "S", listOf(AiMessage.User("q")), AiTools.specs())
    private fun anthropicFailure(status: Int, body: String = ""): AiTransportException {
        val http = FakeHttp(AiHttpReply(status, body))
        return assertThrows(AiTransportException::class.java) { AiFixtures.blocking { AnthropicTransport(key, http).send(req("claude-sonnet-5-5")) } }
    }

    @Test fun anthropicSendsToMessagesEndpointWithKeyHeader() {
        val http = FakeHttp(AiHttpReply(200, ok))
        val response = AiFixtures.blocking { AnthropicTransport(key, http).send(req("claude-sonnet-5-5")) }
        assertEquals("Ciao", response.text)
        val call = http.calls.single()
        assertEquals("https://api.anthropic.com/v1/messages", call.url)
        assertEquals(key, call.headers["x-api-key"])
        assertEquals("2026-10-21", call.headers["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", call.headers["anthropic-beta"])
        assertEquals("default", JSONObject(call.body).getString("fallbacks"))
        assertFalse("The key must never be in the body", call.body.contains(key))
    }

    @Test fun openAiSendsToChatCompletionsWithBearerKey() {
        val http = FakeHttp(AiHttpReply(200, """{"choices":[{"finish_reason":"stop","message":{"content":"Ciao"}}]}"""))
        val response = AiFixtures.blocking { OpenAiTransport(key, http).send(req("gpt-5.6-terra")) }
        assertEquals("Ciao", response.text)
        val call = http.calls.single()
        assertEquals("https://api.openai.com/v1/chat/completions", call.url)
        assertEquals("Bearer $key", call.headers["Authorization"])
        assertFalse(call.body.contains(key))
    }

    @Test fun errorsAreItalianAndNeverEchoTheKey() {
        val unauthorized = anthropicFailure(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key $key"}}""")
        assertEquals(AiTransportException.Kind.AUTH, unauthorized.kind)
        assertTrue(unauthorized.message!!.contains("401") && unauthorized.message!!.contains("Chiave API"))
        assertFalse(unauthorized.message!!.contains("SECRETKEY"))

        val limited = anthropicFailure(429, """{"error":{"message":"rate limit"}}""")
        assertEquals(AiTransportException.Kind.RATE_LIMIT, limited.kind)
        assertTrue(limited.message!!.contains("429") && limited.message!!.contains("riprova"))

        val busy = anthropicFailure(529)
        assertEquals(AiTransportException.Kind.SERVER, busy.kind)
        assertTrue(busy.message!!.contains("sovraccarico"))
        assertEquals(AiTransportException.Kind.SERVER, anthropicFailure(500).kind)

        val missingModel = anthropicFailure(404, """{"error":{"message":"model: x"}}""")
        assertTrue(missingModel.message!!.contains("Modello"))

        val unknown = anthropicFailure(418)
        assertEquals(AiTransportException.Kind.UNKNOWN, unknown.kind)
        assertTrue(unknown.message!!.contains("418"))

        val badRequest = anthropicFailure(400, """{"error":{"message":"messages: roles must alternate $key"}}""")
        assertEquals(AiTransportException.Kind.BAD_REQUEST, badRequest.kind)
        assertFalse(badRequest.message!!.contains("SECRETKEY"))
    }

    @Test fun networkAndTimeoutFailuresAreDistinguished() {
        val network = assertThrows(AiTransportException::class.java) {
            AiFixtures.blocking { AnthropicTransport(key, FakeHttp(IOException("Unable to resolve host"))).send(req("claude-sonnet-5-5")) }
        }
        assertEquals(AiTransportException.Kind.NETWORK, network.kind)
        assertTrue(network.message!!.contains("rete"))
        val timeout = assertThrows(AiTransportException::class.java) {
            AiFixtures.blocking { OpenAiTransport(key, FakeHttp(SocketTimeoutException("timeout"))).send(req("gpt-5.6-terra")) }
        }
        assertEquals(AiTransportException.Kind.TIMEOUT, timeout.kind)
        assertTrue(timeout.message!!.contains("Tempo scaduto"))
    }

    @Test fun openAiQuotaAndResponsesApiErrorsGetSpecificGuidance() {
        fun fail(status: Int, body: String) = assertThrows(AiTransportException::class.java) {
            AiFixtures.blocking { OpenAiTransport(key, FakeHttp(AiHttpReply(status, body))).send(req("gpt-6-astra")) }
        }
        val quota = fail(429, """{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}""")
        assertTrue(quota.message!!.contains("Quota"))
        val responses = fail(400, """{"error":{"message":"Tool calling is not supported on this endpoint for this model; use the Responses API."}}""")
        assertTrue(responses.message!!.contains("Responses API"))
        assertTrue(responses.message!!.contains(AiProvider.OPENAI.defaultModel))
    }

    @Test fun refusedFallbackBetaIsRetriedOnceWithoutItAndRemembered() {
        val rejection = AiHttpReply(400, """{"error":{"type":"invalid_request_error","message":"Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header."}}""")
        val http = FakeHttp(rejection, AiHttpReply(200, ok), AiHttpReply(200, ok))
        val transport = AnthropicTransport(key, http)
        AiFixtures.blocking { transport.send(req("claude-sonnet-5-5")) }
        assertEquals(2, http.calls.size)
        assertTrue(http.calls[0].headers.containsKey("anthropic-beta"))
        assertFalse(http.calls[1].headers.containsKey("anthropic-beta"))
        assertFalse(JSONObject(http.calls[1].body).has("fallbacks"))
        AiFixtures.blocking { transport.send(req("claude-sonnet-5-5")) }
        assertEquals(3, http.calls.size)
        assertFalse(http.calls[2].headers.containsKey("anthropic-beta"))
    }

    @Test fun redirectsAreNeverFollowedAndReadAsAClearError() {
        // The real client: the key is in a header, so a redirect must not carry it anywhere.
        assertFalse(OkHttpAiHttp.shared.followRedirects)
        assertFalse(OkHttpAiHttp.shared.followSslRedirects)
        for (status in listOf(301, 302, 307, 308)) {
            val e = anthropicFailure(status, """{"error":{"message":"moved $key"}}""")
            assertTrue(e.message!!, e.message!!.contains("reindirizzamento") && e.message!!.contains("$status"))
            assertFalse(e.message!!.contains("SECRETKEY"))
        }
        val http = FakeHttp(AiHttpReply(307, ""))
        val openAi = assertThrows(AiTransportException::class.java) { AiFixtures.blocking { OpenAiTransport(key, http).send(req("gpt-5.6-terra")) } }
        assertTrue(openAi.message!!.contains("reindirizzamento"))
        assertEquals(1, http.calls.size)
    }

    @Test fun anOrdinaryBadRequestIsNotRetried() {
        val http = FakeHttp(AiHttpReply(400, """{"error":{"message":"messages: roles must alternate"}}"""))
        assertThrows(AiTransportException::class.java) { AiFixtures.blocking { AnthropicTransport(key, http).send(req("claude-sonnet-5-5")) } }
        assertEquals(1, http.calls.size)
    }

    @Test fun fallbackCanBeSwitchedOffAndSkipsUnsupportedModels() {
        val http = FakeHttp(AiHttpReply(200, ok))
        AiFixtures.blocking { AnthropicTransport(key, http, serverFallback = false).send(req("claude-sonnet-5-5")) }
        assertNull(http.calls.single().headers["anthropic-beta"])
        val other = FakeHttp(AiHttpReply(200, ok))
        AiFixtures.blocking { AnthropicTransport(key, other).send(req("claude-haiku-5-5")) }
        assertNull(other.calls.single().headers["anthropic-beta"])
    }
}
