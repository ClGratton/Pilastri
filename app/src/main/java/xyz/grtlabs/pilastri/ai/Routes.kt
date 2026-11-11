// Account linking adapted from the user's own Cattedra app (registro_elettronico/nextapp, data/Assistant.kt: providers,
// session check by cookie name only, host allow-list). Same author; Cattedra's repository is CC BY-NC-SA 4.0 and its
// next-app licence is unresolved (COUNCIL.md, licence note). Rewritten for Pilastri, not copied verbatim.
package xyz.grtlabs.pilastri.ai

/**
 * How the user reaches an assistant. Persisted in [AiSettings.route].
 *
 *  - [CLAUDE_ACCOUNT]: the user's own Claude account, signed in on claude.ai inside an app WebView that stays hidden under
 *    the native Agente chat (shown only for a sign-in or a challenge the user must solve). The app types the question into
 *    that page and reads the reply back ([WebChatSession]). Consumer terms: Anthropic's consumer terms restrict automated
 *    or scripted access to claude.ai except through the API or where explicitly allowed, so driving the page with a script
 *    may conflict with them. The supported routes are the API keys and the documented routine endpoint. Every send starts
 *    from the user's tap on "Invia"; nothing is ever sent in the background.
 *  - [CHATGPT_ACCOUNT]: the user's ChatGPT plan through OpenAI's official "Sign in with ChatGPT" (OAuth in the system
 *    browser, Responses API with tool calling: [ChatGptPlanTransport]). Since 2026-10-31 it replaces the chatgpt.com
 *    WebView, which is no longer offered.
 *  - [CLAUDE_API], [OPENAI_API]: the user's API key (Keystore vault) with native tool calling ([AiAgent]).
 *  - [CLAUDE_ROUTINE]: the user's Claude routine, fired through Anthropic's documented `/v1/claude_code/routines/<id>/fire`
 *    endpoint (Cattedra ADR 0016); the reply comes back as a file in a folder the user picks ([RoutineAsk]).
 */
enum class AiRoute(val label: String, val kind: Kind) {
    CLAUDE_ACCOUNT("Claude (account)", Kind.WEB),
    CHATGPT_ACCOUNT("ChatGPT (account)", Kind.PLAN),
    CLAUDE_API("Claude API", Kind.API),
    OPENAI_API("OpenAI API", Kind.API),
    CLAUDE_ROUTINE("Routine Claude", Kind.ROUTINE);

    /** WEB: provider page in a hidden WebView. PLAN: Sign in with ChatGPT. API: API key. ROUTINE: Claude routine. */
    enum class Kind { WEB, PLAN, API, ROUTINE }

    val web: WebAccount?
        get() = when (this) {
            CLAUDE_ACCOUNT -> WebAccount.CLAUDE
            else -> null
        }

    val api: AiProvider?
        get() = when (this) {
            CLAUDE_API -> AiProvider.ANTHROPIC
            OPENAI_API -> AiProvider.OPENAI
            else -> null
        }

    /** What the user accepts before the first send on this route. API routes keep [AiProvider.consentText]. */
    fun consentText(): String = when (kind) {
        Kind.API -> api!!.consentText()
        Kind.WEB -> "Autorizzo l'invio a ${web!!.label}, con il mio account nella pagina del sito dentro l'app, delle mie domande e di un " +
            "riepilogo dei dati dell'archivio letto dall'app (mascherato: IBAN, carte, codici fiscali e numeri lunghi). L'invio parte solo " +
            "dal mio tocco su «Invia». Questi dati lasciano il dispositivo e sono soggetti alle condizioni del mio account ${web!!.label}. " +
            "Le condizioni d'uso del sito possono vietare l'accesso automatizzato: la via documentata è la chiave API o la routine."
        Kind.PLAN -> "Autorizzo l'invio a OpenAI, con il mio account ChatGPT e a carico del mio piano, delle mie domande e dei dati " +
            "dell'archivio che l'assistente legge per rispondere (movimenti, valutazioni, conti, movimenti importati, piano, indici). " +
            "L'invio parte solo dal mio tocco su «Invia». Questi dati lasciano il dispositivo e sono soggetti alle condizioni del mio " +
            "account ChatGPT. Posso revocare il consenso in qualsiasi momento."
        Kind.ROUTINE -> "Autorizzo l'invio delle mie domande e di un riepilogo mascherato dei dati dell'archivio alla mia routine di Claude, " +
            "che gira nel cloud di Anthropic con il mio account e scrive la risposta nella cartella che ho scelto. L'invio parte solo dal mio tocco. " +
            "Posso revocare il consenso in qualsiasi momento."
    }
}

/**
 * A consumer site the user signs in to. Only cookie NAMES are ever read, never values; sessions and cookies never enter
 * the archive, backups, logs or the AI context.
 */
enum class WebAccount(
    val label: String,
    /** The page the chat opens on. */
    val siteUrl: String,
    /** Where the session cookie is looked up. */
    val cookieUrl: String,
    /** Any of these names (or its `name.0`, `name.1` chunks) means a session exists. */
    val sessionCookies: List<String>,
    /** Hosts the chat page may stay on (the site and its sign-in providers); anything else opens outside. */
    val hosts: List<String>,
    /** Hosts whose cookies are deleted on "Scollega". Google, Apple or Microsoft sign-in cookies are not touched. */
    val ownHosts: List<String>,
    /** First path segments that are sign-in, sign-out or onboarding pages, not the chat. */
    val notChat: Set<String>,
) {
    CLAUDE(
        "Claude", "https://claude.ai/new", "https://claude.ai", listOf("sessionKey"),
        listOf("claude.ai", "claude.com", "anthropic.com"),
        listOf("claude.ai", "claude.com", "anthropic.com"),
        setOf("login", "logout", "magic", "magic-link", "signup", "verify", "onboarding"),
    ),
    /**
     * Legacy page-script adapter only. ChatGPT sign-in is never an in-app page: setup and the assistant both use
     * [ChatGptSignIn.start] (system browser, code + PKCE). [AiWebActivity] refuses to open this entry, and setup does not list it.
     */
    CHATGPT(
        "ChatGPT", "https://chatgpt.com/", "https://chatgpt.com",
        listOf("__Secure-next-auth.session-token", "next-auth.session-token", "__Secure-authjs.session-token", "authjs.session-token"),
        listOf("chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com"),
        listOf("chatgpt.com", "openai.com", "auth.openai.com"),
        setOf("auth", "login", "logout", "signup", "onboarding"),
    );

    /**
     * Sign-in can pass through these identity and bot-check providers; they stay inside the SIGN-IN view ([AiWebActivity])
     * only. The always-signed-in chat view uses [chatNavigation], which keeps just the provider's own [hosts].
     */
    val signInHosts: List<String>
        get() = hosts + listOf(
            "google.com", "gstatic.com", "googleapis.com", "googleusercontent.com", "youtube.com", "recaptcha.net",
            "apple.com", "microsoft.com", "microsoftonline.com", "live.com", "msauth.net", "msftauth.net",
            "cloudflare.com", "hcaptcha.com", "arkoselabs.com", "auth0.com",
        )

    /** ChatGPT never signs in through an in-app page (documented browser flow only). */
    val webSignIn: Boolean get() = this != CHATGPT

    fun matches(host: String, list: List<String> = hosts): Boolean = list.any { host == it || host.endsWith(".$it") }

    /**
     * Where a top-level navigation of the chat page ([WebChatSession]) goes. Only https/http on the provider's own [hosts]
     * stays inside; any other link (identity providers included: a new sign-in happens in [AiWebActivity]) opens in the
     * external browser; other schemes and, while the page is covered, `/logout` are blocked.
     */
    fun chatNavigation(scheme: String?, host: String?, path: String?, shown: Boolean): ChatNavigation = when {
        scheme != "https" && scheme != "http" -> ChatNavigation.BLOCK
        !shown && path.orEmpty().startsWith("/logout") -> ChatNavigation.BLOCK
        matches(host.orEmpty(), hosts) -> ChatNavigation.STAY
        else -> ChatNavigation.OPEN_OUTSIDE
    }
}

enum class ChatNavigation { STAY, BLOCK, OPEN_OUTSIDE }

enum class AiLinkState(val label: String) {
    NOT_LINKED("non collegato"),
    LINKED("collegato"),
    EXPIRED("sessione scaduta"),
}

object WebSessions {
    /** Cookie names in a `Cookie:` header, without values. */
    fun cookieNames(cookieHeader: String?): List<String> =
        cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }

    /** A session cookie is present: one of [names], or a chunk of it (`name.0`). Values are never read. */
    fun hasSession(cookieHeader: String?, names: List<String>): Boolean =
        cookieNames(cookieHeader).any { c -> names.any { n -> c == n || c.startsWith("$n.") } }

    /**
     * The state shown to the user. [linkedAt] is when the app last saw a completed sign-in; [rejectedAt] is when the
     * provider page last sent the user back to its sign-in page. ISO-8601 instants compare as strings.
     */
    fun state(cookiePresent: Boolean, linkedAt: String?, rejectedAt: String?): AiLinkState = when {
        rejectedAt != null && (linkedAt == null || rejectedAt >= linkedAt) -> if (linkedAt == null && !cookiePresent) AiLinkState.NOT_LINKED else AiLinkState.EXPIRED
        cookiePresent -> AiLinkState.LINKED
        linkedAt != null -> AiLinkState.EXPIRED
        else -> AiLinkState.NOT_LINKED
    }

    /** The first path segment is a sign-in or onboarding page of [account], not the chat. */
    fun isSignInPath(account: WebAccount, path: String): Boolean =
        path.removePrefix("/").substringBefore('/').substringBefore('?').lowercase() in account.notChat

    /**
     * `Set-Cookie` strings that expire every cookie named in [cookieHeader] for [host], both host-only and for the
     * domain, at path `/`. Used by "Scollega" for the provider's own hosts only.
     */
    fun expiryCookies(cookieHeader: String?, host: String): List<String> =
        cookieNames(cookieHeader).distinct().flatMap { name ->
            listOf(
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/",
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$host",
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.$host",
            )
        }
}
