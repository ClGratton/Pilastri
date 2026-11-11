package xyz.grtlabs.pilastri.sync.auto

import java.net.URI
import java.util.Locale
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.isybank.IsybankConnector
import xyz.grtlabs.pilastri.sync.paypal.PaypalConnector

/** What kind of page a URL is, for the automatic sync. */
enum class SitePage { LOGIN, SCA, CHALLENGE, SIGNED_IN, PROVIDER_OTHER, FOREIGN }

/**
 * Where a provider lives: the hosts that may receive its cookies, the exact origins where the sign-in script runs, and the path
 * prefixes that tell sign-in, approval, challenge and signed-in pages apart. Pure Kotlin (java.net.URI), so it is unit-tested.
 *
 * A path prefix matches the path itself or a sub-path (`/signin` matches `/signin` and `/signin/x`, not `/signinx`).
 */
class ProviderSite(
    val provider: ImportProvider,
    val label: String,
    /** The connector's own host rule (one definition for cookies, downloads and navigation). */
    private val hostRule: (String?) -> Boolean,
    /** Exact `https://host` origins where the autofill script and its message bridge are installed. Never wildcards. */
    val loginOrigins: Set<String>,
    val loginUrl: String,
    /** A signed-in page; a signed-out visit is expected to be sent to the sign-in page. */
    val appUrl: String,
    val loginPaths: List<String>,
    val scaPaths: List<String> = emptyList(),
    val challengePaths: List<String> = emptyList(),
    val signedInPaths: List<String> = emptyList(),
    /** Opt-in: re-write the session cookies as persistent ones ([SessionCookiePersistence]). Off unless the provider's policy allows. */
    val persistSession: Boolean = false,
    /** Registrable domain whose cookies are re-written (`paypal.com`). */
    val cookieDomain: String = "",
    /** Cookie names the page's own scripts read (not HttpOnly); a restored cookie is HttpOnly unless listed here. */
    val pageReadableCookies: Set<String> = emptySet(),
) {
    init {
        require(loginOrigins.isNotEmpty() && loginOrigins.all { ORIGIN.matches(it) }) { "Origini di accesso non valide." }
        require(isProviderUrl(loginUrl) && isProviderUrl(appUrl)) { "Indirizzi del fornitore non validi." }
        require(!persistSession || isProviderUrl("https://$cookieDomain/")) { "Dominio dei cookie non valido." }
    }

    fun isProviderHost(host: String?): Boolean = host != null && hostRule(host.lowercase(Locale.ROOT).trimEnd('.'))

    /** https and a provider host, no user info, default port. */
    fun isProviderUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) && isProviderHost(uri.host)
    }

    /** True only for an exact login origin, as the WebView reports it (`https://www.paypal.com`, no path, no trailing slash). */
    fun isLoginOrigin(origin: String?): Boolean = origin != null && origin.lowercase(Locale.ROOT).trimEnd('/') in loginOrigins

    /** The page's origin is one of [loginOrigins]. */
    fun isOnLoginOrigin(url: String?): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || !(uri.port == -1 || uri.port == 443)) return false
        return "https://${uri.host.lowercase(Locale.ROOT)}" in loginOrigins
    }

    fun classify(url: String?): SitePage {
        if (!isProviderUrl(url)) return SitePage.FOREIGN
        val path = parse(url)?.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return when {
            matches(path, challengePaths) -> SitePage.CHALLENGE
            matches(path, scaPaths) -> SitePage.SCA
            matches(path, loginPaths) -> SitePage.LOGIN
            matches(path, signedInPaths) -> SitePage.SIGNED_IN
            else -> SitePage.PROVIDER_OTHER
        }
    }

    private fun matches(path: String, prefixes: List<String>): Boolean {
        val p = path.lowercase(Locale.ROOT)
        return prefixes.any { prefix ->
            val q = prefix.lowercase(Locale.ROOT).trimEnd('/')
            q.isEmpty() || p == q || p.startsWith("$q/")
        }
    }

    internal companion object {
        private val ORIGIN = Regex("https://[a-z0-9.-]+")

        fun parse(url: String?): URI? = try {
            url?.let { URI(it) }?.takeIf { it.host != null }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The two sites. EVERY PATH HERE IS UNVERIFIED: no sign-in was made during development. The host rules are the connectors' own
 * ([PaypalConnector.isPaypalHost], [IsybankConnector.isBankHost]). Fix the paths here once the primary agent has seen the real
 * signed-in sessions; nothing else needs to change.
 */
object ProviderSites {
    val PAYPAL = ProviderSite(
        provider = ImportProvider.PAYPAL,
        label = "PayPal",
        hostRule = PaypalConnector::isPaypalHost,
        loginOrigins = setOf("https://www.paypal.com"),
        loginUrl = "https://www.paypal.com/signin",
        // The account summary, not the statements page: after sign-in the app fetches the data itself.
        appUrl = "https://www.paypal.com/myaccount/summary",
        loginPaths = listOf("/signin", "/authflow", "/auth", "/ap/signin"),
        challengePaths = listOf("/auth/validatecaptcha"),
        signedInPaths = listOf("/myaccount"),
        persistSession = true,
        cookieDomain = "paypal.com",
        pageReadableCookies = setOf("TLTSID", "tcs"),
    )

    val ISYBANK = ProviderSite(
        provider = ImportProvider.ISYBANK,
        label = "isybank",
        hostRule = IsybankConnector::isBankHost,
        loginOrigins = setOf("https://www.isybank.com"),
        loginUrl = IsybankConnector.LOGIN_URL,
        // Unknown signed-in entry: the authorize URL is expected to forward a signed-in session into internet banking.
        appUrl = IsybankConnector.LOGIN_URL,
        loginPaths = listOf("/isyweblogin"),
    )

    fun forProvider(provider: ImportProvider): ProviderSite? = when (provider) {
        ImportProvider.PAYPAL -> PAYPAL
        ImportProvider.ISYBANK -> ISYBANK
        else -> null
    }
}
