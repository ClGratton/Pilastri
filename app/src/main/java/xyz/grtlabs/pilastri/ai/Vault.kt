package xyz.grtlabs.pilastri.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider(val label: String, val defaultModel: String, val keyHint: String) {
    /** Default from the claude-api skill (cached 2026-10-06): Claude Opus 5.5 unless the user picks another model. */
    ANTHROPIC("Anthropic", "claude-opus-5-5", "sk-ant-…"),

    /**
     * Default read from OpenAI's migration guide and model page (2026-10-28): `gpt-5.6-terra` is the model its
     * Chat Completions function-calling example uses. GPT-6 models need the Responses API for tool calling.
     */
    OPENAI("OpenAI", "gpt-5.6-terra", "sk-…");

    /** What the user accepts when enabling this provider. Shown next to the opt-in switch. */
    fun consentText(): String =
        "Autorizzo l'invio a $label dei dati dell'archivio che l'assistente legge per rispondere (movimenti, valutazioni, " +
            "conti, movimenti importati, piano, indici) e delle mie domande. Questi dati lasciano il dispositivo e sono " +
            "soggetti alle condizioni di $label. Posso revocare il consenso in qualsiasi momento."

    /**
     * The separate consent for the Gmail fallback ([AiForConnectors]): mail text is not covered by [consentText]. The limit
     * is `GmailAiFallback.MAX_TEXT_CHARS` (2 500); keep the two in step.
     */
    fun mailConsentText(): String =
        "Rate da email sconosciute: invia a $label l'oggetto e un estratto (max 2.500 caratteri, dati sensibili mascherati) " +
            "delle email di mittenti che l'app non riconosce. Con la data del messaggio; mai l'indirizzo del mittente né altre email. " +
            "Questi dati lasciano il dispositivo e sono soggetti alle condizioni di $label."
}

/** Minimal string store so the vault and settings can be tested without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable Android Keystore key. The blob is `iv(12) || ciphertext+tag`. */
class KeystoreCipher(private val alias: String = "pilastri.ai.vault.v1") : SecretCipher {
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
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plain)
        return cipher.iv + encrypted
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
 * API keys for the assistant, encrypted with a Keystore key and kept in app-private preferences. A key is never
 * part of the archive, a backup or a log (the manifest also disables Android backup). The provider name is bound
 * to each ciphertext as associated data so one provider's blob cannot be read as another's.
 */
class AiVault(private val store: KeyValueStore, private val cipher: SecretCipher) {
    private fun slot(provider: AiProvider) = "vault.${provider.name}"
    private fun aad(provider: AiProvider) = "pilastri.ai.${provider.name}".toByteArray(StandardCharsets.UTF_8)

    fun has(provider: AiProvider): Boolean = store.get(slot(provider)) != null

    /** The stored key, or null when absent or no longer decryptable (for example after a Keystore reset). */
    fun get(provider: AiProvider): String? {
        val encoded = store.get(slot(provider)) ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(encoded), aad(provider)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Throws [IllegalArgumentException] (Italian message) when the text cannot be an API key. */
    fun set(provider: AiProvider, apiKey: String) {
        val clean = apiKey.trim()
        require(clean.matches(KEY_SHAPE)) { "La chiave non sembra valida: usa solo caratteri ASCII visibili, senza spazi (8-300 caratteri)." }
        val blob = cipher.encrypt(clean.toByteArray(StandardCharsets.UTF_8), aad(provider))
        store.put(slot(provider), Base64.getEncoder().encodeToString(blob))
    }

    fun clear(provider: AiProvider) = store.remove(slot(provider))

    // ---- named secrets (the routine token). Same cipher; the name is bound as associated data. ----

    private fun secretSlot(name: String) = "vault.secret.$name"
    private fun secretAad(name: String) = "pilastri.ai.secret.$name".toByteArray(StandardCharsets.UTF_8)

    fun hasSecret(name: String): Boolean = store.get(secretSlot(name)) != null

    /** The stored secret, or null when absent or no longer decryptable. */
    fun getSecret(name: String): String? {
        val encoded = store.get(secretSlot(name)) ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(encoded), secretAad(name)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun setSecret(name: String, value: String) {
        require(name.matches(Regex("[a-z0-9_.-]{1,40}")) && value.isNotEmpty() && value.length <= 4000) { "Valore non valido." }
        store.put(secretSlot(name), Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(StandardCharsets.UTF_8), secretAad(name))))
    }

    fun clearSecret(name: String) = store.remove(secretSlot(name))

    companion object {
        private val KEY_SHAPE = Regex("[\\u0021-\\u007E]{8,300}")

        /** The real vault, or null when the device Keystore is unusable (the page then disables the assistant). */
        fun createOrNull(context: Context): AiVault? = try {
            AiVault(SharedPrefsStore(prefs(context)), KeystoreCipher())
        } catch (_: Exception) {
            null
        }

        internal fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("pilastri_ai", Context.MODE_PRIVATE)
    }
}

/**
 * Non-secret assistant settings: chosen provider, editable model ids, the per-provider opt-in and the redaction
 * toggle (on by default). Kept in app-private preferences, never in the archive.
 */
class AiSettings(private val store: KeyValueStore) {
    var provider: AiProvider
        get() = store.get("provider")?.let { name -> AiProvider.entries.firstOrNull { it.name == name } } ?: AiProvider.ANTHROPIC
        set(value) = store.put("provider", value.name)

    fun model(provider: AiProvider): String = store.get("model.${provider.name}")?.takeIf { MODEL_SHAPE.matches(it) } ?: provider.defaultModel

    /** Returns false (and keeps the old value) when [model] is not a plausible model id. */
    fun setModel(provider: AiProvider, model: String): Boolean {
        val clean = model.trim()
        if (!MODEL_SHAPE.matches(clean)) return false
        store.put("model.${provider.name}", clean)
        return true
    }

    fun optedIn(provider: AiProvider): Boolean = store.get("optin.${provider.name}") == "1"
    fun setOptIn(provider: AiProvider, value: Boolean) {
        if (value) store.put("optin.${provider.name}", "1") else store.remove("optin.${provider.name}")
    }

    /**
     * The per-provider opt-in for sending unknown senders' mail to the AI (the Gmail fallback). Separate from [optedIn]:
     * the chat consent never mentions mail. Off unless the user turns it on.
     */
    fun mailOptedIn(provider: AiProvider): Boolean = store.get("optin.mail.${provider.name}") == "1"
    fun setMailOptIn(provider: AiProvider, value: Boolean) {
        if (value) store.put("optin.mail.${provider.name}", "1") else store.remove("optin.mail.${provider.name}")
    }

    var redactionOn: Boolean
        get() = store.get("redaction") != "0"
        set(value) = store.put("redaction", if (value) "1" else "0")

    /**
     * "Oscura dati personali negli allegati": OFF by default (user decision, 2026-11-01). Off: the assistant gets the attachment
     * itself where the route takes files, plus the text read on the device, unmasked. On: only text with codice fiscale, IBAN,
     * matricola, names, email and phone replaced by placeholders leaves the phone; no image or PDF is sent.
     */
    var attachmentRedaction: Boolean
        get() = store.get("attach.redact") == "1"
        set(value) = store.put("attach.redact", if (value) "1" else "0")

    /**
     * The chosen route (provider choice). The user's explicit choice ("Usa") always wins. Without one, the Claude account is
     * the default whenever it is linked; an install from before account linking that chose an API provider keeps it only
     * while Claude is not linked; a new install starts on the Claude account.
     */
    var route: AiRoute
        get() = store.get("route")
            // A ChatGPT choice saved before 2026-10-31 meant the retired chatgpt.com page, not Sign in with ChatGPT: not explicit.
            ?.takeIf { it != AiRoute.CHATGPT_ACCOUNT.name || store.get("route.v") == "2" }
            ?.let { name -> AiRoute.entries.firstOrNull { it.name == name } }
            ?: when {
                linkedAt(WebAccount.CLAUDE) != null -> AiRoute.CLAUDE_ACCOUNT
                store.get("provider") != null -> if (provider == AiProvider.OPENAI) AiRoute.OPENAI_API else AiRoute.CLAUDE_API
                else -> AiRoute.CLAUDE_ACCOUNT
            }
        set(value) {
            store.put("route", value.name)
            store.put("route.v", "2")
            value.api?.let { provider = it }
        }

    /**
     * Consent per route; the API routes share the per-provider opt-in. The ChatGPT plan route has its own key: a consent
     * given to the retired chatgpt.com page (same route name, different text) does not carry over.
     */
    fun optedIn(route: AiRoute): Boolean = route.api?.let { optedIn(it) } ?: (store.get(consentKey(route)) == "1")
    fun setOptIn(route: AiRoute, value: Boolean) {
        val api = route.api
        if (api != null) setOptIn(api, value)
        else if (value) store.put(consentKey(route), "1") else store.remove(consentKey(route))
    }

    private fun consentKey(route: AiRoute) = if (route.kind == AiRoute.Kind.PLAN) "optin.plan.${route.name}" else "optin.route.${route.name}"

    /** When the app last saw a completed sign-in on [account] (ISO instant), or null. */
    fun linkedAt(account: WebAccount): String? = store.get("web.linked.${account.name}")
    /** When the provider page last sent the user back to its sign-in page, or null. */
    fun rejectedAt(account: WebAccount): String? = store.get("web.rejected.${account.name}")
    fun markLinked(account: WebAccount, at: String) { store.put("web.linked.${account.name}", at); store.remove("web.rejected.${account.name}") }
    fun markRejected(account: WebAccount, at: String) = store.put("web.rejected.${account.name}", at)
    fun clearLink(account: WebAccount) { store.remove("web.linked.${account.name}"); store.remove("web.rejected.${account.name}") }

    /** The routine's reply folder (a persisted document-tree permission) and its display name; not secret. */
    var routineFolder: String?
        get() = store.get("routine.folder")
        set(value) { if (value == null) store.remove("routine.folder") else store.put("routine.folder", value) }
    var routineFolderName: String?
        get() = store.get("routine.folderName")
        set(value) { if (value == null) store.remove("routine.folderName") else store.put("routine.folderName", value) }
    /** Last time the routine refused the token (401/403/404): shown as "token non valido" until it is replaced. */
    var routineRejectedAt: String?
        get() = store.get("routine.rejected")
        set(value) { if (value == null) store.remove("routine.rejected") else store.put("routine.rejected", value) }

    companion object {
        private val MODEL_SHAPE = Regex("[A-Za-z0-9._:/-]{1,100}")
        fun create(context: Context): AiSettings = AiSettings(SharedPrefsStore(AiVault.prefs(context)))
    }
}
