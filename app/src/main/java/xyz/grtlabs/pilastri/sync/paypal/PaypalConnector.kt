package xyz.grtlabs.pilastri.sync.paypal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.sync.ConnectorResult
import xyz.grtlabs.pilastri.sync.ImportHandoff

/**
 * PayPal.
 *
 * API route: none that a personal account can use from a phone. PayPal's reporting API (Transaction Search) is called with a
 * REST app's client id and client secret (OAuth client credentials) and, per PayPal's developer pages, going live needs a PayPal
 * Business account; the secret cannot be embedded in an app and there is no backend, so no API path exists here (research of
 * 2026-10-31: developer.paypal.com/api/rest and the Transaction Search guides).
 *
 * Supported routes, both ending in [PaypalParser]:
 *  1. [parseFile]: the user downloads the Activity report as CSV from paypal.com (Cronologia > download icon > Personalizzato, or
 *     paypal.com/reports/accountStatements) and picks it with the system file picker. Verified with synthetic files only.
 *  2. [webIntent]: PayPal's own page in a WebView ([PaypalWebActivity]); the user signs in and creates/downloads the report there and
 *     the activity captures the download. NOT verified against the live site ([WEB_CAPTURE_VERIFIED]).
 *
 * The app never sees or stores the PayPal password or 2FA code. Cookie values are never read, logged or stored.
 */
object PaypalConnector {
    /**
     * Statement/report page of the signed-in account: "the download icon links to https://www.paypal.com/myaccount/activities/statements",
     * from PayPal's Italian help article "Come posso scaricare gli estratti del mio conto PayPal" (faq1007), read 2026-10-31. A signed-out
     * visit is expected to be sent to PayPal's sign-in first; this was not opened or signed into during development.
     */
    const val ENTRY_URL = "https://www.paypal.com/myaccount/activities/statements"

    const val EXTRA_TOKEN = "xyz.grtlabs.pilastri.paypal.TOKEN"
    const val EXTRA_ERROR = "xyz.grtlabs.pilastri.paypal.ERROR"
    const val RESULT_FAILED = Activity.RESULT_FIRST_USER
    const val RESULT_SESSION_EXPIRED = Activity.RESULT_FIRST_USER + 1

    /** False until the WebView capture has been exercised against paypal.com by a person; the UI should offer the file picker as the proven route. */
    const val WEB_CAPTURE_VERIFIED = false

    private val PAYPAL_DOMAINS = listOf("paypal.com")

    /**
     * Cookie NAMES that PayPal sets for a signed-in browser. UNVERIFIED: chosen from memory of PayPal's web traffic and never checked on a
     * signed-in session, so [hasSession] is a hint, not proof. Only names are compared; values are never read.
     */
    private val SESSION_COOKIE_NAMES = setOf("nsid", "x-pp-s", "x-pp-silover")

    /** Hosts visited by the PayPal page in this process, so that [disconnect] can also expire their cookies. */
    internal val visitedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** PayPal's own site in a WebView: the user signs in there and the activity export is captured. Result through [parseResult]. */
    fun webIntent(ctx: Context): Intent = Intent(ctx, PaypalWebActivity::class.java)

    fun parseResult(resultCode: Int, data: Intent?): ConnectorResult =
        resultFor(resultCode, data?.getStringExtra(EXTRA_TOKEN), data?.getStringExtra(EXTRA_ERROR))

    /** An activity export the user downloaded from PayPal and picked with the system file picker. Never throws. */
    fun parseFile(bytes: ByteArray, fileName: String): ConnectorResult = try {
        ConnectorResult.Imported(PaypalParser.parse(bytes, fileName, Instant.now()))
    } catch (e: IllegalArgumentException) {
        ConnectorResult.Failed(e.message ?: "Il file non è il report Attività di PayPal.")
    }

    /** Only whether a session cookie name is present; never its value. */
    fun hasSession(ctx: Context): Boolean = try {
        CookieManager.getInstance().getCookie("https://www.paypal.com/")
            ?.split(';')?.map { it.substringBefore('=').trim().lowercase(Locale.ROOT) }?.any { it in SESSION_COOKIE_NAMES } == true
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Forgets the PayPal sign-in: expires only cookies of paypal.com hosts (other connectors keep their sessions) and flushes. Cookies can
     * only be listed per URL, so this covers the host and path variants known to the flow; a cookie on a path or host this process never saw
     * may survive until it expires on its own.
     */
    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    fun disconnect(ctx: Context) {
        try {
            val cookies = CookieManager.getInstance()
            val hosts = (visitedHosts + listOf("www.paypal.com", "paypal.com")).filter(::isPaypalHost)
            val paths = listOf("/", "/signin", "/myaccount", "/myaccount/activities", "/reports", "/authflow", "/webapps")
            for (host in hosts) {
                val root = PAYPAL_DOMAINS.firstOrNull { host == it || host.endsWith(".$it") } ?: continue
                for (path in paths) {
                    val url = "https://$host$path"
                    val names = cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() } ?: continue
                    for (name in names) for (domain in listOf(null, host, ".$root")) {
                        cookies.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure" + (domain?.let { "; Domain=$it" } ?: ""))
                    }
                }
            }
            for (host in hosts) WebStorage.getInstance().deleteOrigin("https://$host")
            cookies.flush()
        } catch (e: RuntimeException) {
            // No WebView on this device: there is no session to forget.
        }
    }

    /** The connector now imports from a picked file (verified with synthetic exports) and from the WebView capture (see [WEB_CAPTURE_VERIFIED]). */
    val available: Boolean get() = true

    /** True for paypal.com and its subdomains: where cookies are sent, downloads are accepted from and pages may be navigated to. */
    fun isPaypalHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return PAYPAL_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    internal fun resultFor(resultCode: Int, token: String?, error: String?): ConnectorResult = when (resultCode) {
        Activity.RESULT_OK -> {
            val batch: ImportBatch? = ImportHandoff.take(token)
            if (batch != null) ConnectorResult.Imported(batch)
            else ConnectorResult.Failed("I movimenti letti non sono più disponibili. Riprova l'importazione.")
        }
        RESULT_FAILED -> ConnectorResult.Failed(error?.takeIf { it.isNotBlank() } ?: "Importazione da PayPal non riuscita.")
        RESULT_SESSION_EXPIRED -> ConnectorResult.SessionExpired
        else -> ConnectorResult.Cancelled
    }
}
