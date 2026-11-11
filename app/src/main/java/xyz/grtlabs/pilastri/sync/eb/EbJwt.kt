package xyz.grtlabs.pilastri.sync.eb

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.json.JSONObject

/**
 * Signs bytes with RSASSA-PKCS1-v1_5 and SHA-256 (JWT "RS256"). The only thing the Enable Banking client needs from a private key,
 * so a key held elsewhere (a server, a different keystore) can replace [JcaRsaSigner] without touching the client.
 */
interface RsaSigner {
    fun sign(data: ByteArray): ByteArray
}

/** [RsaSigner] over any JCA private key: an Android Keystore key (never leaves the secure hardware) or a software key in tests. */
class JcaRsaSigner(private val key: PrivateKey) : RsaSigner {
    override fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(data)
        return signature.sign()
    }
}

/** Where the API client gets its `Authorization: Bearer` token from. */
fun interface EbTokens {
    fun bearer(): String
}

/**
 * The application JWT Enable Banking expects (docs reference, "JWT format and signature", read 2026-10-31):
 * header `{"typ":"JWT","alg":"RS256","kid":<application id>}`, claims `iss` = `enablebanking.com`, `aud` = `api.enablebanking.com`,
 * `iat` and `exp`; a lifetime longer than 86400 s is rejected. The token is reused until [renewBefore] from its end.
 *
 * `iat` is set [skew] in the past, so a phone clock a little ahead of the server's does not produce a token from the future.
 * The total lifetime (exp − iat) stays within [lifetime].
 */
class EbJwt(
    private val applicationId: String,
    private val signer: RsaSigner,
    private val clock: () -> Instant = Instant::now,
    private val lifetime: Duration = Duration.ofHours(1),
    private val renewBefore: Duration = Duration.ofMinutes(5),
    private val skew: Duration = Duration.ofSeconds(30),
) : EbTokens {
    init {
        require(UUID.matches(applicationId)) { "L'identificativo dell'applicazione Enable Banking non è valido." }
        require(!lifetime.isNegative && !lifetime.isZero && lifetime <= MAX_LIFETIME) { "Durata del token non valida." }
        require(skew < lifetime && renewBefore < lifetime.minus(skew))
    }

    private var cached: String? = null
    private var cachedExp: Instant = Instant.MIN

    @Synchronized
    override fun bearer(): String {
        val now = clock()
        cached?.let { if (now.plus(renewBefore).isBefore(cachedExp)) return it }
        val iat = now.minus(skew)
        val exp = iat.plus(lifetime)
        val token = build(iat, exp)
        cached = token
        cachedExp = exp
        return token
    }

    private fun build(iat: Instant, exp: Instant): String {
        val header = """{"typ":"JWT","alg":"RS256","kid":${JSONObject.quote(applicationId)}}"""
        val claims = """{"iss":"$ISSUER","aud":"$AUDIENCE","iat":${iat.epochSecond},"exp":${exp.epochSecond}}"""
        val input = b64(header.toByteArray(StandardCharsets.UTF_8)) + "." + b64(claims.toByteArray(StandardCharsets.UTF_8))
        return input + "." + b64(signer.sign(input.toByteArray(StandardCharsets.US_ASCII)))
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        const val ISSUER = "enablebanking.com"
        const val AUDIENCE = "api.enablebanking.com"
        val MAX_LIFETIME: Duration = Duration.ofSeconds(86_400)
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
