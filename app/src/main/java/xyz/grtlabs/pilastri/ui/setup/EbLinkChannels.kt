package xyz.grtlabs.pilastri.ui.setup

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import xyz.grtlabs.pilastri.sync.eb.EbLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Enable Banking sign-in link can reach the app by two automatic channels: the Gmail read (`GmailConnector.magicLink`) and a
 * notification from any mail app ([EbNotificationListener]). [EbLinkRace] runs both; the first link wins; none within
 * [EbLinkRace.TIMEOUT_MS] opens the instruction screen. Nothing here stores or logs a notification, a message or a link.
 */
object EbLinkRace {
    const val TIMEOUT_MS = 45_000L

    /** The first non-null link from [channels] within [timeoutMs], else null. A channel that has nothing (returns null) does not end the race. */
    suspend fun await(timeoutMs: Long = TIMEOUT_MS, channels: List<suspend () -> String?>): String? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val done = CompletableDeferred<String>()
            channels.forEach { c -> launch { c()?.let { done.complete(it) } } }
            try { done.await() } finally { coroutineContext.cancelChildren() }
        }
    }
}

/** In-memory hand-over from the notification listener to the sign-in step. Reads nothing unless a step is [waiting]. */
object EbLinkInbox {
    @Volatile var waiting: Boolean = false
    private val skippedNonMail = java.util.concurrent.atomic.AtomicInteger(0)
    /** Where the wait lines go; tests replace it. */
    @Volatile var sink: (String) -> Unit = { EbLog.line(EbLog.MAIL, it) }

    /** A link wait starts: one line, the non-mail counter restarts. */
    fun beginWait() { skippedNonMail.set(0); waiting = true; sink("link wait start") }

    /** A link wait ends ([outcome] = found, timeout or cancel): one line with the count of notifications from non-mail apps (no package names). Idempotent. */
    fun endWait(outcome: String) {
        if (!waiting) return
        waiting = false
        sink("link wait end outcome=$outcome nonMailNotifications=${skippedNonMail.getAndSet(0)}")
    }

    /** True when the listener should look at this notification: only while a wait is active and only for a mail app; other apps are only counted, never named. */
    fun admit(isMail: Boolean): Boolean {
        if (!waiting) return false
        if (!isMail) { skippedNonMail.incrementAndGet(); return false }
        return true
    }
    private val links = Channel<String>(Channel.CONFLATED)

    /** Called by the listener; ignored unless the sign-in step is waiting. */
    fun offer(url: String): Boolean {
        if (!waiting) return false
        return links.trySend(url).isSuccess
    }

    /** Forget anything left from an earlier wait. */
    fun reset() { while (links.tryReceive().isSuccess) { /* drain */ } }

    suspend fun next(): String = links.receive()

    /** "The provider's e-mail arrived": a flag only (no address, no content), set by the listener, read by the wait page. */
    private val seen = kotlinx.coroutines.flow.MutableStateFlow(false)
    val mailSeen: kotlinx.coroutines.flow.StateFlow<Boolean> get() = seen
    fun markMailSeen() { if (waiting) seen.value = true }
    fun clearMailSeen() { seen.value = false }
}

/** Pure matching of a mail notification: unit tested on synthetic extras. */
object EbNotificationLink {
    /** Mail apps matched by package when a notification carries no e-mail category. */
    val MAIL_PACKAGES = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider", "ch.protonmail.android",
        "com.yahoo.mobile.client.android.mail", "com.fsck.k9", "net.thunderbird.android", "me.bluemail.mail", "com.tutanota.android",
    )
    private val SENDER = Regex("enable\\s?banking", RegexOption.IGNORE_CASE)
    private val URL = Regex("https://[^\\s<>\"')\\]]+", RegexOption.IGNORE_CASE)

    /** The https addresses in a text, for the debug log (reduced by EbLog.link before they are written). */
    fun urls(t: CharSequence): List<String> = URL.findAll(t).map { it.value.trimEnd('.', ',', ';') }.toList()

    /** [texts] are the text-like extras (text, big text, lines, sub text), [title] the title. Null unless a mail app and Enable Banking. */
    /** True when a mail-app notification names Enable Banking outside any URL (with or without a link: the e-mail has arrived). */
    fun fromProvider(pkg: String?, category: String?, title: CharSequence?, texts: List<CharSequence?>): Boolean {
        if (category != Notification.CATEGORY_EMAIL && pkg !in MAIL_PACKAGES) return false
        return (listOf(title) + texts).mapNotNull { it?.toString() }.any { SENDER.containsMatchIn(URL.replace(it, " ")) }
    }

    fun extract(pkg: String?, category: String?, title: CharSequence?, texts: List<CharSequence?>): String? {
        if (category != Notification.CATEGORY_EMAIL && pkg !in MAIL_PACKAGES) return null
        val all = (listOf(title) + texts).mapNotNull { it?.toString() }
        // the sender is named outside any URL: a link alone does not make a mail come from Enable Banking
        if (all.none { SENDER.containsMatchIn(URL.replace(it, " ")) }) return null
        return all.asSequence().flatMap { URL.findAll(it).map { m -> m.value.trimEnd('.', ',', ';') } }
            .firstNotNullOfOrNull { EbSignInLink.fromClipboard(it) }
    }
}

/** Needs the person's notification access (asked once in setup). Reads only while [EbLinkInbox.waiting]; stores and logs nothing. */
class EbNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        val n = sbn.notification
        val isMail = n?.category == Notification.CATEGORY_EMAIL || pkg in EbNotificationLink.MAIL_PACKAGES
        if (!EbLinkInbox.admit(isMail)) return
        val e = n?.extras
        if (e == null) { EbLog.line(EbLog.MAIL, "skip reason=no extras pkg=$pkg mailApp=true"); return }
        val title = e.getCharSequence(Notification.EXTRA_TITLE)
        val text = e.getCharSequence(Notification.EXTRA_TEXT)
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList().orEmpty()
        val texts = listOf(text, big, sub) + lines
        val link = EbNotificationLink.extract(pkg, n.category, title, texts)
        val links = (listOf(title) + texts).filterNotNull().flatMap { EbNotificationLink.urls(it) }.distinct().map { EbLog.link(it) }
        val reason = when {
            link != null -> "taken"
            (listOf(title) + texts).none { it?.toString()?.let { t -> Regex("enable\\s?banking", RegexOption.IGNORE_CASE).containsMatchIn(t) } == true } -> "no Enable Banking sender in title or text"
            links.isEmpty() -> "no link in title or text"
            else -> "no sign-in link among the links"
        }
        EbLog.line(EbLog.MAIL, "notification pkg=$pkg mailApp=true category=${n.category} title=${EbLog.scrub(title?.toString())} text=${EbLog.scrub(text?.toString())} " +
            "bigText=${EbLog.scrub(big?.toString())} subText=${EbLog.scrub(sub?.toString())} textLines=${lines.map { EbLog.scrub(it.toString()) }} links=$links " +
            "actions=${n.actions?.map { EbLog.scrub(it.title?.toString()) } ?: emptyList<String>()} decision=${if (link != null) "link taken" else "skipped"} reason=$reason")
        if (EbNotificationLink.fromProvider(pkg, n.category, title, texts)) EbLinkInbox.markMailSeen()
        link?.let(EbLinkInbox::offer)
    }
}
