package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * The Responses API as the ChatGPT-plan route uses it (OpenAI "Models and inference" and "Preview limitations", read
 * 2026-10-31): `POST https://api.openai.com/v1/responses` with the OAuth access token as bearer, `store: false`,
 * `stream: true`, the whole history in `input`, the system prompt in `instructions` (system-role items are rejected), and
 * function tools grouped in a namespace. Unsupported fields (`max_output_tokens`, `temperature`, `previous_response_id`, …)
 * are never sent. Reasoning items come back with `encrypted_content` in this stateless mode and are replayed, without ids,
 * inside the tool loop. Pure, so it is tested without a socket.
 */
object ResponsesWire {
    const val ENDPOINT = "https://api.openai.com/v1/responses"
    const val MODELS = "https://api.openai.com/v1/models"
    const val NAMESPACE = "pilastri"

    fun headers(accessToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $accessToken",
        "Content-Type" to "application/json",
        "Accept" to "text/event-stream",
    )

    fun requestBody(request: AiRequest): JSONObject {
        val body = JSONObject()
            .put("model", request.model)
            .put("instructions", request.system)
            .put("input", input(request.messages))
            .put("store", false)
            .put("stream", true)
        if (request.tools.isNotEmpty()) {
            val functions = JSONArray()
            request.tools.forEach { tool ->
                // Optional arguments exist, so the schemas are not strict (Responses functions are strict by default).
                functions.put(JSONObject().put("type", "function").put("name", tool.name).put("description", tool.description)
                    .put("parameters", tool.parameters).put("strict", false))
            }
            body.put("tools", JSONArray().put(JSONObject().put("type", "namespace").put("name", NAMESPACE)
                .put("description", "Lettura e modifiche proposte dell'archivio Pilastri dell'utente.").put("tools", functions)))
        }
        return body
    }

    /** Plain text, or (with attachments) `input_text` then `input_image` / `input_file` parts with data URLs. */
    internal fun userContent(message: AiMessage.User): Any {
        if (message.attachments.isEmpty()) return message.text
        val parts = JSONArray().put(JSONObject().put("type", "input_text").put("text", message.text))
        message.attachments.forEach { file ->
            parts.put(
                if (file.isPdf) JSONObject().put("type", "input_file").put("filename", file.genericName).put("file_data", file.dataUrl())
                else JSONObject().put("type", "input_image").put("image_url", file.dataUrl()),
            )
        }
        return parts
    }

    fun input(list: List<AiMessage>): JSONArray {
        val out = JSONArray()
        list.forEach { message ->
            when (message) {
                is AiMessage.User -> out.put(JSONObject().put("role", "user").put("content", userContent(message)))
                is AiMessage.Assistant -> {
                    val raw = message.raw
                    if (raw is JSONArray) {
                        for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { out.put(it) }
                    } else {
                        if (message.text.isNotEmpty()) {
                            out.put(JSONObject().put("type", "message").put("role", "assistant")
                                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", message.text))))
                        }
                        message.toolCalls.forEach { call ->
                            out.put(JSONObject().put("type", "function_call").put("call_id", call.id).put("name", call.name)
                                .put("namespace", NAMESPACE).put("arguments", call.rawArguments ?: call.arguments.toString()))
                        }
                    }
                }
                is AiMessage.ToolResult -> out.put(JSONObject().put("type", "function_call_output").put("call_id", message.callId)
                    .put("output", if (message.isError) "ERRORE: ${message.content}" else message.content))
            }
        }
        return out
    }

    /** Server-sent events: each `data:` payload as JSON, in order. Comments, `[DONE]` and broken payloads are skipped. */
    fun events(sse: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val data = StringBuilder()
        fun flush() {
            val text = data.toString().trim()
            data.setLength(0)
            if (text.isEmpty() || text == "[DONE]") return
            try { out += JSONObject(text) } catch (_: JSONException) { }
        }
        sse.replace("\r\n", "\n").split('\n').forEach { line ->
            when {
                line.isEmpty() -> flush()
                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").removePrefix(" ")) }
                else -> Unit // event:, id:, retry:, comments
            }
        }
        flush()
        return out
    }

    /**
     * Reads a finished stream. Success needs `response.completed`; `response.incomplete` is a truncation or a refusal;
     * `response.failed` and `error` events throw with their code. A stream that just stops is an error too.
     */
    fun parseStream(sse: String): AiResponse {
        val events = events(sse)
        val doneItems = JSONArray()
        var terminal: JSONObject? = null
        var terminalType = ""
        for (e in events) {
            when (val type = e.optString("type")) {
                "response.output_item.done" -> e.optJSONObject("item")?.let { doneItems.put(it) }
                "response.completed", "response.incomplete" -> { terminal = e.optJSONObject("response") ?: JSONObject(); terminalType = type }
                "response.failed" -> {
                    val err = e.optJSONObject("response")?.optJSONObject("error")
                    throw ChatGptErrors.fromCode(err?.optString("code"), err?.optString("message"), null)
                }
                "error" -> throw ChatGptErrors.fromCode(e.optString("code").ifBlank { e.optJSONObject("error")?.optString("code") },
                    e.optString("message").ifBlank { e.optJSONObject("error")?.optString("message") }, null)
            }
        }
        val response = terminal ?: throw AiTransportException(AiTransportException.Kind.BAD_RESPONSE,
            "La risposta di ChatGPT si è interrotta prima della fine: nessuna operazione è stata eseguita.")
        val output = response.optJSONArray("output")?.takeIf { it.length() > 0 } ?: doneItems
        val texts = ArrayList<String>()
        val calls = ArrayList<AiToolCall>()
        val replay = JSONArray()
        var refusal: String? = null
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (k in 0 until content.length()) {
                        val part = content.optJSONObject(k) ?: continue
                        when (part.optString("type")) {
                            "output_text" -> part.optString("text").takeIf { it.isNotEmpty() }?.let(texts::add)
                            "refusal" -> refusal = part.optString("refusal").ifBlank { "rifiuto" }
                        }
                    }
                }
                "function_call" -> {
                    val rawArguments = item.optString("arguments").ifBlank { "{}" }
                    val parsed = try { JSONObject(rawArguments) } catch (_: JSONException) { null }
                    calls += AiToolCall(item.optString("call_id"), item.optString("name"), parsed ?: JSONObject(), rawArguments,
                        if (parsed == null) "Argomenti dello strumento non interpretabili (JSON non valido)." else null)
                }
            }
            // Replayed in the next round: the same item without its id (not stored with store:false).
            replay.put(JSONObject(item.toString()).apply { remove("id") })
        }
        val incomplete = response.optJSONObject("incomplete_details")?.optString("reason")
        val stop = when {
            refusal != null -> AiStop.REFUSED
            terminalType == "response.incomplete" && incomplete == "content_filter" -> AiStop.REFUSED
            terminalType == "response.incomplete" -> AiStop.TRUNCATED
            calls.isNotEmpty() -> AiStop.TOOL_USE
            else -> AiStop.END
        }
        val usage = response.optJSONObject("usage")?.let { AiUsage(it.optLong("input_tokens"), it.optLong("output_tokens")) } ?: AiUsage()
        return AiResponse(texts.joinToString("\n"), calls, replay, stop, usage, refusal ?: incomplete?.takeIf { stop == AiStop.REFUSED })
    }

    /** `GET /v1/models`: the slugs meant for display (`visibility: "list"`), in the server's order. */
    fun parseModels(body: String): List<Pair<String, String>> = try {
        val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
        (0 until models.length()).mapNotNull { models.optJSONObject(it) }
            .filter { it.optString("visibility") == "list" && it.optString("slug").isNotBlank() }
            .map { it.optString("slug") to it.optString("display_name").ifBlank { it.optString("slug") } }
    } catch (_: JSONException) { emptyList() }
}

/** Italian errors for the ChatGPT-plan route (OpenAI "Errors and recovery"). Never echoes a token. */
object ChatGptErrors {
    const val USAGE_LIMIT = "subscription_sharing_usage_limit_exceeded"

    fun fromCode(code: String?, message: String?, status: Int?): AiTransportException = when (code) {
        USAGE_LIMIT -> AiTransportException(AiTransportException.Kind.RATE_LIMIT,
            "Limite d'uso del piano ChatGPT raggiunto (per il piano o per questa app). Controlla in ChatGPT → Impostazioni → Utilizzo.", status)
        "subscription_sharing_user_not_eligible" -> AiTransportException(AiTransportException.Kind.AUTH,
            "Il piano ChatGPT di questo account non può essere usato da app esterne (utente, workspace o criterio).", status)
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> AiTransportException(AiTransportException.Kind.SERVER,
            "OpenAI non riesce a verificare ora l'uso del piano ChatGPT. Riprova tra poco.", status)
        "subscription_sharing_unsupported_capability" -> AiTransportException(AiTransportException.Kind.BAD_REQUEST,
            "Il piano ChatGPT non accetta questa richiesta (modello o funzione non supportati). Scegli un altro modello in Assistenti.", status)
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            AiTransportException(AiTransportException.Kind.AUTH, "OpenAI non accetta l'autorizzazione del piano ChatGPT: accedi di nuovo in Assistenti.", status)
        "subscription_sharing_route_not_supported" -> AiTransportException(AiTransportException.Kind.BAD_REQUEST,
            "OpenAI non accetta questa chiamata con il piano ChatGPT.", status)
        else -> AiTransportException(AiTransportException.Kind.UNKNOWN,
            "ChatGPT ha interrotto la risposta" + (code?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                (message?.let { AiPolicy.redact(it).take(160) }?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."), status)
    }

    /** A non-2xx reply before the stream opened: a Responses `error` object, or a bare `{"detail": "..."}` admission body. */
    fun fromHttp(status: Int, body: String): AiTransportException {
        val o = try { JSONObject(body) } catch (_: JSONException) { null }
        val err = o?.optJSONObject("error")
        val code = err?.optString("code")?.takeIf { it.isNotBlank() }
        if (code != null) return fromCode(code, err.optString("message"), status)
        return when {
            status in 300..399 -> AiErrors.fromHttp(AiProvider.OPENAI, status, body)
            status == 401 -> AiTransportException(AiTransportException.Kind.AUTH, "OpenAI non accetta l'accesso a ChatGPT (401): accedi di nuovo in Assistenti.", status)
            status == 403 -> AiTransportException(AiTransportException.Kind.AUTH,
                "OpenAI ha bloccato la richiesta con il piano ChatGPT (403): permesso o regione non ammessi.", status)
            status == 503 -> AiTransportException(AiTransportException.Kind.SERVER, "L'uso del piano ChatGPT non è disponibile ora (503). Riprova tra poco.", status)
            else -> AiErrors.fromHttp(AiProvider.OPENAI, status, body)
        }
    }
}

/**
 * The ChatGPT-plan transport: native tool calling through the same [AiAgent] loop, [AiWriteRouter] tiers, taint rule and
 * undo as the API-key routes. A 401 before the stream opened is retried once with a refreshed token (nothing ran yet).
 */
class ChatGptPlanTransport(
    private val session: ChatGptSession,
    private val http: AiHttp = OkHttpAiHttp(),
) : AiTransport {
    override val provider = AiProvider.OPENAI
    override val label: String = AiRoute.CHATGPT_ACCOUNT.label

    override suspend fun send(request: AiRequest): AiResponse {
        val body = ResponsesWire.requestBody(request).toString()
        var reply = post(session.accessToken(), body)
        if (reply.status == 401) reply = post(session.accessToken(forceRefresh = true), body)
        if (reply.status !in 200..299) throw ChatGptErrors.fromHttp(reply.status, reply.body)
        return ResponsesWire.parseStream(reply.body)
    }

    private suspend fun post(token: String, body: String): AiHttpReply = try {
        http.post(ResponsesWire.ENDPOINT, ResponsesWire.headers(token), body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw AiErrors.fromIo(provider, e)
    }
}
