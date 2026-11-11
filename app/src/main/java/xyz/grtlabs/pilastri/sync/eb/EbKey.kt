package xyz.grtlabs.pilastri.sync.eb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal

/** PEM (RFC 7468) armor, for the public material that is shown or copied to the Enable Banking control panel. */
object Pem {
    fun encode(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" + Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n") + "\n-----END $label-----\n"

    /** The DER bytes of the single [label] block in [pem], or [IllegalArgumentException]. */
    fun decode(pem: String, label: String): ByteArray {
        val match = Regex("-----BEGIN $label-----([A-Za-z0-9+/=\\s]+)-----END $label-----").find(pem)
            ?: throw IllegalArgumentException("Blocco PEM $label non trovato.")
        return Base64.getMimeDecoder().decode(match.groupValues[1])
    }
}

/**
 * The application's own RSA key pair. The private half is created inside the Android Keystore and cannot be exported; only
 * the public half leaves it, as a PEM certificate or public key to paste or upload at registration.
 */
interface EbAppKey {
    fun exists(): Boolean
    /** Generates the key pair when absent. Idempotent: an existing key is kept, because the registration is tied to it. */
    fun create()
    fun signer(): RsaSigner
    /** Self-signed X.509 certificate of the key, PEM `CERTIFICATE`. */
    fun certificatePem(): String
    /** SubjectPublicKeyInfo of the key, PEM `PUBLIC KEY`. */
    fun publicKeyPem(): String
    fun delete()
}

/**
 * Non-exportable RSA-2048 key in the Android Keystore (signing with SHA-256 and PKCS#1 v1.5 only). The Keystore itself attaches a
 * self-signed certificate to the key pair, signed with that key; its validity is set here to 10 years.
 *
 * Not unit-testable (no Keystore on the JVM): [EbJwt] and [Pem] are tested with software keys, and this class is verified on a
 * device. Whether Enable Banking's control panel accepts this certificate, or only the bare public key, is verified at the first
 * real registration.
 */
class AndroidEbAppKey(private val alias: String = DEFAULT_ALIAS) : EbAppKey {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun exists(): Boolean = keyStore().containsAlias(alias)

    override fun create() {
        if (exists()) return
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=Pilastri personal application"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 10 * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    override fun signer(): RsaSigner = JcaRsaSigner(privateKey())

    override fun certificatePem(): String = Pem.encode("CERTIFICATE", certificate().encoded)

    override fun publicKeyPem(): String = Pem.encode("PUBLIC KEY", certificate().publicKey.encoded)

    override fun delete() {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    /** Where the private key lives: "strongbox", "tee", "software" or "unknown". For the first-run record only. */
    fun protection(): String {
        val key = privateKey()
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            else -> "unknown"
        }
    }

    private fun privateKey(): PrivateKey =
        keyStore().getKey(alias, null) as? PrivateKey ?: throw IllegalStateException("La chiave dell'applicazione non esiste.")

    private fun certificate(): Certificate =
        keyStore().getCertificate(alias) ?: throw IllegalStateException("La chiave dell'applicazione non esiste.")

    companion object {
        const val DEFAULT_ALIAS = "pilastri.eb.app.v1"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
