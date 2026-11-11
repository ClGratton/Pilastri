package xyz.grtlabs.pilastri.sync.gmail

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the user must set up once in Google Cloud; shown by the app when Google rejects the app's identity. Package: [PACKAGE]. */
internal object GmailSetup {
    const val PACKAGE = "xyz.grtlabs.pilastri"
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    val NOT_CONFIGURED_IT: String =
        "Gmail non è ancora configurato per questa app. Una sola volta, nella console Google Cloud (console.cloud.google.com): " +
            "1) crea un progetto; 2) in «API e servizi» abilita «Gmail API»; " +
            "3) in «Google Auth Platform» imposta pubblico «Esterno», stato «Test», e aggiungi il tuo indirizzo Gmail tra gli utenti di prova; " +
            "4) in «Accesso ai dati» aggiungi l'ambito gmail.readonly; " +
            "5) in «Client» crea un client OAuth di tipo «Android» con nome pacchetto $PACKAGE e l'impronta SHA-1 del certificato con cui è firmata questa build " +
            "(per le build di debug: keytool -list -v -keystore %USERPROFILE%\\.android\\debug.keystore -alias androiddebugkey -storepass android). " +
            "Poi riprova. In modalità Test l'autorizzazione scade dopo 7 giorni e va rinnovata."

    val API_DISABLED_IT: String =
        "L'API di Gmail non è abilitata nel progetto Google Cloud dell'app. Aprila in «API e servizi» → «Libreria» → «Gmail API» → Abilita, attendi un minuto e riprova."
}

internal sealed interface AuthOutcome {
    data class Token(val value: String) : AuthOutcome
    data class Consent(val intent: PendingIntent) : AuthOutcome
    data class NotConfigured(val message: String) : AuthOutcome
    data class Failed(val message: String) : AuthOutcome
}

/**
 * Google Identity Services `AuthorizationClient` for the read-only Gmail scope. Play services keeps the grant and mints a short-lived access
 * token on each [accessToken] call (about an hour); this app stores no token and no refresh token, only the local "linked" flag.
 */
internal object GmailAuth {
    private const val PREFS = "pilastri_gmail"
    private const val LINKED = "linked"

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GmailSetup.SCOPE))).build()

    fun isLinked(ctx: Context): Boolean = runCatching { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LINKED, false) }.getOrDefault(false)

    fun setLinked(ctx: Context, linked: Boolean) {
        runCatching { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LINKED, linked).apply() }
    }

    /** Silent when the grant exists, otherwise [AuthOutcome.Consent]. Maps Google's "client not configured" errors to [AuthOutcome.NotConfigured]. */
    suspend fun accessToken(ctx: Context): AuthOutcome = try {
        val result = Identity.getAuthorizationClient(ctx.applicationContext).authorize(request()).await()
        val pending = result.pendingIntent
        when {
            result.hasResolution() && pending != null -> AuthOutcome.Consent(pending)
            result.hasResolution() -> AuthOutcome.Failed("Google richiede il consenso ma non l'ha proposto: riprova.")
            result.accessToken == null || !scopeGranted(result.grantedScopes) ->
                AuthOutcome.Failed("Il permesso di lettura di Gmail non è stato concesso. Ripeti il collegamento e accetta la lettura.")
            else -> AuthOutcome.Token(result.accessToken!!)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        mapFailure(e.statusCode, e.message)
    } catch (e: Exception) {
        mapFailure(null, e.message)
    }

    /** The consent screen returned. True when Google granted the scope; the grant itself stays with Play services. */
    fun onConsentResult(ctx: Context, data: android.content.Intent?): Boolean {
        if (data == null) return false
        val granted = try {
            val result = Identity.getAuthorizationClient(ctx.applicationContext).getAuthorizationResultFromIntent(data)
            result.accessToken != null && scopeGranted(result.grantedScopes)
        } catch (_: ApiException) {
            false
        } catch (_: Exception) {
            false
        }
        setLinked(ctx, granted)
        return granted
    }

    /** After a 401: drop the cached token so the next [accessToken] mints a fresh one. */
    suspend fun invalidate(ctx: Context, token: String) {
        try {
            Identity.getAuthorizationClient(ctx.applicationContext).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Best effort: forget the flag first, then clear the cached token and revoke the grant if Google lets us (needs the signed-in account). */
    fun disconnect(ctx: Context) {
        setLinked(ctx, false)
        try {
            val client = Identity.getAuthorizationClient(ctx.applicationContext)
            client.authorize(request()).addOnSuccessListener { result ->
                if (result.hasResolution()) return@addOnSuccessListener // nothing is granted: nothing to revoke
                result.accessToken?.let { client.clearToken(ClearTokenRequest.builder().setToken(it).build()) }
                val account = result.toGoogleSignInAccount()?.account ?: return@addOnSuccessListener
                client.revokeAccess(RevokeAccessRequest.builder().setScopes(listOf(Scope(GmailSetup.SCOPE))).setAccount(account).build())
            }
        } catch (_: Exception) {
        }
    }

    /** Google lists the granted scope URIs; an empty list alongside a token is treated as granted (only a list that omits the scope is a refusal). */
    internal fun scopeGranted(granted: List<String>?): Boolean = granted.isNullOrEmpty() || GmailSetup.SCOPE in granted

    /** DEVELOPER_ERROR (10, "[28444] Developer console is not set up correctly") means no Android OAuth client matches package + SHA-1. */
    internal fun mapFailure(code: Int?, message: String?): AuthOutcome {
        val text = message.orEmpty()
        return when {
            code == 10 || text.contains("28444") || text.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                text.contains("Developer console is not set up correctly", ignoreCase = true) -> AuthOutcome.NotConfigured(GmailSetup.NOT_CONFIGURED_IT)
            code == 7 -> AuthOutcome.Failed("Connessione a Google non riuscita: controlla la rete e riprova.")
            code == 16 || code == 12501 || code == 12502 -> AuthOutcome.Failed("Autorizzazione Google annullata.")
            code == 1 || code == 2 || code == 3 || code == 9 || code == 17 ->
                AuthOutcome.Failed("Servizi Google Play non disponibili o da aggiornare su questo dispositivo.")
            else -> AuthOutcome.Failed("Autorizzazione Google non riuscita" + (code?.let { " (codice $it)" } ?: "") + ".")
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
