package xyz.grtlabs.pilastri.sync.auto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import xyz.grtlabs.pilastri.data.ImportProvider

/** A small string store, so the vault and the sync bookkeeping are tested without Android. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class PrefsSecretStore(private val prefs: SharedPreferences) : SecretStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    // commit(): a saved sign-in or a "forget" must be on disk before the caller reports it.
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun remove(key: String) { prefs.edit().remove(key).commit() }
}

class MemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value }
    @Synchronized override fun remove(key: String) { map.remove(key) }
}

/** Authenticated encryption with associated data. */
interface CredentialCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key of its own (not the AI vault's key). The blob is `iv(12) || ciphertext+tag`.
 * No user-authentication requirement: the sync runs when the app opens, without a biometric prompt. A background sync while the
 * phone is locked would still work; `setUnlockedDeviceRequired(true)` would forbid that if it is ever wanted.
 */
class KeystoreCredentialCipher(private val alias: String = "pilastri.sync.login.v1") : CredentialCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object { const val IV_BYTES = 12 }
}

/**
 * A provider sign-in the user chose to keep. Deliberately not a data class: no generated toString, copy or component functions
 * that could print the password. Never log it, never put it in an Intent, the archive, a backup or AI context.
 */
class SavedLogin(val username: String, val password: String) {
    override fun toString(): String = "SavedLogin(***)"
}

/**
 * Provider usernames and passwords, kept only while "Ricorda l'accesso" is on (see [isRemembered]). Encrypted with a Keystore key; the
 * provider is bound to each ciphertext as associated data, so one provider's blob cannot be read as another's. The preferences
 * file is app-private and the manifest disables Android backup; nothing here is ever logged.
 *
 * [markRejected] records that the provider refused the saved values: automatic sign-in then stays off until the user saves new
 * ones, so the app never retries a wrong password into an account lock.
 */
class CredentialVault(private val store: SecretStore, private val cipher: CredentialCipher, private val now: () -> Instant = Instant::now) {
    private fun slot(p: ImportProvider) = "login.${p.name}"
    private fun rememberKey(p: ImportProvider) = "remember.${p.name}"
    private fun rejectedKey(p: ImportProvider) = "rejected.${p.name}"
    private fun aad(p: ImportProvider) = "pilastri.sync.login.v1.${p.name}".toByteArray(StandardCharsets.UTF_8)

    /**
     * The "Ricorda l'accesso" switch. On by default (user, 2026-10-31: an optional saved sign-in makes no sense, since without it
     * there is no automatic reading once the session lapses), as Trade Republic's PIN already was (Council 3, user answer 3: the
     * toggle there is "Non salvare il PIN"). Except isybank: its web sign-in signed the person's isybank app out (Fold,
     * 2026-10-31), so the app must never sign in there by itself until that is resolved.
     */
    fun isRemembered(p: ImportProvider): Boolean = when (store.get(rememberKey(p))) {
        "1" -> true
        "0" -> false
        else -> savesByDefault(p)
    }

    /** Turning it off deletes the saved sign-in at once (and, where saving is the default, keeps the "off" choice). */
    fun setRemember(p: ImportProvider, on: Boolean) {
        if (on) store.put(rememberKey(p), "1")
        else {
            if (savesByDefault(p)) store.put(rememberKey(p), "0") else store.remove(rememberKey(p))
            store.remove(slot(p)); store.remove(rejectedKey(p))
        }
    }

    /** Trade Republic's "Non salvare il PIN": on means no saved PIN (and the saved one is deleted now). */
    fun isTrPinDeclined(): Boolean = !isRemembered(ImportProvider.TRADE_REPUBLIC)
    fun setTrPinDeclined(declined: Boolean) = setRemember(ImportProvider.TRADE_REPUBLIC, !declined)

    /**
     * Saves Trade Republic's sign-in (phone number and 4-digit PIN), encrypted like any other, unless "Non salvare il PIN" is on:
     * then nothing is stored and the result is false. [IllegalArgumentException] for a PIN that is not 4 digits. TR's own
     * approval (2FA in the TR app) is never stored.
     */
    fun saveTrPin(phone: String, pin: String): Boolean {
        require(pin.length == 4 && pin.all { it in '0'..'9' }) { "Il PIN di Trade Republic ha 4 cifre." }
        if (isTrPinDeclined()) return false
        save(ImportProvider.TRADE_REPUBLIC, phone, pin)
        return true
    }

    private fun savesByDefault(p: ImportProvider) = p != ImportProvider.ISYBANK

    fun has(p: ImportProvider): Boolean = store.get(slot(p)) != null

    /**
     * Saves (or replaces) the sign-in. Only allowed while "Ricorda l'accesso" is on ([IllegalStateException] otherwise);
     * [IllegalArgumentException] with an Italian message for values that cannot be a username or password.
     */
    fun save(p: ImportProvider, username: String, password: String) {
        check(isRemembered(p)) { "Ricorda l'accesso è spento." }
        val user = username.trim()
        require(user.length in 1..MAX_CHARS && user.none(Char::isISOControl)) { "Nome utente non valido." }
        require(password.length in 1..MAX_CHARS && password.none(Char::isISOControl)) { "Password non valida." }
        val u = user.toByteArray(StandardCharsets.UTF_8)
        val pw = password.toByteArray(StandardCharsets.UTF_8)
        val plain = ByteArray(3 + u.size + pw.size)
        plain[0] = FORMAT
        plain[1] = (u.size ushr 8).toByte()
        plain[2] = u.size.toByte()
        u.copyInto(plain, 3)
        pw.copyInto(plain, 3 + u.size)
        try {
            store.put(slot(p), Base64.getEncoder().encodeToString(cipher.encrypt(plain, aad(p))))
            store.remove(rejectedKey(p))
        } finally {
            plain.fill(0); u.fill(0); pw.fill(0)
        }
    }

    /** The saved sign-in, or null when absent or no longer decryptable (another key, another provider, a Keystore reset). */
    fun load(p: ImportProvider): SavedLogin? {
        val encoded = store.get(slot(p)) ?: return null
        var plain: ByteArray? = null
        return try {
            plain = cipher.decrypt(Base64.getDecoder().decode(encoded), aad(p))
            if (plain.size < 4 || plain[0] != FORMAT) return null
            val ulen = ((plain[1].toInt() and 0xff) shl 8) or (plain[2].toInt() and 0xff)
            if (ulen == 0 || 3 + ulen >= plain.size) return null
            SavedLogin(String(plain, 3, ulen, StandardCharsets.UTF_8), String(plain, 3 + ulen, plain.size - 3 - ulen, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            plain?.fill(0)
        }
    }

    /** The sign-in to fill automatically: saved, decryptable and not refused by the provider. */
    fun loadForAutoSignIn(p: ImportProvider): SavedLogin? = if (isRejected(p)) null else load(p)

    fun markRejected(p: ImportProvider) = store.put(rejectedKey(p), now().toString())
    fun isRejected(p: ImportProvider): Boolean = store.get(rejectedKey(p)) != null

    /** Disconnect: the saved sign-in, the switch (back to its default) and the refusal marker. */
    fun forget(p: ImportProvider) { store.remove(rememberKey(p)); store.remove(slot(p)); store.remove(rejectedKey(p)) }

    companion object {
        private const val FORMAT: Byte = 1
        private const val MAX_CHARS = 256

        /** The real vault, or null when the device Keystore is unusable (then "Ricorda l'accesso" is not offered). */
        fun createOrNull(context: Context): CredentialVault? = try {
            KeystoreCredentialCipher().encrypt(ByteArray(1), ByteArray(0))
            CredentialVault(PrefsSecretStore(prefs(context)), KeystoreCredentialCipher())
        } catch (_: Exception) {
            null
        }

        internal fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("pilastri_connector_logins", Context.MODE_PRIVATE)
    }
}
