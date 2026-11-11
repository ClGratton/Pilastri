package xyz.grtlabs.pilastri.ui.setup

import xyz.grtlabs.pilastri.ai.ChatGptSignIn
import xyz.grtlabs.pilastri.sync.gmail.AuthOutcome

/**
 * CR-86, every action confirms its result: a control that starts an action renders one of these on itself or right next to it.
 * Silence after a tap is a defect. [Failed] carries a short reason and optionally the steps behind a "?".
 */
internal sealed interface ActionOutcome {
    data object Idle : ActionOutcome
    data class Working(val text: String) : ActionOutcome
    data class Done(val text: String) : ActionOutcome
    data class Failed(val reason: String, val steps: String? = null) : ActionOutcome

    companion object {
        /** Gmail authorization result to what the Posta row shows. A pending consent screen is "working", never silence. */
        fun ofGmail(o: AuthOutcome): ActionOutcome = when (o) {
            is AuthOutcome.Token -> Done("Gmail collegato")
            is AuthOutcome.Consent -> Working("In attesa del consenso Google…")
            is AuthOutcome.NotConfigured -> Failed("Configurazione Google mancante", o.message)
            is AuthOutcome.Failed -> Failed(o.message)
        }
        /** ChatGPT sign-in to what the assistant row shows. Whose error it is stays in the words: the person's, OpenAI's, or Pilastri's. */
        fun ofChatGpt(running: Boolean, o: ChatGptSignIn.Outcome?): ActionOutcome = when {
            running -> Working("Accesso a ChatGPT nel browser…")
            else -> ChatGptSignIn.failureText(o)?.let { Failed(it) } ?: Idle
        }
        fun ofConsentResult(granted: Boolean): ActionOutcome =
            if (granted) Done("Gmail collegato") else Failed("Collegamento Gmail non completato: riprova e accetta la lettura.")
        fun ofNotifications(granted: Boolean, asked: Boolean): ActionOutcome = when {
            granted -> Done("Notifiche consentite")
            asked -> Failed("Notifiche non ancora consentite: attiva Pilastri nell'elenco.")
            else -> Idle
        }
    }
}

/** The outcome status line (INFO): codes and states only, never tokens, accounts, e-mails or links. Kept out of the link-path files (EbLinkChannelsTest). */
internal fun logOutcome(tag: String, line: String) { android.util.Log.i(tag, line) }
