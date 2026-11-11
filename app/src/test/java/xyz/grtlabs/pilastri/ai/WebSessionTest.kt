package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session state by cookie name, route choice and its persistence, and the reply watcher. No WebView, no network. */
class WebSessionTest {
    @Test fun sessionIsSeenFromCookieNamesOnly() {
        val claude = WebAccount.CLAUDE.sessionCookies
        assertTrue(WebSessions.hasSession("a=1; sessionKey=sk-SYNTHETIC; b=2", claude))
        assertFalse(WebSessions.hasSession("a=1; notsessionKey=x; b=sessionKey", claude))
        assertFalse(WebSessions.hasSession(null, claude))
        val gpt = WebAccount.CHATGPT.sessionCookies
        assertTrue(WebSessions.hasSession("__Secure-next-auth.session-token.0=x; __Secure-next-auth.session-token.1=y", gpt))
        assertEquals(listOf("a", "sessionKey"), WebSessions.cookieNames("a=1; sessionKey=VALUE=WITH=EQUALS"))
    }

    @Test fun linkStateIsHonest() {
        val t1 = "2026-10-30T10:00:00Z"
        val t2 = "2026-10-31T10:00:00Z"
        assertEquals(AiLinkState.NOT_LINKED, WebSessions.state(cookiePresent = false, linkedAt = null, rejectedAt = null))
        assertEquals(AiLinkState.LINKED, WebSessions.state(cookiePresent = true, linkedAt = t1, rejectedAt = null))
        // the cookie disappeared after a sign-in: expired, not "never linked"
        assertEquals(AiLinkState.EXPIRED, WebSessions.state(cookiePresent = false, linkedAt = t1, rejectedAt = null))
        // the page sent the user to sign-in after the link, even with a cookie still there
        assertEquals(AiLinkState.EXPIRED, WebSessions.state(cookiePresent = true, linkedAt = t1, rejectedAt = t2))
        // a newer sign-in clears an older rejection
        assertEquals(AiLinkState.LINKED, WebSessions.state(cookiePresent = true, linkedAt = t2, rejectedAt = t1))
        assertEquals(AiLinkState.NOT_LINKED, WebSessions.state(cookiePresent = false, linkedAt = null, rejectedAt = t1))
    }

    @Test fun signInPagesAreNotTheChat() {
        assertTrue(WebSessions.isSignInPath(WebAccount.CLAUDE, "/login"))
        assertTrue(WebSessions.isSignInPath(WebAccount.CLAUDE, "/magic-link?x=1"))
        assertFalse(WebSessions.isSignInPath(WebAccount.CLAUDE, "/new"))
        assertFalse(WebSessions.isSignInPath(WebAccount.CLAUDE, "/chat/abc"))
        assertTrue(WebSessions.isSignInPath(WebAccount.CHATGPT, "/auth/login"))
        assertFalse(WebSessions.isSignInPath(WebAccount.CHATGPT, "/c/123"))
    }

    @Test fun unlinkExpiresEveryProviderCookieByNameForHostAndDomain() {
        val lines = WebSessions.expiryCookies("sessionKey=SECRET; cf_clearance=x", "claude.ai")
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.contains("Max-Age=0") && !it.contains("SECRET") })
        assertTrue(lines.any { it.startsWith("sessionKey=;") && it.endsWith("Domain=.claude.ai") })
        assertTrue(WebSessions.expiryCookies(null, "claude.ai").isEmpty())
    }

    @Test fun hostsAreMatchedExactlyOrBySubdomain() {
        assertTrue(WebAccount.CLAUDE.matches("claude.ai"))
        assertTrue(WebAccount.CLAUDE.matches("www.claude.ai"))
        assertFalse(WebAccount.CLAUDE.matches("evilclaude.ai"))
        assertFalse(WebAccount.CLAUDE.matches("claude.ai.evil.com"))
        assertTrue(WebAccount.CHATGPT.matches("accounts.google.com", WebAccount.CHATGPT.signInHosts))
        assertFalse(WebAccount.CHATGPT.matches("accounts.google.com"))
    }

    @Test fun theSignedInChatViewKeepsOnlyTheProvidersOwnHosts() {
        for (account in WebAccount.entries) {
            val own = account.hosts.first()
            assertEquals(ChatNavigation.STAY, account.chatNavigation("https", own, "/chat/x", shown = false))
            assertEquals(ChatNavigation.STAY, account.chatNavigation("https", "www.$own", "/", shown = true))
            // Identity and bot-check providers belong to the sign-in view only: from the chat they open outside.
            for (other in listOf("accounts.google.com", "login.live.com", "example.auth0.com", "appleid.apple.com", "evil.example")) {
                assertEquals(other, ChatNavigation.OPEN_OUTSIDE, account.chatNavigation("https", other, "/", shown = true))
            }
            assertEquals(ChatNavigation.OPEN_OUTSIDE, account.chatNavigation("https", "${own}.evil.com", "/", shown = true))
            assertEquals(ChatNavigation.BLOCK, account.chatNavigation("intent", own, "/", shown = true))
            assertEquals(ChatNavigation.BLOCK, account.chatNavigation("javascript", null, null, shown = true))
            // Sign-out never happens through the covered page.
            assertEquals(ChatNavigation.BLOCK, account.chatNavigation("https", own, "/logout", shown = false))
            assertEquals(ChatNavigation.STAY, account.chatNavigation("https", own, "/logout", shown = true))
        }
        // The sign-in view still keeps them.
        assertTrue(WebAccount.CLAUDE.matches("accounts.google.com", WebAccount.CLAUDE.signInHosts))
    }

    @Test fun routeChoiceIsPersistedAndKeepsOlderApiChoices() {
        val fresh = AiSettings(MemoryStore())
        assertEquals(AiRoute.CLAUDE_ACCOUNT, fresh.route)
        val oldOpenAi = AiSettings(MemoryStore().apply { put("provider", "OPENAI") })
        assertEquals(AiRoute.OPENAI_API, oldOpenAi.route)
        val oldAnthropic = AiSettings(MemoryStore().apply { put("provider", "ANTHROPIC") })
        assertEquals(AiRoute.CLAUDE_API, oldAnthropic.route)
        val store = MemoryStore()
        val settings = AiSettings(store)
        settings.route = AiRoute.OPENAI_API
        assertEquals(AiProvider.OPENAI, settings.provider)
        settings.route = AiRoute.CHATGPT_ACCOUNT
        assertEquals(AiRoute.CHATGPT_ACCOUNT, AiSettings(store).route)
        assertEquals(AiProvider.OPENAI, settings.provider)
    }

    @Test fun consentIsPerRouteAndApiRoutesShareTheProviderOptIn() {
        val settings = AiSettings(MemoryStore())
        settings.setOptIn(AiRoute.CLAUDE_ACCOUNT, true)
        assertTrue(settings.optedIn(AiRoute.CLAUDE_ACCOUNT))
        assertFalse(settings.optedIn(AiRoute.CHATGPT_ACCOUNT))
        assertFalse(settings.optedIn(AiRoute.CLAUDE_API))
        settings.setOptIn(AiRoute.CLAUDE_API, true)
        assertTrue(settings.optedIn(AiProvider.ANTHROPIC))
        settings.setOptIn(AiRoute.CLAUDE_ACCOUNT, false)
        assertFalse(settings.optedIn(AiRoute.CLAUDE_ACCOUNT))
        AiRoute.entries.forEach { assertTrue(it.consentText().length > 80) }
        assertTrue(AiRoute.CLAUDE_ACCOUNT.consentText().contains("tocco"))
    }

    @Test fun linkBookkeepingNeverHoldsSecrets() {
        val store = MemoryStore()
        val settings = AiSettings(store)
        settings.markRejected(WebAccount.CLAUDE, "2026-10-30T10:00:00Z")
        settings.markLinked(WebAccount.CLAUDE, "2026-10-31T10:00:00Z")
        assertNull(settings.rejectedAt(WebAccount.CLAUDE))
        assertEquals("2026-10-31T10:00:00Z", settings.linkedAt(WebAccount.CLAUDE))
        settings.clearLink(WebAccount.CLAUDE)
        assertNull(settings.linkedAt(WebAccount.CLAUDE))
    }

    // ---- page snapshots and the reply watcher ----

    private fun snap(users: Int, assistants: Int, busy: Boolean = false, last: String = "", challenge: Boolean = false, loggedOut: Boolean = false, ext: Boolean = false, path: String = "/chat/x") =
        PageSnap("claude", path, ready = true, busy = busy, loggedOut = loggedOut, challenge = challenge, users = users, assistants = assistants,
            messages = listOf(PageMessage(true, "q", false), PageMessage(false, last, ext)))

    @Test fun snapshotsFromThePageAreParsedDefensively() {
        val s = PageSnap.parse("""{"v":1,"site":"claude","path":"/new","ready":true,"busy":false,"loggedOut":false,"challenge":false,"nu":2,"na":1,"msgs":[{"r":"u","t":"ciao"},{"r":"a","t":"risposta","x":true}]}""")!!
        assertEquals(2, s.users)
        assertEquals("risposta", s.lastAssistant!!.text)
        assertTrue(s.lastAssistant!!.external)
        assertNull(PageSnap.parse("non json"))
        assertEquals(0, PageSnap.parse("""{"nu":-5}""")!!.users)
    }

    @Test fun aReplyIsCompleteOnlyAfterDeliveryStreamingAndQuiet() {
        val w = ReplyWatcher(baseUsers = 1, baseAssistants = 1, sentAt = 0)
        assertNull(w.update(snap(1, 1, last = "vecchia risposta"), 1_000))
        assertNull(w.update(snap(2, 1, busy = true, last = "vecchia risposta"), 2_000))
        assertNull(w.update(snap(2, 2, busy = true, last = "Nuo"), 3_000))
        assertNull(w.update(snap(2, 2, busy = false, last = "Nuova risposta"), 4_000))
        assertNull(w.update(snap(2, 2, busy = false, last = "Nuova risposta"), 6_000))
        assertEquals(WebAsk.Reply("Nuova risposta", false), w.update(snap(2, 2, busy = false, last = "Nuova risposta"), 8_100))
    }

    @Test fun theOldReplyIsNeverTakenForTheNewOne() {
        val w = ReplyWatcher(baseUsers = 1, baseAssistants = 1, sentAt = 0)
        w.update(snap(2, 1, last = "vecchia"), 1_000)
        assertNull(w.update(snap(2, 1, last = "vecchia"), 20_000))
    }

    @Test fun anUndeliveredMessageFallsBackToTheHandoff() {
        val w = ReplyWatcher(baseUsers = 1, baseAssistants = 1, sentAt = 0)
        assertNull(w.update(snap(1, 1), 10_000))
        assertTrue(w.update(snap(1, 1), 31_000) is WebAsk.NotDriven)
    }

    @Test fun signInAndBotChecksStopTheWait() {
        assertEquals(WebAsk.NeedsUser, ReplyWatcher(1, 1, 0).update(snap(1, 1, challenge = true), 500))
        assertEquals(WebAsk.NeedsLogin, ReplyWatcher(1, 1, 0).update(snap(1, 1, loggedOut = true), 500))
        assertEquals(WebAsk.NeedsLogin, ReplyWatcher(1, 1, 0).update(snap(1, 1), 500, signInPath = true))
        assertTrue(ReplyWatcher(1, 1, 0).update(snap(2, 1), 500, hung = true) is WebAsk.NotDriven)
    }

    @Test fun anEmptyOrEndlessReplyEndsWithAReason() {
        val empty = ReplyWatcher(1, 1, 0)
        empty.update(snap(2, 2, busy = false, last = ""), 1_000)
        assertTrue(empty.update(snap(2, 2, busy = false, last = ""), 32_000) is WebAsk.Failed)
        val endless = ReplyWatcher(1, 1, 0, totalMs = 60_000)
        endless.update(snap(2, 2, busy = true, last = "parziale"), 1_000)
        val r = endless.update(snap(2, 2, busy = true, last = "parziale"), 61_000)
        assertTrue(r is WebAsk.Reply && r.text.contains("incompleta"))
    }

    @Test fun externalSourcesAreCarriedToTheReply() {
        val w = ReplyWatcher(1, 1, 0)
        w.update(snap(2, 2, last = "con fonti", ext = true), 1_000)
        assertEquals(WebAsk.Reply("con fonti", true), w.update(snap(2, 2, last = "con fonti", ext = true), 6_000))
    }
}
