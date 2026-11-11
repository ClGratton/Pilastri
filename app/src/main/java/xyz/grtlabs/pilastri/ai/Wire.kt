package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Neutral conversation types shared by both providers. */
data class AiToolSpec(val name: String, val description: String, val parameters: JSONObject)

/** One tool call requested by the model. [argumentsError] is set when the provider sent unparseable arguments. */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
    val rawArguments: String? = null,
    val argumentsError: String? = null,
)

/**
 * An attachment's original bytes (a downscaled photo, a PDF) for a route that takes files. Sent only when the user left
 * "Oscura dati personali negli allegati" off; the original file name never goes out (the wire uses a generic one).
 */
class AiAttachment(val mime: String, val bytes: ByteArray) {
    val isPdf: Boolean get() = mime == "application/pdf"
    val isImage: Boolean get() = mime.startsWith("image/")
    internal fun dataUrl(): String = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    internal fun base64(): String = java.util.Base64.getEncoder().encodeToString(bytes)
    internal val genericName: String get() = if (isPdf) "allegato.pdf" else "allegato.jpg"
}

sealed interface AiMessage {
    data class User(val text: String, val attachments: List<AiAttachment> = emptyList()) : AiMessage

    /**
     * [raw] is provider-specific and must be replayed unchanged within a tool loop (Anthropic: the response
     * `content` array, including thinking blocks). It is null for text-only history.
     */
    data class Assistant(val text: String, val toolCalls: List<AiToolCall> = emptyList(), val raw: Any? = null) : AiMessage

    data class ToolResult(val callId: String, val name: String, val content: String, val isError: Boolean = false) : AiMessage
}

data class AiRequest(
    val model: String,
    val system: String,
    val messages: List<AiMessage>,
    val tools: List<AiToolSpec>,
    val maxTokens: Int = 16_000,
)

enum class AiStop { END, TOOL_USE, TRUNCATED, REFUSED, OTHER }

data class AiUsage(val inputTokens: Long = 0, val outputTokens: Long = 0) {
    operator fun plus(other: AiUsage) = AiUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}

data class AiResponse(
    val text: String,
    val toolCalls: List<AiToolCall>,
    val raw: Any?,
    val stop: AiStop,
    val usage: AiUsage = AiUsage(),
    val note: String? = null,
)

/** Thrown with an Italian, user-presentable message. It never contains the API key. */
class AiTransportException(val kind: Kind, message: String, val status: Int? = null) : Exception(message) {
    enum class Kind { AUTH, RATE_LIMIT, NETWORK, TIMEOUT, BAD_REQUEST, SERVER, BAD_RESPONSE, UNKNOWN }
}

/** Pure request building and response parsing for Anthropic's Messages API (native tool use). */
object AnthropicWire {
    const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    /** API version from the claude-api skill's raw-HTTP examples; it removes `temperature`, which is never sent here. */
    const val API_VERSION = "2026-10-21"
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    private val serverFallbackModels = setOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1")
    private val effortModels = Regex("claude-(sonnet|opus|haiku|fable|mythos)-5.*|claude-(sonnet|opus)-4-[678].*")

    fun supportsEffort(model: String) = effortModels.matches(model)
    fun supportsServerFallback(model: String) = model in serverFallbackModels

    fun headers(apiKey: String, fallback: Boolean): Map<String, String> = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", API_VERSION)
        put("content-type", "application/json")
        if (fallback) put("anthropic-beta", FALLBACK_BETA)
    }

    fun requestBody(request: AiRequest, effort: String? = "medium", fallback: Boolean = false): JSONObject {
        val body = JSONObject()
            .put("model", request.model)
            .put("max_tokens", request.maxTokens)
            .put("system", request.system)
        if (request.tools.isNotEmpty()) {
            body.put("tools", JSONArray().apply {
                request.tools.forEach { tool ->
                    put(JSONObject().put("name", tool.name).put("description", tool.description).put("input_schema", tool.parameters))
                }
            })
        }
        body.put("messages", messages(request.messages))
        if (effort != null && supportsEffort(request.model)) body.put("output_config", JSONObject().put("effort", effort))
        if (fallback && supportsServerFallback(request.model)) body.put("fallbacks", "default")
        return body
    }

    /** True when a 400 body says the beta header or `fallbacks` was refused, so the request can go without them. */
    fun isFallbackRejection(body: String): Boolean {
        val lower = body.lowercase()
        return "anthropic-beta" in lower || "fallbacks" in lower || "server-side-fallback" in lower
    }

    fun messages(list: List<AiMessage>): JSONArray {
        val out = JSONArray()
        var i = 0
        while (i < list.size) {
            when (val message = list[i]) {
                is AiMessage.User -> { out.put(JSONObject().put("role", "user").put("content", userContent(message))); i++ }
                is AiMessage.Assistant -> { out.put(assistant(message)); i++ }
                is AiMessage.ToolResult -> {
                    // All results for one assistant turn go back in a single user message.
                    val blocks = JSONArray()
                    while (i < list.size && list[i] is AiMessage.ToolResult) {
                        val result = list[i] as AiMessage.ToolResult
                        val block = JSONObject().put("type", "tool_result").put("tool_use_id", result.callId).put("content", result.content)
                        if (result.isError) block.put("is_error", true)
                        blocks.put(block)
                        i++
                    }
                    out.put(JSONObject().put("role", "user").put("content", blocks))
                }
            }
        }
        return out
    }

    /** Plain text, or (with attachments) the files as image/document blocks followed by the text. */
    internal fun userContent(message: AiMessage.User): Any {
        if (message.attachments.isEmpty()) return message.text
        val blocks = JSONArray()
        message.attachments.forEach { file ->
            val source = JSONObject().put("type", "base64").put("media_type", file.mime).put("data", file.base64())
            blocks.put(JSONObject().put("type", if (file.isPdf) "document" else "image").put("source", source))
        }
        blocks.put(JSONObject().put("type", "text").put("text", message.text))
        return blocks
    }

    private fun assistant(message: AiMessage.Assistant): JSONObject {
        val raw = message.raw
        if (raw is JSONArray) return JSONObject().put("role", "assistant").put("content", raw)
        if (message.toolCalls.isEmpty()) return JSONObject().put("role", "assistant").put("content", message.text)
        val blocks = JSONArray()
        if (message.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", message.text))
        message.toolCalls.forEach { call ->
            blocks.put(JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name).put("input", call.arguments))
        }
        return JSONObject().put("role", "assistant").put("content", blocks)
    }

    fun parse(body: String): AiResponse {
        val root = try { JSONObject(body) } catch (e: JSONException) { throw AiTransportException(AiTransportException.Kind.BAD_RESPONSE, "Risposta di Anthropic non interpretabile.") }
        val content = root.optJSONArray("content") ?: JSONArray()
        val texts = ArrayList<String>()
        val calls = ArrayList<AiToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let(texts::add)
                "tool_use" -> {
                    val input = block.optJSONObject("input") ?: JSONObject()
                    calls += AiToolCall(block.optString("id"), block.optString("name"), input, input.toString())
                }
            }
        }
        val stopReason = root.optString("stop_reason")
        val stop = when (stopReason) {
            "refusal" -> AiStop.REFUSED
            "max_tokens" -> AiStop.TRUNCATED
            "tool_use" -> if (calls.isNotEmpty()) AiStop.TOOL_USE else AiStop.END
            "end_turn", "stop_sequence" -> AiStop.END
            else -> AiStop.OTHER
        }
        val details = root.optJSONObject("stop_details")
        val note = if (stop == AiStop.REFUSED) details?.optString("category")?.takeIf { it.isNotEmpty() } else null
        val usage = root.optJSONObject("usage")?.let { AiUsage(it.optLong("input_tokens"), it.optLong("output_tokens")) } ?: AiUsage()
        return AiResponse(texts.joinToString("\n"), calls, content, stop, usage, note)
    }
}

/** Pure request building and response parsing for OpenAI Chat Completions with function calling. */
object OpenAiWire {
    const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

    private val reasoningNoneModels = Regex("gpt-(5\\.([4-9]|[1-9][0-9])|[6-9])(\\D.*)?")

    /**
     * Since GPT-5.4 Chat Completions only supports tool calling with reasoning_effort none (OpenAI migration guide,
     * 2026-10-28), so that value is sent for those models. Older models do not take the parameter.
     */
    fun needsReasoningNone(model: String) = reasoningNoneModels.matches(model)

    fun headers(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
    )

    fun requestBody(request: AiRequest): JSONObject {
        val body = JSONObject()
            .put("model", request.model)
            .put("messages", messages(request.system, request.messages))
            .put("max_completion_tokens", request.maxTokens)
        if (request.tools.isNotEmpty()) {
            body.put("tools", JSONArray().apply {
                request.tools.forEach { tool ->
                    put(JSONObject().put("type", "function").put("function",
                        JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", tool.parameters)))
                }
            })
        }
        if (needsReasoningNone(request.model)) body.put("reasoning_effort", "none")
        return body
    }

    /** Plain text, or (with attachments) text then `image_url` / `file` parts with data URLs. */
    internal fun userContent(message: AiMessage.User): Any {
        if (message.attachments.isEmpty()) return message.text
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", message.text))
        message.attachments.forEach { file ->
            parts.put(
                if (file.isPdf) JSONObject().put("type", "file").put("file", JSONObject().put("filename", file.genericName).put("file_data", file.dataUrl()))
                else JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", file.dataUrl())),
            )
        }
        return parts
    }

    fun messages(system: String, list: List<AiMessage>): JSONArray {
        val out = JSONArray()
        out.put(JSONObject().put("role", "system").put("content", system))
        list.forEach { message ->
            when (message) {
                is AiMessage.User -> out.put(JSONObject().put("role", "user").put("content", userContent(message)))
                is AiMessage.Assistant -> {
                    val json = JSONObject().put("role", "assistant")
                    json.put("content", if (message.text.isEmpty()) JSONObject.NULL else message.text)
                    if (message.toolCalls.isNotEmpty()) {
                        json.put("tool_calls", JSONArray().apply {
                            message.toolCalls.forEach { call ->
                                put(JSONObject().put("id", call.id).put("type", "function").put("function",
                                    JSONObject().put("name", call.name).put("arguments", call.rawArguments ?: call.arguments.toString())))
                            }
                        })
                    }
                    out.put(json)
                }
                is AiMessage.ToolResult -> out.put(JSONObject().put("role", "tool").put("tool_call_id", message.callId)
                    .put("content", if (message.isError) "ERRORE: ${message.content}" else message.content))
            }
        }
        return out
    }

    fun parse(body: String): AiResponse {
        val root = try { JSONObject(body) } catch (e: JSONException) { throw AiTransportException(AiTransportException.Kind.BAD_RESPONSE, "Risposta di OpenAI non interpretabile.") }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiTransportException(AiTransportException.Kind.BAD_RESPONSE, "Risposta di OpenAI senza contenuto.")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val text = if (message.isNull("content")) "" else message.optString("content")
        val calls = ArrayList<AiToolCall>()
        val rawCalls = message.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val call = rawCalls.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val rawArguments = function.optString("arguments").ifBlank { "{}" }
                val parsed = try { JSONObject(rawArguments) } catch (_: JSONException) { null }
                calls += AiToolCall(call.optString("id"), function.optString("name"), parsed ?: JSONObject(), rawArguments,
                    if (parsed == null) "Argomenti dello strumento non interpretabili (JSON non valido)." else null)
            }
        }
        val refusal = message.optString("refusal").takeIf { !message.isNull("refusal") && it.isNotEmpty() }
        val stop = when {
            refusal != null -> AiStop.REFUSED
            choice.optString("finish_reason") == "length" -> AiStop.TRUNCATED
            choice.optString("finish_reason") == "content_filter" -> AiStop.REFUSED
            calls.isNotEmpty() -> AiStop.TOOL_USE
            choice.optString("finish_reason") == "stop" -> AiStop.END
            else -> AiStop.OTHER
        }
        val usage = root.optJSONObject("usage")?.let { AiUsage(it.optLong("prompt_tokens"), it.optLong("completion_tokens")) } ?: AiUsage()
        return AiResponse(text, calls, null, stop, usage, refusal)
    }
}
