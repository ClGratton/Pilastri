package xyz.grtlabs.pilastri.ui.setup

import xyz.grtlabs.pilastri.sync.eb.EbLog

/*
 * One bank authorization at a time (user, 2026-11-10: "Avevo fatto apro banca, stava lavorando, e quando ho aperto si è resettato").
 * A flow-level holder, outside any page's composition: from the first `begin` for a bank until its outcome (linked, denied, a named
 * failure, the end of the authorization's validity, the flow's end) it keeps that bank's authorization address. A second request
 * reopens the same address in the browser; nothing starts another authorization (the client keeps one pending `state`, a second begin
 * overwrote the first). Since wave bank-browser-return the page is the person's browser (BankBrowser), not a view of ours.
 * Logs: `Pilastri.EB` through [EbLog] only; causes are fixed lower-case words, never an address with its query, a code or a name.
 */

/** One authorization in flight for one bank. */
internal class ConsentLive(val bank: String, val url: String, val openedAt: Long)

/** What [ConsentSessions.claim] decided for a begin. */
internal sealed interface ConsentClaim {
    /** An authorization for this bank is live: open the same address again, start nothing. */
    class Reopen(val live: ConsentLive) : ConsentClaim
    /** A begin for this bank is already running. */
    data object Busy : ConsentClaim
    /** Nothing in flight: the caller starts the authorization, then calls [ConsentSessions.opened] or [ConsentSessions.released]. */
    data object Go : ConsentClaim
}

internal class ConsentSessions(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val live = LinkedHashMap<String, ConsentLive>()
    private val starting = HashSet<String>()

    private fun log(text: String) = EbLog.line(EbLog.EB, text)

    /** The live authorization for [bank], or null (an authorization older than [maxAgeMs] ended: [liveFor] closes it, cause=expired). */
    fun liveFor(bank: String): ConsentLive? {
        val l = live[bank] ?: return null
        if (now() - l.openedAt > maxAgeMs) { finish(bank, "expired"); return null }
        return l
    }

    fun inFlight(bank: String): Boolean = liveFor(bank) != null || bank in starting

    /** The only gate before an authorization starts. [cause]: effect | button | retry | restore. */
    fun claim(bank: String, cause: String): ConsentClaim {
        reopen(bank, cause)?.let { return ConsentClaim.Reopen(it) }
        if (!starting.add(bank)) { log("bank begin outcome=skipped_starting cause=$cause"); return ConsentClaim.Busy }
        return ConsentClaim.Go
    }

    /** A live authorization for [bank] (its address is opened again), or null. Logs the begin that was not needed. */
    fun reopen(bank: String, cause: String): ConsentLive? = liveFor(bank)?.also { log("bank begin outcome=skipped_live cause=$cause") }

    /** The start gave no address (blocked, failed): the reservation ends. */
    fun released(bank: String) { starting.remove(bank) }

    /** The authorization started: it is live until [finish]. */
    fun opened(bank: String, url: String): ConsentLive {
        starting.remove(bank)
        val l = ConsentLive(bank, url, now())
        live[bank] = l
        return l
    }

    /** The outcome (or the end of validity, or the flow's end): the authorization ends. */
    fun finish(bank: String, cause: String) {
        if (live.remove(bank) == null) return
        starting.remove(bank)
        log("bank authorization end cause=$cause")
    }

    /** The flow ends: nothing stays. */
    fun releaseAll(cause: String = "flow_end") { live.keys.toList().forEach { finish(it, cause) } }

    companion object {
        /** The app's own bound for an authorization left open (not a provider figure; none was read): 60 minutes. */
        const val MAX_AGE_MS = 60 * 60_000L
    }
}
