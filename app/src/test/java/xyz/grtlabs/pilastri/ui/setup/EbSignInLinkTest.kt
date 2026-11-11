package xyz.grtlabs.pilastri.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EbSignInLinkTest {
    @Test fun onlyAnHttpsEnableBankingLinkIsTaken() {
        val ok = "https://enablebanking.com/auth/magic?token=synthetic"
        assertEquals(ok, EbSignInLink.fromClipboard("  $ok \n"))
        assertEquals("https://sso.enablebanking.com/x", EbSignInLink.fromClipboard("https://sso.enablebanking.com/x"))
        assertNull(EbSignInLink.fromClipboard("http://enablebanking.com/x"))
        assertNull(EbSignInLink.fromClipboard("https://enablebanking.com.evil.example/x"))
        assertNull(EbSignInLink.fromClipboard("https://evilenablebanking.com/x"))
        assertNull(EbSignInLink.fromClipboard("https://user@enablebanking.com/x"))
        assertNull(EbSignInLink.fromClipboard("apri https://enablebanking.com/x"))
        assertNull(EbSignInLink.fromClipboard("javascript:alert(1)"))
        assertNull(EbSignInLink.fromClipboard(null))
        assertNull(EbSignInLink.fromClipboard(""))
    }
}
