package xyz.grtlabs.pilastri.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Sign in with ChatGPT: PKCE, authorize URL, callback, token handling, ID token and refresh. Synthetic values only. */
class ChatGptPlanTest {
    private class TestCipher : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }
            return iv + c.doFinal(plain)
        }
        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)); updateAAD(aad) }
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private fun store(settings: KeyValueStore = MemoryStore()) = ChatGptStore(AiVault(MemoryStore(), TestCipher()), settings)

    private fun query(url: String): Map<String, String> = ChatGptOAuth.parseQuery(URI(url).rawQuery)

    @Test fun pkceMatchesTheRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val a = ChatGptOAuth.newAttempt(1455, null)
        val b = ChatGptOAuth.newAttempt(1455, null)
        assertTrue(a.state != b.state && a.nonce != b.nonce && a.verifier != b.verifier)
        assertTrue(a.verifier.length in 43..128)
    }

    @Test fun firstRegistrationAndReauthorizationFollowTheDocumentedParameters() {
        val host = "urn:uuid:00000000-0000-4000-8000-000000000000"
        val first = ChatGptOAuth.newAttempt(1455, null)
        assertTrue(first.newRegistration)
        assertEquals("http://127.0.0.1:1455/auth/callback", first.redirectUri)
        val url = ChatGptOAuth.authorizeUrl(first, host, idTokenHint = "ignored", loginHint = "ignored@example.com")
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        val q = query(url)
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("Pilastri", q["agent_name_hint"])
        assertEquals(host, q["ext_agent_host_id"])
        assertEquals("code", q["response_type"])
        assertEquals(first.redirectUri, q["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"])
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(ChatGptOAuth.challenge(first.verifier), q["code_challenge"])
        assertEquals(first.state, q["state"]); assertEquals(first.nonce, q["nonce"])
        assertFalse("hints only on reauthorization", q.containsKey("id_token_hint") || q.containsKey("login_hint"))
        assertFalse(q.containsKey("prompt"))
        assertFalse("never a client secret", url.contains("secret"))

        val again = ChatGptOAuth.newAttempt(54321, "oaiapp_SYNTHETIC")
        assertFalse(again.newRegistration)
        val q2 = query(ChatGptOAuth.authorizeUrl(again, host, idTokenHint = "id.token.hint", loginHint = "user@example.com", forceConsent = true))
        assertEquals("oaiapp_SYNTHETIC", q2["client_id"])
        assertFalse("agent_name_hint only on first registration", q2.containsKey("agent_name_hint"))
        assertEquals("id.token.hint", q2["id_token_hint"])
        assertEquals("user@example.com", q2["login_hint"])
        assertEquals("consent", q2["prompt"])
        assertEquals("http://127.0.0.1:54321/auth/callback", q2["redirect_uri"])
        // dynamic_agent_client is never kept as the issued id.
        assertTrue(ChatGptOAuth.newAttempt(1455, "dynamic_agent_client").newRegistration)
    }

    @Test fun theCallbackIsValidatedBeforeAnythingIsUsed() {
        val first = ChatGptOAuth.newAttempt(1455, null)
        fun cb(q: String) = ChatGptOAuth.parseCallback(q, first)
        assertTrue(cb("code=abc&state=WRONG&client_id=oaiapp_x") is ChatGptOAuth.Callback.Invalid)
        assertEquals(ChatGptOAuth.Callback.Denied("access_denied"), cb("error=access_denied&state=${first.state}"))
        assertTrue("an error with a wrong state is not trusted", cb("error=access_denied&state=nope") is ChatGptOAuth.Callback.Invalid)
        assertTrue("registration without an issued id is incomplete", cb("code=abc&state=${first.state}") is ChatGptOAuth.Callback.Invalid)
        assertTrue(cb("code=abc&state=${first.state}&client_id=dynamic_agent_client") is ChatGptOAuth.Callback.Invalid)
        assertEquals(ChatGptOAuth.Callback.Code("abc", "oaiapp_new"),
            cb("code=abc&scope=chatgpt.tokens.use.direct+email+offline_access+openid+profile+resource.invoke&state=${first.state}&client_id=oaiapp_new"))

        val again = ChatGptOAuth.newAttempt(1455, "oaiapp_saved")
        assertEquals(ChatGptOAuth.Callback.Code("xyz", "oaiapp_saved"), ChatGptOAuth.parseCallback("code=xyz&state=${again.state}", again))
        assertTrue("another registration is rejected, not swapped in",
            ChatGptOAuth.parseCallback("code=xyz&state=${again.state}&client_id=oaiapp_other", again) is ChatGptOAuth.Callback.Invalid)
    }

    @Test fun tokenRefreshAndRevocationFormsCarryNoSecretAndKeepTheGrant() {
        val a = ChatGptOAuth.newAttempt(1455, null)
        assertEquals(
            mapOf("grant_type" to "authorization_code", "client_id" to "oaiapp_x", "code" to "c", "code_verifier" to a.verifier,
                "redirect_uri" to a.redirectUri, "resource" to "https://api.openai.com/v1"),
            ChatGptOAuth.tokenForm("c", "oaiapp_x", a),
        )
        val refresh = ChatGptOAuth.refreshForm("oaiapp_x", "rt")
        assertEquals("refresh_token", refresh["grant_type"])
        assertFalse("omit scope to retain the grant", refresh.containsKey("scope"))
        assertEquals("https://api.openai.com/v1", refresh["resource"])
        assertEquals(mapOf("token" to "rt", "token_type_hint" to "refresh_token", "client_id" to "oaiapp_x"), ChatGptOAuth.revokeForm("oaiapp_x", "rt"))
        val t = ChatGptOAuth.parseTokens("""{"access_token":"at","refresh_token":"rt","id_token":"it","token_type":"Bearer","expires_in":3600,"scope":"openid email chatgpt.tokens.use.direct"}""", 1_000)
        assertEquals(4_600, t.expiresAt)
        assertTrue(ChatGptOAuth.PLAN_SCOPE in t.scopes)
        assertThrows(ChatGptAuthException::class.java) { ChatGptOAuth.parseTokens("""{"token_type":"Bearer"}""", 0) }
        assertEquals("invalid_grant", ChatGptOAuth.errorCode("""{"error":"invalid_grant"}"""))
    }

    // ---- ID token ----

    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private val jwks = (rsa.public as RSAPublicKey).let { k ->
        JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "k1").put("n", b64(unsigned(k.modulus))).put("e", b64(unsigned(k.publicExponent)))))
    }

    private fun idToken(claims: JSONObject, kid: String = "k1", alg: String = "RS256"): String {
        val head = b64(JSONObject().put("alg", alg).put("kid", kid).toString().toByteArray())
        val body = b64(claims.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(rsa.private); update("$head.$body".toByteArray()); sign() }
        return "$head.$body.${b64(sig)}"
    }

    private fun claims(aud: Any = "oaiapp_x", nonce: String = "n1", exp: Long = 2_000, iss: String = "https://auth.openai.com") =
        JSONObject().put("iss", iss).put("aud", aud).put("sub", "user-sub").put("email", "user@example.com").put("exp", exp).put("nonce", nonce)

    @Test fun theIdTokenIsCheckedForSignatureIssuerAudienceExpiryAndNonce() {
        val ok = ChatGptIdToken.verify(idToken(claims()), jwks, "oaiapp_x", "n1", 1_000)
        assertEquals(ChatGptIdToken.Identity("user-sub", "user@example.com"), ok)
        assertEquals("user-sub", ChatGptIdToken.verify(idToken(claims(aud = JSONArray().put("other").put("oaiapp_x"))), jwks, "oaiapp_x", "n1", 1_000).subject)
        fun bad(token: String, nonce: String? = "n1") = assertThrows(ChatGptAuthException::class.java) { ChatGptIdToken.verify(token, jwks, "oaiapp_x", nonce, 1_000) }
        bad(idToken(claims(aud = "oaiapp_other")))
        bad(idToken(claims(nonce = "n2")))
        bad(idToken(claims(iss = "https://evil.example")))
        bad(idToken(claims(exp = 100)))
        bad(idToken(claims(), kid = "unknown"))
        bad(idToken(claims(), alg = "none"))
        val good = idToken(claims())
        val parts = good.split('.')
        bad(parts[0] + "." + b64(claims(aud = "oaiapp_x").put("sub", "attacker").toString().toByteArray()) + "." + parts[2])
        bad("not.a.jwt.at.all")
    }

    // ---- credentials, store and refresh ----

    private class FakeAuth(vararg replies: Any) : ChatGptAuthHttp {
        private val queue = ArrayDeque(replies.toList())
        val forms = ArrayList<Pair<String, Map<String, String>>>()
        override suspend fun postForm(url: String, fields: Map<String, String>): AiHttpReply {
            forms += url to fields
            delay(10)
            val next = queue.removeFirstOrNull() ?: error("no reply left")
            if (next is Exception) throw next
            return next as AiHttpReply
        }
        override suspend fun get(url: String, bearer: String?): AiHttpReply = error("not used")
    }

    private fun creds(expiresAt: Long, scopes: Set<String> = setOf("openid", ChatGptOAuth.PLAN_SCOPE)) =
        ChatGptCredentials("oaiapp_x", "user-sub", "user@example.com", "id", "at-old", "rt-old", expiresAt, scopes)

    @Test fun credentialsRoundTripEncryptedAndKeepTheRegistrationWithoutTokens() {
        val settings = MemoryStore()
        val vaultStore = MemoryStore()
        val s = ChatGptStore(AiVault(vaultStore, TestCipher()), settings)
        assertNull(s.load())
        val c = creds(5_000)
        s.save(c)
        assertEquals(c, s.load())
        assertFalse("tokens never in clear", vaultStore.get("vault.secret.chatgpt.credentials")!!.contains("rt-old"))
        val cleared = c.withoutTokens()
        assertFalse(cleared.hasTokens)
        assertEquals("oaiapp_x", cleared.clientId)
        assertEquals("id", cleared.idToken)
        val host = s.hostId()
        assertTrue(Regex("urn:uuid:[0-9a-f-]{36}").matches(host))
        assertEquals("stable per installation", host, s.hostId())
        assertEquals(ChatGptStore.DEFAULT_MODEL, s.model)
        s.model = "bad model!"
        assertEquals(ChatGptStore.DEFAULT_MODEL, s.model)
        s.model = "gpt-6.1-sol"
        assertEquals("gpt-6.1-sol", s.model)
    }

    @Test fun aFreshTokenIsUsedAsIsAndANearlyExpiredOneIsRefreshedOnceAndRotated() = runBlocking {
        val s = store()
        s.save(creds(expiresAt = 10_000))
        val idle = FakeAuth()
        assertEquals("at-old", ChatGptSession(s, idle) { 1_000 }.accessToken())
        assertTrue(idle.forms.isEmpty())

        s.save(creds(expiresAt = 1_100)) // 100 s left: inside the refresh margin
        val auth = FakeAuth(AiHttpReply(200, """{"access_token":"at-new","refresh_token":"rt-new","expires_in":3600,"scope":"openid chatgpt.tokens.use.direct"}"""))
        val session = ChatGptSession(s, auth) { 1_000 }
        // Two concurrent callers: one refresh only (the refresh token rotates).
        val tokens = listOf(async { session.accessToken() }, async { session.accessToken() }).awaitAll()
        assertEquals(listOf("at-new", "at-new"), tokens)
        assertEquals(1, auth.forms.size)
        assertEquals(ChatGptOAuth.TOKEN, auth.forms.single().first)
        assertEquals(ChatGptOAuth.refreshForm("oaiapp_x", "rt-old"), auth.forms.single().second)
        val saved = s.load()!!
        assertEquals("rt-new", saved.refreshToken)
        assertEquals(4_600, saved.expiresAt)
    }

    @Test fun aTerminalRefreshErrorClearsTheTokensButANetworkErrorKeepsThem() = runBlocking {
        val s = store()
        s.save(creds(expiresAt = 1_000))
        val offline = ChatGptSession(s, FakeAuth(IOException("offline"))) { 1_000 }
        val net = assertThrows(AiTransportException::class.java) { runBlocking { offline.accessToken() } }
        assertEquals(AiTransportException.Kind.NETWORK, net.kind)
        assertTrue(s.load()!!.hasTokens)

        val revoked = ChatGptSession(s, FakeAuth(AiHttpReply(400, """{"error":"invalid_grant"}"""))) { 1_000 }
        val auth = assertThrows(AiTransportException::class.java) { runBlocking { revoked.accessToken() } }
        assertEquals(AiTransportException.Kind.AUTH, auth.kind)
        val after = s.load()!!
        assertFalse(after.hasTokens)
        assertEquals("the registration stays for the next sign-in", "oaiapp_x", after.clientId)

        val notLinked = assertThrows(AiTransportException::class.java) { runBlocking { ChatGptSession(s, FakeAuth()) { 1_000 }.accessToken() } }
        assertTrue(notLinked.message!!.contains("accedi"))
    }

    @Test fun withoutThePlanScopeNoInferenceIsAttempted() = runBlocking {
        val s = store()
        s.save(creds(expiresAt = 10_000, scopes = setOf("openid", "email")))
        val e = assertThrows(AiTransportException::class.java) { runBlocking { ChatGptSession(s, FakeAuth()) { 1_000 }.accessToken() } }
        assertTrue(e.message!!.contains("piano"))
    }

    // ---- loopback callback ----

    @Test fun onlyTheCallbackRequestIsAccepted() {
        assertEquals("code=a&state=b", LoopbackCallback.query("GET /auth/callback?code=a&state=b HTTP/1.1"))
        assertEquals("", LoopbackCallback.query("GET /auth/callback HTTP/1.1"))
        assertNull(LoopbackCallback.query("GET /favicon.ico HTTP/1.1"))
        assertNull(LoopbackCallback.query("GET /callback?code=a HTTP/1.1"))
        assertNull(LoopbackCallback.query("POST /auth/callback?code=a HTTP/1.1"))
        assertNull(LoopbackCallback.query(""))
        val page = LoopbackCallback.response(ok = true)
        assertTrue(page.startsWith("HTTP/1.1 200 OK\r\n") && page.contains("Torna a Pilastri"))
    }

    @Test fun theChatGptRouteIsOfficialSignInWithItsOwnConsent() {
        assertEquals(AiRoute.Kind.PLAN, AiRoute.CHATGPT_ACCOUNT.kind)
        assertNull("no chatgpt.com WebView route any more", AiRoute.CHATGPT_ACCOUNT.web)
        assertNotNull(AiRoute.CLAUDE_ACCOUNT.web)
        val store = MemoryStore()
        // A consent given to the retired chatgpt.com page does not carry over to the plan route.
        store.put("optin.route.CHATGPT_ACCOUNT", "1")
        val settings = AiSettings(store)
        assertFalse(settings.optedIn(AiRoute.CHATGPT_ACCOUNT))
        settings.setOptIn(AiRoute.CHATGPT_ACCOUNT, true)
        assertTrue(settings.optedIn(AiRoute.CHATGPT_ACCOUNT))
        assertTrue(AiRoute.CHATGPT_ACCOUNT.consentText().contains("piano"))
    }

    @Test fun claudeAccountIsTheDefaultRouteOnceLinkedUnlessTheUserChoseAnother() {
        val legacyApi = MemoryStore().apply { put("provider", "OPENAI") }
        assertEquals(AiRoute.OPENAI_API, AiSettings(legacyApi).route)
        AiSettings(legacyApi).markLinked(WebAccount.CLAUDE, "2026-10-31T10:00:00Z")
        assertEquals(AiRoute.CLAUDE_ACCOUNT, AiSettings(legacyApi).route)
        // A ChatGPT choice saved for the retired page is not an explicit choice of the new route.
        val oldWeb = MemoryStore().apply { put("route", "CHATGPT_ACCOUNT"); put("web.linked.CLAUDE", "2026-10-31T10:00:00Z") }
        assertEquals(AiRoute.CLAUDE_ACCOUNT, AiSettings(oldWeb).route)
        // An explicit choice made now always wins.
        val explicit = AiSettings(oldWeb)
        explicit.route = AiRoute.CHATGPT_ACCOUNT
        assertEquals(AiRoute.CHATGPT_ACCOUNT, AiSettings(oldWeb).route)
        explicit.route = AiRoute.CLAUDE_API
        assertEquals(AiRoute.CLAUDE_API, AiSettings(oldWeb).route)
    }
}
