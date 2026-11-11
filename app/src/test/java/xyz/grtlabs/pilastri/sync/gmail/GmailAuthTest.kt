package xyz.grtlabs.pilastri.sync.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Play services calls need a device; what can be proven here is how Google's failures map to what the user sees. */
class GmailAuthTest {
    private fun notConfigured(o: AuthOutcome) = (o as AuthOutcome.NotConfigured).message
    private fun failed(o: AuthOutcome) = (o as AuthOutcome.Failed).message

    @Test fun developerErrorMeansNoAndroidOAuthClientMatchesPackageAndKey() {
        for (outcome in listOf(
            GmailAuth.mapFailure(10, "10: "),
            GmailAuth.mapFailure(10, null),
            GmailAuth.mapFailure(null, "10: [28444] Developer console is not set up correctly."),
            GmailAuth.mapFailure(null, "DEVELOPER_ERROR"),
        )) {
            val m = notConfigured(outcome)
            assertTrue(m, m.contains("xyz.grtlabs.pilastri"))
            assertTrue(m, m.contains("SHA-1") && m.contains("Gmail API") && m.contains("utenti di prova") && m.contains("gmail.readonly"))
            assertTrue(m, m.contains("Android") && m.contains("7 giorni"))
        }
    }

    @Test fun otherGoogleFailuresAreNotTreatedAsMissingSetup() {
        assertTrue(failed(GmailAuth.mapFailure(7, "NETWORK_ERROR")).contains("rete"))
        assertTrue(failed(GmailAuth.mapFailure(16, null)).contains("annullata"))
        assertTrue(failed(GmailAuth.mapFailure(12501, null)).contains("annullata"))
        assertTrue(failed(GmailAuth.mapFailure(2, null)).contains("Google Play"))
        assertTrue(failed(GmailAuth.mapFailure(8, "INTERNAL")).contains("(codice 8)"))
        assertFalse(failed(GmailAuth.mapFailure(null, "boom")).contains("codice"))
    }

    @Test fun aGrantedScopeListIsOnlyRefusedWhenItOmitsGmailReadonly() {
        assertTrue(GmailAuth.scopeGranted(emptyList()))
        assertTrue(GmailAuth.scopeGranted(listOf(GmailSetup.SCOPE)))
        assertTrue(GmailAuth.scopeGranted(listOf("https://www.googleapis.com/auth/userinfo.email", GmailSetup.SCOPE)))
        assertFalse(GmailAuth.scopeGranted(listOf("https://www.googleapis.com/auth/drive.file")))
    }

    @Test fun theSetupTextNamesTheScopeAndThePackage() {
        assertEquals("xyz.grtlabs.pilastri", GmailSetup.PACKAGE)
        assertEquals("https://www.googleapis.com/auth/gmail.readonly", GmailSetup.SCOPE)
        assertTrue(GmailSetup.API_DISABLED_IT.contains("Gmail API"))
    }

    @Test fun theConnectorReportsItselfAvailable() {
        assertTrue(GmailConnector.available)
    }
}
