package xyz.grtlabs.pilastri.sync.eb

import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Software RSA keys only: the Android Keystore key class is verified on a device. */
class EbJwtTest {
    private val t0 = Instant.parse("2026-10-31T12:00:00Z")
    private var now = t0
    private var signs = 0
    private val signer = object : RsaSigner {
        private val real = JcaRsaSigner(EbFx.software.private)
        override fun sign(data: ByteArray): ByteArray { signs++; return real.sign(data) }
    }
    private fun jwt(lifetime: Duration = Duration.ofHours(1)) = EbJwt(EbFx.APP_ID, signer, { now }, lifetime)

    private fun part(token: String, i: Int) = JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[i])))

    @Test fun headerAndClaimsFollowTheDocumentedFormat() {
        val token = jwt().bearer()
        val header = part(token, 0)
        val claims = part(token, 1)
        assertEquals("JWT", header.getString("typ"))
        assertEquals("RS256", header.getString("alg"))
        assertEquals(EbFx.APP_ID, header.getString("kid"))
        assertEquals("enablebanking.com", claims.getString("iss"))
        assertEquals("api.enablebanking.com", claims.getString("aud"))
        assertTrue(claims.getLong("iat") <= t0.epochSecond)
        assertEquals(Duration.ofHours(1).seconds, claims.getLong("exp") - claims.getLong("iat"))
        assertTrue(claims.getLong("exp") > t0.epochSecond)
    }

    @Test fun signatureVerifiesWithThePublicKeyAndIsUrlSafeWithoutPadding() {
        val token = jwt().bearer()
        assertFalse(token.contains('=') || token.contains('+') || token.contains('/'))
        val (h, p, s) = token.split('.')
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(EbFx.software.public); update("$h.$p".toByteArray()) }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(s)))
    }

    @Test fun theTokenIsReusedUntilNearExpiryThenRenewed() {
        val j = jwt()
        val first = j.bearer()
        now = t0.plus(Duration.ofMinutes(40))
        assertEquals(first, j.bearer())
        assertEquals(1, signs)
        now = t0.plus(Duration.ofMinutes(56))
        val second = j.bearer()
        assertNotEquals(first, second)
        assertEquals(2, signs)
    }

    @Test fun lifetimeAboveTwentyFourHoursAndBadApplicationIdsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { jwt(Duration.ofHours(25)) }
        assertThrows(IllegalArgumentException::class.java) { EbJwt("not-a-uuid", signer, { now }) }
        assertEquals(86_400L, part(jwt(Duration.ofSeconds(86_400)).bearer(), 1).let { it.getLong("exp") - it.getLong("iat") })
    }

    @Test fun pemOfACertificateAndOfItsPublicKeyRoundTrips() {
        val der = Pem.decode(EbFx.CERT_PEM, "CERTIFICATE")
        assertEquals(EbFx.CERT_PEM, Pem.encode("CERTIFICATE", der))
        assertTrue(Pem.encode("CERTIFICATE", der).lines().filter { it.isNotEmpty() && !it.startsWith("-----") }.all { it.length <= 64 })
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        cert.verify(cert.publicKey) // self-signed: verifies with its own key
        val keyPem = Pem.encode("PUBLIC KEY", cert.publicKey.encoded)
        val back = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Pem.decode(keyPem, "PUBLIC KEY")))
        assertEquals(cert.publicKey, back)
        assertThrows(IllegalArgumentException::class.java) { Pem.decode(keyPem, "CERTIFICATE") }
    }
}
