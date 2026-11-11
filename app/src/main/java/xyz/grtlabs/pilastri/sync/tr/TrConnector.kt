package xyz.grtlabs.pilastri.sync.tr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import java.net.URI
import xyz.grtlabs.pilastri.sync.ConnectorResult
import xyz.grtlabs.pilastri.sync.ImportHandoff

/**
 * Public face of the read-only Trade Republic connector.
 *
 * Typical use from MainActivity: launch [syncIntent] with an ActivityResult contract and pass the result to [parseResult].
 * [syncIntent] handles a missing or expired session itself (it shows Trade Republic's own login page and continues), so
 * [hasSession] is only a hint for labels such as "connected"; it is a guess from cookie names and can be wrong either way.
 *
 * The app never reads, stores or logs the phone number, PIN, 2FA code, cookies or tokens. The user types them into Trade
 * Republic's own page; the session lives in the WebView's cookie jar. A result carries only an [ImportHandoff] token.
 */
object TrConnector {
    /** Opens only the visible login; the result is [ConnectorResult.LoggedIn] (or Cancelled). */
    fun loginIntent(ctx: Context): Intent =
        Intent(ctx, TrLoginActivity::class.java).putExtra(TrLoginActivity.EXTRA_MODE, TrLoginActivity.MODE_LOGIN)

    /** Reads the account behind a progress screen; the result is [ConnectorResult.Imported], SessionExpired, Failed or Cancelled. */
    fun syncIntent(ctx: Context, sinceDays: Int = 400): Intent =
        Intent(ctx, TrLoginActivity::class.java)
            .putExtra(TrLoginActivity.EXTRA_MODE, TrLoginActivity.MODE_SYNC)
            .putExtra(TrLoginActivity.EXTRA_SINCE_DAYS, sinceDays.coerceIn(1, 3660))

    fun parseResult(resultCode: Int, data: Intent?): ConnectorResult = TrResult.decode(
        ok = resultCode == Activity.RESULT_OK,
        kind = data?.getStringExtra(TrResult.KIND),
        token = data?.getStringExtra(TrResult.TOKEN),
        message = data?.getStringExtra(TrResult.MESSAGE),
    )

    /** True when a cookie that looks like a Trade Republic session is present. Names only; no value is read into a variable. */
    @Suppress("UNUSED_PARAMETER")
    fun hasSession(ctx: Context): Boolean = TrUrls.hasSessionCookie(cookieNames())

    /**
     * Forgets the Trade Republic session on this phone by expiring only the traderepublic.com cookies (not the WebView's whole
     * jar), then flushes. This is local: it does not log the device out on Trade Republic's side.
     */
    @Suppress("UNUSED_PARAMETER")
    fun disconnect(ctx: Context) {
        runCatching {
            val cookies = CookieManager.getInstance()
            for (url in COOKIE_URLS) {
                val paths = ancestorPaths(URI(url).path)
                for (name in cookieNames(url)) {
                    for (path in paths) {
                        cookies.setCookie(url, "$name=; Max-Age=0; Expires=$EPOCH; Path=$path")
                        cookies.setCookie(url, "$name=; Max-Age=0; Expires=$EPOCH; Path=$path; Domain=traderepublic.com")
                    }
                }
            }
            cookies.flush()
        }
    }

    private const val EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT"

    // Cookies are scoped by host and path, so a few deeper URLs reveal the cookies that the API paths would receive.
    private val COOKIE_URLS = listOf(
        "https://app.traderepublic.com/",
        "https://api.traderepublic.com/",
        TrUrls.SESSION_URL,
        TrUrls.ACCOUNT_URL,
    )

    /** Names of the cookies the WebView would send to [url] (traderepublic.com hosts only). */
    internal fun cookieNames(urls: List<String> = COOKIE_URLS): List<String> =
        urls.flatMap { cookieNames(it) }.distinct()

    private fun cookieNames(url: String): List<String> {
        val header = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: return emptyList()
        return header.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
    }

    /** "/a/b" -> ["/", "/a", "/a/b"]; a cookie set with any of these paths would be sent to that URL. */
    internal fun ancestorPaths(path: String?): List<String> {
        val parts = (path ?: "").split('/').filter { it.isNotEmpty() }
        return listOf("/") + parts.indices.map { i -> "/" + parts.take(i + 1).joinToString("/") }
    }
}

/** Result transport between [TrLoginActivity] and [TrConnector.parseResult]. Pure, so that it can be tested without Android. */
internal object TrResult {
    const val KIND = "xyz.grtlabs.pilastri.sync.tr.KIND"
    const val TOKEN = "xyz.grtlabs.pilastri.sync.tr.TOKEN"
    const val MESSAGE = "xyz.grtlabs.pilastri.sync.tr.MESSAGE"

    const val LOGGED_IN = "logged_in"
    const val IMPORTED = "imported"
    const val EXPIRED = "expired"
    const val FAILED = "failed"

    fun decode(ok: Boolean, kind: String?, token: String?, message: String?): ConnectorResult = when {
        !ok -> ConnectorResult.Cancelled
        kind == LOGGED_IN -> ConnectorResult.LoggedIn
        kind == IMPORTED -> ImportHandoff.take(token)?.let { ConnectorResult.Imported(it) }
            ?: ConnectorResult.Failed("Il risultato della sincronizzazione non è più disponibile: ripeti la sincronizzazione.")
        kind == EXPIRED -> ConnectorResult.SessionExpired
        kind == FAILED -> ConnectorResult.Failed(message?.take(300)?.ifBlank { null } ?: "Sincronizzazione con Trade Republic non riuscita.")
        else -> ConnectorResult.Failed("Risposta dell'accesso a Trade Republic non riconosciuta.")
    }
}
