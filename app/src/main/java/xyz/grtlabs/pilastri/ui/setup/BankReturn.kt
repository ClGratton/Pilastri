package xyz.grtlabs.pilastri.ui.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.BuildConfig
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbLog
import xyz.grtlabs.pilastri.sync.eb.EbRedirect

/*
 * Bank authorization in the person's own browser (wave bank-browser-return, D1-D8). The provider's session lives in that browser, so
 * the bank page and the bank app share it; the return is a verified App Link on the site (gradle.properties `pilastri.siteHost`,
 * path [BankReturn.PATH]) that the site's static page hands back to MainActivity. Nothing here names a provider, a bank or a tracker host.
 * Logs: `Pilastri.EB` through [EbLog]; lengths and yes/no only, never the code or the state.
 */

/** The one return address: https, the site host from gradle.properties, the fixed path. */
object BankReturn {
    const val PATH = "/ritorno-banca.html"
    val ADDRESS: String get() = "https://${BuildConfig.SITE_HOST}$PATH"

    /** True for the return address (scheme, host, port, path); the query is not looked at. */
    fun isReturn(url: String?): Boolean = url != null && EbRedirect.matches(url, ADDRESS)

    /** Only the code's length is ever logged. */
    fun codeLength(url: String): Int = param(url, "code")?.length ?: 0
    fun hasError(url: String): Boolean = param(url, "error") != null

    private fun param(url: String, name: String): String? = try {
        URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val i = part.indexOf('=')
            if (URLDecoder.decode(if (i < 0) part else part.substring(0, i), "UTF-8") == name) URLDecoder.decode(if (i < 0) "" else part.substring(i + 1), "UTF-8") else null
        }
    } catch (_: Exception) { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * MainActivity's entry (cold start and onNewIntent). [data] is the intent's address; [source] is `applink` for a VIEW from the system
     * or `intent` for the page's intent: hand-back. Runs off the main thread; the result goes to [BankReturnInbox].
     */
    fun deliver(context: Context, data: String?, source: String) {
        if (!isReturn(data)) return
        val app = context.applicationContext
        scope.launch { BankReturnHandler(EbBankAccess(app), BankReturnInbox.of(app)).handle(data!!, source) }
    }
}

/** What a return finished: the bank, whether it is linked, and the accounts read (null when unknown). */
data class BankReturnResult(val bank: String, val ok: Boolean, val accounts: Int? = null)

/** Where an unconsumed result survives a process death (a bank name, a flag and a count). */
interface ReturnStore { fun load(): BankReturnResult?; fun save(r: BankReturnResult?) }

/** The finished return waits here until the step that started it takes it; a flow restored after a process death finds it too. */
class BankReturnInbox(private val store: ReturnStore? = null) {
    private val flow = MutableStateFlow(store?.load())
    val pending: StateFlow<BankReturnResult?> get() = flow
    fun publish(r: BankReturnResult) { store?.save(r); flow.value = r }
    fun consume(r: BankReturnResult) { if (flow.value == r) { store?.save(null); flow.value = null } }

    companion object {
        @Volatile private var shared: BankReturnInbox? = null
        fun of(context: Context): BankReturnInbox = shared ?: synchronized(this) {
            shared ?: BankReturnInbox(PrefsReturnStore(context.applicationContext)).also { shared = it }
        }
    }
}

private class PrefsReturnStore(private val c: Context) : ReturnStore {
    private val p get() = c.getSharedPreferences("pilastri_setup", Context.MODE_PRIVATE)
    override fun load(): BankReturnResult? = p.getString("returnBank", null)?.let { BankReturnResult(it, p.getBoolean("returnOk", false), p.getInt("returnAccounts", -1).takeIf { n -> n >= 0 }) }
    override fun save(r: BankReturnResult?) {
        p.edit().apply {
            if (r == null) { remove("returnBank"); remove("returnOk"); remove("returnAccounts") }
            else { putString("returnBank", r.bank); putBoolean("returnOk", r.ok); putInt("returnAccounts", r.accounts ?: -1) }
        }.apply()
    }
}

/**
 * A return reached the app: the `state` must match the authorization kept in the encrypted client state (it survives the process), else
 * the return is ignored with one log line. A match completes the link ([BankAccess.completeLink], which exchanges the code) and publishes
 * the outcome; an `error` return ends the authorization as a failure.
 */
class BankReturnHandler(private val access: BankAccess, private val inbox: BankReturnInbox) {
    private fun log(t: String) = EbLog.line(EbLog.EB, t)

    suspend fun handle(url: String, source: String): BankReturnResult? {
        if (!BankReturn.isReturn(url)) return null
        val match = try { access.matchesPending(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        log("return received source=$source state=${EbLog.yn(match)} code=${BankReturn.codeLength(url)} error=${EbLog.yn(BankReturn.hasError(url))}")
        if (!match) { log("return ignored reason=no_matching_authorization"); return null }
        val bank = access.pendingBank() ?: run { log("return ignored reason=no_pending_bank"); return null }
        val aspsp = runCatching { access.banks().firstOrNull { it.name == bank } }.getOrNull() ?: EbAspsp(bank, "IT", null, emptyList(), emptyList(), emptyList(), false)
        val ok = BankSteps.complete(aspsp, url, access)
        val result = BankReturnResult(bank, ok, if (ok) runCatching { access.accountCount(bank) }.getOrNull() else null)
        log("return outcome=" + if (ok) "linked accounts=${result.accounts ?: "?"}" else "failed")
        inbox.publish(result)
        return result
    }
}

/** Opens the bank's authorization address in the person's default browser: a Custom Tab when it offers one, else ACTION_VIEW. */
object BankBrowser {
    /** https only; the debug build, whose provider is a local fake (BuildConfig.EB_BASE_URL, empty elsewhere), may also open that fake's own address. */
    fun allowed(url: String): Boolean = url.startsWith("https://") || BuildConfig.EB_BASE_URL.isNotEmpty() && url.startsWith(BuildConfig.EB_BASE_URL + "/")

    /** True when a browser took the address. [customTabs] is the answer of the browser check (a seam for tests). */
    fun open(context: Context, url: String, customTabs: Boolean = CustomTabsClient.getPackageName(context, null) != null): Boolean {
        // Only the debug build, whose provider is a local fake (BuildConfig.EB_BASE_URL, empty elsewhere), may open its own cleartext address.
        if (!allowed(url)) { EbLog.line(EbLog.EB, "bank browser refused reason=not_https"); return false }
        val uri = Uri.parse(url)
        val opened = try {
            if (customTabs) {
                CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, uri)
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } catch (_: ActivityNotFoundException) { false } catch (_: Exception) { false }
        EbLog.line(EbLog.EB, "bank browser opened=${EbLog.yn(opened)} customTab=${EbLog.yn(customTabs)}")
        if (opened) openedAt = android.os.SystemClock.elapsedRealtime()
        return opened
    }

    @Volatile private var openedAt = 0L

    /** Whole seconds since `bank browser opened`, or -1 when none was opened in this process (app foreground/background log lines). */
    fun secondsSinceOpen(): Long = if (openedAt == 0L) -1 else (android.os.SystemClock.elapsedRealtime() - openedAt) / 1000
}
