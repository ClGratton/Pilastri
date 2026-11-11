package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic messages only. */
class EbMagicLinkTest {
    private val day = LocalDate.of(2026, 11, 9)
    private fun mail(id: String, from: String, text: String) = MailMessage(id, from, "Sign in", day, text)
    private val good = "https://enablebanking.com/auth/magic?token=synthetic123"

    private class Source(val mails: Map<String, MailMessage>, val appearAt: Map<String, Int> = emptyMap()) : MailSource {
        var polls = 0
        override suspend fun list(query: String, pageToken: String?, pageSize: Int): MailPage {
            polls++
            return MailPage(mails.keys.filter { (appearAt[it] ?: 0) <= polls }, null)
        }
        override suspend fun fetch(id: String) = mails[id]
    }

    @Test fun extractsTheSignInLinkAndNothingElse() {
        assertEquals(good, EbMagicLink.extract(mail("1", "Enable Banking <no-reply@enablebanking.com>", "Ciao,\nclicca: $good.\nDisiscriviti https://enablebanking.com/unsubscribe/x")))
        assertNull(EbMagicLink.extract(mail("2", "someone@example.com", good)))
        assertNull(EbMagicLink.extract(mail("3", "no-reply@enablebanking.com", "https://evil.example/enablebanking.com/auth http://enablebanking.com/auth")))
        assertNull(EbMagicLink.extract(mail("4", "no-reply@enablebanking.com", "nessun link")))
    }

    @Test fun findsALinkThatOnlyExistsAsAnHrefTarget() {
        val from = "Enable Banking <no-reply@enablebanking.com>"
        val html = """<p>Ciao</p><a href="https://enablebanking.com/auth/magic?token=abc&amp;lang=it">Accedi</a><a href='https://enablebanking.com/unsubscribe/1'>Esci</a>"""
        assertEquals("https://enablebanking.com/auth/magic?token=abc&lang=it", EbMagicLink.extract(MailMessage("h1", from, "Sign in", day, "Ciao Accedi Esci", html)))
        // the same through the real Gmail JSON path: the HTML part reaches the extractor
        val data = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(html.toByteArray())
        val json = org.json.JSONObject("""{"id":"h2","internalDate":"1762700000000","payload":{"mimeType":"multipart/alternative","headers":[{"name":"From","value":"$from"},{"name":"Subject","value":"Sign in"}],"parts":[{"mimeType":"text/html","body":{"data":"$data"}}]}}""")
        assertEquals("https://enablebanking.com/auth/magic?token=abc&lang=it", EbMagicLink.extract(MailText.fromGmailJson(json)!!))
    }

    @Test fun anHrefToAnotherHostIsIgnored() {
        val html = """<a href="https://evil.example/enablebanking.com/auth">Accedi</a><a href="http://enablebanking.com/auth">x</a>"""
        assertNull(EbMagicLink.extract(MailMessage("h3", "no-reply@enablebanking.com", "Sign in", day, "Accedi", html)))
    }

    @Test fun onlyAGmailAddressPrefillsTheForm() {
        assertEquals(true, EbMagicLink.isGmailAddress("synthetic@gmail.com"))
        assertEquals(true, EbMagicLink.isGmailAddress("x@googlemail.com"))
        assertEquals(false, EbMagicLink.isGmailAddress("x@example.com"))
        assertEquals(false, EbMagicLink.isGmailAddress("x@y@gmail.com"))
    }

    @Test fun queryCarriesTheTimeWindow() = assertEquals("from:enablebanking.com after:1762700000", EbMagicLink.query(1_762_700_000))

    @Test fun ignoresOlderMailsAndWaitsForTheNewOne() = runBlocking {
        val old = mail("old", "no-reply@enablebanking.com", "https://enablebanking.com/auth?token=OLD")
        val new = mail("new", "no-reply@enablebanking.com", good)
        val src = Source(mapOf("old" to old, "new" to new), appearAt = mapOf("new" to 3))
        var now = 0L
        val link = EbMagicLinkPoller(src, { now }, { now += it }).find(1, ignore = setOf("old"))
        assertEquals(good, link)
        assertEquals(3, src.polls)
    }

    @Test fun givesUpAfterTheWindow() = runBlocking {
        val src = Source(emptyMap())
        var now = 0L
        assertNull(EbMagicLinkPoller(src, { now }, { now += it }, windowMs = 120_000, everyMs = 5_000).find(1))
        assertEquals(true, src.polls in 20..30 && now <= 120_000)
    }

    @Test fun aFailingSourceEndsQuietly() = runBlocking {
        val src = object : MailSource {
            override suspend fun list(query: String, pageToken: String?, pageSize: Int): MailPage = error("x")
            override suspend fun fetch(id: String) = null
        }
        assertNull(EbMagicLinkPoller(src).find(1))
    }
}
