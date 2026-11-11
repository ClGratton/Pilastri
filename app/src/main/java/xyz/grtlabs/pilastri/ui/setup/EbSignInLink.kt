package xyz.grtlabs.pilastri.ui.setup

import java.net.URI

/**
 * Enable Banking signs in by an e-mail link. Opened from the mail app it lands in the default browser, whose session the app's
 * WebView never sees; so the person copies the link and the app loads it in its own WebView. Only an https link on
 * enablebanking.com is ever taken from the clipboard (a clipboard is untrusted input).
 */
object EbSignInLink {
    fun fromClipboard(text: CharSequence?): String? {
        val t = text?.toString()?.trim() ?: return null
        if (t.isEmpty() || t.length > 2000 || t.any { it.isWhitespace() }) return null
        val u = try { URI(t) } catch (_: Exception) { return null }
        val host = u.host?.lowercase() ?: return null
        if (u.scheme?.lowercase() != "https" || u.userInfo != null) return null
        return if (host == "enablebanking.com" || host.endsWith(".enablebanking.com")) t else null
    }

    /** One line, shown only on the sign-in step (setup copy rules). */
    const val INSTRUCTION = "Dall'e-mail tieni premuto il link, copialo e torna qui: si apre in questa pagina."
}
