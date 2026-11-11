package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.ai.AiFixtures.Host

/** The ChatGPT-plan Responses contract: request shape, stream parsing, errors, and the full agent loop. No network. */
class ResponsesWireTest {
    private fun sse(vararg events: JSONObject) = events.joinToString("") { "event: ${it.optString("type")}\ndata: $it\n\n" }
    private fun completed(vararg items: JSONObject, usage: JSONObject = JSONObject().put("input_tokens", 10).put("output_tokens", 5)) =
        JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed").put("output", JSONArray(items.toList())).put("usage", usage))
    private fun message(text: String) = JSONObject().put("id", "msg_1").put("type", "message").put("role", "assistant").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))
    private fun call(callId: String, name: String, args: String) = JSONObject().put("id", "fc_$callId").put("type", "function_call")
        .put("call_id", callId).put("name", name).put("namespace", ResponsesWire.NAMESPACE).put("arguments", args).put("status", "completed")
    private fun reasoning() = JSONObject().put("id", "rs_1").put("type", "reasoning").put("summary", JSONArray()).put("encrypted_content", "ENC")

    @Test fun theRequestFollowsThePlanRouteLimits() {
        val body = ResponsesWire.requestBody(AiRequest("gpt-6.1-sol", "SISTEMA", listOf(AiMessage.User("ciao")), AiTools.specs()))
        assertEquals(false, body.getBoolean("store"))
        assertEquals(true, body.getBoolean("stream"))
        assertEquals("SISTEMA", body.getString("instructions"))
        for (forbidden in listOf("max_output_tokens", "temperature", "top_p", "previous_response_id", "metadata", "user", "conversation", "background", "truncation")) {
            assertFalse(forbidden, body.has(forbidden))
        }
        val input = body.getJSONArray("input")
        for (i in 0 until input.length()) assertFalse("no system-role items", input.getJSONObject(i).optString("role") == "system")
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        val ns = tools.getJSONObject(0)
        assertEquals("namespace", ns.getString("type"))
        assertEquals(ResponsesWire.NAMESPACE, ns.getString("name"))
        val fns = ns.getJSONArray("tools")
        assertEquals(AiTools.all.size, fns.length())
        for (i in 0 until fns.length()) {
            val f = fns.getJSONObject(i)
            assertEquals("function", f.getString("type"))
            assertEquals("optional arguments: not strict", false, f.getBoolean("strict"))
        }
        assertEquals(mapOf("Authorization" to "Bearer at", "Content-Type" to "application/json", "Accept" to "text/event-stream"), ResponsesWire.headers("at"))
    }

    @Test fun historyAndToolResultsBecomeInputItems() {
        val raw = JSONArray().put(JSONObject(reasoning().toString()).apply { remove("id") }).put(JSONObject(call("c1", "elenca_conti", "{}").toString()).apply { remove("id") })
        val input = ResponsesWire.input(listOf(
            AiMessage.User("domanda"),
            AiMessage.Assistant("risposta precedente"),
            AiMessage.User("altra"),
            AiMessage.Assistant("", listOf(AiToolCall("c1", "elenca_conti", JSONObject())), raw),
            AiMessage.ToolResult("c1", "elenca_conti", "{\"totale\":0}"),
            AiMessage.ToolResult("c2", "x", "rotto", isError = true),
        ))
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("output_text", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("reasoning", input.getJSONObject(3).getString("type"))
        assertEquals("ENC", input.getJSONObject(3).getString("encrypted_content"))
        assertEquals("function_call", input.getJSONObject(4).getString("type"))
        val output = input.getJSONObject(5)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("c1", output.getString("call_id"))
        assertEquals("{\"totale\":0}", output.getString("output"))
        assertTrue(input.getJSONObject(6).getString("output").startsWith("ERRORE: "))
    }

    @Test fun aCompletedStreamGivesTextToolCallsUsageAndReplayableItemsWithoutIds() {
        val text = ResponsesWire.parseStream(sse(
            JSONObject().put("type", "response.created").put("response", JSONObject()),
            JSONObject().put("type", "response.output_text.delta").put("delta", "Ci"),
            completed(message("Ciao")),
        ))
        assertEquals("Ciao", text.text)
        assertEquals(AiStop.END, text.stop)
        assertEquals(AiUsage(10, 5), text.usage)

        val tools = ResponsesWire.parseStream(": keep-alive\n\n" + sse(completed(reasoning(), call("call_1", "aggiungi_voce_piano", """{"mese":"2026-12"}"""))))
        assertEquals(AiStop.TOOL_USE, tools.stop)
        val c = tools.toolCalls.single()
        assertEquals("call_1", c.id); assertEquals("aggiungi_voce_piano", c.name); assertEquals("2026-12", c.arguments.getString("mese"))
        val replay = tools.raw as JSONArray
        assertEquals(2, replay.length())
        for (i in 0 until replay.length()) assertFalse("ids are not replayed with store:false", replay.getJSONObject(i).has("id"))
        assertEquals("ENC", replay.getJSONObject(0).getString("encrypted_content"))

        // Output items seen only as output_item.done (an empty completed output) still count.
        val fromDone = ResponsesWire.parseStream(sse(JSONObject().put("type", "response.output_item.done").put("item", message("Solo done")), completed()))
        assertEquals("Solo done", fromDone.text)
        val broken = ResponsesWire.parseStream(sse(completed(call("c2", "elenca_conti", "{non json"))))
        assertTrue(broken.toolCalls.single().argumentsError != null)
    }

    @Test fun truncationRefusalFailureAndAnInterruptedStreamAreDistinguished() {
        val cut = ResponsesWire.parseStream(sse(JSONObject().put("type", "response.incomplete").put("response",
            JSONObject().put("output", JSONArray().put(message("Parz"))).put("incomplete_details", JSONObject().put("reason", "max_output_tokens")))))
        assertEquals(AiStop.TRUNCATED, cut.stop)
        val refusal = JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "No")))
        assertEquals(AiStop.REFUSED, ResponsesWire.parseStream(sse(completed(refusal))).stop)

        val limit = assertThrows(AiTransportException::class.java) {
            ResponsesWire.parseStream(sse(JSONObject().put("type", "response.failed").put("response", JSONObject().put("error",
                JSONObject().put("code", ChatGptErrors.USAGE_LIMIT).put("message", "limit")))))
        }
        assertEquals(AiTransportException.Kind.RATE_LIMIT, limit.kind)
        assertTrue(limit.message!!.contains("Utilizzo"))
        val interrupted = assertThrows(AiTransportException::class.java) {
            ResponsesWire.parseStream(sse(JSONObject().put("type", "response.output_text.delta").put("delta", "a")))
        }
        assertEquals(AiTransportException.Kind.BAD_RESPONSE, interrupted.kind)
        val errorEvent = assertThrows(AiTransportException::class.java) {
            ResponsesWire.parseStream(sse(JSONObject().put("type", "error").put("code", "subscription_sharing_unsupported_capability").put("message", "x")))
        }
        assertEquals(AiTransportException.Kind.BAD_REQUEST, errorEvent.kind)
    }

    @Test fun admissionErrorsBeforeTheStreamAreMappedWithoutEchoingTokens() {
        assertEquals(AiTransportException.Kind.AUTH, ChatGptErrors.fromHttp(403, """{"detail":"region not permitted"}""").kind)
        assertEquals(AiTransportException.Kind.SERVER, ChatGptErrors.fromHttp(503, """{"detail":"direct routing disabled"}""").kind)
        val notEligible = ChatGptErrors.fromHttp(403, """{"error":{"code":"subscription_sharing_user_not_eligible","message":"no"}}""")
        assertEquals(AiTransportException.Kind.AUTH, notEligible.kind)
        assertTrue(notEligible.message!!.contains("piano ChatGPT"))
        assertTrue(ChatGptErrors.fromHttp(307, "").message!!.contains("reindirizzamento"))
        assertFalse(ChatGptErrors.fromCode("x", "token sk-SECRETTOKEN123456 4111 1111 1111 1111", 400).message!!.contains("4111"))
        assertEquals(listOf("gpt-6.1-sol" to "GPT-6.1 Sol"), ResponsesWire.parseModels(
            """{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}"""))
    }

    // ---- the transport inside the agent loop ----

    private class FakeHttp(vararg replies: AiHttpReply) : AiHttp {
        private val queue = ArrayDeque(replies.toList())
        val calls = ArrayList<Triple<String, Map<String, String>, String>>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String): AiHttpReply {
            calls += Triple(url, headers, body)
            return queue.removeFirst()
        }
    }

    private class FakeAuth(private val refreshes: MutableList<String>) : ChatGptAuthHttp {
        override suspend fun postForm(url: String, fields: Map<String, String>): AiHttpReply {
            refreshes += fields.getValue("refresh_token")
            return AiHttpReply(200, """{"access_token":"at-2","refresh_token":"rt-2","expires_in":3600,"scope":"openid chatgpt.tokens.use.direct"}""")
        }
        override suspend fun get(url: String, bearer: String?) = AiHttpReply(404, "")
    }

    private fun session(refreshes: MutableList<String> = ArrayList()): ChatGptSession {
        val store = ChatGptStore(AiVault(MemoryStore(), object : SecretCipher {
            override fun encrypt(plain: ByteArray, aad: ByteArray) = plain.reversedArray()
            override fun decrypt(blob: ByteArray, aad: ByteArray) = blob.reversedArray()
        }), MemoryStore())
        store.save(ChatGptCredentials("oaiapp_x", "sub", null, null, "at-1", "rt-1", Long.MAX_VALUE / 2, setOf(ChatGptOAuth.PLAN_SCOPE)))
        return ChatGptSession(store, FakeAuth(refreshes)) { 0 }
    }

    @Test fun aRejectedTokenIsRefreshedOnceBeforeTheStreamOpens() = runBlocking {
        val refreshes = ArrayList<String>()
        val http = FakeHttp(AiHttpReply(401, """{"detail":"expired"}"""), AiHttpReply(200, sse(completed(message("ok")))))
        val response = ChatGptPlanTransport(session(refreshes), http).send(AiRequest("gpt-6.1-sol", "S", listOf(AiMessage.User("q")), emptyList()))
        assertEquals("ok", response.text)
        assertEquals(listOf("rt-1"), refreshes)
        assertEquals("Bearer at-1", http.calls[0].second["Authorization"])
        assertEquals("Bearer at-2", http.calls[1].second["Authorization"])
        assertEquals(ResponsesWire.ENDPOINT, http.calls[0].first)
        assertFalse("the token is never in the body", http.calls[0].third.contains("at-1"))
    }

    @Test fun theChatGptPlanGoesThroughTheSameToolsTiersAndUndo() {
        val host = Host(AiFixtures.archive())
        val plan = """{"mese":"2026-05","etichetta":"Cena","tipo":"EXPENSE","stato":"SCENARIO","importo_eur":"10.00"}"""
        val http = FakeHttp(
            AiHttpReply(200, sse(completed(reasoning(), call("c1", "aggiungi_voce_piano", plan)))),
            AiHttpReply(200, sse(completed(message("Fatto.")))),
            AiHttpReply(200, sse(completed(call("c2", "registra_valutazione_pilastro", """{"pilastro":2,"data":"2026-04-30","importo_eur":"100.00"}""")))),
            AiHttpReply(200, sse(completed(message("In attesa.")))),
        )
        val transport = ChatGptPlanTransport(session(), http)
        val agent = AiAgent(transport, "gpt-6.1-sol", AiFixtures.clock, retryDelaysMs = listOf(0L))
        val first = AiFixtures.blocking { agent.run("Aggiungi una cena", emptyList(), host.archive, host.commit) }
        assertEquals("Fatto.", first.text)
        val applied = first.applied.single()
        assertTrue(host.archive.plan.single { it.id == "ai:${applied.id}" }.source.startsWith("Assistente · ChatGPT (account) · "))
        // Round two replays the reasoning and the call, without ids, followed by the tool output.
        val second = JSONObject(http.calls[1].third).getJSONArray("input")
        val types = (0 until second.length()).map { second.getJSONObject(it).optString("type", "message") }
        assertEquals(listOf("message", "reasoning", "function_call", "function_call_output"), types)
        assertFalse(second.getJSONObject(2).has("id"))
        // A Tier-2 kind waits for the tap on this route too.
        val tier2 = AiFixtures.blocking { agent.run("Valuta", emptyList(), host.archive, host.commit) }
        assertEquals(AiChangeKind.ADD_VALUATION, tier2.pending.single().kind)
        assertTrue(tier2.applied.isEmpty())
        // Undo is the same inverse operation.
        assertTrue(AiFixtures.blocking { AiCommits.run(host.commit) { applied.undoFrom(it) } } is AiCommitResult.Done)
    }

    @Test fun aUsageLimitStopsTheRunWithItsKindForTheManageUsageAction() {
        val http = FakeHttp(AiHttpReply(200, sse(JSONObject().put("type", "response.failed").put("response",
            JSONObject().put("error", JSONObject().put("code", ChatGptErrors.USAGE_LIMIT))))))
        val result = AiFixtures.blocking {
            AiAgent(ChatGptPlanTransport(session(), http), "gpt-6.1-sol", AiFixtures.clock).run("q", emptyList(), AiFixtures.archive(), Host(AiFixtures.archive()).commit)
        }
        assertEquals(AiTransportException.Kind.RATE_LIMIT, result.errorKind)
        assertTrue(result.error!!.contains("piano ChatGPT"))
    }
}
