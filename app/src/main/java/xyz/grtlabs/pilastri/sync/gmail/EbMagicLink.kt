package xyz.grtlabs.pilastri.sync.gmail

import kotlinx.coroutines.CancellationException
import xyz.grtlabs.pilastri.ui.setup.EbSignInLink

/**
 * Finds the Enable Banking sign-in e-mail (a magic link) in the person's Gmail so the app can load it in its own WebView.
 * Nothing is stored or logged: not the message, not the link. Replaceable as one component (setup's SignUpPage calls
 * [GmailConnector.magicLink] only).
 */
internal object EbMagicLink {
    /** Messages from Enable Banking received after [sinceEpochSec] (Gmail's `after:` takes epoch seconds). */
    fun query(sinceEpochSec: Long) = "from:enablebanking.com after:$sinceEpochSec"

    private val url = Regex("https://(?:[A-Za-z0-9-]+\\.)*enablebanking\\.com/[^\\s<>\"')\\]]+", RegexOption.IGNORE_CASE)
    private val signIn = Regex("sign|login|magic|auth|token|verify|confirm", RegexOption.IGNORE_CASE)

    /** Only a Gmail address is used to prefill the sign-in form. */
    fun isGmailAddress(a: String) = a.count { it == '@' } == 1 && a.substringAfter('@').lowercase() in setOf("gmail.com", "googlemail.com")

    private val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** HTML attribute escapes and quoted-printable leftovers that can sit inside an href. */
    private fun unescape(s: String) = s.trim().replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&").replace("&#61;", "=").replace("&#x3d;", "=", ignoreCase = true).replace("=3D", "=")

    /** The sign-in link of [message]: an Enable Banking sender, an https link on enablebanking.com, never an unsubscribe link. */
    fun extract(message: MailMessage): String? {
        if (!message.from.contains("enablebanking.com", ignoreCase = true)) return null
        val hrefs = href.findAll(message.html).map { unescape(it.groupValues[1]) }
        val links = (hrefs + url.findAll(message.text).map { it.value }).map { it.trimEnd('.', ',', ';') }.distinct()
            .filter { !it.contains("unsubscribe", ignoreCase = true) }.mapNotNull { EbSignInLink.fromClipboard(it) }.toList()
        return links.firstOrNull { signIn.containsMatchIn(it) } ?: links.firstOrNull()
    }
}

/** Polls for the sign-in mail for about [windowMs]. [ignore] holds the ids of mails that existed when the person asked for the link. */
internal class EbMagicLinkPoller(
    private val source: MailSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val windowMs: Long = 120_000,
    private val everyMs: Long = 5_000,
) {
    suspend fun find(sinceEpochSec: Long, ignore: Set<String> = emptySet()): String? {
        val end = clock() + windowMs
        try {
            while (true) {
                for (id in source.list(EbMagicLink.query(sinceEpochSec), null, 5).ids) {
                    if (id in ignore) continue
                    source.fetch(id)?.let { m -> EbMagicLink.extract(m)?.let { return it } }
                }
                if (clock() + everyMs > end) return null
                sleep(everyMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }
}
