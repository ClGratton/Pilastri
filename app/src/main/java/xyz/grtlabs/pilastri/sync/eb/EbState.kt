package xyz.grtlabs.pilastri.sync.eb

import android.content.Context
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.sync.auto.CredentialCipher
import xyz.grtlabs.pilastri.sync.auto.KeystoreCredentialCipher
import xyz.grtlabs.pilastri.sync.auto.PrefsSecretStore
import xyz.grtlabs.pilastri.sync.auto.SecretStore

/** An account of the linked session. [key] is stable across sessions (the identification hash), [uid] only within one. */
data class EbLinkedAccount(val uid: String, val identificationHash: String?, val label: String, val currency: String?) {
    val key: String get() = identificationHash ?: uid
}

/** The authorized session. Not a data class and redacted in [toString]: the session id is a credential for reading the accounts. */
class EbSessionState(
    val sessionId: String,
    val bankName: String,
    val country: String,
    val validUntil: Instant?,
    val createdAt: Instant,
    val requiredPsuHeaders: List<String>,
    val accounts: List<EbLinkedAccount>,
) {
    override fun toString() = "EbSessionState(***)"
}

/** An authorization started and not yet completed: the `state` the redirect must carry back. */
class EbPendingAuth(val state: String, val bankName: String, val country: String, val redirectUrl: String, val validUntil: Instant, val startedAt: Instant) {
    override fun toString() = "EbPendingAuth(***)"
}

/**
 * Everything the Enable Banking connector remembers between runs. App-private, encrypted with a Keystore key, never in the
 * archive, a backup, a log or AI context. [assignments] maps an account [EbLinkedAccount.key] to the app's own account id, so a
 * new session after the consent expired finds the same accounts again.
 */
data class EbLocal(
    val applicationId: String? = null,
    /** The redirect URL registered with the application (https; see BANK-INTEGRATIONS.md). */
    val redirectUrl: String? = null,
    /** One consent per bank (Aggiungi banca): a new session for a bank replaces that bank's earlier one (Rinnova). */
    val sessions: List<EbSessionState> = emptyList(),
    val pending: EbPendingAuth? = null,
    val assignments: Map<String, String> = emptyMap(),
    /** No read before this instant: the bank said "too many requests". */
    val rateLimitedUntil: Instant? = null,
) {
    override fun toString() = "EbLocal(***)"

    /** The first session: what code written for a single bank reads. */
    val session: EbSessionState? get() = sessions.firstOrNull()

    fun sessionFor(bankName: String): EbSessionState? = sessions.firstOrNull { sameBank(it.bankName, bankName) }

    fun withNewSession(session: EbSessionState): EbLocal =
        copy(sessions = sessions.filterNot { sameBank(it.bankName, session.bankName) } + session, pending = null, rateLimitedUntil = null)

    fun withoutSession(bankName: String): EbLocal = copy(sessions = sessions.filterNot { sameBank(it.bankName, bankName) }, pending = null)

    /** Sessions whose consent ends within [within] (or already ended): the ones that offer "Rinnova". An unknown end offers nothing. */
    fun needingRenewal(now: Instant, within: java.time.Duration = java.time.Duration.ofDays(7)): List<EbSessionState> =
        sessions.filter { it.validUntil != null && !it.validUntil.isAfter(now.plus(within)) }

    fun assign(accountKey: String, appAccountId: String?): EbLocal =
        copy(assignments = if (appAccountId == null) assignments - accountKey else assignments + (accountKey to appAccountId))
}

internal fun sameBank(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

/** Reads and writes [EbLocal] as one encrypted blob. A blob that cannot be decrypted (a Keystore reset) reads as empty: link again. */
class EbStateStore(private val store: SecretStore, private val cipher: CredentialCipher) {
    fun load(): EbLocal {
        val encoded = store.get(SLOT) ?: return EbLocal()
        return try {
            decode(String(cipher.decrypt(Base64.getDecoder().decode(encoded), AAD), StandardCharsets.UTF_8))
        } catch (_: Exception) {
            EbLocal()
        }
    }

    fun save(state: EbLocal) {
        store.put(SLOT, Base64.getEncoder().encodeToString(cipher.encrypt(encode(state).toByteArray(StandardCharsets.UTF_8), AAD)))
    }

    /** Forgets the session, the pending authorization and the assignments; the application id and redirect stay (they belong to the registration). */
    fun forgetLink() = save(load().copy(sessions = emptyList(), pending = null, assignments = emptyMap(), rateLimitedUntil = null))

    /** Forgets everything, the registration included. */
    fun clear() = store.remove(SLOT)

    companion object {
        private const val SLOT = "eb.state.v1"
        private val AAD = "pilastri.eb.state.v1".toByteArray(StandardCharsets.UTF_8)

        /** The real store, or null when the device Keystore is unusable. */
        fun createOrNull(context: Context): EbStateStore? = try {
            val cipher = KeystoreCredentialCipher("pilastri.sync.eb.v1")
            cipher.encrypt(ByteArray(1), ByteArray(0))
            EbStateStore(PrefsSecretStore(context.applicationContext.getSharedPreferences("pilastri_eb_state", Context.MODE_PRIVATE)), cipher)
        } catch (_: Exception) {
            null
        }

        internal fun encode(s: EbLocal): String {
            val o = JSONObject().put("v", 1)
            s.applicationId?.let { o.put("applicationId", it) }
            s.redirectUrl?.let { o.put("redirectUrl", it) }
            s.rateLimitedUntil?.let { o.put("rateLimitedUntil", it.toString()) }
            if (s.sessions.isNotEmpty()) o.put("sessions", JSONArray(s.sessions.map { x ->
                JSONObject()
                    .put("sessionId", x.sessionId).put("bankName", x.bankName).put("country", x.country)
                    .put("createdAt", x.createdAt.toString())
                    .put("requiredPsuHeaders", JSONArray(x.requiredPsuHeaders))
                    .apply { x.validUntil?.let { put("validUntil", it.toString()) } }
                    .put("accounts", JSONArray(x.accounts.map { a ->
                        JSONObject().put("uid", a.uid).put("label", a.label)
                            .apply { a.identificationHash?.let { put("identificationHash", it) }; a.currency?.let { put("currency", it) } }
                    }))
            }))
            s.pending?.let { p ->
                o.put("pending", JSONObject().put("state", p.state).put("bankName", p.bankName).put("country", p.country)
                    .put("redirectUrl", p.redirectUrl).put("validUntil", p.validUntil.toString()).put("startedAt", p.startedAt.toString()))
            }
            o.put("assignments", JSONObject(s.assignments))
            return o.toString()
        }

        internal fun decode(text: String): EbLocal {
            val o = JSONObject(text)
            require(o.optInt("v") == 1)
            fun JSONObject.opt2(k: String): String? = if (isNull(k)) null else getString(k)
            fun session(x: JSONObject): EbSessionState {
                val accounts = x.getJSONArray("accounts")
                return EbSessionState(
                    sessionId = x.getString("sessionId"), bankName = x.getString("bankName"), country = x.getString("country"),
                    validUntil = x.opt2("validUntil")?.let(Instant::parse), createdAt = Instant.parse(x.getString("createdAt")),
                    requiredPsuHeaders = x.getJSONArray("requiredPsuHeaders").let { a -> (0 until a.length()).map { a.getString(it) } },
                    accounts = (0 until accounts.length()).map { i ->
                        accounts.getJSONObject(i).let { a -> EbLinkedAccount(a.getString("uid"), a.opt2("identificationHash"), a.getString("label"), a.opt2("currency")) }
                    },
                )
            }
            // "session" is the earlier single-bank form: it reads as a list of one.
            val sessions = o.optJSONArray("sessions")?.let { a -> (0 until a.length()).map { session(a.getJSONObject(it)) } }
                ?: o.optJSONObject("session")?.let { listOf(session(it)) }.orEmpty()
            val pending = o.optJSONObject("pending")?.let { p ->
                EbPendingAuth(p.getString("state"), p.getString("bankName"), p.getString("country"), p.getString("redirectUrl"),
                    Instant.parse(p.getString("validUntil")), Instant.parse(p.getString("startedAt")))
            }
            val assignments = o.optJSONObject("assignments")?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } }.orEmpty()
            return EbLocal(o.opt2("applicationId"), o.opt2("redirectUrl"), sessions, pending, assignments, o.opt2("rateLimitedUntil")?.let(Instant::parse))
        }
    }
}
