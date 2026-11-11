package xyz.grtlabs.pilastri.ai

import android.content.Context

/**
 * The AI a connector may use on its own (the Gmail fallback for unknown senders): only an API-key route with a saved key and the
 * user's separate mail opt-in for that provider ([AiSettings.mailOptedIn], "Rate da email sconosciute" in Collegamenti). The chat
 * consent does not cover mail. The account routes and the routine cannot serve it (no tap, no tool call). The route the Agente
 * uses is preferred when it is an API route; otherwise any opted-in API route. Null when none qualifies.
 */
object AiForConnectors {
    class Choice(val transport: AiTransport, val model: String, val provider: AiProvider)

    fun choice(context: Context): Choice? {
        val settings = AiSettings.create(context)
        val vault = AiVault.createOrNull(context) ?: return null
        val provider = pick(settings) { vault.has(it) } ?: return null
        val key = vault.get(provider) ?: return null
        return Choice(AiTransports.create(provider, key), settings.model(provider), provider)
    }

    /** Pure selection: the first provider with a saved key and the mail opt-in on, the Agente's API route first. */
    internal fun pick(settings: AiSettings, hasKey: (AiProvider) -> Boolean): AiProvider? =
        (listOfNotNull(settings.route.api) + AiProvider.entries).distinct().firstOrNull { settings.mailOptedIn(it) && hasKey(it) }
}
