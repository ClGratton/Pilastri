package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private val tool = AiToolSpec("riepilogo_pilastri", "Riepilogo.", JSONObject("""{"type":"object","properties":{}}"""))
    private fun request(model: String, vararg messages: AiMessage) = AiRequest(model, "Sistema.", messages.toList(), listOf(tool))

    // ---- Anthropic ----

    @Test fun anthropicRequestUsesMessagesApiShapeAndNeverSendsSampling() {
        val body = AnthropicWire.requestBody(request("claude-sonnet-5-5", AiMessage.User("Ciao")), "medium", fallback = false)
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals(16_000, body.getInt("max_tokens"))
        assertEquals("Sistema.", body.getString("system"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        val tools = body.getJSONArray("tools")
        assertEquals("riepilogo_pilastri", tools.getJSONObject(0).getString("name"))
        assertEquals("object", tools.getJSONObject(0).getJSONObject("input_schema").getString("type"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("Ciao", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        listOf("temperature", "top_p", "top_k", "thinking", "tool_choice", "fallbacks").forEach { assertFalse(it, body.has(it)) }
    }

    @Test fun anthropicHeadersCarryKeyVersionAndOptionalBeta() {
        val plain = AnthropicWire.headers("sk-ant-test-1234", fallback = false)
        assertEquals("sk-ant-test-1234", plain["x-api-key"])
        assertEquals("2026-10-21", plain["anthropic-version"])
        assertFalse(plain.containsKey("anthropic-beta"))
        assertEquals("server-side-fallback-2026-07-01", AnthropicWire.headers("k", fallback = true)["anthropic-beta"])
    }

    @Test fun serverFallbackIsSentOnlyForModelsThatSupportIt() {
        assertEquals("default", AnthropicWire.requestBody(request("claude-sonnet-5-5", AiMessage.User("x")), "medium", fallback = true).getString("fallbacks"))
        assertFalse(AnthropicWire.requestBody(request("claude-haiku-5-5", AiMessage.User("x")), "medium", fallback = true).has("fallbacks"))
        assertFalse(AnthropicWire.requestBody(request("claude-haiku-4-5", AiMessage.User("x")), "medium", fallback = true).has("output_config"))
        assertTrue(AnthropicWire.isFallbackRejection("""{"error":{"message":"Unexpected value(s) `x` for the `anthropic-beta` header."}}"""))
        assertFalse(AnthropicWire.isFallbackRejection("""{"error":{"message":"messages: roles must alternate"}}"""))
    }

    @Test fun anthropicGroupsToolResultsOfOneTurnInOneUserMessage() {
        val calls = listOf(
            AiToolCall("toolu_1", "riepilogo_pilastri", JSONObject()),
            AiToolCall("toolu_2", "elenca_conti", JSONObject("""{"pilastro":4}""")),
        )
        val messages = AnthropicWire.messages(listOf(
            AiMessage.User("Domanda"),
            AiMessage.Assistant("Controllo.", calls),
            AiMessage.ToolResult("toolu_1", "riepilogo_pilastri", "{\"ok\":1}"),
            AiMessage.ToolResult("toolu_2", "elenca_conti", "Errore", isError = true),
        ))
        assertEquals(3, messages.length())
        val assistant = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", assistant.getJSONObject(0).getString("type"))
        assertEquals("tool_use", assistant.getJSONObject(1).getString("type"))
        assertEquals(4, assistant.getJSONObject(2).getJSONObject("input").getInt("pilastro"))
        val results = messages.getJSONObject(2)
        assertEquals("user", results.getString("role"))
        val blocks = results.getJSONArray("content")
        assertEquals(2, blocks.length())
        assertEquals("toolu_1", blocks.getJSONObject(0).getString("tool_use_id"))
        assertFalse(blocks.getJSONObject(0).has("is_error"))
        assertTrue(blocks.getJSONObject(1).getBoolean("is_error"))
    }

    @Test fun anthropicToolUseResponseIsParsedAndRawContentSurvivesReplay() {
        val response = """
            {"id":"msg_1","type":"message","role":"assistant","stop_reason":"tool_use",
             "content":[{"type":"thinking","thinking":"","signature":"sig-abc"},
                        {"type":"text","text":"Guardo i dati."},
                        {"type":"tool_use","id":"toolu_9","name":"elenca_conti","input":{"pilastro":4,"con_valutazioni":true}}],
             "usage":{"input_tokens":120,"output_tokens":45}}
        """.trimIndent()
        val parsed = AnthropicWire.parse(response)
        assertEquals(AiStop.TOOL_USE, parsed.stop)
        assertEquals("Guardo i dati.", parsed.text)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("toolu_9", parsed.toolCalls[0].id)
        assertEquals("elenca_conti", parsed.toolCalls[0].name)
        assertEquals(4, parsed.toolCalls[0].arguments.getInt("pilastro"))
        assertEquals(AiUsage(120, 45), parsed.usage)
        val replay = AnthropicWire.messages(listOf(AiMessage.User("q"), AiMessage.Assistant(parsed.text, parsed.toolCalls, parsed.raw)))
        val content = replay.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("sig-abc", content.getJSONObject(0).getString("signature"))
        assertEquals(3, content.length())
    }

    @Test fun anthropicStopReasonsMapToNeutralStops() {
        fun stop(reason: String, extra: String = "") =
            AnthropicWire.parse("""{"stop_reason":"$reason","content":[{"type":"text","text":"t"}]$extra}""")
        assertEquals(AiStop.END, stop("end_turn").stop)
        assertEquals(AiStop.TRUNCATED, stop("max_tokens").stop)
        val refusal = stop("refusal", ""","stop_details":{"type":"refusal","category":"general_harms"}""")
        assertEquals(AiStop.REFUSED, refusal.stop)
        assertEquals("general_harms", refusal.note)
        assertEquals(AiStop.END, stop("tool_use").stop) // no tool_use block: nothing to run
        assertEquals(AiStop.OTHER, stop("pause_turn").stop)
    }

    @Test fun anthropicInvalidJsonIsABadResponse() {
        val e = assertThrows(AiTransportException::class.java) { AnthropicWire.parse("<html>") }
        assertEquals(AiTransportException.Kind.BAD_RESPONSE, e.kind)
    }

    // ---- OpenAI ----

    @Test fun openAiRequestUsesChatCompletionsToolsAndReasoningNone() {
        val body = OpenAiWire.requestBody(request("gpt-5.6-terra", AiMessage.User("Ciao")))
        assertEquals("gpt-5.6-terra", body.getString("model"))
        assertEquals(16_000, body.getInt("max_completion_tokens"))
        assertEquals("none", body.getString("reasoning_effort"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Sistema.", messages.getJSONObject(0).getString("content"))
        val function = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", function.getString("type"))
        assertEquals("riepilogo_pilastri", function.getJSONObject("function").getString("name"))
        assertEquals("object", function.getJSONObject("function").getJSONObject("parameters").getString("type"))
        listOf("temperature", "max_tokens").forEach { assertFalse(it, body.has(it)) }
    }

    @Test fun reasoningNoneIsOnlySentWhereChatCompletionsToolsNeedIt() {
        listOf("gpt-5.4", "gpt-5.5", "gpt-5.6-terra", "gpt-6.1-sol", "gpt-6-astra").forEach { assertTrue(it, OpenAiWire.needsReasoningNone(it)) }
        listOf("gpt-4o", "gpt-5", "gpt-5.1", "o3", "gpt-4.1-mini").forEach { assertFalse(it, OpenAiWire.needsReasoningNone(it)) }
        assertFalse(OpenAiWire.requestBody(request("gpt-4o", AiMessage.User("x"))).has("reasoning_effort"))
    }

    @Test fun openAiToolCallsRoundTripThroughMessages() {
        val rawArguments = """{"pilastro": 4}"""
        val messages = OpenAiWire.messages("S", listOf(
            AiMessage.User("Domanda"),
            AiMessage.Assistant("", listOf(AiToolCall("call_1", "elenca_conti", JSONObject(rawArguments), rawArguments))),
            AiMessage.ToolResult("call_1", "elenca_conti", "Dato non valido", isError = true),
            AiMessage.ToolResult("call_2", "riepilogo_pilastri", "{}"),
        ))
        assertEquals(5, messages.length())
        val assistant = messages.getJSONObject(2)
        assertTrue(assistant.isNull("content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals(rawArguments, call.getJSONObject("function").getString("arguments"))
        val failed = messages.getJSONObject(3)
        assertEquals("tool", failed.getString("role"))
        assertEquals("call_1", failed.getString("tool_call_id"))
        assertEquals("ERRORE: Dato non valido", failed.getString("content"))
        assertEquals("{}", messages.getJSONObject(4).getString("content"))
    }

    @Test fun openAiToolCallResponseIsParsed() {
        val response = """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"call_7","type":"function","function":{"name":"elenca_movimenti","arguments":"{\"pilastro\":4,\"limite\":10}"}}]}}],
             "usage":{"prompt_tokens":200,"completion_tokens":30}}
        """.trimIndent()
        val parsed = OpenAiWire.parse(response)
        assertEquals(AiStop.TOOL_USE, parsed.stop)
        assertEquals("", parsed.text)
        val call = parsed.toolCalls.single()
        assertEquals("call_7", call.id)
        assertEquals("elenca_movimenti", call.name)
        assertEquals(10, call.arguments.getInt("limite"))
        assertEquals("""{"pilastro":4,"limite":10}""", call.rawArguments)
        assertNull(call.argumentsError)
        assertEquals(AiUsage(200, 30), parsed.usage)
    }

    @Test fun openAiBadArgumentsAndOtherFinishReasons() {
        val bad = OpenAiWire.parse("""{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"c","type":"function","function":{"name":"x","arguments":"{non json"}}]}}]}""")
        assertTrue(bad.toolCalls.single().argumentsError!!.contains("JSON"))
        assertEquals(AiStop.END, OpenAiWire.parse("""{"choices":[{"finish_reason":"stop","message":{"content":"Ciao"}}]}""").stop)
        assertEquals(AiStop.TRUNCATED, OpenAiWire.parse("""{"choices":[{"finish_reason":"length","message":{"content":"Cia"}}]}""").stop)
        val refused = OpenAiWire.parse("""{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"Non posso."}}]}""")
        assertEquals(AiStop.REFUSED, refused.stop)
        assertEquals("Non posso.", refused.note)
        assertThrows(AiTransportException::class.java) { OpenAiWire.parse("""{"choices":[]}""") }
    }

    @Test fun registryToolSpecsFitBothProviders() {
        val specs = AiTools.specs()
        val names = specs.map { it.name }
        assertEquals(names.size, names.toSet().size)
        specs.forEach {
            assertTrue(it.name, Regex("[a-zA-Z0-9_-]{1,64}").matches(it.name))
            assertTrue(it.description.isNotBlank())
            assertEquals("object", it.parameters.getString("type"))
        }
        val anthropic = AnthropicWire.requestBody(AiRequest("claude-sonnet-5-5", "S", listOf(AiMessage.User("q")), specs))
        val openAi = OpenAiWire.requestBody(AiRequest("gpt-5.6-terra", "S", listOf(AiMessage.User("q")), specs))
        assertEquals(specs.size, anthropic.getJSONArray("tools").length())
        assertEquals(specs.size, openAi.getJSONArray("tools").length())
        assertTrue(JSONArray(names).length() == specs.size)
    }
}
