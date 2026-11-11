package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One provider behind a neutral interface. A transport never executes tools and never retries a write. */
interface AiTransport {
    val provider: AiProvider

    /** Who answers, as shown with the reply and stamped on changes ("Anthropic", "ChatGPT (account)"). */
    val label: String get() = provider.label

    /** Throws [AiTransportException] with an Italian message; cancellation propagates unchanged. */
    suspend fun send(request: AiRequest): AiResponse
}

data class AiHttpReply(val status: Int, val body: String)

/** The only network seam, so parsing and error mapping can be tested without a socket. */
fun interface AiHttp {
    suspend fun post(url: String, headers: Map<String, String>, body: String): AiHttpReply
}

class OkHttpAiHttp(private val client: OkHttpClient = shared) : AiHttp {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): AiHttpReply =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody(JSON))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use { AiHttpReply(it.code, it.body?.string().orEmpty()) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(reply)
                }
            })
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Non-streaming responses arrive whole, so the read timeout is the time the model may think. Redirects are never
         * followed: the key travels in a header (`x-api-key`, `Authorization`) and api.anthropic.com / api.openai.com never
         * redirect a call; a 3xx comes back as [AiErrors.fromHttp]'s redirect error instead.
         */
        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(240, TimeUnit.SECONDS)
                .callTimeout(270, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}

/** Italian error text for failed provider calls. Provider messages are scrubbed of anything key-like. */
object AiErrors {
    fun fromHttp(provider: AiProvider, status: Int, body: String): AiTransportException {
        val name = provider.label
        val detail = errorDetail(body)
        val lowerDetail = detail.lowercase()
        val kindAndText: Pair<AiTransportException.Kind, String> = when {
            status in 300..399 -> AiTransportException.Kind.NETWORK to
                "$name ha risposto con un reindirizzamento ($status): per non inviare la chiave altrove l'app non lo segue. " +
                    "Può dipendere dalla rete (proxy o pagina di accesso del Wi-Fi): riprova da un'altra rete."
            status == 401 -> AiTransportException.Kind.AUTH to
                "Chiave API non valida o revocata (401). Controlla la chiave di $name nelle impostazioni dell'assistente."
            status == 403 -> AiTransportException.Kind.AUTH to
                "Accesso negato da $name (403): la chiave non ha i permessi per questo modello o per la tua area."
            status == 402 -> AiTransportException.Kind.RATE_LIMIT to
                "Credito esaurito o problema di fatturazione presso $name (402)."
            status == 404 -> AiTransportException.Kind.BAD_REQUEST to
                "Modello non trovato o non disponibile per la tua chiave (404). Controlla l'ID del modello nelle impostazioni."
            status == 413 -> AiTransportException.Kind.BAD_REQUEST to
                "La richiesta è troppo grande (413). Riduci la domanda o riprova con meno dati."
            status == 429 && ("insufficient_quota" in body || "credit balance" in lowerDetail || "quota" in lowerDetail) ->
                AiTransportException.Kind.RATE_LIMIT to "Quota o credito esauriti presso $name (429). Controlla il piano di fatturazione."
            status == 429 -> AiTransportException.Kind.RATE_LIMIT to
                "Troppe richieste a $name (429). Attendi qualche istante e riprova."
            status == 400 && provider == AiProvider.OPENAI && "responses" in lowerDetail -> AiTransportException.Kind.BAD_REQUEST to
                "OpenAI rifiuta questo modello su Chat Completions con gli strumenti (400): richiede la Responses API. " +
                    "Scegli un modello compatibile, ad esempio ${AiProvider.OPENAI.defaultModel}."
            status == 400 || status == 422 -> AiTransportException.Kind.BAD_REQUEST to
                "Richiesta rifiutata da $name ($status)" + if (detail.isNotEmpty()) ": $detail" else "."
            status == 529 || status >= 500 -> AiTransportException.Kind.SERVER to
                "$name è temporaneamente non disponibile o sovraccarico ($status). Riprova tra poco."
            else -> AiTransportException.Kind.UNKNOWN to
                "Errore imprevisto da $name (HTTP $status)." + if (detail.isNotEmpty()) " $detail" else ""
        }
        return AiTransportException(kindAndText.first, kindAndText.second, status)
    }

    fun fromIo(provider: AiProvider, e: IOException): AiTransportException =
        if (e is InterruptedIOException || e.message?.contains("timeout", ignoreCase = true) == true)
            AiTransportException(AiTransportException.Kind.TIMEOUT, "Tempo scaduto in attesa della risposta di ${provider.label}. Riprova.")
        else AiTransportException(AiTransportException.Kind.NETWORK, "Connessione non riuscita: controlla la rete e riprova.")

    /** The provider's own message, shortened and with anything shaped like a key or a long number masked. */
    internal fun errorDetail(body: String): String {
        val message = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return message.replace(Regex("(sk|key)[-_A-Za-z0-9]{8,}"), "[chiave]").let { AiPolicy.redact(it) }.take(200).trim()
    }
}

private suspend fun AiHttp.postMapped(provider: AiProvider, url: String, headers: Map<String, String>, body: String): AiHttpReply =
    try {
        post(url, headers, body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw AiErrors.fromIo(provider, e)
    }

/**
 * Anthropic Messages API with native tool use. The default model is the current Sonnet. Sampling parameters are
 * never sent (the 2026-10-21 API version rejects them). Thinking is left at the model default (adaptive) and the
 * assistant `content` array is replayed unchanged inside a tool loop, as thinking blocks require.
 */
class AnthropicTransport(
    private val apiKey: String,
    private val http: AiHttp = OkHttpAiHttp(),
    private val effort: String? = "medium",
    private val serverFallback: Boolean = true,
) : AiTransport {
    override val provider = AiProvider.ANTHROPIC

    @Volatile private var fallbackRefused = false

    override suspend fun send(request: AiRequest): AiResponse {
        val useFallback = serverFallback && !fallbackRefused && AnthropicWire.supportsServerFallback(request.model)
        var reply = http.postMapped(provider, AnthropicWire.ENDPOINT, AnthropicWire.headers(apiKey, useFallback),
            AnthropicWire.requestBody(request, effort, useFallback).toString())
        if (useFallback && reply.status == 400 && AnthropicWire.isFallbackRejection(reply.body)) {
            // The optional server-side fallback beta was refused for this account: go without it. Nothing has run yet.
            fallbackRefused = true
            reply = http.postMapped(provider, AnthropicWire.ENDPOINT, AnthropicWire.headers(apiKey, false),
                AnthropicWire.requestBody(request, effort, false).toString())
        }
        if (reply.status !in 200..299) throw AiErrors.fromHttp(provider, reply.status, reply.body)
        return AnthropicWire.parse(reply.body)
    }
}

/** OpenAI Chat Completions with function calling (`tools` / `tool_calls`). */
class OpenAiTransport(
    private val apiKey: String,
    private val http: AiHttp = OkHttpAiHttp(),
) : AiTransport {
    override val provider = AiProvider.OPENAI

    override suspend fun send(request: AiRequest): AiResponse {
        val reply = http.postMapped(provider, OpenAiWire.ENDPOINT, OpenAiWire.headers(apiKey), OpenAiWire.requestBody(request).toString())
        if (reply.status !in 200..299) throw AiErrors.fromHttp(provider, reply.status, reply.body)
        return OpenAiWire.parse(reply.body)
    }
}

object AiTransports {
    fun create(provider: AiProvider, apiKey: String, http: AiHttp = OkHttpAiHttp()): AiTransport = when (provider) {
        AiProvider.ANTHROPIC -> AnthropicTransport(apiKey, http)
        AiProvider.OPENAI -> OpenAiTransport(apiKey, http)
    }
}
