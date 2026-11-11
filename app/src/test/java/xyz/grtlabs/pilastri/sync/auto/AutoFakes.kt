package xyz.grtlabs.pilastri.sync.auto

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.channels.Channel
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedMovement

/** TEST VALUES ONLY: synthetic logins, hosts and keys. Nothing here is a real credential. */
internal object T {
    const val USER = "utente.sintetico@example.test"
    const val PASSWORD = "Pw-sintetica-123!"
    val fetchedAt: Instant = Instant.parse("2026-10-31T09:00:00Z")
    val today: LocalDate = LocalDate.of(2026, 10, 31)

    fun batch(provider: ImportProvider = ImportProvider.ISYBANK, to: LocalDate = today, n: Int = 1) = ImportBatch(
        provider = provider, accountLabel = provider.label, fetchedAt = fetchedAt, coverageFrom = to.minusDays(30), coverageTo = to,
        movements = (1..n).map { ImportedMovement("m$it", to.minusDays(it.toLong()), -100L * it, "EUR", "Sintetico $it", ExternalKind.CARD_PAYMENT) },
    )
}

/** Real AES-GCM with a software key: binds the associated data like the Keystore cipher does. */
internal class SoftwareCipher(key: ByteArray = ByteArray(32) { it.toByte() }) : CredentialCipher {
    private val spec = SecretKeySpec(key, "AES")
    private var counter = 0L

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also { java.nio.ByteBuffer.wrap(it).putLong(4, ++counter) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(aad)
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Scripted HTTP: answers by exact URL; records every call as sent (with the Cookie header the fetcher attached). */
internal class FakeHttp : SessionHttp {
    val calls = ArrayList<HttpCall>()
    val routes = HashMap<String, (HttpCall) -> HttpReply>()

    fun on(url: String, reply: (HttpCall) -> HttpReply) { routes[url] = reply }

    override fun execute(call: HttpCall, maxBytes: Int): HttpReply {
        calls += call
        val reply = routes[call.url]?.invoke(call) ?: HttpReply(404, emptyMap(), ByteArray(0))
        return if (reply.body.size > maxBytes) HttpReply(reply.code, mapOf("Content-Type" to listOfNotNull(reply.header("Content-Type"))), reply.body.copyOf(maxBytes), true) else reply
    }

    companion object {
        fun ok(body: ByteArray, type: String, vararg headers: Pair<String, String>) =
            HttpReply(200, (listOf("Content-Type" to type) + headers).groupBy({ it.first }, { it.second }), body)
        fun redirect(code: Int, location: String) = HttpReply(code, mapOf("Location" to listOf(location)), ByteArray(0))
        fun status(code: Int, type: String = "application/json") = HttpReply(code, mapOf("Content-Type" to listOf(type)), ByteArray(0))
    }
}

/** A cookie jar keyed by host; records every URL it was asked about. */
internal class FakeCookies(private val byHost: Map<String, String> = emptyMap()) : SessionCookies {
    val asked = ArrayList<String>()
    val stored = ArrayList<Pair<String, List<String>>>()

    override fun header(url: String): String? { asked += url; return byHost[URI(url).host] }
    override fun value(url: String, name: String): String? =
        header(url)?.split(';')?.map { it.trim() }?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')
    override fun store(url: String, setCookie: List<String>) { stored += url to setCookie }
}

/** A provider page for the fetchers: load answers the final URL; evaluate answers scripted results. */
internal open class FakePage(var url: String? = null) : PageAccess {
    val loads = ArrayList<String>()
    var onLoad: (String) -> String? = { it }
    var onEvaluate: (String) -> String? = { null }

    override suspend fun load(url: String): String? { loads += url; return onLoad(url).also { this.url = it } }
    override suspend fun evaluate(script: String): String? = onEvaluate(script)
    override fun currentUrl(): String? = url
}

/** A WebView driver whose page reacts as scripted. */
internal class FakeDriver(override val canReveal: Boolean = true) : FakePage(), LoginDriver {
    val events = Channel<LoginEvent>(Channel.UNLIMITED)
    val opened = ArrayList<String>()
    val fills = ArrayList<LoginStep>()
    val filledValues = ArrayList<Pair<String, String>>()
    val reveals = ArrayList<AttentionReason>()
    val captures = ArrayList<Boolean>()
    var covers = 0
    var onOpen: (String) -> List<LoginEvent> = { emptyList() }
    var onFill: (LoginStep) -> List<LoginEvent> = { emptyList() }

    fun send(vararg e: LoginEvent) = e.forEach { if (it is LoginEvent.Page) url = it.url; events.trySend(it) }

    override fun open(url: String) { opened += url; this.url = url; send(*onOpen(url).toTypedArray()) }
    override suspend fun nextEvent(): LoginEvent = events.receive()
    override fun fill(step: LoginStep, login: SavedLogin) {
        fills += step
        filledValues += login.username to login.password
        send(*onFill(step).toTypedArray())
    }
    override fun setCapture(on: Boolean) { captures += on }
    override fun reveal(reason: AttentionReason) { reveals += reason }
    override fun cover() { covers++ }
}

/** A fetcher whose answer depends on a flag the fake page flips when the sign-in "works". */
internal class FakeFetcher(
    override val site: ProviderSite = ProviderSites.ISYBANK,
    override val needsPage: Boolean = false,
    override val navigates: Boolean = false,
    override val configured: Boolean = true,
    var signedIn: Boolean = false,
    var batch: ImportBatch = T.batch(),
    var whenSignedInButRefused: Boolean = false,
) : SessionFetcher {
    var calls = 0
    val navigations = ArrayList<Boolean>()

    override suspend fun fetch(range: SyncRange, page: PageAccess?, mayNavigate: Boolean): FetchOutcome {
        calls++
        navigations += mayNavigate
        if (!configured) return FetchOutcome.NotConfigured("${site.label}: $NOT_CONFIGURED")
        if (navigates && !mayNavigate) return FetchOutcome.NotReady
        return if (signedIn && !whenSignedInButRefused) FetchOutcome.Fetched(batch) else FetchOutcome.SignedOut
    }
}

/**
 * A tiny reader of synthetic login HTML into [PageSignals], for the matcher tests. It understands `<input …>`, `<label for>`,
 * `<iframe src>`, id/class/name attributes, `hidden`, `style="display:none"`, `role="alert"` blocks and plain text.
 */
internal object SyntheticHtml {
    private val TAG = Regex("<(input|iframe|div|span|label|button|form|/form)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z-]+)(?:\\s*=\\s*\"([^\"]*)\")?")
    private val LABEL = Regex("<label\\s+for=\"([^\"]+)\"[^>]*>(.*?)</label>", RegexOption.IGNORE_CASE)
    private val ALERT = Regex("<div[^>]*role=\"alert\"[^>]*>(.*?)</div>", RegexOption.IGNORE_CASE)

    private fun attrs(raw: String): Map<String, String> = ATTR.findAll(raw).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    private fun hidden(a: Map<String, String>) = "hidden" in a || a["style"].orEmpty().replace(" ", "").let { "display:none" in it || "visibility:hidden" in it }

    fun signals(html: String): PageSignals {
        val labels = LABEL.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        val fields = ArrayList<FormField>()
        val frames = ArrayList<Marked>()
        val markers = ArrayList<Marked>()
        var form = -1
        var inForm = false
        for (m in TAG.findAll(html)) {
            val tag = m.groupValues[1].lowercase()
            val a = attrs(m.groupValues[2])
            when (tag) {
                "form" -> { form++; inForm = true }
                "/form" -> inForm = false
                "input" -> fields += FormField(
                    type = a["type"]?.lowercase() ?: "text", name = a["name"].orEmpty(), id = a["id"].orEmpty(),
                    autocomplete = a["autocomplete"].orEmpty(),
                    label = listOfNotNull(a["aria-label"], a["id"]?.let { labels[it] }, a["title"]).joinToString(" "),
                    placeholder = a["placeholder"].orEmpty(), maxLength = a["maxlength"]?.toIntOrNull(),
                    visible = !hidden(a) && a["type"]?.lowercase() != "hidden", enabled = "disabled" !in a, readOnly = "readonly" in a,
                    form = if (inForm) form else -1,
                )
                "iframe" -> frames += Marked(a["src"].orEmpty(), !hidden(a))
            }
            val tagged = listOfNotNull(a["id"], a["class"], a["name"]).joinToString(" ")
            if (tagged.isNotBlank() && tag != "iframe") markers += Marked(tagged, !hidden(a))
        }
        val alert = ALERT.findAll(html).joinToString(" ") { it.groupValues[1] }
        val text = html.replace(Regex("<[^>]+>"), " ")
        return PageSignals(fields, frames, markers, text, alert)
    }
}
