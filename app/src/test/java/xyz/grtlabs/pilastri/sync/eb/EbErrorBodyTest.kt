package xyz.grtlabs.pilastri.sync.eb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EbErrorBodyTest {
    @Test fun maskBodyHidesMailUuidAndLongRuns() {
        val raw = """{"error":{"message":"bad nome@esempio.it 123e4567-e89b-12d3-a456-426614174000 ${"A".repeat(60)}"}}"""
        val m = EbLog.maskBody(raw)
        assertTrue(m.contains("<mail>") && m.contains("<uuid>") && m.contains("<long>"))
        assertFalse(m.contains("@esempio") || m.contains("AAAAAAAAAA"))
    }

    @Test fun maskBodyIsBounded() {
        assertTrue(EbLog.maskBody("x y ".repeat(1000)).length <= 600)
    }

    @Test fun scriptsReadTheNestedErrorMessageAndNeverReturnTheToken() {
        assertTrue(EbPanelScripts.REGISTER.contains("e.message||e.detail"))
        assertTrue(EbPanelScripts.REGISTER.contains("body:tx"))
    }
}
