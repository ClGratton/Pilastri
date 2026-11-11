package xyz.grtlabs.pilastri.ui.setup

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import xyz.grtlabs.pilastri.sync.eb.AndroidEbAppKey
import xyz.grtlabs.pilastri.sync.eb.EbApi
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbConnector
import xyz.grtlabs.pilastri.sync.eb.EbJwt
import xyz.grtlabs.pilastri.sync.eb.EbLinkResult
import xyz.grtlabs.pilastri.sync.eb.EbLog
import xyz.grtlabs.pilastri.sync.eb.EbRedirect
import xyz.grtlabs.pilastri.sync.eb.EbStateStore

/**
 * Setup's view of the Enable Banking client (sync/eb), wired only through its public API: the Keystore key
 * ([AndroidEbAppKey]), the encrypted state ([EbStateStore]) and [EbConnector]. Nothing here is verified against the real
 * service yet (BANK-INTEGRATIONS.md "First real run"); every failure surfaces as "Non riuscito" in setup.
 */
class EbBankAccess(context: Context) : BankAccess {
    private val app = context.applicationContext
    private val store: EbStateStore? by lazy { EbStateStore.createOrNull(app) }
    private val key = AndroidEbAppKey()

    override val signUpUrl: String = SIGN_UP_URL
    override val redirectUrl: String = REDIRECT_URL

    private fun connector(applicationId: String, s: EbStateStore) = EbConnector(EbApi(http, EbJwt(applicationId, key.signer())), s)

    private fun connector(): EbConnector {
        val s = store ?: error("Archivio sicuro del dispositivo non disponibile")
        val id = s.load().applicationId ?: error("Applicazione Enable Banking non registrata")
        check(key.exists()) { "Chiave dell'applicazione assente" }
        return connector(id, s)
    }

    override suspend fun registered(): Boolean = withContext(Dispatchers.IO) {
        val s = store ?: return@withContext false
        val local = s.load()
        local.applicationId != null && local.redirectUrl != null && keyUsable()
    }

    /**
     * The key exists and signs. A key the Keystore lists but cannot use (a leftover of a reset or restore) is deleted, so the next
     * registration creates a fresh one instead of failing on it; nothing is registered with it, because [registered] is false.
     */
    private fun keyUsable(): Boolean {
        if (!key.exists()) return false
        if (runCatching { key.signer().sign(ByteArray(1)) }.isSuccess) return true
        runCatching { key.delete() }
        return false
    }

    override suspend fun hasSession(bank: String): Boolean = withContext(Dispatchers.IO) { store?.load()?.sessionFor(bank) != null }

    override suspend fun publicKeyPem(): String? = withContext(Dispatchers.IO) {
        try { key.create(); key.publicKeyPem() } catch (_: Exception) { null }
    }

    override suspend fun verifyRegistration(applicationId: String): Boolean = withContext(Dispatchers.IO) {
        val s = store ?: return@withContext false
        if (!keyUsable()) return@withContext false
        val before = s.load()
        try {
            val c = connector(applicationId.trim(), s)
            c.saveRegistration(applicationId, REDIRECT_URL)
            c.banks(COUNTRY)
            true
        } catch (_: Exception) {
            // A wrong id or a key the form did not receive: restore what was there, claim nothing.
            runCatching { s.save(s.load().copy(applicationId = before.applicationId, redirectUrl = before.redirectUrl)) }
            false
        }
    }

    override suspend fun applicationId(): String? = withContext(Dispatchers.IO) { store?.load()?.applicationId }

    /**
     * Wave 16: the native registration got [applicationId] from the panel itself, for this account and this key's public half, so
     * it is stored as is (an app not yet activated by linking may refuse the signed bank list). The key must still sign.
     */
    override suspend fun storeRegistration(applicationId: String): Boolean = withContext(Dispatchers.IO) {
        val s = store ?: return@withContext false
        if (!keyUsable()) return@withContext false
        runCatching { connector(applicationId.trim(), s).saveRegistration(applicationId.trim(), REDIRECT_URL) }.isSuccess
    }

    override suspend fun accountCount(bank: String): Int? = withContext(Dispatchers.IO) { store?.load()?.sessionFor(bank)?.accounts?.size }

    override suspend fun banks(): List<EbAspsp> = withContext(Dispatchers.IO) { connector().banks(COUNTRY) }

    override suspend fun startLink(bank: EbAspsp): String = withContext(Dispatchers.IO) {
        // The authorization is always sent with the site's return address (BankReturn), also for an application stored before it.
        store?.let { s -> s.load().let { l -> if (l.redirectUrl != REDIRECT_URL) { EbLog.line(EbLog.EB, "redirect differs from the stored one: sending the site address"); s.save(l.copy(redirectUrl = REDIRECT_URL)) } } }
        connector().startLink(bank).authorizationUrl
    }

    override suspend fun matchesPending(url: String): Boolean = withContext(Dispatchers.IO) {
        val p = store?.load()?.pending ?: return@withContext false
        EbRedirect.parse(url, p.redirectUrl, p.state) != EbRedirect.Result.Invalid
    }

    override suspend fun pendingBank(): String? = withContext(Dispatchers.IO) { store?.load()?.pending?.bankName }

    override fun isRedirect(url: String): Boolean = EbRedirect.matches(url, REDIRECT_URL)

    override suspend fun completeLink(url: String, bank: EbAspsp): Boolean = withContext(Dispatchers.IO) {
        val c = connector()
        EbLog.line(EbLog.EB, "completeLink " + EbLog.claim(url, REDIRECT_URL, c.local().pending?.state))
        val r = try { c.completeLink(url, bank) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            EbLog.line(EbLog.EB, "completeLink outcome=failed kind=" + EbLog.kind(e)); throw e
        }
        EbLog.line(EbLog.EB, "completeLink outcome=" + when (r) {
            is EbLinkResult.Linked -> "linked accounts=${r.accounts.size}"
            is EbLinkResult.Denied -> "denied error=" + EbLog.errName(r.error)
            else -> "ignored"
        })
        r is EbLinkResult.Linked
    }

    companion object {
        const val COUNTRY = "IT"
        /** The control panel's sign-up / sign-in page (docs: Control Panel, read 2026-11-01). */
        const val SIGN_UP_URL = "https://enablebanking.com/sign-in/"
        /** The return address to whitelist in the application: the site's static page, handed back to the app as a verified App Link (BankReturn). */
        val REDIRECT_URL: String get() = BankReturn.ADDRESS

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
        }
    }
}

/** Setup's own small, non-secret memory: whether setup finished, and the banks whose consent waits ("Da approvare"). */
object SetupStore {
    private const val FILE = "pilastri_setup"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun done(c: Context): Boolean = prefs(c).getBoolean("done", false)
    fun markDone(c: Context) { prefs(c).edit().putBoolean("done", true).apply() }

    /** Bank names only (no consent, session or id). Read by Impostazioni › Conti to show "Da approvare". */
    fun pendingBanks(c: Context): Set<String> = prefs(c).getStringSet("pendingBanks", emptySet()).orEmpty().toSet()
    fun savePendingBanks(c: Context, names: Set<String>) { prefs(c).edit().putStringSet("pendingBanks", names.toSet()).apply() }
}
