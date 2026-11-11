package xyz.grtlabs.pilastri.sync.auto

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.ImportProvider

/** Which fields a sign-in form shows right now. */
enum class LoginStep(val wire: String) {
    USERNAME("username"), PASSWORD("password"), BOTH("both");

    companion object { fun of(wire: String?): LoginStep? = entries.firstOrNull { it.wire == wire } }
}

/** Strong customer authentication: a code typed into the provider's page, an approval in the provider's app, or a choice of method. */
enum class ScaKind(val wire: String) {
    CODE("code"), PUSH("push"), CHOICE("choice");

    companion object { fun of(wire: String?): ScaKind? = entries.firstOrNull { it.wire == wire } }
}

/**
 * What the sign-in script looks for, as data. Tokens and phrases are compared after [LoginMatcher.norm] (accents removed, lower
 * case, every non-alphanumeric run turned into one space, camelCase split) and match on word boundaries: the token `pin` matches
 * "PIN" and "codice pin", not "shopping". [challengeMarkers] are raw lower-case substrings of iframe sources and of id/class/name
 * values; [challengeIgnore] removes passive bot scoring (the invisible reCAPTCHA badge), which is not a challenge for the person.
 *
 * The PayPal and isybank values are UNVERIFIED guesses from the sites' public pages; fix them here, not in the script.
 */
data class LoginRules(
    val username: List<String> = emptyList(),
    val password: List<String> = emptyList(),
    val otp: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val submit: List<String> = emptyList(),
    val next: List<String> = emptyList(),
    /** Buttons never clicked: sign-up, forgotten password, cancel. */
    val avoid: List<String> = emptyList(),
    val scaCode: List<String> = emptyList(),
    val scaPush: List<String> = emptyList(),
    val scaChoice: List<String> = emptyList(),
    val challengeMarkers: List<String> = emptyList(),
    val challengeIgnore: List<String> = emptyList(),
    val challengePhrases: List<String> = emptyList(),
    val errorPhrases: List<String> = emptyList(),
    /** Labels of a checkbox that keeps the sign-in or the device trusted ("stay signed in", "remember this device"): ticked when found, never the verification itself. */
    val trust: List<String> = emptyList(),
    /** Words that veto a [trust] checkbox (marketing, saving a card). */
    val trustExclude: List<String> = emptyList(),
) {
    operator fun plus(other: LoginRules) = LoginRules(
        username + other.username, password + other.password, otp + other.otp, exclude + other.exclude, submit + other.submit,
        next + other.next, avoid + other.avoid, scaCode + other.scaCode, scaPush + other.scaPush, scaChoice + other.scaChoice,
        challengeMarkers + other.challengeMarkers, challengeIgnore + other.challengeIgnore, challengePhrases + other.challengePhrases,
        errorPhrases + other.errorPhrases, trust + other.trust, trustExclude + other.trustExclude,
    )

    /** Normalized lists as the script reads them. */
    fun toJson(): String {
        fun words(list: List<String>) = JSONArray(list.map(LoginMatcher::norm).filter { it.isNotEmpty() }.distinct())
        fun raw(list: List<String>) = JSONArray(list.map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct())
        return JSONObject()
            .put("username", words(username)).put("password", words(password)).put("otp", words(otp)).put("exclude", words(exclude))
            .put("submit", words(submit)).put("next", words(next)).put("avoid", words(avoid))
            .put("scaCode", words(scaCode)).put("scaPush", words(scaPush)).put("scaChoice", words(scaChoice))
            .put("challengeMarkers", raw(challengeMarkers)).put("challengeIgnore", raw(challengeIgnore))
            .put("challengePhrases", words(challengePhrases)).put("errorPhrases", words(errorPhrases))
            .put("trust", words(trust)).put("trustExclude", words(trustExclude))
            .toString()
    }
}

object LoginRulesTable {
    val COMMON = LoginRules(
        username = listOf("username", "user name", "user", "utente", "nome utente", "email", "e mail", "indirizzo email", "login", "user id", "codice titolare", "codice cliente", "titolare"),
        password = listOf("password", "pin", "codice pin", "parola chiave", "passcode"),
        otp = listOf("otp", "one time code", "one time password", "codice otp", "codice sms", "codice di sicurezza", "codice di verifica", "codice ricevuto", "security code", "verification code", "sms code"),
        exclude = listOf("search", "cerca", "ricerca", "newsletter", "coupon", "promo", "captcha"),
        submit = listOf("accedi", "entra", "login", "log in", "sign in", "conferma", "continua"),
        next = listOf("avanti", "next", "prosegui", "continue"),
        avoid = listOf("registrati", "crea account", "crea un account", "apri conto", "sign up", "password dimenticata", "hai dimenticato", "recupera", "forgot", "annulla", "cancel", "indietro", "back"),
        scaCode = listOf("ti abbiamo inviato un codice", "inserisci il codice", "abbiamo inviato un codice", "codice di sicurezza", "enter the code", "we sent a code"),
        scaPush = listOf("approva l accesso", "autorizza l accesso", "conferma dall app", "apri l app", "notifica sul tuo smartphone", "notifica push", "approve in the app", "check your phone"),
        scaChoice = listOf("conferma la tua identita", "verifica la tua identita", "scegli come", "confirm it s you", "verify it s you"),
        challengeMarkers = listOf("captcha", "arkoselabs", "funcaptcha", "cf-turnstile", "challenges.cloudflare.com"),
        challengeIgnore = listOf("size=invisible", "grecaptcha-badge", "grecaptcha-logo"),
        challengePhrases = listOf("non sono un robot", "i m not a robot", "dimostra di non essere un robot", "verifica di non essere un robot", "controllo di sicurezza", "security check"),
        errorPhrases = listOf("credenziali non valide", "credenziali errate", "password errata", "password non corretta", "password non valida", "dati non corretti", "dati errati", "non corrispondono", "utenza bloccata", "account bloccato", "accesso bloccato", "incorrect password", "wrong password", "some of your info isn t correct", "alcune delle informazioni inserite non sono corrette"),
        trust = listOf("resta connesso", "rimani connesso", "mantieni l accesso", "mantieni l accesso", "ricorda questo dispositivo", "ricorda dispositivo", "ricorda il dispositivo", "considera attendibile", "dispositivo attendibile", "dispositivo di fiducia", "non chiedermelo", "non chiedere di nuovo", "stay logged in", "stay signed in", "keep me logged in", "keep me signed in", "remember this device", "remember me", "trust this device", "don t ask again"),
        trustExclude = listOf("newsletter", "marketing", "promozion", "offerte", "carta", "card", "password", "accetto", "termini", "terms", "privacy", "cookie"),
    )

    /** PayPal: e-mail first, then the password on the next view; 2FA is an SMS code or a choice of method. UNVERIFIED. */
    val PAYPAL = COMMON + LoginRules(
        username = listOf("email o numero di cellulare", "email or mobile number", "login email"),
        password = listOf("login password"),
        otp = listOf("ci otp code", "otp code"),
        scaChoice = listOf("ricevi un codice", "get a code", "scegli un modo per confermare"),
    )

    /** isybank: titolare code and PIN, then approval in the isybank app. UNVERIFIED. */
    val ISYBANK = COMMON + LoginRules(
        username = listOf("codice titolare isybank", "codice utente"),
        password = listOf("pin isybank"),
        scaPush = listOf("autorizza dall app isybank", "conferma con l app isybank", "app isybank"),
    )

    fun forProvider(p: ImportProvider): LoginRules = when (p) {
        ImportProvider.PAYPAL -> PAYPAL
        ImportProvider.ISYBANK -> ISYBANK
        else -> COMMON
    }
}

/** One `<input>` as the script sees it. [label] joins aria-label, aria-labelledby text, `<label>` text and title. */
data class FormField(
    val type: String = "text",
    val name: String = "",
    val id: String = "",
    val autocomplete: String = "",
    val label: String = "",
    val placeholder: String = "",
    val maxLength: Int? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val readOnly: Boolean = false,
    /** Index of the owning form in document order, -1 without a form. */
    val form: Int = -1,
)

/** An iframe or an element with id/class/name, reduced to what the challenge rule reads. */
data class Marked(val value: String, val visible: Boolean = true)

/** Everything the matcher reads from a page. [text] is the visible body text; [alertText] the text of visible alerts. */
data class PageSignals(
    val fields: List<FormField>,
    val frames: List<Marked> = emptyList(),
    val markers: List<Marked> = emptyList(),
    val text: String = "",
    val alertText: String = "",
)

sealed interface PageVerdict {
    data class Form(val step: LoginStep, val username: Int?, val password: Int?) : PageVerdict
    data class Sca(val kind: ScaKind) : PageVerdict
    data object Challenge : PageVerdict
    data object Nothing : PageVerdict
}

/**
 * The matching rules of the sign-in script ([LoginAutofill.script]), in Kotlin, so they can be tested on synthetic forms. The two
 * implementations must stay in step: change both or neither.
 */
object LoginMatcher {
    private val MARKS = Regex("\\p{M}+")
    private val CAMEL = Regex("([a-z])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val TEXTUAL = setOf("text", "email", "tel", "password", "number")

    fun norm(s: String?): String =
        Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD).replace(MARKS, "").replace(CAMEL, "$1 $2")
            .lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    /** [hay] already normalized; tokens are normalized here. */
    fun has(hay: String, tokens: List<String>): Boolean {
        val h = " $hay "
        return tokens.any { t -> norm(t).let { it.isNotEmpty() && h.contains(" $it ") } }
    }

    private fun hay(f: FormField) = norm(listOf(f.name, f.id, f.label, f.placeholder, f.autocomplete).joinToString(" "))
    private fun ac(f: FormField) = f.autocomplete.lowercase(Locale.ROOT).split(Regex("\\s+"))
    private fun type(f: FormField) = f.type.lowercase(Locale.ROOT).ifEmpty { "text" }
    fun usable(f: FormField) = type(f) in TEXTUAL && f.visible && f.enabled && !f.readOnly

    fun isOtp(f: FormField, r: LoginRules): Boolean =
        usable(f) && !has(hay(f), r.exclude) && ("one-time-code" in ac(f) || has(hay(f), r.otp))

    fun passwordScore(f: FormField, r: LoginRules): Int {
        if (!usable(f) || has(hay(f), r.exclude) || isOtp(f, r)) return 0
        val t = type(f)
        if (t == "password" || "current-password" in ac(f)) return 3
        if ((t == "tel" || t == "number" || t == "text") && has(hay(f), r.password)) return 2
        return 0
    }

    fun usernameScore(f: FormField, r: LoginRules): Int {
        if (!usable(f) || has(hay(f), r.exclude) || isOtp(f, r) || passwordScore(f, r) > 0) return 0
        val t = type(f)
        if ("username" in ac(f) || "email" in ac(f) || t == "email") return 3
        if (has(hay(f), r.username)) return 2
        if (t == "text" || t == "tel" || t == "number") return 1
        return 0
    }

    fun isChallenge(s: PageSignals, r: LoginRules): Boolean {
        fun hit(m: Marked): Boolean {
            val v = m.value.lowercase(Locale.ROOT)
            return m.visible && r.challengeMarkers.any { v.contains(it.lowercase(Locale.ROOT)) } && r.challengeIgnore.none { v.contains(it.lowercase(Locale.ROOT)) }
        }
        return s.frames.any(::hit) || s.markers.any(::hit) || has(norm(s.text), r.challengePhrases)
    }

    fun showsError(s: PageSignals, r: LoginRules): Boolean = has(norm(s.alertText), r.errorPhrases)

    fun scan(s: PageSignals, r: LoginRules): PageVerdict {
        if (isChallenge(s, r)) return PageVerdict.Challenge
        val fs = s.fields
        var otp = 0
        var boxes = 0
        var pw = -1
        var pwScore = 0
        fs.forEachIndexed { i, f ->
            if (isOtp(f, r)) otp++
            if (usable(f) && type(f) != "password" && f.maxLength == 1) boxes++
            val score = passwordScore(f, r)
            if (score > pwScore) { pwScore = score; pw = i }
        }
        if (otp > 0 || boxes >= 4) return PageVerdict.Sca(ScaKind.CODE)
        var user = -1
        var userScore = 0
        fs.forEachIndexed { i, f ->
            var score = usernameScore(f, r)
            if (score == 1 && !(pw >= 0 && i < pw && f.form == fs[pw].form)) score = 0
            if (score > userScore) { userScore = score; user = i }
        }
        if (pw >= 0) return PageVerdict.Form(if (user >= 0) LoginStep.BOTH else LoginStep.PASSWORD, user.takeIf { it >= 0 }, pw)
        if (user >= 0 && userScore >= 2) return PageVerdict.Form(LoginStep.USERNAME, user, null)
        val text = norm(s.text)
        return when {
            has(text, r.scaPush) -> PageVerdict.Sca(ScaKind.PUSH)
            has(text, r.scaChoice) -> PageVerdict.Sca(ScaKind.CHOICE)
            has(text, r.scaCode) -> PageVerdict.Sca(ScaKind.CODE)
            else -> PageVerdict.Nothing
        }
    }
}
