package xyz.grtlabs.pilastri.sync.gmail

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.IOException
import java.time.LocalDate
import xyz.grtlabs.pilastri.ai.AiTransport
import xyz.grtlabs.pilastri.data.InstallmentPaymentSeen
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSync

/** What a Gmail scan returns. Messages are read, never stored: only the extracted plan facts and short evidence summaries. */
sealed interface GmailResult {
    data class Found(val plans: List<InstallmentPlan>, val payments: List<InstallmentPaymentSeen>, val sync: InstallmentSync, val scanned: Int) : GmailResult

    /** Google needs the user's consent (first use or after it expired): launch it, then call [GmailConnector.onConsentResult] and scan again. */
    data class NeedsConsent(val consent: PendingIntent) : GmailResult

    /** The app has no Google OAuth client for this package and signing key yet; [message] says what the user must set up. */
    data class NotConfigured(val message: String) : GmailResult

    data class Failed(val message: String) : GmailResult
}

/**
 * Gmail, read-only, to learn about purchases paid in installments (contract fixed 2026-10-31; the connector owner fills in the bodies
 * and may add functions, never change these). Tokens stay with Google Play services or the Keystore; never in the archive, backups,
 * logs or AI context.
 *
 * Flow: [scan] asks Google Identity Services (`AuthorizationClient`, scope `gmail.readonly`) for an access token. If the user has not
 * consented, or the grant expired, it returns [GmailResult.NeedsConsent] with Google's own consent screen; the host launches it and calls
 * [onConsentResult]. With a token, [scan] lists and reads receipt-like messages through Gmail REST v1 (bounded: see [ScanLimits]), parses
 * them deterministically per lender, and returns plans, paid notices and a sync record for the host to merge. It never touches the archive.
 */
object GmailConnector {
    /** The user has linked Gmail before (a local flag, not a token). */
    fun isLinked(ctx: Context): Boolean = GmailAuth.isLinked(ctx)

    /** Searches receipts from [since] onwards and extracts installment plans and paid parts. Never mutates the archive. */
    suspend fun scan(ctx: Context, since: LocalDate): GmailResult = scan(ctx, since, null, null)

    /**
     * As [scan], plus an optional AI fallback for mails from senders no parser knows: only the subject and a redacted, trimmed text of those
     * mails are sent through [ai] (see [GmailAiFallback]); results stay PENDING with `extractedByAgent = true`. Off unless the host passes a
     * transport, and the host must have the user's AI consent before doing so. [aiModel] defaults to the provider's default model.
     *
     * A scan reads at most 300 messages, newest first. When that cap is hit, the returned `sync.coverageFrom` is later than [since]: call again
     * with [until] = `coverageFrom` to read the older window (a scan with [until] covers `since..until`; `sync.coverageTo` is then [until]).
     *
     * [known] are the plans the archive already holds (`archive.installments`). Windows are scanned independently, so without them a cancellation,
     * or a paid notice that numbers no part, can only look at its own window. With them, a cancellation of a known plan (matched by lender key
     * and order id, or by alias) returns that plan with its open parts CANCELLED, a numberless paid notice lands on the known plan's open
     * part (same amount, else the earliest), and a numberless paid notice or reminder that matches nothing is counted as unmatched and
     * creates no stub plan. `plans` then holds the new plans plus only the known plans a mail of this scan changed; merge them with
     * `withInstallmentPlans` and apply `payments` with `withInstallmentPaid` as before.
     */
    suspend fun scan(
        ctx: Context, since: LocalDate, ai: AiTransport?, aiModel: String? = null, until: LocalDate? = null, known: List<InstallmentPlan> = emptyList(),
    ): GmailResult {
        var refreshed = false
        while (true) {
            val token = when (val auth = GmailAuth.accessToken(ctx)) {
                is AuthOutcome.Consent -> return GmailResult.NeedsConsent(auth.intent)
                is AuthOutcome.NotConfigured -> return GmailResult.NotConfigured(auth.message)
                is AuthOutcome.Failed -> return GmailResult.Failed(auth.message)
                is AuthOutcome.Token -> auth.value
            }
            try {
                val source = GmailRestSource(OkHttpGmailHttp(), token)
                val today = LocalDate.now(MailText.ZONE)
                val found = GmailScanner(source, ai, aiModel).scan(since.coerceAtMost(today), today, until, known)
                GmailAuth.setLinked(ctx, true)
                return found
            } catch (e: GmailApiException) {
                when (e.kind) {
                    GmailApiException.Kind.AUTH -> {
                        // A cached token can be stale or revoked: drop it and ask once more before giving up.
                        GmailAuth.invalidate(ctx, token)
                        if (!refreshed) { refreshed = true; continue }
                        return GmailResult.Failed(e.message ?: "Accesso a Gmail non riuscito.")
                    }
                    GmailApiException.Kind.NOT_CONFIGURED -> return GmailResult.NotConfigured(e.message ?: GmailSetup.API_DISABLED_IT)
                    else -> return GmailResult.Failed(e.message ?: "Lettura di Gmail non riuscita.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: IOException) {
                return GmailResult.Failed("Connessione a Gmail non riuscita: controlla la rete e riprova.")
            } catch (_: Exception) {
                // Never surface e.message here: it could carry response fragments.
                return GmailResult.Failed("Lettura di Gmail non riuscita: la risposta non è stata compresa.")
            }
        }
    }

    /** The Enable Banking sign-in link from the newest mail since [sinceEpochSec], or null (not linked, no mail within ~2 minutes). Nothing kept. */
    suspend fun magicLink(ctx: Context, sinceEpochSec: Long): String? {
        if (!GmailAuth.isLinked(ctx)) return null
        val token = (GmailAuth.accessToken(ctx) as? AuthOutcome.Token)?.value ?: return null
        return EbMagicLinkPoller(GmailRestSource(OkHttpGmailHttp(), token)).find(sinceEpochSec)
    }

    /** The linked mailbox's own address when it is a Gmail one (prefills Enable Banking's e-mail field), else null. Nothing kept. */
    suspend fun mailboxAddress(ctx: Context): String? {
        if (!GmailAuth.isLinked(ctx)) return null
        val token = (GmailAuth.accessToken(ctx) as? AuthOutcome.Token)?.value ?: return null
        return try {
            val reply = OkHttpGmailHttp().get("https://gmail.googleapis.com/gmail/v1/users/me/profile?fields=emailAddress", token)
            if (reply.status !in 200..299) null else org.json.JSONObject(reply.body).optString("emailAddress").takeIf { EbMagicLink.isGmailAddress(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** The consent screen returned; true when access was granted. */
    fun onConsentResult(ctx: Context, data: Intent?): Boolean = GmailAuth.onConsentResult(ctx, data)

    /** Forgets the local link and, best effort, clears the cached token and revokes the grant with Google. */
    fun disconnect(ctx: Context) = GmailAuth.disconnect(ctx)

    /** True once the code path is complete. Without the user's Google Cloud setup a scan returns [GmailResult.NotConfigured] with the steps. */
    val available: Boolean get() = true
}
