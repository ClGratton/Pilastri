package xyz.grtlabs.pilastri.sync.gmail

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures.b64
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures.gmailJson
import xyz.grtlabs.pilastri.sync.gmail.GmailFixtures.part

class MailTextTest {
    @Test fun base64UrlWithoutPaddingAndWithUrlSafeCharacters() {
        val text = "Rata n. 2 di 3: € 33,33 >>>??? fine"
        val encoded = b64(text)
        assertTrue("url-safe alphabet, no padding", encoded.contains('-') || encoded.contains('_'))
        assertFalse(encoded.contains('+') || encoded.contains('/') || encoded.contains('='))
        assertEquals(text, String(MailText.decodeBase64Url(encoded)!!, Charsets.UTF_8))
        assertNull(MailText.decodeBase64Url("***not base64***"))
    }

    @Test fun htmlBecomesLinesAndTableRowsKeepTheirCellsTogether() {
        val html = """
            <html><head><style>td{color:red}</style><title>x</title></head><body>
            <p>Il tuo piano&nbsp;di pagamento</p>
            <table><tr><td>Oggi</td><td><b>&euro;</b> 33,34</td></tr>
            <tr><td>5 dic 2026</td><td>&#8364; 33,33</td></tr></table>
            <script>alert(1)</script><!-- nascosto -->
            <div>Totale&nbsp;<span>€100,00</span></div></body></html>
        """.trimIndent()
        val text = MailText.htmlToText(html)
        assertTrue(text, text.contains("Il tuo piano di pagamento"))
        assertTrue(text, text.lines().contains("Oggi € 33,34"))
        assertTrue(text, text.lines().contains("5 dic 2026 € 33,33"))
        assertTrue(text, text.contains("Totale €100,00"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("nascosto"))
        assertFalse(text.contains("color:red"))
    }

    @Test fun mimeWalkPrefersPlainAndSkipsAttachments() {
        val payload = part("multipart/mixed", parts = listOf(
            part("multipart/alternative", parts = listOf(
                part("text/plain", b64("Hai pagato la rata 2 di 3 di € 33,33. Grazie per aver scelto il nostro servizio, a presto."), headers = mapOf("Content-Type" to "text/plain; charset=utf-8")),
                part("text/html", b64("<p>Hai pagato la rata 2 di 3</p>")),
            )),
            part("text/plain", b64("ALLEGATO SEGRETO"), filename = "estratto.txt"),
            part("application/pdf", "AAAA", filename = "ricevuta.pdf"),
        ))
        val ms = Instant.parse("2026-12-05T10:00:00Z").toEpochMilli()
        val msg = MailText.fromGmailJson(gmailJson("abc123", "PayPal <service@paypal.it>", "  Pagamento   ricevuto ", ms, payload))!!
        assertEquals("abc123", msg.id)
        assertEquals("Pagamento ricevuto", msg.subject)
        assertEquals("PayPal <service@paypal.it>", msg.from)
        assertEquals(LocalDate.of(2026, 12, 5), msg.date)
        assertTrue(msg.text.startsWith("Hai pagato la rata 2 di 3 di € 33,33"))
        assertFalse(msg.text.contains("ALLEGATO"))
    }

    @Test fun htmlOnlyMessageIsConverted() {
        val payload = part("multipart/alternative", parts = listOf(part("text/html", b64("<div>Rata 2 di 3</div><div>€ 33,33</div>"))))
        val msg = MailText.fromGmailJson(gmailJson("h1", "x@y.test", "s", 0L, payload).put("internalDate", "1796468400000"))!!
        assertEquals(listOf("Rata 2 di 3", "€ 33,33"), msg.text.lines())
    }

    @Test fun charsetFromThePartHeaderIsHonoured() {
        val latin = b64("Pagamento di 33,33 euro effettuato così", Charsets.ISO_8859_1)
        val payload = part("text/plain", latin, headers = mapOf("Content-Type" to "text/plain; charset=\"iso-8859-1\""))
        val msg = MailText.fromGmailJson(gmailJson("c1", "x@y.test", "s", 1796468400000L, payload))!!
        assertTrue(msg.text, msg.text.endsWith("così"))
    }

    @Test fun theDateIsTheRomeCalendarDayOfTheInternalDate() {
        // 23:30 UTC on 5 Nov is 00:30 on 6 Nov in Rome (CET, UTC+1).
        val ms = Instant.parse("2026-11-05T23:30:00Z").toEpochMilli()
        val msg = MailText.fromGmailJson(gmailJson("d1", "x@y.test", "s", ms, part("text/plain", b64("ciao"))))!!
        assertEquals(LocalDate.of(2026, 11, 6), msg.date)
    }

    @Test fun textIsBoundedAndBrokenMessagesAreNull() {
        val long = "riga di prova con una rata da 10 euro\n".repeat(2_000)
        val msg = MailText.fromGmailJson(gmailJson("l1", "x@y.test", "s", 1796468400000L, part("text/plain", b64(long))))!!
        assertTrue(msg.text.length <= MailText.MAX_CHARS)
        assertNull(MailText.fromGmailJson(org.json.JSONObject().put("id", "x")))
        assertNull(MailText.fromGmailJson(org.json.JSONObject().put("payload", org.json.JSONObject())))
        assertNotNull(MailText.fromGmailJson(gmailJson("e1", "", "", 1796468400000L, part("text/plain"))))
    }
}
