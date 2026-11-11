package xyz.grtlabs.pilastri.sync.auto

import android.content.Context
import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import java.nio.charset.StandardCharsets
import java.util.Base64
import xyz.grtlabs.pilastri.data.ImportProvider

/** The one place that says how long a re-written provider session lasts. */
object SessionCookieConfig {
    const val MAX_AGE_SECONDS: Long = 30L * 24 * 60 * 60
}

/** The part of the WebView cookie jar this needs; a fake in tests. */
interface CookieJar {
    /** Each cookie for [url] as a Set-Cookie line with its attributes, or null when the WebView cannot report them. */
    fun cookieInfo(url: String): List<String>?
    /** The `name=value; name=value` header the WebView would send to [url] (all cookies, attributes or not), or null. */
    fun cookieHeader(url: String): String?
    fun setCookie(url: String, cookie: String)
    fun flush()
}

class AndroidCookieJar : CookieJar {
    private val manager get() = CookieManager.getInstance()
    override fun cookieInfo(url: String): List<String>? =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) CookieManagerCompat.getCookieInfo(manager, url) else null
    override fun cookieHeader(url: String): String? = manager.getCookie(url)
    override fun setCookie(url: String, cookie: String) = manager.setCookie(url, cookie)
    override fun flush() = manager.flush()
}

/**
 * The provider's session cookies, encrypted (AES-GCM, Keystore key via [CredentialCipher], provider bound as associated data) in an
 * app-private store. Needs no cookie attributes: it is the fallback when the WebView cannot say which cookies are session ones.
 * One blob per provider: Set-Cookie lines, newline separated. Never in the archive, backups, logs or AI context.
 */
class SessionSnapshots(private val store: SecretStore, private val cipher: CredentialCipher) {
    private fun slot(p: ImportProvider) = "session.${p.name}"
    private fun aad(p: ImportProvider) = "pilastri.sync.session.v1.${p.name}".toByteArray(StandardCharsets.UTF_8)

    fun save(p: ImportProvider, lines: List<String>) {
        val blob = cipher.encrypt(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8), aad(p))
        store.put(slot(p), Base64.getEncoder().encodeToString(blob))
    }

    /** The snapshot, or empty when absent or no longer decryptable (a Keystore reset). */
    fun load(p: ImportProvider): List<String> {
        val encoded = store.get(slot(p)) ?: return emptyList()
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(encoded), aad(p)), StandardCharsets.UTF_8).split('\n').filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun forget(p: ImportProvider) = store.remove(slot(p))

    companion object {
        /** The real store, or null when the device Keystore is unusable. */
        fun createOrNull(context: Context): SessionSnapshots? = try {
            val cipher = KeystoreCredentialCipher()
            cipher.encrypt(ByteArray(1), ByteArray(0))
            SessionSnapshots(PrefsSecretStore(context.applicationContext.getSharedPreferences("pilastri_session_snapshots", Context.MODE_PRIVATE)), cipher)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * A browser restores its session cookies after a restart; the WebView does not. Two paths, both only for sites with
 * [ProviderSite.persistSession]:
 *  1. Attributes: when the cookie info shows an expiry on at least one cookie (so session and persistent cookies can be told apart),
 *     the session cookies are re-written as read plus an explicit Max-Age.
 *  2. Snapshot/restore (needs no attributes): after a good run the cookies are snapshotted, encrypted; at the start of each process
 *     the names missing from the jar now (exactly what the restart dropped) are re-set as session cookies. Present cookies are never touched.
 * Values only move between the cookie store and the encrypted snapshot: never logged.
 */
object SessionCookiePersistence {
    private val EXPIRY = Regex("(?i)^(expires|max-age)[ ]*=")

    fun isSessionCookie(info: String): Boolean = info.split(';').drop(1).none { EXPIRY.containsMatchIn(it.trim()) }

    /** The same cookie line with a Max-Age added. */
    fun withMaxAge(info: String, maxAgeSeconds: Long): String = info.trimEnd().trimEnd(';') + "; Max-Age=" + maxAgeSeconds

    private fun hostUrl(site: ProviderSite) = "https://" + site.loginUrl.removePrefix("https://").substringBefore('/') + "/"

    private fun pairs(header: String?): List<Pair<String, String>> =
        header?.split(';')?.mapNotNull { part ->
            val t = part.trim()
            val i = t.indexOf('=')
            if (i <= 0) null else t.substring(0, i) to t.substring(i + 1)
        } ?: emptyList()

    private fun nameOf(line: String) = line.substringBefore(';').substringBefore('=').trim()

    /** Re-writes the site's current session cookies when the cookie info can decide, and snapshots them; returns how many got a Max-Age. Logs counts only. */
    fun persist(
        site: ProviderSite,
        jar: CookieJar,
        log: (String) -> Unit,
        maxAgeSeconds: Long = SessionCookieConfig.MAX_AGE_SECONDS,
        snapshots: SessionSnapshots? = null,
    ): Int {
        if (!site.persistSession) return 0
        val hostUrl = hostUrl(site)
        if (!site.isProviderUrl(hostUrl)) return 0
        val info = jar.cookieInfo(hostUrl)
        // A provider always keeps some cookies with an expiry: if none shows one, this WebView's cookie info is missing or omits
        // expiry attributes, so session and persistent cookies can't be told apart. Then only the snapshot path applies.
        val decidable = info != null && info.any { !isSessionCookie(it) }
        snapshot(site, jar, if (decidable) info else null, snapshots, log)
        if (!decidable || info == null) { log("${site.provider.name} session cookies: expiry not visible in cookie info, restore relies on the snapshot"); return 0 }
        val session = info.filter(::isSessionCookie)
        session.forEach { jar.setCookie(hostUrl, withMaxAge(it, maxAgeSeconds)) }
        if (session.isNotEmpty()) jar.flush()
        log("${site.provider.name} session cookies made persistent: ${session.size} of ${info.size}")
        return session.size
    }

    /** Stores the session cookies encrypted. [decidedInfo]: the cookie info when it tells session cookies apart (then only those are kept). */
    private fun snapshot(site: ProviderSite, jar: CookieJar, decidedInfo: List<String>?, snapshots: SessionSnapshots?, log: (String) -> Unit) {
        if (snapshots == null) return
        val www = pairs(jar.cookieHeader(hostUrl(site)))
        if (www.isEmpty()) return // signed out or unreadable: keep the last good snapshot
        val apex = pairs(jar.cookieHeader("https://${site.cookieDomain}/")).map { it.first }.toSet()
        val info = (decidedInfo ?: jar.cookieInfo(hostUrl(site)))?.associateBy(::nameOf).orEmpty()
        val lines = www.mapNotNull { (name, value) ->
            val known = info[name]
            when {
                decidedInfo != null -> known?.takeIf(::isSessionCookie)
                known != null -> known
                else -> "$name=$value; Path=/; Secure" +
                    (if (name in apex) "; Domain=.${site.cookieDomain}" else "") +
                    (if (name in site.pageReadableCookies) "" else "; HttpOnly")
            }
        }
        if (lines.isEmpty()) return
        runCatching { snapshots.save(site.provider, lines) }
            .onSuccess { log("${site.provider.name} session snapshot: ${lines.size} cookies (encrypted)") }
            .onFailure { log("${site.provider.name} session snapshot: not saved") }
    }

    /**
     * The start of a process, before the first provider page: re-sets, as session cookies, the snapshotted cookies that are missing
     * now. Cookies that are present are never touched. Returns how many were re-set.
     */
    fun restore(site: ProviderSite, jar: CookieJar, snapshots: SessionSnapshots?, log: (String) -> Unit): Int {
        if (!site.persistSession || snapshots == null) return 0
        val hostUrl = hostUrl(site)
        val snap = snapshots.load(site.provider)
        if (snap.isEmpty()) return 0
        val present = (pairs(jar.cookieHeader(hostUrl)).map { it.first } + pairs(jar.cookieHeader("https://${site.cookieDomain}/")).map { it.first }).toSet()
        val missing = snap.filter { nameOf(it) !in present }
        missing.forEach { jar.setCookie(hostUrl, it) }
        if (missing.isNotEmpty()) jar.flush()
        log("${site.provider.name} session restore: restored ${missing.size} of ${snap.size} snapshotted session cookies")
        return missing.size
    }

    /** One line after a run that started with cookies restored by an earlier process: whether the server session was still alive. */
    fun logRestore(provider: String, expired: Boolean, log: (String) -> Unit) =
        log("$provider restored session: ${if (expired) "expired" else "valid"}")
}
