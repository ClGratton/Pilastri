package xyz.grtlabs.pilastri.sync.auto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportProvider

/** Synthetic login forms only: the field shapes are guesses written for the tests, not copies of the providers' pages. */
class LoginAutofillTest {
    private val paypal = LoginRulesTable.PAYPAL
    private val isy = LoginRulesTable.ISYBANK
    private fun scan(html: String, rules: LoginRules = paypal) = LoginMatcher.scan(SyntheticHtml.signals(html), rules)

    // ---- field detection ----

    @Test fun emailStepWithTheHiddenPasswordIsTheUsernameStep() {
        val v = scan("""<form><input type="email" id="email" name="login_email" autocomplete="username" placeholder="Email o numero di cellulare">
            <input type="password" id="password" style="display: none"><button>Avanti</button></form>""")
        assertEquals(PageVerdict.Form(LoginStep.USERNAME, 0, null), v)
    }

    @Test fun combinedFormFindsBothByAutocompleteAndType() {
        val v = scan("""<input type="search" name="q" placeholder="Cerca"><form>
            <input type="text" name="field_a1" autocomplete="username"><input type="password" name="field_b2"><button type="submit">Accedi</button></form>""")
        assertEquals(PageVerdict.Form(LoginStep.BOTH, 1, 2), v)
    }

    @Test fun labelsFindATitolareCodeAndANumericPinThatIsNotTypePassword() {
        val v = scan("""<form><label for="u1">Codice titolare</label><input id="u1" type="text">
            <label for="p1">PIN</label><input id="p1" type="tel" maxlength="8"><button>Entra</button></form>""", isy)
        assertEquals(PageVerdict.Form(LoginStep.BOTH, 0, 1), v)
    }

    @Test fun anUnlabelledTextFieldCountsOnlyBeforeThePasswordOfTheSameForm() {
        val before = scan("""<form><input type="text" name="a"><input type="password" name="b"></form>""")
        assertEquals(PageVerdict.Form(LoginStep.BOTH, 0, 1), before)
        val elsewhere = scan("""<form><input type="password" name="b"></form><form><input type="text" name="a"></form>""")
        assertEquals(PageVerdict.Form(LoginStep.PASSWORD, null, 0), elsewhere)
    }

    @Test fun passwordAloneIsThePasswordStep() {
        assertEquals(PageVerdict.Form(LoginStep.PASSWORD, null, 0), scan("""<input type="password" id="password" aria-label="Password">"""))
    }

    @Test fun tokensMatchWholeWordsOnly() {
        // "shopping" contains "pin" but is not a PIN field; a lone text field without a password is no form.
        assertEquals(PageVerdict.Nothing, scan("""<input type="text" name="shopping_list">"""))
        assertEquals(PageVerdict.Form(LoginStep.PASSWORD, null, 0), scan("""<input type="tel" name="pinCode">""", isy))
    }

    @Test fun disabledReadonlyAndHiddenFieldsAreIgnored() {
        val v = scan("""<input type="email" name="email" readonly><input type="hidden" name="password"><input type="password" disabled>""")
        assertEquals(PageVerdict.Nothing, v)
    }

    @Test fun oneTimeCodeFieldsAndDigitBoxesAre2fa() {
        assertEquals(PageVerdict.Sca(ScaKind.CODE), scan("""<input type="text" autocomplete="one-time-code">"""))
        assertEquals(PageVerdict.Sca(ScaKind.CODE), scan("""<label for="c">Codice di sicurezza</label><input id="c" type="tel">"""))
        val boxes = (0..5).joinToString("") { """<input type="tel" id="ci-otpCode-$it" maxlength="1">""" }
        assertEquals(PageVerdict.Sca(ScaKind.CODE), scan(boxes))
    }

    @Test fun pushAndChoicePagesAreReadFromTheText() {
        assertEquals(PageVerdict.Sca(ScaKind.PUSH), scan("""<div><p>Apri l'app isybank e autorizza l'accesso.</p></div>""", isy))
        assertEquals(PageVerdict.Sca(ScaKind.CHOICE), scan("""<h1>Conferma la tua identità</h1><input type="radio" name="m"><button>Avanti</button>"""))
    }

    @Test fun aVisibleCaptchaIsAChallengeButThePassiveBadgeIsNot() {
        val badge = """<iframe src="https://www.google.com/recaptcha/api2/anchor?k=x&size=invisible"></iframe><div class="grecaptcha-badge"></div>
            <input type="email" name="email"><input type="password" name="pw">"""
        assertEquals(LoginStep.BOTH, (scan(badge) as PageVerdict.Form).step)
        val visible = """<iframe src="https://www.google.com/recaptcha/api2/bframe?k=x"></iframe><input type="password">"""
        assertEquals(PageVerdict.Challenge, scan(visible))
        assertEquals(PageVerdict.Challenge, scan("""<p>Dimostra di non essere un robot</p>"""))
        val hiddenFrame = """<iframe style="display:none" src="https://hcaptcha.com/checkbox"></iframe><input type="password">"""
        assertEquals(LoginStep.PASSWORD, (scan(hiddenFrame) as PageVerdict.Form).step)
    }

    @Test fun signInErrorsAreReadFromAlerts() {
        val s = SyntheticHtml.signals("""<div role="alert">Alcune delle informazioni inserite non sono corrette.</div><input type="password">""")
        assertTrue(LoginMatcher.showsError(s, paypal))
        assertFalse(LoginMatcher.showsError(SyntheticHtml.signals("""<div role="alert">Benvenuto</div>"""), paypal))
    }

    // ---- script and messages ----

    @Test fun theScriptCarriesTheRulesButNoCredentialsAndReadsOnlyDeclaredRules() {
        val script = LoginAutofill.script(paypal)
        assertTrue(script.contains("window.PilastriLogin"))
        assertTrue(script.contains("\"email o numero di cellulare\""))
        assertFalse(script.contains("__RULES__") || script.contains("__BRIDGE__"))
        assertFalse(script.contains(T.PASSWORD) || script.contains("addJavascriptInterface"))
        val keys = JSONObject(paypal.toJson()).keys().asSequence().toSet()
        val used = Regex("R\\.([A-Za-z]+)").findAll(script).map { it.groupValues[1] }.toSet()
        assertTrue("script reads undeclared rules: ${used - keys}", keys.containsAll(used))
        assertTrue(script.contains("'$1 $2'"))
    }

    @Test fun rulesAreNormalizedForTheScript() {
        val json = JSONObject(LoginRules(username = listOf("Nome Utente", "E-mail"), challengeMarkers = listOf("CAPTCHA")).toJson())
        assertEquals("nome utente", json.getJSONArray("username").getString(0))
        assertEquals("e mail", json.getJSONArray("username").getString(1))
        assertEquals("captcha", json.getJSONArray("challengeMarkers").getString(0))
    }

    @Test fun pageMessagesAreParsedStrictly() {
        assertEquals(LoginAutofill.PageMessage.Form(LoginStep.BOTH), LoginAutofill.parse("""{"t":"form","step":"both"}"""))
        assertEquals(LoginAutofill.PageMessage.Sca(ScaKind.PUSH), LoginAutofill.parse("""{"t":"sca","kind":"push"}"""))
        assertEquals(LoginAutofill.PageMessage.Challenge, LoginAutofill.parse("""{"t":"challenge"}"""))
        assertNull(LoginAutofill.parse("""{"t":"form","step":"everything"}"""))
        assertNull(LoginAutofill.parse("""{"t":"eval","code":"x"}"""))
        assertNull(LoginAutofill.parse("not json"))
        assertNull(LoginAutofill.parse("""{"t":"none","pad":"${"x".repeat(5000)}"}"""))
        val captured = LoginAutofill.parse("""{"t":"captured","u":"${T.USER}","p":null}""") as LoginAutofill.PageMessage.Captured
        assertEquals(T.USER, captured.username)
        assertNull(captured.password)
        assertEquals("Captured(***)", captured.toString())
        assertNull(LoginAutofill.parse("""{"t":"captured","u":null,"p":null}"""))
    }

    @Test fun fillMessagesCarryOnlyTheStepsFields() {
        val login = SavedLogin(T.USER, "q\"uote\\")
        val u = JSONObject(LoginAutofill.fillMessage(LoginStep.USERNAME, login))
        assertEquals(T.USER, u.getString("u"))
        assertFalse(u.has("p"))
        val p = JSONObject(LoginAutofill.fillMessage(LoginStep.PASSWORD, login))
        assertFalse(p.has("u"))
        assertEquals("q\"uote\\", p.getString("p"))
        val both = JSONObject(LoginAutofill.fillMessage(LoginStep.BOTH, login))
        assertTrue(both.has("u") && both.has("p"))
    }

    @Test fun originsAreExactAndPagesClassify() {
        val site = ProviderSites.PAYPAL
        assertTrue(site.isLoginOrigin("https://www.paypal.com"))
        assertTrue(site.isLoginOrigin("https://www.paypal.com/"))
        assertFalse(site.isLoginOrigin("https://evil.paypal.com.example.test"))
        assertFalse(site.isLoginOrigin("http://www.paypal.com"))
        assertFalse(site.isLoginOrigin("https://c.paypal.com"))
        assertEquals(SitePage.LOGIN, site.classify("https://www.paypal.com/signin?returnUri=x"))
        assertEquals(SitePage.CHALLENGE, site.classify("https://www.paypal.com/auth/validatecaptcha"))
        assertEquals(SitePage.SIGNED_IN, site.classify("https://www.paypal.com/myaccount/summary"))
        assertEquals(SitePage.PROVIDER_OTHER, site.classify("https://www.paypal.com/signinx"))
        assertEquals(SitePage.FOREIGN, site.classify("https://www.paypal.com.example.test/myaccount"))
        assertEquals(SitePage.FOREIGN, site.classify("https://user@www.paypal.com/myaccount"))
        assertEquals(SitePage.FOREIGN, site.classify("https://www.paypal.com:8443/myaccount"))
        assertEquals(LoginRulesTable.ISYBANK, LoginRulesTable.forProvider(ImportProvider.ISYBANK))
    }
}
