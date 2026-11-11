package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class VaultTest {
    /** Same framing as the Keystore cipher (iv || ciphertext+tag, provider bound as AAD) with an in-memory AES key. */
    private class SoftwareCipher : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, 12, blob.size - 12)
        }
    }

    private val secret = "sk-test-SYNTHETIC-0123456789"

    @Test fun keysRoundTripPerProviderAndAreNotStoredInClear() {
        val store = MemoryStore()
        val vault = AiVault(store, SoftwareCipher())
        assertFalse(vault.has(AiProvider.ANTHROPIC))
        assertNull(vault.get(AiProvider.ANTHROPIC))
        vault.set(AiProvider.ANTHROPIC, "  $secret ")
        vault.set(AiProvider.OPENAI, "sk-other-SYNTHETIC-987654321")
        assertEquals(secret, vault.get(AiProvider.ANTHROPIC))
        assertEquals("sk-other-SYNTHETIC-987654321", vault.get(AiProvider.OPENAI))
        val stored = AiProvider.entries.mapNotNull { store.get("vault.${it.name}") }
        assertEquals(2, stored.size)
        stored.forEach { assertFalse(it.contains("SYNTHETIC")) }
        vault.clear(AiProvider.ANTHROPIC)
        assertFalse(vault.has(AiProvider.ANTHROPIC))
        assertNull(vault.get(AiProvider.ANTHROPIC))
        assertTrue(vault.has(AiProvider.OPENAI))
    }

    @Test fun anotherProvidersBlobCannotBeReadAndUndecryptableBlobsReadAsAbsent() {
        val store = MemoryStore()
        val vault = AiVault(store, SoftwareCipher())
        vault.set(AiProvider.ANTHROPIC, secret)
        store.put("vault.OPENAI", store.get("vault.ANTHROPIC")!!)
        assertNull("The provider is bound to the ciphertext", vault.get(AiProvider.OPENAI))
        store.put("vault.OPENAI", "not-base64-!!")
        assertNull(vault.get(AiProvider.OPENAI))
        // A different device key (for example after a Keystore reset) yields no key rather than a crash.
        assertNull(AiVault(store, SoftwareCipher()).get(AiProvider.ANTHROPIC))
    }

    @Test fun implausibleKeysAreRefusedInItalian() {
        val vault = AiVault(MemoryStore(), SoftwareCipher())
        listOf("", "short", "has a space inside", "line\nbreak-1234567", "chiave-con-à-12345678", "x".repeat(301)).forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { vault.set(AiProvider.OPENAI, bad) }
            assertTrue(e.message!!.contains("chiave"))
        }
        assertFalse(vault.has(AiProvider.OPENAI))
    }

    @Test fun settingsDefaultToSafeValuesAndKeepModelsEditable() {
        val settings = AiSettings(MemoryStore())
        assertEquals(AiProvider.ANTHROPIC, settings.provider)
        assertTrue("Redaction is on by default", settings.redactionOn)
        AiProvider.entries.forEach {
            assertFalse("Consent is never preset", settings.optedIn(it))
            assertEquals(it.defaultModel, settings.model(it))
        }
        assertEquals("claude-opus-5-5", AiProvider.ANTHROPIC.defaultModel)
        assertEquals("gpt-5.6-terra", AiProvider.OPENAI.defaultModel)

        assertTrue(settings.setModel(AiProvider.ANTHROPIC, " claude-opus-5-5 "))
        assertEquals("claude-opus-5-5", settings.model(AiProvider.ANTHROPIC))
        assertEquals(AiProvider.OPENAI.defaultModel, settings.model(AiProvider.OPENAI))
        assertFalse(settings.setModel(AiProvider.ANTHROPIC, "bad model!"))
        assertEquals("claude-opus-5-5", settings.model(AiProvider.ANTHROPIC))

        settings.setOptIn(AiProvider.OPENAI, true)
        assertTrue(settings.optedIn(AiProvider.OPENAI))
        assertFalse(settings.optedIn(AiProvider.ANTHROPIC))
        settings.setOptIn(AiProvider.OPENAI, false)
        assertFalse(settings.optedIn(AiProvider.OPENAI))
        settings.redactionOn = false
        assertFalse(settings.redactionOn)
        settings.provider = AiProvider.OPENAI
        assertEquals(AiProvider.OPENAI, settings.provider)
    }

    @Test fun consentNamesTheProviderAndStatesThatDataLeavesTheDevice() {
        AiProvider.entries.forEach {
            val text = it.consentText()
            assertTrue(text.contains(it.label))
            assertTrue(text.contains("lasciano il dispositivo"))
        }
    }

    @Test fun theGmailFallbackNeedsItsOwnMailOptInNotTheChatConsent() {
        val settings = AiSettings(MemoryStore())
        val keys = mutableSetOf(AiProvider.ANTHROPIC, AiProvider.OPENAI)
        // Off by default, for every provider.
        AiProvider.entries.forEach { assertFalse(settings.mailOptedIn(it)) }
        assertNull(AiForConnectors.pick(settings) { it in keys })
        // The chat consent alone does not send mail anywhere.
        settings.setOptIn(AiProvider.ANTHROPIC, true)
        settings.setOptIn(AiProvider.OPENAI, true)
        assertNull(AiForConnectors.pick(settings) { it in keys })
        // The mail opt-in does, for that provider only, and only with a saved key.
        settings.setMailOptIn(AiProvider.OPENAI, true)
        assertEquals(AiProvider.OPENAI, AiForConnectors.pick(settings) { it in keys })
        keys -= AiProvider.OPENAI
        assertNull(AiForConnectors.pick(settings) { it in keys })
        keys += AiProvider.OPENAI
        // The Agente's API route is preferred when it is opted in too.
        settings.setMailOptIn(AiProvider.ANTHROPIC, true)
        settings.route = AiRoute.CLAUDE_API
        assertEquals(AiProvider.ANTHROPIC, AiForConnectors.pick(settings) { it in keys })
        settings.setMailOptIn(AiProvider.ANTHROPIC, false)
        assertEquals(AiProvider.OPENAI, AiForConnectors.pick(settings) { it in keys })
        // Revoking the chat consent does not touch the mail opt-in, and vice versa: they are separate switches.
        settings.setOptIn(AiProvider.OPENAI, false)
        assertTrue(settings.mailOptedIn(AiProvider.OPENAI))
        settings.setMailOptIn(AiProvider.OPENAI, false)
        assertNull(AiForConnectors.pick(settings) { it in keys })
    }

    @Test fun theMailConsentSaysWhatLeavesTheDevice() {
        AiProvider.entries.forEach {
            val text = it.mailConsentText()
            assertTrue(text.startsWith("Rate da email sconosciute: invia a ${it.label} l'oggetto e un estratto (max 2.500 caratteri, dati sensibili mascherati) delle email di mittenti che l'app non riconosce"))
            assertTrue(text.contains("lasciano il dispositivo"))
            assertFalse("the chat consent never mentions mail", it.consentText().contains("email"))
        }
    }

    @Test fun routineSecretsAreEncryptedAndBoundToTheirName() {
        val store = MemoryStore()
        val vault = AiVault(store, SoftwareCipher())
        val config = RoutineConfig("trig_SYNTH12345", "sk-ant-oat01-SYNTHETIC0123456789abcdef")
        RoutineConfig.save(vault, config)
        assertEquals(config, RoutineConfig.load(vault))
        assertFalse(store.get("vault.secret.routine.token")!!.contains("SYNTHETIC"))
        // a blob moved to another name does not decrypt
        store.put("vault.secret.routine.id", store.get("vault.secret.routine.token")!!)
        assertNull(RoutineConfig.load(vault))
        RoutineConfig.clear(vault)
        assertFalse(vault.hasSecret(RoutineConfig.SECRET_TOKEN))
        assertThrows(IllegalArgumentException::class.java) { vault.setSecret("Nome Non Valido", "x") }
    }
}
