package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/*
 * "Sign in with ChatGPT" with ChatGPT plan usage, the open-source flow (OpenAI docs read 2026-10-31):
 *  - https://developers.openai.com/siwc/token-sharing-open-source            (overview, host id)
 *  - https://developers.openai.com/siwc/token-sharing-open-source/sign-in    (authorize, callback, token exchange)
 *  - .../profiles-and-sessions (refresh, revocation) · .../token-reference (lifetimes) · .../errors-and-recovery
 *  - .../models-and-inference and .../preview-limitations (Responses API contract, see ResponsesWire.kt)
 * Eligibility as stated there: "These docs explain ChatGPT plan usage for open-source and locally hosted apps. If you're
 * interested in offering it in a paid or remotely hosted app, complete the interest form." Pilastri runs locally on the phone
 * and is not paid; it is not published as open source yet (licence unresolved, COUNCIL.md), so publication must settle that.
 *
 * The user signs in in the system browser (a Custom Tab), never in a WebView; the app sees no password. The callback comes back
 * to a loopback listener on 127.0.0.1 (the only redirect form the docs allow). Tokens live in the Keystore vault.
 */
object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
    const val DISCOVERY = "https://auth.openai.com/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val REGISTRATION_CLIENT = "dynamic_agent_client"
    const val AGENT_NAME = "Pilastri"
    const val CALLBACK_PATH = "/auth/callback"
    const val PREFERRED_PORT = 1455
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    const val USAGE_URL = "https://chatgpt.com/settings/usage"

    /** One authorization attempt: fresh state, nonce and PKCE verifier, and the exact callback URI. */
    data class Attempt(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        /** [REGISTRATION_CLIENT] for a first registration, else the issued client id being reauthorized. */
        val clientId: String,
    ) {
        val newRegistration: Boolean get() = clientId == REGISTRATION_CLIENT
    }

    private fun randomToken(random: SecureRandom, bytes: Int = 32) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun newAttempt(port: Int, issuedClientId: String?, random: SecureRandom = SecureRandom()) = Attempt(
        state = randomToken(random),
        nonce = randomToken(random),
        verifier = randomToken(random, 48),
        redirectUri = "http://127.0.0.1:$port$CALLBACK_PATH",
        clientId = issuedClientId?.takeIf { it.isNotBlank() && it != REGISTRATION_CLIENT } ?: REGISTRATION_CLIENT,
    )

    /** S256: base64url of the SHA-256 of the verifier, no padding. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    /** A host id the docs accept: `urn:uuid:` + a UUIDv4, generated once per installation. */
    fun newHostId(): String = "urn:uuid:" + UUID.randomUUID().toString()

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** The authorize URL. [idTokenHint] and [loginHint] only on reauthorization; `agent_name_hint` only on first registration. */
    fun authorizeUrl(attempt: Attempt, hostId: String, idTokenHint: String? = null, loginHint: String? = null, forceConsent: Boolean = false): String {
        val params = LinkedHashMap<String, String>()
        params["client_id"] = attempt.clientId
        if (attempt.newRegistration) params["agent_name_hint"] = AGENT_NAME
        params["ext_agent_host_id"] = hostId
        if (!attempt.newRegistration) {
            idTokenHint?.takeIf { it.isNotBlank() }?.let { params["id_token_hint"] = it }
            loginHint?.takeIf { it.isNotBlank() }?.let { params["login_hint"] = it }
        }
        params["response_type"] = "code"
        params["redirect_uri"] = attempt.redirectUri
        params["scope"] = SCOPES
        params["resource"] = RESOURCE
        params["state"] = attempt.state
        params["nonce"] = attempt.nonce
        params["code_challenge_method"] = "S256"
        params["code_challenge"] = challenge(attempt.verifier)
        // Enabling plan use after an earlier decline: the docs keep `prompt=consent` until `force_reconsent` is rolled out.
        if (forceConsent) params["prompt"] = "consent"
        return AUTHORIZE + "?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    sealed interface Callback {
        data class Code(val code: String, val clientId: String) : Callback
        /** The user declined (`error=access_denied`) or another OAuth error came back with a valid state. */
        data class Denied(val error: String) : Callback
        data class Invalid(val reason: String) : Callback
    }

    fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { part ->
        val key = part.substringBefore('=')
        val value = if ('=' in part) part.substringAfter('=') else ""
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    /** Validates the callback against the pending [attempt] before anything else is used. */
    fun parseCallback(query: String, attempt: Attempt): Callback {
        val q = try { parseQuery(query) } catch (_: IllegalArgumentException) { return Callback.Invalid("risposta di accesso non leggibile") }
        if (q["state"] != attempt.state) return Callback.Invalid("risposta di accesso non riconosciuta (state diverso)")
        q["error"]?.let { return Callback.Denied(it) }
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return Callback.Invalid("manca il codice di accesso")
        val returned = q["client_id"]?.takeIf { it.isNotBlank() }
        val clientId = if (attempt.newRegistration) {
            // A new registration must return the issued id; dynamic_agent_client is never the id to keep.
            returned?.takeIf { it != REGISTRATION_CLIENT } ?: return Callback.Invalid("registrazione incompleta: manca l'identificativo dell'app")
        } else {
            if (returned != null && returned != attempt.clientId) return Callback.Invalid("l'accesso ha restituito un'altra registrazione: rifiutata")
            attempt.clientId
        }
        return Callback.Code(code, clientId)
    }

    fun tokenForm(code: String, clientId: String, attempt: Attempt): Map<String, String> = linkedMapOf(
        "grant_type" to "authorization_code",
        "client_id" to clientId,
        "code" to code,
        "code_verifier" to attempt.verifier,
        "redirect_uri" to attempt.redirectUri,
        "resource" to RESOURCE,
    )

    /** Refresh near expiry; `scope` is omitted to keep the grant. */
    fun refreshForm(clientId: String, refreshToken: String): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token",
        "client_id" to clientId,
        "refresh_token" to refreshToken,
        "resource" to RESOURCE,
    )

    fun revokeForm(clientId: String, refreshToken: String): Map<String, String> = linkedMapOf(
        "token" to refreshToken,
        "token_type_hint" to "refresh_token",
        "client_id" to clientId,
    )

    data class Tokens(val access: String, val refresh: String?, val idToken: String?, val expiresAt: Long, val scopes: Set<String>)

    /** A 200 token response. [nowSec] is when it arrived. Throws [ChatGptAuthException] when it carries no access token. */
    fun parseTokens(body: String, nowSec: Long): Tokens {
        val o = try { JSONObject(body) } catch (_: JSONException) { throw ChatGptAuthException("Risposta di accesso di OpenAI non leggibile.") }
        val access = o.optString("access_token").takeIf { it.isNotBlank() } ?: throw ChatGptAuthException("OpenAI non ha restituito un token di accesso.")
        val expiresIn = o.optLong("expires_in", 3600L).coerceIn(60L, 24 * 3600L)
        return Tokens(
            access,
            o.optString("refresh_token").takeIf { it.isNotBlank() },
            o.optString("id_token").takeIf { it.isNotBlank() },
            nowSec + expiresIn,
            o.optString("scope").split(' ').filter { it.isNotBlank() }.toSet(),
        )
    }

    /** The OAuth `error` code of a failed token or refresh call, or null. */
    fun errorCode(body: String): String? = try {
        val o = JSONObject(body)
        o.optString("error").takeIf { it.isNotBlank() } ?: o.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
    } catch (_: JSONException) { null }

    /** Refresh errors after which the token set is unusable: the user signs in again with the saved client id. */
    val TERMINAL_REFRESH_ERRORS = setOf(
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
    )
}

class ChatGptAuthException(message: String) : Exception(message)

/** Checks an RS256 ID token against OpenAI's JWKS: signature, issuer, audience, expiry and nonce. Pure. */
object ChatGptIdToken {
    data class Identity(val subject: String, val email: String?)

    private fun b64(part: String): ByteArray = Base64.getUrlDecoder().decode(part.trimEnd('='))

    /** The claims without any check (for the saved token's own subject/email only after [verify] passed once). */
    fun claims(token: String): JSONObject? = try {
        JSONObject(String(b64(token.split('.')[1]), StandardCharsets.UTF_8))
    } catch (_: Exception) { null }

    /** Throws [ChatGptAuthException] (Italian) when anything does not hold. [nowSec] allows 2 minutes of clock skew. */
    fun verify(token: String, jwks: JSONObject, clientId: String, nonce: String?, nowSec: Long): Identity {
        val parts = token.split('.')
        if (parts.size != 3) throw ChatGptAuthException("Token di identità non valido.")
        val header = try { JSONObject(String(b64(parts[0]), StandardCharsets.UTF_8)) } catch (_: Exception) { throw ChatGptAuthException("Token di identità non leggibile.") }
        if (header.optString("alg") != "RS256") throw ChatGptAuthException("Token di identità con un algoritmo non accettato.")
        val kid = header.optString("kid")
        val keys = jwks.optJSONArray("keys") ?: JSONArray()
        val jwk = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .firstOrNull { it.optString("kty") == "RSA" && (kid.isEmpty() || it.optString("kid") == kid) }
            ?: throw ChatGptAuthException("Chiave di firma di OpenAI non trovata.")
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64(jwk.getString("n"))), BigInteger(1, b64(jwk.getString("e")))),
        )
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
            verify(b64(parts[2]))
        }
        if (!ok) throw ChatGptAuthException("Firma del token di identità non valida.")
        val c = claims(token) ?: throw ChatGptAuthException("Token di identità non leggibile.")
        if (c.optString("iss") != ChatGptOAuth.ISSUER) throw ChatGptAuthException("Token di identità di un emittente sconosciuto.")
        val aud = c.opt("aud")
        val audOk = when (aud) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        if (!audOk) throw ChatGptAuthException("Token di identità per un'altra app.")
        if (c.optLong("exp", 0L) + 120 < nowSec) throw ChatGptAuthException("Token di identità scaduto.")
        if (nonce != null && c.optString("nonce") != nonce) throw ChatGptAuthException("Token di identità non legato a questo accesso (nonce).")
        val sub = c.optString("sub").takeIf { it.isNotBlank() } ?: throw ChatGptAuthException("Token di identità senza soggetto.")
        return Identity(sub, c.optString("email").takeIf { it.isNotBlank() })
    }
}

/**
 * One saved ChatGPT registration: the issued client id, the verified identity and the token set. Kept only in the Keystore
 * vault (never in the archive, a backup or a log). After a terminal refresh error the tokens are cleared but the client id,
 * identity and ID token stay, so the next sign-in reuses the registration (`id_token_hint`).
 */
data class ChatGptCredentials(
    val clientId: String,
    val subject: String,
    val email: String?,
    val idToken: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAt: Long,
    val scopes: Set<String>,
) {
    val hasTokens: Boolean get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
    val planEnabled: Boolean get() = ChatGptOAuth.PLAN_SCOPE in scopes

    fun withoutTokens() = copy(accessToken = null, refreshToken = null, expiresAt = 0L)

    fun toJson(): String = JSONObject()
        .put("client_id", clientId).put("subject", subject).put("email", email ?: JSONObject.NULL)
        .put("id_token", idToken ?: JSONObject.NULL).put("access_token", accessToken ?: JSONObject.NULL)
        .put("refresh_token", refreshToken ?: JSONObject.NULL).put("expires_at", expiresAt)
        .put("scopes", JSONArray(scopes.sorted())).toString()

    companion object {
        fun fromJson(text: String): ChatGptCredentials? = try {
            val o = JSONObject(text)
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
            val scopes = o.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            ChatGptCredentials(s("client_id") ?: return null, s("subject") ?: return null, s("email"), s("id_token"), s("access_token"),
                s("refresh_token"), o.optLong("expires_at"), scopes)
        } catch (_: JSONException) { null }
    }
}

/** Where the registration lives: credentials encrypted in the vault, the host id and the model in plain settings. */
class ChatGptStore(private val vault: AiVault, private val settings: KeyValueStore) {
    fun load(): ChatGptCredentials? = vault.getSecret(SECRET)?.let { ChatGptCredentials.fromJson(it) }
    fun save(credentials: ChatGptCredentials) = vault.setSecret(SECRET, credentials.toJson())
    fun clear() = vault.clearSecret(SECRET)

    /** Created once per installation and reused for every sign-in (the docs' "agent host"). Not a secret. */
    fun hostId(): String = settings.get(HOST) ?: ChatGptOAuth.newHostId().also { settings.put(HOST, it) }

    var model: String
        get() = settings.get(MODEL)?.takeIf { MODEL_SHAPE.matches(it) } ?: DEFAULT_MODEL
        set(value) { if (MODEL_SHAPE.matches(value.trim())) settings.put(MODEL, value.trim()) }

    /** The one-time "Stai usando il tuo piano ChatGPT" confirmation was shown. */
    var welcomed: Boolean
        get() = settings.get(WELCOMED) == "1"
        set(value) { if (value) settings.put(WELCOMED, "1") else settings.remove(WELCOMED) }

    companion object {
        const val SECRET = "chatgpt.credentials"
        const val HOST = "chatgpt.host"
        const val MODEL = "chatgpt.model"
        const val WELCOMED = "chatgpt.welcomed"
        /** The model in OpenAI's own examples for this flow; replaced by the first listed model after sign-in. */
        const val DEFAULT_MODEL = "gpt-6.1-sol"
        private val MODEL_SHAPE = Regex("[A-Za-z0-9._:/-]{1,100}")
    }
}

/** The auth endpoints as a seam, so refresh and sign-in can be tested without a socket. */
interface ChatGptAuthHttp {
    suspend fun postForm(url: String, fields: Map<String, String>): AiHttpReply
    suspend fun get(url: String, bearer: String? = null): AiHttpReply
}

/**
 * Hands out a valid access token for inference, refreshing it near expiry. Refreshes are serialized (the refresh token
 * rotates); a terminal refresh error clears the tokens and asks for a new sign-in; a network error keeps them.
 */
class ChatGptSession(
    private val store: ChatGptStore,
    private val http: ChatGptAuthHttp,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()

    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val creds = store.load()
        if (creds == null || !creds.hasTokens) throw AiTransportException(AiTransportException.Kind.AUTH, "ChatGPT non è collegato: accedi in Assistenti.")
        if (!creds.planEnabled) throw AiTransportException(AiTransportException.Kind.AUTH, "L'uso del piano ChatGPT non è autorizzato: riautorizzalo in Assistenti.")
        if (!forceRefresh && creds.expiresAt - nowSec() > REFRESH_MARGIN_SEC) return@withLock creds.accessToken!!
        refreshLocked(creds).accessToken!!
    }

    private suspend fun refreshLocked(creds: ChatGptCredentials): ChatGptCredentials {
        val reply = try {
            http.postForm(ChatGptOAuth.TOKEN, ChatGptOAuth.refreshForm(creds.clientId, creds.refreshToken!!))
        } catch (e: java.io.IOException) {
            throw AiTransportException(AiTransportException.Kind.NETWORK, "Connessione non riuscita: controlla la rete e riprova.")
        }
        if (reply.status == 200) {
            val t = try { ChatGptOAuth.parseTokens(reply.body, nowSec()) } catch (e: ChatGptAuthException) {
                throw AiTransportException(AiTransportException.Kind.BAD_RESPONSE, e.message ?: "Risposta di OpenAI non leggibile.")
            }
            // Access token, expiry, scopes and the rotating refresh token are replaced together.
            val next = creds.copy(accessToken = t.access, refreshToken = t.refresh ?: creds.refreshToken, expiresAt = t.expiresAt,
                scopes = t.scopes.ifEmpty { creds.scopes }, idToken = t.idToken ?: creds.idToken)
            store.save(next)
            return next
        }
        val code = ChatGptOAuth.errorCode(reply.body)
        if (code in ChatGptOAuth.TERMINAL_REFRESH_ERRORS || reply.status == 401) {
            store.save(creds.withoutTokens())
            throw AiTransportException(AiTransportException.Kind.AUTH, "La sessione ChatGPT è scaduta o revocata: accedi di nuovo in Assistenti.", reply.status)
        }
        if (code == "invalid_client") throw AiTransportException(AiTransportException.Kind.AUTH, "OpenAI non riconosce più questa registrazione dell'app: accedi di nuovo.", reply.status)
        throw AiTransportException(
            if (reply.status >= 500) AiTransportException.Kind.SERVER else AiTransportException.Kind.UNKNOWN,
            "Rinnovo dell'accesso a ChatGPT non riuscito (${reply.status}). Riprova tra poco.", reply.status,
        )
    }

    companion object { const val REFRESH_MARGIN_SEC = 300L }
}
