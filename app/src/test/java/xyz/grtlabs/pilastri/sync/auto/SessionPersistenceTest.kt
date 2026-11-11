package xyz.grtlabs.pilastri.sync.auto

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A provider sign-in must stay: nothing in the sync code may clear the shared WebView session except an explicit disconnect. */
class SessionPersistenceTest {
    private val root = listOf(File("src/main/java/xyz/grtlabs/pilastri/sync"), File("app/src/main/java/xyz/grtlabs/pilastri/sync")).first { it.isDirectory }

    @Test fun onlyTheExplicitDisconnectsTouchCookiesOrStorage() {
        val allowed = setOf("PaypalConnector.kt", "IsybankConnector.kt", "TrConnector.kt")
        val clearing = Regex("removeAllCookie|removeSessionCookie|clearCache|deleteAllData|deleteOrigin|setCookie[(][^)]*Max-Age=0|setDataDirectorySuffix|clearFormData")
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }
            .filter { clearing.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals("session cleared outside a disconnect: $offenders", emptyList<String>(), offenders)
    }

    @Test fun theDisconnectsAreOnlyCalledFromAutoSyncKitDisconnect() {
        val callers = root.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("(Paypal|Isybank|Tr)Connector[.]disconnect[(]").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(listOf("AutoSyncKit.kt"), callers)
    }

    @Test fun cookiesAreFlushedAfterEverySignedInPageAndRun() {
        val flush = { name: String -> root.walkTopDown().first { it.name == name }.readText().contains("CookieManager.getInstance().flush()") }
        assertTrue(flush("WebLoginDriver.kt") && flush("AutoSyncKit.kt") && flush("PaypalWebActivity.kt"))
    }

    @Test fun theStaySignedInBoxIsTickedButTheVerificationIsNot() {
        val rules = LoginRulesTable.PAYPAL
        val json = JSONObject(rules.toJson())
        val trust = (0 until json.getJSONArray("trust").length()).map { json.getJSONArray("trust").getString(it) }
        assertTrue("remember this device" in trust && "resta connesso" in trust)
        val veto = (0 until json.getJSONArray("trustExclude").length()).map { json.getJSONArray("trustExclude").getString(it) }
        assertTrue("newsletter" in veto && "card" in veto)
        val script = LoginAutofill.script(rules)
        assertTrue(script.contains("keepTrust()"))
        assertFalse(trust.any { it.contains("codice") || it.contains("code") })
    }
}
