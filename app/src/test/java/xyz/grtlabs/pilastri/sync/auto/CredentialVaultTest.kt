package xyz.grtlabs.pilastri.sync.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportProvider

class CredentialVaultTest {
    private val store = MemorySecretStore()
    private val vault = CredentialVault(store, SoftwareCipher())
    private val P = ImportProvider.PAYPAL
    private val OFF = ImportProvider.ISYBANK

    @Test fun roundTripKeepsUnicodeAndIsNotStoredInClear() {
        vault.setRemember(P, true)
        vault.save(P, "  ${T.USER}  ", "pàss wörd €1")
        val back = vault.load(P)!!
        assertEquals(T.USER, back.username)
        assertEquals("pàss wörd €1", back.password)
        val raw = store.get("login.PAYPAL")!!
        assertFalse(raw.contains("pàss") || raw.contains("utente"))
        assertEquals("SavedLogin(***)", back.toString())
    }

    @Test fun anotherKeyCannotReadTheBlob() {
        vault.setRemember(P, true)
        vault.save(P, T.USER, T.PASSWORD)
        val other = CredentialVault(store, SoftwareCipher(ByteArray(32) { 7 }))
        assertTrue(other.has(P))
        assertNull(other.load(P))
    }

    @Test fun oneProvidersBlobCannotBeReadAsAnothers() {
        vault.setRemember(P, true)
        vault.save(P, T.USER, T.PASSWORD)
        store.put("login.ISYBANK", store.get("login.PAYPAL")!!)
        assertNull(vault.load(ImportProvider.ISYBANK))
        assertNotNull(vault.load(P))
    }

    @Test fun savingNeedsTheSwitchAndTurningItOffForgets() {
        assertThrows(IllegalStateException::class.java) { vault.save(OFF, T.USER, T.PASSWORD) }
        vault.setRemember(OFF, true)
        vault.save(OFF, T.USER, T.PASSWORD)
        vault.setRemember(OFF, false)
        assertFalse(vault.has(OFF))
        assertNull(vault.load(OFF))
        assertFalse(vault.isRemembered(OFF))
    }

    @Test fun invalidValuesAreRefused() {
        vault.setRemember(P, true)
        assertThrows(IllegalArgumentException::class.java) { vault.save(P, " ", T.PASSWORD) }
        assertThrows(IllegalArgumentException::class.java) { vault.save(P, T.USER, "") }
        assertThrows(IllegalArgumentException::class.java) { vault.save(P, T.USER, "a\nb") }
        assertThrows(IllegalArgumentException::class.java) { vault.save(P, "x".repeat(257), T.PASSWORD) }
    }

    @Test fun aRefusedLoginIsNotFilledAgainUntilReplaced() {
        vault.setRemember(P, true)
        vault.save(P, T.USER, T.PASSWORD)
        vault.markRejected(P)
        assertTrue(vault.isRejected(P))
        assertNull(vault.loadForAutoSignIn(P))
        assertNotNull(vault.load(P))
        vault.save(P, T.USER, "Nuova-sintetica-456")
        assertFalse(vault.isRejected(P))
        assertEquals("Nuova-sintetica-456", vault.loadForAutoSignIn(P)!!.password)
    }

    @Test fun forgetClearsEverything() {
        vault.setRemember(P, true)
        vault.save(P, T.USER, T.PASSWORD)
        vault.markRejected(P)
        vault.forget(P)
        assertFalse(vault.has(P) || vault.isRejected(P))
        assertTrue(vault.isRemembered(P)) // back to the default
    }

    @Test fun corruptBlobIsNull() {
        vault.setRemember(P, true)
        store.put("login.PAYPAL", "not-base64!!")
        assertNull(vault.load(P))
    }

    /** Council 3, user answer 3: the TR PIN is saved by default; "Non salvare il PIN" deletes it and stops saving. Synthetic values. */
    @Test fun trPinIsSavedByDefaultUnlessDeclined() {
        val tr = ImportProvider.TRADE_REPUBLIC
        assertFalse(vault.isTrPinDeclined())
        assertTrue(vault.isRemembered(tr))
        assertTrue(vault.saveTrPin("+390000000000", "1234"))
        assertEquals("1234", vault.load(tr)!!.password)
        assertFalse(store.get("login.TRADE_REPUBLIC")!!.contains("1234"))

        vault.setTrPinDeclined(true)
        assertTrue(vault.isTrPinDeclined())
        assertNull(vault.load(tr))
        assertFalse(vault.saveTrPin("+390000000000", "1234"))
        assertFalse(vault.has(tr))

        vault.setTrPinDeclined(false)
        assertTrue(vault.saveTrPin("+390000000000", "5678"))
        assertThrows(IllegalArgumentException::class.java) { vault.saveTrPin("+390000000000", "12a4") }
        assertThrows(IllegalArgumentException::class.java) { vault.saveTrPin("+390000000000", "12345") }

        // Disconnect forgets the PIN and the choice: a new setup saves by default again.
        vault.setTrPinDeclined(true)
        vault.forget(tr)
        assertFalse(vault.isTrPinDeclined())
        assertFalse(vault.has(tr))
    }

    /** User, 2026-10-31: the sign-in is saved by default; turning it off forgets it and keeps the "off" choice. Synthetic values. */
    @Test fun paypalSavesByDefaultAndOffIsKept() {
        assertTrue(vault.isRemembered(P))
        vault.save(P, T.USER, T.PASSWORD)
        assertTrue(vault.has(P))
        vault.setRemember(P, false)
        assertFalse(vault.isRemembered(P))
        assertEquals("0", store.get("remember.PAYPAL"))
        assertFalse(vault.has(P))
    }

    /** isybank's web sign-in signed the isybank app out (Fold, 2026-10-31): never saved unless turned on. */
    @Test fun isybankStaysOffByDefaultAndOffLeavesNoMarker() {
        assertFalse(vault.isRemembered(OFF))
        vault.setRemember(OFF, true)
        vault.save(OFF, T.USER, T.PASSWORD)
        vault.setRemember(OFF, false)
        assertFalse(vault.isRemembered(OFF))
        assertNull(store.get("remember.ISYBANK"))
        assertFalse(vault.has(OFF))
    }
}
