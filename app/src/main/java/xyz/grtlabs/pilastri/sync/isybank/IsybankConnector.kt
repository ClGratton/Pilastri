package xyz.grtlabs.pilastri.sync.isybank

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
 * isybank has no consumer API. The supported route is its own official Excel export (see BANK-INTEGRATIONS.md), reached
 * either through [IsybankWebActivity] (the user signs in on isybank's page inside a WebView and taps the Excel export
 * themselves) or through a file the user picks ([parseFile]). The app never sees, stores or logs credentials, codes or
 * cookie values.
 */
object IsybankConnector {
    /**
     * isybank internet banking sign-in: the "ACCEDI" link of https://www.isybank.com/it.html and of the app and internet
     * banking FAQ (https://www.isybank.com/it/domande-frequenti/app-e-internet-banking.html), both read 2026-10-28. It is
     * the site's own relative link `/isyweblogin/webapp/authorize`; it was not opened or signed into during development.
     */
    const val LOGIN_URL = "https://www.isybank.com/isyweblogin/webapp/authorize"

    const val EXTRA_TOKEN = "xyz.grtlabs.pilastri.isybank.TOKEN"
    const val EXTRA_ERROR = "xyz.grtlabs.pilastri.isybank.ERROR"
    const val RESULT_FAILED = Activity.RESULT_FIRST_USER
    const val RESULT_SESSION_EXPIRED = Activity.RESULT_FIRST_USER + 1

    private val BANK_DOMAINS = listOf("isybank.com", "intesasanpaolo.com")

    /** Hosts visited by the sign-in page in this process, so that [disconnect] can also expire their cookies. */
    internal val visitedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun webIntent(ctx: Context): Intent = Intent(ctx, IsybankWebActivity::class.java)

    fun parseResult(resultCode: Int, data: Intent?): ConnectorResult =
        resultFor(resultCode, data?.getStringExtra(EXTRA_TOKEN), data?.getStringExtra(EXTRA_ERROR))

    /** The file-picker path: Imported with the preview batch, or Failed with an Italian message. Never throws. */
    fun parseFile(bytes: ByteArray, fileName: String): ConnectorResult = try {
        ConnectorResult.Imported(IsybankParser.parse(bytes, fileName, Instant.now()))
    } catch (e: IllegalArgumentException) {
        ConnectorResult.Failed(e.message ?: "Il file non è un'esportazione dei movimenti isybank.")
    }

    /**
     * Forgets the isybank sign-in: expires only cookies of isybank/intesasanpaolo hosts (other connectors keep their
     * sessions) and flushes. Cookies can only be listed per URL, so this covers the host and path variants known to the
     * sign-in flow; a cookie on a path or host this process never saw may survive until it expires on its own.
     */
    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    fun disconnect(ctx: Context) {
        val cookies = CookieManager.getInstance()
        val hosts = (visitedHosts + listOf("www.isybank.com", "isybank.com", "www.intesasanpaolo.com", "intesasanpaolo.com")).filter(::isBankHost)
        val paths = listOf("/", "/isyweblogin", "/isyweblogin/webapp")
        for (host in hosts) {
            val root = BANK_DOMAINS.firstOrNull { host == it || host.endsWith(".$it") } ?: continue
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
    }

    /** True for isybank and Intesa Sanpaolo hosts: where cookies are sent and downloads are accepted from. */
    fun isBankHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return BANK_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    internal fun resultFor(resultCode: Int, token: String?, error: String?): ConnectorResult = when (resultCode) {
        Activity.RESULT_OK -> {
            val batch: ImportBatch? = ImportHandoff.take(token)
            if (batch != null) ConnectorResult.Imported(batch)
            else ConnectorResult.Failed("I movimenti letti non sono più disponibili. Riprova l'importazione.")
        }
        RESULT_FAILED -> ConnectorResult.Failed(error?.takeIf { it.isNotBlank() } ?: "Importazione da isybank non riuscita.")
        RESULT_SESSION_EXPIRED -> ConnectorResult.SessionExpired
        else -> ConnectorResult.Cancelled
    }
}
